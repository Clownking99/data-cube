package com.datacube.provider.oracle;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safely renders selected Oracle schema changes from structured snapshot data. */
public final class OracleSchemaChangeRenderer implements SchemaChangeRenderer {
    static final String WRONG_DATABASE = "Oracle renderer requires an Oracle context";
    static final String MANUAL_CHANGE = "Schema change requires manual execution";
    static final String DESTRUCTIVE_APPROVAL =
            "Destructive schema change requires explicit approval";
    static final String UNSUPPORTED_SHAPE = "Schema change shape is unsupported";
    static final String UNSAFE_DEFINITION = "Schema definition cannot be retargeted safely";
    static final String UNKNOWN_SEQUENCE_START = "Oracle sequence start value is unknown";
    static final String IMPLICIT_COMMIT_WARNING = "Oracle DDL causes an implicit commit";
    static final String DESTRUCTIVE_WARNING =
            "Destructive Oracle DDL causes an implicit commit";

    private static final String OBJECT_KEY_DOMAIN = "oracle-object-v1\0";
    private static final String SCHEMA_KEY_DOMAIN = "oracle-schema-v1\0";
    private static final String CHILD_KEY_DOMAIN = "oracle-child-v1\0";
    private static final String ROUTINE_SIGNATURE_DOMAIN = "oracle-routine-signature-v1\0";
    private static final Pattern IDENTITY_OPTIONS = Pattern.compile(
            "START WITH: ([+-]?[0-9]+), INCREMENT BY: ([+-]?[0-9]+), "
                    + "MAX_VALUE: ([+-]?[0-9]+), MIN_VALUE: ([+-]?[0-9]+), "
                    + "CYCLE_FLAG: ([YN]), CACHE_SIZE: ([0-9]+), ORDER_FLAG: ([YN])");

