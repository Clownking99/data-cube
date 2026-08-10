package com.datacube.schemadiff;

import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDependencyPlannerTest {
    private final SchemaChangePlanner planner = new SchemaChangePlanner();

    @Test
    void createOrderUsesExactRanksThenObjectKeyAndId() {
        List<ObjectType> expected = List.of(
                ObjectType.TYPE,
                ObjectType.SEQUENCE,
                ObjectType.TABLE,
                ObjectType.PRIMARY_KEY,
                ObjectType.UNIQUE_CONSTRAINT,
                ObjectType.CHECK_CONSTRAINT,
                ObjectType.INDEX,
                ObjectType.FOREIGN_KEY,
                ObjectType.VIEW,
                ObjectType.MATERIALIZED_VIEW,
                ObjectType.FUNCTION,
                ObjectType.PROCEDURE,
                ObjectType.PACKAGE_SPEC,
                ObjectType.PACKAGE_BODY,
                ObjectType.TRIGGER);
        List<SchemaDifference> input = new ArrayList<>();
        for (int index = expected.size() - 1; index >= 0; index--) {
            input.add(missing(definition(expected.get(index), "object_" + expected.get(index), Set.of())));
        }
        input.add(missing(definition(ObjectType.VIEW, "a_same_rank", Set.of())));

        SchemaChangePlan plan = planner.plan(result(input));

        assertEquals(expected, plan.changes().stream().map(change -> change.object().type()).distinct().toList());
        assertEquals(List.of("a_same_rank", "object_VIEW", "object_MATERIALIZED_VIEW"),
                plan.changes().stream()
                        .filter(change -> change.object().type() == ObjectType.VIEW
                                || change.object().type() == ObjectType.MATERIALIZED_VIEW)
                        .map(change -> change.object().name().original()).toList());
    }

    @Test
    void dropOrderReversesRanksButKeepsSameRankObjectKeyAndIdOrder() {
        List<ObjectType> createOrder = List.of(
                ObjectType.TYPE, ObjectType.SEQUENCE, ObjectType.TABLE,
                ObjectType.PRIMARY_KEY, ObjectType.UNIQUE_CONSTRAINT, ObjectType.CHECK_CONSTRAINT,
                ObjectType.INDEX, ObjectType.FOREIGN_KEY,
                ObjectType.VIEW, ObjectType.MATERIALIZED_VIEW,
                ObjectType.FUNCTION, ObjectType.PROCEDURE, ObjectType.PACKAGE_SPEC, ObjectType.PACKAGE_BODY,
                ObjectType.TRIGGER);
        List<SchemaDifference> input = new ArrayList<>();
        for (ObjectType type : createOrder) {
            input.add(extra(definition(type, "object_" + type, Set.of())));
        }
        input.add(extra(definition(ObjectType.VIEW, "a_same_rank", Set.of())));

        SchemaChangePlan plan = planner.plan(result(input));

        assertEquals(List.of(
                        ObjectType.TRIGGER,
                        ObjectType.FUNCTION, ObjectType.PROCEDURE, ObjectType.PACKAGE_SPEC, ObjectType.PACKAGE_BODY,
                        ObjectType.VIEW, ObjectType.MATERIALIZED_VIEW,
                        ObjectType.FOREIGN_KEY, ObjectType.INDEX,
                        ObjectType.PRIMARY_KEY, ObjectType.UNIQUE_CONSTRAINT, ObjectType.CHECK_CONSTRAINT,
                        ObjectType.TABLE, ObjectType.SEQUENCE, ObjectType.TYPE),
                plan.changes().stream().map(change -> change.object().type()).distinct().toList());
        assertEquals(List.of("a_same_rank", "object_VIEW", "object_MATERIALIZED_VIEW"),
                plan.changes().stream()
                        .filter(change -> change.object().type() == ObjectType.VIEW
                                || change.object().type() == ObjectType.MATERIALIZED_VIEW)
                        .map(change -> change.object().name().original()).toList());
    }

    @Test
    void createEdgesFollowDependenciesAndDropEdgesReverseThemEvenWithinOneRank() {
        ObjectKey aKey = key(ObjectType.VIEW, "a_view");
        ObjectKey zKey = key(ObjectType.VIEW, "z_view");
        DefinitionObject createA = definition(aKey, Set.of(zKey));
        DefinitionObject createZ = definition(zKey, Set.of());

        SchemaChangePlan createPlan = planner.plan(result(List.of(missing(createA), missing(createZ))));

        assertEquals(List.of(zKey, aKey), createPlan.changes().stream().map(SchemaChange::object).toList());
        SchemaChange createAChange = changeFor(createPlan, aKey);
        SchemaChange createZChange = changeFor(createPlan, zKey);
        assertEquals(Set.of(createZChange.id()), createAChange.dependencyChangeIds());

        DefinitionObject dropA = definition(aKey, Set.of());
        DefinitionObject dropZ = definition(zKey, Set.of(aKey));
        SchemaChangePlan dropPlan = planner.plan(result(List.of(extra(dropA), extra(dropZ))));

        assertEquals(List.of(zKey, aKey), dropPlan.changes().stream().map(SchemaChange::object).toList());
        SchemaChange dropAChange = changeFor(dropPlan, aKey);
        SchemaChange dropZChange = changeFor(dropPlan, zKey);
        assertEquals(Set.of(dropZChange.id()), dropAChange.dependencyChangeIds());
    }

    @Test
    void cyclesAndSelfLoopsBecomeManualAndBlockExecutableDependents() {
        ObjectKey aKey = key(ObjectType.VIEW, "a_cycle");
        ObjectKey bKey = key(ObjectType.VIEW, "b_cycle");
        ObjectKey selfKey = key(ObjectType.VIEW, "self_cycle");
        ObjectKey dependentKey = key(ObjectType.FUNCTION, "cycle_consumer");
        DefinitionObject a = definition(aKey, Set.of(bKey));
        DefinitionObject b = definition(bKey, Set.of(aKey));
        DefinitionObject self = definition(selfKey, Set.of(selfKey));
        DefinitionObject dependent = definition(dependentKey, Set.of(aKey));

        SchemaChangePlan plan = planner.plan(result(List.of(
                missing(a), missing(b), missing(self), missing(dependent))));

        for (ObjectKey key : List.of(aKey, bKey, selfKey)) {
            SchemaChange cycle = changeFor(plan, key);
            assertEquals(ChangeKind.MANUAL, cycle.kind());
            assertEquals(AutomationLevel.MANUAL_ONLY, cycle.automation());
            assertFalse(cycle.selectedByDefault());
            assertFalse(plan.selectedChangeIds().contains(cycle.id()));
        }
        SchemaChange consumer = changeFor(plan, dependentKey);
        assertEquals(ChangeKind.CREATE, consumer.kind());
        assertTrue(consumer.selectedByDefault());
        assertFalse(plan.selectedChangeIds().contains(consumer.id()));
        assertTrue(plan.blockedChangeIds().contains(consumer.id()));
        assertTrue(consumer.dependencyChangeIds().contains(changeFor(plan, aKey).id()));
    }

    @Test
    void disablingARequiredChangeIterativelyBlocksItsDependents() {
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "orders_seq");
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        ObjectKey viewKey = key(ObjectType.VIEW, "orders_view");
        DefinitionObject sequence = definition(sequenceKey, Set.of());
        DefinitionObject table = definition(tableKey, Set.of(sequenceKey));
        DefinitionObject view = definition(viewKey, Set.of(tableKey));
        SchemaChangePlan initial = planner.plan(result(List.of(
                missing(sequence), missing(table), missing(view))));
        SchemaChange sequenceChange = changeFor(initial, sequenceKey);
        SchemaChange tableChange = changeFor(initial, tableKey);
        SchemaChange viewChange = changeFor(initial, viewKey);

        assertEquals(Set.of(sequenceChange.id(), tableChange.id(), viewChange.id()),
                initial.selectedChangeIds());

        SchemaChangePlan missingRoot = planner.select(initial, Set.of(tableChange.id(), viewChange.id()));
        assertTrue(missingRoot.selectedChangeIds().isEmpty());
        assertEquals(Set.of(tableChange.id(), viewChange.id()), missingRoot.blockedChangeIds());

        SchemaChangePlan missingMiddle = planner.select(initial, Set.of(sequenceChange.id(), viewChange.id()));
        assertEquals(Set.of(sequenceChange.id()), missingMiddle.selectedChangeIds());
        assertEquals(Set.of(viewChange.id()), missingMiddle.blockedChangeIds());
    }

    @Test
    void unchangedDependencyObjectsDoNotCreatePhantomChangeDependencies() {
        ObjectKey tableKey = key(ObjectType.TABLE, "stable_table");
        ObjectKey viewKey = key(ObjectType.VIEW, "new_view");
        DefinitionObject table = definition(tableKey, Set.of());
        DefinitionObject view = definition(viewKey, Set.of(tableKey));

        SchemaChangePlan plan = planner.plan(result(List.of(
                equivalent(table), missing(view))));

        assertEquals(1, plan.changes().size());
        assertTrue(plan.changes().getFirst().dependencyChangeIds().isEmpty());
        assertEquals(Set.of(plan.changes().getFirst().id()), plan.selectedChangeIds());
    }

    @Test
    void dropWaitsForTheCanonicalExecutableDependencyChange() {
        ObjectKey dependencyKey = key(ObjectType.TYPE, "legacy_type");
        ObjectKey viewKey = key(ObjectType.VIEW, "dependent_view");
        DefinitionObject sourceView = definition(viewKey, Set.of());
        DefinitionObject targetView = definition(viewKey, Set.of(dependencyKey));
        DefinitionObject targetDependency = definition(dependencyKey, Set.of());
        SchemaDifference replace = new SchemaDifference(DifferenceKind.MODIFIED, viewKey,
                sourceView, targetView,
                List.of(
                        new PropertyDifference("dependencies", Set.of(), Set.of(dependencyKey), "safe"),
                        new PropertyDifference("normalizedDefinition", "sha256:new", "sha256:old", "safe")),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of(dependencyKey), "safe");
        SchemaChangePlan plan = planner.plan(result(List.of(
                extra(targetDependency), replace)));

        SchemaChange drop = changeFor(plan, dependencyKey);
        SchemaChange replaceChange = changeForProperty(plan, viewKey, "dependencies");
        SchemaChange definitionChange = changeForProperty(plan, viewKey, "normalizedDefinition");
        assertEquals(ChangeKind.DROP, drop.kind());
        assertEquals(Set.of(replaceChange.id()), drop.dependencyChangeIds());
        assertTrue(plan.changes().indexOf(replaceChange) < plan.changes().indexOf(drop));
        assertFalse(drop.dependencyChangeIds().contains(definitionChange.id()));

        SchemaChangePlan dropOnly = planner.select(plan, Set.of(drop.id()));
        assertTrue(dropOnly.selectedChangeIds().isEmpty());
        assertEquals(Set.of(drop.id()), dropOnly.blockedChangeIds());
        SchemaChangePlan allSelected = planner.select(
                plan, Set.of(drop.id(), replaceChange.id()));
        assertEquals(Set.of(drop.id(), replaceChange.id()),
                allSelected.selectedChangeIds());
    }

    @Test
    void sequenceStartValueAlterCannotReleaseARemovedTargetDependency() {
        ObjectKey dependencyKey = key(ObjectType.TYPE, "start_value_type");
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "start_value_sequence");
        DefinitionObject dependency = definition(dependencyKey, Set.of());
        SequenceDefinition sourceSequence = sequence(sequenceKey, "2", Set.of());
        SequenceDefinition targetSequence = sequence(sequenceKey, "1", Set.of(dependencyKey));
        SchemaDifference startValueOnly = new SchemaDifference(DifferenceKind.MODIFIED, sequenceKey,
                sourceSequence, targetSequence,
                List.of(new PropertyDifference("startValue", "2", "1", "safe")),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of(dependencyKey), "safe");

        SchemaChangePlan plan = planner.plan(result(List.of(extra(dependency), startValueOnly)));

        SchemaChange guardedDrop = changeFor(plan, dependencyKey);
        assertEquals(ChangeKind.MANUAL, guardedDrop.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, guardedDrop.automation());
        assertTrue(guardedDrop.dependencyChangeIds().isEmpty());
        assertEquals(ChangeKind.ALTER, changeFor(plan, sequenceKey).kind());
    }

    @Test
    void manualCanonicalDependencyChangeCannotBeReplacedByAnUnrelatedExecutableAlter() {
        ObjectKey dependencyKey = key(ObjectType.TYPE, "manual_release_type");
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "manual_release_sequence");
        DefinitionObject dependency = definition(dependencyKey, Set.of());
        SequenceDefinition sourceSequence = sequence(sequenceKey, "2", Set.of());
        SequenceDefinition targetSequence = sequence(sequenceKey, "1", Set.of(dependencyKey));
        SchemaDifference mixed = new SchemaDifference(DifferenceKind.MODIFIED, sequenceKey,
                sourceSequence, targetSequence,
                List.of(
                        new PropertyDifference("dependencies", Set.of(), Set.of(dependencyKey), "safe"),
                        new PropertyDifference("startValue", "2", "1", "safe")),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of(dependencyKey), "safe");

        SchemaChangePlan plan = planner.plan(result(List.of(extra(dependency), mixed)));

        assertEquals(ChangeKind.MANUAL, changeFor(plan, dependencyKey).kind());
        assertEquals(ChangeKind.MANUAL,
                changeForProperty(plan, sequenceKey, "dependencies").kind());
        assertEquals(ChangeKind.ALTER,
                changeForProperty(plan, sequenceKey, "startValue").kind());
    }

    @Test
    void unrelatedExecutableChangeCannotReleaseADependencyStillPresentInSource() {
        ObjectKey dependencyKey = key(ObjectType.TYPE, "retained_type");
        ObjectKey sequenceKey = key(ObjectType.SEQUENCE, "retained_dependency_sequence");
        DefinitionObject dependency = definition(dependencyKey, Set.of());
        SequenceDefinition sourceSequence = sequence(sequenceKey, "2", Set.of(dependencyKey));
        SequenceDefinition targetSequence = sequence(sequenceKey, "1", Set.of(dependencyKey));
        SchemaDifference unrelatedAlter = new SchemaDifference(DifferenceKind.MODIFIED, sequenceKey,
                sourceSequence, targetSequence,
                List.of(new PropertyDifference("startValue", "2", "1", "safe")),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of(dependencyKey), "safe");

        SchemaChangePlan plan = planner.plan(result(List.of(extra(dependency), unrelatedAlter)));

        assertEquals(ChangeKind.MANUAL, changeFor(plan, dependencyKey).kind());
        assertEquals(ChangeKind.ALTER, changeFor(plan, sequenceKey).kind());
    }

    @Test
    void dropBecomesManualWhenAContinuingTargetDependentHasNoExecutableReleaseChange() {
        ObjectKey dependencyKey = key(ObjectType.TYPE, "still_required_type");
        ObjectKey viewKey = key(ObjectType.VIEW, "unchanged_dependent_view");
        DefinitionObject dependency = definition(dependencyKey, Set.of());
        DefinitionObject dependent = definition(viewKey, Set.of(dependencyKey));

        SchemaChangePlan plan = planner.plan(result(List.of(
                extra(dependency), equivalent(dependent))));

        SchemaChange guardedDrop = changeFor(plan, dependencyKey);
        assertEquals(ChangeKind.MANUAL, guardedDrop.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, guardedDrop.automation());
        assertFalse(guardedDrop.selectedByDefault());
        assertTrue(plan.selectedChangeIds().isEmpty());
        assertTrue(planner.select(plan, Set.of(guardedDrop.id())).selectedChangeIds().isEmpty());
    }

    private static SchemaChange changeFor(SchemaChangePlan plan, ObjectKey key) {
        return plan.changes().stream().filter(change -> change.object().equals(key))
                .findFirst().orElseThrow();
    }

    private static SchemaChange changeForProperty(
            SchemaChangePlan plan, ObjectKey key, String propertyPath) {
        return plan.changes().stream()
                .filter(change -> change.object().equals(key)
                        && change.property() != null
                        && change.property().path().equals(propertyPath))
                .findFirst().orElseThrow();
    }

    private static SchemaDifference missing(DefinitionObject object) {
        return difference(DifferenceKind.MISSING_IN_TARGET, object, null,
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC);
    }

    private static SchemaDifference extra(DefinitionObject object) {
        return difference(DifferenceKind.EXTRA_IN_TARGET, null, object,
                RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN);
    }

    private static SchemaDifference equivalent(DefinitionObject object) {
        return difference(DifferenceKind.EQUIVALENT, object, object,
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC);
    }

    private static SchemaDifference difference(
            DifferenceKind kind, DefinitionObject source, DefinitionObject target,
            RiskLevel risk, AutomationLevel automation) {
        DefinitionObject object = source == null ? target : source;
        return new SchemaDifference(kind, object.key(), source, target, List.of(), risk, automation,
                object.dependencies(), "fixed difference explanation");
    }

    private static SchemaDiffResult result(List<SchemaDifference> differences) {
        SortedMap<ObjectKey, SchemaObject> sourceObjects = new TreeMap<>();
        SortedMap<ObjectKey, SchemaObject> targetObjects = new TreeMap<>();
        for (SchemaDifference difference : differences) {
            if (difference.source() != null) sourceObjects.put(difference.object(), difference.source());
            if (difference.target() != null) targetObjects.put(difference.object(), difference.target());
        }
        return new SchemaDiffResult(snapshot("source", "s".repeat(64), sourceObjects),
                snapshot("target", "t".repeat(64), targetObjects), differences, List.of());
    }

    private static SchemaSnapshot snapshot(
            String connectionId, String fingerprint, SortedMap<ObjectKey, SchemaObject> objects) {
        return new SchemaSnapshot(DbType.POSTGRESQL, connectionId, name("public"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), objects, fingerprint);
    }

    private static DefinitionObject definition(ObjectType type, String value, Set<ObjectKey> dependencies) {
        return definition(key(type, value), dependencies);
    }

    private static DefinitionObject definition(ObjectKey key, Set<ObjectKey> dependencies) {
        return new DefinitionObject(key, "normalized-" + key.name().comparisonKey(),
                "original-secret", dependencies, DefinitionConfidence.HIGH);
    }

    private static SequenceDefinition sequence(
            ObjectKey key, String startValue, Set<ObjectKey> dependencies) {
        return new SequenceDefinition(key, startValue, "1", null, null,
                false, null, dependencies);
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value.toLowerCase(), false);
    }

    private static ObjectKey key(ObjectType type, String value) {
        return new ObjectKey(type, name(value), "");
    }
}
