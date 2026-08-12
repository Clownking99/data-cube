package com.datacube.provider.oracle;

import com.datacube.schemadiff.DifferenceKind;
import com.datacube.schemadiff.SchemaDiffEngine;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.TableDefinition;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.RenderContext;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaSnapshotReaderTest {
    @Test
    void javaAndCCallSpecsAreLowManualMissingWithoutBlockingOtherObjectsOrLeakingMarker() throws Exception {
        String definition = "CREATE FUNCTION \"Sales\".\"JAVA_FN\" RETURN NUMBER "
                + "AS LANGUAGE JAVA NAME 'example.Owner.call() return int';";
        String cDefinition = "CREATE PROCEDURE \"Sales\".\"C_PROC\" AS LANGUAGE C "
                + "LIBRARY \"Sales\".\"NATIVE_LIB\" NAME \"native_call\";";
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("definitions", definitionRow("JAVA_FN", "FUNCTION", 701, 1, null),
                        definitionRow("C_PROC", "PROCEDURE", 702, 1, null))
                .ddl("FUNCTION", "JAVA_FN", definition)
                .ddl("PROCEDURE", "C_PROC", cDefinition)
                .rows("sequences", row("sequence_name", "STABLE", "min_value", "1",
                        "max_value", "99", "increment_by", "1", "cycle_flag", "N",
                        "cache_size", 20, "order_flag", "N"));
        SchemaSnapshot source = new OracleSchemaSnapshotReader(jdbc.connection()).read(
                "source", OracleSchemaIdentifierNormalizer.schema("Sales"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new SchemaSnapshot(DbType.ORACLE, "target", source.schema(),
                Instant.EPOCH, new SnapshotCompleteness(true, new TreeMap<>()),
                new TreeMap<>(), "empty");

        DefinitionObject routine = definition(source, ObjectType.FUNCTION,
                "Sales", "JAVA_FN", "oracle-routine-signature-v1\0");
        DefinitionObject cRoutine = definition(source, ObjectType.PROCEDURE,
                "Sales", "C_PROC", "oracle-routine-signature-v1\0");
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new OracleSchemaDiffCapability().comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);
        var routineDifference = diff.differences().stream()
                .filter(value -> value.object().type() == ObjectType.FUNCTION)
                .findFirst().orElseThrow();
        var routineChange = plan.changes().stream()
                .filter(value -> value.object().type() == ObjectType.FUNCTION)
                .findFirst().orElseThrow();

        assertEquals(DefinitionConfidence.LOW, routine.confidence());
        assertEquals(DefinitionConfidence.LOW, cRoutine.confidence());
        assertTrue(source.completeness().unavailableScopes().isEmpty());
        assertEquals(DifferenceKind.MISSING_IN_TARGET, routineDifference.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, routineDifference.automation());
        assertEquals(ChangeKind.MANUAL, routineChange.kind());
        assertFalse(plan.selectedChangeIds().contains(routineChange.id()));
        assertEquals(OracleSchemaChangeRenderer.MANUAL_CHANGE,
                assertThrows(IllegalArgumentException.class,
                        () -> new OracleSchemaChangeRenderer().render(routineChange,
                                new RenderContext(DbType.ORACLE, source.schema(),
                                        source.schema(), false))).getMessage());
        assertTrue(diff.differences().stream().anyMatch(value ->
                value.object().type() == ObjectType.SEQUENCE));
        assertEquals(2, diff.differences().stream().filter(value ->
                value.object().type() == ObjectType.FUNCTION
                        || value.object().type() == ObjectType.PROCEDURE).count());
        assertTrue(diff.differences().stream().filter(value ->
                value.object().type() == ObjectType.FUNCTION
                        || value.object().type() == ObjectType.PROCEDURE)
                .allMatch(value -> value.automation() == AutomationLevel.MANUAL_ONLY));
        assertFalse((routine + cRoutine.toString() + diff.toString() + plan + plan.digest())
                .contains("oracle-manual-definition"));
    }

    @Test
    void unprovablePackageTypeBodyAndTriggerReadAsLocalManualPartialResults() throws Exception {
        String packageBody = "CREATE PACKAGE BODY \"Sales\".\"API\" AS FUNCTION broken "
                + "RETURN NUMBER IS BEGIN RETURN 1; END wrong; END API; /";
        String typeBody = "CREATE TYPE BODY \"Sales\".\"OBJ_T\" AS MEMBER FUNCTION broken "
                + "RETURN NUMBER IS BEGIN RETURN 1; END wrong; END; /";
        String trigger = "CREATE TRIGGER \"Sales\".\"ORDERS_TRG\" BEFORE INSERT ON "
                + "\"Sales\".\"ORDERS\" BEGIN <<dangling>> NULL; END; /";
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "ORDERS"))
                .rows("definitions", definitionRow("API", "PACKAGE", 801, 0, null),
                        definitionRow("API", "PACKAGE BODY", 802, 0, null),
                        definitionRow("OBJ_T", "TYPE", 803, 0, null),
                        definitionRow("OBJ_T", "TYPE BODY", 804, 0, null),
                        definitionRow("ORDERS_TRG", "TRIGGER", 805, 0, "ORDERS"))
                .ddl("PACKAGE_SPEC", "API", "CREATE PACKAGE \"Sales\".\"API\" AS END API; /")
                .ddl("PACKAGE_BODY", "API", packageBody)
                .ddl("TYPE_SPEC", "OBJ_T", "CREATE TYPE \"Sales\".\"OBJ_T\" AS OBJECT (id NUMBER);")
                .ddl("TYPE_BODY", "OBJ_T", typeBody)
                .ddl("TRIGGER", "ORDERS_TRG", trigger)
                .rows("sequences", row("sequence_name", "STABLE", "min_value", "1",
                        "max_value", "99", "increment_by", "1", "cycle_flag", "N",
                        "cache_size", 20, "order_flag", "N"));
        SchemaSnapshot source = new OracleSchemaSnapshotReader(jdbc.connection()).read(
                "source", OracleSchemaIdentifierNormalizer.schema("Sales"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new SchemaSnapshot(DbType.ORACLE, "target", source.schema(),
                Instant.EPOCH, new SnapshotCompleteness(true, new TreeMap<>()),
                new TreeMap<>(), "empty");

        for (DefinitionObject definition : source.objects().values().stream()
                .filter(DefinitionObject.class::isInstance).map(DefinitionObject.class::cast)
                .filter(value -> value.key().type() == ObjectType.PACKAGE_BODY
                        || value.key().type() == ObjectType.TRIGGER
                        || value.key().type() == ObjectType.TYPE
                        && value.key().signature().equals("BODY")).toList()) {
            assertEquals(DefinitionConfidence.LOW, definition.confidence(),
                    definition.key().toString());
        }
        assertTrue(source.completeness().unavailableScopes().isEmpty());
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new OracleSchemaDiffCapability().comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);
        assertEquals(3, diff.differences().stream().filter(value ->
                value.object().type() == ObjectType.PACKAGE_BODY
                        || value.object().type() == ObjectType.TRIGGER
                        || value.object().type() == ObjectType.TYPE
                        && value.object().signature().equals("BODY")).count());
        assertTrue(diff.differences().stream().filter(value ->
                value.object().type() == ObjectType.PACKAGE_BODY
                        || value.object().type() == ObjectType.TRIGGER
                        || value.object().type() == ObjectType.TYPE
                        && value.object().signature().equals("BODY"))
                .allMatch(value -> value.automation() == AutomationLevel.MANUAL_ONLY));
        assertTrue(plan.changes().stream().filter(value ->
                value.object().type() == ObjectType.PACKAGE_BODY
                        || value.object().type() == ObjectType.TRIGGER
                        || value.object().type() == ObjectType.TYPE
                        && value.object().signature().equals("BODY"))
                .allMatch(value -> value.kind() == ChangeKind.MANUAL
                        && !plan.selectedChangeIds().contains(value.id())));
        assertFalse((diff + plan.toString() + plan.digest()).contains("oracle-manual-definition"));
    }

    @Test
    void undeclaredLabelChainReadsAsLowManualWhileDeclaredChainAndPackageCallStayAutomatic()
            throws Exception {
        String unsafe = "CREATE FUNCTION \"Sales\".\"UNSAFE_LABEL\" RETURN NUMBER AS BEGIN "
                + "<<\"Sales\">> DECLARE rec \"Sales\".\"OBJ_T\"; BEGIN "
                + "\"Sales\".missing.value := 1; END; RETURN 1; END; /";
        String safe = "CREATE FUNCTION \"Sales\".\"SAFE_LABEL\" RETURN NUMBER AS BEGIN "
                + "<<\"Sales\">> DECLARE rec \"Sales\".\"OBJ_T\"; BEGIN "
                + "\"Sales\".rec.value := 1; \"Sales\".\"PKG\".\"RUN\"(); END; "
                + "\"Sales\".\"PKG\".\"RUN\"(); RETURN 1; END; /";
        String badClosing = "CREATE FUNCTION \"Sales\".\"BAD_CLOSING\" RETURN NUMBER AS BEGIN "
                + "<<mixed>> BEGIN NULL; END other; RETURN 1; END; /";
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("definitions",
                        definitionRow("UNSAFE_LABEL", "FUNCTION", 901, 1, null),
                        definitionRow("SAFE_LABEL", "FUNCTION", 902, 1, null),
                        definitionRow("BAD_CLOSING", "FUNCTION", 903, 1, null))
                .ddl("FUNCTION", "UNSAFE_LABEL", unsafe)
                .ddl("FUNCTION", "SAFE_LABEL", safe)
                .ddl("FUNCTION", "BAD_CLOSING", badClosing);
        SchemaSnapshot source = new OracleSchemaSnapshotReader(jdbc.connection()).read(
                "source", OracleSchemaIdentifierNormalizer.schema("Sales"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new SchemaSnapshot(DbType.ORACLE, "target", source.schema(),
                Instant.EPOCH, new SnapshotCompleteness(true, new TreeMap<>()),
                new TreeMap<>(), "empty");

        DefinitionObject unsafeDefinition = definition(source, ObjectType.FUNCTION,
                "Sales", "UNSAFE_LABEL", "oracle-routine-signature-v1\0");
        DefinitionObject safeDefinition = definition(source, ObjectType.FUNCTION,
                "Sales", "SAFE_LABEL", "oracle-routine-signature-v1\0");
        DefinitionObject badClosingDefinition = definition(source, ObjectType.FUNCTION,
                "Sales", "BAD_CLOSING", "oracle-routine-signature-v1\0");
        assertEquals(DefinitionConfidence.LOW, unsafeDefinition.confidence());
        assertEquals(DefinitionConfidence.HIGH, safeDefinition.confidence());
        assertEquals(DefinitionConfidence.LOW, badClosingDefinition.confidence());
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new OracleSchemaDiffCapability().comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);
        assertEquals(AutomationLevel.MANUAL_ONLY, diff.differences().stream()
                .filter(value -> value.object().equals(unsafeDefinition.key()))
                .findFirst().orElseThrow().automation());
        assertEquals(AutomationLevel.SAFE_AUTOMATIC, diff.differences().stream()
                .filter(value -> value.object().equals(safeDefinition.key()))
                .findFirst().orElseThrow().automation());
        assertEquals(AutomationLevel.MANUAL_ONLY, diff.differences().stream()
                .filter(value -> value.object().equals(badClosingDefinition.key()))
                .findFirst().orElseThrow().automation());
        assertTrue(plan.changes().stream()
                .filter(value -> value.object().equals(unsafeDefinition.key()))
                .allMatch(value -> value.kind() == ChangeKind.MANUAL
                        && !plan.selectedChangeIds().contains(value.id())));
        assertFalse((diff + plan.toString() + plan.digest()).contains("oracle-manual-definition"));
    }

    @Test
    void packageSpecReaderProjectionRenderAndRereadConvergeWhileUnknownSpecStaysLocalManual()
            throws Exception {
        String sourceSpec = "CREATE PACKAGE \"Source\".\"API\" AS "
                + "\"Source\" CONSTANT \"Source\".\"OBJ_T\" := NULL; "
                + "same_name NUMBER := \"Source\".value; "
                + "TYPE rec_t IS RECORD (value \"Source\".\"OBJ_T\"); "
                + "public_record rec_t := \"Source\".orders; "
                + "FUNCTION make(value IN \"Source\".\"OBJ_T\") RETURN \"Source\".\"OBJ_T\"; "
                + "PROCEDURE forward(value IN \"External\".\"OBJ_T\"); END API;\n/";
        String targetSpec = sourceSpec.replace("\"Source\".\"API\"", "\"Target\".\"API\"")
                .replace("CONSTANT \"Source\".\"OBJ_T\"", "CONSTANT \"Target\".\"OBJ_T\"")
                .replace("(value \"Source\".\"OBJ_T\")", "(value \"Target\".\"OBJ_T\")")
                .replace("IN \"Source\".\"OBJ_T\"", "IN \"Target\".\"OBJ_T\"")
                .replace("RETURN \"Source\".\"OBJ_T\"", "RETURN \"Target\".\"OBJ_T\"");
        String unknownSpec = "CREATE PACKAGE \"Source\".\"UNKNOWN_API\" AS "
                + "MYSTERY DECLARATION \"Source\".thing; END UNKNOWN_API;\n/";
        SchemaSnapshot source = new OracleSchemaSnapshotReader(new SnapshotJdbc("Source")
                .rows("definitions",
                        definitionRow("API", "PACKAGE", 910, 0, null),
                        definitionRow("UNKNOWN_API", "PACKAGE", 911, 0, null))
                .ddl("PACKAGE_SPEC", "API", sourceSpec)
                .ddl("PACKAGE_SPEC", "UNKNOWN_API", unknownSpec)
                .rows("sequences", row("sequence_name", "STABLE", "min_value", "1",
                        "max_value", "99", "increment_by", "1", "cycle_flag", "N",
                        "cache_size", 20, "order_flag", "N")).connection()).read(
                "source", OracleSchemaIdentifierNormalizer.schema("Source"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot empty = new SchemaSnapshot(DbType.ORACLE, "empty",
                OracleSchemaIdentifierNormalizer.schema("Target"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(), "empty");
        OracleSchemaDiffCapability capability = new OracleSchemaDiffCapability();
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, empty, capability.comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);
        DefinitionObject safe = definition(source, ObjectType.PACKAGE_SPEC, "Source", "API", "");
        DefinitionObject unknown = definition(
                source, ObjectType.PACKAGE_SPEC, "Source", "UNKNOWN_API", "");

        assertEquals(DefinitionConfidence.HIGH, safe.confidence());
        assertEquals(DefinitionConfidence.LOW, unknown.confidence());
        var safeChange = plan.changes().stream()
                .filter(value -> value.object().equals(safe.key())).findFirst().orElseThrow();
        var unknownChange = plan.changes().stream()
                .filter(value -> value.object().equals(unknown.key())).findFirst().orElseThrow();
        String sql = capability.changeRenderer().render(safeChange,
                new RenderContext(DbType.ORACLE, source.schema(), empty.schema(), false))
                .getFirst().sql();
        assertTrue(sql.contains("CREATE PACKAGE \"Target\".\"API\""), sql);
        assertTrue(sql.contains("same_name NUMBER := \"Source\".value"), sql);
        assertFalse(sql.contains("\0oracle-"), sql);
        assertEquals(ChangeKind.MANUAL, unknownChange.kind());
        assertFalse(plan.selectedChangeIds().contains(unknownChange.id()));
        assertEquals(OracleSchemaChangeRenderer.MANUAL_CHANGE,
                assertThrows(IllegalArgumentException.class,
                        () -> capability.changeRenderer().render(unknownChange,
                                new RenderContext(DbType.ORACLE, source.schema(),
                                        empty.schema(), false))).getMessage());

        SchemaSnapshot target = new OracleSchemaSnapshotReader(new SnapshotJdbc("Target")
                .rows("definitions", definitionRow("API", "PACKAGE", 912, 0, null))
                .ddl("PACKAGE_SPEC", "API", targetSpec)
                .rows("sequences", row("sequence_name", "STABLE", "min_value", "1",
                        "max_value", "99", "increment_by", "1", "cycle_flag", "N",
                        "cache_size", 20, "order_flag", "N")).connection()).read(
                "target", OracleSchemaIdentifierNormalizer.schema("Target"),
                SqlExecutionOptions.defaults(0));
        SchemaDiffResult converged = new SchemaDiffEngine().compare(
                source, target, capability.comparisonProjector());
        assertTrue(converged.differences().stream()
                .noneMatch(value -> value.object().type() == ObjectType.PACKAGE_SPEC
                        && value.object().name().original().equals("API")
                        && value.kind() != DifferenceKind.EQUIVALENT));
        assertFalse((diff + plan.toString() + plan.digest()).contains("oracle-manual-definition"));
    }

    @Test
    void labelRelationCollisionAndTypeSpecsFlowThroughReaderProjectionPlanAndReread()
            throws Exception {
        String sourceRoutine = "CREATE FUNCTION \"Source\".\"LABEL_RELATION\" RETURN NUMBER AS "
                + "BEGIN <<\"Source\">> DECLARE orders \"Source\".\"ORDER_REC\"; BEGIN "
                + "\"Source\".orders.value := 1; SELECT ID INTO orders.value FROM \"Source\".orders; "
                + "END \"Source\"; RETURN 1; END;\n/";
        String targetRoutine = sourceRoutine.replace("\"Source\".\"LABEL_RELATION\"",
                        "\"Target\".\"LABEL_RELATION\"")
                .replace("orders \"Source\".\"ORDER_REC\"",
                        "orders \"Target\".\"ORDER_REC\"")
                .replace("FROM \"Source\".orders", "FROM \"Target\".orders");
        String sourceType = "CREATE TYPE \"Source\".\"ORDER_T\" AS OBJECT ("
                + "id \"Source\".\"ID_T\", external_value \"External\".\"VALUE_T\", "
                + "MEMBER FUNCTION current_value RETURN \"Source\".\"RESULT_T\", "
                + "MEMBER FUNCTION convert(value IN \"Source\".\"ARG_T\") "
                + "RETURN \"Source\".\"RESULT_T\");";
        String targetType = sourceType.replace("\"Source\".\"ORDER_T\"",
                        "\"Target\".\"ORDER_T\"")
                .replace("\"Source\".\"ID_T\"", "\"Target\".\"ID_T\"")
                .replace("\"Source\".\"ARG_T\"", "\"Target\".\"ARG_T\"")
                .replace("\"Source\".\"RESULT_T\"", "\"Target\".\"RESULT_T\"");
        String unsafeType = "CREATE TYPE \"Source\".\"UNSAFE_T\" AS "
                + "VARRAY(10) OF \"Source\".\"ID_T\";";
        SchemaSnapshot source = new OracleSchemaSnapshotReader(new SnapshotJdbc("Source")
                .rows("definitions",
                        definitionRow("LABEL_RELATION", "FUNCTION", 920, 1, null),
                        definitionRow("ORDER_T", "TYPE", 921, 0, null),
                        definitionRow("UNSAFE_T", "TYPE", 922, 0, null))
                .ddl("FUNCTION", "LABEL_RELATION", sourceRoutine)
                .ddl("TYPE_SPEC", "ORDER_T", sourceType)
                .ddl("TYPE_SPEC", "UNSAFE_T", unsafeType)
                .rows("sequences", row("sequence_name", "STABLE", "min_value", "1",
                        "max_value", "99", "increment_by", "1", "cycle_flag", "N",
                        "cache_size", 20, "order_flag", "N")).connection()).read(
                "source", OracleSchemaIdentifierNormalizer.schema("Source"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot empty = new SchemaSnapshot(DbType.ORACLE, "empty",
                OracleSchemaIdentifierNormalizer.schema("Target"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(), "empty");
        OracleSchemaDiffCapability capability = new OracleSchemaDiffCapability();
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, empty, capability.comparisonProjector());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);
        DefinitionObject safeType = definition(source, ObjectType.TYPE,
                "Source", "ORDER_T", "SPEC");
        DefinitionObject unsafe = definition(source, ObjectType.TYPE,
                "Source", "UNSAFE_T", "SPEC");

        assertEquals(DefinitionConfidence.HIGH, safeType.confidence());
        assertEquals(DefinitionConfidence.LOW, unsafe.confidence());
        var routineChange = plan.changes().stream()
                .filter(value -> value.object().type() == ObjectType.FUNCTION)
                .findFirst().orElseThrow();
        var typeChange = plan.changes().stream()
                .filter(value -> value.object().equals(safeType.key())).findFirst().orElseThrow();
        String routineSql = capability.changeRenderer().render(routineChange,
                new RenderContext(DbType.ORACLE, source.schema(), empty.schema(), false))
                .getFirst().sql();
        String typeSql = capability.changeRenderer().render(typeChange,
                new RenderContext(DbType.ORACLE, source.schema(), empty.schema(), false))
                .getFirst().sql();
        assertTrue(routineSql.contains("\"Source\".orders.value := 1"), routineSql);
        assertTrue(routineSql.contains("FROM \"Target\".orders"), routineSql);
        assertTrue(typeSql.contains("id \"Target\".\"ID_T\""), typeSql);
        assertTrue(typeSql.contains("\"External\".\"VALUE_T\""), typeSql);
        var unsafeChange = plan.changes().stream()
                .filter(value -> value.object().equals(unsafe.key())).findFirst().orElseThrow();
        assertEquals(ChangeKind.MANUAL, unsafeChange.kind());
        assertFalse(plan.selectedChangeIds().contains(unsafeChange.id()));
        assertTrue(diff.differences().stream()
                .anyMatch(value -> value.object().type() == ObjectType.SEQUENCE));

        SchemaSnapshot target = new OracleSchemaSnapshotReader(new SnapshotJdbc("Target")
                .rows("definitions",
                        definitionRow("LABEL_RELATION", "FUNCTION", 923, 1, null),
                        definitionRow("ORDER_T", "TYPE", 924, 0, null))
                .ddl("FUNCTION", "LABEL_RELATION", targetRoutine)
                .ddl("TYPE_SPEC", "ORDER_T", targetType)
                .rows("sequences", row("sequence_name", "STABLE", "min_value", "1",
                        "max_value", "99", "increment_by", "1", "cycle_flag", "N",
                        "cache_size", 20, "order_flag", "N")).connection()).read(
                "target", OracleSchemaIdentifierNormalizer.schema("Target"),
                SqlExecutionOptions.defaults(0));
        SchemaDiffResult converged = new SchemaDiffEngine().compare(
                source, target, capability.comparisonProjector());
        assertTrue(converged.differences().stream().noneMatch(value ->
                (value.object().type() == ObjectType.FUNCTION
                        || value.object().type() == ObjectType.TYPE
                        && value.object().name().original().equals("ORDER_T"))
                        && value.kind() != DifferenceKind.EQUIVALENT));
        assertFalse((diff + plan.toString() + plan.digest()).contains("oracle-manual-definition"));
    }

    @Test
    void jdbcProxySnapshotsWithDifferentOwnersCompareByProviderRelativeIdentity() throws Exception {
        SnapshotJdbc sourceJdbc = new SnapshotJdbc("Source\"Owner")
                .rows("tables", row("table_name", "Order\"Line"))
                .rows("columns", column("Order\"Line", "Id", 1, "NUMBER", 19, 0,
                        null, null, null, null, "NO", "YES", "NO", null, null));
        SnapshotJdbc targetJdbc = new SnapshotJdbc("Target\"Owner")
                .rows("tables", row("table_name", "Order\"Line"))
                .rows("columns", column("Order\"Line", "Id", 1, "NUMBER", 19, 0,
                        null, null, null, null, "NO", "YES", "NO", null, null));
        SchemaSnapshot source = new OracleSchemaSnapshotReader(sourceJdbc.connection()).read(
                "source", OracleSchemaIdentifierNormalizer.schema("Source\"Owner"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new OracleSchemaSnapshotReader(targetJdbc.connection()).read(
                "target", OracleSchemaIdentifierNormalizer.schema("Target\"Owner"),
                SqlExecutionOptions.defaults(0));

        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new OracleSchemaDiffCapability().comparisonProjector());

        assertTrue(diff.differences().stream()
                .allMatch(difference -> difference.kind() == DifferenceKind.EQUIVALENT));
        assertTrue(diff.renameSuggestions().isEmpty());
    }

    @Test
    void routineSignatureKeepsAmbiguousExternalOwnerAndTypeStructurallyDistinct() throws Exception {
        SnapshotJdbc sourceJdbc = new SnapshotJdbc("Self.Owner")
                .rows("definitions", definitionRow("CALC", "FUNCTION", 301, 1, null))
                .rows("arguments", argument("CALC", 301, 1, 1, 0, "IN",
                        "Owner.Type", "Self", null, null, null, "P_VALUE", null))
                .ddl("FUNCTION", "CALC", "CREATE OR REPLACE FUNCTION "
                        + "\"Self.Owner\".\"CALC\"(P_VALUE IN \"Self\".\"Owner.Type\") "
                        + "RETURN NUMBER AS BEGIN RETURN 1; END;\n/");
        SnapshotJdbc targetJdbc = new SnapshotJdbc("Target.Owner")
                .rows("definitions", definitionRow("CALC", "FUNCTION", 401, 1, null))
                .rows("arguments", argument("CALC", 401, 1, 1, 0, "IN",
                        "Owner.Type", "Self", null, null, null, "P_VALUE", null))
                .ddl("FUNCTION", "CALC", "CREATE OR REPLACE FUNCTION "
                        + "\"Target.Owner\".\"CALC\"(P_VALUE IN \"Self\".\"Owner.Type\") "
                        + "RETURN NUMBER AS BEGIN RETURN 1; END;\n/");
        SchemaSnapshot source = new OracleSchemaSnapshotReader(sourceJdbc.connection()).read(
                "source", OracleSchemaIdentifierNormalizer.schema("Self.Owner"),
                SqlExecutionOptions.defaults(0));
        SchemaSnapshot target = new OracleSchemaSnapshotReader(targetJdbc.connection()).read(
                "target", OracleSchemaIdentifierNormalizer.schema("Target.Owner"),
                SqlExecutionOptions.defaults(0));

        DefinitionObject routine = source.objects().values().stream()
                .filter(DefinitionObject.class::isInstance).map(DefinitionObject.class::cast)
                .findFirst().orElseThrow();
        assertTrue(routine.key().signature().contains("\"Self\".\"Owner.Type\""));
        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, target, new OracleSchemaDiffCapability().comparisonProjector());
        assertTrue(diff.differences().stream()
                .allMatch(difference -> difference.kind() == DifferenceKind.EQUIVALENT));
        assertTrue(diff.renameSuggestions().isEmpty());
    }

    @Test
    void readsFullOracleColumnTypeDefaultIdentityVirtualAndCommentMatrix() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "Order\"Line"))
                .rows("columns",
                        column("Order\"Line", "CREATED_AT", 5, "DATE", null, null, null,
                                null, null, null, "NO", "NO", "NO", null, null),
                        column("Order\"Line", "ID", 1, "NUMBER", 12, -2, null,
                                null, null, null, "NO", "NO", "YES", "ALWAYS", "ISEQ$$_1.NEXTVAL"),
                        column("Order\"Line", "CODE", 2, "VARCHAR2", null, null, 40L,
                                "C", null, null, "NO", "YES", "NO", null, "'new'"),
                        column("Order\"Line", "RAW_PAYLOAD", 3, "RAW", null, null, 16L,
                                null, null, null, "NO", "NO", "NO", null, null),
                        column("Order\"Line", "EVENT_TIME", 4, "TIMESTAMP(6) WITH TIME ZONE",
                                null, 6, null, null, null, null, "NO", "NO", "NO", null, null),
                        column("Order\"Line", "LOCAL_TIME", 6, "TIMESTAMP(3) WITH LOCAL TIME ZONE",
                                null, 3, null, null, null, null, "NO", "NO", "NO", null, null),
                        column("Order\"Line", "DURATION", 7, "INTERVAL DAY(2) TO SECOND(6)",
                                null, 6, null, null, null, null, "NO", "NO", "NO", null, null),
                        column("Order\"Line", "ADDRESS", 8, "ADDRESS_T", null, null, null,
                                null, "Sales", null, "NO", "NO", "NO", null, null),
                        column("Order\"Line", "TOTAL", 9, "NUMBER", 18, 2, null,
                                null, null, null, "YES", "NO", "NO", null, "AMOUNT * 2"),
                        column("Order\"Line", "AMOUNT", 10, "NUMBER", 18, 2, null,
                                null, null, null, "NO", "YES", "NO", null, "0"),
                        row("table_name", "Order\"Line", "column_name", "PRIVATE_CODE",
                                "column_id", 11, "data_type", "VARCHAR2", "data_length", 20L,
                                "char_length", 20L, "char_used", "B", "data_precision", null,
                                "data_scale", null, "data_type_owner", null, "data_type_mod", null,
                                "nullable", "Y", "identity_column", "NO", "generation_type", null,
                                "default_on_null", "NO", "virtual_column", "NO",
                                "hidden_column", "YES", "user_generated", "YES",
                                "comments", "user invisible", "identity_options", null,
                                "data_default", null),
                        row("table_name", "Order\"Line", "column_name", "SYS_NC00001$",
                                "column_id", 12, "data_type", "VARCHAR2", "data_length", 20L,
                                "char_length", 20L, "char_used", "B", "data_precision", null,
                                "data_scale", null, "data_type_owner", null, "data_type_mod", null,
                                "nullable", "Y", "identity_column", "NO", "generation_type", null,
                                "default_on_null", "NO", "virtual_column", "YES",
                                "hidden_column", "YES", "user_generated", "NO",
                                "comments", "must not leak", "identity_options", null,
                                "data_default", "secret hidden default"));

        SchemaSnapshot snapshot = new OracleSchemaSnapshotReader(jdbc.connection()).read(
                "oracle-connection", OracleSchemaIdentifierNormalizer.schema("Sales"),
                new SqlExecutionOptions(0, 9, new SqlExecutionControl()));

        TableDefinition table = assertInstanceOf(TableDefinition.class,
                snapshot.objects().get(key(ObjectType.TABLE, "Sales", "Order\"Line", "")));
        assertEquals(List.of("\"ID\"", "\"CODE\"", "\"RAW_PAYLOAD\"", "\"EVENT_TIME\"",
                        "\"CREATED_AT\"", "\"LOCAL_TIME\"", "\"DURATION\"", "\"ADDRESS\"",
                        "\"TOTAL\"", "\"AMOUNT\"", "\"PRIVATE_CODE\""),
                table.columns().stream().map(column -> column.name().original()).toList());

        ColumnDefinition id = table.columns().getFirst();
        assertEquals("NUMBER", id.dataType().baseType());
        assertEquals(12, id.dataType().precision());
        assertEquals(-2, id.dataType().scale());
        assertEquals("GENERATED ALWAYS AS IDENTITY", id.normalizedDefault());
        assertEquals("ALWAYS", id.dataType().providerExtensions().get("oracle.identity"));

        ColumnDefinition code = table.columns().get(1);
        assertEquals(40L, code.dataType().length());
        assertEquals("CHAR", code.dataType().providerExtensions().get("oracle.lengthSemantics"));
        assertEquals("DEFAULT ON NULL 'new'", code.normalizedDefault());
        assertEquals("customer-visible", code.comment());

        CanonicalDataType eventTime = table.columns().get(3).dataType();
        assertEquals("TIMESTAMP", eventTime.baseType());
        assertTrue(eventTime.withTimeZone());
        assertEquals(6, eventTime.scale());
        assertEquals("WITH TIME ZONE", eventTime.providerExtensions().get("oracle.timeZone"));
        CanonicalDataType localTime = table.columns().get(5).dataType();
        assertTrue(localTime.withTimeZone());
        assertEquals("WITH LOCAL TIME ZONE", localTime.providerExtensions().get("oracle.timeZone"));

        CanonicalDataType interval = table.columns().get(6).dataType();
        assertEquals("INTERVAL DAY TO SECOND", interval.baseType());
        assertEquals("INTERVAL DAY(2) TO SECOND(6)",
                interval.providerExtensions().get("formattedType"));
        CanonicalDataType objectType = table.columns().get(7).dataType();
        assertEquals("Sales.ADDRESS_T", objectType.baseType());
        assertEquals("\"Sales\".\"ADDRESS_T\"", objectType.providerExtensions().get("formattedType"));

        assertEquals("GENERATED ALWAYS AS (AMOUNT * 2) VIRTUAL",
                table.columns().get(8).normalizedDefault());
        assertEquals("true", table.columns().get(10).dataType()
                .providerExtensions().get("oracle.invisible"));
        assertNull(table.columns().stream()
                .filter(column -> column.name().original().contains("SYS_NC"))
                .findFirst().orElse(null));
        assertTrue(snapshot.completeness().complete());
        assertEquals("oracle-connection", snapshot.connectionId());
        assertEquals(64, snapshot.fingerprint().length());
        assertFalse(jdbc.connectionSetSchemaCalled());
        assertEquals("data_default", jdbc.statement("columns").lastReadLabel());
        assertFalse(jdbc.statement("columns").sql().contains("c.INVISIBLE_COLUMN"));
    }

    @Test
    void readsConstraintsIndexesAndDeclarativeSequenceSemanticsWithoutRuntimeDrift() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "ORDERS"), row("table_name", "CUSTOMERS"))
                .rows("constraints",
                        row("table_name", "ORDERS", "constraint_name", "ORDERS_PK",
                                "constraint_type", "P", "position", 1, "column_name", "ID",
                                "referenced_owner", null, "referenced_table_name", null,
                                "referenced_column_name", null, "delete_rule", null,
                                "generated", "USER NAME", "search_condition", null),
                        row("table_name", "ORDERS", "constraint_name", "ORDERS_UQ",
                                "constraint_type", "U", "position", 2, "column_name", "REGION",
                                "referenced_owner", null, "referenced_table_name", null,
                                "referenced_column_name", null, "delete_rule", null,
                                "generated", "USER NAME", "search_condition", null),
                        row("table_name", "ORDERS", "constraint_name", "ORDERS_UQ",
                                "constraint_type", "U", "position", 1, "column_name", "CODE",
                                "referenced_owner", null, "referenced_table_name", null,
                                "referenced_column_name", null, "delete_rule", null,
                                "generated", "USER NAME", "search_condition", null),
                        row("table_name", "ORDERS", "constraint_name", "ORDERS_CUSTOMER_FK",
                                "constraint_type", "R", "position", 1, "column_name", "CUSTOMER_ID",
                                "referenced_owner", "Sales", "referenced_table_name", "CUSTOMERS",
                                "referenced_column_name", "ID", "delete_rule", "CASCADE",
                                "generated", "USER NAME", "search_condition", null),
                        row("table_name", "ORDERS", "constraint_name", "SYS_C009",
                                "constraint_type", "C", "position", null, "column_name", null,
                                "referenced_owner", null, "referenced_table_name", null,
                                "referenced_column_name", null, "delete_rule", null,
                                "generated", "GENERATED NAME", "search_condition", "AMOUNT > 0"))
                .rows("indexes",
                        row("table_name", "ORDERS", "index_name", "ORDERS_PK_IDX",
                                "index_type", "NORMAL", "uniqueness", "UNIQUE", "column_position", 1,
                                "column_name", "ID", "constraint_name", "ORDERS_PK",
                                "descend", "ASC", "column_expression", null),
                        row("table_name", "ORDERS", "index_name", "ORDERS_CODE_IDX",
                                "index_type", "NORMAL", "uniqueness", "NONUNIQUE", "column_position", 1,
                                "column_name", "CODE", "constraint_name", null,
                                "descend", "ASC", "column_expression", null),
                        row("table_name", "ORDERS", "index_name", "ORDERS_CODE_IDX",
                                "index_type", "NORMAL", "uniqueness", "NONUNIQUE", "column_position", 2,
                                "column_name", "REGION", "constraint_name", null,
                                "descend", "DESC", "column_expression", null),
                        row("table_name", "ORDERS", "index_name", "ORDERS_UPPER_IDX",
                                "index_type", "FUNCTION-BASED NORMAL", "uniqueness", "NONUNIQUE",
                                "column_position", 1, "column_name", "SYS_NC00002$", "constraint_name", null,
                                "descend", "DESC", "column_expression", "UPPER(\"CODE\")"))
                .rows("sequences",
                        row("sequence_name", "ORDERS_SEQ", "min_value", "1", "max_value", "999999",
                                "increment_by", "5", "cycle_flag", "Y", "cache_size", 20,
                                "order_flag", "Y", "last_number", "secret-runtime-1005"),
                        row("sequence_name", "AUDIT_SEQ", "min_value", "1", "max_value", "9999",
                                "increment_by", "1", "cycle_flag", "N", "cache_size", 0,
                                "order_flag", "N", "last_number", "secret-runtime-77"));

        SchemaSnapshot snapshot = new OracleSchemaSnapshotReader(jdbc.connection()).read(
                "oracle-connection", OracleSchemaIdentifierNormalizer.schema("Sales"),
                new SqlExecutionOptions(0, 13, new SqlExecutionControl()));

        TableDefinition orders = assertInstanceOf(TableDefinition.class,
                snapshot.objects().get(key(ObjectType.TABLE, "Sales", "ORDERS", "")));
        assertEquals(List.of(ConstraintKind.PRIMARY_KEY, ConstraintKind.UNIQUE,
                        ConstraintKind.FOREIGN_KEY, ConstraintKind.CHECK),
                orders.constraints().stream().map(ConstraintDefinition::kind).sorted().toList());
        ConstraintDefinition unique = orders.constraints().stream()
                .filter(value -> value.kind() == ConstraintKind.UNIQUE).findFirst().orElseThrow();
        assertEquals(List.of("\"CODE\"", "\"REGION\""),
                unique.columns().stream().map(value -> value.original()).toList());
        ConstraintDefinition foreignKey = orders.constraints().stream()
                .filter(value -> value.kind() == ConstraintKind.FOREIGN_KEY).findFirst().orElseThrow();
        ObjectKey customers = key(ObjectType.TABLE, "Sales", "CUSTOMERS", "");
        assertEquals(customers, foreignKey.referencedTable());
        assertEquals(List.of("\"ID\""),
                foreignKey.referencedColumns().stream().map(value -> value.original()).toList());
        assertNull(foreignKey.updateAction());
        assertEquals("CASCADE", foreignKey.deleteAction());
        assertTrue(foreignKey.dependencies().contains(customers));
        ConstraintDefinition check = orders.constraints().stream()
                .filter(value -> value.kind() == ConstraintKind.CHECK).findFirst().orElseThrow();
        assertEquals("AMOUNT > 0", check.normalizedExpression());
        assertTrue(check.providerGeneratedName());

        assertEquals(3, orders.indexes().size());
        IndexDefinition backing = orders.indexes().stream()
                .filter(value -> value.key().name().original().contains("ORDERS_PK_IDX"))
                .findFirst().orElseThrow();
        assertTrue(backing.providerGeneratedName());
        assertEquals(List.of("\"ID\""), backing.normalizedExpressions());
        IndexDefinition function = orders.indexes().stream()
                .filter(value -> value.key().name().original().contains("ORDERS_UPPER_IDX"))
                .findFirst().orElseThrow();
        assertEquals(List.of("UPPER(\"CODE\") DESC"), function.normalizedExpressions());
        IndexDefinition ordinary = orders.indexes().stream()
                .filter(value -> value.key().name().original().contains("ORDERS_CODE_IDX"))
                .findFirst().orElseThrow();
        assertEquals(List.of("\"CODE\"", "\"REGION\" DESC"), ordinary.normalizedExpressions());
        assertTrue(jdbc.statement("indexes").sql().contains("columns.DESCEND"));

        SequenceDefinition orderSequence = assertInstanceOf(SequenceDefinition.class,
                snapshot.objects().get(key(ObjectType.SEQUENCE, "Sales", "ORDERS_SEQ", "")));
        assertNull(orderSequence.startValue());
        assertEquals("5", orderSequence.incrementBy());
        assertEquals("1", orderSequence.minimumValue());
        assertEquals("999999", orderSequence.maximumValue());
        assertTrue(orderSequence.cycle());
        assertEquals(20, orderSequence.cacheSize());
        assertEquals(Map.of("oracle.order", "ORDER", "oracle.startValueKnown", "false"),
                orderSequence.providerExtensions());
        SequenceDefinition noOrderSequence = assertInstanceOf(SequenceDefinition.class,
                snapshot.objects().get(key(ObjectType.SEQUENCE, "Sales", "AUDIT_SEQ", "")));
        assertEquals("NOORDER", noOrderSequence.providerExtensions().get("oracle.order"));
        assertFalse(jdbc.statement("sequences").sql().contains("LAST_NUMBER"));
        assertFalse(jdbc.statement("sequences").readLabels().contains("last_number"));
        assertTrue(snapshot.completeness().complete());
        assertFalse(snapshot.completeness().unavailableScopes().containsKey(ObjectType.SEQUENCE));
    }

    @Test
    void filtersNestedSecondaryAndIotOverflowImplementationTablesInCatalogSql() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales").rows("tables",
                tableRow("ORDERS", "NO", "N", null),
                tableRow("IOT_TOP", "NO", "N", "IOT"),
                tableRow("NESTED_IMPL", "YES", "N", null),
                tableRow("SECONDARY_IMPL", "NO", "Y", null),
                tableRow("IOT_OVERFLOW_IMPL", "NO", "N", "IOT_OVERFLOW"));

        SchemaSnapshot snapshot = read(jdbc, "connection");

        assertEquals(Set.of("\"Sales\".\"ORDERS\"", "\"Sales\".\"IOT_TOP\""),
                snapshot.objects().keySet().stream()
                        .filter(key -> key.type() == ObjectType.TABLE)
                        .map(key -> key.name().original()).collect(java.util.stream.Collectors.toSet()));
        String sql = jdbc.statement("tables").sql();
        assertTrue(sql.contains("t.NESTED = 'NO'"));
        assertTrue(sql.contains("t.SECONDARY = 'N'"));
        assertTrue(sql.contains("t.IOT_TYPE IS NULL OR t.IOT_TYPE = 'IOT'"));
    }

    @Test
    void identityOptionsPreserveDeclarativeSequenceSemanticsInTypeExtensions() throws Exception {
        String firstOptions = "  START WITH: 1,  INCREMENT BY: 5, MAX_VALUE: 999, "
                + "MIN_VALUE: 1, CYCLE_FLAG: N, CACHE_SIZE: 20, ORDER_FLAG: Y  ";
        SnapshotJdbc firstJdbc = new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "ORDERS"))
                .rows("columns", identityColumn(firstOptions));

        SchemaSnapshot first = read(firstJdbc, "first");

        TableDefinition table = assertInstanceOf(TableDefinition.class,
                first.objects().get(key(ObjectType.TABLE, "Sales", "ORDERS", "")));
        assertEquals("START WITH: 1, INCREMENT BY: 5, MAX_VALUE: 999, MIN_VALUE: 1, "
                        + "CYCLE_FLAG: N, CACHE_SIZE: 20, ORDER_FLAG: Y",
                table.columns().getFirst().dataType().providerExtensions()
                        .get("oracle.identityOptions"));
        for (String changedOptions : List.of(
                "START WITH: 1, INCREMENT BY: 7, MAX_VALUE: 999, MIN_VALUE: 1, CYCLE_FLAG: N, CACHE_SIZE: 20, ORDER_FLAG: Y",
                "START WITH: 1, INCREMENT BY: 5, MAX_VALUE: 1000, MIN_VALUE: 1, CYCLE_FLAG: N, CACHE_SIZE: 20, ORDER_FLAG: Y",
                "START WITH: 1, INCREMENT BY: 5, MAX_VALUE: 999, MIN_VALUE: 0, CYCLE_FLAG: N, CACHE_SIZE: 20, ORDER_FLAG: Y",
                "START WITH: 1, INCREMENT BY: 5, MAX_VALUE: 999, MIN_VALUE: 1, CYCLE_FLAG: Y, CACHE_SIZE: 20, ORDER_FLAG: Y",
                "START WITH: 1, INCREMENT BY: 5, MAX_VALUE: 999, MIN_VALUE: 1, CYCLE_FLAG: N, CACHE_SIZE: 40, ORDER_FLAG: Y",
                "START WITH: 1, INCREMENT BY: 5, MAX_VALUE: 999, MIN_VALUE: 1, CYCLE_FLAG: N, CACHE_SIZE: 20, ORDER_FLAG: N")) {
            SnapshotJdbc changedJdbc = new SnapshotJdbc("Sales")
                    .rows("tables", row("table_name", "ORDERS"))
                    .rows("columns", identityColumn(changedOptions));
            assertNotEquals(first.fingerprint(), read(changedJdbc, "changed").fingerprint(),
                    changedOptions);
        }
        String columnsSql = firstJdbc.statement("columns").sql();
        assertTrue(columnsSql.contains("identity.IDENTITY_OPTIONS"));
        assertFalse(columnsSql.contains("LAST_NUMBER"));
        assertFalse(columnsSql.contains("ISEQ$$_"));
    }

    @Test
    void readsDefinitionsRoutineSignaturesAndOnlyResolvedSameOwnerDependencies() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "ORDERS"))
                .rows("definitions",
                        definitionRow("ORDERS_V", "VIEW", 201, 0, null),
                        definitionRow("ORDERS_MV", "MATERIALIZED VIEW", 202, 0, null),
                        definitionRow("CALC", "FUNCTION", 301, 1, null),
                        definitionRow("REFRESH_ORDERS", "PROCEDURE", 302, 1, null),
                        definitionRow("AUDIT_ORDERS", "TRIGGER", 401, 0, "ORDERS"),
                        definitionRow("ORDER_API", "PACKAGE", 501, 0, null),
                        definitionRow("ORDER_API", "PACKAGE BODY", 502, 0, null),
                        definitionRow("ADDRESS_T", "TYPE", 601, 0, null),
                        definitionRow("ADDRESS_T", "TYPE BODY", 602, 0, null))
                .rows("arguments",
                        argument("CALC", 301, 1, 0, 0, "OUT", "NUMBER", null, null, null, null,
                                "RETURN_SECRET", "return default secret"),
                        argument("CALC", 301, 1, 1, 0, "IN", "NUMBER", null, 10, 0, null,
                                "P_AMOUNT", "amount default secret"),
                        argument("CALC", 301, 1, 2, 0, "OUT", "VARCHAR2", null, null, null, 200,
                                "P_RESULT", null),
                        argument("CALC", 301, 1, 3, 1, "IN", "VARCHAR2", null, null, null, 99,
                                "NESTED_SECRET", null))
                .ddl("VIEW", "ORDERS_V", "CREATE VIEW \"Sales\".\"ORDERS_V\" AS SELECT * FROM \"Sales\".\"ORDERS\";")
                .ddl("MATERIALIZED_VIEW", "ORDERS_MV", "CREATE MATERIALIZED VIEW \"Sales\".\"ORDERS_MV\" "
                        + "SEGMENT CREATION IMMEDIATE AS SELECT * FROM \"Sales\".\"ORDERS\";")
                .ddl("FUNCTION", "CALC", "CREATE OR REPLACE FUNCTION \"Sales\".\"CALC\" RETURN NUMBER IS\n"
                        + "BEGIN RETURN 1; END;\n/")
                .ddl("PROCEDURE", "REFRESH_ORDERS", "CREATE OR REPLACE PROCEDURE \"Sales\".\"REFRESH_ORDERS\" IS\n"
                        + "BEGIN NULL; END;\n/")
                .ddl("TRIGGER", "AUDIT_ORDERS", "CREATE OR REPLACE TRIGGER \"Sales\".\"AUDIT_ORDERS\" "
                        + "BEFORE INSERT ON \"Sales\".\"ORDERS\" BEGIN NULL; END;\n/")
                .ddl("PACKAGE_SPEC", "ORDER_API", "CREATE OR REPLACE PACKAGE \"Sales\".\"ORDER_API\" IS END;\n/")
                .ddl("PACKAGE_BODY", "ORDER_API", "CREATE OR REPLACE PACKAGE BODY \"Sales\".\"ORDER_API\" IS END;\n/")
                .ddl("TYPE_SPEC", "ADDRESS_T", "CREATE TYPE \"Sales\".\"ADDRESS_T\" AS OBJECT (CITY VARCHAR2(20));")
                .ddl("TYPE_BODY", "ADDRESS_T", "CREATE TYPE BODY \"Sales\".\"ADDRESS_T\" AS END;\n/")
                .rows("dependencies",
                        dependency("ORDERS_V", "VIEW", "Sales", "ORDERS", "TABLE"),
                        dependency("CALC", "FUNCTION", "Sales", "ADDRESS_T", "TYPE"),
                        dependency("REFRESH_ORDERS", "PROCEDURE", "Sales", "MISSING_V", "VIEW"),
                        dependency("ORDER_API", "PACKAGE BODY", "Sales", "ORDER_API", "PACKAGE"),
                        dependency("ADDRESS_T", "TYPE BODY", "Sales", "ADDRESS_T", "TYPE"),
                        dependency("AUDIT_ORDERS", "TRIGGER", "SYS", "DBMS_STANDARD", "PACKAGE"));

        SchemaSnapshot snapshot = new OracleSchemaSnapshotReader(jdbc.connection()).read(
                "oracle-connection", OracleSchemaIdentifierNormalizer.schema("Sales"),
                new SqlExecutionOptions(0, 17, new SqlExecutionControl()));

        DefinitionObject view = definition(snapshot, ObjectType.VIEW, "Sales", "ORDERS_V", "");
        assertEquals(DefinitionConfidence.HIGH, view.confidence());
        ObjectKey orders = key(ObjectType.TABLE, "Sales", "ORDERS", "");
        assertEquals(Set.of(orders), view.dependencies());
        DefinitionObject materialized = definition(
                snapshot, ObjectType.MATERIALIZED_VIEW, "Sales", "ORDERS_MV", "");
        assertEquals(DefinitionConfidence.LOW, materialized.confidence());
        assertTrue(materialized.originalDefinition().contains("SEGMENT CREATION IMMEDIATE"));
        assertEquals(SnapshotCompleteness.DEFINITION_UNAVAILABLE,
                snapshot.completeness().unavailableScopes().get(ObjectType.MATERIALIZED_VIEW));

        DefinitionObject function = snapshot.objects().values().stream()
                .filter(DefinitionObject.class::isInstance).map(DefinitionObject.class::cast)
                .filter(value -> value.key().type() == ObjectType.FUNCTION
                        && value.key().name().original().contains("CALC"))
                .findFirst().orElseThrow();
        assertTrue(function.key().signature().contains("IN"));
        assertTrue(function.key().signature().contains("NUMBER(10,0)"));
        assertFalse(function.key().signature().contains("P_AMOUNT"));
        assertFalse(function.key().signature().contains("SECRET"));
        assertFalse(function.key().signature().contains("P_RESULT"));
        assertFalse(function.key().signature().contains("NESTED"));
        assertTrue(function.dependencies().contains(
                key(ObjectType.TYPE, "Sales", "ADDRESS_T", "SPEC")));
        DefinitionObject procedure = snapshot.objects().values().stream()
                .filter(DefinitionObject.class::isInstance).map(DefinitionObject.class::cast)
                .filter(value -> value.key().type() == ObjectType.PROCEDURE).findFirst().orElseThrow();
        assertEquals("oracle-routine-signature-v1\0", procedure.key().signature());
        assertEquals(SnapshotCompleteness.DEPENDENCY_UNRESOLVED,
                snapshot.completeness().unavailableScopes().get(ObjectType.PROCEDURE));

        DefinitionObject trigger = definition(
                snapshot, ObjectType.TRIGGER, "Sales", "AUDIT_ORDERS", "");
        assertEquals(Set.of(orders), trigger.dependencies(), "external SYS dependency must be ignored");
        DefinitionObject packageSpec = definition(
                snapshot, ObjectType.PACKAGE_SPEC, "Sales", "ORDER_API", "");
        DefinitionObject packageBody = definition(
                snapshot, ObjectType.PACKAGE_BODY, "Sales", "ORDER_API", "");
        assertEquals(Set.of(packageSpec.key()), packageBody.dependencies());
        DefinitionObject typeSpec = definition(snapshot, ObjectType.TYPE, "Sales", "ADDRESS_T", "SPEC");
        DefinitionObject typeBody = definition(snapshot, ObjectType.TYPE, "Sales", "ADDRESS_T", "BODY");
        assertEquals(Set.of(typeSpec.key()), typeBody.dependencies());

        List<SnapshotJdbc.StatementTrace> ddlStatements = jdbc.statements().stream()
                .filter(value -> value.tag().equals("ddl")).toList();
        assertEquals(1, ddlStatements.stream()
                .filter(value -> "CALC".equals(value.bindings().get(2))).count(),
                "a standalone routine has one ALL_PROCEDURES identity row");
        assertTrue(jdbc.statement("definitions").sql().contains("ALL_PROCEDURES"));
        assertTrue(jdbc.statement("definitions").sql().contains("PROCEDURE_NAME IS NULL"));
        assertEquals(9, ddlStatements.size());
        assertEquals(List.of("FUNCTION", "MATERIALIZED_VIEW", "PACKAGE_BODY",
                        "PACKAGE_SPEC", "PROCEDURE", "TRIGGER", "TYPE_BODY", "TYPE_SPEC", "VIEW"),
                ddlStatements.stream().map(value -> (String) value.bindings().get(1))
                        .sorted().toList());
        assertTrue(ddlStatements.stream().allMatch(value -> value.timeout() == 17));
        assertTrue(ddlStatements.stream().allMatch(value -> "Sales".equals(value.bindings().get(3))));
        assertTrue(ddlStatements.stream().allMatch(value -> value.nextCalls() == 2),
                "GET_DDL result sets must be drained before activation release");
        assertFalse(new OracleSchemaSnapshotReader(jdbc.connection()).toString().contains("Sales"));
        assertFalse(snapshot.toString().contains("amount default secret"));
    }

    @Test
    void triggerBaseDependenciesRespectCatalogOwnerAndType() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "ORDERS"), row("table_name", "EXT_TARGET"))
                .rows("definitions",
                        definitionRow("ORDERS_V", "VIEW", 101, 0, null, null, null),
                        definitionRow("TABLE_TRIGGER", "TRIGGER", 201, 0,
                                "Sales", "TABLE", "ORDERS"),
                        definitionRow("VIEW_TRIGGER", "TRIGGER", 202, 0,
                                "Sales", "VIEW", "ORDERS_V"),
                        definitionRow("EXTERNAL_TRIGGER", "TRIGGER", 203, 0,
                                "Other", "TABLE", "EXT_TARGET"),
                        definitionRow("SCHEMA_TRIGGER", "TRIGGER", 204, 0,
                                "Sales", "SCHEMA", "ORDERS"),
                        definitionRow("DATABASE_TRIGGER", "TRIGGER", 205, 0,
                                "Sales", "DATABASE", "ORDERS"),
                        definitionRow("MISSING_VIEW_TRIGGER", "TRIGGER", 206, 0,
                                "Sales", "VIEW", "MISSING_V"))
                .ddl("VIEW", "ORDERS_V", "CREATE VIEW ORDERS_V AS SELECT 1")
                .ddl("TRIGGER", "TABLE_TRIGGER", "CREATE TRIGGER TABLE_TRIGGER BEFORE INSERT ON ORDERS BEGIN NULL; END;")
                .ddl("TRIGGER", "VIEW_TRIGGER", "CREATE TRIGGER VIEW_TRIGGER INSTEAD OF INSERT ON ORDERS_V BEGIN NULL; END;")
                .ddl("TRIGGER", "EXTERNAL_TRIGGER", "CREATE TRIGGER EXTERNAL_TRIGGER BEFORE INSERT ON EXT_TARGET BEGIN NULL; END;")
                .ddl("TRIGGER", "SCHEMA_TRIGGER", "CREATE TRIGGER SCHEMA_TRIGGER AFTER LOGON ON SCHEMA BEGIN NULL; END;")
                .ddl("TRIGGER", "DATABASE_TRIGGER", "CREATE TRIGGER DATABASE_TRIGGER AFTER STARTUP ON DATABASE BEGIN NULL; END;")
                .ddl("TRIGGER", "MISSING_VIEW_TRIGGER", "CREATE TRIGGER MISSING_VIEW_TRIGGER INSTEAD OF INSERT ON MISSING_V BEGIN NULL; END;");

        SchemaSnapshot snapshot = read(jdbc, "connection");

        ObjectKey orders = key(ObjectType.TABLE, "Sales", "ORDERS", "");
        ObjectKey view = key(ObjectType.VIEW, "Sales", "ORDERS_V", "");
        assertEquals(Set.of(orders), definition(
                snapshot, ObjectType.TRIGGER, "Sales", "TABLE_TRIGGER", "").dependencies());
        assertEquals(Set.of(view), definition(
                snapshot, ObjectType.TRIGGER, "Sales", "VIEW_TRIGGER", "").dependencies());
        for (String name : List.of("EXTERNAL_TRIGGER", "SCHEMA_TRIGGER", "DATABASE_TRIGGER",
                "MISSING_VIEW_TRIGGER")) {
            assertTrue(definition(snapshot, ObjectType.TRIGGER, "Sales", name, "")
                    .dependencies().isEmpty(), name);
        }
        assertEquals(SnapshotCompleteness.DEPENDENCY_UNRESOLVED,
                snapshot.completeness().unavailableScopes().get(ObjectType.TRIGGER));
        String sql = jdbc.statement("definitions").sql();
        assertTrue(sql.contains("triggers.TABLE_OWNER AS BASE_OBJECT_OWNER"));
        assertTrue(sql.contains("triggers.BASE_OBJECT_TYPE"));
    }

    @Test
    void permissionFailureAfterAColumnRowRollsBackTheWholeCategory() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "ORDERS"))
                .rows("columns", column("ORDERS", "ID", 1, "NUMBER", 10, 0,
                        null, null, null, null, "NO", "NO", "NO", null, null))
                .failAfterRows("columns", 1,
                        new SQLException("driver secret text", "42000", 1031));

        SchemaSnapshot snapshot = read(jdbc, "first");

        TableDefinition table = assertInstanceOf(TableDefinition.class,
                snapshot.objects().get(key(ObjectType.TABLE, "Sales", "ORDERS", "")));
        assertTrue(table.columns().isEmpty(), "a partial category must not leak its first row");
        assertEquals(SnapshotCompleteness.PERMISSION_DENIED,
                snapshot.completeness().unavailableScopes().get(ObjectType.TABLE));
        assertFalse(snapshot.toString().contains("driver secret text"));
    }

    @Test
    void knownGetDdlVisibilityGapBecomesSafeDefinitionPartial() throws Exception {
        SnapshotJdbc jdbc = new SnapshotJdbc("Sales")
                .rows("definitions", definitionRow("ORDERS_V", "VIEW", 201, 0, null))
                .failure("ddl", new SQLException(
                        "ORA-31603: secret owner and object", "99999", 31603));

        SchemaSnapshot snapshot = read(jdbc, "connection-secret");

        assertFalse(snapshot.objects().containsKey(
                key(ObjectType.VIEW, "Sales", "ORDERS_V", "")));
        assertEquals(SnapshotCompleteness.DEFINITION_UNAVAILABLE,
                snapshot.completeness().unavailableScopes().get(ObjectType.VIEW));
        assertFalse(snapshot.completeness().toString().contains("secret"));
    }

    @Test
    void unknownAndTimeoutFailuresRemainDistinctFixedSafeTerminalErrors() {
        SnapshotJdbc unknown = new SnapshotJdbc("Sales").failure(
                "constraints", new SQLException("driver text with secret DDL", "08006", 600));
        SQLException unknownFailure = assertThrows(SQLException.class,
                () -> read(unknown, "connection-secret"));
        assertEquals("Snapshot metadata failed", unknownFailure.getMessage());
        assertEquals("08006", unknownFailure.getSQLState());
        assertEquals(600, unknownFailure.getErrorCode());
        assertNull(unknownFailure.getCause());

        SnapshotJdbc timeout = new SnapshotJdbc("Sales").failure(
                "columns", new SQLTimeoutException("driver timeout secret", "HYT00", 51));
        SQLTimeoutException timeoutFailure = assertThrows(SQLTimeoutException.class,
                () -> read(timeout, "connection-secret"));
        assertEquals("Snapshot metadata timed out", timeoutFailure.getMessage());
        assertEquals("HYT00", timeoutFailure.getSQLState());
        assertEquals(51, timeoutFailure.getErrorCode());
        assertNull(timeoutFailure.getCause());
    }

    @Test
    void fingerprintIgnoresCaptureAndConnectionIdentityButTracksStructure() throws Exception {
        SnapshotJdbc first = tableFixture("customer-visible");
        SnapshotJdbc second = tableFixture("customer-visible");
        SnapshotJdbc changed = tableFixture("changed-comment");

        SchemaSnapshot firstSnapshot = read(first, "connection-a");
        Thread.sleep(2);
        SchemaSnapshot secondSnapshot = read(second, "connection-b");
        SchemaSnapshot changedSnapshot = read(changed, "connection-a");

        assertEquals(firstSnapshot.fingerprint(), secondSnapshot.fingerprint());
        assertNotEquals(firstSnapshot.capturedAt(), secondSnapshot.capturedAt());
        assertNotEquals(firstSnapshot.fingerprint(), changedSnapshot.fingerprint());
    }

    private static SnapshotJdbc tableFixture(String comment) {
        return new SnapshotJdbc("Sales")
                .rows("tables", row("table_name", "ORDERS"))
                .rows("columns", row("table_name", "ORDERS", "column_name", "ID",
                        "column_id", 1, "data_type", "NUMBER", "data_length", null,
                        "char_length", null, "char_used", null, "data_precision", 10,
                        "data_scale", 0, "data_type_owner", null, "data_type_mod", null,
                        "nullable", "N", "identity_column", "NO", "generation_type", null,
                        "default_on_null", "NO", "virtual_column", "NO",
                        "hidden_column", "NO", "user_generated", "YES",
                        "comments", comment, "identity_options", null, "data_default", "0"));
    }

    private static Map<String, Object> identityColumn(String options) {
        return row("table_name", "ORDERS", "column_name", "ID", "column_id", 1,
                "data_type", "NUMBER", "data_length", null, "char_length", null,
                "char_used", null, "data_precision", 18, "data_scale", 0,
                "data_type_owner", null, "data_type_mod", null, "nullable", "N",
                "identity_column", "YES", "generation_type", "BY DEFAULT",
                "default_on_null", "NO", "virtual_column", "NO",
                "hidden_column", "NO", "user_generated", "YES", "comments", null,
                "identity_options", options, "data_default", "ISEQ$$_42.NEXTVAL");
    }

    private static Map<String, Object> tableRow(
            String name, String nested, String secondary, String iotType) {
        return row("table_name", name, "nested", nested,
                "secondary", secondary, "iot_type", iotType);
    }

    private static SchemaSnapshot read(SnapshotJdbc jdbc, String connectionId) throws SQLException {
        return new OracleSchemaSnapshotReader(jdbc.connection()).read(connectionId,
                OracleSchemaIdentifierNormalizer.schema("Sales"),
                new SqlExecutionOptions(0, 7, new SqlExecutionControl()));
    }

    private static Map<String, Object> column(
            String table, String name, int ordinal, String type,
            Integer precision, Integer scale, Long length, String charUsed,
            String typeOwner, String typeMod, String virtual, String defaultOnNull,
            String identity, String generationType, String defaultExpression) {
        return row("table_name", table, "column_name", name, "column_id", ordinal,
                "data_type", type, "data_length", length, "char_length", length,
                "char_used", charUsed, "data_precision", precision, "data_scale", scale,
                "data_type_owner", typeOwner, "data_type_mod", typeMod, "nullable", "Y",
                "identity_column", identity, "generation_type", generationType,
                "default_on_null", defaultOnNull, "virtual_column", virtual,
                "hidden_column", "NO", "user_generated", "YES",
                "comments", name.equals("CODE") ? "customer-visible" : null,
                "identity_options", null,
                "data_default", defaultExpression);
    }

    private static Map<String, Object> definitionRow(
            String name, String type, int objectId, int subprogramId, String baseObjectName) {
        return definitionRow(name, type, objectId, subprogramId,
                baseObjectName == null ? null : "Sales",
                baseObjectName == null ? null : "TABLE", baseObjectName);
    }

    private static Map<String, Object> definitionRow(
            String name, String type, int objectId, int subprogramId,
            String baseOwner, String baseType, String baseObjectName) {
        return row("object_name", name, "object_type", type, "object_id", objectId,
                "subprogram_id", subprogramId, "base_object_owner", baseOwner,
                "base_object_type", baseType, "base_object_name", baseObjectName);
    }

    private static Map<String, Object> argument(
            String name, int objectId, int subprogramId, int position, int dataLevel,
            String mode, String dataType, String typeOwner, Integer precision,
            Integer scale, Integer length, String argumentName, String defaultValue) {
        return row("object_name", name, "object_id", objectId, "subprogram_id", subprogramId,
                "position", position, "sequence", position, "data_level", dataLevel,
                "in_out", mode, "data_type", dataType, "data_length", length,
                "data_precision", precision, "data_scale", scale, "type_owner", typeOwner,
                "type_name", typeOwner == null ? null : dataType, "type_subname", null,
                "pls_type", dataType, "argument_name", argumentName,
                "defaulted", defaultValue == null ? "N" : "Y", "default_value", defaultValue);
    }

    private static Map<String, Object> dependency(
            String sourceName, String sourceType, String targetOwner,
            String targetName, String targetType) {
        return row("source_name", sourceName, "source_type", sourceType,
                "referenced_owner", targetOwner, "referenced_name", targetName,
                "referenced_type", targetType);
    }

    private static DefinitionObject definition(
            SchemaSnapshot snapshot, ObjectType type, String owner, String name, String signature) {
        return assertInstanceOf(DefinitionObject.class,
                snapshot.objects().get(key(type, owner, name, signature)));
    }

    private static ObjectKey key(ObjectType type, String owner, String name, String signature) {
        return new ObjectKey(type, OracleSchemaIdentifierNormalizer.object(owner, name), signature);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    static final class SnapshotJdbc {
        private final String expectedOwner;
        private final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        private final Map<DdlKey, String> ddls = new LinkedHashMap<>();
        private final Map<String, SQLException> failures = new LinkedHashMap<>();
        private final Map<String, RowFailure> rowFailures = new LinkedHashMap<>();
        private final List<StatementTrace> statements = new ArrayList<>();
        private boolean connectionSetSchemaCalled;

        SnapshotJdbc(String expectedOwner) {
            this.expectedOwner = expectedOwner;
        }

        @SafeVarargs
        final SnapshotJdbc rows(String tag, Map<String, Object>... queryRows) {
            rows.put(tag, List.of(queryRows));
            return this;
        }

        SnapshotJdbc ddl(String objectType, String objectName, String ddl) {
            ddls.put(new DdlKey(objectType, objectName), ddl);
            return this;
        }

        SnapshotJdbc failure(String tag, SQLException failure) {
            failures.put(tag, failure);
            return this;
        }

        SnapshotJdbc failAfterRows(String tag, int count, SQLException failure) {
            rowFailures.put(tag, new RowFailure(count, failure));
            return this;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> preparedStatement((String) args[0]);
                        case "setSchema" -> {
                            connectionSetSchemaCalled = true;
                            throw new AssertionError("snapshot reader must not mutate Connection schema");
                        }
                        case "getAutoCommit" -> true;
                        case "isClosed" -> false;
                        case "toString" -> "oracle-snapshot-jdbc-proxy";
                        default -> defaultValue(method.getReturnType());
                    });
        }

        boolean connectionSetSchemaCalled() {
            return connectionSetSchemaCalled;
        }

        StatementTrace statement(String tag) {
            return statements.stream().filter(statement -> statement.tag.equals(tag))
                    .findFirst().orElseThrow();
        }

        List<StatementTrace> statements() {
            return List.copyOf(statements);
        }

        private PreparedStatement preparedStatement(String sql) {
            assertFalse(sql.contains(expectedOwner), "owner must never be concatenated into SQL");
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
                            trace.executed = true;
                            SQLException failure = failures.get(trace.tag);
                            if (failure != null) throw failure;
                            List<Map<String, Object>> queryRows;
                            if (trace.tag.equals("ddl")) {
                                String ddl = ddls.get(new DdlKey(
                                        (String) trace.bindings.get(1), (String) trace.bindings.get(2)));
                                queryRows = ddl == null ? List.of() : List.of(row("ddl", ddl));
                            } else {
                                queryRows = rows.getOrDefault(trace.tag, List.of());
                            }
                            if (trace.tag.equals("tables")) {
                                queryRows = filterImplementationTables(queryRows, trace.sql);
                            }
                            yield resultSet(queryRows, trace, rowFailures.get(trace.tag));
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
                        case "toString" -> "oracle-prepared-" + trace.tag;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet resultSet(
                List<Map<String, Object>> queryRows, StatementTrace trace, RowFailure rowFailure) {
            int[] cursor = {-1};
            AtomicBoolean wasNull = new AtomicBoolean();
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> {
                            trace.nextCalls++;
                            if (rowFailure != null && cursor[0] + 1 >= rowFailure.afterRows()) {
                                throw rowFailure.failure();
                            }
                            yield ++cursor[0] < queryRows.size();
                        }
                        case "getString" -> string(value(queryRows, cursor[0], args[0], wasNull, trace));
                        case "getLong" -> number(value(queryRows, cursor[0], args[0], wasNull, trace)).longValue();
                        case "getInt" -> number(value(queryRows, cursor[0], args[0], wasNull, trace)).intValue();
                        case "getBoolean" -> bool(value(queryRows, cursor[0], args[0], wasNull, trace));
                        case "getObject" -> value(queryRows, cursor[0], args[0], wasNull, trace);
                        case "wasNull" -> wasNull.get();
                        case "close" -> {
                            trace.resultSetClosed = true;
                            yield null;
                        }
                        case "isClosed" -> trace.resultSetClosed;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static Object value(List<Map<String, Object>> queryRows, int cursor,
                                    Object label, AtomicBoolean wasNull, StatementTrace trace) {
            Map<String, Object> row = queryRows.get(cursor);
            Object value = label instanceof Integer position
                    ? row.values().stream().skip(position - 1L).findFirst().orElse(null)
                    : row.get((String) label);
            trace.lastReadLabel = label.toString();
            trace.readLabels.add(label.toString());
            wasNull.set(value == null);
            return value;
        }

        private static List<Map<String, Object>> filterImplementationTables(
                List<Map<String, Object>> values, String sql) {
            return values.stream()
                    .filter(row -> !sql.contains("t.NESTED = 'NO'")
                            || !row.containsKey("nested") || "NO".equals(row.get("nested")))
                    .filter(row -> !sql.contains("t.SECONDARY = 'N'")
                            || !row.containsKey("secondary") || "N".equals(row.get("secondary")))
                    .filter(row -> !sql.contains("t.IOT_TYPE IS NULL OR t.IOT_TYPE = 'IOT'")
                            || !row.containsKey("iot_type") || row.get("iot_type") == null
                            || "IOT".equals(row.get("iot_type")))
                    .toList();
        }

        private static String tag(String sql) {
            int start = sql.indexOf("snapshot:");
            int end = sql.indexOf("*/", start);
            assertTrue(start >= 0 && end > start, "snapshot SQL requires a fixed tag");
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
            private boolean resultSetClosed;
            private String lastReadLabel;
            private final List<String> readLabels = new ArrayList<>();
            private int nextCalls;

            private StatementTrace(String tag, String sql) {
                this.tag = tag;
                this.sql = sql;
            }

            String lastReadLabel() {
                return lastReadLabel;
            }

            String sql() {
                return sql;
            }

            List<String> readLabels() {
                return List.copyOf(readLabels);
            }

            String tag() {
                return tag;
            }

            int timeout() {
                return timeout;
            }

            Map<Integer, Object> bindings() {
                return Map.copyOf(bindings);
            }

            int nextCalls() {
                return nextCalls;
            }
        }

        private record DdlKey(String objectType, String objectName) {
        }

        private record RowFailure(int afterRows, SQLException failure) {
        }
    }
}