    @Override
    public List<RenderedStatement> render(SchemaChange change, RenderContext context) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(context, "context");
        if (context.databaseType() != DbType.ORACLE) {
            throw new IllegalArgumentException(WRONG_DATABASE);
        }
        if (change.kind() == ChangeKind.MANUAL
                || change.automation() == AutomationLevel.MANUAL_ONLY) {
            throw new IllegalArgumentException(MANUAL_CHANGE);
        }
        validateShape(change, context);
        validateContextOwners(change, context);
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
        String warning = destructive ? DESTRUCTIVE_WARNING : IMPLICIT_COMMIT_WARNING;
        return sql.stream().map(statement -> new RenderedStatement(
                change.id(), statement, destructive, change.dependencyChangeIds(), warning)).toList();
    }

    private static boolean hasDestructiveSemantics(SchemaChange change) {
        if (change.kind() == ChangeKind.DROP || change.kind() == ChangeKind.REPLACE) return true;
        if (change.kind() != ChangeKind.ALTER || change.property() == null) return false;
        Object sourceValue = change.property().sourceValue();
        Object targetValue = change.property().targetValue();
        if (sourceValue == null && targetValue instanceof ColumnDefinition) return true;
        if (sourceValue instanceof ColumnDefinition column && targetValue == null) {
            return !column.nullable() || column.normalizedDefault() != null;
        }
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

    private static void validateShape(SchemaChange change, RenderContext context) {
        switch (change.kind()) {
            case CREATE -> requireShape(change.source(), change.target(), change.object());
            case DROP -> requireComparisonShape(
                    change.target(), change.source(), change.object(), context);
            case ALTER, REPLACE -> {
                if (change.source() == null || change.target() == null
                        || !sameComparisonIdentity(
                                change.object(), change.source().key(), context)
                        || !sameComparisonIdentity(
                                change.object(), change.target().key(), context)) {
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

    private static void requireShape(SchemaObject present, SchemaObject absent, ObjectKey key) {
        if (present == null || absent != null || !key.equals(present.key())) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static void requireComparisonShape(
            SchemaObject present, SchemaObject absent, ObjectKey key,
            RenderContext context) {
        if (present == null || absent != null
                || !sameComparisonIdentity(key, present.key(), context)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static boolean sameComparisonIdentity(
            ObjectKey left, ObjectKey right, RenderContext context) {
        return left.type() == right.type()
                && comparisonSignature(left, context)
                        .equals(comparisonSignature(right, context))
                && objectPart(left).equals(objectPart(right));
    }

    private static String comparisonSignature(ObjectKey key, RenderContext context) {
        if (key.type() != ObjectType.FUNCTION && key.type() != ObjectType.PROCEDURE) {
            return key.signature();
        }
        String sourceOwner = schemaPart(context.sourceSchema());
        String targetOwner = schemaPart(context.targetSchema());
        StringBuilder comparison = new StringBuilder(ROUTINE_SIGNATURE_DOMAIN);
        for (RoutineArgument argument : decodeRoutineSignature(key.signature())) {
            appendSignatureField(comparison, argument.mode());
            appendSignatureField(comparison, retargetRoutineIdentityType(
                    argument.type(), sourceOwner, targetOwner));
        }
        return comparison.toString();
    }

    private static void appendSignatureField(StringBuilder signature, String value) {
        signature.append(value.length()).append(':').append(value);
    }

    private static String retargetRoutineIdentityType(
            String type, String sourceOwner, String targetOwner) {
        String sourcePrefix = sourceOwner + '.';
        if (!type.startsWith(sourcePrefix)) return type;
        if (type.length() == sourcePrefix.length()) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        return targetOwner + type.substring(sourceOwner.length());
    }

    private static void validateContextOwners(SchemaChange change, RenderContext context) {
        String sourceOwner = schemaPart(context.sourceSchema());
        String targetOwner = schemaPart(context.targetSchema());
        if (change.source() != null
                && !objectOwner(change.source().key()).equals(sourceOwner)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        if (change.target() != null) {
            String owner = objectOwner(change.target().key());
            if (!owner.equals(sourceOwner) && !owner.equals(targetOwner)) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
        }
        String shapeOwner = switch (change.kind()) {
            case CREATE, ALTER, REPLACE -> objectOwner(change.source().key());
            case DROP -> objectOwner(change.target().key());
            case MANUAL -> throw new IllegalArgumentException(MANUAL_CHANGE);
        };
        if (!objectOwner(change.object()).equals(shapeOwner)) {
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
            validateTopLevelKey(sequence.key(), ObjectType.SEQUENCE);
            return List.of(createSequence(sequence, context));
        }
        if (source instanceof TableDefinition table) {
            validateTopLevelKey(table.key(), ObjectType.TABLE);
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
                || change.object().type() == ObjectType.MATERIALIZED_VIEW) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        targetName(change.target().key(), context);
        return List.of(renderDefinition(definition, context, true));
    }

    private static String renderDefinition(
            DefinitionObject definition, RenderContext context, boolean replace) {
        if (!Set.of(ObjectType.VIEW, ObjectType.MATERIALIZED_VIEW,
                ObjectType.FUNCTION, ObjectType.PROCEDURE, ObjectType.TRIGGER,
                ObjectType.PACKAGE_SPEC, ObjectType.PACKAGE_BODY, ObjectType.TYPE)
                .contains(definition.key().type())) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        validateDefinitionDependencyShape(definition);
        try {
            String original = definition.originalDefinition();
            String definitionOwner = objectOwner(definition.key());
            String sourceOwner = schemaPart(context.sourceSchema());
            String targetOwner = schemaPart(context.targetSchema());
            if (original == null || original.isBlank()
                    || (!definitionOwner.equals(sourceOwner)
                            && !definitionOwner.equals(targetOwner))) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            String normalized = OracleSchemaDefinitionNormalizer.normalize(original);
            if (normalized == null || normalized.isBlank() || normalized.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            validateDefinitionSegments(normalized);
            DefinitionHeader header = definitionHeader(
                    normalized, definition.key(), definitionOwner);
            if (header == null || replace && !header.replace()) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            if ((definition.key().type() == ObjectType.FUNCTION
                    || definition.key().type() == ObjectType.PROCEDURE)
                    && !routineIdentityMatches(normalized, header, definition.key())) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            if (definition.key().type() == ObjectType.TRIGGER
                    && !triggerOwnerMatches(normalized, header.nameEnd(),
                            definition, definitionOwner)) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            validateDefinitionTerminator(normalized, definition.key());
            String retargeted = definitionOwner.equals(sourceOwner)
                    ? retargetDefinitionBasic(normalized, context) : normalized;
            return requiresSlash(definition.key())
                    ? retargeted.stripTrailing() + "\n/"
                    : retargeted.stripTrailing();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
    }

    private static DefinitionHeader definitionHeader(
            String definition, ObjectKey key, String expectedOwner) {
        int index = keywordEnd(definition, 0, "CREATE");
        if (index < 0) return null;
        boolean replace = false;
        int orEnd = keywordEnd(definition, index, "OR");
        if (orEnd >= 0) {
            int replaceEnd = keywordEnd(definition, orEnd, "REPLACE");
            if (replaceEnd < 0) return null;
            replace = true;
            index = replaceEnd;
        }
        boolean modifier;
        do {
            modifier = false;
            for (String candidate : List.of(
                    "FORCE", "NOFORCE", "EDITIONABLE", "NONEDITIONABLE")) {
                int end = keywordEnd(definition, index, candidate);
                if (end >= 0) {
                    index = end;
                    modifier = true;
                    break;
                }
            }
        } while (modifier);

        for (String noun : definitionNouns(key)) {
            index = keywordEnd(definition, index, noun);
            if (index < 0) return null;
        }
        QualifiedSqlName name = qualifiedSqlNameAt(definition, index);
        if (name == null
                || !identifierMatches(name.schema(), expectedOwner, true)
                || !identifierMatches(name.object(), objectPart(key), false)) {
            return null;
        }
        return new DefinitionHeader(replace, name.object().end());
    }

    private static List<String> definitionNouns(ObjectKey key) {
        return switch (key.type()) {
            case VIEW -> requireEmptySignature(key, List.of("VIEW"));
            case MATERIALIZED_VIEW -> requireEmptySignature(key, List.of("MATERIALIZED", "VIEW"));
            case FUNCTION -> requireRoutineSignature(key, List.of("FUNCTION"));
            case PROCEDURE -> requireRoutineSignature(key, List.of("PROCEDURE"));
            case TRIGGER -> requireEmptySignature(key, List.of("TRIGGER"));
            case PACKAGE_SPEC -> requireEmptySignature(key, List.of("PACKAGE"));
            case PACKAGE_BODY -> requireEmptySignature(key, List.of("PACKAGE", "BODY"));
            case TYPE -> switch (key.signature()) {
                case "SPEC" -> List.of("TYPE");
                case "BODY" -> List.of("TYPE", "BODY");
                default -> throw new IllegalArgumentException(UNSAFE_DEFINITION);
            };
            default -> throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        };
    }

    private static List<String> requireEmptySignature(ObjectKey key, List<String> nouns) {
        if (!key.signature().isEmpty()) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        return nouns;
    }

    private static List<String> requireRoutineSignature(ObjectKey key, List<String> nouns) {
        decodeRoutineSignature(key.signature());
        return nouns;
    }

    private static boolean routineIdentityMatches(
            String definition, DefinitionHeader header, ObjectKey key) {
        List<RoutineArgument> expected = decodeRoutineSignature(key.signature());
        int index = skipTrivia(definition, header.nameEnd());
        List<String> declarations;
        if (charAt(definition, index) == '(') {
            int close = matchingParenthesis(definition, index);
            if (close < 0) return false;
            declarations = splitSqlList(definition.substring(index + 1, close));
        } else {
            declarations = List.of();
        }
        List<RoutineArgument> actual = new ArrayList<>();
        for (String declaration : declarations) {
            RoutineArgument argument = routineArgument(declaration);
            if (argument == null) return false;
            if (!"OUT".equals(argument.mode())) actual.add(argument);
        }
        if (actual.size() != expected.size()) return false;
        for (int argument = 0; argument < actual.size(); argument++) {
            RoutineArgument actualArgument = actual.get(argument);
            RoutineArgument expectedArgument = expected.get(argument);
            if (!actualArgument.mode().equals(expectedArgument.mode())
                    || !canonicalDefinitionType(actualArgument.type())
                            .equals(canonicalExpectedType(expectedArgument.type()))) {
                return false;
            }
        }
        return true;
    }

    private static List<RoutineArgument> decodeRoutineSignature(String signature) {
        if (signature == null || !signature.startsWith(ROUTINE_SIGNATURE_DOMAIN)) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        List<RoutineArgument> arguments = new ArrayList<>();
        int index = ROUTINE_SIGNATURE_DOMAIN.length();
        while (index < signature.length()) {
            Field mode = signatureFieldAt(signature, index);
            Field type = signatureFieldAt(signature, mode.next());
            if (!Set.of("IN", "INOUT").contains(mode.value())) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            arguments.add(new RoutineArgument(mode.value(), type.value()));
            index = type.next();
        }
        return List.copyOf(arguments);
    }

    private static Field signatureFieldAt(String value, int start) {
        try {
            return fieldAt(value, start);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
    }

    private static RoutineArgument routineArgument(String declaration) {
        String value = beforeTopLevelDefault(declaration).strip();
        SqlIdentifier name = sqlIdentifierAt(value, 0);
        if (name == null) return null;
        int index = skipTrivia(value, name.end());
        String mode = "IN";
        int inEnd = keywordEnd(value, index, "IN");
        int outEnd = keywordEnd(value, index, "OUT");
        if (inEnd >= 0) {
            int inOutEnd = keywordEnd(value, inEnd, "OUT");
            if (inOutEnd >= 0) {
                mode = "INOUT";
                index = inOutEnd;
            } else {
                index = inEnd;
            }
        } else if (outEnd >= 0) {
            mode = "OUT";
            index = outEnd;
        }
        String type = value.substring(index).strip();
        return type.isEmpty() ? null : new RoutineArgument(mode, type);
    }

    private static String beforeTopLevelDefault(String declaration) {
        int depth = 0;
        int index = 0;
        while (index < declaration.length()) {
            char current = declaration.charAt(index);
            if (alternativeQuoteAt(declaration, index)) {
                index = alternativeQuoteEnd(declaration, index);
            } else if (current == '\'') {
                index = singleQuotedEnd(declaration, index);
            } else if (current == '"') {
                SqlIdentifier quoted = sqlIdentifierAt(declaration, index);
                if (quoted == null) return declaration;
                index = quoted.end();
            } else if (current == '-' && charAt(declaration, index + 1) == '-') {
                index = lineCommentEnd(declaration, index);
            } else if (current == '/' && charAt(declaration, index + 1) == '*') {
                index = blockCommentEnd(declaration, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (--depth < 0) return declaration;
                index++;
            } else if (depth == 0 && current == ':' && charAt(declaration, index + 1) == '=') {
                return declaration.substring(0, index);
            } else if (depth == 0 && keywordAt(declaration, index, "DEFAULT")) {
                return declaration.substring(0, index);
            } else {
                index++;
            }
        }
        return declaration;
    }

    private static String canonicalDefinitionType(String value) {
        StringBuilder canonical = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            if (Character.isWhitespace(value.charAt(index))) {
                index++;
                continue;
            }
            SqlIdentifier identifier = sqlIdentifierAt(value, index);
            if (identifier != null) {
                if (identifier.quoted()) {
                    canonical.append("Q").append(identifier.value().length())
                            .append(':').append(identifier.value());
                } else {
                    canonical.append(identifier.value().toUpperCase(java.util.Locale.ROOT));
                }
                index = identifier.end();
            } else {
                char current = value.charAt(index++);
                if (!(Character.isDigit(current) || ".(),%".indexOf(current) >= 0)) return "";
                canonical.append(current);
            }
        }
        return canonical.toString();
    }

    private static String canonicalExpectedType(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
                || value.indexOf('"') >= 0 || value.indexOf('\'') >= 0) return "";
        String compact = value.replaceAll("\\s+", "");
        int suffixStart = compact.indexOf('(');
        String base = suffixStart < 0 ? compact : compact.substring(0, suffixStart);
        String suffix = suffixStart < 0 ? "" : compact.substring(suffixStart);
        if (!base.contains(".")) {
            return (base + suffix).toUpperCase(java.util.Locale.ROOT);
        }
        StringBuilder canonical = new StringBuilder();
        String[] identifiers = base.split("\\.", -1);
        for (int index = 0; index < identifiers.length; index++) {
            String identifier = identifiers[index];
            if (!identifier.matches("[A-Za-z][A-Za-z0-9_$#]*")) return "";
            if (index > 0) canonical.append('.');
            canonical.append('Q').append(identifier.length()).append(':').append(identifier);
        }
        if (!suffix.matches("(?:\\([+-]?[0-9]+(?:,[+-]?[0-9]+)?\\))?")) return "";
        return canonical.append(suffix).toString();
    }

    private static int matchingParenthesis(String text, int open) {
        int depth = 1;
        int index = open + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (alternativeQuoteAt(text, index)) {
                index = alternativeQuoteEnd(text, index);
            } else if (current == '\'') {
                index = singleQuotedEnd(text, index);
            } else if (current == '"') {
                SqlIdentifier quoted = sqlIdentifierAt(text, index);
                if (quoted == null) return -1;
                index = quoted.end();
            } else if (current == '-' && charAt(text, index + 1) == '-') {
                index = lineCommentEnd(text, index);
            } else if (current == '/' && charAt(text, index + 1) == '*') {
                index = blockCommentEnd(text, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) return index;
                index++;
            } else {
                index++;
            }
        }
        return -1;
    }

    private static List<String> splitSqlList(String text) {
        if (text.isBlank()) return List.of();
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (alternativeQuoteAt(text, index)) {
                index = alternativeQuoteEnd(text, index);
            } else if (current == '\'') {
                index = singleQuotedEnd(text, index);
            } else if (current == '"') {
                SqlIdentifier quoted = sqlIdentifierAt(text, index);
                if (quoted == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index = quoted.end();
            } else if (current == '-' && charAt(text, index + 1) == '-') {
                index = lineCommentEnd(text, index);
            } else if (current == '/' && charAt(text, index + 1) == '*') {
                index = blockCommentEnd(text, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
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

    private static boolean triggerOwnerMatches(
            String ddl, int start, DefinitionObject definition, String expectedOwner) {
        int index = start;
        while (index < ddl.length()) {
            char current = ddl.charAt(index);
            if (alternativeQuoteAt(ddl, index)) {
                index = alternativeQuoteEnd(ddl, index);
            } else if (current == '\'') {
                index = singleQuotedEnd(ddl, index);
            } else if (current == '"') {
                SqlIdentifier quoted = sqlIdentifierAt(ddl, index);
                if (quoted == null) return false;
                index = quoted.end();
            } else if (current == '-' && charAt(ddl, index + 1) == '-') {
                index = lineCommentEnd(ddl, index);
            } else if (current == '/' && charAt(ddl, index + 1) == '*') {
                index = blockCommentEnd(ddl, index);
            } else if (identifierStart(current)) {
                int end = index + 1;
                while (end < ddl.length() && identifierPart(ddl.charAt(end))) end++;
                if (ddl.substring(index, end).equalsIgnoreCase("ON")) {
                    QualifiedSqlName candidate = qualifiedSqlNameAt(ddl, end);
                    if (candidate == null
                            || !identifierMatches(candidate.schema(), expectedOwner, true)) {
                        return false;
                    }
                    long matches = definition.dependencies().stream()
                            .filter(dependency -> dependency.type() == ObjectType.TABLE
                                    || dependency.type() == ObjectType.VIEW)
                            .filter(dependency -> objectOwner(dependency).equals(expectedOwner))
                            .filter(dependency -> identifierMatches(
                                    candidate.object(), objectPart(dependency), false))
                            .count();
                    return matches == 1;
                }
                index = end;
            } else {
                index++;
            }
        }
        return false;
    }

    private static void validateDefinitionTerminator(String definition, ObjectKey key) {
        List<Integer> semicolons = definitionSemicolons(definition);
        if (semicolons.isEmpty()
                || semicolons.getLast() != definition.length() - 1) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        if (!plsqlEndsWithEnd(key)) {
            if (semicolons.size() != 1) throw new IllegalArgumentException(UNSAFE_DEFINITION);
            return;
        }
        List<SqlIdentifier> identifiers = definitionIdentifiers(
                definition.substring(0, definition.length() - 1));
        if (identifiers.isEmpty()) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        SqlIdentifier last = identifiers.getLast();
        if (!last.quoted() && last.value().equalsIgnoreCase("END")) return;
        if (identifiers.size() < 2) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        SqlIdentifier beforeLast = identifiers.get(identifiers.size() - 2);
        if (beforeLast.quoted() || !beforeLast.value().equalsIgnoreCase("END")) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        if (!identifierMatches(last, objectPart(key), false)) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
    }

    private static void validateDefinitionSegments(String definition) {
        int index = 0;
        int createCount = 0;
        while (index < definition.length()) {
            char current = definition.charAt(index);
            if (alternativeQuoteAt(definition, index)) {
                index = alternativeQuoteEnd(definition, index);
            } else if (current == '\'') {
                index = singleQuotedEnd(definition, index);
            } else if (current == '"') {
                SqlIdentifier quoted = sqlIdentifierAt(definition, index);
                if (quoted == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index = quoted.end();
            } else if (current == '-' && charAt(definition, index + 1) == '-') {
                index = lineCommentEnd(definition, index);
            } else if (current == '/' && charAt(definition, index + 1) == '*') {
                index = blockCommentEnd(definition, index);
            } else if (identifierStart(current)) {
                int end = index + 1;
                while (end < definition.length()
                        && identifierPart(definition.charAt(end))) {
                    end++;
                }
                if (definition.substring(index, end).equalsIgnoreCase("CREATE")
                        && ++createCount > 1) {
                    throw new IllegalArgumentException(UNSAFE_DEFINITION);
                }
                index = end;
            } else {
                if (current == '/' && standaloneSlashAt(definition, index)) {
                    throw new IllegalArgumentException(UNSAFE_DEFINITION);
                }
                index++;
            }
        }
    }

    private static boolean standaloneSlashAt(String definition, int slash) {
        int lineStart = definition.lastIndexOf('\n', slash - 1) + 1;
        for (int index = lineStart; index < slash; index++) {
            if (!Character.isWhitespace(definition.charAt(index))) return false;
        }
        int lineEnd = definition.indexOf('\n', slash + 1);
        if (lineEnd < 0) lineEnd = definition.length();
        for (int index = slash + 1; index < lineEnd; index++) {
            if (!Character.isWhitespace(definition.charAt(index))) return false;
        }
        return true;
    }

    private static List<SqlIdentifier> definitionIdentifiers(String definition) {
        List<SqlIdentifier> identifiers = new ArrayList<>();
        int index = 0;
        while (index < definition.length()) {
            char current = definition.charAt(index);
            if (alternativeQuoteAt(definition, index)) {
                index = alternativeQuoteEnd(definition, index);
                continue;
            } else if (current == '\'') {
                index = singleQuotedEnd(definition, index);
                continue;
            }
            if (current == '-' && charAt(definition, index + 1) == '-') {
                index = lineCommentEnd(definition, index);
                continue;
            }
            if (current == '/' && charAt(definition, index + 1) == '*') {
                index = blockCommentEnd(definition, index);
                continue;
            }
            SqlIdentifier identifier = sqlIdentifierAt(definition, index);
            if (identifier != null) {
                identifiers.add(identifier);
                index = identifier.end();
            } else {
                index++;
            }
        }
        return List.copyOf(identifiers);
    }

    private static List<Integer> definitionSemicolons(String definition) {
        List<Integer> semicolons = new ArrayList<>();
        int index = 0;
        while (index < definition.length()) {
            char current = definition.charAt(index);
            if (alternativeQuoteAt(definition, index)) {
                index = alternativeQuoteEnd(definition, index);
            } else if (current == '\'') {
                index = singleQuotedEnd(definition, index);
            } else if (current == '"') {
                SqlIdentifier quoted = sqlIdentifierAt(definition, index);
                if (quoted == null) throw new IllegalArgumentException(UNSAFE_DEFINITION);
                index = quoted.end();
            } else if (current == '-' && charAt(definition, index + 1) == '-') {
                index = lineCommentEnd(definition, index);
            } else if (current == '/' && charAt(definition, index + 1) == '*') {
                index = blockCommentEnd(definition, index);
            } else {
                if (current == ';') semicolons.add(index);
                index++;
            }
        }
        return List.copyOf(semicolons);
    }

    private static boolean plsqlEndsWithEnd(ObjectKey key) {
        return switch (key.type()) {
            case FUNCTION, PROCEDURE, TRIGGER, PACKAGE_SPEC, PACKAGE_BODY -> true;
            case TYPE -> key.signature().equals("BODY");
            default -> false;
        };
    }

    private static boolean requiresSlash(ObjectKey key) {
        return key.type() != ObjectType.VIEW && key.type() != ObjectType.MATERIALIZED_VIEW;
    }

    private static String retargetDefinitionBasic(String text, RenderContext context) {
        String source = schemaPart(context.sourceSchema());
        String target = schemaPart(context.targetSchema());
        StringBuilder output = new StringBuilder(text.length() + target.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (alternativeQuoteAt(text, index)) {
                int end = alternativeQuoteEnd(text, index);
                output.append(text, index, end);
                index = end;
                continue;
            }
            if (current == '\'') {
                int end = singleQuotedEnd(text, index);
                output.append(text, index, end);
                index = end;
                continue;
            }
            if (current == '-' && charAt(text, index + 1) == '-') {
                int end = lineCommentEnd(text, index);
                output.append(text, index, end);
                index = end;
                continue;
            }
            if (current == '/' && charAt(text, index + 1) == '*') {
                int end = blockCommentEnd(text, index);
                output.append(text, index, end);
                index = end;
                continue;
            }
            SqlIdentifier identifier = sqlIdentifierAt(text, index);
            if (identifier != null) {
                boolean retarget = identifierMatches(identifier, source, true)
                        && qualifiedDotAt(text, identifier.end());
                output.append(retarget
                        ? OracleSchemaIdentifierNormalizer.quote(target)
                        : text.substring(index, identifier.end()));
                index = identifier.end();
                continue;
            }
            if (current == '\0' || Character.isISOControl(current)
                    && current != '\r' && current != '\n' && current != '\t') {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
            }
            output.append(current);
            index++;
        }
        return output.toString();
    }

    private static int keywordEnd(String text, int start, String keyword) {
        int index = skipTrivia(text, start);
        return keywordAt(text, index, keyword) ? index + keyword.length() : -1;
    }

    private static boolean keywordAt(String text, int index, String keyword) {
        if (index < 0 || index + keyword.length() > text.length()
                || !text.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        return (index == 0 || !identifierPart(text.charAt(index - 1)))
                && (index + keyword.length() == text.length()
                        || !identifierPart(text.charAt(index + keyword.length())));
    }

    private static int skipTrivia(String text, int start) {
        int index = start;
        while (index < text.length()) {
            if (Character.isWhitespace(text.charAt(index))) {
                index++;
            } else if (charAt(text, index) == '-' && charAt(text, index + 1) == '-') {
                index = lineCommentEnd(text, index);
            } else if (charAt(text, index) == '/' && charAt(text, index + 1) == '*') {
                index = blockCommentEnd(text, index);
            } else {
                break;
            }
        }
        return index;
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

    private record DefinitionHeader(boolean replace, int nameEnd) {
    }

    private record QualifiedSqlName(SqlIdentifier schema, SqlIdentifier object) {
    }

    private record RoutineArgument(String mode, String type) {
    }

    private static List<String> createTable(TableDefinition table, RenderContext context) {
        validateTopLevelKey(table.key(), ObjectType.TABLE);
        List<ColumnDefinition> columns = table.columns().stream()
                .sorted(Comparator.comparingInt(ColumnDefinition::ordinal)
                        .thenComparing(ColumnDefinition::name))
                .toList();
        validateColumns(columns);
        rejectDuplicateKeys(table.constraints().stream()
                .map(ConstraintDefinition::key).toList());
        rejectDuplicateKeys(table.indexes().stream().map(IndexDefinition::key).toList());
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
        columns.stream().filter(column -> column.comment() != null && !column.comment().isBlank())
                .map(column -> commentOnColumn(table.key(), column, context))
                .forEach(statements::add);
        table.indexes().stream().filter(index -> !index.providerGeneratedName())
                .sorted(Comparator.comparing(IndexDefinition::key))
                .map(index -> createIndex(index, table.key(), context))
                .forEach(statements::add);
        return List.copyOf(statements);
    }

    private static void validateColumns(List<ColumnDefinition> columns) {
        if (columns.isEmpty()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        Set<String> names = new java.util.HashSet<>();
        Set<Integer> ordinals = new java.util.HashSet<>();
        for (ColumnDefinition column : columns) {
            if (column.ordinal() <= 0
                    || !names.add(column.name().comparisonKey())
                    || !ordinals.add(column.ordinal())) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
        }
    }

    private static String columnClause(ColumnDefinition column, RenderContext context) {
        StringBuilder clause = new StringBuilder(childName(column.name()))
                .append(' ').append(formattedType(column, context));
        Map<String, String> extensions = column.dataType().providerExtensions();
        validateColumnExtensions(extensions);
        String identity = extensions.get("oracle.identity");
        boolean virtual = "true".equals(extensions.get("oracle.virtual"));
        boolean defaultOnNull = "true".equals(extensions.get("oracle.defaultOnNull"));
        String defaultValue = column.normalizedDefault();
        if (identity != null) {
            if (virtual || defaultOnNull
                    || !Set.of("ALWAYS", "BY DEFAULT", "BY DEFAULT ON NULL").contains(identity)
                    || !Objects.equals(defaultValue,
                            "GENERATED " + identity + " AS IDENTITY")) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            clause.append(' ').append(defaultValue).append(' ')
                    .append(identityOptions(extensions.get("oracle.identityOptions")));
        } else if (virtual) {
            if (defaultOnNull || defaultValue == null
                    || !defaultValue.startsWith("GENERATED ALWAYS AS (")
                    || !defaultValue.endsWith(") VIRTUAL")) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            clause.append(' ').append(renderFragment(defaultValue, context));
        } else if (defaultOnNull) {
            String prefix = "DEFAULT ON NULL ";
            if (defaultValue == null || !defaultValue.startsWith(prefix)
                    || defaultValue.length() == prefix.length()) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            clause.append(' ').append(prefix)
                    .append(renderFragment(defaultValue.substring(prefix.length()), context));
        } else if (defaultValue != null && !defaultValue.isBlank()) {
            if (defaultValue.stripLeading().startsWith("GENERATED ")
                    || defaultValue.stripLeading().startsWith("DEFAULT ")) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            clause.append(" DEFAULT ").append(renderFragment(defaultValue, context));
        }
        clause.append(column.nullable() ? " NULL" : " NOT NULL");
        String invisible = extensions.get("oracle.invisible");
        if (invisible != null) {
            if (!"true".equals(invisible)) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            clause.append(" INVISIBLE");
        }
        return clause.toString();
    }

    private static String identityOptions(String options) {
        if (options == null) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        Matcher matcher = IDENTITY_OPTIONS.matcher(options);
        if (!matcher.matches()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        String cache = requireInteger(matcher.group(6), false);
        StringBuilder rendered = new StringBuilder("(START WITH ")
                .append(requireInteger(matcher.group(1), false))
                .append(" INCREMENT BY ").append(requireInteger(matcher.group(2), false))
                .append(" MAXVALUE ").append(requireInteger(matcher.group(3), false))
                .append(" MINVALUE ").append(requireInteger(matcher.group(4), false))
                .append("Y".equals(matcher.group(5)) ? " CYCLE" : " NOCYCLE");
        rendered.append("0".equals(cache) ? " NOCACHE" : " CACHE " + cache)
                .append("Y".equals(matcher.group(7)) ? " ORDER)" : " NOORDER)");
        return rendered.toString();
    }

    private static String constraintClause(
            ConstraintDefinition constraint, RenderContext context) {
        ObjectType expectedType = switch (constraint.kind()) {
            case PRIMARY_KEY -> ObjectType.PRIMARY_KEY;
            case UNIQUE -> ObjectType.UNIQUE_CONSTRAINT;
            case FOREIGN_KEY -> ObjectType.FOREIGN_KEY;
            case CHECK -> ObjectType.CHECK_CONSTRAINT;
        };
        if (constraint.key().type() != expectedType) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        StringBuilder clause = new StringBuilder();
        if (!constraint.providerGeneratedName()) {
            clause.append("CONSTRAINT ").append(nestedObjectName(constraint.key())).append(' ');
        }
        switch (constraint.kind()) {
            case PRIMARY_KEY -> clause.append("PRIMARY KEY ")
                    .append(columnList(constraint.columns()));
            case UNIQUE -> clause.append("UNIQUE ").append(columnList(constraint.columns()));
            case CHECK -> {
                String expression = renderFragment(constraint.normalizedExpression(), context);
                clause.append(expression.startsWith("CHECK")
                        ? expression : "CHECK (" + expression + ')');
            }
            case FOREIGN_KEY -> {
                if (constraint.referencedTable() == null
                        || constraint.columns().isEmpty()
                        || constraint.referencedColumns().isEmpty()
                        || constraint.columns().size() != constraint.referencedColumns().size()
                        || constraint.updateAction() != null
                                && !constraint.updateAction().isBlank()) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                clause.append("FOREIGN KEY ").append(columnList(constraint.columns()))
                        .append(" REFERENCES ")
                        .append(targetName(constraint.referencedTable(), context))
                        .append(' ').append(columnList(constraint.referencedColumns()));
                String deleteAction = constraint.deleteAction();
                if (deleteAction != null && !deleteAction.isBlank()
                        && !"NO ACTION".equals(deleteAction)) {
                    if (!Set.of("CASCADE", "SET NULL").contains(deleteAction)) {
                        throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                    }
                    clause.append(" ON DELETE ").append(deleteAction);
                }
            }
        }
        return clause.toString();
    }

    private static String columnList(
            List<com.datacube.spi.schemadiff.QualifiedName> columns) {
        if (columns.isEmpty()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return '(' + String.join(", ", columns.stream()
                .map(OracleSchemaChangeRenderer::childName).toList()) + ')';
    }

    private static String createIndex(
            IndexDefinition index, ObjectKey table, RenderContext context) {
        if (index.key().type() != ObjectType.INDEX
                || index.normalizedExpressions().isEmpty()
                || index.normalizedExpressions().stream()
                        .anyMatch(value -> value == null || value.isBlank())
                || index.normalizedPredicate() != null
                        && !index.normalizedPredicate().isBlank()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        StringBuilder sql = new StringBuilder("CREATE ");
        if (index.unique()) sql.append("UNIQUE ");
        sql.append("INDEX ").append(targetName(index.key(), context))
                .append(" ON ").append(targetName(table, context)).append(" (")
                .append(String.join(", ", index.normalizedExpressions().stream()
                        .map(expression -> renderFragment(expression, context)).toList()))
                .append(");");
        return sql.toString();
    }

    private static String commentOnColumn(
            ObjectKey table, ColumnDefinition column, RenderContext context) {
        return "COMMENT ON COLUMN " + targetName(table, context) + '.'
                + childName(column.name()) + " IS " + sqlString(column.comment()) + ';';
    }

    private static List<String> renderAlter(SchemaChange change, RenderContext context) {
        if (change.source() instanceof SequenceDefinition sourceSequence
                && change.target() instanceof SequenceDefinition targetSequence
                && change.property() != null) {
            validateTopLevelKey(sourceSequence.key(), ObjectType.SEQUENCE);
            return alterSequence(change, sourceSequence, targetSequence, context);
        }
        if (!(change.source() instanceof TableDefinition source)
                || !(change.target() instanceof TableDefinition target)
                || change.property() == null) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        validateTopLevelKey(source.key(), ObjectType.TABLE);
        Object sourceValue = change.property().sourceValue();
        if (sourceValue instanceof ColumnDefinition column
                && change.property().targetValue() == null
                && change.property().path().equals(columnPath(column))
                && source.columns().contains(column)
                && target.columns().stream().noneMatch(candidate -> sameColumn(candidate, column))) {
            List<String> statements = new ArrayList<>();
            statements.add("ALTER TABLE " + targetName(target.key(), context)
                    + " ADD (" + columnClause(column, context) + ");");
            if (column.comment() != null && !column.comment().isBlank()) {
                statements.add(commentOnColumn(target.key(), column, context));
            }
            return List.copyOf(statements);
        }
        if (sourceValue == null
                && change.property().targetValue() instanceof ColumnDefinition column
                && change.property().path().equals(columnPath(column))
                && target.columns().contains(column)
                && source.columns().stream().noneMatch(candidate -> sameColumn(candidate, column))) {
            return List.of("ALTER TABLE " + targetName(target.key(), context)
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
        validateSequenceExtensions(source.providerExtensions());
        validateSequenceExtensions(target.providerExtensions());
        String option = switch (change.property().path()) {
            case "startValue" -> {
                if (!Objects.equals(change.property().sourceValue(), source.startValue())
                        || !Objects.equals(change.property().targetValue(), target.startValue())
                        || Objects.equals(source.startValue(), target.startValue())) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                if (!"true".equals(source.providerExtensions()
                        .get("oracle.startValueKnown"))
                        || !"true".equals(target.providerExtensions()
                                .get("oracle.startValueKnown"))
                        || source.startValue() == null || target.startValue() == null) {
                    throw new IllegalArgumentException(UNKNOWN_SEQUENCE_START);
                }
                requireInteger(source.startValue(), false);
                requireInteger(target.startValue(), false);
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            case "incrementBy" -> sequenceStringOption(change, source.incrementBy(),
                    target.incrementBy(), "INCREMENT BY ", false);
            case "minimumValue" -> sequenceStringOption(change, source.minimumValue(),
                    target.minimumValue(), "MINVALUE ", true);
            case "maximumValue" -> sequenceStringOption(change, source.maximumValue(),
                    target.maximumValue(), "MAXVALUE ", true);
            case "cycle" -> {
                if (!Objects.equals(change.property().sourceValue(), source.cycle())
                        || !Objects.equals(change.property().targetValue(), target.cycle())
                        || source.cycle() == target.cycle()) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                yield source.cycle() ? "CYCLE" : "NOCYCLE";
            }
            case "cacheSize" -> {
                if (!Objects.equals(change.property().sourceValue(), source.cacheSize())
                        || !Objects.equals(change.property().targetValue(), target.cacheSize())
                        || Objects.equals(source.cacheSize(), target.cacheSize())
                        || source.cacheSize() == null || source.cacheSize() < 0) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                yield source.cacheSize() == 0 ? "NOCACHE" : "CACHE " + source.cacheSize();
            }
            case "providerExtensions" -> sequenceOrderOption(change, source, target);
            default -> throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        };
        return List.of("ALTER SEQUENCE " + targetName(target.key(), context) + ' '
                + option + ';');
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
        return value == null ? "NO" + keyword.strip() : keyword + value;
    }

    private static String sequenceOrderOption(
            SchemaChange change, SequenceDefinition source, SequenceDefinition target) {
        if (!Objects.equals(change.property().sourceValue(), source.providerExtensions())
                || !Objects.equals(change.property().targetValue(), target.providerExtensions())
                || source.providerExtensions().equals(target.providerExtensions())
                || !Objects.equals(source.providerExtensions().get("oracle.startValueKnown"),
                        target.providerExtensions().get("oracle.startValueKnown"))) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String sourceOrder = oracleOrder(source.providerExtensions().get("oracle.order"));
        String targetOrder = oracleOrder(target.providerExtensions().get("oracle.order"));
        if (sourceOrder.equals(targetOrder)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return sourceOrder;
    }

    private static void validateSequenceExtensions(Map<String, String> extensions) {
        if (!extensions.keySet().equals(Set.of(
                "oracle.order", "oracle.startValueKnown"))
                || !Set.of("true", "false").contains(
                        extensions.get("oracle.startValueKnown"))) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        oracleOrder(extensions.get("oracle.order"));
    }

    private static List<String> alterColumnProperty(
            SchemaChange change, TableDefinition source, TableDefinition target,
            RenderContext context) {
        for (ColumnDefinition desired : source.columns()) {
            ColumnDefinition current = target.columns().stream()
                    .filter(candidate -> sameColumn(candidate, desired)).findFirst().orElse(null);
            if (current == null) continue;
            String prefix = columnPath(desired);
            String tableName = targetName(target.key(), context);
            String columnName = childName(desired.name());
            if (change.property().path().equals(prefix + ".dataType")
                    && Objects.equals(change.property().sourceValue(), desired.dataType())
                    && Objects.equals(change.property().targetValue(), current.dataType())
                    && !Objects.equals(desired.dataType(), current.dataType())) {
                if (!columnBehaviors(desired).equals(columnBehaviors(current))) {
                    throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
                }
                return List.of("ALTER TABLE " + tableName + " MODIFY (" + columnName + ' '
                        + formattedType(desired, context) + ");");
            }
            if (change.property().path().equals(prefix + ".nullable")
                    && Objects.equals(change.property().sourceValue(), desired.nullable())
                    && Objects.equals(change.property().targetValue(), current.nullable())
                    && desired.nullable() != current.nullable()) {
                return List.of("ALTER TABLE " + tableName + " MODIFY (" + columnName
                        + (desired.nullable() ? " NULL);" : " NOT NULL);"));
            }
            if (change.property().path().equals(prefix + ".normalizedDefault")
                    && Objects.equals(change.property().sourceValue(), desired.normalizedDefault())
                    && Objects.equals(change.property().targetValue(), current.normalizedDefault())
                    && !Objects.equals(desired.normalizedDefault(), current.normalizedDefault())) {
                String desiredDefault = alterDefault(desired, current, context);
                return List.of("ALTER TABLE " + tableName + " MODIFY (" + columnName
                        + " DEFAULT " + desiredDefault + ");");
            }
            if (change.property().path().equals(prefix + ".comment")
                    && Objects.equals(change.property().sourceValue(), desired.comment())
                    && Objects.equals(change.property().targetValue(), current.comment())
                    && !Objects.equals(desired.comment(), current.comment())) {
                return List.of("COMMENT ON COLUMN " + tableName + '.' + columnName + " IS "
                        + (desired.comment() == null || desired.comment().isBlank()
                        ? "''" : sqlString(desired.comment())) + ';');
            }
        }
        return null;
    }

    private static Map<String, String> columnBehaviors(ColumnDefinition column) {
        Map<String, String> result = new java.util.TreeMap<>();
        for (String key : Set.of("oracle.identity", "oracle.identityOptions",
                "oracle.defaultOnNull", "oracle.virtual", "oracle.invisible")) {
            String value = column.dataType().providerExtensions().get(key);
            if (value != null) result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static String alterDefault(
            ColumnDefinition desired, ColumnDefinition current, RenderContext context) {
        Map<String, String> desiredExtensions = desired.dataType().providerExtensions();
        Map<String, String> currentExtensions = current.dataType().providerExtensions();
        if (desiredExtensions.containsKey("oracle.identity")
                || currentExtensions.containsKey("oracle.identity")
                || desiredExtensions.containsKey("oracle.virtual")
                || currentExtensions.containsKey("oracle.virtual")) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String value = desired.normalizedDefault();
        if (value == null || value.isBlank()) return "NULL";
        if ("true".equals(desiredExtensions.get("oracle.defaultOnNull"))) {
            String prefix = "DEFAULT ON NULL ";
            if (!value.startsWith(prefix) || value.length() == prefix.length()) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            return "ON NULL " + renderFragment(value.substring(prefix.length()), context);
        }
        if (desiredExtensions.containsKey("oracle.defaultOnNull")
                || currentExtensions.containsKey("oracle.defaultOnNull")
                || value.stripLeading().startsWith("GENERATED ")
                || value.stripLeading().startsWith("DEFAULT ")) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return renderFragment(value, context);
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
        target.constraints().stream()
                .filter(constraint -> !source.constraints().contains(constraint))
                .sorted(Comparator.comparing(ConstraintDefinition::key))
                .map(constraint -> "ALTER TABLE " + targetName(target.key(), context)
                        + " DROP CONSTRAINT " + nestedObjectName(constraint.key()) + ';')
                .forEach(statements::add);
        source.constraints().stream()
                .filter(constraint -> !target.constraints().contains(constraint))
                .sorted(Comparator.comparing(ConstraintDefinition::key))
                .map(constraint -> "ALTER TABLE " + targetName(target.key(), context)
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
                .filter(index -> !index.providerGeneratedName()
                        && !source.indexes().contains(index))
                .sorted(Comparator.comparing(IndexDefinition::key))
                .map(index -> "DROP INDEX " + targetName(index.key(), context) + ';')
                .forEach(statements::add);
        source.indexes().stream()
                .filter(index -> !index.providerGeneratedName()
                        && !target.indexes().contains(index))
                .sorted(Comparator.comparing(IndexDefinition::key))
                .map(index -> createIndex(index, target.key(), context))
                .forEach(statements::add);
        if (statements.isEmpty()) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return List.copyOf(statements);
    }

    private static boolean sameColumn(ColumnDefinition left, ColumnDefinition right) {
        return left.name().comparisonKey().equals(right.name().comparisonKey());
    }

    private static String columnPath(ColumnDefinition column) {
        return "columns[" + column.name().comparisonKey() + ']';
    }

    private static String formattedType(ColumnDefinition column, RenderContext context) {
        com.datacube.spi.schemadiff.CanonicalDataType type = column.dataType();
        validateColumnExtensions(type.providerExtensions());
        if (type.arrayDimensions() != 0) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        String formatted = type.providerExtensions().get("formattedType");
        if (formatted != null) return renderFragment(formatted, context);
        String base = type.baseType();
        String result;
        if ("NUMBER".equals(base)) {
            if (type.precision() == null && type.scale() != null) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            result = type.precision() == null ? "NUMBER"
                    : "NUMBER(" + type.precision()
                            + (type.scale() == null ? "" : "," + type.scale()) + ')';
        } else if (Set.of("VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR").contains(base)) {
            if (type.length() == null || type.length() <= 0) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            String semantics = type.providerExtensions().get("oracle.lengthSemantics");
            if (semantics != null && !Set.of("BYTE", "CHAR").contains(semantics)) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            result = base + '(' + type.length()
                    + (semantics == null ? "" : " " + semantics) + ')';
        } else if (Set.of("RAW", "UROWID").contains(base)) {
            if (type.length() == null || type.length() <= 0) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            result = base + '(' + type.length() + ')';
        } else if (Set.of("DATE", "BLOB", "CLOB", "NCLOB", "ROWID", "XMLTYPE",
                "BINARY_FLOAT", "BINARY_DOUBLE", "LONG", "LONG RAW").contains(base)
                && type.length() == null && type.precision() == null && type.scale() == null) {
            result = base;
        } else {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return result;
    }

    private static String renderFragment(String fragment, RenderContext context) {
        if (fragment == null || fragment.isBlank() || fragment.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        try {
            return retargetSimpleFragment(fragment.strip(), context);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static String retargetSimpleFragment(String text, RenderContext context) {
        String source = schemaPart(context.sourceSchema());
        String target = schemaPart(context.targetSchema());
        StringBuilder output = new StringBuilder(text.length() + target.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (alternativeQuoteAt(text, index)) {
                int end = alternativeQuoteEnd(text, index);
                output.append(text, index, end);
                index = end;
                continue;
            }
            if (current == ';' || current == '\r' || current == '\n'
                    || current == '-' && charAt(text, index + 1) == '-'
                    || current == '/' && charAt(text, index + 1) == '*') {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            if (current == '\'') {
                int end = singleQuotedEnd(text, index);
                output.append(text, index, end);
                index = end;
                continue;
            }
            SqlIdentifier identifier = sqlIdentifierAt(text, index);
            if (identifier != null) {
                boolean retarget = identifierMatches(identifier, source)
                        && qualifiedDotAt(text, identifier.end());
                output.append(retarget
                        ? OracleSchemaIdentifierNormalizer.quote(target)
                        : text.substring(index, identifier.end()));
                index = identifier.end();
                continue;
            }
            if (Character.isISOControl(current) && current != '\t') {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
            output.append(current);
            index++;
        }
        return output.toString();
    }

    private static int singleQuotedEnd(String text, int start) {
        int index = start + 1;
        while (index < text.length()) {
            if (text.charAt(index) == '\'') {
                if (charAt(text, index + 1) == '\'') {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }
        throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
    }

    private static boolean alternativeQuoteAt(String text, int start) {
        return alternativeQuotePrefixLength(text, start) > 0;
    }

    private static int alternativeQuotePrefixLength(String text, int start) {
        if (start < 0 || start >= text.length()
                || start > 0 && identifierPart(text.charAt(start - 1))) {
            return 0;
        }
        char first = text.charAt(start);
        if ((first == 'q' || first == 'Q')
                && charAt(text, start + 1) == '\''
                && start + 2 < text.length()) {
            return 2;
        }
        return (first == 'n' || first == 'N')
                && (charAt(text, start + 1) == 'q' || charAt(text, start + 1) == 'Q')
                && charAt(text, start + 2) == '\''
                && start + 3 < text.length() ? 3 : 0;
    }

    private static int alternativeQuoteEnd(String text, int start) {
        int prefixLength = alternativeQuotePrefixLength(text, start);
        if (prefixLength == 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        char opener = charAt(text, start + prefixLength);
        if (opener == '\0' || opener == '\'' || Character.isWhitespace(opener)) {
            throw new IllegalArgumentException(UNSAFE_DEFINITION);
        }
        char close = switch (opener) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opener;
        };
        for (int index = start + prefixLength + 1; index < text.length(); index++) {
            if (text.charAt(index) == close && charAt(text, index + 1) == '\'') {
                return index + 2;
            }
        }
        throw new IllegalArgumentException(UNSAFE_DEFINITION);
    }

    private static int lineCommentEnd(String text, int start) {
        int newline = text.indexOf('\n', start + 2);
        return newline < 0 ? text.length() : newline;
    }

    private static int blockCommentEnd(String text, int start) {
        int end = text.indexOf("*/", start + 2);
        if (end < 0) throw new IllegalArgumentException(UNSAFE_DEFINITION);
        return end + 2;
    }

    private static SqlIdentifier sqlIdentifierAt(String text, int start) {
        if (start >= text.length()) return null;
        if (text.charAt(start) == '"') {
            StringBuilder value = new StringBuilder();
            int index = start + 1;
            while (index < text.length()) {
                if (text.charAt(index) == '"') {
                    if (charAt(text, index + 1) == '"') {
                        value.append('"');
                        index += 2;
                    } else {
                        return value.isEmpty() ? null
                                : new SqlIdentifier(value.toString(), true, index + 1);
                    }
                } else {
                    value.append(text.charAt(index++));
                }
            }
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        if (!identifierStart(text.charAt(start))) return null;
        int end = start + 1;
        while (end < text.length() && identifierPart(text.charAt(end))) end++;
        return new SqlIdentifier(text.substring(start, end), false, end);
    }

    private static boolean qualifiedDotAt(String text, int start) {
        int index = skipTrivia(text, start);
        if (charAt(text, index) != '.') return false;
        index = skipTrivia(text, index + 1);
        return sqlIdentifierAt(text, index) != null;
    }

    private static boolean identifierMatches(SqlIdentifier identifier, String expected) {
        return identifierMatches(identifier, expected, true);
    }

    private static boolean identifierMatches(
            SqlIdentifier identifier, String expected, boolean schema) {
        if (identifier.quoted()) return identifier.value().equals(expected);
        boolean requiresQuoting = schema
                ? OracleSchemaIdentifierNormalizer.schema(expected).quoted()
                : OracleSchemaIdentifierNormalizer.child(expected).quoted();
        return !requiresQuoting
                && identifier.value().equalsIgnoreCase(expected);
    }

    private static boolean identifierStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean identifierPart(char value) {
        return value == '_' || value == '$' || value == '#' || Character.isLetterOrDigit(value);
    }

    private static char charAt(String value, int index) {
        return index >= 0 && index < value.length() ? value.charAt(index) : '\0';
    }

    private static String sqlString(String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return '\'' + value.replace("'", "''") + '\'';
    }

    private static void rejectDuplicateKeys(List<ObjectKey> keys) {
        if (Set.copyOf(keys).size() != keys.size()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private record SqlIdentifier(String value, boolean quoted, int end) {
    }

    private static String createSequence(SequenceDefinition sequence, RenderContext context) {
        validateTopLevelKey(sequence.key(), ObjectType.SEQUENCE);
        Map<String, String> extensions = sequence.providerExtensions();
        validateSequenceExtensions(extensions);
        if (!"true".equals(extensions.get("oracle.startValueKnown"))) {
            throw new IllegalArgumentException(UNKNOWN_SEQUENCE_START);
        }
        String order = oracleOrder(extensions.get("oracle.order"));
        String start = requireInteger(sequence.startValue(), false);
        String increment = requireInteger(sequence.incrementBy(), false);
        String minimum = requireInteger(sequence.minimumValue(), true);
        String maximum = requireInteger(sequence.maximumValue(), true);
        Integer cache = sequence.cacheSize();
        if (cache != null && cache < 0) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);

        StringBuilder sql = new StringBuilder("CREATE SEQUENCE ")
                .append(targetName(sequence.key(), context))
                .append(" START WITH ").append(start)
                .append(" INCREMENT BY ").append(increment)
                .append(minimum == null ? " NOMINVALUE" : " MINVALUE " + minimum)
                .append(maximum == null ? " NOMAXVALUE" : " MAXVALUE " + maximum)
                .append(sequence.cycle() ? " CYCLE" : " NOCYCLE")
                .append(cache == null || cache == 0 ? " NOCACHE" : " CACHE " + cache)
                .append(' ').append(order);
        return sql.append(';').toString();
    }

    private static String oracleOrder(String value) {
        if (!"ORDER".equals(value) && !"NOORDER".equals(value)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return value;
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

    private static String renderDrop(SchemaChange change, RenderContext context) {
        ObjectKey key = change.target().key();
        if (change.target() instanceof TableDefinition) {
            validateTopLevelKey(key, ObjectType.TABLE);
        } else if (change.target() instanceof SequenceDefinition) {
            validateTopLevelKey(key, ObjectType.SEQUENCE);
        }
        String name = targetName(key, context);
        if (change.target() instanceof DefinitionObject definition) {
            renderDefinition(definition, context, false);
        }
        return switch (key.type()) {
            case TABLE -> "DROP TABLE " + name + ';';
            case SEQUENCE -> "DROP SEQUENCE " + name + ';';
            case VIEW -> "DROP VIEW " + name + ';';
            case MATERIALIZED_VIEW -> "DROP MATERIALIZED VIEW " + name + ';';
            case FUNCTION -> "DROP FUNCTION " + name + ';';
            case PROCEDURE -> "DROP PROCEDURE " + name + ';';
            case TRIGGER -> "DROP TRIGGER " + name + ';';
            case PACKAGE_SPEC -> "DROP PACKAGE " + name + ';';
            case PACKAGE_BODY -> "DROP PACKAGE BODY " + name + ';';
            case TYPE -> switch (key.signature()) {
                case "SPEC" -> "DROP TYPE " + name + ';';
                case "BODY" -> "DROP TYPE BODY " + name + ';';
                default -> throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            };
            default -> throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        };
    }

    private static String targetName(ObjectKey key, RenderContext context) {
        String owner = objectOwner(key);
        if (!owner.equals(schemaPart(context.sourceSchema()))
                && !owner.equals(schemaPart(context.targetSchema()))) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return OracleSchemaIdentifierNormalizer.quote(schemaPart(context.targetSchema())) + '.'
                + OracleSchemaIdentifierNormalizer.quote(objectPart(key));
    }

    private static String schemaPart(com.datacube.spi.schemadiff.QualifiedName name) {
        String comparisonKey = name.comparisonKey();
        if (!comparisonKey.startsWith(SCHEMA_KEY_DOMAIN)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        Field field = fieldAt(comparisonKey, SCHEMA_KEY_DOMAIN.length());
        if (field.next() != comparisonKey.length()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return field.value();
    }

    private static String objectPart(ObjectKey key) {
        String comparisonKey = key.name().comparisonKey();
        if (!comparisonKey.startsWith(OBJECT_KEY_DOMAIN)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        Field owner = fieldAt(comparisonKey, OBJECT_KEY_DOMAIN.length());
        Field object = fieldAt(comparisonKey, owner.next());
        if (object.next() != comparisonKey.length()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return object.value();
    }

    private static String objectOwner(ObjectKey key) {
        String comparisonKey = key.name().comparisonKey();
        if (!comparisonKey.startsWith(OBJECT_KEY_DOMAIN)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        Field owner = fieldAt(comparisonKey, OBJECT_KEY_DOMAIN.length());
        Field object = fieldAt(comparisonKey, owner.next());
        if (object.next() != comparisonKey.length()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return owner.value();
    }

    private static String childName(com.datacube.spi.schemadiff.QualifiedName name) {
        String comparisonKey = name.comparisonKey();
        if (!comparisonKey.startsWith(CHILD_KEY_DOMAIN)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        Field child = fieldAt(comparisonKey, CHILD_KEY_DOMAIN.length());
        if (child.next() != comparisonKey.length()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return OracleSchemaIdentifierNormalizer.quote(child.value());
    }

    private static String nestedObjectName(ObjectKey key) {
        return OracleSchemaIdentifierNormalizer.quote(objectPart(key));
    }

    private static Field fieldAt(String value, int start) {
        int colon = value.indexOf(':', start);
        if (colon <= start) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        String lengthText = value.substring(start, colon);
        if (!lengthText.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        int length;
        try {
            length = Integer.parseInt(lengthText);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        int valueStart = colon + 1;
        int end = valueStart + length;
        if (length == 0 || end < valueStart || end > value.length()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String decoded = value.substring(valueStart, end);
        if (decoded.indexOf('\0') >= 0) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        return new Field(decoded, end);
    }

    private record Field(String value, int next) {
    }

    private static void validateTopLevelKey(ObjectKey key, ObjectType expectedType) {
        if (key.type() != expectedType || !key.signature().isEmpty()) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static void validateDefinitionDependencyShape(DefinitionObject definition) {
        ObjectKey key = definition.key();
        if (key.type() == ObjectType.PACKAGE_BODY) {
            boolean specPresent = definition.dependencies().stream().anyMatch(dependency ->
                    dependency.type() == ObjectType.PACKAGE_SPEC
                            && dependency.signature().isEmpty()
                            && dependency.name().comparisonKey()
                                    .equals(key.name().comparisonKey()));
            if (!specPresent) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        } else if (key.type() == ObjectType.TYPE && key.signature().equals("BODY")) {
            boolean specPresent = definition.dependencies().stream().anyMatch(dependency ->
                    dependency.type() == ObjectType.TYPE
                            && dependency.signature().equals("SPEC")
                            && dependency.name().comparisonKey()
                                    .equals(key.name().comparisonKey()));
            if (!specPresent) throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }

    private static void validateColumnExtensions(Map<String, String> extensions) {
        Set<String> allowed = Set.of(
                "formattedType", "oracle.typeOwner", "oracle.lengthSemantics",
                "oracle.typeModifier", "oracle.timeZone", "oracle.identity",
                "oracle.identityOptions", "oracle.defaultOnNull", "oracle.virtual",
                "oracle.invisible");
        if (!allowed.containsAll(extensions.keySet())) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        for (Map.Entry<String, String> extension : extensions.entrySet()) {
            if (extension.getValue() == null || extension.getValue().isBlank()
                    || extension.getValue().indexOf('\0') >= 0) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
        }
        for (String booleanKey : List.of(
                "oracle.defaultOnNull", "oracle.virtual", "oracle.invisible")) {
            String value = extensions.get(booleanKey);
            if (value != null && !value.equals("true")) {
                throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
            }
        }
        String semantics = extensions.get("oracle.lengthSemantics");
        if (semantics != null && !Set.of("BYTE", "CHAR").contains(semantics)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        String timeZone = extensions.get("oracle.timeZone");
        if (timeZone != null && !Set.of(
                "WITH TIME ZONE", "WITH LOCAL TIME ZONE").contains(timeZone)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        if (extensions.containsKey("oracle.typeModifier")) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        if (extensions.containsKey("oracle.identityOptions")
                && !extensions.containsKey("oracle.identity")) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
    }
}
