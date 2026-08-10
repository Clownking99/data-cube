package com.datacube.provider.postgres;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.SnapshotFingerprint;
import com.datacube.spi.schemadiff.TableDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSchemaSnapshotReaderTest {
    @Test
    void readsTableColumnsConstraintsExpressionIndexesAndSequencesFromPgCatalog() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales Data")
                .rows("tables", row(
                        "object_oid", 10L,
                        "object_name", "Order\"Line"))
                .rows("columns",
                        row("table_oid", 10L, "column_name", "amount", "ordinal_position", 1,
                                "base_type", "numeric", "character_length", null,
                                "numeric_precision", 12, "numeric_scale", 3,
                                "with_time_zone", false, "array_dimensions", 0,
                                "formatted_type", "numeric(12,3)", "type_schema", "pg_catalog",
                                "nullable", false, "default_expression", " 0.000 ", "comment", "money"),
                        row("table_oid", 10L, "column_name", "When", "ordinal_position", 2,
                                "base_type", "timestamptz", "character_length", null,
                                "numeric_precision", null, "numeric_scale", null,
                                "with_time_zone", true, "array_dimensions", 2,
                                "formatted_type", "timestamp with time zone[]", "type_schema", "pg_catalog",
                                "nullable", true, "default_expression", null, "comment", null))
                .rows("constraints",
                        row("constraint_oid", 20L, "table_oid", 10L, "constraint_name", "Order_pkey",
                                "constraint_type", "p", "position", 1, "column_name", "amount",
                                "referenced_table_oid", null, "referenced_column_name", null,
                                "check_expression", null, "update_action", null, "delete_action", null,
                                "provider_generated", false),
                        row("constraint_oid", 21L, "table_oid", 10L, "constraint_name", "uq_when",
                                "constraint_type", "u", "position", 1, "column_name", "When",
                                "referenced_table_oid", null, "referenced_column_name", null,
                                "check_expression", null, "update_action", null, "delete_action", null,
                                "provider_generated", false),
                        row("constraint_oid", 22L, "table_oid", 10L, "constraint_name", "fk_self",
                                "constraint_type", "f", "position", 1, "column_name", "amount",
                                "referenced_table_oid", 10L, "referenced_column_name", "amount",
                                "check_expression", null, "update_action", "CASCADE", "delete_action", "SET NULL",
                                "provider_generated", false),
                        row("constraint_oid", 23L, "table_oid", 10L, "constraint_name", "amount_check",
                                "constraint_type", "c", "position", null, "column_name", null,
                                "referenced_table_oid", null, "referenced_column_name", null,
                                "check_expression", " amount > 0; ", "update_action", null, "delete_action", null,
                                "provider_generated", false))
                .rows("indexes",
                        row("index_oid", 30L, "table_oid", 10L, "index_name", "amount_expr_idx",
                                "is_unique", false, "position", 1, "index_expression", "lower((amount)::text)",
                                "predicate", " amount > 0; ", "provider_generated", false),
                        row("index_oid", 30L, "table_oid", 10L, "index_name", "amount_expr_idx",
                                "is_unique", false, "position", 2, "index_expression", "(amount + 1)",
                                "predicate", " amount > 0; ", "provider_generated", false))
                .rows("sequences", row(
                        "object_oid", 40L, "object_name", "Order_seq", "start_value", "10",
                        "increment_by", "5", "minimum_value", "10", "maximum_value", "9999",
                        "cycle", true, "cache_size", 20));
        SqlExecutionControl control = new SqlExecutionControl();

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection-original", PgSchemaIdentifierNormalizer.schema("Sales Data"),
                new SqlExecutionOptions(0, 7, control));

        TableDefinition table = assertInstanceOf(TableDefinition.class,
                snapshot.objects().get(key(ObjectType.TABLE, "Sales Data", "Order\"Line")));
        assertEquals(List.of("\"amount\"", "\"When\""),
                table.columns().stream().map(column -> column.name().original()).toList());
        CanonicalDataType amountType = table.columns().getFirst().dataType();
        assertEquals("numeric", amountType.baseType());
        assertEquals(12, amountType.precision());
        assertEquals(3, amountType.scale());
        assertEquals("0.000", table.columns().getFirst().normalizedDefault());
        CanonicalDataType timestampArray = table.columns().get(1).dataType();
        assertTrue(timestampArray.withTimeZone());
        assertEquals(2, timestampArray.arrayDimensions());
        assertEquals("timestamp with time zone[]", timestampArray.providerExtensions().get("formattedType"));

        assertEquals(Set.of(ConstraintKind.CHECK, ConstraintKind.FOREIGN_KEY,
                        ConstraintKind.PRIMARY_KEY, ConstraintKind.UNIQUE),
                table.constraints().stream().map(ConstraintDefinition::kind)
                        .collect(java.util.stream.Collectors.toSet()));
        ConstraintDefinition foreignKey = table.constraints().stream()
                .filter(constraint -> constraint.kind() == ConstraintKind.FOREIGN_KEY).findFirst().orElseThrow();
        assertEquals(table.key(), foreignKey.referencedTable());
        assertEquals(List.of("\"amount\""),
                foreignKey.referencedColumns().stream().map(name -> name.original()).toList());
        assertEquals("CASCADE", foreignKey.updateAction());
        assertEquals("SET NULL", foreignKey.deleteAction());
        ConstraintDefinition check = table.constraints().stream()
                .filter(constraint -> constraint.kind() == ConstraintKind.CHECK).findFirst().orElseThrow();
        assertEquals("amount > 0", check.normalizedExpression());

        assertEquals(1, table.indexes().size());
        assertEquals(List.of("lower((amount)::text)", "(amount + 1)"),
                table.indexes().getFirst().normalizedExpressions());
        assertEquals("amount > 0", table.indexes().getFirst().normalizedPredicate());

        SequenceDefinition sequence = assertInstanceOf(SequenceDefinition.class,
                snapshot.objects().get(key(ObjectType.SEQUENCE, "Sales Data", "Order_seq")));
        assertEquals("10", sequence.startValue());
        assertEquals("5", sequence.incrementBy());
        assertEquals(20, sequence.cacheSize());
        assertTrue(sequence.cycle());
        assertTrue(snapshot.completeness().complete());
        assertTrue(snapshot.completeness().unavailableScopes().isEmpty());
        assertFalse(control.hasActiveStatement());
        assertEquals("connection-original", snapshot.connectionId());
        assertEquals(64, snapshot.fingerprint().length());
    }

    @Test
    void readsDefinitionObjectsOverloadsTypesAndOnlyMappedInSchemaDependencies() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales Data")
                .rows("tables", row("object_oid", 10L, "object_name", "Order\"Line"))
                .rows("views",
                        row("object_oid", 60L, "object_name", "order_view", "relation_kind", "v",
                                "definition", " SELECT amount FROM \"Sales Data\".\"Order\"\"Line\"; "),
                        row("object_oid", 61L, "object_name", "order_mv", "relation_kind", "m",
                                "definition", "SELECT amount FROM \"Sales Data\".\"Order\"\"Line\";"))
                .rows("routines",
                        row("object_oid", 10L, "object_name", "calculate", "routine_kind", "f",
                                "identity_arguments", "integer", "definition",
                                " CREATE FUNCTION \"Sales Data\".calculate(integer) RETURNS integer\n"
                                        + "LANGUAGE sql AS $$ SELECT $1 + 1; $$; "),
                        row("object_oid", 51L, "object_name", "calculate", "routine_kind", "f",
                                "identity_arguments", "text", "definition",
                                "CREATE FUNCTION \"Sales Data\".calculate(text) RETURNS text\n"
                                        + "LANGUAGE sql AS $$ SELECT $1; $$;"),
                        row("object_oid", 52L, "object_name", "refresh_orders", "routine_kind", "p",
                                "identity_arguments", "", "definition",
                                "CREATE PROCEDURE \"Sales Data\".refresh_orders() LANGUAGE sql AS $$ SELECT 1; $$;"))
                .rows("triggers", row("object_oid", 70L, "object_name", "audit_trigger", "table_oid", 10L,
                        "definition", "CREATE TRIGGER audit_trigger AFTER INSERT ON \"Sales Data\".\"Order\"\"Line\" "
                                + "FOR EACH ROW EXECUTE FUNCTION audit();"))
                .rows("enums",
                        row("type_oid", 80L, "array_oid", 180L, "type_name", "order_state", "sort_order", 1,
                                "enum_label", "new"),
                        row("type_oid", 80L, "array_oid", 180L, "type_name", "order_state", "sort_order", 2,
                                "enum_label", "won't ship"))
                .rows("composites",
                        row("type_oid", 81L, "array_oid", 281L, "relation_oid", 181L,
                                "type_name", "order_pair", "position", 1,
                                "attribute_name", "Left\"Side", "attribute_type", "integer"),
                        row("type_oid", 81L, "array_oid", 281L, "relation_oid", 181L,
                                "type_name", "order_pair", "position", 2,
                                "attribute_name", "state", "attribute_type", "\"Sales Data\".order_state"))
                .rows("domains", row("type_oid", 82L, "array_oid", 282L, "type_name", "positive_amount",
                        "base_type", "numeric(12,3)", "not_null", true, "default_expression", "1.000",
                        "constraint_oid", 182L, "constraint_name", "positive_amount_check",
                        "constraint_definition", "CHECK ((VALUE > (0)::numeric))"))
                .rows("dependencies",
                        row("source_catalog", "pg_proc", "source_oid", 10L, "source_schema", "Sales Data",
                                "target_catalog", "pg_class", "target_oid", 10L, "target_schema", "Sales Data"),
                        row("source_catalog", "pg_class", "source_oid", 60L, "source_schema", "Sales Data",
                                "target_catalog", "pg_class", "target_oid", 10L, "target_schema", "Sales Data"),
                        row("source_catalog", "pg_class", "source_oid", 181L, "source_schema", "Sales Data",
                                "target_catalog", "pg_type", "target_oid", 80L, "target_schema", "Sales Data"),
                        row("source_catalog", "pg_class", "source_oid", 10L, "source_schema", "Sales Data",
                                "target_catalog", "pg_type", "target_oid", 180L, "target_schema", "Sales Data"),
                        row("source_catalog", "pg_proc", "source_oid", 51L, "source_schema", "Sales Data",
                                "target_catalog", "pg_class", "target_oid", 999L, "target_schema", "Sales Data"),
                        row("source_catalog", "pg_proc", "source_oid", 52L, "source_schema", "Sales Data",
                                "target_catalog", "pg_type", "target_oid", 23L, "target_schema", "pg_catalog"));

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection-original", PgSchemaIdentifierNormalizer.schema("Sales Data"),
                new SqlExecutionOptions(0, 11, new SqlExecutionControl()));

        DefinitionObject view = definition(snapshot, ObjectType.VIEW, "order_view", "");
        assertEquals(DefinitionConfidence.HIGH, view.confidence());
        assertEquals("CREATE VIEW \"Sales Data\".\"order_view\" AS\n"
                        + " SELECT amount FROM \"Sales Data\".\"Order\"\"Line\"",
                view.normalizedDefinition());
        assertEquals(Set.of(key(ObjectType.TABLE, "Sales Data", "Order\"Line")), view.dependencies());
        assertEquals(DefinitionConfidence.HIGH,
                definition(snapshot, ObjectType.MATERIALIZED_VIEW, "order_mv", "").confidence());

        DefinitionObject integerOverload = definition(snapshot, ObjectType.FUNCTION, "calculate", "integer");
        DefinitionObject textOverload = definition(snapshot, ObjectType.FUNCTION, "calculate", "text");
        assertEquals(Set.of(key(ObjectType.TABLE, "Sales Data", "Order\"Line")), integerOverload.dependencies());
        assertTrue(integerOverload.originalDefinition().contains("SELECT $1 + 1;"));
        assertTrue(textOverload.dependencies().isEmpty());
        assertEquals(DefinitionConfidence.HIGH,
                definition(snapshot, ObjectType.PROCEDURE, "refresh_orders", "").confidence());
        assertEquals(DefinitionConfidence.HIGH,
                definition(snapshot, ObjectType.TRIGGER, "audit_trigger",
                        key(ObjectType.TABLE, "Sales Data", "Order\"Line").name().comparisonKey()).confidence());

        DefinitionObject enumType = definition(snapshot, ObjectType.TYPE, "order_state", "enum");
        assertTrue(enumType.normalizedDefinition().contains("'won''t ship'"));
        TableDefinition table = assertInstanceOf(TableDefinition.class,
                snapshot.objects().get(key(ObjectType.TABLE, "Sales Data", "Order\"Line")));
        assertEquals(Set.of(enumType.key()), table.dependencies());
        DefinitionObject composite = definition(snapshot, ObjectType.TYPE, "order_pair", "composite");
        assertTrue(composite.normalizedDefinition().contains("\"Left\"\"Side\" integer"));
        assertEquals(Set.of(enumType.key()), composite.dependencies());
        DefinitionObject domain = definition(snapshot, ObjectType.TYPE, "positive_amount", "domain");
        assertTrue(domain.normalizedDefinition().contains("DEFAULT 1.000 NOT NULL"));
        assertTrue(domain.normalizedDefinition().contains(
                "CONSTRAINT \"positive_amount_check\" CHECK ((VALUE > (0)::numeric))"));
        assertEquals(DefinitionConfidence.HIGH, domain.confidence());

        assertFalse(snapshot.completeness().complete());
        assertEquals(SnapshotCompleteness.DEPENDENCY_UNRESOLVED,
                snapshot.completeness().unavailableScopes().get(ObjectType.FUNCTION));
        assertNull(snapshot.completeness().unavailableScopes().get(ObjectType.PROCEDURE),
                "dependencies outside the requested schema must be ignored");
    }

    @Test
    void ordinaryMetadataFailuresAndMissingDefinitionsProduceOnlyFixedPartialDiagnostics() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("tables", row("object_oid", 1L, "object_name", "orders"))
                .rows("routines", row("object_oid", 2L, "object_name", "missing_body",
                        "routine_kind", "f", "identity_arguments", "integer", "definition", null))
                .rows("views", row("object_oid", 3L, "object_name", "missing_view",
                        "relation_kind", "v", "definition", "   "))
                .fail("indexes", new SQLException(
                        "permission denied for schema app at jdbc:postgresql://host/db?password=secret", "42501"))
                .fail("triggers", new SQLException(
                        "select * from secret.app_trigger; credential=secret", "XX000"));
        SqlExecutionControl control = new SqlExecutionControl();

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection-id", PgSchemaIdentifierNormalizer.schema("app"),
                new SqlExecutionOptions(0, 5, control));

        assertFalse(snapshot.completeness().complete());
        assertEquals(SnapshotCompleteness.PERMISSION_DENIED,
                snapshot.completeness().unavailableScopes().get(ObjectType.INDEX));
        assertEquals(SnapshotCompleteness.METADATA_UNAVAILABLE,
                snapshot.completeness().unavailableScopes().get(ObjectType.TRIGGER));
        assertEquals(SnapshotCompleteness.DEFINITION_UNAVAILABLE,
                snapshot.completeness().unavailableScopes().get(ObjectType.FUNCTION));
        assertEquals(Set.of(SnapshotCompleteness.PERMISSION_DENIED,
                        SnapshotCompleteness.METADATA_UNAVAILABLE,
                        SnapshotCompleteness.DEFINITION_UNAVAILABLE),
                Set.copyOf(snapshot.completeness().unavailableScopes().values()));
        assertEquals(DefinitionConfidence.LOW,
                definition(snapshot, ObjectType.FUNCTION, "app", "missing_body", "integer").confidence());
        assertEquals(DefinitionConfidence.LOW,
                definition(snapshot, ObjectType.VIEW, "app", "missing_view", "").confidence());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void timeoutAndPostgresCancellationRemainTerminalAndDoNotExposeDriverDetails() {
        SQLTimeoutException driverTimeout = new SQLTimeoutException(
                "SELECT secret FROM app.t jdbc:postgresql://host/db password=secret", "57000");
        SnapshotJdbc timeoutJdbc = new SnapshotJdbc("app").fail("sequences", driverTimeout);

        SQLTimeoutException timeout = assertThrows(SQLTimeoutException.class,
                () -> new PgSchemaSnapshotReader(timeoutJdbc.connection()).read(
                        "connection-id", PgSchemaIdentifierNormalizer.schema("app"),
                        new SqlExecutionOptions(0, 5, new SqlExecutionControl())));

        assertFalse(timeout.getMessage().contains("secret"));
        assertFalse(timeout.getMessage().contains("jdbc:"));

        SnapshotJdbc cancelJdbc = new SnapshotJdbc("app").fail("domains",
                new SQLException("driver echoed SELECT and app and credential=secret", "57014"));
        SQLException cancelled = assertThrows(SQLException.class,
                () -> new PgSchemaSnapshotReader(cancelJdbc.connection()).read(
                        "connection-id", PgSchemaIdentifierNormalizer.schema("app"),
                        new SqlExecutionOptions(0, 5, new SqlExecutionControl())));

        assertEquals("57014", cancelled.getSQLState());
        assertFalse(cancelled.getMessage().contains("secret"));
        assertFalse(cancelled.getMessage().contains("app"));
        assertFalse(cancelled.getMessage().contains("SELECT"));
    }

    @Test
    void fingerprintIsCanonicalWhileConnectionIdentityAndCaptureTimeRemainSnapshotMetadata() throws Exception {
        SnapshotJdbc firstJdbc = new SnapshotJdbc("app")
                .rows("tables",
                        row("object_oid", 2L, "object_name", "z_table"),
                        row("object_oid", 1L, "object_name", "a_table"))
                .rows("sequences", row("object_oid", 3L, "object_name", "a_sequence",
                        "start_value", "1", "increment_by", "1", "minimum_value", "1",
                        "maximum_value", "99", "cycle", false, "cache_size", 1));
        SnapshotJdbc secondJdbc = new SnapshotJdbc("app")
                .rows("tables",
                        row("object_oid", 1L, "object_name", "a_table"),
                        row("object_oid", 2L, "object_name", "z_table"))
                .rows("sequences", row("object_oid", 3L, "object_name", "a_sequence",
                        "start_value", "1", "increment_by", "1", "minimum_value", "1",
                        "maximum_value", "99", "cycle", false, "cache_size", 1));
        Instant before = Instant.now();

        SchemaSnapshot first = new PgSchemaSnapshotReader(firstJdbc.connection()).read(
                "connection-a", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot second = new PgSchemaSnapshotReader(secondJdbc.connection()).read(
                "connection-b", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
        Instant after = Instant.now();

        assertEquals("connection-a", first.connectionId());
        assertEquals("connection-b", second.connectionId());
        assertFalse(first.capturedAt().isBefore(before));
        assertFalse(second.capturedAt().isAfter(after));
        assertEquals(List.copyOf(new java.util.TreeSet<>(first.objects().keySet())),
                List.copyOf(first.objects().keySet()));
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals(SnapshotFingerprint.compute(first), first.fingerprint());
        assertEquals(SnapshotFingerprint.compute(second), second.fingerprint());
    }

    private static DefinitionObject definition(SchemaSnapshot snapshot, ObjectType type,
                                               String name, String signature) {
        return definition(snapshot, type, "Sales Data", name, signature);
    }

    private static DefinitionObject definition(SchemaSnapshot snapshot, ObjectType type,
                                               String schema, String name, String signature) {
        return assertInstanceOf(DefinitionObject.class,
                snapshot.objects().get(new ObjectKey(type,
                        PgSchemaIdentifierNormalizer.object(schema, name), signature)));
    }

    private static ObjectKey key(ObjectType type, String schema, String name) {
        return new ObjectKey(type, PgSchemaIdentifierNormalizer.object(schema, name), "");
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    static final class SnapshotJdbc {
        private final String expectedSchema;
        private final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        private final Map<String, SQLException> failures = new LinkedHashMap<>();
        private final List<StatementTrace> statements = new ArrayList<>();

        SnapshotJdbc(String expectedSchema) {
            this.expectedSchema = expectedSchema;
        }

        @SafeVarargs
        final SnapshotJdbc rows(String tag, Map<String, Object>... queryRows) {
            rows.put(tag, List.of(queryRows));
            return this;
        }

        SnapshotJdbc fail(String tag, SQLException failure) {
            failures.put(tag, failure);
            return this;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> preparedStatement((String) args[0]);
                        case "isClosed" -> false;
                        case "toString" -> "snapshot-jdbc-proxy";
                        default -> defaultValue(method.getReturnType());
                    });
        }

        List<StatementTrace> statements() {
            return List.copyOf(statements);
        }

        private PreparedStatement preparedStatement(String sql) {
            assertTrue(sql.contains("pg_catalog."), "snapshot SQL must use pg_catalog: " + sql);
            assertFalse(sql.contains(expectedSchema), "schema must never be concatenated into SQL");
            StatementTrace trace = new StatementTrace(tag(sql), sql);
            statements.add(trace);
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setString" -> {
                            trace.bindings.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "setQueryTimeout" -> {
                            trace.timeout = (Integer) args[0];
                            yield null;
                        }
                        case "executeQuery" -> {
                            assertEquals(expectedSchema, trace.bindings.get(1));
                            trace.executed = true;
                            SQLException failure = failures.get(trace.tag);
                            if (failure != null) throw failure;
                            yield resultSet(rows.getOrDefault(trace.tag, List.of()));
                        }
                        case "cancel" -> {
                            trace.cancelled = true;
                            yield null;
                        }
                        case "close" -> {
                            trace.closed = true;
                            yield null;
                        }
                        case "isClosed" -> trace.closed;
                        case "toString" -> "prepared-" + trace.tag;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet resultSet(List<Map<String, Object>> queryRows) {
            int[] cursor = {-1};
            AtomicBoolean wasNull = new AtomicBoolean();
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> ++cursor[0] < queryRows.size();
                        case "getString" -> string(value(queryRows, cursor[0], args[0], wasNull));
                        case "getLong" -> number(value(queryRows, cursor[0], args[0], wasNull)).longValue();
                        case "getInt" -> number(value(queryRows, cursor[0], args[0], wasNull)).intValue();
                        case "getBoolean" -> bool(value(queryRows, cursor[0], args[0], wasNull));
                        case "getObject" -> value(queryRows, cursor[0], args[0], wasNull);
                        case "wasNull" -> wasNull.get();
                        case "close" -> null;
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static Object value(List<Map<String, Object>> queryRows, int cursor,
                                    Object label, AtomicBoolean wasNull) {
            Map<String, Object> row = queryRows.get(cursor);
            Object value = label instanceof Integer position
                    ? row.values().stream().skip(position - 1L).findFirst().orElse(null)
                    : row.get((String) label);
            wasNull.set(value == null);
            return value;
        }

        private static String tag(String sql) {
            int start = sql.indexOf("snapshot:");
            int end = sql.indexOf("*/", start);
            return sql.substring(start + "snapshot:".length(), end).trim();
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }

        private static Number number(Object value) {
            return value instanceof Number number ? number : 0;
        }

        private static boolean bool(Object value) {
            if (value instanceof Boolean bool) return bool;
            return value != null && Boolean.parseBoolean(value.toString());
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0D;
            if (type == float.class) return 0F;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return '\0';
            return null;
        }

        static final class StatementTrace {
            private final String tag;
            private final String sql;
            private final Map<Integer, Object> bindings = new LinkedHashMap<>();
            private int timeout = -1;
            private boolean executed;
            private boolean cancelled;
            private boolean closed;

            private StatementTrace(String tag, String sql) {
                this.tag = tag;
                this.sql = sql;
            }
        }
    }
}
