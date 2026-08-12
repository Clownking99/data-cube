package com.datacube.schemadiff;

import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaComparisonProjection;
import com.datacube.spi.schemadiff.SchemaComparisonProjector;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.TableDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Compares immutable canonical snapshots without database or SQL-dialect access. */
public final class SchemaDiffEngine {

    private static final String INVALID_OBJECTS_MESSAGE = "Schema snapshot objects are invalid";

    public SchemaDiffResult compare(SchemaSnapshot source, SchemaSnapshot target) {
        return compare(source, target, SchemaComparisonProjector.identity());
    }

    /** Explicit provider-aware comparison path; the two-argument API retains identity semantics. */
    public SchemaDiffResult compare(
            SchemaSnapshot source, SchemaSnapshot target,
            SchemaComparisonProjector projector) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(projector, "projector");
        validateObjects(source);
        validateObjects(target);
        if (source.databaseType() != target.databaseType()) {
            throw new IllegalArgumentException("Schema snapshots use different database types");
        }

        SchemaComparisonProjection sourceProjection = projector.project(source);
        SchemaComparisonProjection targetProjection = projector.project(target);
        if (sourceProjection.original() != source || targetProjection.original() != target) {
            throw new IllegalArgumentException("Schema comparison projection is invalid");
        }

        TreeSet<ObjectKey> keys = new TreeSet<>(sourceProjection.comparisonObjects().keySet());
        keys.addAll(targetProjection.comparisonObjects().keySet());
        List<SchemaDifference> differences = new ArrayList<>(keys.size());
        List<ProjectedObject> missing = new ArrayList<>();
        List<ProjectedObject> extra = new ArrayList<>();

        for (ObjectKey comparisonKey : keys) {
            SchemaObject sourceComparison = sourceProjection.comparisonObjects().get(comparisonKey);
            SchemaObject targetComparison = targetProjection.comparisonObjects().get(comparisonKey);
            SchemaObject sourceObject = sourceComparison == null
                    ? null : sourceProjection.originalObject(comparisonKey);
            SchemaObject targetObject = targetComparison == null
                    ? null : targetProjection.originalObject(comparisonKey);
            ObjectKey resultKey = sourceObject == null ? targetObject.key() : sourceObject.key();
            if (isUnavailable(source, resultKey) || isUnavailable(target, resultKey)) {
                differences.add(unsupported(resultKey, sourceObject, targetObject));
            } else if (sourceComparison == null) {
                differences.add(extra(resultKey, targetObject));
                extra.add(new ProjectedObject(targetComparison, targetObject));
            } else if (targetComparison == null) {
                differences.add(missing(resultKey, sourceObject));
                missing.add(new ProjectedObject(sourceComparison, sourceObject));
            } else {
                differences.add(compareMatched(resultKey, sourceComparison, targetComparison,
                        sourceObject, targetObject));
            }
        }

