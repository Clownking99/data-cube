package com.datacube.spi.schemadiff;

import com.datacube.spi.model.DbType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SchemaSnapshotModelTest {

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
    void partialCompletenessRetainsOnlyExactUnavailableObjectTypesAndNoExceptionMessage() {
        String secret = "jdbc:postgresql://user:top-secret@example.test/db";
        SortedMap<ObjectType, String> unavailable = new TreeMap<>();
        unavailable.put(ObjectType.VIEW, "views");
        unavailable.put(ObjectType.FUNCTION, "routines");
        SnapshotCompleteness completeness = new SnapshotCompleteness(false, unavailable);

        assertFalse(completeness.complete());
        assertEquals(Set.of(ObjectType.VIEW, ObjectType.FUNCTION), completeness.unavailableScopes().keySet());
        assertFalse(completeness.unavailableScopes().toString().contains(secret));
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
}
