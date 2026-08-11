package com.datacube.provider.postgres;

import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.TableDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Safely renders selected PostgreSQL schema changes from structured snapshot data. */
public final class PgSchemaChangeRenderer implements SchemaChangeRenderer {
    static final String WRONG_DATABASE = "PostgreSQL renderer requires a PostgreSQL context";
    static final String MANUAL_CHANGE = "Schema change requires manual execution";
    static final String DESTRUCTIVE_APPROVAL =
            "Destructive schema change requires explicit approval";
    static final String UNSUPPORTED_SHAPE = "Schema change shape is unsupported";
    static final String UNSAFE_DEFINITION = "Schema definition cannot be retargeted safely";
    static final String DESTRUCTIVE_WARNING = "Destructive PostgreSQL schema change";

    private static final String OBJECT_KEY_DOMAIN = "pg-object-v1\0";
    private static final String SCHEMA_KEY_DOMAIN = "pg-schema-v1\0";
    private static final String CHILD_KEY_DOMAIN = "pg-child-v1\0";
    private static final Map<String, String> PG_TYPE_ALIASES = Map.ofEntries(
            Map.entry("smallint", "int2"), Map.entry("int2", "int2"),
            Map.entry("integer", "int4"), Map.entry("int", "int4"), Map.entry("int4", "int4"),
            Map.entry("bigint", "int8"), Map.entry("int8", "int8"),
            Map.entry("real", "float4"), Map.entry("float4", "float4"),
            Map.entry("doubleprecision", "float8"), Map.entry("float8", "float8"),
            Map.entry("boolean", "bool"), Map.entry("bool", "bool"),
            Map.entry("character", "bpchar"), Map.entry("char", "bpchar"),
            Map.entry("bpchar", "bpchar"), Map.entry("charactervarying", "varchar"),
            Map.entry("varchar", "varchar"), Map.entry("decimal", "numeric"),
            Map.entry("numeric", "numeric"), Map.entry("text", "text"),
            Map.entry("timestampwithtimezone", "timestamptz"),
            Map.entry("timestamptz", "timestamptz"),
            Map.entry("timestampwithouttimezone", "timestamp"), Map.entry("timestamp", "timestamp"),
            Map.entry("timewithtimezone", "timetz"), Map.entry("timetz", "timetz"),
            Map.entry("timewithouttimezone", "time"), Map.entry("time", "time"),
            Map.entry("bitvarying", "varbit"), Map.entry("varbit", "varbit"),
            Map.entry("bit", "bit"));

    @Override
    public List<RenderedStatement> render(SchemaChange change, RenderContext context) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(context, "context");
        if (context.databaseType() != DbType.POSTGRESQL) {
            throw new IllegalArgumentException(WRONG_DATABASE);
        }
        if (change.kind() == ChangeKind.MANUAL
                || change.automation() == AutomationLevel.MANUAL_ONLY) {
            throw new IllegalArgumentException(MANUAL_CHANGE);
        }
        validateShape(change);
        boolean destructive = change.automation() == AutomationLevel.DESTRUCTIVE_OPT_IN
                || hasDestructiveSemantics(change);
        if (destructive && !context.destructiveApproved()) {
            throw new IllegalArgumentException(DESTRUCTIVE_APPROVAL);
        }