        return new SchemaDiffResult(source, target, differences, renameSuggestions(missing, extra));
    }

    private static void validateObjects(SchemaSnapshot snapshot) {
        for (Map.Entry<ObjectKey, SchemaObject> entry : snapshot.objects().entrySet()) {
            ObjectKey mapKey = entry.getKey();
            SchemaObject value = entry.getValue();
            if (mapKey == null || value == null || value.key() == null || !mapKey.equals(value.key())) {
                throw new IllegalArgumentException(INVALID_OBJECTS_MESSAGE);
            }
        }
    }

    private static boolean isUnavailable(SchemaSnapshot snapshot, ObjectKey key) {
        return snapshot.completeness().unavailableScopes().containsKey(key.type());
    }

    private static SchemaDifference unsupported(
            ObjectKey key, SchemaObject source, SchemaObject target) {
        return new SchemaDifference(DifferenceKind.UNSUPPORTED, key, source, target, List.of(),
                RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, dependencies(source, target),
                "Metadata for this object type is unavailable in at least one snapshot");
    }

    private static SchemaDifference missing(ObjectKey key, SchemaObject source) {
        boolean highConfidence = isHighConfidence(source);
        return new SchemaDifference(DifferenceKind.MISSING_IN_TARGET, key, source, null, List.of(),
                highConfidence ? RiskLevel.LOW : RiskLevel.HIGH,
                highConfidence ? AutomationLevel.SAFE_AUTOMATIC : AutomationLevel.MANUAL_ONLY,
                source.dependencies(), "Object is missing from the target snapshot");
    }

    private static SchemaDifference extra(ObjectKey key, SchemaObject target) {
        return new SchemaDifference(DifferenceKind.EXTRA_IN_TARGET, key, null, target, List.of(),
                RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN, target.dependencies(),
                "Object exists only in the target snapshot");
    }

    private static boolean isHighConfidence(SchemaObject object) {
        return !(object instanceof DefinitionObject definition)
                || definition.confidence() == DefinitionConfidence.HIGH;
    }

    private static SchemaDifference compareMatched(
            ObjectKey key, SchemaObject sourceComparison, SchemaObject targetComparison,
            SchemaObject source, SchemaObject target) {
        Comparison projected = compareObjects(sourceComparison, targetComparison);
        List<PropertyDifference> properties = rehydrateProperties(
                projected.properties(), sourceComparison, targetComparison, source, target);
        if (properties.isEmpty() && projected.reliable()) {
            return new SchemaDifference(DifferenceKind.EQUIVALENT, key, source, target, List.of(),
                    RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, dependencies(source, target),
                    "Objects are semantically equivalent");
        }

        AutomationLevel automation;
        RiskLevel risk = RiskLevel.HIGH;
        if (!projected.reliable() || changedLowConfidenceDefinition(source, target)) {
            automation = AutomationLevel.MANUAL_ONLY;
        } else if (isSafeNullableColumnAddition(source, target, properties)) {
            risk = RiskLevel.LOW;
            automation = AutomationLevel.SAFE_AUTOMATIC;
        } else {
            automation = AutomationLevel.DESTRUCTIVE_OPT_IN;
        }
        return new SchemaDifference(DifferenceKind.MODIFIED, key, source, target, properties,
                risk, automation, dependencies(source, target), "Object definitions differ");
    }

    private static List<PropertyDifference> rehydrateProperties(
            List<PropertyDifference> projected,
            SchemaObject sourceComparison, SchemaObject targetComparison,
            SchemaObject source, SchemaObject target) {
        List<PropertyDifference> properties = new ArrayList<>(projected.size());
        for (PropertyDifference property : projected) {
            properties.add(new PropertyDifference(property.path(),
                    originalValue(source, sourceComparison, property.path(), property.sourceValue()),
                    originalValue(target, targetComparison, property.path(), property.targetValue()),
                    property.explanation()));
        }
        return List.copyOf(properties);
    }

    private static Object originalValue(
            SchemaObject original, SchemaObject comparison, String path, Object fallback) {
        if (original == null || comparison == null || fallback == null) return fallback;
        if (path.equals("objectKey")) return original.key();
        if (path.equals("dependencies")) return original.dependencies();
        if (original instanceof TableDefinition originalTable
                && comparison instanceof TableDefinition comparisonTable) {
            if (path.equals("constraints")) return originalTable.constraints();
            if (path.equals("indexes")) return originalTable.indexes();
            for (int index = 0; index < comparisonTable.columns().size(); index++) {
                ColumnDefinition projectedColumn = comparisonTable.columns().get(index);
                String prefix = "columns[" + projectedColumn.name().comparisonKey() + "]";
                if (!path.equals(prefix) && !path.startsWith(prefix + ".")) continue;
                if (index >= originalTable.columns().size()) return fallback;
                ColumnDefinition originalColumn = originalTable.columns().get(index);
                if (path.equals(prefix)) return originalColumn;
                return switch (path.substring(prefix.length() + 1)) {
                    case "dataType" -> originalColumn.dataType();
                    case "nullable" -> originalColumn.nullable();
                    case "normalizedDefault" -> originalColumn.normalizedDefault();
                    case "ordinal" -> originalColumn.ordinal();
                    case "comment" -> originalColumn.comment();
                    default -> fallback;
                };
            }
        }
        if (original instanceof SequenceDefinition sequence) {
            return switch (path) {
                case "startValue" -> sequence.startValue();
                case "incrementBy" -> sequence.incrementBy();
                case "minimumValue" -> sequence.minimumValue();
                case "maximumValue" -> sequence.maximumValue();
                case "cycle" -> sequence.cycle();
                case "cacheSize" -> sequence.cacheSize();
                case "providerExtensions" -> sequence.providerExtensions();
                default -> fallback;
            };
        }
        return fallback;
    }

    private static boolean changedLowConfidenceDefinition(SchemaObject source, SchemaObject target) {
        return source instanceof DefinitionObject sourceDefinition
                && target instanceof DefinitionObject targetDefinition
                && (sourceDefinition.confidence() == DefinitionConfidence.LOW
                || targetDefinition.confidence() == DefinitionConfidence.LOW);
    }

    private static boolean isSafeNullableColumnAddition(
            SchemaObject source, SchemaObject target, List<PropertyDifference> properties) {
        if (!(source instanceof TableDefinition) || !(target instanceof TableDefinition)
                || properties.size() != 1) {
            return false;
        }
        PropertyDifference property = properties.getFirst();
        return property.path().startsWith("columns[")
                && property.path().endsWith("]")
                && property.sourceValue() instanceof ColumnDefinition column
                && property.targetValue() == null
                && column.nullable()
                && column.normalizedDefault() == null;
    }

    private static Comparison compareObjects(SchemaObject source, SchemaObject target) {
        if (source.getClass() != target.getClass()) {
            return new Comparison(List.of(property("objectShape", source.getClass().getSimpleName(),
                    target.getClass().getSimpleName())), false);
        }
        if (!source.key().equals(target.key())) {
            return new Comparison(List.of(property("objectKey", source.key(), target.key())), false);
        }
        if (source instanceof TableDefinition sourceTable && target instanceof TableDefinition targetTable) {
            return compareTables(sourceTable, targetTable);
        }
        if (source instanceof SequenceDefinition sourceSequence && target instanceof SequenceDefinition targetSequence) {
            return compareSequences(sourceSequence, targetSequence);
        }
        if (source instanceof DefinitionObject sourceDefinition && target instanceof DefinitionObject targetDefinition) {
            return compareDefinitions(sourceDefinition, targetDefinition);
        }
        return new Comparison(List.of(property("objectShape", source.getClass().getSimpleName(),
                target.getClass().getSimpleName())), false);
    }

    private static Comparison compareTables(TableDefinition source, TableDefinition target) {
        List<PropertyDifference> properties = new ArrayList<>();
        boolean reliable = compareColumns(source.columns(), target.columns(), properties);
        if (!constraintMultiset(source.constraints(), source.key(), false)
                .equals(constraintMultiset(target.constraints(), target.key(), false))) {
            properties.add(property("constraints", source.constraints(), target.constraints()));
        }
        if (!indexMultiset(source.indexes(), source.key(), false)
                .equals(indexMultiset(target.indexes(), target.key(), false))) {
            properties.add(property("indexes", source.indexes(), target.indexes()));
        }
        addIfDifferent(properties, "dependencies", source.dependencies(), target.dependencies());
        return new Comparison(properties, reliable);
    }

    private static boolean compareColumns(
            List<ColumnDefinition> sourceColumns, List<ColumnDefinition> targetColumns,
            List<PropertyDifference> properties) {
        Map<QualifiedName, ColumnDefinition> sourceByName = new TreeMap<>();
        Map<QualifiedName, ColumnDefinition> targetByName = new TreeMap<>();
        boolean reliable = putUniqueColumns(sourceColumns, sourceByName)
                & putUniqueColumns(targetColumns, targetByName);
        if (!reliable) {
            properties.add(property("columns", sourceColumns, targetColumns));
            return false;
        }

        TreeSet<QualifiedName> names = new TreeSet<>(sourceByName.keySet());
        names.addAll(targetByName.keySet());
        for (QualifiedName name : names) {
            ColumnDefinition source = sourceByName.get(name);
            ColumnDefinition target = targetByName.get(name);
            String prefix = "columns[" + name.comparisonKey() + "]";
            if (source == null || target == null) {
                properties.add(property(prefix, source, target));
                continue;
            }
            addIfDifferent(properties, prefix + ".dataType", source.dataType(), target.dataType());
            addIfDifferent(properties, prefix + ".nullable", source.nullable(), target.nullable());
            addIfDifferent(properties, prefix + ".normalizedDefault",
                    source.normalizedDefault(), target.normalizedDefault());
            addIfDifferent(properties, prefix + ".ordinal", source.ordinal(), target.ordinal());
            addIfDifferent(properties, prefix + ".comment", source.comment(), target.comment());
        }
        return true;
    }

    private static boolean putUniqueColumns(
            List<ColumnDefinition> columns, Map<QualifiedName, ColumnDefinition> byName) {
        boolean unique = true;
        for (ColumnDefinition column : columns) {
            if (byName.put(column.name(), column) != null) unique = false;
        }
        return unique;
    }

    private static Comparison compareSequences(SequenceDefinition source, SequenceDefinition target) {
        List<PropertyDifference> properties = new ArrayList<>();
        addIfDifferent(properties, "startValue", source.startValue(), target.startValue());
        addIfDifferent(properties, "incrementBy", source.incrementBy(), target.incrementBy());
        addIfDifferent(properties, "minimumValue", source.minimumValue(), target.minimumValue());
        addIfDifferent(properties, "maximumValue", source.maximumValue(), target.maximumValue());
        addIfDifferent(properties, "cycle", source.cycle(), target.cycle());
        addIfDifferent(properties, "cacheSize", source.cacheSize(), target.cacheSize());
        addIfDifferent(properties, "providerExtensions",
                source.providerExtensions(), target.providerExtensions());
        addIfDifferent(properties, "dependencies", source.dependencies(), target.dependencies());
        return new Comparison(properties, true);
    }

    private static Comparison compareDefinitions(DefinitionObject source, DefinitionObject target) {
        List<PropertyDifference> properties = new ArrayList<>();
        if (!Objects.equals(source.normalizedDefinition(), target.normalizedDefinition())) {
            properties.add(property("normalizedDefinition",
                    definitionDigest(source.normalizedDefinition()),
                    definitionDigest(target.normalizedDefinition())));
        }
        addIfDifferent(properties, "dependencies", source.dependencies(), target.dependencies());
        return new Comparison(properties, true);
    }

    private static String definitionDigest(String definition) {
        if (definition == null) return null;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(definition.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("sha256:");
            for (byte element : hash) value.append(String.format("%02x", element));
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Definition digest is unavailable", exception);
        }
    }

    private static void addIfDifferent(
            List<PropertyDifference> properties, String path, Object source, Object target) {
        if (!Objects.equals(source, target)) properties.add(property(path, source, target));
    }

    private static PropertyDifference property(String path, Object source, Object target) {
        return new PropertyDifference(path, source, target, "Property values differ");
    }

    private static Set<ObjectKey> dependencies(SchemaObject source, SchemaObject target) {
        TreeSet<ObjectKey> dependencies = new TreeSet<>();
        if (source != null) dependencies.addAll(source.dependencies());
        if (target != null) dependencies.addAll(target.dependencies());
        return dependencies;
    }

    private static List<RenameSuggestion> renameSuggestions(
            List<ProjectedObject> missing, List<ProjectedObject> extra) {
        Map<ObjectKey, List<ObjectKey>> targetsBySource = new TreeMap<>();
        Map<ObjectKey, List<ObjectKey>> sourcesByTarget = new TreeMap<>();
        Map<ObjectKey, ProjectedObject> missingByKey = new TreeMap<>();
        Map<ObjectKey, ProjectedObject> extraByKey = new TreeMap<>();
        missing.forEach(object -> missingByKey.put(object.original().key(), object));
        extra.forEach(object -> extraByKey.put(object.original().key(), object));

        for (ProjectedObject source : missingByKey.values()) {
            for (ProjectedObject target : extraByKey.values()) {
                if (semanticallyIdenticalRename(source.comparison(), target.comparison())) {
                    targetsBySource.computeIfAbsent(source.original().key(), ignored -> new ArrayList<>())
                            .add(target.original().key());
                    sourcesByTarget.computeIfAbsent(target.original().key(), ignored -> new ArrayList<>())
                            .add(source.original().key());
                }
            }
        }

        List<RenameSuggestion> suggestions = new ArrayList<>();
        for (Map.Entry<ObjectKey, List<ObjectKey>> entry : targetsBySource.entrySet()) {
            if (entry.getValue().size() != 1) continue;
            ObjectKey target = entry.getValue().getFirst();
            if (sourcesByTarget.getOrDefault(target, List.of()).size() != 1) continue;
            suggestions.add(new RenameSuggestion(entry.getKey(), target, 1.0,
                    "Objects have identical semantic structure and may represent a rename"));
        }
        return suggestions;
    }

    private static boolean semanticallyIdenticalRename(SchemaObject source, SchemaObject target) {
        if (source.getClass() != target.getClass()
                || source.key().type() != target.key().type()
                || !source.key().signature().equals(target.key().signature())) {
            return false;
        }
        if (source instanceof TableDefinition sourceTable && target instanceof TableDefinition targetTable) {
            List<PropertyDifference> columnDifferences = new ArrayList<>();
            return compareColumns(sourceTable.columns(), targetTable.columns(), columnDifferences)
                    && columnDifferences.isEmpty()
                    && constraintMultiset(sourceTable.constraints(), sourceTable.key(), true)
                    .equals(constraintMultiset(targetTable.constraints(), targetTable.key(), true))
                    && indexMultiset(sourceTable.indexes(), sourceTable.key(), true)
                    .equals(indexMultiset(targetTable.indexes(), targetTable.key(), true))
                    && withoutSelf(sourceTable.dependencies(), sourceTable.key())
                    .equals(withoutSelf(targetTable.dependencies(), targetTable.key()));
        }
        if (source instanceof SequenceDefinition sourceSequence && target instanceof SequenceDefinition targetSequence) {
            return Objects.equals(sourceSequence.startValue(), targetSequence.startValue())
                    && Objects.equals(sourceSequence.incrementBy(), targetSequence.incrementBy())
                    && Objects.equals(sourceSequence.minimumValue(), targetSequence.minimumValue())
                    && Objects.equals(sourceSequence.maximumValue(), targetSequence.maximumValue())
                    && sourceSequence.cycle() == targetSequence.cycle()
                    && Objects.equals(sourceSequence.cacheSize(), targetSequence.cacheSize())
                    && Objects.equals(sourceSequence.providerExtensions(), targetSequence.providerExtensions())
                    && withoutSelf(sourceSequence.dependencies(), sourceSequence.key())
                    .equals(withoutSelf(targetSequence.dependencies(), targetSequence.key()));
        }
        if (source instanceof DefinitionObject sourceDefinition && target instanceof DefinitionObject targetDefinition) {
            String sourceText = sourceDefinition.normalizedDefinition();
            String targetText = targetDefinition.normalizedDefinition();
            return sourceDefinition.confidence() == DefinitionConfidence.HIGH
                    && targetDefinition.confidence() == DefinitionConfidence.HIGH
                    && sourceText != null && !sourceText.isBlank()
                    && targetText != null && !targetText.isBlank()
                    && sourceText.equals(targetText)
                    && withoutSelf(sourceDefinition.dependencies(), sourceDefinition.key())
                    .equals(withoutSelf(targetDefinition.dependencies(), targetDefinition.key()));
        }
        return false;
    }

    private static Set<ObjectKey> withoutSelf(Set<ObjectKey> dependencies, ObjectKey self) {
        TreeSet<ObjectKey> normalized = new TreeSet<>(dependencies);
        normalized.remove(self);
        return normalized;
    }

    private static Map<ConstraintSemantic, Integer> constraintMultiset(
            List<ConstraintDefinition> constraints, ObjectKey self, boolean normalizeSelf) {
        Map<ConstraintSemantic, Integer> values = new HashMap<>();
        for (ConstraintDefinition constraint : constraints) {
            ObjectKey semanticKey = constraint.providerGeneratedName() ? null : constraint.key();
            ObjectKey referencedTable = normalizeSelf && Objects.equals(constraint.referencedTable(), self)
                    ? null : constraint.referencedTable();
            ConstraintSemantic semantic = new ConstraintSemantic(
                    semanticKey, constraint.kind(), constraint.columns(), referencedTable,
                    constraint.referencedColumns(), constraint.normalizedExpression(), constraint.updateAction(),
                    constraint.deleteAction(), constraint.providerGeneratedName(),
                    normalizedDependencies(constraint.dependencies(), self, normalizeSelf));
            values.merge(semantic, 1, Integer::sum);
        }
        return values;
    }

    private static Map<IndexSemantic, Integer> indexMultiset(
            List<IndexDefinition> indexes, ObjectKey self, boolean normalizeSelf) {
        Map<IndexSemantic, Integer> values = new LinkedHashMap<>();
        for (IndexDefinition index : indexes) {
            ObjectKey semanticKey = index.providerGeneratedName() ? null : index.key();
            IndexSemantic semantic = new IndexSemantic(semanticKey, index.unique(), index.normalizedExpressions(),
                    index.normalizedPredicate(), index.providerGeneratedName(),
                    normalizedDependencies(index.dependencies(), self, normalizeSelf));
            values.merge(semantic, 1, Integer::sum);
        }
        return values;
    }

    private static Set<ObjectKey> normalizedDependencies(
            Set<ObjectKey> dependencies, ObjectKey self, boolean normalizeSelf) {
        return normalizeSelf ? withoutSelf(dependencies, self) : new TreeSet<>(dependencies);
    }

    private record Comparison(List<PropertyDifference> properties, boolean reliable) {
        private Comparison {
            properties = List.copyOf(properties);
        }
    }

    private record ConstraintSemantic(
            ObjectKey key, ConstraintKind kind, List<QualifiedName> columns,
            ObjectKey referencedTable, List<QualifiedName> referencedColumns,
            String normalizedExpression, String updateAction, String deleteAction,
            boolean providerGeneratedName, Set<ObjectKey> dependencies) {
    }

    private record IndexSemantic(
            ObjectKey key, boolean unique, List<String> normalizedExpressions,
            String normalizedPredicate, boolean providerGeneratedName,
            Set<ObjectKey> dependencies) {
    }

    private record ProjectedObject(SchemaObject comparison, SchemaObject original) {
    }
}
