package com.datacube.schemadiff;

import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
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
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.TableDefinition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SchemaDiffEngineTest {

    private static final String INVALID_OBJECTS_MESSAGE = "Schema snapshot objects are invalid";
    private static final String INVALID_PROPERTY_VALUE_MESSAGE = "Property value type is not allowed";

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @Test
    void rejectsAliasMapKeyBeforeDiffOrRenameMatching() {
        ObjectKey aliasKey = key(ObjectType.SEQUENCE, "alias-jdbc:secret");
        SequenceDefinition value = new SequenceDefinition(key(ObjectType.SEQUENCE, "real-jdbc:secret"),
                "1", "1", null, null, false, null, Set.of());
        SortedMap<ObjectKey, SchemaObject> aliased = new TreeMap<>();
        aliased.put(aliasKey, value);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> engine.compare(snapshot(DbType.POSTGRESQL, complete(), aliased),
                        snapshot(DbType.POSTGRESQL, complete())));

        assertEquals(INVALID_OBJECTS_MESSAGE, failure.getMessage());
    }

    @Test
    void rejectsNullObjectValueWithFixedSafeDiagnostic() {
        SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>();
        objects.put(key(ObjectType.SEQUENCE, "jdbc:secret"), null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> engine.compare(snapshot(DbType.POSTGRESQL, complete(), objects),
                        snapshot(DbType.POSTGRESQL, complete())));

        assertEquals(INVALID_OBJECTS_MESSAGE, failure.getMessage());
    }

    @Test
    void rejectsNullObjectMapKeyWithFixedSafeDiagnostic() {
        SortedMap<ObjectKey, SchemaObject> objects = new TreeMap<>(java.util.Comparator.nullsFirst(ObjectKey::compareTo));
        objects.put(null, new SequenceDefinition(key(ObjectType.SEQUENCE, "jdbc:secret"),
                "1", "1", null, null, false, null, Set.of()));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> engine.compare(snapshot(DbType.POSTGRESQL, complete(), objects),
                        snapshot(DbType.POSTGRESQL, complete())));

        assertEquals(INVALID_OBJECTS_MESSAGE, failure.getMessage());
    }

    @Test
    void rejectsDifferentDatabaseTypesWithoutLeakingSnapshotContents() {
        String secret = "jdbc:postgresql://alice:secret@example.test/app";
        ObjectKey viewKey = key(ObjectType.VIEW, "orders_view");
        DefinitionObject sensitive = definition(viewKey, "select secret", secret, DefinitionConfidence.LOW);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> engine.compare(snapshot(DbType.POSTGRESQL, complete(), sensitive),
                        snapshot(DbType.ORACLE, complete(), sensitive)));

        assertFalse(failure.getMessage().contains(secret));
        assertFalse(failure.getMessage().contains("select secret"));
        assertFalse(failure.getMessage().contains("jdbc:"));
    }

    @Test
    void comparesColumnsTypesDefaultsNullabilityConstraintsAndIndexes() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        ObjectKey dependency = key(ObjectType.SEQUENCE, "orders_seq");
        ConstraintDefinition sourceConstraint = constraint("uq_orders_code", ConstraintKind.UNIQUE,
                List.of(name("code")), false, Set.of(tableKey));
        ConstraintDefinition targetConstraint = constraint("uq_orders_code", ConstraintKind.UNIQUE,
                List.of(name("code"), name("region")), false, Set.of(tableKey));
        IndexDefinition sourceIndex = index("ix_orders_code", false, List.of("code"), null, false, Set.of(tableKey));
        IndexDefinition targetIndex = index("ix_orders_code", true, List.of("code"), "code is not null", false,
                Set.of(tableKey));
        TableDefinition source = table(tableKey,
                List.of(column("id", type("bigint"), false, null, 1),
                        column("code", type("varchar"), true, null, 2)),
                List.of(sourceConstraint), List.of(sourceIndex), Set.of(dependency));
        TableDefinition target = table(tableKey,
                List.of(column("id", type("varchar"), true, "42", 1),
                        column("code", type("varchar"), true, null, 3)),
                List.of(targetConstraint), List.of(targetIndex), Set.of());

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.MODIFIED, difference.kind());
        assertEquals(RiskLevel.HIGH, difference.risk());
        assertEquals(AutomationLevel.DESTRUCTIVE_OPT_IN, difference.automation());
        assertEquals(Set.of(dependency), difference.dependencies());
        assertEquals(Set.of(
                        "columns[id].dataType", "columns[id].nullable", "columns[id].normalizedDefault",
                        "columns[code].ordinal", "constraints", "indexes", "dependencies"),
                difference.properties().stream().map(PropertyDifference::path).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void addingOnlyNullableColumnWithoutDefaultIsSafeAutomatic() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        TableDefinition source = table(tableKey,
                List.of(column("id", type("bigint"), false, null, 1)), List.of(), List.of(), Set.of());
        TableDefinition target = table(tableKey,
                List.of(column("id", type("bigint"), false, null, 1),
                        column("note", type("varchar"), true, null, 2)), List.of(), List.of(), Set.of());

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.MODIFIED, difference.kind());
        assertEquals(List.of("columns[note]"), difference.properties().stream().map(PropertyDifference::path).toList());
        assertEquals(RiskLevel.LOW, difference.risk());
        assertEquals(AutomationLevel.SAFE_AUTOMATIC, difference.automation());
    }

    @Test
    void providerGeneratedConstraintAndIndexNamesAreSemanticallyEquivalent() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        TableDefinition source = table(tableKey, List.of(column("id", type("bigint"), false, null, 1)),
                List.of(constraint("sys_c_100", ConstraintKind.PRIMARY_KEY, List.of(name("id")), true,
                        Set.of(tableKey))),
                List.of(index("sys_i_100", true, List.of("id"), null, true, Set.of(tableKey))), Set.of());
        TableDefinition target = table(tableKey, List.of(column("id", type("bigint"), false, null, 1)),
                List.of(constraint("sys_c_900", ConstraintKind.PRIMARY_KEY, List.of(name("id")), true,
                        Set.of(tableKey))),
                List.of(index("sys_i_900", true, List.of("id"), null, true, Set.of(tableKey))), Set.of());

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.EQUIVALENT, difference.kind());
        assertTrue(difference.properties().isEmpty());
        assertEquals(RiskLevel.LOW, difference.risk());
        assertEquals(AutomationLevel.SAFE_AUTOMATIC, difference.automation());
    }

    @Test
    void generatedNamesAreNotEquivalentWhenAnotherSemanticPropertyChanges() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        TableDefinition source = table(tableKey, List.of(),
                List.of(constraint("sys_c_100", ConstraintKind.UNIQUE, List.of(name("code")), true, Set.of())),
                List.of(index("sys_i_100", false, List.of("code"), null, true, Set.of())), Set.of());
        TableDefinition target = table(tableKey, List.of(),
                List.of(constraint("sys_c_900", ConstraintKind.UNIQUE, List.of(name("region")), true, Set.of())),
                List.of(index("sys_i_900", true, List.of("code"), null, true, Set.of())), Set.of());

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.MODIFIED, difference.kind());
        assertEquals(Set.of("constraints", "indexes"), difference.properties().stream()
                .map(PropertyDifference::path).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void generatedNamesAreNotEquivalentWhenDependenciesChange() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        TableDefinition source = table(tableKey, List.of(),
                List.of(constraint("sys_c_100", ConstraintKind.UNIQUE, List.of(name("code")), true,
                        Set.of(tableKey))), List.of(), Set.of());
        TableDefinition target = table(tableKey, List.of(),
                List.of(constraint("sys_c_900", ConstraintKind.UNIQUE, List.of(name("code")), true,
                        Set.of())), List.of(), Set.of());

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.MODIFIED, difference.kind());
        assertEquals(List.of("constraints"), difference.properties().stream()
                .map(PropertyDifference::path).toList());
    }

    @Test
    void comparesEverySequenceProperty() {
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "orders_seq");
        ObjectKey dependency = key(ObjectType.TABLE, "orders");
        SequenceDefinition source = new SequenceDefinition(sequenceKey, "1", "1", "1", "99", false, 20,
                Set.of(dependency), Map.of("oracle.order", "ORDER"));
        SequenceDefinition target = new SequenceDefinition(sequenceKey, "10", "2", "0", "999", true, 50,
                Set.of(), Map.of("oracle.order", "NOORDER"));

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.MODIFIED, difference.kind());
        assertEquals(Set.of("startValue", "incrementBy", "minimumValue", "maximumValue", "cycle", "cacheSize",
                        "providerExtensions", "dependencies"),
                difference.properties().stream().map(PropertyDifference::path).collect(java.util.stream.Collectors.toSet()));
        assertEquals(RiskLevel.HIGH, difference.risk());
        assertEquals(AutomationLevel.DESTRUCTIVE_OPT_IN, difference.automation());
    }

    @Test
    void comparesNormalizedDefinitionsButNeverOriginalFormatting() {
        ObjectKey viewKey = key(ObjectType.VIEW, "orders_view");
        DefinitionObject source = definition(viewKey, "select id from orders", "SELECT id\nFROM orders;",
                DefinitionConfidence.HIGH);
        DefinitionObject formattingOnly = definition(viewKey, "select id from orders", "select id from orders",
                DefinitionConfidence.HIGH);
        DefinitionObject changed = definition(viewKey, "select id, code from orders", "sensitive original sql",
                DefinitionConfidence.HIGH);

        SchemaDifference equivalent = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), formattingOnly)).differences());
        SchemaDifference modified = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), changed)).differences());

        assertEquals(DifferenceKind.EQUIVALENT, equivalent.kind());
        assertEquals(DifferenceKind.MODIFIED, modified.kind());
        assertEquals(List.of("normalizedDefinition"), modified.properties().stream()
                .map(PropertyDifference::path).toList());
        assertFalse(modified.explanation().contains("sensitive original sql"));
        assertEquals(RiskLevel.HIGH, modified.risk());
        assertEquals(AutomationLevel.DESTRUCTIVE_OPT_IN, modified.automation());
    }

    @Test
    void changedLowConfidenceDefinitionRequiresManualReview() {
        ObjectKey functionKey = key(ObjectType.FUNCTION, "calculate_total", "integer");
        DefinitionObject source = definition(functionKey, "definition-a", "secret source sql", DefinitionConfidence.LOW);
        DefinitionObject target = definition(functionKey, "definition-b", "secret target sql", DefinitionConfidence.HIGH);

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.MODIFIED, difference.kind());
        assertEquals(RiskLevel.HIGH, difference.risk());
        assertEquals(AutomationLevel.MANUAL_ONLY, difference.automation());
        assertFalse(difference.explanation().contains("secret source sql"));
        assertFalse(difference.explanation().contains("secret target sql"));
    }

    @Test
    void emitsMissingAndExtraObjectsInStableObjectKeyOrderWithConservativeRisk() {
        SequenceDefinition missingSequence = new SequenceDefinition(key(ObjectType.SEQUENCE, "z_seq"), "1", "1",
                null, null, false, null, Set.of());
        DefinitionObject missingLowConfidence = definition(key(ObjectType.VIEW, "low_view"), "select 1", "sql",
                DefinitionConfidence.LOW);
        DefinitionObject extraView = definition(key(ObjectType.VIEW, "a_view"), "select 2", "sql",
                DefinitionConfidence.HIGH);

        List<SchemaDifference> differences = engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), missingSequence, missingLowConfidence),
                snapshot(DbType.POSTGRESQL, complete(), extraView)).differences();

        assertEquals(List.of(missingSequence.key(), extraView.key(), missingLowConfidence.key()),
                differences.stream().map(SchemaDifference::object).toList());
        SchemaDifference missing = differences.get(0);
        assertEquals(DifferenceKind.MISSING_IN_TARGET, missing.kind());
        assertEquals(RiskLevel.LOW, missing.risk());
        assertEquals(AutomationLevel.SAFE_AUTOMATIC, missing.automation());
        SchemaDifference extra = differences.get(1);
        assertEquals(DifferenceKind.EXTRA_IN_TARGET, extra.kind());
        assertEquals(RiskLevel.CRITICAL, extra.risk());
        assertEquals(AutomationLevel.DESTRUCTIVE_OPT_IN, extra.automation());
        SchemaDifference lowConfidenceMissing = differences.get(2);
        assertEquals(RiskLevel.HIGH, lowConfidenceMissing.risk());
        assertEquals(AutomationLevel.MANUAL_ONLY, lowConfidenceMissing.automation());
    }

    @Test
    void resultModelsDefensivelyCopyAllOutputCollections() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        TableDefinition sourceObject = table(tableKey, List.of(), List.of(), List.of(), Set.of());
        SchemaSnapshot source = snapshot(DbType.POSTGRESQL, complete(), sourceObject);
        SchemaSnapshot target = snapshot(DbType.POSTGRESQL, complete());
        List<PropertyDifference> properties = new ArrayList<>(List.of(
                new PropertyDifference("columns[id]", null, "id", "Column differs")));
        Set<ObjectKey> dependencies = new HashSet<>(Set.of(tableKey));
        SchemaDifference difference = new SchemaDifference(DifferenceKind.MODIFIED, tableKey, sourceObject, sourceObject,
                properties, RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, dependencies, "Object differs");
        List<SchemaDifference> differences = new ArrayList<>(List.of(difference));
        List<RenameSuggestion> suggestions = new ArrayList<>();
        SchemaDiffResult result = new SchemaDiffResult(source, target, differences, suggestions);

        properties.clear();
        dependencies.clear();
        differences.clear();
        suggestions.add(new RenameSuggestion(tableKey, tableKey, 1.0, "advisory"));

        assertEquals(1, difference.properties().size());
        assertEquals(1, difference.dependencies().size());
        assertEquals(1, result.differences().size());
        assertTrue(result.renameSuggestions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> difference.properties().clear());
        assertThrows(UnsupportedOperationException.class, () -> difference.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.differences().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.renameSuggestions().clear());
    }

    @Test
    void propertyDifferenceRecursivelyCopiesNestedCollections() {
        List<Object> nestedList = new ArrayList<>(List.of("first", "second"));
        LinkedHashSet<Object> nestedSet = new LinkedHashSet<>(List.of("z", "a"));
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("list", nestedList);
        source.put("set", nestedSet);
        PropertyDifference difference = new PropertyDifference("nested", source, null, "Property differs");

        nestedList.clear();
        nestedSet.clear();
        source.clear();

        Map<?, ?> copiedMap = (Map<?, ?>) difference.sourceValue();
        List<?> copiedList = (List<?>) copiedMap.get("list");
        Set<?> copiedSet = (Set<?>) copiedMap.get("set");
        assertEquals(List.of("list", "set"), new ArrayList<>(copiedMap.keySet()));
        assertEquals(List.of("first", "second"), copiedList);
        assertEquals(List.of("z", "a"), new ArrayList<>(copiedSet));
        assertThrows(UnsupportedOperationException.class, copiedMap::clear);
        assertThrows(UnsupportedOperationException.class, copiedList::clear);
        assertThrows(UnsupportedOperationException.class, copiedSet::clear);
    }

    @Test
    void propertyDifferenceCopiesTopLevelListSetAndMapInIterationOrder() {
        List<Object> sourceList = new ArrayList<>(List.of("b", "a"));
        LinkedHashSet<Object> targetSet = new LinkedHashSet<>(List.of("second", "first"));
        LinkedHashMap<String, Object> targetMap = new LinkedHashMap<>();
        targetMap.put("z", 1);
        targetMap.put("a", 2);

        PropertyDifference listDifference = new PropertyDifference(
                "list", sourceList, targetSet, "Property differs");
        PropertyDifference mapDifference = new PropertyDifference(
                "map", null, targetMap, "Property differs");
        sourceList.clear();
        targetSet.clear();
        targetMap.clear();

        assertEquals(List.of("b", "a"), listDifference.sourceValue());
        assertEquals(List.of("second", "first"),
                new ArrayList<>((Set<?>) listDifference.targetValue()));
        assertEquals(List.of("z", "a"),
                new ArrayList<>(((Map<?, ?>) mapDifference.targetValue()).keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) listDifference.sourceValue()).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((Set<?>) listDifference.targetValue()).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<?, ?>) mapDifference.targetValue()).clear());
    }

    @Test
    void propertyDifferenceAllowsVerifiedImmutableProjectRecords() {
        ObjectKey immutableKey = key(ObjectType.TABLE, "orders");

        PropertyDifference difference = new PropertyDifference(
                "object", immutableKey, type("bigint"), "Property differs");

        assertSame(immutableKey, difference.sourceValue());
        assertEquals(type("bigint"), difference.targetValue());
    }

    @Test
    void propertyDifferenceRejectsArraysAndUnknownMutableReferencesWithFixedMessage() {
        List<Object> rejected = List.of(
                new String[]{"secret-array"},
                new java.util.Date(),
                new StringBuilder("secret-builder"),
                new Object());

        for (Object value : rejected) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new PropertyDifference("value", value, null, "Property differs"));
            assertEquals(INVALID_PROPERTY_VALUE_MESSAGE, failure.getMessage());
            assertFalse(failure.getMessage().contains("secret"));
        }
    }

    @Test
    void propertyDifferenceAllowsOnlyExactImmutableNumberClasses() {
        List<Number> values = List.of(
                Byte.valueOf((byte) 1),
                Short.valueOf((short) 2),
                Integer.valueOf(3),
                Long.valueOf(4L),
                Float.valueOf(5.5f),
                Double.valueOf(6.5d),
                new java.math.BigInteger("7"),
                new java.math.BigDecimal("8.25"));

        PropertyDifference difference = new PropertyDifference(
                "numbers", values, null, "Property differs");

        assertEquals(values, difference.sourceValue());
    }

    @Test
    void propertyDifferenceRejectsAtomicIntegerInsteadOfRetainingItsMutableReference() {
        java.util.concurrent.atomic.AtomicInteger value = new java.util.concurrent.atomic.AtomicInteger(1);

        assertMutableNumberRejected(value, () -> value.set(2));
    }

    @Test
    void propertyDifferenceRejectsAtomicLongAndLongAdder() {
        java.util.concurrent.atomic.AtomicLong atomicLong = new java.util.concurrent.atomic.AtomicLong(1L);
        java.util.concurrent.atomic.LongAdder longAdder = new java.util.concurrent.atomic.LongAdder();
        longAdder.add(1L);

        assertMutableNumberRejected(atomicLong, () -> atomicLong.set(2L));
        assertMutableNumberRejected(longAdder, () -> longAdder.add(1L));
    }

    @Test
    void propertyDifferenceRejectsCustomMutableNumber() {
        MutableNumber value = new MutableNumber(1);

        assertMutableNumberRejected(value, () -> value.set(2));
    }

    @Test
    void changedDefinitionPropertyStoresOnlySha256Digests() {
        String sourceSql = "select source_secret from credentials";
        String targetSql = "select target_secret from credentials";
        ObjectKey viewKey = key(ObjectType.VIEW, "secure_view");
        DefinitionObject source = definition(viewKey, sourceSql, "original-source-secret", DefinitionConfidence.HIGH);
        DefinitionObject target = definition(viewKey, targetSql, "original-target-secret", DefinitionConfidence.HIGH);

        PropertyDifference property = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences()).properties().getFirst();

        assertEquals("normalizedDefinition", property.path());
        assertTrue(((String) property.sourceValue()).matches("sha256:[0-9a-f]{64}"));
        assertTrue(((String) property.targetValue()).matches("sha256:[0-9a-f]{64}"));
        assertFalse(((String) property.sourceValue()).contains(sourceSql));
        assertFalse(((String) property.targetValue()).contains(targetSql));
    }

    @Test
    void publicDiffRecordSummariesNeverRenderSnapshotsObjectsPropertyValuesOrSecrets() {
        String sourceConnection = "jdbc:postgresql://alice:source-password@example.test/source";
        String targetConnection = "jdbc:postgresql://bob:target-password@example.test/target";
        String sourceDefault = "source-default-secret";
        String targetDefault = "target-default-secret";
        String sourceNormalized = "select source-normalized-secret";
        String targetNormalized = "select target-normalized-secret";
        String sourceOriginal = "select source-original-secret";
        String targetOriginal = "select target-original-secret";
        ObjectKey tableKey = key(ObjectType.TABLE, "secret-table-name");
        ObjectKey viewKey = key(ObjectType.VIEW, "secret-view-name");
        TableDefinition sourceTable = table(tableKey,
                List.of(column("token", type("varchar"), true, sourceDefault, 1)), List.of(), List.of(), Set.of());
        TableDefinition targetTable = table(tableKey,
                List.of(column("token", type("varchar"), true, targetDefault, 1)), List.of(), List.of(), Set.of());
        DefinitionObject sourceView = definition(
                viewKey, sourceNormalized, sourceOriginal, DefinitionConfidence.HIGH);
        DefinitionObject targetView = definition(
                viewKey, targetNormalized, targetOriginal, DefinitionConfidence.HIGH);
        SchemaSnapshot source = snapshot(DbType.POSTGRESQL, sourceConnection, complete(), sourceTable, sourceView);
        SchemaSnapshot target = snapshot(DbType.POSTGRESQL, targetConnection, complete(), targetTable, targetView);

        SchemaDiffResult result = engine.compare(source, target);
        RenameSuggestion rename = new RenameSuggestion(
                key(ObjectType.VIEW, sourceConnection), key(ObjectType.VIEW, targetConnection), 1.0,
                sourceOriginal);
        StringBuilder summaries = new StringBuilder(result.toString()).append('\n').append(rename);
        result.differences().forEach(difference -> {
            summaries.append('\n').append(difference);
            difference.properties().forEach(property -> summaries.append('\n').append(property));
        });
        String rendered = summaries.toString();

        assertSame(source, result.source());
        assertSame(target, result.target());
        for (String secret : List.of(sourceConnection, targetConnection, sourceDefault, targetDefault,
                sourceNormalized, targetNormalized, sourceOriginal, targetOriginal,
                "secret-table-name", "secret-view-name")) {
            assertFalse(rendered.contains(secret), () -> "summary leaked: " + secret);
        }
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> engine.compare(source,
                        snapshot(DbType.ORACLE, targetConnection, complete(), targetTable, targetView)));
        assertFalse(mismatch.getMessage().contains(sourceConnection));
        assertFalse(mismatch.getMessage().contains(targetConnection));
        assertFalse(mismatch.getMessage().contains(sourceNormalized));
        assertFalse(mismatch.getMessage().contains(targetOriginal));
    }

    private static SnapshotCompleteness complete() {
        return new SnapshotCompleteness(true, new TreeMap<>());
    }

    private static SchemaSnapshot snapshot(DbType type, SnapshotCompleteness completeness, SchemaObject... objects) {
        return snapshot(type, "connection", completeness, objects);
    }

    private static SchemaSnapshot snapshot(DbType type, String connectionId,
                                           SnapshotCompleteness completeness, SchemaObject... objects) {
        TreeMap<ObjectKey, SchemaObject> values = new TreeMap<>();
        for (SchemaObject object : objects) values.put(object.key(), object);
        return snapshot(type, connectionId, completeness, values);
    }

    private static SchemaSnapshot snapshot(DbType type, SnapshotCompleteness completeness,
                                           SortedMap<ObjectKey, SchemaObject> objects) {
        return snapshot(type, "connection", completeness, objects);
    }

    private static SchemaSnapshot snapshot(DbType type, String connectionId, SnapshotCompleteness completeness,
                                           SortedMap<ObjectKey, SchemaObject> objects) {
        return new SchemaSnapshot(type, connectionId, name("public"), Instant.EPOCH, completeness, objects, "fp");
    }

    private static TableDefinition table(ObjectKey key, List<ColumnDefinition> columns,
                                         List<ConstraintDefinition> constraints, List<IndexDefinition> indexes,
                                         Set<ObjectKey> dependencies) {
        return new TableDefinition(key, columns, constraints, indexes, dependencies);
    }

    private static ColumnDefinition column(String name, CanonicalDataType type, boolean nullable,
                                           String normalizedDefault, int ordinal) {
        return new ColumnDefinition(name(name), type, nullable, normalizedDefault, ordinal, null);
    }

    private static CanonicalDataType type(String baseType) {
        return new CanonicalDataType(baseType, null, null, null, false, 0, new TreeMap<>());
    }

    private static ConstraintDefinition constraint(String name, ConstraintKind kind, List<QualifiedName> columns,
                                                   boolean providerGenerated, Set<ObjectKey> dependencies) {
        ObjectType type = switch (kind) {
            case PRIMARY_KEY -> ObjectType.PRIMARY_KEY;
            case UNIQUE -> ObjectType.UNIQUE_CONSTRAINT;
            case FOREIGN_KEY -> ObjectType.FOREIGN_KEY;
            case CHECK -> ObjectType.CHECK_CONSTRAINT;
        };
        return new ConstraintDefinition(key(type, name), kind, columns, null, List.of(), null, null, null,
                providerGenerated, dependencies);
    }

    private static IndexDefinition index(String name, boolean unique, List<String> expressions, String predicate,
                                         boolean providerGenerated, Set<ObjectKey> dependencies) {
        return new IndexDefinition(key(ObjectType.INDEX, name), unique, expressions, predicate, providerGenerated,
                dependencies);
    }

    private static DefinitionObject definition(ObjectKey key, String normalized, String original,
                                               DefinitionConfidence confidence) {
        return new DefinitionObject(key, normalized, original, Set.of(), confidence);
    }

    private static ObjectKey key(ObjectType type, String value) {
        return key(type, value, "");
    }

    private static ObjectKey key(ObjectType type, String value, String signature) {
        return new ObjectKey(type, name(value), signature);
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static <T> T only(List<T> values) {
        assertEquals(1, values.size());
        return values.getFirst();
    }

    private static void assertMutableNumberRejected(Number value, Runnable mutation) {
        try {
            new PropertyDifference("number", value, null, "Property differs");
            long before = value.longValue();
            mutation.run();
            assertNotEquals(before, value.longValue());
            fail("Mutable Number was accepted");
        } catch (IllegalArgumentException failure) {
            assertEquals(INVALID_PROPERTY_VALUE_MESSAGE, failure.getMessage());
        }
    }

    private static final class MutableNumber extends Number {
        private int value;

        private MutableNumber(int value) {
            this.value = value;
        }

        private void set(int value) {
            this.value = value;
        }

        @Override
        public int intValue() {
            return value;
        }

        @Override
        public long longValue() {
            return value;
        }

        @Override
        public float floatValue() {
            return value;
        }

        @Override
        public double doubleValue() {
            return value;
        }
    }
}
