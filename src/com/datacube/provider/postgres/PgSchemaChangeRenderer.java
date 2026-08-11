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
        boolean destructive = change.automation() == AutomationLevel.DESTRUCTIVE_OPT_IN;
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
        String retargeted = retargetDefinition(normalized, definition.key().type(), context);
        if (definition.key().type() == ObjectType.TRIGGER) {
            ObjectKey owner = triggerOwner(definition);
            String triggerHeader = "CREATE TRIGGER " + nestedObjectName(definition.key());
            if (!retargeted.startsWith(triggerHeader + ' ')
                    || !retargeted.contains(" ON " + targetName(owner, context) + ' ')) {
                throw new IllegalArgumentException(UNSAFE_DEFINITION);
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
                if (identifier.value().equals(source)
                        && identifier.end() < definition.length()
                        && definition.charAt(identifier.end()) == '.') {
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
                if (identifier.equals(source) && end < definition.length()
                        && definition.charAt(end) == '.') {
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
                if (identifier.value().equals(schema)
                        && identifier.end() < text.length()
                        && text.charAt(identifier.end()) == '.') {
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
                if (text.substring(index, end).equals(schema)
                        && end < text.length() && text.charAt(end) == '.') {
                    return true;
                }
                index = end;
            } else {
                index++;
            }
        }
        return false;
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
        int index = start;
        output.append(text.charAt(index++));
        while (index < text.length()) {
            char current = text.charAt(index);
            output.append(current);
            index++;
            if (current == '\\' && index < text.length()) {
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
                && change.property().path().equals(columnPath(column))) {
            return List.of("ALTER TABLE " + targetName(source.key(), context)
                    + " ADD COLUMN " + columnClause(column, context) + ';');
        }
        if (sourceValue == null && change.property().targetValue() instanceof ColumnDefinition column
                && change.property().path().equals(columnPath(column))
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
            case FUNCTION -> "DROP FUNCTION " + name + '(' + routineSignature(key.signature()) + ");";
            case PROCEDURE -> "DROP PROCEDURE " + name + '(' + routineSignature(key.signature()) + ");";
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

    private static String routineSignature(String signature) {
        if (signature == null || !safeRoutineSignature(signature)) {
            throw new IllegalArgumentException(UNSUPPORTED_SHAPE);
        }
        return signature.strip();
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
