package com.datacube.schemadiff;

import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.IndexDefinition;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/** Builds deterministic, provider-neutral changes from semantic schema differences. */
public final class SchemaChangePlanner {
    private static final String UNKNOWN_SELECTION_MESSAGE = "Selected change IDs are invalid";
    private static final String OBJECT_PATH = "$object";

    public SchemaChangePlan plan(SchemaDiffResult result) {
        Objects.requireNonNull(result, "result");
        List<SchemaChange> changes = new ArrayList<>();
        Map<String, Set<ObjectKey>> objectDependencies = new HashMap<>();
        for (SchemaDifference difference : result.differences()) {
            for (SchemaChange change : changesFor(difference)) {
                changes.add(change);
                objectDependencies.put(change.id(), difference.dependencies());
            }
        }
        changes = wireDependencies(changes, objectDependencies);
        CycleRewrite cycleRewrite = rewriteCyclesAsManual(changes);
        changes = topologicalOrder(cycleRewrite.changes(), cycleRewrite.cycleIds());

        Set<String> selected = new TreeSet<>();
        for (SchemaChange change : changes) {
            if (change.selectedByDefault()) selected.add(change.id());
        }
        Selection closed = dependencyClosure(changes, selected);
        return new SchemaChangePlan(result, changes, closed.selected(), closed.blocked(),
                digest(result, closed.selected()));
    }

    public SchemaChangePlan select(SchemaChangePlan plan, Set<String> selectedChangeIds) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(selectedChangeIds, "selectedChangeIds");
        Map<String, SchemaChange> byId = new HashMap<>();
        for (SchemaChange change : plan.changes()) byId.put(change.id(), change);
        if (!byId.keySet().containsAll(selectedChangeIds)) {
            throw new IllegalArgumentException(UNKNOWN_SELECTION_MESSAGE);
        }

