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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SchemaDiffEngineTest {

    private final SchemaDiffEngine engine = new SchemaDiffEngine();

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
                Set.of(dependency));
        SequenceDefinition target = new SequenceDefinition(sequenceKey, "10", "2", "0", "999", true, 50,
                Set.of());

        SchemaDifference difference = only(engine.compare(
                snapshot(DbType.POSTGRESQL, complete(), source),
                snapshot(DbType.POSTGRESQL, complete(), target)).differences());

        assertEquals(DifferenceKind.MODIFIED, difference.kind());
        assertEquals(Set.of("startValue", "incrementBy", "minimumValue", "maximumValue", "cycle", "cacheSize",
                        "dependencies"),
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

    private static SnapshotCompleteness complete() {
        return new SnapshotCompleteness(true, new TreeMap<>());
    }

    private static SchemaSnapshot snapshot(DbType type, SnapshotCompleteness completeness, SchemaObject... objects) {
        TreeMap<ObjectKey, SchemaObject> values = new TreeMap<>();
        for (SchemaObject object : objects) values.put(object.key(), object);
        return new SchemaSnapshot(type, "connection", name("public"), Instant.EPOCH, completeness, values, "fp");
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
}