        List<String> sql = switch (change.kind()) {
            case CREATE -> renderCreate(change.source(), context);
            case DROP -> List.of(renderDrop(change, context));
            case ALTER -> renderAlter(change, context);
            case REPLACE -> renderReplace(change, context);
            case MANUAL -> throw new IllegalArgumentException(MANUAL_CHANGE);
        };
        String warning = destructive ? DESTRUCTIVE_WARNING : null;
        return sql.stream().map(statement -> new RenderedStatement(
                change.id(), statement, destructive, change.dependencyChangeIds(), warning)).toList();
    }

    private static boolean hasDestructiveSemantics(SchemaChange change) {
        if (change.kind() == ChangeKind.DROP || change.kind() == ChangeKind.REPLACE) return true;
        if (change.kind() != ChangeKind.ALTER || change.property() == null) return false;
        Object sourceValue = change.property().sourceValue();
        Object targetValue = change.property().targetValue();
        if (sourceValue == null && targetValue instanceof ColumnDefinition) return true;
        if ((change.property().path().equals("constraints")
                || change.property().path().equals("indexes"))
                && sourceValue instanceof List<?> source
                && targetValue instanceof List<?> target) {
            return target.stream().anyMatch(value -> !source.contains(value));
        }
        if (change.property().path().endsWith(".dataType")
                || change.property().path().endsWith(".normalizedDefault")) return true;
        if (change.property().path().endsWith(".nullable")) {
            return Boolean.FALSE.equals(sourceValue) && Boolean.TRUE.equals(targetValue);
        }
        return change.source() instanceof SequenceDefinition;
    }

    private static void validateShape(SchemaChange change) {
        switch (change.kind()) {
            case CREATE -> requireShape(change.source(), change.target(), true, change.object());
            case DROP -> requireShape(change.target(), change.source(), true, change.object());
            case ALTER, REPLACE -> {
                if (change.source() == null || change.target() == null
                        || !change.object().equals(change.source().key())
                        || !change.object().equals(change.target().key())) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
            }
            case MANUAL -> {
                // Rejected before shape validation.
            }
        }
        rejectLowConfidence(change.source());
        rejectLowConfidence(change.target());
    }

    private static void requireShape(
            SchemaObject present, SchemaObject absent, boolean absentRequired, ObjectKey key) {
        if (present == null || absentRequired && absent != null || !key.equals(present.key())) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static void rejectLowConfidence(SchemaObject object) {
        if (object instanceof DefinitionObject definition
                && definition.confidence() == DefinitionConfidence.LOW) {
            throw new IllegalArgumentException(MANUAL_CHANGE);
        }
    }

    private static List<String> renderCreate(SchemaObject source, RenderContext context) {
        if (source instanceof SequenceDefinition sequence) {
            return List.of(createSequence(sequence, context));
        }
        if (source instanceof TableDefinition table) {
            return createTable(table, context);
        }
        if (source instanceof DefinitionObject definition) {
            return List.of(renderDefinition(definition, context, false));
        }
        throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
    }

    private static List<String> renderReplace(SchemaChange change, RenderContext context) {
        if (!(change.source() instanceof DefinitionObject definition)
                || !(change.target() instanceof DefinitionObject)
                || !supportsReplace(change.object().type())) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return List.of(renderDefinition(definition, context, true));
    }

    private static boolean supportsReplace(ObjectType type) {
        return type == ObjectType.VIEW || type == ObjectType.FUNCTION
                || type == ObjectType.PROCEDURE;
    }

    private static String renderDefinition(
            DefinitionObject definition, RenderContext context, boolean replace) {
        String original = definition.originalDefinition();
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        String normalized = PgSchemaDefinitionNormalizer.normalize(original);
        if (normalized.indexOf('\0') >= 0 || hasTopLevelSemicolon(normalized)
                || hasTrailingLineComment(normalized)) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        if ((definition.key().type() == ObjectType.FUNCTION
                || definition.key().type() == ObjectType.PROCEDURE)
                && !routineIdentityMatches(normalized, definition.key(), context)) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        String retargeted = retargetDefinition(normalized, definition.key().type(), context);
        if (definition.key().type() == ObjectType.TRIGGER) {
            ObjectKey owner = triggerOwner(definition);
            int triggerHeaderEnd = triggerHeaderEnd(retargeted, definition.key());
            if (triggerHeaderEnd < 0
                    || !triggerOwnerMatches(retargeted, triggerHeaderEnd, owner, context)) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
        } else if (definition.key().type() == ObjectType.FUNCTION
                || definition.key().type() == ObjectType.PROCEDURE) {
            RoutineHeader header = routineHeaderAt(retargeted, definition.key(),
                    schemaPart(context.targetSchema()));
            if (header == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
            if (replace && !header.replace()) {
                retargeted = retargeted.substring(0, header.createEnd()) + " OR REPLACE"
                        + retargeted.substring(header.createEnd());
            }
        } else {
            String header = definitionHeader(definition.key(), context);
            if (!matchesCreateHeader(retargeted, definition.key().type(), header)) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            if (replace) retargeted = ensureCreateOrReplace(retargeted, definition.key().type(), header);
        }
        return retargeted.stripTrailing() + ';';
    }

    private static int triggerHeaderEnd(String definition, ObjectKey key) {
        int createEnd = keywordEnd(definition, 0, "CREATE");
        if (createEnd < 0) return -1;
        int constraintEnd = keywordEnd(definition, createEnd, "CONSTRAINT");
        int triggerEnd = keywordEnd(definition,
                constraintEnd < 0 ? createEnd : constraintEnd, "TRIGGER");
        if (triggerEnd < 0) return -1;
        SqlIdentifier name = sqlIdentifierAt(definition, skipTrivia(definition, triggerEnd));
        return name != null && sqlIdentifierMatches(name, objectPart(key), false)
                ? name.end() : -1;
    }

    private static boolean triggerOwnerMatches(
            String definition, int start, ObjectKey owner, RenderContext context) {
        int index = start;
        while (index < definition.length()) {
            char current = definition.charAt(index);
            if (current == '\'') {
                index = skipSingleQuoted(definition, index);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(definition, index);
                if (identifier == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index = identifier.end();
            } else if (current == '-' && charAt(definition, index + 1) == '-') {
                index = skipLineComment(definition, index);
            } else if (current == '/' && charAt(definition, index + 1) == '*') {
                index = skipBlockComment(definition, index);
            } else if (current == '$') {
                String tag = dollarTagAt(definition, index);
                if (tag == null) {
                    index++;
                } else {
                    int end = definition.indexOf(tag, index + tag.length());
                    if (end < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                    index = end + tag.length();
                }
            } else if (identifierStart(current)) {
                int end = index + 1;
                while (end < definition.length() && identifierPart(definition.charAt(end))) end++;
                String token = definition.substring(index, end);
                if (token.equalsIgnoreCase("ON")) {
                    QualifiedSqlName candidate = qualifiedSqlNameAt(definition, end);
                    if (candidate != null) {
                        return sqlIdentifierMatches(candidate.schema(), schemaPart(context.targetSchema()), true)
                                && sqlIdentifierMatches(candidate.object(), objectPart(owner), false);
                    }
                }
                index = end;
            } else {
                index++;
            }
        }
        return false;
    }

    private static QualifiedSqlName qualifiedSqlNameAt(String text, int start) {
        int index = skipTrivia(text, start);
        SqlIdentifier schema = sqlIdentifierAt(text, index);
        if (schema == null) return null;
        index = skipTrivia(text, schema.end());
        if (charAt(text, index) != '.') return null;
        index = skipTrivia(text, index + 1);
        SqlIdentifier object = sqlIdentifierAt(text, index);
        return object == null ? null : new QualifiedSqlName(schema, object);
    }

    private static SqlIdentifier sqlIdentifierAt(String text, int start) {
        if (start >= text.length()) return null;
        if (text.charAt(start) == '"') {
            QuotedIdentifier quoted = quotedIdentifierAt(text, start);
            if (quoted == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
            return new SqlIdentifier(quoted.value(), true, quoted.end());
        }
        if (!identifierStart(text.charAt(start))) return null;
        int end = start + 1;
        while (end < text.length() && identifierPart(text.charAt(end))) end++;
        return new SqlIdentifier(text.substring(start, end), false, end);
    }

    private static int skipTrivia(String text, int start) {
        int index = start;
        while (index < text.length()) {
            if (Character.isWhitespace(text.charAt(index))) {
                index++;
            } else if (charAt(text, index) == '-' && charAt(text, index + 1) == '-') {
                index = skipLineComment(text, index);
            } else if (charAt(text, index) == '/' && charAt(text, index + 1) == '*') {
                index = skipBlockComment(text, index);
            } else {
                return index;
            }
        }
        return index;
    }

    private static boolean sqlIdentifierMatches(
            SqlIdentifier token, String expected, boolean schema) {
        if (token.quoted()) return token.value().equals(expected);
        boolean expectedRequiresQuoting = schema
                ? PgSchemaIdentifierNormalizer.schema(expected).quoted()
                : PgSchemaIdentifierNormalizer.child(expected).quoted();
        return !expectedRequiresQuoting
                && token.value().equalsIgnoreCase(expected);
    }

    private record SqlIdentifier(String value, boolean quoted, int end) {
    }

    private record QualifiedSqlName(SqlIdentifier schema, SqlIdentifier object) {
    }

    private static boolean routineIdentityMatches(
            String definition, ObjectKey key, RenderContext context) {
        RoutineHeader header = routineHeaderAt(
                definition, key, schemaPart(context.sourceSchema()));
        if (header == null) return false;
        int argumentsEnd = matchingParenthesis(definition, header.argumentsStart() - 1);
        if (argumentsEnd < 0) return false;
        List<String> expected = splitSqlList(key.signature());
        List<String> declarations = splitSqlList(
                definition.substring(header.argumentsStart(), argumentsEnd));
        int expectedIndex = 0;
        for (String declaration : declarations) {
            RoutineArgument argument = routineArgument(declaration);
            if (argument == null) return false;
            if (argument.outOnly()) continue;
            if (expectedIndex >= expected.size()
                    || !argumentTypeMatches(argument.declaration(), expected.get(expectedIndex))) {
                return false;
            }
            expectedIndex++;
        }
        return expectedIndex == expected.size();
    }

    private static RoutineHeader routineHeaderAt(
            String definition, ObjectKey key, String expectedSchema) {
        int createEnd = keywordEnd(definition, 0, "CREATE");
        if (createEnd < 0) return null;
        boolean replace = false;
        int nounStart = createEnd;
        int orEnd = keywordEnd(definition, createEnd, "OR");
        if (orEnd >= 0) {
            int replaceEnd = keywordEnd(definition, orEnd, "REPLACE");
            if (replaceEnd < 0) return null;
            replace = true;
            nounStart = replaceEnd;
        }
        String noun = key.type() == ObjectType.FUNCTION ? "FUNCTION" : "PROCEDURE";
        int nounEnd = keywordEnd(definition, nounStart, noun);
        if (nounEnd < 0) return null;
        QualifiedSqlName name = qualifiedSqlNameAt(definition, nounEnd);
        if (name == null
                || !sqlIdentifierMatches(name.schema(), expectedSchema, true)
                || !sqlIdentifierMatches(name.object(), objectPart(key), false)) {
            return null;
        }
        int open = skipTrivia(definition, name.object().end());
        return charAt(definition, open) == '('
                ? new RoutineHeader(open + 1, createEnd, replace) : null;
    }

    private static int keywordEnd(String text, int start, String keyword) {
        int index = skipTrivia(text, start);
        return keywordAt(text, index, keyword) ? index + keyword.length() : -1;
    }

    private record RoutineHeader(int argumentsStart, int createEnd, boolean replace) {
    }

    private static int matchingParenthesis(String text, int openIndex) {
        int depth = 1;
        int index = openIndex + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'') {
                index = skipSingleQuoted(text, index);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(text, index);
                if (identifier == null) return -1;
                index = identifier.end();
            } else if (current == '-' && charAt(text, index + 1) == '-') {
                index = skipLineComment(text, index);
            } else if (current == '/' && charAt(text, index + 1) == '*') {
                index = skipBlockComment(text, index);
            } else if (current == '$') {
                String tag = dollarTagAt(text, index);
                if (tag == null) {
                    index++;
                } else {
                    int end = text.indexOf(tag, index + tag.length());
                    if (end < 0) return -1;
                    index = end + tag.length();
                }
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (--depth == 0) return index;
                index++;
            } else {
                index++;
            }
        }
        return -1;
    }

    private static List<String> splitSqlList(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'') {
                index = skipSingleQuoted(text, index);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(text, index);
                if (identifier == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index = identifier.end();
            } else if (current == '-' && charAt(text, index + 1) == '-') {
                index = skipLineComment(text, index);
            } else if (current == '/' && charAt(text, index + 1) == '*') {
                index = skipBlockComment(text, index);
            } else if (current == '$') {
                String tag = dollarTagAt(text, index);
                if (tag == null) {
                    index++;
                } else {
                    int end = text.indexOf(tag, index + tag.length());
                    if (end < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                    index = end + tag.length();
                }
            } else if (current == '(' || current == '[') {
                depth++;
                index++;
            } else if (current == ')' || current == ']') {
                if (--depth < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index++;
            } else if (current == ',' && depth == 0) {
                String part = text.substring(start, index).strip();
                if (part.isEmpty()) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                parts.add(part);
                start = ++index;
            } else {
                index++;
            }
        }
        if (depth != 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        String part = text.substring(start).strip();
        if (part.isEmpty()) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        parts.add(part);
        return List.copyOf(parts);
    }

    private static RoutineArgument routineArgument(String declaration) {
        String withoutDefault = beforeTopLevelDefault(declaration);
        if (withoutDefault == null || withoutDefault.isBlank()) return null;
        String remaining = withoutDefault.strip();
        String upper = remaining.toUpperCase(java.util.Locale.ROOT);
        boolean outOnly = false;
        if (upper.startsWith("IN OUT ")) {
            remaining = remaining.substring(7).stripLeading();
        } else if (upper.startsWith("INOUT ")) {
            remaining = remaining.substring(6).stripLeading();
        } else if (upper.startsWith("VARIADIC ")) {
            remaining = remaining.substring(9).stripLeading();
        } else if (upper.startsWith("IN ")) {
            remaining = remaining.substring(3).stripLeading();
        } else if (upper.startsWith("OUT ")) {
            remaining = remaining.substring(4).stripLeading();
            outOnly = true;
        }
        if (remaining.isBlank()) return null;
        return new RoutineArgument(remaining, outOnly);
    }

    private static String beforeTopLevelDefault(String declaration) {
        int depth = 0;
        int index = 0;
        while (index < declaration.length()) {
            char current = declaration.charAt(index);
            if (current == '\'') {
                index = skipSingleQuoted(declaration, index);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(declaration, index);
                if (identifier == null) return null;
                index = identifier.end();
            } else if (current == '(' || current == '[') {
                depth++;
                index++;
            } else if (current == ')' || current == ']') {
                if (--depth < 0) return null;
                index++;
            } else if (depth == 0 && current == '=') {
                return declaration.substring(0, index).stripTrailing();
            } else if (depth == 0 && keywordAt(declaration, index, "DEFAULT")) {
                return declaration.substring(0, index).stripTrailing();
            } else {
                index++;
            }
        }
        return depth == 0 ? declaration : null;
    }

    private static boolean keywordAt(String text, int index, String keyword) {
        if (!text.regionMatches(true, index, keyword, 0, keyword.length())) return false;
        int before = index - 1;
        int after = index + keyword.length();
        return (before < 0 || !identifierPart(text.charAt(before)))
                && (after >= text.length() || !identifierPart(text.charAt(after)));
    }

    private static boolean argumentTypeMatches(String declaration, String expected) {
        String expectedType = canonicalIdentityType(expected);
        try {
            if (canonicalIdentityType(declaration).equals(expectedType)) return true;
        } catch (IllegalArgumentException ignored) {
            // A named argument is not itself a valid type; verify the declaration without its name.
        }
        String withoutName = withoutLeadingArgumentName(declaration);
        return withoutName != null && canonicalIdentityType(withoutName).equals(expectedType);
    }

    private static String withoutLeadingArgumentName(String declaration) {
        int end;
        if (declaration.startsWith("\"")) {
            QuotedIdentifier identifier = quotedIdentifierAt(declaration, 0);
            if (identifier == null) return null;
            end = identifier.end();
        } else {
            if (declaration.isEmpty() || !identifierStart(declaration.charAt(0))) return null;
            end = 1;
            while (end < declaration.length() && identifierPart(declaration.charAt(end))) end++;
        }
        if (end >= declaration.length() || !Character.isWhitespace(declaration.charAt(end))) return null;
        return declaration.substring(end).stripLeading();
    }

    private static String canonicalIdentityType(String type) {
        StringBuilder canonical = new StringBuilder(type.length());
        boolean quoted = false;
        for (int index = 0; index < type.length(); index++) {
            char current = type.charAt(index);
            if (quoted) {
                canonical.append(current);
                if (current == '"') {
                    if (index + 1 < type.length() && type.charAt(index + 1) == '"') {
                        canonical.append(type.charAt(++index));
                    } else {
                        quoted = false;
                    }
                }
            } else if (current == '"') {
                quoted = true;
                canonical.append(current);
            } else if (!Character.isWhitespace(current)) {
                canonical.append(Character.toLowerCase(current));
            }
        }
        if (quoted) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        String value = canonical.toString();
        int suffixStart = identityTypeSuffixStart(value);
        if (suffixStart < 0) suffixStart = value.length();
        String base = value.substring(0, suffixStart);
        return canonicalIdentityBase(base) + value.substring(suffixStart);
    }

    private static int identityTypeSuffixStart(String type) {
        boolean quoted = false;
        for (int index = 0; index < type.length(); index++) {
            char current = type.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < type.length() && type.charAt(index + 1) == '"') index++;
                    else quoted = false;
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == '(' || current == '[') {
                return index;
            }
        }
        if (quoted) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        return -1;
    }

    private static String canonicalIdentityBase(String base) {
        SqlIdentifier first = sqlIdentifierAt(base, 0);
        if (first == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        if (first.end() == base.length()) {
            String identifier = canonicalIdentifier(first);
            String alias = first.quoted() && !PG_TYPE_ALIASES.containsValue(identifier)
                    ? null : PG_TYPE_ALIASES.get(identifier);
            return alias == null ? "type\0" + identifier : "pg_catalog\0" + alias;
        }
        if (charAt(base, first.end()) != '.') {
            String alias = PG_TYPE_ALIASES.get(base);
            if (alias == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
            return "pg_catalog\0" + alias;
        }
        SqlIdentifier second = sqlIdentifierAt(base, first.end() + 1);
        if (second == null || second.end() != base.length()) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        String schema = canonicalIdentifier(first);
        String name = canonicalIdentifier(second);
        if (schema.equals("pg_catalog")) {
            String alias = second.quoted() && !PG_TYPE_ALIASES.containsValue(name)
                    ? null : PG_TYPE_ALIASES.get(name);
            return "pg_catalog\0" + (alias == null ? name : alias);
        }
        return "qualified\0" + schema + '\0' + name;
    }

    private static String canonicalIdentifier(SqlIdentifier identifier) {
        return identifier.quoted() ? identifier.value()
                : identifier.value().toLowerCase(java.util.Locale.ROOT);
    }

    private record RoutineArgument(String declaration, boolean outOnly) {
    }

    private static boolean hasTopLevelSemicolon(String text) {
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'') {
                index = skipSingleQuoted(text, index);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(text, index);
                if (identifier == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index = identifier.end();
            } else if (current == '-' && charAt(text, index + 1) == '-') {
                index = skipLineComment(text, index);
            } else if (current == '/' && charAt(text, index + 1) == '*') {
                index = skipBlockComment(text, index);
            } else if (current == '$') {
                String tag = dollarTagAt(text, index);
                if (tag == null) {
                    index++;
                } else {
                    int end = text.indexOf(tag, index + tag.length());
                    if (end < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                    index = end + tag.length();
                }
            } else if (current == ';') {
                return true;
            } else {
                index++;
            }
        }
        return false;
    }

    private static boolean hasTrailingLineComment(String text) {
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'') {
                index = skipSingleQuoted(text, index);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(text, index);
                if (identifier == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index = identifier.end();
            } else if (current == '-' && charAt(text, index + 1) == '-') {
                int newline = text.indexOf('\n', index + 2);
                if (newline < 0) return true;
                index = newline + 1;
            } else if (current == '/' && charAt(text, index + 1) == '*') {
                index = skipBlockComment(text, index);
            } else if (current == '$') {
                String tag = dollarTagAt(text, index);
                if (tag == null) {
                    index++;
                } else {
                    int end = text.indexOf(tag, index + tag.length());
                    if (end < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                    index = end + tag.length();
                }
            } else {
                index++;
            }
        }
        return false;
    }

    private static String definitionHeader(ObjectKey key, RenderContext context) {
        return switch (key.type()) {
            case VIEW -> "VIEW " + targetName(key, context);
            case MATERIALIZED_VIEW -> "MATERIALIZED VIEW " + targetName(key, context);
            case FUNCTION -> "FUNCTION " + targetName(key, context);
            case PROCEDURE -> "PROCEDURE " + targetName(key, context);
            case TYPE -> "TYPE " + targetName(key, context);
            default -> throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        };
    }

    private static boolean matchesCreateHeader(String definition, ObjectType type, String header) {
        return definition.startsWith("CREATE " + header + headerBoundary(type))
                || supportsReplace(type)
                && definition.startsWith("CREATE OR REPLACE " + header + headerBoundary(type));
    }

    private static String headerBoundary(ObjectType type) {
        return type == ObjectType.FUNCTION || type == ObjectType.PROCEDURE ? "(" : " ";
    }

    private static String ensureCreateOrReplace(
            String definition, ObjectType type, String header) {
        String replaceHeader = "CREATE OR REPLACE " + header + headerBoundary(type);
        if (definition.startsWith(replaceHeader)) return definition;
        String createHeader = "CREATE " + header + headerBoundary(type);
        if (!definition.startsWith(createHeader)) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        return "CREATE OR REPLACE " + definition.substring("CREATE ".length());
    }

    private static String retargetDefinition(
            String definition, ObjectType type, RenderContext context) {
        String source = schemaPart(context.sourceSchema());
        String target = schemaPart(context.targetSchema());
        StringBuilder output = new StringBuilder(definition.length());
        int index = 0;
        while (index < definition.length()) {
            char current = definition.charAt(index);
            if (current == '\'') {
                index = copySingleQuoted(definition, index, output);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(definition, index);
                if (identifier == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                if (sqlIdentifierMatches(
                        new SqlIdentifier(identifier.value(), true, identifier.end()), source, true)
                        && qualifiedDotAt(definition, identifier.end())) {
                    output.append(PgSchemaIdentifierNormalizer.quote(target));
                } else {
                    output.append(definition, index, identifier.end());
                }
                index = identifier.end();
            } else if (current == '-' && charAt(definition, index + 1) == '-') {
                index = copyLineComment(definition, index, output);
            } else if (current == '/' && charAt(definition, index + 1) == '*') {
                index = copyBlockComment(definition, index, output);
            } else if (current == '$') {
                String tag = dollarTagAt(definition, index);
                if (tag == null) {
                    output.append(current);
                    index++;
                } else {
                    int end = definition.indexOf(tag, index + tag.length());
                    if (end < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                    String body = definition.substring(index + tag.length(), end);
                    if ((type == ObjectType.FUNCTION || type == ObjectType.PROCEDURE)
                            && containsQualifiedIdentifier(body, source)) {
                        throw new IllegalArgumentException(UNSAFE_DEFINITION);
                    }
                    int after = end + tag.length();
                    output.append(definition, index, after);
                    index = after;
                }
            } else if (identifierStart(current)) {
                int end = index + 1;
                while (end < definition.length() && identifierPart(definition.charAt(end))) end++;
                String identifier = definition.substring(index, end);
                if (sqlIdentifierMatches(new SqlIdentifier(identifier, false, end), source, true)
                        && qualifiedDotAt(definition, end)) {
                    output.append(PgSchemaIdentifierNormalizer.quote(target));
                } else {
                    output.append(identifier);
                }
                index = end;
            } else {
                output.append(current);
                index++;
            }
        }
        return output.toString();
    }

    private static boolean containsQualifiedIdentifier(String text, String schema) {
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'') {
                index = skipSingleQuoted(text, index);
            } else if (current == '"') {
                QuotedIdentifier identifier = quotedIdentifierAt(text, index);
                if (identifier == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                if (sqlIdentifierMatches(
                        new SqlIdentifier(identifier.value(), true, identifier.end()), schema, true)
                        && qualifiedDotAt(text, identifier.end())) {
                    return true;
                }
                index = identifier.end();
            } else if (current == '-' && charAt(text, index + 1) == '-') {
                index = skipLineComment(text, index);
            } else if (current == '/' && charAt(text, index + 1) == '*') {
                index = skipBlockComment(text, index);
            } else if (current == '$') {
                String tag = dollarTagAt(text, index);
                if (tag == null) {
                    index++;
                } else {
                    int end = text.indexOf(tag, index + tag.length());
                    if (end < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                    index = end + tag.length();
                }
            } else if (identifierStart(current)) {
                int end = index + 1;
                while (end < text.length() && identifierPart(text.charAt(end))) end++;
                if (sqlIdentifierMatches(
                        new SqlIdentifier(text.substring(index, end), false, end), schema, true)
                        && qualifiedDotAt(text, end)) {
                    return true;
                }
                index = end;
            } else {
                index++;
            }
        }
        return false;
    }

    private static boolean qualifiedDotAt(String text, int start) {
        return charAt(text, skipTrivia(text, start)) == '.';
    }

    private static int skipSingleQuoted(String text, int start) {
        return copySingleQuoted(text, start, new StringBuilder());
    }

    private static int skipLineComment(String text, int start) {
        int end = text.indexOf('\n', start + 2);
        return end < 0 ? text.length() : end + 1;
    }

    private static int skipBlockComment(String text, int start) {
        return copyBlockComment(text, start, new StringBuilder());
    }

    private static int copySingleQuoted(String text, int start, StringBuilder output) {
        boolean escapeString = hasEscapeStringPrefix(text, start);
        int index = start;
        output.append(text.charAt(index++));
        while (index < text.length()) {
            char current = text.charAt(index);
            output.append(current);
            index++;
            if (escapeString && current == '\\' && index < text.length()) {
                output.append(text.charAt(index++));
            } else if (current == '\'') {
                if (index < text.length() && text.charAt(index) == '\'') {
                    output.append(text.charAt(index++));
                } else {
                    return index;
                }
            }
        }
        throw new IllegalArgumentException(UNSAFE_DEFINITION);
    }

    private static boolean hasEscapeStringPrefix(String text, int quoteIndex) {
        if (quoteIndex < 1) return false;
        char prefix = text.charAt(quoteIndex - 1);
        if (prefix != 'E' && prefix != 'e') return false;
        return quoteIndex == 1 || !identifierPart(text.charAt(quoteIndex - 2));
    }

    private static int copyLineComment(String text, int start, StringBuilder output) {
        int end = text.indexOf('\n', start + 2);
        if (end < 0) end = text.length(); else end++;
        output.append(text, start, end);
        return end;
    }

    private static int copyBlockComment(String text, int start, StringBuilder output) {
        int depth = 1;
        int index = start + 2;
        while (index < text.length() && depth > 0) {
            if (charAt(text, index) == '/' && charAt(text, index + 1) == '*') {
                depth++;
                index += 2;
            } else if (charAt(text, index) == '*' && charAt(text, index + 1) == '/') {
                depth--;
                index += 2;
            } else {
                index++;
            }
        }
        if (depth != 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        output.append(text, start, index);
        return index;
    }

    private static QuotedIdentifier quotedIdentifierAt(String text, int start) {
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < text.length()) {
            char current = text.charAt(index++);
            if (current == '"') {
                if (index < text.length() && text.charAt(index) == '"') {
                    value.append('"');
                    index++;
                } else {
                    return new QuotedIdentifier(value.toString(), index);
                }
            } else if (current == '\0' || current == '\r' || current == '\n') {
                return null;
            } else {
                value.append(current);
            }
        }
        return null;
    }

    private static String dollarTagAt(String text, int start) {
        if (start > 0 && identifierPart(text.charAt(start - 1))) return null;
        int end = text.indexOf('$', start + 1);
        if (end < 0) return null;
        if (end == start + 1) return "$$";
        for (int index = start + 1; index < end; index++) {
            char character = text.charAt(index);
            if (index == start + 1 ? !identifierStart(character) : !identifierPart(character)) {
                return null;
            }
        }
        return text.substring(start, end + 1);
    }

    private static boolean identifierStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean identifierPart(char value) {
        return value == '_' || value == '$' || Character.isLetterOrDigit(value);
    }

    private static char charAt(String value, int index) {
        return index < value.length() ? value.charAt(index) : '\0';
    }

    private record QuotedIdentifier(String value, int end) {
    }

    private static List<String> createTable(TableDefinition table, RenderContext context) {
        List<ColumnDefinition> columns = table.columns().stream()
                .sorted(Comparator.comparingInt(ColumnDefinition::ordinal)
                        .thenComparing(ColumnDefinition::name))
                .toList();
        if (columns.isEmpty()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        List<ConstraintDefinition> constraints = table.constraints().stream()
                .sorted(Comparator.comparing(ConstraintDefinition::key)).toList();
        List<String> elements = new ArrayList<>();
        columns.forEach(column -> elements.add(columnClause(column, context)));
        constraints.stream().filter(constraint -> constraint.kind() != ConstraintKind.FOREIGN_KEY)
                .map(constraint -> constraintClause(constraint, context))
                .forEach(elements::add);

        List<String> statements = new ArrayList<>();
        statements.add("CREATE TABLE " + targetName(table.key(), context) + " (\n    "
                + String.join(",\n    ", elements) + "\n);");
        constraints.stream().filter(constraint -> constraint.kind() == ConstraintKind.FOREIGN_KEY)
                .map(constraint -> "ALTER TABLE " + targetName(table.key(), context)
                        + " ADD " + constraintClause(constraint, context) + ';')
                .forEach(statements::add);
        table.indexes().stream().filter(index -> !index.providerGeneratedName())
                .sorted(Comparator.comparing(IndexDefinition::key))
                .map(index -> createIndex(index, table.key(), context))
                .forEach(statements::add);
        return List.copyOf(statements);
    }

    private static String columnClause(ColumnDefinition column, RenderContext context) {
        String type = formattedType(column, context);
        StringBuilder clause = new StringBuilder(childName(column.name())).append(' ').append(type);
        String defaultValue = column.normalizedDefault();
        if (defaultValue != null && !defaultValue.isBlank()) {
            defaultValue = renderFragment(defaultValue, context);
            if (defaultValue.stripLeading().startsWith("GENERATED ")) {
                clause.append(' ').append(defaultValue.strip());
            } else {
                clause.append(" DEFAULT ").append(defaultValue.strip());
            }
        }
        clause.append(column.nullable() ? " NULL" : " NOT NULL");
        return clause.toString();
    }

    private static String constraintClause(
            ConstraintDefinition constraint, RenderContext context) {
        StringBuilder clause = new StringBuilder();
        if (!constraint.providerGeneratedName()) {
            clause.append("CONSTRAINT ").append(nestedObjectName(constraint.key())).append(' ');
        }
        switch (constraint.kind()) {
            case PRIMARY_KEY -> clause.append("PRIMARY KEY ").append(columnList(constraint.columns()));
            case UNIQUE -> clause.append("UNIQUE ").append(columnList(constraint.columns()));
            case CHECK -> {
                String expression = constraint.normalizedExpression();
                if (expression == null || expression.isBlank()) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                expression = renderFragment(expression, context);
                clause.append(expression.strip().startsWith("CHECK")
                        ? expression.strip() : "CHECK (" + expression.strip() + ')');
            }
            case FOREIGN_KEY -> {
                if (constraint.referencedTable() == null
                        || constraint.columns().isEmpty()
                        || constraint.referencedColumns().isEmpty()) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                clause.append("FOREIGN KEY ").append(columnList(constraint.columns()))
                        .append(" REFERENCES ").append(targetName(constraint.referencedTable(), context))
                        .append(' ').append(columnList(constraint.referencedColumns()));
                appendAction(clause, " ON UPDATE ", constraint.updateAction());
                appendAction(clause, " ON DELETE ", constraint.deleteAction());
            }
        }
        return clause.toString();
    }

    private static void appendAction(StringBuilder clause, String keyword, String action) {
        if (action != null && !action.isBlank()) {
            String normalized = action.strip();
            if (!Set.of("NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT")
                    .contains(normalized)) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            clause.append(keyword).append(normalized);
        }
    }

    private static String columnList(List<com.datacube.spi.schemadiff.QualifiedName> columns) {
        if (columns.isEmpty()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return '(' + String.join(", ", columns.stream()
                .map(PgSchemaChangeRenderer::childName).toList()) + ')';
    }

    private static String createIndex(
            IndexDefinition index, ObjectKey table, RenderContext context) {
        if (index.normalizedExpressions().isEmpty()
                || index.normalizedExpressions().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        StringBuilder sql = new StringBuilder("CREATE ");
        if (index.unique()) sql.append("UNIQUE ");
        sql.append("INDEX ").append(targetName(index.key(), context))
                .append(" ON ").append(targetName(table, context)).append(" (")
                .append(String.join(", ", index.normalizedExpressions().stream()
                        .map(expression -> renderFragment(expression, context)).toList())).append(')');
        if (index.normalizedPredicate() != null && !index.normalizedPredicate().isBlank()) {
            sql.append(" WHERE ").append(renderFragment(index.normalizedPredicate(), context));
        }
        return sql.append(';').toString();
    }

    private static List<String> renderAlter(SchemaChange change, RenderContext context) {
        if (change.source() instanceof SequenceDefinition source
                && change.target() instanceof SequenceDefinition target
                && change.property() != null) {
            return alterSequence(change, source, target, context);
        }
        if (!(change.source() instanceof TableDefinition source)
                || !(change.target() instanceof TableDefinition target)
                || change.property() == null) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        Object sourceValue = change.property().sourceValue();
        if (sourceValue instanceof ColumnDefinition column
                && change.property().targetValue() == null
                && change.property().path().equals(columnPath(column))
                && source.columns().contains(column)
                && target.columns().stream().noneMatch(candidate -> sameColumn(candidate, column))) {
            return List.of("ALTER TABLE " + targetName(source.key(), context)
                    + " ADD COLUMN " + columnClause(column, context) + ';');
        }
        if (sourceValue == null && change.property().targetValue() instanceof ColumnDefinition column
                && change.property().path().equals(columnPath(column))
                && target.columns().contains(column)
                && source.columns().stream().noneMatch(candidate -> sameColumn(candidate, column))) {
            return List.of("ALTER TABLE " + targetName(source.key(), context)
                    + " DROP COLUMN " + childName(column.name()) + ';');
        }
        List<String> columnAlter = alterColumnProperty(change, source, target, context);
        if (columnAlter != null) return columnAlter;
        if (change.property().path().equals("constraints")
                && listMatches(change.property().sourceValue(), source.constraints())
                && listMatches(change.property().targetValue(), target.constraints())) {
            return alterConstraints(source, target, context);
        }
        if (change.property().path().equals("indexes")
                && listMatches(change.property().sourceValue(), source.indexes())
                && listMatches(change.property().targetValue(), target.indexes())) {
            return alterIndexes(source, target, context);
        }
        throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
    }

    private static List<String> alterSequence(
            SchemaChange change, SequenceDefinition source, SequenceDefinition target,
            RenderContext context) {
        String option = switch (change.property().path()) {
            case "startValue" -> sequenceStringOption(
                    change, source.startValue(), target.startValue(), "START WITH ", false);
            case "incrementBy" -> sequenceStringOption(
                    change, source.incrementBy(), target.incrementBy(), "INCREMENT BY ", false);
            case "minimumValue" -> sequenceStringOption(
                    change, source.minimumValue(), target.minimumValue(), "MINVALUE ", true);
            case "maximumValue" -> sequenceStringOption(
                    change, source.maximumValue(), target.maximumValue(), "MAXVALUE ", true);
            case "cycle" -> {
                if (!Objects.equals(change.property().sourceValue(), source.cycle())
                        || !Objects.equals(change.property().targetValue(), target.cycle())
                        || source.cycle() == target.cycle()) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                yield source.cycle() ? "CYCLE" : "NO CYCLE";
            }
            case "cacheSize" -> {
                if (!Objects.equals(change.property().sourceValue(), source.cacheSize())
                        || !Objects.equals(change.property().targetValue(), target.cacheSize())
                        || Objects.equals(source.cacheSize(), target.cacheSize())
                        || source.cacheSize() == null || source.cacheSize() <= 0) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                yield "CACHE " + source.cacheSize();
            }
            default -> throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        };
        return List.of("ALTER SEQUENCE " + targetName(source.key(), context) + ' ' + option + ';');
    }

    private static String sequenceStringOption(
            SchemaChange change, String source, String target,
            String keyword, boolean nullable) {
        if (!Objects.equals(change.property().sourceValue(), source)
                || !Objects.equals(change.property().targetValue(), target)
                || Objects.equals(source, target)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String value = requireInteger(source, nullable);
        return value == null ? "NO " + keyword.strip() : keyword + value;
    }

    private static List<String> alterColumnProperty(
            SchemaChange change, TableDefinition source, TableDefinition target,
            RenderContext context) {
        for (ColumnDefinition desired : source.columns()) {
            ColumnDefinition current = target.columns().stream()
                    .filter(candidate -> sameColumn(candidate, desired)).findFirst().orElse(null);
            if (current == null) continue;
            String prefix = columnPath(desired);
            String tableName = targetName(source.key(), context);
            String columnName = childName(desired.name());
            if (change.property().path().equals(prefix + ".dataType")
                    && Objects.equals(change.property().sourceValue(), desired.dataType())
                    && Objects.equals(change.property().targetValue(), current.dataType())) {
                return List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                        + " TYPE " + formattedType(desired, context) + ';');
            }
            if (change.property().path().equals(prefix + ".nullable")
                    && Objects.equals(change.property().sourceValue(), desired.nullable())
                    && Objects.equals(change.property().targetValue(), current.nullable())
                    && desired.nullable() != current.nullable()) {
                return List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                        + (desired.nullable() ? " DROP NOT NULL;" : " SET NOT NULL;"));
            }
            if (change.property().path().equals(prefix + ".normalizedDefault")
                    && Objects.equals(change.property().sourceValue(), desired.normalizedDefault())
                    && Objects.equals(change.property().targetValue(), current.normalizedDefault())
                    && !Objects.equals(desired.normalizedDefault(), current.normalizedDefault())) {
                String desiredDefault = desired.normalizedDefault();
                if (generatedClause(desiredDefault) || generatedClause(current.normalizedDefault())) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                return List.of("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                        + (desiredDefault == null || desiredDefault.isBlank()
                        ? " DROP DEFAULT;" : " SET DEFAULT "
                                + renderFragment(desiredDefault, context) + ';'));
            }
            if (change.property().path().equals(prefix + ".comment")
                    && Objects.equals(change.property().sourceValue(), desired.comment())
                    && Objects.equals(change.property().targetValue(), current.comment())
                    && !Objects.equals(desired.comment(), current.comment())) {
                return List.of("COMMENT ON COLUMN " + tableName + '.' + columnName + " IS "
                        + (desired.comment() == null ? "NULL" : sqlString(desired.comment())) + ';');
            }
        }
        return null;
    }

    private static boolean sameColumn(ColumnDefinition left, ColumnDefinition right) {
        return left.name().comparisonKey().equals(right.name().comparisonKey());
    }

    private static boolean generatedClause(String value) {
        return value != null && value.stripLeading().startsWith("GENERATED ");
    }

    private static String formattedType(ColumnDefinition column, RenderContext context) {
        String type = column.dataType().providerExtensions().get("formattedType");
        if (type == null || type.isBlank()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return renderFragment(type, context);
    }

    private static String renderFragment(String fragment, RenderContext context) {
        if (fragment == null || fragment.isBlank() || fragment.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        try {
            if (hasTopLevelSemicolon(fragment) || hasTrailingLineComment(fragment)) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            return retargetDefinition(fragment.strip(), ObjectType.TABLE, context);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static String sqlString(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }

    private static boolean listMatches(Object value, List<?> expected) {
        if (!(value instanceof List<?> actual) || actual.size() != expected.size()) return false;
        return actual.containsAll(expected) && expected.containsAll(actual);
    }

    private static List<String> alterConstraints(
            TableDefinition source, TableDefinition target, RenderContext context) {
        rejectDuplicateKeys(source.constraints().stream().map(ConstraintDefinition::key).toList());
        rejectDuplicateKeys(target.constraints().stream().map(ConstraintDefinition::key).toList());
        List<String> statements = new ArrayList<>();
        target.constraints().stream().filter(constraint -> !source.constraints().contains(constraint))
                .sorted(Comparator.comparing(ConstraintDefinition::key))
                .map(constraint -> "ALTER TABLE " + targetName(source.key(), context)
                        + " DROP CONSTRAINT " + nestedObjectName(constraint.key()) + ';')
                .forEach(statements::add);
        source.constraints().stream().filter(constraint -> !target.constraints().contains(constraint))
                .sorted(Comparator.comparing(ConstraintDefinition::key))
                .map(constraint -> "ALTER TABLE " + targetName(source.key(), context)
                        + " ADD " + constraintClause(constraint, context) + ';')
                .forEach(statements::add);
        if (statements.isEmpty()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return List.copyOf(statements);
    }

    private static List<String> alterIndexes(
            TableDefinition source, TableDefinition target, RenderContext context) {
        rejectDuplicateKeys(source.indexes().stream().map(IndexDefinition::key).toList());
        rejectDuplicateKeys(target.indexes().stream().map(IndexDefinition::key).toList());
        List<String> statements = new ArrayList<>();
        target.indexes().stream()
                .filter(index -> !index.providerGeneratedName() && !source.indexes().contains(index))
                .sorted(Comparator.comparing(IndexDefinition::key))
                .map(index -> "DROP INDEX " + targetName(index.key(), context) + ';')
                .forEach(statements::add);
        source.indexes().stream()
                .filter(index -> !index.providerGeneratedName() && !target.indexes().contains(index))
                .sorted(Comparator.comparing(IndexDefinition::key))
                .map(index -> createIndex(index, source.key(), context))
                .forEach(statements::add);
        if (statements.isEmpty()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return List.copyOf(statements);
    }

    private static void rejectDuplicateKeys(List<ObjectKey> keys) {
        if (Set.copyOf(keys).size() != keys.size()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static String columnPath(ColumnDefinition column) {
        return "columns[" + column.name().comparisonKey() + ']';
    }

    private static String createSequence(SequenceDefinition sequence, RenderContext context) {
        requireInteger(sequence.startValue(), false);
        requireInteger(sequence.incrementBy(), false);
        requireInteger(sequence.minimumValue(), true);
        requireInteger(sequence.maximumValue(), true);
        if (sequence.cacheSize() != null && sequence.cacheSize() <= 0) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        StringBuilder sql = new StringBuilder("CREATE SEQUENCE ")
                .append(targetName(sequence.key(), context));
        appendOption(sql, " START WITH ", sequence.startValue());
        appendOption(sql, " INCREMENT BY ", sequence.incrementBy());
        appendOption(sql, " MINVALUE ", sequence.minimumValue());
        appendOption(sql, " MAXVALUE ", sequence.maximumValue());
        sql.append(sequence.cycle() ? " CYCLE" : " NO CYCLE");
        if (sequence.cacheSize() != null) sql.append(" CACHE ").append(sequence.cacheSize());
        return sql.append(';').toString();
    }

    private static String requireInteger(String value, boolean nullable) {
        if (value == null) {
            if (nullable) return null;
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String stripped = value.strip();
        if (!stripped.matches("[+-]?[0-9]+")) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return stripped;
    }

    private static void appendOption(StringBuilder sql, String keyword, String value) {
        if (value != null && !value.isBlank()) sql.append(keyword).append(value);
    }

    private static String renderDrop(SchemaChange change, RenderContext context) {
        ObjectKey key = change.object();
        String name = targetName(key, context);
        return switch (key.type()) {
            case TABLE -> "DROP TABLE " + name + ';';
            case SEQUENCE -> "DROP SEQUENCE " + name + ';';
            case VIEW -> "DROP VIEW " + name + ';';
            case MATERIALIZED_VIEW -> "DROP MATERIALIZED VIEW " + name + ';';
            case FUNCTION -> "DROP FUNCTION " + name + '(' + routineSignature(key.signature(), context) + ");";
            case PROCEDURE -> "DROP PROCEDURE " + name + '(' + routineSignature(key.signature(), context) + ");";
            case TYPE -> "DROP TYPE " + name + ';';
            case TRIGGER -> dropTrigger(change, context);
            default -> throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        };
    }

    private static String dropTrigger(SchemaChange change, RenderContext context) {
        if (!(change.target() instanceof DefinitionObject definition)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        ObjectKey owner = triggerOwner(definition);
        return "DROP TRIGGER " + nestedObjectName(change.object()) + " ON "
                + targetName(owner, context) + ';';
    }

    private static ObjectKey triggerOwner(DefinitionObject definition) {
        List<ObjectKey> owners = definition.dependencies().stream()
                .filter(dependency -> dependency.type() == ObjectType.TABLE).toList();
        if (owners.size() != 1) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return owners.getFirst();
    }

    private static String targetName(ObjectKey key, RenderContext context) {
        String schema = schemaPart(context.targetSchema());
        String object = objectPart(key);
        return PgSchemaIdentifierNormalizer.quote(schema) + '.'
                + PgSchemaIdentifierNormalizer.quote(object);
    }

    private static String schemaPart(com.datacube.spi.schemadiff.QualifiedName name) {
        String key = name.comparisonKey();
        if (!key.startsWith(SCHEMA_KEY_DOMAIN)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String schema = key.substring(SCHEMA_KEY_DOMAIN.length());
        if (schema.isEmpty() || schema.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return schema;
    }

    private static String objectPart(ObjectKey key) {
        String comparisonKey = key.name().comparisonKey();
        if (!comparisonKey.startsWith(OBJECT_KEY_DOMAIN)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String identity = comparisonKey.substring(OBJECT_KEY_DOMAIN.length());
        int separator = identity.indexOf('\0');
        if (separator <= 0 || separator == identity.length() - 1
                || identity.indexOf('\0', separator + 1) >= 0) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return identity.substring(separator + 1);
    }

    private static String childName(com.datacube.spi.schemadiff.QualifiedName name) {
        String key = name.comparisonKey();
        if (!key.startsWith(CHILD_KEY_DOMAIN)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String child = key.substring(CHILD_KEY_DOMAIN.length());
        if (child.isEmpty() || child.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return PgSchemaIdentifierNormalizer.quote(child);
    }

    private static String nestedObjectName(ObjectKey key) {
        return PgSchemaIdentifierNormalizer.quote(objectPart(key));
    }

    private static String routineSignature(String signature, RenderContext context) {
        if (signature == null || !safeRoutineSignature(signature)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        if (signature.isBlank()) return "";
        return renderFragment(signature, context);
    }

    private static boolean safeRoutineSignature(String signature) {
        boolean quoted = false;
        int parentheses = 0;
        for (int index = 0; index < signature.length(); index++) {
            char current = signature.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < signature.length() && signature.charAt(index + 1) == '"') {
                        index++;
                    } else {
                        quoted = false;
                    }
                } else if (current == '\0' || current == '\r' || current == '\n') {
                    return false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '(') {
                parentheses++;
            } else if (current == ')') {
                if (--parentheses < 0) return false;
            } else if (!(Character.isLetterOrDigit(current) || Character.isWhitespace(current)
                    || current == '_' || current == '$' || current == '.' || current == ','
                    || current == '[' || current == ']')) {
                return false;
            }
        }
        return !quoted && parentheses == 0;
    }
}
