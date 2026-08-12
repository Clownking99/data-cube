package com.datacube.schemadiff;

import com.datacube.provider.oracle.OracleSchemaDiffCapability;
import com.datacube.provider.oracle.OracleSchemaIdentifierNormalizer;
import com.datacube.provider.postgres.PgSchemaDiffCapability;
import com.datacube.provider.postgres.PgSchemaIdentifierNormalizer;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.TableDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaCrossOwnerComparisonTest {

    @Test
    void postgresDifferentOwnersComparePlanRenderAndConvergeWithoutOwnerLeakage() {
        assertCrossOwnerClosure(DbType.POSTGRESQL, new PgSchemaDiffCapability(),
                PgSchemaIdentifierNormalizer::schema, PgSchemaIdentifierNormalizer::object);
    }

    @Test
    void oracleDifferentOwnersComparePlanRenderAndConvergeWithoutOwnerLeakage() {
        assertCrossOwnerClosure(DbType.ORACLE, new OracleSchemaDiffCapability(),
                OracleSchemaIdentifierNormalizer::schema, OracleSchemaIdentifierNormalizer::object);
    }

    @Test
    void oraclePlSqlSourceOwnerBindingRendersAndConvergesWithEmbeddedQuoteOwners() {
        String sourceOwner = "Source\"Owner";
        String targetOwner = "Target\"Owner";
        ObjectKey sourceKey = key(ObjectType.FUNCTION, sourceOwner, "Scoped", oracleSignature(),
                OracleSchemaIdentifierNormalizer::object);
        ObjectKey targetKey = key(ObjectType.FUNCTION, targetOwner, "Scoped", oracleSignature(),
                OracleSchemaIdentifierNormalizer::object);
        String sourceDefinition = oracleScopedRoutine(sourceOwner, sourceOwner);
        String targetDefinition = oracleScopedRoutine(targetOwner, sourceOwner);
        DefinitionObject sourceRoutine = new DefinitionObject(sourceKey, sourceDefinition,
                sourceDefinition, Set.of(), DefinitionConfidence.HIGH);
        DefinitionObject targetRoutine = new DefinitionObject(targetKey, targetDefinition,
                targetDefinition, Set.of(), DefinitionConfidence.HIGH);
        SchemaSnapshot source = snapshot(DbType.ORACLE, "source", sourceOwner,
                OracleSchemaIdentifierNormalizer::schema, sourceRoutine);
        SchemaSnapshot emptyTarget = snapshot(DbType.ORACLE, "empty", targetOwner,
                OracleSchemaIdentifierNormalizer::schema);
        OracleSchemaDiffCapability capability = new OracleSchemaDiffCapability();
        DefinitionObject projectedSource = (DefinitionObject) capability.comparisonProjector()
                .project(source).comparisonObjects().values().iterator().next();
        DefinitionObject projectedTarget = (DefinitionObject) capability.comparisonProjector()
                .project(snapshot(DbType.ORACLE, "projected-target", targetOwner,
                        OracleSchemaIdentifierNormalizer::schema, targetRoutine))
                .comparisonObjects().values().iterator().next();
        assertEquals(projectedSource.normalizedDefinition(),
                projectedTarget.normalizedDefinition());
        SchemaChangePlan plan = new SchemaChangePlanner().plan(new SchemaDiffEngine().compare(
                source, emptyTarget, capability.comparisonProjector()));

        String rendered = capability.changeRenderer().render(plan.changes().getFirst(),
                new RenderContext(DbType.ORACLE, source.schema(), emptyTarget.schema(), false))
                .getFirst().sql();
        assertTrue(rendered.contains(quote(DbType.ORACLE, sourceOwner)
                + ".local_record.field"), rendered);
        assertTrue(rendered.contains(quote(DbType.ORACLE, sourceOwner)
                + ".label_record.field"), rendered);
        assertTrue(rendered.contains(qualified(DbType.ORACLE, targetOwner, "PKG") + ".RUN()"),
                rendered);
        assertTrue(new SchemaDiffEngine().compare(source,
                        snapshot(DbType.ORACLE, "after", targetOwner,
                                OracleSchemaIdentifierNormalizer::schema, targetRoutine),
                        capability.comparisonProjector()).differences().stream()
                .allMatch(difference -> difference.kind() == DifferenceKind.EQUIVALENT));
    }

    @Test
    void crossOwnerTargetDependencyReleaseOrdersReplacementBeforeDrop() {
        assertCrossOwnerDependencyRelease(DbType.POSTGRESQL, new PgSchemaDiffCapability(),
                PgSchemaIdentifierNormalizer::schema, PgSchemaIdentifierNormalizer::object);
        assertCrossOwnerDependencyRelease(DbType.ORACLE, new OracleSchemaDiffCapability(),
                OracleSchemaIdentifierNormalizer::schema, OracleSchemaIdentifierNormalizer::object);
    }

    @Test
    void postgresUnknownRoutineLanguagesRemainLocalManualDifferencesWithoutFalseEquivalence() {
        PgSchemaDiffCapability capability = new PgSchemaDiffCapability();
        for (String[] definitions : List.of(
                new String[]{unqualifiedUnknownRoutine("SELECT 1"),
                        unqualifiedUnknownRoutine("SELECT 1")},
                new String[]{unknownRoutine("Source", "SELECT * FROM \"Source\".\"Orders\""),
                        unknownRoutine("Target", "SELECT * FROM \"Target\".\"Orders\"")},
                new String[]{unknownRoutine("Source", "SELECT 1"),
                        unknownRoutine("Target", "SELECT 2")})) {
            DefinitionObject sourceRoutine = routine("Source", definitions[0]);
            DefinitionObject targetRoutine = routine("Target", definitions[1]);
            SequenceDefinition sourceSequence = sequence("Source");
            SequenceDefinition targetSequence = sequence("Target");

            SchemaDiffResult result = new SchemaDiffEngine().compare(
                    snapshot(DbType.POSTGRESQL, "source", "Source",
                            PgSchemaIdentifierNormalizer::schema, sourceRoutine, sourceSequence),
                    snapshot(DbType.POSTGRESQL, "target", "Target",
                            PgSchemaIdentifierNormalizer::schema, targetRoutine, targetSequence),
                    capability.comparisonProjector());

            SchemaDifference routineDifference = result.differences().stream()
                    .filter(difference -> difference.object().type() == ObjectType.FUNCTION)
                    .findFirst().orElseThrow();
            assertEquals(DifferenceKind.MODIFIED, routineDifference.kind());
            assertEquals(com.datacube.spi.schemadiff.AutomationLevel.MANUAL_ONLY,
                    routineDifference.automation());
            assertEquals(DefinitionConfidence.HIGH,
                    ((DefinitionObject) routineDifference.source()).confidence());
            assertSame(sourceRoutine, routineDifference.source());
            assertSame(targetRoutine, routineDifference.target());
            assertFalse(routineDifference.properties().isEmpty());
            assertTrue(routineDifference.properties().stream().allMatch(property ->
                    property.sourceValue() == null
                            || property.sourceValue().toString().startsWith("sha256:")));
            assertEquals(DifferenceKind.EQUIVALENT, result.differences().stream()
                    .filter(difference -> difference.object().type() == ObjectType.SEQUENCE)
                    .findFirst().orElseThrow().kind());
            SchemaChangePlan plan = new SchemaChangePlanner().plan(result);
            assertEquals(com.datacube.spi.schemadiff.ChangeKind.MANUAL,
                    plan.changes().stream()
                            .filter(change -> change.object().type() == ObjectType.FUNCTION)
                            .findFirst().orElseThrow().kind());
        }
    }

    @Test
    void oracleJavaAndCCallSpecsRemainObjectSpecificManualDifferences() {
        OracleSchemaDiffCapability capability = new OracleSchemaDiffCapability();
        for (Object[] definitionCase : List.of(
                new Object[]{ObjectType.FUNCTION, "JAVA_FN",
                        "CREATE FUNCTION \"Source\".\"JAVA_FN\" RETURN NUMBER AS LANGUAGE JAVA "
                                + "NAME 'example.Owner.call() return int';",
                        "CREATE FUNCTION \"Target\".\"JAVA_FN\" RETURN NUMBER AS LANGUAGE JAVA "
                                + "NAME 'example.Owner.call() return int';"},
                new Object[]{ObjectType.PROCEDURE, "C_PROC",
                        "CREATE PROCEDURE \"Source\".\"C_PROC\" AS LANGUAGE C LIBRARY "
                                + "\"Source\".\"NATIVE_LIB\" NAME \"native_call\";",
                        "CREATE PROCEDURE \"Target\".\"C_PROC\" AS LANGUAGE C LIBRARY "
                                + "\"Target\".\"NATIVE_LIB\" NAME \"native_call\";"})) {
            ObjectType type = (ObjectType) definitionCase[0];
            String name = (String) definitionCase[1];
            ObjectKey sourceKey = key(type, "Source", name, oracleSignature(),
                    OracleSchemaIdentifierNormalizer::object);
            ObjectKey targetKey = key(type, "Target", name, oracleSignature(),
                    OracleSchemaIdentifierNormalizer::object);
            DefinitionObject sourceRoutine = new DefinitionObject(sourceKey,
                    (String) definitionCase[2], (String) definitionCase[2], Set.of(),
                    DefinitionConfidence.LOW);
            DefinitionObject targetRoutine = new DefinitionObject(targetKey,
                    (String) definitionCase[3], (String) definitionCase[3], Set.of(),
                    DefinitionConfidence.LOW);

            SchemaDiffResult result = new SchemaDiffEngine().compare(
                    snapshot(DbType.ORACLE, "source", "Source",
                            OracleSchemaIdentifierNormalizer::schema, sourceRoutine),
                    snapshot(DbType.ORACLE, "target", "Target",
                            OracleSchemaIdentifierNormalizer::schema, targetRoutine),
                    capability.comparisonProjector());
            SchemaDifference difference = result.differences().getFirst();
            SchemaChangePlan plan = new SchemaChangePlanner().plan(result);

            assertEquals(DifferenceKind.MODIFIED, difference.kind());
            assertEquals(com.datacube.spi.schemadiff.AutomationLevel.MANUAL_ONLY,
                    difference.automation());
            assertSame(sourceRoutine, difference.source());
            assertSame(targetRoutine, difference.target());
            assertEquals(com.datacube.spi.schemadiff.ChangeKind.MANUAL,
                    plan.changes().getFirst().kind());
            assertTrue(plan.selectedChangeIds().isEmpty());
            assertFalse((difference + plan.toString() + plan.digest())
                    .contains("oracle-manual-definition"));
        }
    }

    @Test
    void malformedProviderIdentityFailsClosedWithFixedDiagnostics() {
        QualifiedName pgSchema = PgSchemaIdentifierNormalizer.schema("owner");
        ObjectKey malformedPgKey = new ObjectKey(ObjectType.TABLE,
                new QualifiedName("\"orders\"", "pg-object-v1\0owner", true), "");
        TableDefinition malformedPg = new TableDefinition(malformedPgKey,
                List.of(new ColumnDefinition(PgSchemaIdentifierNormalizer.child("id"),
                        scalarType(), false, null, 1, null)), List.of(), List.of(), Set.of());
        IllegalArgumentException pgFailure = assertThrows(IllegalArgumentException.class,
                () -> new PgSchemaDiffCapability().comparisonProjector().project(
                        snapshot(DbType.POSTGRESQL, "bad-pg", pgSchema,
                                malformedPg)));
        assertEquals("PostgreSQL schema comparison projection is invalid", pgFailure.getMessage());

        QualifiedName oracleSchema = OracleSchemaIdentifierNormalizer.schema("OWNER");
        ObjectKey malformedOracleKey = new ObjectKey(ObjectType.TABLE,
                new QualifiedName("\"ORDERS\"", "oracle-object-v1\0002:OWNER", true), "");
        TableDefinition malformedOracle = new TableDefinition(malformedOracleKey,
                List.of(new ColumnDefinition(OracleSchemaIdentifierNormalizer.child("ID"),
                        scalarType(), false, null, 1, null)), List.of(), List.of(), Set.of());
        IllegalArgumentException oracleFailure = assertThrows(IllegalArgumentException.class,
                () -> new OracleSchemaDiffCapability().comparisonProjector().project(
                        snapshot(DbType.ORACLE, "bad-oracle", oracleSchema,
                                malformedOracle)));
        assertEquals("Oracle schema comparison projection is invalid", oracleFailure.getMessage());
    }

    private static void assertCrossOwnerDependencyRelease(
            DbType type, SchemaDiffCapability capability,
            SchemaName schemaName, ObjectName objectName) {
        String sourceOwner = "Source\"Release";
        String targetOwner = "Target\"Release";
        ObjectKey sourceViewKey = key(ObjectType.VIEW, sourceOwner, "Active\"View", "", objectName);
        ObjectKey targetViewKey = key(ObjectType.VIEW, targetOwner, "Active\"View", "", objectName);
        ObjectKey targetTypeKey = key(ObjectType.TYPE, targetOwner, "Legacy\"Type",
                type == DbType.ORACLE ? "SPEC" : "domain", objectName);
        String sourceViewDdl = "CREATE VIEW " + quote(type, sourceOwner) + ".\"Active\"\"View\" AS SELECT 1";
        String targetViewDdl = "CREATE VIEW " + quote(type, targetOwner)
                + ".\"Active\"\"View\" AS SELECT legacy_value FROM "
                + quote(type, targetOwner) + ".\"Legacy\"\"Type\"";
        DefinitionObject sourceView = new DefinitionObject(sourceViewKey,
                sourceViewDdl, sourceViewDdl, Set.of(), DefinitionConfidence.HIGH);
        DefinitionObject targetView = new DefinitionObject(targetViewKey,
                targetViewDdl, targetViewDdl, Set.of(targetTypeKey), DefinitionConfidence.HIGH);
        String targetTypeDdl = type == DbType.ORACLE
                ? "CREATE TYPE " + quote(type, targetOwner) + ".\"Legacy\"\"Type\" AS OBJECT (id NUMBER)"
                : "CREATE DOMAIN " + quote(type, targetOwner) + ".\"Legacy\"\"Type\" AS bigint";
        DefinitionObject targetType = new DefinitionObject(targetTypeKey,
                targetTypeDdl, targetTypeDdl, Set.of(), DefinitionConfidence.HIGH);
        SchemaSnapshot source = snapshot(type, "release-source", sourceOwner, schemaName,
                sourceView);
        SchemaSnapshot target = snapshot(type, "release-target", targetOwner, schemaName,
                targetView, targetType);

        SchemaChangePlan plan = new SchemaChangePlanner().plan(new SchemaDiffEngine().compare(
                source, target, capability.comparisonProjector()));

        SchemaChange replace = plan.changes().stream()
                .filter(change -> change.object().type() == ObjectType.VIEW).findFirst().orElseThrow();
        SchemaChange drop = plan.changes().stream()
                .filter(change -> change.object().type() == ObjectType.TYPE).findFirst().orElseThrow();
        assertEquals(com.datacube.spi.schemadiff.ChangeKind.REPLACE, replace.kind());
        assertEquals(com.datacube.spi.schemadiff.ChangeKind.DROP, drop.kind());
        assertEquals(Set.of(replace.id()), drop.dependencyChangeIds());
        assertTrue(plan.changes().indexOf(replace) < plan.changes().indexOf(drop));
    }

    private static void assertCrossOwnerClosure(
            DbType type, SchemaDiffCapability capability,
            SchemaName schemaName, ObjectName objectName) {
        String sourceOwner = "Source\"Owner";
        String targetOwner = "Target\"Owner";
        String externalOwner = "External.Owner";
        SchemaSnapshot source = snapshot(type, "source", sourceOwner, true,
                schemaName, objectName, externalOwner);
        SchemaSnapshot targetBefore = snapshot(type, "target", targetOwner, false,
                schemaName, objectName, externalOwner);

        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                source, targetBefore, capability.comparisonProjector());

        assertEquals(2, diff.differences().stream()
                .filter(difference -> difference.kind() != DifferenceKind.EQUIVALENT).count());
        assertTrue(diff.renameSuggestions().isEmpty());
        SchemaDifference tableDifference = diff.differences().stream()
                .filter(difference -> difference.kind() == DifferenceKind.MODIFIED)
                .findFirst().orElseThrow();
        assertSame(source.objects().get(tableDifference.source().key()), tableDifference.source());
        assertSame(targetBefore.objects().get(tableDifference.target().key()), tableDifference.target());
        assertTrue(tableDifference.dependencies().stream()
                .anyMatch(key -> key.name().original().contains(externalOwner)));

        SchemaChangePlan plan = new SchemaChangePlanner().plan(diff);
        assertEquals(2, plan.changes().size());
        RenderContext context = new RenderContext(type,
                schemaName.normalize(sourceOwner), schemaName.normalize(targetOwner), true);
        List<RenderedStatement> statements = plan.changes().stream()
                .flatMap(change -> capability.changeRenderer().render(change, context).stream())
                .toList();
        assertFalse(statements.isEmpty());
        assertTrue(statements.stream().allMatch(
                statement -> statement.sql().contains(quote(type, targetOwner))));
        assertTrue(statements.stream().noneMatch(statement -> statement.sql().contains("\0")
                || statement.sql().contains("comparison-object")
                || statement.sql().contains("self-owner")));
        assertTrue(statements.stream().anyMatch(statement ->
                statement.sql().contains(quote(type, sourceOwner) + ".\"Id\"")));
        assertTrue(statements.stream().anyMatch(statement ->
                statement.sql().contains(quote(type, targetOwner) + ".\"Id\"")));
        if (type == DbType.POSTGRESQL) {
            assertTrue(statements.stream().anyMatch(statement -> statement.sql().equals(
                    "COMMENT ON COLUMN \"Target\"\"Owner\".\"Order\"\"Line\".\"Note\" "
                            + "IS 'owner-safe comment';")));
        }

        SchemaSnapshot targetAfter = snapshot(type, "target-after", targetOwner, true,
                schemaName, objectName, externalOwner);
        SchemaDiffResult converged = new SchemaDiffEngine().compare(
                source, targetAfter, capability.comparisonProjector());
        assertTrue(converged.differences().stream()
                .allMatch(difference -> difference.kind() == DifferenceKind.EQUIVALENT));
        assertTrue(converged.renameSuggestions().isEmpty());
        assertTrue(new SchemaChangePlanner().plan(converged).changes().isEmpty());
    }

    private static SchemaSnapshot snapshot(
            DbType type, String connectionId, String owner, boolean includeAddedColumn,
            SchemaName schemaName, ObjectName objectName, String externalOwner) {
        ObjectKey tableKey = key(ObjectType.TABLE, owner, "Order\"Line", "", objectName);
        ObjectKey externalType = key(ObjectType.TYPE, externalOwner, "Shared.Type", "domain", objectName);
        ObjectKey constraintKey = key(ObjectType.PRIMARY_KEY, owner, "PK\"Orders",
                tableKey.name().comparisonKey(), objectName);
        ObjectKey indexKey = key(ObjectType.INDEX, owner, "IX\"Orders", "", objectName);
        ConstraintDefinition primaryKey = new ConstraintDefinition(
                constraintKey, ConstraintKind.PRIMARY_KEY, List.of(child(type, "Id")),
                tableKey, List.of(child(type, "Id")), null, null, null,
                false, Set.of(tableKey));
        ObjectKey checkKey = key(ObjectType.CHECK_CONSTRAINT, owner, "CK\"Orders",
                tableKey.name().comparisonKey(), objectName);
        ConstraintDefinition check = new ConstraintDefinition(
                checkKey, ConstraintKind.CHECK, List.of(), null, List.of(),
                "CHECK (" + callable(type, owner, "is_valid") + "(\"Id\"))",
                null, null, false, Set.of(tableKey));
        IndexDefinition index = new IndexDefinition(indexKey, false,
                List.of(callable(type, owner, "normalize") + "(\"Id\")"),
                callable(type, owner, "is_visible") + "(\"Id\")",
                false, Set.of(tableKey));
        List<ColumnDefinition> columns = new java.util.ArrayList<>();
        columns.add(new ColumnDefinition(child(type, "Id"), scalarType(), false,
                callable(type, owner, "default_value") + "()", 1, null));
        if (includeAddedColumn) {
            columns.add(new ColumnDefinition(child(type, "Note"), selfType(type, owner), true,
                    null, 2, "owner-safe comment"));
        }
        TableDefinition table = new TableDefinition(tableKey, columns,
                List.of(primaryKey, check), List.of(index), Set.of(externalType));

        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, owner, "Seq\"Orders", "", objectName);
        SequenceDefinition sequence = type == DbType.ORACLE
                ? new SequenceDefinition(sequenceKey, "1", "1", "1", "999", false, 20,
                        Set.of(), Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"))
                : new SequenceDefinition(sequenceKey, "1", "1", "1", "999", false, 20, Set.of());

        ObjectKey viewKey = key(ObjectType.VIEW, owner, "View\"Orders", "", objectName);
        String sourceAlias = quote(type, "Source\"Owner");
        String targetAlias = quote(type, "Target\"Owner");
        String selectedColumn = includeAddedColumn ? "Note" : "Id";
        String viewDefinition = "CREATE OR REPLACE VIEW " + qualified(type, owner, "View\"Orders")
                + " AS SELECT " + sourceAlias + ".\"" + selectedColumn + "\", "
                + targetAlias + ".\"Id\" FROM " + qualified(type, owner, "Order\"Line")
                + " AS " + sourceAlias + " JOIN " + qualified(type, owner, "Order\"Line")
                + " AS " + targetAlias + " ON " + sourceAlias + ".\"Id\" = "
                + targetAlias + ".\"Id\" JOIN " + qualified(type, externalOwner, "Audit.Table")
                + " ON 1 = 1" + (type == DbType.ORACLE ? ";" : "");
        DefinitionObject view = new DefinitionObject(viewKey, viewDefinition, viewDefinition,
                Set.of(tableKey, externalType), DefinitionConfidence.HIGH);

        String routineSignature = type == DbType.POSTGRESQL
                ? qualified(type, owner, "Self.Type") + "[], "
                        + qualified(type, externalOwner, "External.Type")
                : oracleSignature("IN", owner + ".Self.Type", "IN", externalOwner + ".External.Type");
        ObjectKey routineKey = key(ObjectType.FUNCTION, owner, "Fn\"Orders", routineSignature, objectName);
        String routineDefinition = type == DbType.POSTGRESQL
                ? "CREATE FUNCTION " + qualified(type, owner, "Fn\"Orders") + "(value "
                        + qualified(type, owner, "Self.Type") + ") RETURNS integer LANGUAGE sql AS 'SELECT 1'"
                : "CREATE FUNCTION " + qualified(type, owner, "Fn\"Orders") + "(value IN "
                        + qualified(type, owner, "Self.Type") + ") RETURN NUMBER AS BEGIN RETURN 1; END;";
        DefinitionObject routine = new DefinitionObject(routineKey,
                routineDefinition, routineDefinition, Set.of(tableKey, externalType),
                DefinitionConfidence.HIGH);

        SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>();
        for (SchemaObject object : List.of(table, sequence, view, routine)) {
            objects.put(object.key(), object);
        }
        return new SchemaSnapshot(type, connectionId, schemaName.normalize(owner), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), objects,
                connectionId + "-fingerprint");
    }

    private static SchemaSnapshot snapshot(
            DbType type, String connectionId, String owner,
            SchemaName schemaName, SchemaObject... objects) {
        SortedMap<ObjectKey, SchemaObject> values = new TreeMap<>();
        for (SchemaObject object : objects) values.put(object.key(), object);
        return new SchemaSnapshot(type, connectionId, schemaName.normalize(owner), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), values, connectionId + "-fingerprint");
    }

    private static SchemaSnapshot snapshot(
            DbType type, String connectionId, QualifiedName schema,
            SchemaObject object) {
        SortedMap<ObjectKey, SchemaObject> values = new TreeMap<>();
        values.put(object.key(), object);
        return new SchemaSnapshot(type, connectionId, schema, Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), values,
                connectionId + "-fingerprint");
    }

    private static CanonicalDataType selfType(DbType type, String owner) {
        SortedMap<String, String> extensions = new TreeMap<>();
        if (type == DbType.POSTGRESQL) {
            extensions.put("formattedType", qualified(type, owner, "Self.Type"));
            extensions.put("typeSchema", owner);
            return new CanonicalDataType("Self.Type", null, null, null, false, 0, extensions);
        }
        extensions.put("formattedType", qualified(type, owner, "Self.Type"));
        extensions.put("oracle.typeOwner", owner);
        return new CanonicalDataType(owner + ".Self.Type", null, null, null,
                false, 0, extensions);
    }

    private static CanonicalDataType scalarType() {
        return new CanonicalDataType("integer", null, null, null,
                false, 0, new TreeMap<>());
    }

    private static String unknownRoutine(String owner, String body) {
        return "CREATE FUNCTION \"" + owner + "\".\"opaque\"() RETURNS integer "
                + "LANGUAGE python AS $body$ " + body + " $body$";
    }

    private static String unqualifiedUnknownRoutine(String body) {
        return "CREATE FUNCTION opaque() RETURNS integer LANGUAGE python AS $body$ "
                + body + " $body$";
    }

    private static DefinitionObject routine(String owner, String definition) {
        ObjectKey key = new ObjectKey(ObjectType.FUNCTION,
                PgSchemaIdentifierNormalizer.object(owner, "opaque"), "");
        return new DefinitionObject(key, definition, definition, Set.of(), DefinitionConfidence.HIGH);
    }

    private static SequenceDefinition sequence(String owner) {
        return new SequenceDefinition(new ObjectKey(ObjectType.SEQUENCE,
                PgSchemaIdentifierNormalizer.object(owner, "stable"), ""),
                "1", "1", "1", "9", false, 1, Set.of());
    }

    private static QualifiedName child(DbType type, String name) {
        return type == DbType.POSTGRESQL
                ? PgSchemaIdentifierNormalizer.child(name)
                : OracleSchemaIdentifierNormalizer.child(name);
    }

    private static ObjectKey key(
            ObjectType type, String owner, String name, String signature, ObjectName objectName) {
        return new ObjectKey(type, objectName.normalize(owner, name), signature);
    }

    private static String qualified(DbType type, String owner, String name) {
        return quote(type, owner) + "." + quote(type, name);
    }

    private static String callable(DbType type, String owner, String routine) {
        return type == DbType.ORACLE
                ? qualified(type, owner, "PKG") + "." + quote(type, routine)
                : qualified(type, owner, routine);
    }

    private static String oracleScopedRoutine(String owner, String stableBinding) {
        return "CREATE FUNCTION " + qualified(DbType.ORACLE, owner, "Scoped")
                + " RETURN NUMBER AS FUNCTION local_fn("
                + quote(DbType.ORACLE, stableBinding) + " IN "
                + qualified(DbType.ORACLE, owner, "Self.Type")
                + ") RETURN NUMBER IS local_record "
                + qualified(DbType.ORACLE, owner, "Self.Type") + "; BEGIN "
                + quote(DbType.ORACLE, stableBinding) + ".local_record.field := 1; RETURN 1; "
                + "END local_fn; BEGIN <<" + quote(DbType.ORACLE, stableBinding)
                + ">> DECLARE label_record "
                + qualified(DbType.ORACLE, owner, "Self.Type") + "; BEGIN "
                + quote(DbType.ORACLE, stableBinding) + ".label_record.field := 2; END; "
                + qualified(DbType.ORACLE, owner, "PKG")
                + ".RUN(); RETURN local_fn(NULL); END;";
    }

    private static String quote(DbType type, String name) {
        String escaped = name.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static String oracleSignature(String... fields) {
        StringBuilder value = new StringBuilder("oracle-routine-signature-v1\0");
        for (String field : fields) value.append(field.length()).append(':').append(field);
        return value.toString();
    }

    @FunctionalInterface
    private interface SchemaName {
        QualifiedName normalize(String schema);
    }

    @FunctionalInterface
    private interface ObjectName {
        QualifiedName normalize(String schema, String object);
    }
}
