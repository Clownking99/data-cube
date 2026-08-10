package com.datacube.spi.schemadiff;

import com.datacube.spi.model.DbType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SnapshotFingerprintTest {

    @Test
    void fingerprintIgnoresObjectInsertionOrderConnectionIdentityAndCaptureTime() {
        TableDefinition orders = table("orders", "bigint");
        TableDefinition customers = table("customers", "uuid");
        Map<ObjectKey, SchemaObject> firstOrder = new LinkedHashMap<>();
        firstOrder.put(orders.key(), orders);
        firstOrder.put(customers.key(), customers);
        Map<ObjectKey, SchemaObject> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put(customers.key(), customers);
        reverseOrder.put(orders.key(), orders);
        SnapshotCompleteness completeness = new SnapshotCompleteness(true, new TreeMap<>());

        String first = SnapshotFingerprint.compute(DbType.POSTGRESQL, name("public"), completeness, firstOrder);
        String second = SnapshotFingerprint.compute(DbType.POSTGRESQL, name("public"), completeness, reverseOrder);
        SchemaSnapshot left = new SchemaSnapshot(DbType.POSTGRESQL, "connection-a", name("public"),
                Instant.parse("2026-01-01T00:00:00Z"), completeness, new TreeMap<>(firstOrder), first);
        SchemaSnapshot right = new SchemaSnapshot(DbType.POSTGRESQL, "connection-b", name("public"),
                Instant.parse("2026-02-01T00:00:00Z"), completeness, new TreeMap<>(reverseOrder), second);

        assertEquals(first, second);
        assertEquals(SnapshotFingerprint.compute(left), SnapshotFingerprint.compute(right));
    }

    @Test
    void fingerprintChangesWhenAnySchemaPropertyChanges() {
        SnapshotCompleteness completeness = new SnapshotCompleteness(true, new TreeMap<>());
        String baseline = SnapshotFingerprint.compute(DbType.POSTGRESQL, name("public"), completeness,
                Map.of(table("orders", "bigint").key(), table("orders", "bigint")));
        String changedType = SnapshotFingerprint.compute(DbType.POSTGRESQL, name("public"), completeness,
                Map.of(table("orders", "uuid").key(), table("orders", "uuid")));
        String changedDatabase = SnapshotFingerprint.compute(DbType.ORACLE, name("public"), completeness,
                Map.of(table("orders", "bigint").key(), table("orders", "bigint")));
        String changedCompleteness = SnapshotFingerprint.compute(DbType.POSTGRESQL, name("public"),
                new SnapshotCompleteness(false, sortedMap(ObjectType.FUNCTION, "routines")),
                Map.of(table("orders", "bigint").key(), table("orders", "bigint")));

        assertNotEquals(baseline, changedType);
        assertNotEquals(baseline, changedDatabase);
        assertNotEquals(baseline, changedCompleteness);
    }

    private static TableDefinition table(String tableName, String baseType) {
        ObjectKey key = new ObjectKey(ObjectType.TABLE, name(tableName), "");
        ColumnDefinition column = new ColumnDefinition(name("id"),
                new CanonicalDataType(baseType, null, null, null, false, 0, sortedMap("dialect", "native")),
                false, "0", 1, "identifier");
        return new TableDefinition(key, List.of(column), List.of(), List.of(), java.util.Set.of());
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static <K extends Comparable<? super K>, V> SortedMap<K, V> sortedMap(K key, V value) {
        SortedMap<K, V> values = new TreeMap<>();
        values.put(key, value);
        return values;
    }
}
