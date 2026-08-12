package com.datacube.spi.schemadiff;

import com.datacube.spi.model.DbType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SchemaSnapshotModelTest {

    @Test
    void columnDefaultPresenceTreatsNullAndBlankAsNoDefault() {
        CanonicalDataType type = new CanonicalDataType("text", null, null, null,
                false, 0, new java.util.TreeMap<>());
        for (String value : java.util.Arrays.asList(null, "", " \t")) {
            assertFalse(new ColumnDefinition(new QualifiedName("c", "c", false), type,
                    true, value, 1, null).hasDefault());
        }
        assertTrue(new ColumnDefinition(new QualifiedName("c", "c", false), type,
                true, "0", 1, null).hasDefault());
    }

    @Test
    void publicModelSummariesNeverRenderConnectionSchemaObjectDdlPlsqlOrDefaults() {
        String connectionSecret = "production-admin:password-secret@database";
        QualifiedName schema = new QualifiedName(
                "\"SecretOwner\"", "oracle-schema-v1\0SecretOwner", true);
        ObjectKey tableKey = new ObjectKey(ObjectType.TABLE,
                new QualifiedName("\"SecretOwner\".\"PayrollTable\"",
                        "oracle-object-v1\0PayrollTable", true), "");
        ColumnDefinition column = new ColumnDefinition(
                new QualifiedName("\"SalarySecret\"", "oracle-child-v1\0SalarySecret", true),
                new CanonicalDataType("VARCHAR2", 200L, null, null,
                        false, 0, new TreeMap<>()), false,
                "decrypt('column-default-secret')", 1, "confidential-comment");
        TableDefinition table = new TableDefinition(
                tableKey, List.of(column), List.of(), List.of(), Set.of());
        ObjectKey functionKey = new ObjectKey(ObjectType.FUNCTION,
                new QualifiedName("\"SecretOwner\".\"PayrollFunction\"",
                        "oracle-object-v1\0PayrollFunction", true), "oracle-signature-secret");
        String normalizedPlsql = "CREATE FUNCTION PayrollFunction RETURN VARCHAR2 IS "
                + "BEGIN RETURN 'plsql-secret'; END;";
        DefinitionObject definition = new DefinitionObject(
                functionKey, normalizedPlsql, normalizedPlsql + "\n/", Set.of(),
                DefinitionConfidence.HIGH);
        SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>();
        objects.put(tableKey, table);
        objects.put(functionKey, definition);
        SchemaSnapshot snapshot = new SchemaSnapshot(
                DbType.ORACLE, connectionSecret, schema, Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), objects, "safe-fingerprint");

        assertEquals("SchemaSnapshot[complete=true, objectCount=2, fingerprint=safe-fingerprint]",
                snapshot.toString());
        assertEquals("DefinitionObject[type=FUNCTION, definitionPresent=true, "
                        + "dependencyCount=0, confidence=HIGH]", definition.toString());
        assertEquals("ColumnDefinition[nullable=false, ordinal=1, defaultPresent=true, "
                        + "commentPresent=true]", column.toString());
        String summaries = snapshot + "\n" + definition + "\n" + column;
        for (String sensitive : List.of(
                connectionSecret, "SecretOwner", "PayrollTable", "SalarySecret",
                "PayrollFunction", "oracle-signature-secret", "CREATE FUNCTION",
                "plsql-secret", "column-default-secret", "confidential-comment")) {
            assertFalse(summaries.contains(sensitive), sensitive);
        }
    }

    @Test
    void modelDefensivelyCopiesAndExposesOnlyUnmodifiableCollections() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders", "");
        List<ColumnDefinition> columns = new ArrayList<>(List.of(column("id", "bigint")));
        List<ConstraintDefinition> constraints = new ArrayList<>();
        List<IndexDefinition> indexes = new ArrayList<>();
        Set<ObjectKey> dependencies = new java.util.HashSet<>();
        SortedMap<String, String> extensions = new TreeMap<>(Map.of("provider", "identity"));
        CanonicalDataType type = new CanonicalDataType("bigint", null, null, null,
                false, 0, extensions);
        TableDefinition table = new TableDefinition(tableKey, columns, constraints, indexes, dependencies);
        SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>(Map.of(tableKey, table));
        SortedMap<ObjectType, String> unavailable = new TreeMap<>();
        SnapshotCompleteness completeness = new SnapshotCompleteness(false, unavailable);
        SchemaSnapshot snapshot = new SchemaSnapshot(DbType.POSTGRESQL, "connection-a",
                name("public"), Instant.parse("2026-01-01T00:00:00Z"), completeness, objects, "fp");

        columns.clear();
        dependencies.add(key(ObjectType.SEQUENCE, "order_seq", ""));
        extensions.put("provider", "changed");
        objects.clear();
        unavailable.put(ObjectType.TABLE, "tables");

        assertEquals(1, table.columns().size());
        assertTrue(table.dependencies().isEmpty());
        assertEquals("identity", type.providerExtensions().get("provider"));
        assertEquals(1, snapshot.objects().size());
        assertTrue(completeness.unavailableScopes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> table.columns().add(column("x", "integer")));
        assertThrows(UnsupportedOperationException.class, () -> table.dependencies().add(tableKey));
        assertThrows(UnsupportedOperationException.class, () -> type.providerExtensions().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.objects().clear());
        assertThrows(UnsupportedOperationException.class, () -> completeness.unavailableScopes().put(ObjectType.TABLE, "tables"));
    }

    @Test
    void sequenceProviderExtensionsAreDefensivelyCopiedWithLegacyConstructorCompatibility() {
        ObjectKey key = key(ObjectType.SEQUENCE, "orders_seq", "");
        Map<String, String> extensions = new LinkedHashMap<>(Map.of(
                "oracle.order", "ORDER", "oracle.startValueKnown", "false"));
        SequenceDefinition sequence = new SequenceDefinition(
                key, null, "1", "1", "999", false, 20, Set.of(), extensions);
        SequenceDefinition legacy = new SequenceDefinition(
                key, "1", "1", "1", "999", false, 20, Set.of());

        extensions.put("oracle.order", "NOORDER");

        assertEquals("ORDER", sequence.providerExtensions().get("oracle.order"));
        assertEquals(Map.of(), legacy.providerExtensions());
        assertThrows(UnsupportedOperationException.class,
                () -> sequence.providerExtensions().put("x", "y"));
    }

    @Test
    void objectKeyOrdersByTypeThenProviderComparisonKeyThenSignature() {
        ObjectKey table = key(ObjectType.TABLE, "z", "");
        ObjectKey earlierName = key(ObjectType.TABLE, "a", "");
        ObjectKey firstRoutine = key(ObjectType.FUNCTION, "f", "integer");
        ObjectKey overloadedRoutine = key(ObjectType.FUNCTION, "f", "text");
        List<ObjectKey> keys = new ArrayList<>(List.of(table, overloadedRoutine, firstRoutine, earlierName));

        keys.sort(ObjectKey::compareTo);

        assertEquals(List.of(earlierName, table, firstRoutine, overloadedRoutine), keys);
        assertNotEquals(firstRoutine, overloadedRoutine);
        assertNotEquals(0, firstRoutine.compareTo(overloadedRoutine));
    }

    @Test
    void providerSuppliedComparisonKeysRemainUntouchedForQuotedAndUnquotedNames() {
        QualifiedName quoted = new QualifiedName("Order", "quoted:Order", true);
        QualifiedName unquoted = new QualifiedName("order", "folded:ORDER", false);

        assertEquals("quoted:Order", quoted.comparisonKey());
        assertEquals("folded:ORDER", unquoted.comparisonKey());
        assertNotEquals(quoted, unquoted);
    }

    @Test
    void definitionConfidenceExposesOnlyHighAndLowSafetyLevels() {
        assertEquals(List.of(DefinitionConfidence.HIGH, DefinitionConfidence.LOW),
                Arrays.asList(DefinitionConfidence.values()));
        assertEquals(DefinitionConfidence.LOW, DefinitionConfidence.valueOf("LOW"));
    }

    @Test
    void nestedDefinitionCollectionsAreDefensivelyCopiedAndUnmodifiable() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders", "");
        List<QualifiedName> columns = new ArrayList<>(List.of(name("customer_id")));
        List<QualifiedName> referencedColumns = new ArrayList<>(List.of(name("id")));
        Set<ObjectKey> dependencies = new java.util.HashSet<>(Set.of(tableKey));
        ConstraintDefinition constraint = new ConstraintDefinition(key(ObjectType.FOREIGN_KEY, "fk_orders_customer", ""),
                ConstraintKind.FOREIGN_KEY, columns, tableKey, referencedColumns, null, "CASCADE", "RESTRICT",
                false, dependencies);
        List<String> expressions = new ArrayList<>(List.of("customer_id"));
        IndexDefinition index = new IndexDefinition(key(ObjectType.INDEX, "ix_orders_customer", ""), false,
                expressions, null, false, dependencies);

        columns.clear();
        referencedColumns.clear();
        expressions.clear();
        dependencies.clear();

        assertEquals(1, constraint.columns().size());
        assertEquals(1, constraint.referencedColumns().size());
        assertEquals(1, constraint.dependencies().size());
        assertEquals(1, index.normalizedExpressions().size());
        assertEquals(1, index.dependencies().size());
        assertThrows(UnsupportedOperationException.class, () -> constraint.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> constraint.referencedColumns().clear());
        assertThrows(UnsupportedOperationException.class, () -> constraint.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> index.normalizedExpressions().clear());
        assertThrows(UnsupportedOperationException.class, () -> index.dependencies().clear());
    }

    @Test
    void everyCollectionComponentCopiesItsInputAndRejectsAccessorMutation() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders", "");
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "orders_seq", "");
        List<ColumnDefinition> tableColumns = new ArrayList<>(List.of(column("id", "bigint")));
        List<QualifiedName> constraintColumns = new ArrayList<>(List.of(name("customer_id")));
        List<QualifiedName> referencedColumns = new ArrayList<>(List.of(name("id")));
        Set<ObjectKey> constraintDependencies = new java.util.HashSet<>(Set.of(tableKey));
        ConstraintDefinition constraint = new ConstraintDefinition(key(ObjectType.FOREIGN_KEY, "fk", ""),
                ConstraintKind.FOREIGN_KEY, constraintColumns, tableKey, referencedColumns,
                null, null, null, false, constraintDependencies);
        List<ConstraintDefinition> tableConstraints = new ArrayList<>(List.of(constraint));
        List<String> expressions = new ArrayList<>(List.of("customer_id"));
        Set<ObjectKey> indexDependencies = new java.util.HashSet<>(Set.of(tableKey));
        IndexDefinition index = new IndexDefinition(key(ObjectType.INDEX, "ix", ""), false,
                expressions, null, false, indexDependencies);
        List<IndexDefinition> tableIndexes = new ArrayList<>(List.of(index));
        Set<ObjectKey> tableDependencies = new java.util.HashSet<>(Set.of(sequenceKey));
        TableDefinition table = new TableDefinition(tableKey, tableColumns, tableConstraints, tableIndexes, tableDependencies);
        Set<ObjectKey> sequenceDependencies = new java.util.HashSet<>(Set.of(tableKey));
        SequenceDefinition sequence = new SequenceDefinition(sequenceKey, "1", "1", null, null, false, null,
                sequenceDependencies);
        Set<ObjectKey> definitionDependencies = new java.util.HashSet<>(Set.of(tableKey));
        DefinitionObject definition = new DefinitionObject(key(ObjectType.VIEW, "order_view", ""), "select 1", "SELECT 1",
                definitionDependencies, DefinitionConfidence.LOW);
        SortedMap<String, String> extensions = new TreeMap<>(Map.of("provider", "native"));
        CanonicalDataType type = new CanonicalDataType("integer", null, null, null, false, 0, extensions);
        SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>(Map.of(tableKey, table, sequenceKey, sequence));
        SortedMap<ObjectType, String> unavailable = new TreeMap<>(Map.of(ObjectType.VIEW, "NOT_SUPPORTED"));
        SnapshotCompleteness completeness = new SnapshotCompleteness(false, unavailable);
        SchemaSnapshot snapshot = new SchemaSnapshot(DbType.POSTGRESQL, "connection", name("public"), Instant.EPOCH,
                completeness, objects, "fingerprint");

        tableColumns.clear();
        constraintColumns.clear();
        referencedColumns.clear();
        constraintDependencies.clear();
        tableConstraints.clear();
        expressions.clear();
        indexDependencies.clear();
        tableIndexes.clear();
        tableDependencies.clear();
        sequenceDependencies.clear();
        definitionDependencies.clear();
        extensions.clear();
        objects.clear();
        unavailable.clear();

        assertEquals(1, table.columns().size());
        assertEquals(1, table.constraints().size());
        assertEquals(1, table.indexes().size());
        assertEquals(1, table.dependencies().size());
        assertEquals(1, constraint.columns().size());
        assertEquals(1, constraint.referencedColumns().size());
        assertEquals(1, constraint.dependencies().size());
        assertEquals(1, index.normalizedExpressions().size());
        assertEquals(1, index.dependencies().size());
        assertEquals(1, sequence.dependencies().size());
        assertEquals(1, definition.dependencies().size());
        assertEquals("native", type.providerExtensions().get("provider"));
        assertEquals(2, snapshot.objects().size());
        assertEquals(1, completeness.unavailableScopes().size());
        assertThrows(UnsupportedOperationException.class, () -> table.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.constraints().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.indexes().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> constraint.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> constraint.referencedColumns().clear());
        assertThrows(UnsupportedOperationException.class, () -> constraint.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> index.normalizedExpressions().clear());
        assertThrows(UnsupportedOperationException.class, () -> index.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> sequence.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> type.providerExtensions().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.objects().clear());
        assertThrows(UnsupportedOperationException.class, () -> completeness.unavailableScopes().clear());
    }

    @Test
    void partialCompletenessRetainsOnlyExactUnavailableObjectTypesAndNoExceptionMessage() {
        List<String> diagnosticCodes = List.of(
                SnapshotCompleteness.NOT_SUPPORTED,
                SnapshotCompleteness.PERMISSION_DENIED,
                SnapshotCompleteness.METADATA_UNAVAILABLE,
                SnapshotCompleteness.DEFINITION_UNAVAILABLE,
                SnapshotCompleteness.DEPENDENCY_UNRESOLVED);
        SortedMap<ObjectType, String> unavailable = new TreeMap<>();
        for (int index = 0; index < diagnosticCodes.size(); index++) {
            unavailable.put(ObjectType.values()[index], diagnosticCodes.get(index));
        }

        SnapshotCompleteness completeness = new SnapshotCompleteness(false, unavailable);

        assertFalse(completeness.complete());
        assertEquals(Set.copyOf(unavailable.keySet()), completeness.unavailableScopes().keySet());
        assertEquals(Set.copyOf(diagnosticCodes), Set.copyOf(completeness.unavailableScopes().values()));
        assertFalse(completeness.toString().contains("jdbc:"));
    }

    @Test
    void partialCompletenessRejectsSecretsUrlsAndUnknownDiagnosticText() {
        String secret = "top-secret-password";
        String jdbcUrl = "jdbc:postgresql://alice:top-secret-password@example.test:5432/app";

        assertRejectedDiagnostic(secret);
        assertRejectedDiagnostic(jdbcUrl);
        assertRejectedDiagnostic(new IllegalStateException(jdbcUrl).getMessage());
        assertRejectedDiagnostic("metadata query failed for object 42");
        assertRejectedDiagnostic(null);
    }

    private static void assertRejectedDiagnostic(String value) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotCompleteness(false, sortedMap(ObjectType.VIEW, value)));
        assertFalse(exception.getMessage().contains(String.valueOf(value)));
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static ObjectKey key(ObjectType type, String value, String signature) {
        return new ObjectKey(type, name(value), signature);
    }

    private static ColumnDefinition column(String value, String baseType) {
        return new ColumnDefinition(name(value), new CanonicalDataType(baseType, null, null, null,
                false, 0, new TreeMap<>()), true, null, 1, null);
    }

    private static <K extends Comparable<? super K>, V> SortedMap<K, V> sortedMap(K key, V value) {
        SortedMap<K, V> values = new TreeMap<>();
        values.put(key, value);
        return values;
    }
}
