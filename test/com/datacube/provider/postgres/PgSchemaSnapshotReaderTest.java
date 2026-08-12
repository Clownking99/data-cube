package com.datacube.provider.postgres;

import com.datacube.schemadiff.DifferenceKind;
import com.datacube.schemadiff.SchemaDiffEngine;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
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
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSchemaSnapshotReaderTest {
    @Test
    void jdbcProxySnapshotsWithDifferentOwnersCompareByProviderRelativeIdentity() throws Exception {
        SnapshotJdbc sourceJdbc = new SnapshotJdbc("Source\"Owner")
                .rows("tables", row("object_oid", 10L, "object_name", "Order\"Line"))
                .rows("columns", column(10L, "Id", 1, "int8", 0, null, "", ""));
        SnapshotJdbc targetJdbc = new SnapshotJdbc("Target\"Owner")
                .rows("tables", row("object_oid", 20L, "object_name", "Order\"Line"))
                .rows("columns", column(20L, "Id", 1, "int8", 0, null, "", ""));
        SchemaSnapshot source = new PgSchemaSnapshotReader(sourceJdbc.connection()).read(
                "source", PgSchemaIdentifierNormalizer.schema("Source\"Owner"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new PgSchemaSnapshotReader(targetJdbc.connection()).read(
                "target", PgSchemaIdentifierNormalizer.schema("Target\"Owner"),
                SqlExecutionOptions.defaults(0));

        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new PgSchemaDiffCapability().comparisonProjector());

        assertTrue(diff.differences().stream()
                .allMatch(difference -> difference.kind() == DifferenceKind.EQUIVALENT));
        assertTrue(diff.renameSuggestions().isEmpty());
    }

    @Test
    void constraintBackingIndexesUseConindidAndDoNotRenderAsStandaloneIndexes() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("tables", row("object_oid", 10L, "object_name", "orders"))
                .rows("columns",
                        column(10L, "id", 1, "int8", 0, null, "", ""),
                        column(10L, "code", 2, "text", 0, null, "", ""))
                .rows("constraints",
                        row("constraint_oid", 20L, "table_oid", 10L,
                                "constraint_name", "orders_pkey", "constraint_type", "p",
                                "position", 1, "column_name", "id",
                                "referenced_table_oid", null, "referenced_column_name", null,
                                "check_expression", null, "update_action", null,
                                "delete_action", null, "provider_generated", false),
                        row("constraint_oid", 21L, "table_oid", 10L,
                                "constraint_name", "orders_code_key", "constraint_type", "u",
                                "position", 1, "column_name", "code",
                                "referenced_table_oid", null, "referenced_column_name", null,
                                "check_expression", null, "update_action", null,
                                "delete_action", null, "provider_generated", false))
                .rows("indexes",
                        row("index_oid", 32L, "table_oid", 10L,
                                "index_name", "orders_code_idx", "is_unique", false,
                                "position", 1, "index_expression", "code",
                                "predicate", null, "provider_generated", false),
                        row("index_oid", 33L, "table_oid", 10L,
                                "index_name", "orders_code_expr_idx", "is_unique", false,
                                "position", 1, "index_expression", "lower(code)",
                                "predicate", "code IS NOT NULL", "provider_generated", false));

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "source", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));

        String indexSql = jdbc.statement("indexes").sql;
        assertTrue(indexSql.contains("constraint_index.conindid = index_meta.indexrelid"));
        assertTrue(indexSql.contains("NOT EXISTS"));
        assertFalse(indexSql.contains("constraint_index.conname"));
        TableDefinition table = assertInstanceOf(TableDefinition.class,
                snapshot.objects().get(key(ObjectType.TABLE, "app", "orders")));
        assertEquals(2, table.constraints().size());
        assertEquals(List.of("\"app\".\"orders_code_expr_idx\"", "\"app\".\"orders_code_idx\""),
                table.indexes().stream().map(index -> index.key().name().original())
                .sorted().toList());

        SchemaChange create = new SchemaChange(
                "create:orders", ChangeKind.CREATE, table.key(), table, null, null,
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, false, Set.of(), "safe");
        List<RenderedStatement> rendered = new PgSchemaChangeRenderer().render(create,
                new RenderContext(DbType.POSTGRESQL,
                        PgSchemaIdentifierNormalizer.schema("app"),
                        PgSchemaIdentifierNormalizer.schema("deployed"), false));
        List<String> statements = rendered.stream().map(RenderedStatement::sql).toList();
        assertTrue(statements.getFirst().contains("CONSTRAINT \"orders_pkey\" PRIMARY KEY"));
        assertTrue(statements.getFirst().contains("CONSTRAINT \"orders_code_key\" UNIQUE"));
        assertEquals(2, statements.stream().filter(sql -> sql.startsWith("CREATE INDEX")).count());
        assertTrue(statements.stream().noneMatch(sql -> sql.contains("CREATE INDEX \"deployed\".\"orders_pkey\"")
                || sql.contains("CREATE INDEX \"deployed\".\"orders_code_key\"")));
    }

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
                        "select * from secret.app_trigger; credential=secret", "0A000"));
        SqlExecutionControl control = new SqlExecutionControl();

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection-id", PgSchemaIdentifierNormalizer.schema("app"),
                new SqlExecutionOptions(0, 5, control));

        assertFalse(snapshot.completeness().complete());
        assertEquals(SnapshotCompleteness.PERMISSION_DENIED,
                snapshot.completeness().unavailableScopes().get(ObjectType.INDEX));
        assertEquals(SnapshotCompleteness.NOT_SUPPORTED,
                snapshot.completeness().unavailableScopes().get(ObjectType.TRIGGER));
        assertEquals(SnapshotCompleteness.DEFINITION_UNAVAILABLE,
                snapshot.completeness().unavailableScopes().get(ObjectType.FUNCTION));
        assertEquals(Set.of(SnapshotCompleteness.PERMISSION_DENIED,
                        SnapshotCompleteness.NOT_SUPPORTED,
                        SnapshotCompleteness.DEFINITION_UNAVAILABLE),
                Set.copyOf(snapshot.completeness().unavailableScopes().values()));
        assertEquals(DefinitionConfidence.LOW,
                definition(snapshot, ObjectType.FUNCTION, "app", "missing_body", "integer").confidence());
        assertEquals(DefinitionConfidence.LOW,
                definition(snapshot, ObjectType.VIEW, "app", "missing_view", "").confidence());
        assertFalse(control.hasActiveStatement());
        assertEquals(12, jdbc.statements().size(), "partial categories must continue in auto-commit mode");
    }

    @Test
    void unknownRoutineLanguageFromRealFunctionDefShapeIsRetainedAsLowConfidence() throws Exception {
        String definition = "CREATE FUNCTION app.opaque() RETURNS integer "
                + "LANGUAGE python AS $body$ return 1 $body$";
        SnapshotJdbc jdbc = new SnapshotJdbc("app").rows("routines",
                row("object_oid", 77L, "object_name", "opaque", "routine_kind", "f",
                        "identity_arguments", "", "definition", definition));

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection-id", PgSchemaIdentifierNormalizer.schema("app"),
                new SqlExecutionOptions(0, 5, new SqlExecutionControl()));

        DefinitionObject opaque = definition(snapshot, ObjectType.FUNCTION, "app", "opaque", "");
        assertEquals(DefinitionConfidence.LOW, opaque.confidence());
        assertEquals(definition, opaque.originalDefinition());
        assertTrue(snapshot.objects().containsKey(opaque.key()));
    }

    @Test
    void declaredRecordSourceOnlyRoutineFlowsAsSafeSelectedMissingChange() throws Exception {
        String definition = "CREATE FUNCTION app.ambiguous() RETURNS integer LANGUAGE plpgsql "
                + "AS $body$ DECLARE app record; BEGIN RETURN app.value; END $body$";
        SnapshotJdbc jdbc = new SnapshotJdbc("app").rows("routines",
                row("object_oid", 78L, "object_name", "ambiguous", "routine_kind", "f",
                        "identity_arguments", "", "definition", definition));
        SchemaSnapshot source = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "source", PgSchemaIdentifierNormalizer.schema("app"),
                new SqlExecutionOptions(0, 5, new SqlExecutionControl()));
        SchemaSnapshot target = new SchemaSnapshot(DbType.POSTGRESQL, "target", source.schema(),
                Instant.EPOCH, new SnapshotCompleteness(true, new TreeMap<>()),
                new TreeMap<>(), "empty");

        DefinitionObject original = definition(source, ObjectType.FUNCTION, "app", "ambiguous", "");
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new PgSchemaDiffCapability().comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);

        assertEquals(DefinitionConfidence.HIGH, original.confidence());
        assertEquals(1, diff.differences().size());
        assertEquals(DifferenceKind.MISSING_IN_TARGET, diff.differences().getFirst().kind());
        assertEquals(AutomationLevel.SAFE_AUTOMATIC, diff.differences().getFirst().automation());
        assertEquals(1, plan.changes().size());
        assertEquals(ChangeKind.CREATE, plan.changes().getFirst().kind());
        assertEquals(AutomationLevel.SAFE_AUTOMATIC, plan.changes().getFirst().automation());
        assertEquals(Set.of(plan.changes().getFirst().id()), plan.selectedChangeIds());
        assertFalse(new PgSchemaChangeRenderer().render(plan.changes().getFirst(),
                new RenderContext(DbType.POSTGRESQL, source.schema(), source.schema(), false)).isEmpty());
        assertFalse((diff.toString() + plan + plan.digest()).contains("\0pg-"));
    }

    @Test
    void labelNamedLikeSchemaStillRetargetsFunctionAcrossReaderPlanRenderAndSecondDiff()
            throws Exception {
        String sourceDefinition = "CREATE FUNCTION \"Source\".labeled() RETURNS integer "
                + "LANGUAGE plpgsql AS $body$ <<\"Source\">> DECLARE rec record; BEGIN "
                + "PERFORM \"Source\".rec.value; "
                + "PERFORM id FROM \"Source\".orders AS \"Source\"; "
                + "PERFORM \"Source\".helper(); RETURN 1; "
                + "END \"Source\" $body$";
        String targetDefinition = sourceDefinition.replace("\"Source\".labeled",
                        "\"Target\".labeled")
                .replace("FROM \"Source\".orders", "FROM \"Target\".orders")
                .replace("PERFORM \"Source\".helper()", "PERFORM \"Target\".helper()");
        SchemaSnapshot source = new PgSchemaSnapshotReader(new SnapshotJdbc("Source")
                .rows("routines", row("object_oid", 80L, "object_name", "labeled",
                        "routine_kind", "f", "identity_arguments", "",
                        "definition", sourceDefinition)).connection()).read(
                "source", PgSchemaIdentifierNormalizer.schema("Source"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot emptyTarget = new SchemaSnapshot(DbType.POSTGRESQL, "empty",
                PgSchemaIdentifierNormalizer.schema("Target"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(), "empty");
        PgSchemaDiffCapability capability = new PgSchemaDiffCapability();
        SchemaChangePlan plan = new SchemaChangePlanner().plan(new SchemaDiffEngine().compare(
                source, emptyTarget, capability.comparisonProjector()));

        String sql = capability.changeRenderer().render(plan.changes().getFirst(),
                new RenderContext(DbType.POSTGRESQL, source.schema(),
                        emptyTarget.schema(), false)).getFirst().sql();
        assertTrue(sql.contains("PERFORM \"Source\".rec.value"), sql);
        assertTrue(sql.contains("FROM \"Target\".orders AS \"Source\""), sql);
        assertTrue(sql.contains("PERFORM \"Target\".helper()"), sql);
        assertFalse(sql.contains("PERFORM \"Source\".helper()"), sql);

        SchemaSnapshot reread = new PgSchemaSnapshotReader(new SnapshotJdbc("Target")
                .rows("routines", row("object_oid", 81L, "object_name", "labeled",
                        "routine_kind", "f", "identity_arguments", "",
                        "definition", targetDefinition)).connection()).read(
                "target", PgSchemaIdentifierNormalizer.schema("Target"),
                SqlExecutionOptions.defaults(0));
        assertTrue(new SchemaDiffEngine().compare(source, reread,
                        capability.comparisonProjector()).differences().stream()
                .allMatch(value -> value.kind() == DifferenceKind.EQUIVALENT));
    }

    @Test
    void outerLabelBindingAndMismatchedClosingLabelReadAsLowManualWithoutBlockingOthers()
            throws Exception {
        String outerBinding = "CREATE FUNCTION app.outer_binding() RETURNS integer LANGUAGE plpgsql "
                + "AS $body$ DECLARE outer_record record; BEGIN <<app>> DECLARE own_record record; "
                + "BEGIN RETURN app.outer_record.value; END app; END $body$";
        String badClosing = "CREATE FUNCTION app.bad_closing() RETURNS integer LANGUAGE plpgsql "
                + "AS $body$ <<mixed>> BEGIN RETURN 1; END other $body$";
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("routines",
                        row("object_oid", 82L, "object_name", "outer_binding",
                                "routine_kind", "f", "identity_arguments", "",
                                "definition", outerBinding),
                        row("object_oid", 83L, "object_name", "bad_closing",
                                "routine_kind", "f", "identity_arguments", "",
                                "definition", badClosing))
                .rows("sequences", row("sequence_name", "stable", "start_value", "1",
                        "object_oid", 84L, "object_name", "stable", "increment_by", "1",
                        "minimum_value", "1", "maximum_value", "99",
                        "cycle", false, "cache_size", 1));
        SchemaSnapshot source = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "source", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new SchemaSnapshot(DbType.POSTGRESQL, "target", source.schema(),
                Instant.EPOCH, new SnapshotCompleteness(true, new TreeMap<>()),
                new TreeMap<>(), "empty");

        assertEquals(2, source.objects().values().stream()
                .filter(DefinitionObject.class::isInstance)
                .map(DefinitionObject.class::cast)
                .filter(value -> value.confidence() == DefinitionConfidence.LOW).count());
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new PgSchemaDiffCapability().comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);
        assertTrue(diff.differences().stream()
                .filter(value -> value.object().type() == ObjectType.FUNCTION)
                .allMatch(value -> value.automation() == AutomationLevel.MANUAL_ONLY));
        assertTrue(plan.changes().stream()
                .filter(value -> value.object().type() == ObjectType.FUNCTION)
                .allMatch(value -> value.kind() == ChangeKind.MANUAL
                        && !plan.selectedChangeIds().contains(value.id())));
        assertTrue(diff.differences().stream()
                .anyMatch(value -> value.object().type() == ObjectType.SEQUENCE));
        assertFalse((diff + plan.toString() + plan.digest()).contains("pg-manual-definition"));
    }

    @Test
    void unboundThreePartPlpgsqlQualifierFlowsAsLowManualMissingWithoutMarkerLeakage() throws Exception {
        String definition = "CREATE FUNCTION app.three_part() RETURNS integer LANGUAGE plpgsql "
                + "AS $body$ BEGIN RETURN app.record_field.value; END $body$";
        SnapshotJdbc jdbc = new SnapshotJdbc("app").rows("routines",
                row("object_oid", 79L, "object_name", "three_part", "routine_kind", "f",
                        "identity_arguments", "", "definition", definition));
        SchemaSnapshot source = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "source", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new SchemaSnapshot(DbType.POSTGRESQL, "target", source.schema(),
                Instant.EPOCH, new SnapshotCompleteness(true, new TreeMap<>()),
                new TreeMap<>(), "empty");

        DefinitionObject routine = definition(source, ObjectType.FUNCTION, "app", "three_part", "");
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new PgSchemaDiffCapability().comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);

        assertEquals(DefinitionConfidence.LOW, routine.confidence());
        assertEquals(DifferenceKind.MISSING_IN_TARGET, diff.differences().getFirst().kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, diff.differences().getFirst().automation());
        assertEquals(ChangeKind.MANUAL, plan.changes().getFirst().kind());
        assertTrue(plan.selectedChangeIds().isEmpty());
        assertEquals(PgSchemaChangeRenderer.MANUAL_CHANGE,
                assertThrows(IllegalArgumentException.class,
                        () -> new PgSchemaChangeRenderer().render(plan.changes().getFirst(),
                                new RenderContext(DbType.POSTGRESQL,
                                        source.schema(), source.schema(), false))).getMessage());
        assertFalse((routine + diff.toString() + plan + plan.digest()).contains("pg-manual-definition"));
    }

    @Test
    void regclassDefaultsConvergeAcrossReaderCompareRenderAndSimulatedReread() throws Exception {
        String sourceDefault = "nextval('\"Source\".\"Seq\"\"Name\"'::pg_catalog.regclass)";
        String targetDefault = "nextval('\"Target\".\"Seq\"\"Name\"'::pg_catalog.regclass)";
        SchemaSnapshot source = new PgSchemaSnapshotReader(new SnapshotJdbc("Source")
                .rows("tables", row("object_oid", 1L, "object_name", "orders"))
                .rows("columns", column(1L, "id", 1, "int8", 0,
                        sourceDefault, "", "")).connection()).read(
                "source", PgSchemaIdentifierNormalizer.schema("Source"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot targetBefore = new PgSchemaSnapshotReader(new SnapshotJdbc("Target")
                .rows("tables", row("object_oid", 2L, "object_name", "orders"))
                .connection()).read("target-before", PgSchemaIdentifierNormalizer.schema("Target"),
                SqlExecutionOptions.defaults(0));
        PgSchemaDiffCapability capability = new PgSchemaDiffCapability();
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, targetBefore, capability.comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);

        assertEquals(1, plan.changes().size());
        String rendered = capability.changeRenderer().render(plan.changes().getFirst(),
                new RenderContext(DbType.POSTGRESQL, source.schema(), targetBefore.schema(), true))
                .getFirst().sql();
        assertTrue(rendered.contains(targetDefault), rendered);

        SchemaSnapshot targetAfter = new PgSchemaSnapshotReader(new SnapshotJdbc("Target")
                .rows("tables", row("object_oid", 2L, "object_name", "orders"))
                .rows("columns", column(2L, "id", 1, "int8", 0,
                        targetDefault, "", "")).connection()).read(
                "target-after", PgSchemaIdentifierNormalizer.schema("Target"),
                SqlExecutionOptions.defaults(0));
        assertTrue(new SchemaDiffEngine().compare(source, targetAfter,
                        capability.comparisonProjector()).differences().stream()
                .allMatch(difference -> difference.kind() == DifferenceKind.EQUIVALENT));
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

    @Test
    void catalogSqlUsesSafeAliasesPg11ArrayDetectionAndExactlyOneSchemaBinding() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app");

        new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));

        assertEquals(12, jdbc.statements().size());
        for (SnapshotJdbc.StatementTrace statement : jdbc.statements()) {
            String sql = statement.sql.toLowerCase(java.util.Locale.ROOT);
            assertEquals(Map.of(1, "app"), statement.bindings, statement.tag);
            assertFalse(sql.matches("(?s).*\\b(?:from|join)\\s+pg_catalog\\.pg_constraint\\s+constraint\\b.*"),
                    statement.tag);
        }
        String columns = jdbc.statement("columns").sql;
        assertTrue(columns.contains("t.typcategory = 'A'"));
        assertFalse(columns.contains("CASE WHEN t.typelem <> 0"));
        assertTrue(columns.contains("a.attidentity"));
        assertTrue(columns.contains("to_jsonb(a)->>'attgenerated'"));
        assertTrue(columns.contains("ad.oid::bigint AS attrdef_oid"));
    }

    @Test
    void columnSemanticsDistinguishScalarArrayDefaultIdentityAndGenerated() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("tables", row("object_oid", 1L, "object_name", "sample"))
                .rows("columns",
                        column(1L, "pg_name", 1, "name", 0, null, "", ""),
                        column(1L, "location", 2, "point", 0, null, "", ""),
                        column(1L, "numbers", 3, "int4", 1, null, "", ""),
                        column(1L, "matrix", 4, "int4", 3, null, "", ""),
                        column(1L, "with_default", 5, "int4", 0, " 42; ", "", ""),
                        column(1L, "identity_always", 6, "int8", 0, null, "a", ""),
                        column(1L, "identity_default", 7, "int8", 0, null, "d", ""),
                        column(1L, "generated_total", 8, "int8", 0, " qty * 2 ", "", "s"));

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));

        TableDefinition table = assertInstanceOf(TableDefinition.class,
                snapshot.objects().get(key(ObjectType.TABLE, "app", "sample")));
        Map<String, ColumnDefinition> columns = table.columns().stream().collect(
                java.util.stream.Collectors.toMap(column -> column.name().original(), column -> column));
        assertEquals(0, columns.get("\"pg_name\"").dataType().arrayDimensions());
        assertEquals(0, columns.get("\"location\"").dataType().arrayDimensions());
        assertEquals(1, columns.get("\"numbers\"").dataType().arrayDimensions());
        assertEquals(3, columns.get("\"matrix\"").dataType().arrayDimensions());
        assertNull(columns.get("\"pg_name\"").normalizedDefault());
        assertEquals("42", columns.get("\"with_default\"").normalizedDefault());
        assertEquals("ALWAYS", columns.get("\"identity_always\"").dataType()
                .providerExtensions().get("pg.identity"));
        assertEquals("GENERATED ALWAYS AS IDENTITY",
                columns.get("\"identity_always\"").normalizedDefault());
        assertEquals("BY DEFAULT", columns.get("\"identity_default\"").dataType()
                .providerExtensions().get("pg.identity"));
        assertEquals("GENERATED BY DEFAULT AS IDENTITY",
                columns.get("\"identity_default\"").normalizedDefault());
        assertEquals("STORED", columns.get("\"generated_total\"").dataType()
                .providerExtensions().get("pg.generated"));
        assertEquals("GENERATED ALWAYS AS (qty * 2) STORED",
                columns.get("\"generated_total\"").normalizedDefault());
    }

    @Test
    void emptyEnumAndCompositeRemainTypesAndChildCollationsAreStableDefinitionInput() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("enums", row("type_oid", 80L, "array_oid", 180L,
                        "type_name", "empty_enum", "sort_order", null, "enum_label", null))
                .rows("composites",
                        row("type_oid", 81L, "array_oid", 181L, "relation_oid", 281L,
                                "type_name", "empty_pair", "position", null,
                                "attribute_name", null, "attribute_type", null,
                                "attribute_collation_schema", null, "attribute_collation_name", null),
                        row("type_oid", 82L, "array_oid", 182L, "relation_oid", 282L,
                                "type_name", "named_pair", "position", 1,
                                "attribute_name", "label", "attribute_type", "\"pg_catalog\".\"text\"",
                                "attribute_collation_schema", "app", "attribute_collation_name", "natural"));

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));

        assertEquals("CREATE TYPE \"app\".\"empty_enum\" AS ENUM ()",
                definition(snapshot, ObjectType.TYPE, "app", "empty_enum", "enum")
                        .normalizedDefinition());
        assertEquals("CREATE TYPE \"app\".\"empty_pair\" AS ()",
                definition(snapshot, ObjectType.TYPE, "app", "empty_pair", "composite")
                        .normalizedDefinition());
        assertTrue(definition(snapshot, ObjectType.TYPE, "app", "named_pair", "composite")
                .normalizedDefinition().contains(
                        "\"label\" \"pg_catalog\".\"text\" COLLATE \"app\".\"natural\""));

        String enumsSql = jdbc.statement("enums").sql;
        assertTrue(enumsSql.contains("LEFT JOIN pg_catalog.pg_enum"));
        String compositesSql = jdbc.statement("composites").sql;
        assertTrue(compositesSql.contains("LEFT JOIN pg_catalog.pg_attribute"));
        assertTrue(compositesSql.contains("attribute.attnum > 0")
                && compositesSql.indexOf("attribute.attnum > 0")
                < compositesSql.indexOf("WHERE n.nspname"));
        assertTrue(compositesSql.contains("attribute.atttypmod"));
        assertTrue(jdbc.statement("domains").sql.contains("type.typtypmod"));
    }

    @Test
    void compositeAndDomainCollationAloneChangesFingerprint() throws Exception {
        SchemaSnapshot first = collationSnapshot("natural", "domain_natural");
        SchemaSnapshot second = collationSnapshot("reverse", "domain_reverse");

        org.junit.jupiter.api.Assertions.assertNotEquals(first.fingerprint(), second.fingerprint());
        assertTrue(definition(first, ObjectType.TYPE, "app", "pair", "composite")
                .normalizedDefinition().contains("COLLATE \"app\".\"natural\""));
        assertTrue(definition(first, ObjectType.TYPE, "app", "label", "domain")
                .normalizedDefinition().contains("COLLATE \"app\".\"domain_natural\""));
    }

    @Test
    void dependencyAliasesRouteDefaultsRowTypesAndNestedObjectsToTopLevelTables() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("tables",
                        row("object_oid", 10L, "object_name", "orders",
                                "row_type_oid", 110L, "array_type_oid", 111L),
                        row("object_oid", 11L, "object_name", "archive",
                                "row_type_oid", 112L, "array_type_oid", 113L))
                .rows("columns", column(10L, "id", 1, "int8", 0,
                        "nextval('app.orders_id_seq'::regclass)", "", ""))
                .rows("constraints", row("constraint_oid", 130L, "table_oid", 10L,
                        "constraint_name", "orders_check", "constraint_type", "c", "position", null,
                        "column_name", null, "referenced_table_oid", null,
                        "referenced_column_name", null, "check_expression", "id > 0",
                        "update_action", null, "delete_action", null, "provider_generated", false))
                .rows("indexes", row("index_oid", 140L, "table_oid", 10L,
                        "index_name", "orders_expr_idx", "is_unique", false, "position", 1,
                        "index_expression", "app.normalize_id(id)", "predicate", null,
                        "provider_generated", false))
                .rows("sequences", row("object_oid", 150L, "object_name", "orders_id_seq",
                        "start_value", "1", "increment_by", "1", "minimum_value", "1",
                        "maximum_value", "999", "cycle", false, "cache_size", 1))
                .rows("routines", row("object_oid", 160L, "object_name", "normalize_id",
                        "routine_kind", "f", "identity_arguments", "\"app\".\"orders\"",
                        "definition", "CREATE FUNCTION app.normalize_id(app.orders) RETURNS bigint "
                                + "LANGUAGE sql AS $$ SELECT $1.id $$;"))
                .rows("triggers",
                        row("object_oid", 170L, "object_name", "audit", "table_oid", 10L,
                                "relation_name", "orders", "definition", "CREATE TRIGGER audit ..."),
                        row("object_oid", 171L, "object_name", "audit", "table_oid", 11L,
                                "relation_name", "archive", "definition", "CREATE TRIGGER audit ..."))
                .rows("dependencies",
                        row("source_catalog", "pg_attrdef", "source_oid", 901L,
                                "source_schema", "app", "target_catalog", "pg_class",
                                "target_oid", 150L, "target_schema", "app"),
                        row("source_catalog", "pg_proc", "source_oid", 160L,
                                "source_schema", "app", "target_catalog", "pg_type",
                                "target_oid", 111L, "target_schema", "app"),
                        row("source_catalog", "pg_class", "source_oid", 140L,
                                "source_schema", "app", "target_catalog", "pg_proc",
                                "target_oid", 160L, "target_schema", "app"),
                        row("source_catalog", "pg_constraint", "source_oid", 130L,
                                "source_schema", "app", "target_catalog", "pg_proc",
                                "target_oid", 160L, "target_schema", "app"),
                        row("source_catalog", "pg_proc", "source_oid", 160L,
                                "source_schema", "app", "target_catalog", "pg_type",
                                "target_oid", 9999L, "target_schema", "other"),
                        row("source_catalog", "pg_proc", "source_oid", 160L,
                                "source_schema", "app", "target_catalog", "pg_type",
                                "target_oid", 9998L, "target_schema", "app"));

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));

        ObjectKey tableKey = key(ObjectType.TABLE, "app", "orders");
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "app", "orders_id_seq");
        ObjectKey routineKey = new ObjectKey(ObjectType.FUNCTION,
                PgSchemaIdentifierNormalizer.object("app", "normalize_id"), "\"app\".\"orders\"");
        TableDefinition table = assertInstanceOf(TableDefinition.class, snapshot.objects().get(tableKey));
        assertEquals(Set.of(sequenceKey, routineKey), table.dependencies());
        assertEquals(Set.of(routineKey), table.indexes().getFirst().dependencies());
        assertEquals(Set.of(routineKey), table.constraints().getFirst().dependencies());
        assertEquals(Set.of(tableKey), ((DefinitionObject) snapshot.objects().get(routineKey)).dependencies());
        assertEquals(2, snapshot.objects().keySet().stream()
                .filter(key -> key.type() == ObjectType.TRIGGER && key.name().original().endsWith(".\"audit\""))
                .count());
        assertEquals(SnapshotCompleteness.DEPENDENCY_UNRESOLVED,
                snapshot.completeness().unavailableScopes().get(ObjectType.FUNCTION));
        assertTrue(jdbc.statement("tables").sql.contains("c.reltype"));
        assertTrue(jdbc.statement("views").sql.contains("c.reltype"));
        assertTrue(jdbc.statement("dependencies").sql.contains("pg_catalog.pg_attrdef"));
    }

    @Test
    void partialCategoriesCommitNoMidstreamRowsAndMarkTopLevelTable() throws Exception {
        SnapshotJdbc tables = new SnapshotJdbc("app")
                .rows("tables",
                        row("object_oid", 1L, "object_name", "first"),
                        row("object_oid", 2L, "object_name", "second"))
                .failAfter("tables", 1, new SQLException("secret schema app", "42501"));
        SchemaSnapshot noHalfTables = new PgSchemaSnapshotReader(tables.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
        assertTrue(noHalfTables.objects().keySet().stream().noneMatch(key -> key.type() == ObjectType.TABLE));
        assertEquals(SnapshotCompleteness.PERMISSION_DENIED,
                noHalfTables.completeness().unavailableScopes().get(ObjectType.TABLE));

        SnapshotJdbc children = new SnapshotJdbc("app")
                .rows("tables", row("object_oid", 1L, "object_name", "orders"))
                .rows("constraints",
                        row("constraint_oid", 20L, "table_oid", 1L, "constraint_name", "first",
                                "constraint_type", "c", "position", null, "column_name", null,
                                "referenced_table_oid", null, "referenced_column_name", null,
                                "check_expression", "id > 0", "update_action", null,
                                "delete_action", null, "provider_generated", false),
                        row("constraint_oid", 21L, "table_oid", 1L, "constraint_name", "second",
                                "constraint_type", "c", "position", null, "column_name", null,
                                "referenced_table_oid", null, "referenced_column_name", null,
                                "check_expression", "id < 10", "update_action", null,
                                "delete_action", null, "provider_generated", false))
                .failAfter("constraints", 1, new SQLException("unsupported secret", "0A000"))
                .rows("indexes",
                        row("index_oid", 30L, "table_oid", 1L, "index_name", "first_idx",
                                "is_unique", false, "position", 1, "index_expression", "id",
                                "predicate", null, "provider_generated", false),
                        row("index_oid", 31L, "table_oid", 1L, "index_name", "second_idx",
                                "is_unique", false, "position", 1, "index_expression", "id + 1",
                                "predicate", null, "provider_generated", false))
                .failAfter("indexes", 1, new SQLException("permission secret", "42501"));
        SchemaSnapshot noHalfChildren = new PgSchemaSnapshotReader(children.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
        TableDefinition table = assertInstanceOf(TableDefinition.class,
                noHalfChildren.objects().get(key(ObjectType.TABLE, "app", "orders")));
        assertTrue(table.constraints().isEmpty());
        assertTrue(table.indexes().isEmpty());
        assertEquals(SnapshotCompleteness.PERMISSION_DENIED,
                noHalfChildren.completeness().unavailableScopes().get(ObjectType.TABLE));
        assertEquals(SnapshotCompleteness.NOT_SUPPORTED,
                noHalfChildren.completeness().unavailableScopes().get(ObjectType.CHECK_CONSTRAINT));
        assertEquals(SnapshotCompleteness.PERMISSION_DENIED,
                noHalfChildren.completeness().unavailableScopes().get(ObjectType.INDEX));
    }

    @Test
    void syntaxConnectionServerAndUnknownSqlErrorsAreSanitizedTerminalFailures() {
        for (String sqlState : java.util.Arrays.asList("42601", "08006", "57P01", "57P02",
                "57P03", "XX000", null)) {
            SqlExecutionControl control = new SqlExecutionControl();
            SnapshotJdbc jdbc = new SnapshotJdbc("app").fail("views",
                    new SQLException("SELECT secret FROM app.t jdbc:postgresql://host/db password=x",
                            sqlState, 77));

            SQLException failure = assertThrows(SQLException.class,
                    () -> new PgSchemaSnapshotReader(jdbc.connection()).read(
                            "connection", PgSchemaIdentifierNormalizer.schema("app"),
                            new SqlExecutionOptions(0, 3, control)), String.valueOf(sqlState));

            assertEquals(sqlState, failure.getSQLState());
            assertEquals(77, failure.getErrorCode());
            assertNull(failure.getCause());
            assertEquals("Snapshot metadata failed", failure.getMessage());
            assertFalse(control.hasActiveStatement());
        }
    }

    @Test
    void deparsersUseCatalogSearchPathNonPrettyOutputAndStableRoutineTypeIdentity() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app");
        new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));

        for (SnapshotJdbc.StatementTrace statement : jdbc.statements()) {
            if (statement.sql.contains("pg_get_") || statement.sql.contains("format_type")) {
                assertTrue(statement.sql.contains(
                        "pg_catalog.set_config('search_path', 'pg_catalog', true)"), statement.tag);
            }
            assertFalse(statement.sql.matches("(?s).*pg_get_(?:viewdef|indexdef|constraintdef|"
                    + "triggerdef|expr)\\([^)]*,\\s*true\\).*"), statement.tag);
        }
        String routines = jdbc.statement("routines").sql;
        assertFalse(routines.contains("pg_get_function_identity_arguments"));
        assertTrue(routines.contains("p.proargtypes"));
        assertTrue(routines.contains("WITH ORDINALITY"));
        assertTrue(routines.contains("argument_type.typnamespace"));
        assertTrue(routines.contains("argument_type.typcategory = 'A'"));
    }

    @Test
    void simulatedSessionSearchPathsProduceIdenticalDefinitionsSignaturesAndFingerprint() throws Exception {
        Map<String, Object> canonicalView = row("object_oid", 60L, "object_name", "orders_view",
                "relation_kind", "v", "definition", "SELECT id FROM app.orders;",
                "row_type_oid", 160L, "array_type_oid", 161L);
        SnapshotJdbc publicFirst = new SnapshotJdbc("app")
                .sessionRows("views", canonicalView,
                        row("object_oid", 60L, "object_name", "orders_view", "relation_kind", "v",
                                "definition", "SELECT id FROM orders;",
                                "row_type_oid", 160L, "array_type_oid", 161L))
                .rows("routines", row("object_oid", 70L, "object_name", "accept_orders",
                        "routine_kind", "f", "identity_arguments", "\"app\".\"orders\"[]",
                        "definition", "CREATE FUNCTION app.accept_orders(app.orders[]) RETURNS void "
                                + "LANGUAGE sql AS $$ SELECT $$;"));
        SnapshotJdbc privateFirst = new SnapshotJdbc("app")
                .sessionRows("views", canonicalView,
                        row("object_oid", 60L, "object_name", "orders_view", "relation_kind", "v",
                                "definition", "SELECT id FROM app.orders;",
                                "row_type_oid", 160L, "array_type_oid", 161L))
                .rows("routines", row("object_oid", 70L, "object_name", "accept_orders",
                        "routine_kind", "f", "identity_arguments", "\"app\".\"orders\"[]",
                        "definition", "CREATE FUNCTION app.accept_orders(app.orders[]) RETURNS void "
                                + "LANGUAGE sql AS $$ SELECT $$;"));

        SchemaSnapshot first = new PgSchemaSnapshotReader(publicFirst.connection()).read(
                "first", PgSchemaIdentifierNormalizer.schema("app"), SqlExecutionOptions.defaults(0));
        SchemaSnapshot second = new PgSchemaSnapshotReader(privateFirst.connection()).read(
                "second", PgSchemaIdentifierNormalizer.schema("app"), SqlExecutionOptions.defaults(0));

        assertEquals(first.objects(), second.objects());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertTrue(first.objects().containsKey(new ObjectKey(ObjectType.FUNCTION,
                PgSchemaIdentifierNormalizer.object("app", "accept_orders"),
                "\"app\".\"orders\"[]")));
    }

    @Test
    void catalogFormatTypePreservesPayloadBitsIntervalAndDomainArrayDimensions() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("composites",
                        compositeAttribute(81L, "payload", 1, "bits", "bit(13)"),
                        compositeAttribute(81L, "payload", 2, "mask", "bit varying(21)"),
                        compositeAttribute(81L, "payload", 3, "window",
                                "interval day to second(4)"),
                        compositeAttribute(81L, "payload", 4, "amount", "numeric(12,3)"),
                        compositeAttribute(81L, "payload", 5, "label", "character varying(40)"),
                        compositeAttribute(81L, "payload", 6, "at_time", "time(2) without time zone"))
                .rows("domains",
                        domain(82L, "matrix", "integer[]", true, 2),
                        domain(83L, "bits", "bit(7)", false, 0),
                        domain(84L, "bit_matrix", "bit(7)", false, 2));

        SchemaSnapshot snapshot = new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));

        DefinitionObject payload = definition(snapshot, ObjectType.TYPE, "app", "payload", "composite");
        for (String type : List.of("bit(13)", "bit varying(21)",
                "interval day to second(4)", "numeric(12,3)",
                "character varying(40)", "time(2) without time zone")) {
            assertTrue(payload.originalDefinition().contains(type), type);
            assertTrue(payload.normalizedDefinition().contains(type), type);
        }
        DefinitionObject matrix = definition(snapshot, ObjectType.TYPE, "app", "matrix", "domain");
        assertTrue(matrix.originalDefinition().contains(" AS integer[][]"));
        assertTrue(matrix.normalizedDefinition().contains(" AS integer[][]"));
        DefinitionObject bits = definition(snapshot, ObjectType.TYPE, "app", "bits", "domain");
        assertTrue(bits.originalDefinition().contains(" AS bit(7)"));
        assertTrue(bits.normalizedDefinition().contains(" AS bit(7)"));
        assertTrue(definition(snapshot, ObjectType.TYPE, "app", "bit_matrix", "domain")
                .normalizedDefinition().contains(" AS bit(7)[][]"),
                "non-array format_type output must receive every catalog dimension");

        String compositesSql = jdbc.statement("composites").sql;
        assertTrue(compositesSql.contains(
                "pg_catalog.format_type(attribute.atttypid, attribute.atttypmod) AS attribute_type"));
        assertTrue(compositesSql.contains(
                "pg_catalog.set_config('search_path', 'pg_catalog', true)"));
        String domainsSql = jdbc.statement("domains").sql;
        assertTrue(domainsSql.contains(
                "pg_catalog.format_type(type.typbasetype, type.typtypmod) AS base_type"));
        assertTrue(domainsSql.contains("type.typndims AS domain_dimensions"));

        SchemaSnapshot oneDimension = domainDimensionSnapshot(1);
        SchemaSnapshot twoDimensions = domainDimensionSnapshot(2);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                definition(oneDimension, ObjectType.TYPE, "app", "matrix", "domain")
                        .normalizedDefinition(),
                definition(twoDimensions, ObjectType.TYPE, "app", "matrix", "domain")
                        .normalizedDefinition());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                oneDimension.fingerprint(), twoDimensions.fingerprint());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                payloadTypeSnapshot("bit(13)").fingerprint(),
                payloadTypeSnapshot("bit(14)").fingerprint());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                payloadTypeSnapshot("interval day to second(3)").fingerprint(),
                payloadTypeSnapshot("interval year to month").fingerprint());
    }

    @Test
    void rejectsNonAutoCommitAndSanitizesAutoCommitInspectionFailureBeforeAnySql() {
        SqlExecutionControl manualControl = new SqlExecutionControl();
        SnapshotJdbc manual = new SnapshotJdbc("secret_schema").autoCommit(false);

        SQLException rejected = assertThrows(SQLException.class,
                () -> new PgSchemaSnapshotReader(manual.connection()).read(
                        "secret-connection", PgSchemaIdentifierNormalizer.schema("secret_schema"),
                        new SqlExecutionOptions(0, 3, manualControl)));

        assertEquals("25001", rejected.getSQLState());
        assertEquals("Snapshot requires an auto-commit connection", rejected.getMessage());
        assertNull(rejected.getCause());
        assertTrue(manual.statements().isEmpty());
        assertFalse(manualControl.hasActiveStatement());

        SqlExecutionControl inspectionControl = new SqlExecutionControl();
        SnapshotJdbc inspection = new SnapshotJdbc("secret_schema").autoCommitFailure(
                new SQLException("jdbc:postgresql://host/db password=secret schema=secret_schema",
                        "08006", 91));

        SQLException unavailable = assertThrows(SQLException.class,
                () -> new PgSchemaSnapshotReader(inspection.connection()).read(
                        "secret-connection", PgSchemaIdentifierNormalizer.schema("secret_schema"),
                        new SqlExecutionOptions(0, 3, inspectionControl)));

        assertEquals("08006", unavailable.getSQLState());
        assertEquals(91, unavailable.getErrorCode());
        assertEquals("Snapshot connection state unavailable", unavailable.getMessage());
        assertNull(unavailable.getCause());
        assertTrue(inspection.statements().isEmpty());
        assertFalse(inspectionControl.hasActiveStatement());
    }

    private static SchemaSnapshot domainDimensionSnapshot(int dimensions) throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app").rows("domains",
                domain(82L, "matrix", "integer[]", true, dimensions));
        return new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
    }

    private static SchemaSnapshot payloadTypeSnapshot(String formattedType) throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app").rows("composites",
                compositeAttribute(81L, "payload", 1, "value", formattedType));
        return new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
    }

    private static Map<String, Object> compositeAttribute(long oid, String typeName, int position,
                                                           String name, String formattedType) {
        return row("type_oid", oid, "array_oid", oid + 100L, "relation_oid", oid + 200L,
                "type_name", typeName, "position", position, "attribute_name", name,
                "attribute_type", formattedType, "attribute_collation_schema", null,
                "attribute_collation_name", null);
    }

    private static Map<String, Object> domain(long oid, String typeName, String formattedType,
                                               boolean baseArray, int dimensions) {
        return row("type_oid", oid, "array_oid", oid + 100L, "type_name", typeName,
                "base_type", formattedType, "base_is_array", baseArray,
                "domain_dimensions", dimensions, "not_null", false,
                "default_expression", null, "type_collation_schema", null,
                "type_collation_name", null, "constraint_oid", null,
                "constraint_name", null, "constraint_definition", null);
    }

    private static SchemaSnapshot collationSnapshot(String compositeCollation,
                                                     String domainCollation) throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("app")
                .rows("composites", row("type_oid", 81L, "array_oid", 181L,
                        "relation_oid", 281L, "type_name", "pair", "position", 1,
                        "attribute_name", "label", "attribute_type", "\"pg_catalog\".\"text\"",
                        "attribute_collation_schema", "app",
                        "attribute_collation_name", compositeCollation))
                .rows("domains", row("type_oid", 82L, "array_oid", 182L,
                        "type_name", "label", "base_type", "\"pg_catalog\".\"text\"",
                        "not_null", false, "default_expression", null,
                        "type_collation_schema", "app", "type_collation_name", domainCollation,
                        "constraint_oid", null, "constraint_name", null,
                        "constraint_definition", null));
        return new PgSchemaSnapshotReader(jdbc.connection()).read(
                "connection", PgSchemaIdentifierNormalizer.schema("app"),
                SqlExecutionOptions.defaults(0));
    }

    private static Map<String, Object> column(long tableOid, String name, int ordinal,
                                               String baseType, int dimensions, String expression,
                                               String identity, String generated) {
        return row("table_oid", tableOid, "column_name", name, "ordinal_position", ordinal,
                "base_type", baseType, "character_length", null, "numeric_precision", null,
                "numeric_scale", null, "with_time_zone", false, "array_dimensions", dimensions,
                "formatted_type", baseType + "[]".repeat(dimensions), "type_schema", "pg_catalog",
                "nullable", true, "default_expression", expression, "comment", null,
                "attrdef_oid", expression == null ? null : 900L + ordinal,
                "identity_kind", identity, "generated_kind", generated);
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
        private final Map<String, MidstreamFailure> midstreamFailures = new LinkedHashMap<>();
        private final Map<String, SessionRows> sessionRows = new LinkedHashMap<>();
        private final List<StatementTrace> statements = new ArrayList<>();
        private boolean autoCommit = true;
        private SQLException autoCommitFailure;

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

        SnapshotJdbc failAfter(String tag, int successfulRows, SQLException failure) {
            midstreamFailures.put(tag, new MidstreamFailure(successfulRows, failure));
            return this;
        }

        SnapshotJdbc sessionRows(String tag, Map<String, Object> canonical,
                                 Map<String, Object> sessionDependent) {
            sessionRows.put(tag, new SessionRows(List.of(canonical), List.of(sessionDependent)));
            return this;
        }

        SnapshotJdbc autoCommit(boolean enabled) {
            autoCommit = enabled;
            return this;
        }

        SnapshotJdbc autoCommitFailure(SQLException failure) {
            autoCommitFailure = failure;
            return this;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> preparedStatement((String) args[0]);
                        case "getAutoCommit" -> {
                            if (autoCommitFailure != null) throw autoCommitFailure;
                            yield autoCommit;
                        }
                        case "isClosed" -> false;
                        case "toString" -> "snapshot-jdbc-proxy";
                        default -> defaultValue(method.getReturnType());
                    });
        }

        List<StatementTrace> statements() {
            return List.copyOf(statements);
        }

        StatementTrace statement(String tag) {
            return statements.stream().filter(statement -> statement.tag.equals(tag))
                    .findFirst().orElseThrow();
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
                            SessionRows variants = sessionRows.get(trace.tag);
                            List<Map<String, Object>> queryRows = variants == null
                                    ? rows.getOrDefault(trace.tag, List.of())
                                    : (sql.contains("pg_catalog.set_config('search_path', 'pg_catalog', true)")
                                    ? variants.canonical() : variants.sessionDependent());
                            yield resultSet(queryRows,
                                    midstreamFailures.get(trace.tag));
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

        private ResultSet resultSet(List<Map<String, Object>> queryRows,
                                    MidstreamFailure midstreamFailure) {
            int[] cursor = {-1};
            AtomicBoolean wasNull = new AtomicBoolean();
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            if (midstreamFailure != null
                                    && cursor[0] + 1 >= midstreamFailure.successfulRows()) {
                                throw midstreamFailure.failure();
                            }
                            yield ++cursor[0] < queryRows.size();
                        }
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

        private record MidstreamFailure(int successfulRows, SQLException failure) {
        }

        private record SessionRows(List<Map<String, Object>> canonical,
                                   List<Map<String, Object>> sessionDependent) {
        }
    }
}