        Set<String> executable = new TreeSet<>();
        for (String id : selectedChangeIds) {
            SchemaChange change = byId.get(id);
            if (change.automation() != AutomationLevel.MANUAL_ONLY
                    && change.kind() != ChangeKind.MANUAL) {
                executable.add(id);
            }
        }
        Selection closed = dependencyClosure(plan.changes(), executable);
        return new SchemaChangePlan(plan.diff(), plan.changes(), closed.selected(), closed.blocked(),
                digest(plan.diff(), closed.selected()));
    }

    private static List<SchemaChange> changesFor(SchemaDifference difference) {
        return switch (difference.kind()) {
            case EQUIVALENT -> List.of();
            case UNSUPPORTED -> List.of(change(difference, null, OBJECT_PATH,
                    ChangeKind.MANUAL, RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY,
                    "Manual review is required because metadata is unavailable"));
            case MISSING_IN_TARGET -> List.of(missingChange(difference));
            case EXTRA_IN_TARGET -> List.of(change(difference, null, OBJECT_PATH,
                    ChangeKind.DROP, RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN,
                    "Dropping an object requires explicit approval"));
            case MODIFIED -> modifiedChanges(difference);
        };
    }

    private static SchemaChange missingChange(SchemaDifference difference) {
        if (isLowConfidenceDefinition(difference)) {
            return change(difference, null, OBJECT_PATH,
                    ChangeKind.MANUAL, RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY,
                    "A low-confidence definition requires manual review");
        }
        return change(difference, null, OBJECT_PATH,
                ChangeKind.CREATE, RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC,
                "The missing object can be created automatically");
    }

    private static List<SchemaChange> modifiedChanges(SchemaDifference difference) {
        if (difference.properties().isEmpty()) {
            return List.of(change(difference, null, OBJECT_PATH,
                    ChangeKind.MANUAL, RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY,
                    "The modification cannot be isolated safely"));
        }
        List<SchemaChange> changes = new ArrayList<>(difference.properties().size());
        for (PropertyDifference property : difference.properties()) {
            String path = canonicalPath(property.path());
            if (isLowConfidenceDefinition(difference)
                    || difference.automation() == AutomationLevel.MANUAL_ONLY
                    || !isIsolatable(difference, property)) {
                changes.add(change(difference, property, path,
                        ChangeKind.MANUAL, RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY,
                        "The property modification requires manual review"));
            } else if (difference.source() instanceof DefinitionObject
                    && difference.target() instanceof DefinitionObject) {
                changes.add(change(difference, property, path,
                        ChangeKind.REPLACE, RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN,
                        "Replacing a programmable definition requires explicit approval"));
            } else if (isSafeNullableColumnAddition(property)) {
                changes.add(change(difference, property, path,
                        ChangeKind.ALTER, RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC,
                        "Adding a nullable column without a default is safe to automate"));
            } else {
                changes.add(change(difference, property, path,
                        ChangeKind.ALTER, RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN,
                        "The structural modification requires explicit approval"));
            }
        }
        return changes;
    }

    private static boolean isLowConfidenceDefinition(SchemaDifference difference) {
        return difference.source() instanceof DefinitionObject source
                && source.confidence() == DefinitionConfidence.LOW
                || difference.target() instanceof DefinitionObject target
                && target.confidence() == DefinitionConfidence.LOW;
    }

    private static boolean isSafeNullableColumnAddition(PropertyDifference property) {
        if (!isWholeColumnPath(property.path()) || property.sourceValue() != null
                || !(property.targetValue() instanceof ColumnDefinition column) || !column.nullable()) {
            return false;
        }
        return column.normalizedDefault() == null || column.normalizedDefault().isBlank();
    }

    private static boolean isIsolatable(
            SchemaDifference difference, PropertyDifference property) {
        if (difference.source() instanceof DefinitionObject
                && difference.target() instanceof DefinitionObject) {
            return property.path().equals("normalizedDefinition")
                    && valuesAre(property, String.class, true)
                    || property.path().equals("dependencies")
                    && objectKeySets(property);
        }
        String path = property.path();
        if (isWholeColumnPath(path)) {
            return property.sourceValue() == null
                    && property.targetValue() instanceof ColumnDefinition
                    || property.sourceValue() instanceof ColumnDefinition
                    && property.targetValue() == null;
        }
        if (path.matches("columns\\[[^]\\r\\n]+]\\.dataType")) {
            return valuesAre(property, CanonicalDataType.class, false);
        }
        if (path.matches("columns\\[[^]\\r\\n]+]\\.nullable")) {
            return valuesAre(property, Boolean.class, false);
        }
        if (path.matches("columns\\[[^]\\r\\n]+]\\.(normalizedDefault|comment)")) {
            return valuesAre(property, String.class, true);
        }
        if (path.matches("columns\\[[^]\\r\\n]+]\\.ordinal")) {
            return valuesAre(property, Integer.class, false);
        }
        if (path.equals("constraints")) {
            return listsContainOnly(property, ConstraintDefinition.class);
        }
        if (path.equals("indexes")) {
            return listsContainOnly(property, IndexDefinition.class);
        }
        if (path.equals("startValue") || path.equals("incrementBy")
                || path.equals("minimumValue") || path.equals("maximumValue")) {
            return valuesAre(property, String.class, true);
        }
        if (path.equals("cycle")) return valuesAre(property, Boolean.class, false);
        if (path.equals("cacheSize")) return valuesAre(property, Integer.class, true);
        return false;
    }

    private static boolean valuesAre(
            PropertyDifference property, Class<?> type, boolean nullable) {
        return !Objects.equals(property.sourceValue(), property.targetValue())
                && valueIs(property.sourceValue(), type, nullable)
                && valueIs(property.targetValue(), type, nullable);
    }

    private static boolean valueIs(Object value, Class<?> type, boolean nullable) {
        return value == null ? nullable : type.isInstance(value);
    }

    private static boolean listsContainOnly(PropertyDifference property, Class<?> elementType) {
        if (!(property.sourceValue() instanceof List<?> source)
                || !(property.targetValue() instanceof List<?> target)
                || source.equals(target)) {
            return false;
        }
        return source.stream().allMatch(elementType::isInstance)
                && target.stream().allMatch(elementType::isInstance);
    }

    private static boolean objectKeySets(PropertyDifference property) {
        if (!(property.sourceValue() instanceof Set<?> source)
                || !(property.targetValue() instanceof Set<?> target)
                || source.equals(target)) {
            return false;
        }
        return source.stream().allMatch(ObjectKey.class::isInstance)
                && target.stream().allMatch(ObjectKey.class::isInstance);
    }

    private static boolean isWholeColumnPath(String path) {
        return path.matches("columns\\[[^]\\r\\n]+]");
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank()) return OBJECT_PATH;
        return path.strip();
    }

    private static SchemaChange change(
            SchemaDifference difference, PropertyDifference property, String path,
            ChangeKind kind, RiskLevel risk, AutomationLevel automation, String explanation) {
        return new SchemaChange(changeId(kind, difference.object(), path), kind, difference.object(),
                difference.source(), difference.target(), property, risk, automation,
                automation == AutomationLevel.SAFE_AUTOMATIC, Set.of(), explanation);
    }

    private static String changeId(ChangeKind kind, ObjectKey object, String canonicalPath) {
        String identity = kind.name() + '\0'
                + object.type().name() + '\0'
                + object.name().comparisonKey() + '\0'
                + object.signature() + '\0'
                + canonicalPath;
        return "chg:" + sha256(identity);
    }

    private static Selection dependencyClosure(List<SchemaChange> changes, Set<String> requested) {
        Map<String, SchemaChange> byId = new HashMap<>();
        for (SchemaChange change : changes) byId.put(change.id(), change);
        Set<String> selected = new TreeSet<>(requested);
        Set<String> blocked = new TreeSet<>();
        boolean changed;
        do {
            changed = false;
            for (String id : List.copyOf(selected)) {
                SchemaChange change = byId.get(id);
                if (change == null || !selected.containsAll(change.dependencyChangeIds())) {
                    selected.remove(id);
                    blocked.add(id);
                    changed = true;
                }
            }
        } while (changed);
        return new Selection(selected, blocked);
    }

    private static List<SchemaChange> wireDependencies(
            List<SchemaChange> changes, Map<String, Set<ObjectKey>> objectDependencies) {
        Map<ObjectKey, List<SchemaChange>> byObject = new HashMap<>();
        Map<String, Set<String>> dependencyIds = new HashMap<>();
        for (SchemaChange change : changes) {
            byObject.computeIfAbsent(change.object(), ignored -> new ArrayList<>()).add(change);
            dependencyIds.put(change.id(), new TreeSet<>());
        }

        for (SchemaChange change : changes) {
            for (ObjectKey dependencyObject : objectDependencies.getOrDefault(change.id(), Set.of())) {
                List<SchemaChange> dependencyChanges = byObject.getOrDefault(dependencyObject, List.of());
                if (change.kind() == ChangeKind.DROP) {
                    for (SchemaChange dependencyChange : dependencyChanges) {
                        if (dependencyChange.kind() == ChangeKind.DROP) {
                            dependencyIds.get(dependencyChange.id()).add(change.id());
                        }
                    }
                } else {
                    for (SchemaChange dependencyChange : dependencyChanges) {
                        if (dependencyChange.kind() != ChangeKind.DROP) {
                            dependencyIds.get(change.id()).add(dependencyChange.id());
                        }
                    }
                }
            }
        }

        List<SchemaChange> wired = new ArrayList<>(changes.size());
        for (SchemaChange change : changes) {
            wired.add(copy(change, change.id(), change.kind(), change.risk(), change.automation(),
                    change.selectedByDefault(), dependencyIds.get(change.id()), change.explanation()));
        }
        return wired;
    }

    private static CycleRewrite rewriteCyclesAsManual(List<SchemaChange> changes) {
        Set<String> cycleIds = cycleIds(changes);
        if (cycleIds.isEmpty()) return new CycleRewrite(changes, Set.of());

        Map<String, String> rewrittenIds = new HashMap<>();
        for (SchemaChange change : changes) {
            String rewrittenId = cycleIds.contains(change.id())
                    ? changeId(ChangeKind.MANUAL, change.object(), propertyPath(change))
                    : change.id();
            rewrittenIds.put(change.id(), rewrittenId);
        }

        List<SchemaChange> rewritten = new ArrayList<>(changes.size());
        Set<String> rewrittenCycleIds = new HashSet<>();
        for (SchemaChange change : changes) {
            Set<String> dependencies = new TreeSet<>();
            for (String dependency : change.dependencyChangeIds()) {
                dependencies.add(rewrittenIds.getOrDefault(dependency, dependency));
            }
            if (cycleIds.contains(change.id())) {
                String id = rewrittenIds.get(change.id());
                rewrittenCycleIds.add(id);
                RiskLevel risk = change.risk().ordinal() < RiskLevel.HIGH.ordinal()
                        ? RiskLevel.HIGH : change.risk();
                rewritten.add(copy(change, id, ChangeKind.MANUAL, risk, AutomationLevel.MANUAL_ONLY,
                        false, dependencies, "A dependency cycle requires manual review"));
            } else {
                rewritten.add(copy(change, rewrittenIds.get(change.id()), change.kind(), change.risk(),
                        change.automation(), change.selectedByDefault(), dependencies, change.explanation()));
            }
        }
        return new CycleRewrite(rewritten, rewrittenCycleIds);
    }

    private static Set<String> cycleIds(List<SchemaChange> changes) {
        Map<String, SchemaChange> byId = new LinkedHashMap<>();
        for (SchemaChange change : changes) byId.put(change.id(), change);
        Tarjan tarjan = new Tarjan(byId);
        return tarjan.findCycleIds();
    }

    private static List<SchemaChange> topologicalOrder(
            List<SchemaChange> changes, Set<String> cycleIds) {
        Map<String, SchemaChange> byId = new HashMap<>();
        Map<String, Integer> indegrees = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (SchemaChange change : changes) {
            byId.put(change.id(), change);
            indegrees.put(change.id(), 0);
        }
        for (SchemaChange change : changes) {
            for (String dependency : change.dependencyChangeIds()) {
                if (!byId.containsKey(dependency)
                        || cycleIds.contains(change.id()) && cycleIds.contains(dependency)) {
                    continue;
                }
                indegrees.compute(change.id(), (ignored, value) -> Objects.requireNonNull(value) + 1);
                dependents.computeIfAbsent(dependency, ignored -> new TreeSet<>()).add(change.id());
            }
        }

        PriorityQueue<SchemaChange> ready = new PriorityQueue<>(changeOrder());
        for (SchemaChange change : changes) {
            if (indegrees.get(change.id()) == 0) ready.add(change);
        }
        List<SchemaChange> ordered = new ArrayList<>(changes.size());
        while (!ready.isEmpty()) {
            SchemaChange change = ready.remove();
            ordered.add(change);
            for (String dependent : dependents.getOrDefault(change.id(), Set.of())) {
                int remaining = indegrees.compute(dependent,
                        (ignored, value) -> Objects.requireNonNull(value) - 1);
                if (remaining == 0) ready.add(byId.get(dependent));
            }
        }
        if (ordered.size() != changes.size()) {
            Set<String> included = new HashSet<>();
            ordered.forEach(change -> included.add(change.id()));
            changes.stream().filter(change -> !included.contains(change.id()))
                    .sorted(changeOrder()).forEach(ordered::add);
        }
        return ordered;
    }

    private static String propertyPath(SchemaChange change) {
        return change.property() == null ? OBJECT_PATH : canonicalPath(change.property().path());
    }

    private static SchemaChange copy(
            SchemaChange change, String id, ChangeKind kind, RiskLevel risk,
            AutomationLevel automation, boolean selectedByDefault,
            Set<String> dependencies, String explanation) {
        return new SchemaChange(id, kind, change.object(), change.source(), change.target(),
                change.property(), risk, automation, selectedByDefault, dependencies, explanation);
    }

    private static Comparator<SchemaChange> changeOrder() {
        return Comparator.comparingInt(SchemaChangePlanner::actionGroup)
                .thenComparingInt(SchemaChangePlanner::effectiveRank)
                .thenComparing(SchemaChange::object)
                .thenComparing(SchemaChange::id);
    }

    private static int actionGroup(SchemaChange change) {
        return change.kind() == ChangeKind.DROP ? 1 : 0;
    }

    private static int effectiveRank(SchemaChange change) {
        int rank = rank(change.object().type());
        return change.kind() == ChangeKind.DROP ? -rank : rank;
    }

    private static int rank(ObjectType type) {
        return switch (type) {
            case TYPE -> 0;
            case SEQUENCE -> 1;
            case TABLE -> 2;
            case PRIMARY_KEY, UNIQUE_CONSTRAINT, CHECK_CONSTRAINT -> 3;
            case INDEX -> 4;
            case FOREIGN_KEY -> 5;
            case VIEW, MATERIALIZED_VIEW -> 6;
            case FUNCTION, PROCEDURE, PACKAGE_SPEC, PACKAGE_BODY -> 7;
            case TRIGGER -> 8;
        };
    }

    private static String digest(SchemaDiffResult result, Set<String> selectedIds) {
        String value = result.source().fingerprint() + '\0'
                + result.target().fingerprint() + '\0'
                + String.join("\0", new TreeSet<>(selectedIds));
        return sha256(value);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte element : hash) hex.append(String.format("%02x", element));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Selection(Set<String> selected, Set<String> blocked) {
    }

    private record CycleRewrite(List<SchemaChange> changes, Set<String> cycleIds) {
    }

    private static final class Tarjan {
        private final Map<String, SchemaChange> changes;
        private final Map<String, Integer> indexes = new HashMap<>();
        private final Map<String, Integer> lowLinks = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final Set<String> cycles = new HashSet<>();
        private int nextIndex;

        private Tarjan(Map<String, SchemaChange> changes) {
            this.changes = changes;
        }

        private Set<String> findCycleIds() {
            for (String id : changes.keySet()) {
                if (!indexes.containsKey(id)) strongConnect(id);
            }
            return cycles;
        }

        private void strongConnect(String id) {
            indexes.put(id, nextIndex);
            lowLinks.put(id, nextIndex);
            nextIndex++;
            stack.push(id);
            onStack.add(id);

            for (String dependency : changes.get(id).dependencyChangeIds()) {
                if (!changes.containsKey(dependency)) continue;
                if (!indexes.containsKey(dependency)) {
                    strongConnect(dependency);
                    lowLinks.put(id, Math.min(lowLinks.get(id), lowLinks.get(dependency)));
                } else if (onStack.contains(dependency)) {
                    lowLinks.put(id, Math.min(lowLinks.get(id), indexes.get(dependency)));
                }
            }

            if (!lowLinks.get(id).equals(indexes.get(id))) return;
            Set<String> component = new HashSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(id));
            if (component.size() > 1
                    || changes.get(id).dependencyChangeIds().contains(id)) {
                cycles.addAll(component);
            }
        }
    }
}
