package com.datacube.spi.schemadiff;

import com.datacube.spi.model.DbType;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotFingerprintTest {

    @Test
    void fingerprintIgnoresObjectInsertionOrderConnectionIdentityAndCaptureTime() {
        Fixture fixture = fixture();
        Map<ObjectKey, SchemaObject> firstOrder = objects(fixture.table(), fixture.sequence(), fixture.definition());
        Map<ObjectKey, SchemaObject> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put(fixture.definition().key(), fixture.definition());
        reverseOrder.put(fixture.sequence().key(), fixture.sequence());
        reverseOrder.put(fixture.table().key(), fixture.table());
        String first = fingerprint(DbType.POSTGRESQL, name("public", "public", false), fixture.completeness(), firstOrder);
        String second = fingerprint(DbType.POSTGRESQL, name("public", "public", false), fixture.completeness(), reverseOrder);
        SchemaSnapshot left = new SchemaSnapshot(DbType.POSTGRESQL, "connection-a", name("public", "public", false),
                Instant.parse("2026-01-01T00:00:00Z"), fixture.completeness(), new TreeMap<>(firstOrder), first);
        SchemaSnapshot right = new SchemaSnapshot(DbType.POSTGRESQL, "connection-b", name("public", "public", false),
                Instant.parse("2026-02-01T00:00:00Z"), fixture.completeness(), new TreeMap<>(reverseOrder), second);

        assertEquals(first, second);
        assertEquals(SnapshotFingerprint.compute(left), SnapshotFingerprint.compute(right));
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void fingerprintMutationMatrixCoversEveryRecordComponentAndNestedProperty() {
        Fixture fixture = fixture();
        String baseline = fingerprint(fixture);
        TableDefinition table = fixture.table();
        ColumnDefinition firstColumn = table.columns().getFirst();
        CanonicalDataType type = firstColumn.dataType();
        ConstraintDefinition constraint = table.constraints().getFirst();
        IndexDefinition index = table.indexes().getFirst();
        SequenceDefinition sequence = fixture.sequence();
        DefinitionObject definition = fixture.definition();

        assertChanged(baseline, fingerprint(DbType.ORACLE, schema(), fixture.completeness(), objects(fixture)));
        assertChanged(baseline, fingerprint(DbType.POSTGRESQL, name("PUBLIC", "public", false), fixture.completeness(), objects(fixture)));
        assertChanged(baseline, fingerprint(DbType.POSTGRESQL, name("public", "PUBLIC", false), fixture.completeness(), objects(fixture)));
        assertChanged(baseline, fingerprint(DbType.POSTGRESQL, name("public", "public", true), fixture.completeness(), objects(fixture)));
        assertChanged(baseline, fingerprint(DbType.POSTGRESQL, schema(), new SnapshotCompleteness(true, new TreeMap<>()), objects(fixture)));
        assertChanged(baseline, fingerprint(DbType.POSTGRESQL, schema(),
                new SnapshotCompleteness(false, sortedMap(ObjectType.PROCEDURE, SnapshotCompleteness.PERMISSION_DENIED)), objects(fixture)));

        assertChanged(baseline, fingerprint(replace(fixture, tableWithKey(table, key(ObjectType.VIEW, table.key().name(), "")), sequence, definition)));
        assertChanged(baseline, fingerprint(replace(fixture, tableWithKey(table, key(ObjectType.TABLE, name("ORDERS", "orders", false), "")), sequence, definition)));
        assertChanged(baseline, fingerprint(replace(fixture, tableWithKey(table, key(ObjectType.TABLE, name("orders", "ORDERS", false), "")), sequence, definition)));
        assertChanged(baseline, fingerprint(replace(fixture, tableWithKey(table, key(ObjectType.TABLE, name("orders", "orders", true), "")), sequence, definition)));
        assertChanged(baseline, fingerprint(replace(fixture, tableWithKey(table, key(ObjectType.TABLE, table.key().name(), "v2")), sequence, definition)));

        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, new ColumnDefinition(
                name("ID", "id", false), type, firstColumn.nullable(), firstColumn.normalizedDefault(), firstColumn.ordinal(), firstColumn.comment()))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, new ColumnDefinition(
                name("id", "ID", false), type, firstColumn.nullable(), firstColumn.normalizedDefault(), firstColumn.ordinal(), firstColumn.comment()))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, new ColumnDefinition(
                name("id", "id", true), type, firstColumn.nullable(), firstColumn.normalizedDefault(), firstColumn.ordinal(), firstColumn.comment()))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, columnWithType(firstColumn,
                new CanonicalDataType("uuid", type.length(), type.precision(), type.scale(), type.withTimeZone(), type.arrayDimensions(), type.providerExtensions())))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, columnWithType(firstColumn,
                new CanonicalDataType(type.baseType(), 99L, type.precision(), type.scale(), type.withTimeZone(), type.arrayDimensions(), type.providerExtensions())))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, columnWithType(firstColumn,
                new CanonicalDataType(type.baseType(), type.length(), 19, type.scale(), type.withTimeZone(), type.arrayDimensions(), type.providerExtensions())))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, columnWithType(firstColumn,
                new CanonicalDataType(type.baseType(), type.length(), type.precision(), 4, type.withTimeZone(), type.arrayDimensions(), type.providerExtensions())))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, columnWithType(firstColumn,
                new CanonicalDataType(type.baseType(), type.length(), type.precision(), type.scale(), !type.withTimeZone(), type.arrayDimensions(), type.providerExtensions())))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, columnWithType(firstColumn,
                new CanonicalDataType(type.baseType(), type.length(), type.precision(), type.scale(), type.withTimeZone(), 2, type.providerExtensions())))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, columnWithType(firstColumn,
                new CanonicalDataType(type.baseType(), type.length(), type.precision(), type.scale(), type.withTimeZone(), type.arrayDimensions(), sortedMap("dialect", "changed"))))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, new ColumnDefinition(firstColumn.name(), type,
                !firstColumn.nullable(), firstColumn.normalizedDefault(), firstColumn.ordinal(), firstColumn.comment()))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, new ColumnDefinition(firstColumn.name(), type,
                firstColumn.nullable(), "nextval('orders_seq')", firstColumn.ordinal(), firstColumn.comment()))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, new ColumnDefinition(firstColumn.name(), type,
                firstColumn.nullable(), firstColumn.normalizedDefault(), 9, firstColumn.comment()))));
        assertChanged(baseline, fingerprint(replaceFirstColumn(fixture, new ColumnDefinition(firstColumn.name(), type,
                firstColumn.nullable(), firstColumn.normalizedDefault(), firstColumn.ordinal(), "changed comment"))));
        ColumnDefinition ordinalOneCustomer = new ColumnDefinition(table.columns().get(1).name(), table.columns().get(1).dataType(),
                table.columns().get(1).nullable(), table.columns().get(1).normalizedDefault(), 1, table.columns().get(1).comment());
        ColumnDefinition ordinalTwoId = new ColumnDefinition(firstColumn.name(), firstColumn.dataType(), firstColumn.nullable(),
                firstColumn.normalizedDefault(), 2, firstColumn.comment());
        assertChanged(baseline, fingerprint(replace(fixture, new TableDefinition(table.key(), List.of(ordinalOneCustomer, ordinalTwoId),
                table.constraints(), table.indexes(), table.dependencies()), sequence, definition)));

        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(key(ObjectType.UNIQUE_CONSTRAINT, constraint.key().name(), ""),
                constraint.kind(), constraint.columns(), constraint.referencedTable(), constraint.referencedColumns(), constraint.normalizedExpression(),
                constraint.updateAction(), constraint.deleteAction(), constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), ConstraintKind.CHECK,
                constraint.columns(), constraint.referencedTable(), constraint.referencedColumns(), constraint.normalizedExpression(), constraint.updateAction(),
                constraint.deleteAction(), constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(),
                List.of(constraint.columns().get(1), constraint.columns().getFirst()), constraint.referencedTable(), constraint.referencedColumns(),
                constraint.normalizedExpression(), constraint.updateAction(), constraint.deleteAction(), constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                key(ObjectType.TABLE, "accounts", ""), constraint.referencedColumns(), constraint.normalizedExpression(), constraint.updateAction(),
                constraint.deleteAction(), constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                constraint.referencedTable(), List.of(constraint.referencedColumns().get(1), constraint.referencedColumns().getFirst()),
                constraint.normalizedExpression(), constraint.updateAction(), constraint.deleteAction(), constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                constraint.referencedTable(), constraint.referencedColumns(), "customer_id <> 0", constraint.updateAction(), constraint.deleteAction(),
                constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                constraint.referencedTable(), constraint.referencedColumns(), constraint.normalizedExpression(), "SET NULL", constraint.deleteAction(),
                constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                constraint.referencedTable(), constraint.referencedColumns(), constraint.normalizedExpression(), constraint.updateAction(), "CASCADE",
                constraint.providerGeneratedName(), constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                constraint.referencedTable(), constraint.referencedColumns(), constraint.normalizedExpression(), constraint.updateAction(), constraint.deleteAction(),
                true, constraint.dependencies()))));
        assertChanged(baseline, fingerprint(replaceConstraint(fixture, new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                constraint.referencedTable(), constraint.referencedColumns(), constraint.normalizedExpression(), constraint.updateAction(), constraint.deleteAction(),
                constraint.providerGeneratedName(), Set.of(key(ObjectType.SEQUENCE, "changed_dependency", ""))))));

        assertChanged(baseline, fingerprint(replaceIndex(fixture, new IndexDefinition(key(ObjectType.INDEX, "ix_changed", ""), index.unique(),
                index.normalizedExpressions(), index.normalizedPredicate(), index.providerGeneratedName(), index.dependencies()))));
        assertChanged(baseline, fingerprint(replaceIndex(fixture, new IndexDefinition(index.key(), true,
                index.normalizedExpressions(), index.normalizedPredicate(), index.providerGeneratedName(), index.dependencies()))));
        assertChanged(baseline, fingerprint(replaceIndex(fixture, new IndexDefinition(index.key(), index.unique(),
                List.of(index.normalizedExpressions().get(1), index.normalizedExpressions().getFirst()), index.normalizedPredicate(),
                index.providerGeneratedName(), index.dependencies()))));
        assertChanged(baseline, fingerprint(replaceIndex(fixture, new IndexDefinition(index.key(), index.unique(), index.normalizedExpressions(), "id > 0",
                index.providerGeneratedName(), index.dependencies()))));
        assertChanged(baseline, fingerprint(replaceIndex(fixture, new IndexDefinition(index.key(), index.unique(), index.normalizedExpressions(),
                index.normalizedPredicate(), true, index.dependencies()))));
        assertChanged(baseline, fingerprint(replaceIndex(fixture, new IndexDefinition(index.key(), index.unique(), index.normalizedExpressions(),
                index.normalizedPredicate(), index.providerGeneratedName(), Set.of(key(ObjectType.TABLE, "changed_dependency", ""))))));
        assertChanged(baseline, fingerprint(replace(fixture, new TableDefinition(table.key(), table.columns(), table.constraints(), table.indexes(),
                Set.of(key(ObjectType.SEQUENCE, "changed_dependency", ""))), sequence, definition)));

        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(key(ObjectType.SEQUENCE, "changed_seq", ""), sequence.startValue(),
                sequence.incrementBy(), sequence.minimumValue(), sequence.maximumValue(), sequence.cycle(), sequence.cacheSize(), sequence.dependencies()), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(sequence.key(), "2", sequence.incrementBy(), sequence.minimumValue(),
                sequence.maximumValue(), sequence.cycle(), sequence.cacheSize(), sequence.dependencies()), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(sequence.key(), sequence.startValue(), "2", sequence.minimumValue(),
                sequence.maximumValue(), sequence.cycle(), sequence.cacheSize(), sequence.dependencies()), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(sequence.key(), sequence.startValue(), sequence.incrementBy(), "1",
                sequence.maximumValue(), sequence.cycle(), sequence.cacheSize(), sequence.dependencies()), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(sequence.key(), sequence.startValue(), sequence.incrementBy(),
                sequence.minimumValue(), "1000", sequence.cycle(), sequence.cacheSize(), sequence.dependencies()), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(sequence.key(), sequence.startValue(), sequence.incrementBy(),
                sequence.minimumValue(), sequence.maximumValue(), true, sequence.cacheSize(), sequence.dependencies()), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(sequence.key(), sequence.startValue(), sequence.incrementBy(),
                sequence.minimumValue(), sequence.maximumValue(), sequence.cycle(), 21, sequence.dependencies()), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, new SequenceDefinition(sequence.key(), sequence.startValue(), sequence.incrementBy(),
                sequence.minimumValue(), sequence.maximumValue(), sequence.cycle(), sequence.cacheSize(), Set.of(key(ObjectType.TABLE, "changed_dependency", ""))), definition)));
        assertChanged(baseline, fingerprint(replace(fixture, table, sequence, new DefinitionObject(key(ObjectType.VIEW, "changed_view", ""),
                definition.normalizedDefinition(), definition.originalDefinition(), definition.dependencies(), definition.confidence()))));
        assertChanged(baseline, fingerprint(replace(fixture, table, sequence, new DefinitionObject(definition.key(), "select 2", definition.originalDefinition(),
                definition.dependencies(), definition.confidence()))));
        assertChanged(baseline, fingerprint(replace(fixture, table, sequence, new DefinitionObject(definition.key(), definition.normalizedDefinition(), "SELECT 2",
                definition.dependencies(), definition.confidence()))));
        assertChanged(baseline, fingerprint(replace(fixture, table, sequence, new DefinitionObject(definition.key(), definition.normalizedDefinition(),
                definition.originalDefinition(), Set.of(key(ObjectType.TABLE, "changed_dependency", "")), definition.confidence()))));
        assertChanged(baseline, fingerprint(replace(fixture, table, sequence, new DefinitionObject(definition.key(), definition.normalizedDefinition(),
                definition.originalDefinition(), definition.dependencies(), DefinitionConfidence.LOW))));

        Map<ObjectKey, SchemaObject> changedMapKey = objects(fixture);
        changedMapKey.remove(table.key());
        changedMapKey.put(key(ObjectType.TABLE, "map_key_only", ""), table);
        assertChanged(baseline, fingerprint(DbType.POSTGRESQL, schema(), fixture.completeness(), changedMapKey));
    }

    @Test
    void fingerprintIgnoresAllSemanticUnorderedCollectionAndMapInputOrder() {
        Fixture fixture = fixture();
        TableDefinition table = fixture.table();
        ConstraintDefinition constraint = table.constraints().getFirst();
        IndexDefinition index = table.indexes().getFirst();
        TableDefinition reorderedSets = new TableDefinition(table.key(), table.columns(), table.constraints(), table.indexes(),
                linkedSet(table.dependencies().stream().toList().reversed()));
        TableDefinition reorderedColumns = new TableDefinition(table.key(), List.of(table.columns().get(1), table.columns().getFirst()),
                table.constraints(), table.indexes(), table.dependencies());
        ConstraintDefinition reorderedConstraint = new ConstraintDefinition(constraint.key(), constraint.kind(), constraint.columns(),
                constraint.referencedTable(), constraint.referencedColumns(), constraint.normalizedExpression(), constraint.updateAction(),
                constraint.deleteAction(), constraint.providerGeneratedName(), linkedSet(constraint.dependencies().stream().toList().reversed()));
        IndexDefinition reorderedIndex = new IndexDefinition(index.key(), index.unique(), index.normalizedExpressions(), index.normalizedPredicate(),
                index.providerGeneratedName(), linkedSet(index.dependencies().stream().toList().reversed()));
        TableDefinition reordered = new TableDefinition(table.key(), table.columns(), List.of(table.constraints().get(1), reorderedConstraint),
                List.of(table.indexes().get(1), reorderedIndex), reorderedSets.dependencies());
        SortedMap<ObjectType, String> reversedCompleteness = new TreeMap<>();
        reversedCompleteness.put(ObjectType.VIEW, SnapshotCompleteness.NOT_SUPPORTED);
        reversedCompleteness.put(ObjectType.FUNCTION, SnapshotCompleteness.DEFINITION_UNAVAILABLE);
        assertEquals(fingerprint(fixture), fingerprint(DbType.POSTGRESQL, schema(), new SnapshotCompleteness(false, reversedCompleteness),
                objects(reordered, fixture.sequence(), fixture.definition())));
        assertEquals(fingerprint(fixture), fingerprint(DbType.POSTGRESQL, schema(), fixture.completeness(),
                objects(reorderedColumns, fixture.sequence(), fixture.definition())));
    }

    private static Fixture fixture() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders", "");
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "orders_seq", "");
        ObjectKey typeKey = key(ObjectType.TYPE, "order_status", "");
        CanonicalDataType type = new CanonicalDataType("numeric", 18L, 10, 2, true, 1,
                sortedMap("dialect", "native", "domain", "money"));
        ColumnDefinition id = new ColumnDefinition(name("id", "id", false), type, false, "0", 1, "identifier");
        ColumnDefinition customer = new ColumnDefinition(name("customer_id", "customer_id", false), type, true, null, 2, "customer");
        ConstraintDefinition firstConstraint = new ConstraintDefinition(key(ObjectType.FOREIGN_KEY, "fk_orders_customer", ""), ConstraintKind.FOREIGN_KEY,
                List.of(name("customer_id", "customer_id", false), name("store_id", "store_id", false)), key(ObjectType.TABLE, "customers", ""),
                List.of(name("id", "id", false), name("store_id", "store_id", false)), "customer_id > 0", "CASCADE", "RESTRICT", false,
                Set.of(sequenceKey, typeKey));
        ConstraintDefinition secondConstraint = new ConstraintDefinition(key(ObjectType.CHECK_CONSTRAINT, "ck_orders", ""), ConstraintKind.CHECK,
                List.of(name("id", "id", false)), null, List.of(), "id > 0", null, null, true, Set.of(tableKey));
        IndexDefinition firstIndex = new IndexDefinition(key(ObjectType.INDEX, "ix_orders", ""), false,
                List.of("lower(customer_id)", "id"), "customer_id IS NOT NULL", false, Set.of(sequenceKey, typeKey));
        IndexDefinition secondIndex = new IndexDefinition(key(ObjectType.INDEX, "ix_orders_id", ""), true,
                List.of("id"), null, true, Set.of(tableKey));
        TableDefinition table = new TableDefinition(tableKey, List.of(id, customer), List.of(firstConstraint, secondConstraint), List.of(firstIndex, secondIndex),
                Set.of(sequenceKey, typeKey));
        SequenceDefinition sequence = new SequenceDefinition(sequenceKey, "1", "5", "0", "999", false, 20, Set.of(tableKey, typeKey));
        DefinitionObject definition = new DefinitionObject(key(ObjectType.VIEW, "order_view", ""), "select id from orders", "SELECT ID FROM ORDERS",
                Set.of(tableKey, sequenceKey), DefinitionConfidence.HIGH);
        return new Fixture(table, sequence, definition, new SnapshotCompleteness(false,
                sortedMap(ObjectType.VIEW, SnapshotCompleteness.NOT_SUPPORTED, ObjectType.FUNCTION, SnapshotCompleteness.DEFINITION_UNAVAILABLE)));
    }

    private static String fingerprint(Fixture fixture) {
        return fingerprint(DbType.POSTGRESQL, schema(), fixture.completeness(), objects(fixture));
    }

    private static String fingerprint(DbType databaseType, QualifiedName schema, SnapshotCompleteness completeness,
                                      Map<ObjectKey, ? extends SchemaObject> objects) {
        return SnapshotFingerprint.compute(databaseType, schema, completeness, objects);
    }

    private static Map<ObjectKey, SchemaObject> objects(Fixture fixture) {
        return objects(fixture.table(), fixture.sequence(), fixture.definition());
    }

    private static Map<ObjectKey, SchemaObject> objects(TableDefinition table, SequenceDefinition sequence, DefinitionObject definition) {
        Map<ObjectKey, SchemaObject> objects = new LinkedHashMap<>();
        objects.put(table.key(), table);
        objects.put(sequence.key(), sequence);
        objects.put(definition.key(), definition);
        return objects;
    }

    private static Fixture replace(Fixture fixture, TableDefinition table, SequenceDefinition sequence, DefinitionObject definition) {
        return new Fixture(table, sequence, definition, fixture.completeness());
    }

    private static Fixture replaceFirstColumn(Fixture fixture, ColumnDefinition column) {
        TableDefinition table = fixture.table();
        return replace(fixture, new TableDefinition(table.key(), List.of(column, table.columns().get(1)), table.constraints(), table.indexes(), table.dependencies()),
                fixture.sequence(), fixture.definition());
    }

    private static Fixture replaceConstraint(Fixture fixture, ConstraintDefinition constraint) {
        TableDefinition table = fixture.table();
        return replace(fixture, new TableDefinition(table.key(), table.columns(), List.of(constraint, table.constraints().get(1)),
                table.indexes(), table.dependencies()), fixture.sequence(), fixture.definition());
    }

    private static Fixture replaceIndex(Fixture fixture, IndexDefinition index) {
        TableDefinition table = fixture.table();
        return replace(fixture, new TableDefinition(table.key(), table.columns(), table.constraints(), List.of(index, table.indexes().get(1)),
                table.dependencies()), fixture.sequence(), fixture.definition());
    }

    private static TableDefinition tableWithKey(TableDefinition table, ObjectKey key) {
        return new TableDefinition(key, table.columns(), table.constraints(), table.indexes(), table.dependencies());
    }

    private static ColumnDefinition columnWithType(ColumnDefinition column, CanonicalDataType type) {
        return new ColumnDefinition(column.name(), type, column.nullable(), column.normalizedDefault(), column.ordinal(), column.comment());
    }

    private static QualifiedName schema() {
        return name("public", "public", false);
    }

    private static QualifiedName name(String original, String comparisonKey, boolean quoted) {
        return new QualifiedName(original, comparisonKey, quoted);
    }

    private static ObjectKey key(ObjectType type, String value, String signature) {
        return key(type, name(value, value, false), signature);
    }

    private static ObjectKey key(ObjectType type, QualifiedName name, String signature) {
        return new ObjectKey(type, name, signature);
    }

    private static void assertChanged(String baseline, String changed) {
        assertNotEquals(baseline, changed);
    }

    private static <T> Set<T> linkedSet(List<T> values) {
        return new LinkedHashSet<>(values);
    }

    private static <K extends Comparable<? super K>, V> SortedMap<K, V> sortedMap(K key, V value) {
        return sortedMap(key, value, null, null);
    }

    private static <K extends Comparable<? super K>, V> SortedMap<K, V> sortedMap(K firstKey, V firstValue, K secondKey, V secondValue) {
        SortedMap<K, V> values = new TreeMap<>();
        values.put(firstKey, firstValue);
        if (secondKey != null) values.put(secondKey, secondValue);
        return values;
    }

    private record Fixture(TableDefinition table, SequenceDefinition sequence, DefinitionObject definition,
                           SnapshotCompleteness completeness) {
    }
}
