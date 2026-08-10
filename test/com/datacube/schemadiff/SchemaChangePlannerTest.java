package com.datacube.schemadiff;

import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.CanonicalDataType;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ColumnDefinition;
import com.datacube.spi.schemadiff.ConstraintDefinition;
import com.datacube.spi.schemadiff.ConstraintKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaObject;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.TableDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaChangePlannerTest {
    private static final String UNKNOWN_SELECTION_MESSAGE = "Selected change IDs are invalid";
    private static final String DUPLICATE_CHANGE_ID_MESSAGE = "Schema change IDs are not unique";
    private static final String PLAN_DIGEST_DOMAIN = "datacube.schema-change-plan-digest.v2";

    private final SchemaChangePlanner planner = new SchemaChangePlanner();

    @Test
    void safeMissingAndNullableColumnAdditionAreSelectedByDefault() {
        DefinitionObject missing = definition(ObjectType.VIEW, "new_view", DefinitionConfidence.HIGH, Set.of());
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        TableDefinition targetTable = table(tableKey, List.of(column("id", false, null)));
        ColumnDefinition added = column("note", true, "   ");
        TableDefinition sourceTable = table(tableKey, List.of(column("id", false, null), added));
        SchemaDifference missingDifference = difference(DifferenceKind.MISSING_IN_TARGET,
                missing.key(), missing, null, List.of(), RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, Set.of());
        SchemaDifference addedColumn = difference(DifferenceKind.MODIFIED,
                tableKey, sourceTable, targetTable,
                List.of(new PropertyDifference("columns[note]", added, null, "secret-value")),
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, Set.of());

        SchemaChangePlan plan = planner.plan(result(List.of(missingDifference, addedColumn)));

        assertEquals(2, plan.changes().size());
        assertEquals(Set.of(ChangeKind.CREATE, ChangeKind.ALTER), plan.changes().stream()
                .map(SchemaChange::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(plan.changes().stream().allMatch(change -> change.risk() == RiskLevel.LOW));
        assertTrue(plan.changes().stream().allMatch(change -> change.automation() == AutomationLevel.SAFE_AUTOMATIC));
        assertTrue(plan.changes().stream().allMatch(SchemaChange::selectedByDefault));
        assertEquals(plan.changes().stream().map(SchemaChange::id).collect(java.util.stream.Collectors.toSet()),
                plan.selectedChangeIds());
        assertTrue(plan.blockedChangeIds().isEmpty());
    }

    @Test
    void equivalentProducesNoChangeAndUnsupportedProducesOnlyUnselectedManual() {
        DefinitionObject equivalent = definition(ObjectType.VIEW, "same_view", DefinitionConfidence.HIGH, Set.of());
        DefinitionObject unsupported = definition(ObjectType.FUNCTION, "hidden_function", DefinitionConfidence.HIGH,
                Set.of());

        SchemaChangePlan plan = planner.plan(result(List.of(
                difference(DifferenceKind.EQUIVALENT, equivalent.key(), equivalent, equivalent,
                        List.of(), RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, Set.of()),
                difference(DifferenceKind.UNSUPPORTED, unsupported.key(), unsupported, null,
                        List.of(), RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, Set.of()))));

        assertEquals(1, plan.changes().size());
        SchemaChange manual = plan.changes().getFirst();
        assertEquals(ChangeKind.MANUAL, manual.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, manual.automation());
        assertFalse(manual.selectedByDefault());
        assertTrue(plan.selectedChangeIds().isEmpty());
    }

    @Test
    void everyRiskyStructuralAndProgrammableChangeRequiresDestructiveOptIn() {
        DefinitionObject extra = definition(ObjectType.VIEW, "obsolete_view", DefinitionConfidence.HIGH, Set.of());
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        TableDefinition table = table(tableKey, List.of(column("amount", true, null)));
        DefinitionObject sourceFunction = definition(ObjectType.FUNCTION, "calculate", DefinitionConfidence.HIGH,
                Set.of());
        DefinitionObject targetFunction = new DefinitionObject(sourceFunction.key(), "different-definition",
                "sql-secret", Set.of(), DefinitionConfidence.HIGH);
        ConstraintDefinition oldKey = constraint(tableKey, "pk_orders_old");
        ConstraintDefinition newKey = constraint(tableKey, "pk_orders_new");
        List<PropertyDifference> riskyProperties = List.of(
                new PropertyDifference("columns[amount].dataType", type("varchar", 100L), type("varchar", 20L), "safe"),
                new PropertyDifference("columns[amount].nullable", true, false, "safe"),
                new PropertyDifference("constraints", List.of(oldKey), List.of(newKey), "safe"));

        SchemaChangePlan plan = planner.plan(result(List.of(
                difference(DifferenceKind.EXTRA_IN_TARGET, extra.key(), null, extra, List.of(),
                        RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of()),
                difference(DifferenceKind.MODIFIED, tableKey, table, table, riskyProperties,
                        RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of()),
                difference(DifferenceKind.MODIFIED, sourceFunction.key(), sourceFunction, targetFunction,
                        List.of(new PropertyDifference("normalizedDefinition", "sha256:a", "sha256:b", "safe")),
                        RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of()))));

        assertEquals(5, plan.changes().size());
        assertTrue(plan.selectedChangeIds().isEmpty());
        SchemaChange drop = only(plan, ChangeKind.DROP);
        assertEquals(RiskLevel.CRITICAL, drop.risk());
        assertEquals(AutomationLevel.DESTRUCTIVE_OPT_IN, drop.automation());
        assertFalse(drop.selectedByDefault());
        SchemaChange replace = only(plan, ChangeKind.REPLACE);
        assertEquals(RiskLevel.HIGH, replace.risk());
        assertEquals(AutomationLevel.DESTRUCTIVE_OPT_IN, replace.automation());
        assertTrue(plan.changes().stream().filter(change -> change.kind() == ChangeKind.ALTER)
                .allMatch(change -> change.risk() == RiskLevel.HIGH
                        && change.automation() == AutomationLevel.DESTRUCTIVE_OPT_IN
                        && !change.selectedByDefault()));
    }

    @Test
    void definitionPropertiesCoalesceIntoOneStableAtomicReplacement() {
        ObjectKey definitionKey = key(ObjectType.VIEW, "atomic_view");
        ObjectKey oldDependency = key(ObjectType.TABLE, "old_dependency");
        ObjectKey newDependency = key(ObjectType.TABLE, "new_dependency");
        DefinitionObject source = new DefinitionObject(definitionKey, "select new", "source-secret",
                Set.of(newDependency), DefinitionConfidence.HIGH);
        DefinitionObject target = new DefinitionObject(definitionKey, "select old", "target-secret",
                Set.of(oldDependency), DefinitionConfidence.HIGH);
        PropertyDifference definition = new PropertyDifference(
                "normalizedDefinition", "sha256:new", "sha256:old", "safe");
        PropertyDifference dependencies = new PropertyDifference(
                "dependencies", Set.of(newDependency), Set.of(oldDependency), "safe");
        SchemaDifference ordered = difference(DifferenceKind.MODIFIED, definitionKey, source, target,
                List.of(dependencies, definition), RiskLevel.HIGH,
                AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of(oldDependency, newDependency));
        SchemaDifference reorderedWithOtherValues = difference(
                DifferenceKind.MODIFIED, definitionKey, source, target,
                List.of(
                        new PropertyDifference("normalizedDefinition", "sha256:other-new",
                                "sha256:other-old", "safe"),
                        new PropertyDifference("dependencies", Set.of(newDependency),
                                Set.of(oldDependency), "safe")),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN,
                Set.of(oldDependency, newDependency));

        SchemaChangePlan first = planner.plan(result(
                List.of(ordered), "source-stable", "target-stable", Instant.EPOCH));
        SchemaChangePlan second = planner.plan(result(
                List.of(reorderedWithOtherValues), "source-stable", "target-stable", Instant.MAX));

        assertEquals(1, first.changes().size());
        assertEquals(1, second.changes().size());
        SchemaChange firstReplacement = first.changes().getFirst();
        SchemaChange secondReplacement = second.changes().getFirst();
        assertEquals(ChangeKind.REPLACE, firstReplacement.kind());
        assertEquals("normalizedDefinition", firstReplacement.property().path());
        assertEquals("normalizedDefinition", secondReplacement.property().path());
        assertEquals(firstReplacement.id(), secondReplacement.id());
        SchemaChangePlan firstSelected = planner.select(first, Set.of(firstReplacement.id()));
        SchemaChangePlan secondSelected = planner.select(second, Set.of(secondReplacement.id()));
        assertEquals(firstSelected.digest(), secondSelected.digest());
    }

    @Test
    void dependencyOnlyDefinitionChangeProducesOneAtomicReplacement() {
        ObjectKey definitionKey = key(ObjectType.FUNCTION, "dependent_function");
        ObjectKey oldDependency = key(ObjectType.TABLE, "old_input");
        ObjectKey newDependency = key(ObjectType.TABLE, "new_input");
        DefinitionObject source = new DefinitionObject(definitionKey, "select value", "source-secret",
                Set.of(newDependency), DefinitionConfidence.HIGH);
        DefinitionObject target = new DefinitionObject(definitionKey, "select value", "target-secret",
                Set.of(oldDependency), DefinitionConfidence.HIGH);
        PropertyDifference dependencies = new PropertyDifference(
                "dependencies", Set.of(newDependency), Set.of(oldDependency), "safe");

        SchemaChangePlan plan = planner.plan(result(List.of(difference(
                DifferenceKind.MODIFIED, definitionKey, source, target, List.of(dependencies),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN,
                Set.of(oldDependency, newDependency)))));

        assertEquals(1, plan.changes().size());
        SchemaChange replacement = plan.changes().getFirst();
        assertEquals(ChangeKind.REPLACE, replacement.kind());
        assertEquals("dependencies", replacement.property().path());
    }

    @Test
    void lowConfidenceDefinitionPropertiesCoalesceIntoOneManualChange() {
        ObjectKey definitionKey = key(ObjectType.PROCEDURE, "low_atomic_procedure");
        ObjectKey dependency = key(ObjectType.TABLE, "procedure_input");
        DefinitionObject source = new DefinitionObject(definitionKey, "begin new; end", "source-secret",
                Set.of(), DefinitionConfidence.LOW);
        DefinitionObject target = new DefinitionObject(definitionKey, "begin old; end", "target-secret",
                Set.of(dependency), DefinitionConfidence.HIGH);
        List<PropertyDifference> properties = List.of(
                new PropertyDifference("dependencies", Set.of(), Set.of(dependency), "safe"),
                new PropertyDifference("normalizedDefinition", "sha256:new", "sha256:old", "safe"));

        SchemaChangePlan plan = planner.plan(result(List.of(difference(
                DifferenceKind.MODIFIED, definitionKey, source, target, properties,
                RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, Set.of(dependency)))));

        assertEquals(1, plan.changes().size());
        SchemaChange manual = plan.changes().getFirst();
        assertEquals(ChangeKind.MANUAL, manual.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, manual.automation());
        assertEquals("normalizedDefinition", manual.property().path());
        assertTrue(plan.selectedChangeIds().isEmpty());
    }

    @Test
    void nonIsolatableDefinitionPropertiesCoalesceIntoOneStableManualChange() {
        ObjectKey definitionKey = key(ObjectType.VIEW, "manual_atomic_view");
        ObjectKey dependency = key(ObjectType.TABLE, "manual_input");
        DefinitionObject source = new DefinitionObject(definitionKey, "select value", "source-secret",
                Set.of(), DefinitionConfidence.HIGH);
        DefinitionObject target = new DefinitionObject(definitionKey, "select value", "target-secret",
                Set.of(dependency), DefinitionConfidence.HIGH);
        PropertyDifference dependencies = new PropertyDifference(
                "dependencies", Set.of(), Set.of(dependency), "safe");
        PropertyDifference unknown = new PropertyDifference(
                "unknownShape", "source-value", "target-value", "safe");

        SchemaChangePlan first = planner.plan(result(List.of(difference(
                DifferenceKind.MODIFIED, definitionKey, source, target,
                List.of(unknown, dependencies), RiskLevel.HIGH,
                AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of(dependency)))));
        SchemaChangePlan second = planner.plan(result(List.of(difference(
                DifferenceKind.MODIFIED, definitionKey, source, target,
                List.of(dependencies, unknown), RiskLevel.HIGH,
                AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of(dependency)))));

        assertEquals(1, first.changes().size());
        assertEquals(1, second.changes().size());
        SchemaChange manual = first.changes().getFirst();
        assertEquals(ChangeKind.MANUAL, manual.kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, manual.automation());
        assertEquals("dependencies", manual.property().path());
        assertEquals(manual.id(), second.changes().getFirst().id());
        assertTrue(first.selectedChangeIds().isEmpty());
    }

    @Test
    void lowConfidenceDefinitionsAndUnknownPropertyShapesAreManual() {
        DefinitionObject missingLow = definition(ObjectType.VIEW, "low_missing", DefinitionConfidence.LOW, Set.of());
        DefinitionObject sourceLow = definition(ObjectType.PROCEDURE, "low_modified", DefinitionConfidence.LOW,
                Set.of());
        DefinitionObject targetHigh = new DefinitionObject(sourceLow.key(), "other", "sql-secret", Set.of(),
                DefinitionConfidence.HIGH);
        ObjectKey tableKey = key(ObjectType.TABLE, "ambiguous_table");
        TableDefinition table = table(tableKey, List.of());

        SchemaChangePlan plan = planner.plan(result(List.of(
                difference(DifferenceKind.MISSING_IN_TARGET, missingLow.key(), missingLow, null, List.of(),
                        RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, Set.of()),
                difference(DifferenceKind.MODIFIED, sourceLow.key(), sourceLow, targetHigh,
                        List.of(new PropertyDifference("normalizedDefinition", "sha256:a", "sha256:b", "safe")),
                        RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, Set.of()),
                difference(DifferenceKind.MODIFIED, tableKey, table, table,
                        List.of(new PropertyDifference("objectShape", "source-secret", "target-secret", "safe")),
                        RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, Set.of()))));

        assertEquals(3, plan.changes().size());
        assertTrue(plan.changes().stream().allMatch(change -> change.kind() == ChangeKind.MANUAL));
        assertTrue(plan.changes().stream().allMatch(change -> change.automation() == AutomationLevel.MANUAL_ONLY));
        assertTrue(plan.changes().stream().noneMatch(SchemaChange::selectedByDefault));
        assertTrue(plan.selectedChangeIds().isEmpty());
    }

    @Test
    void recognizedPathsWithMismatchedValueShapesAreManualRatherThanGuessed() {
        ObjectKey tableKey = key(ObjectType.TABLE, "unsafe_values");
        TableDefinition table = table(tableKey, List.of(column("amount", true, null)));
        List<PropertyDifference> unsafe = List.of(
                new PropertyDifference("columns[amount].nullable", "yes", "no", "safe"),
                new PropertyDifference("columns[amount].dataType", "varchar(100)", "varchar(20)", "safe"),
                new PropertyDifference("constraints", List.of("old-key"), List.of("new-key"), "safe"),
                new PropertyDifference("columns[amount]", column("amount", true, null),
                        column("amount", false, null), "safe"));

        SchemaChangePlan plan = planner.plan(result(List.of(
                difference(DifferenceKind.MODIFIED, tableKey, table, table, unsafe,
                        RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of()))));

        assertEquals(4, plan.changes().size());
        assertTrue(plan.changes().stream().allMatch(change -> change.kind() == ChangeKind.MANUAL));
        assertTrue(plan.changes().stream().allMatch(change -> change.automation() == AutomationLevel.MANUAL_ONLY));
        assertTrue(plan.selectedChangeIds().isEmpty());
    }

    @Test
    void engineToPlannerTreatsComplexQuotedNullableSourceColumnAsSafeAddition() {
        ObjectKey tableKey = key(ObjectType.TABLE, "orders");
        QualifiedName complexName = new QualifiedName(
                "\"line].雪\"", "line].\n雪", true);
        ColumnDefinition added = new ColumnDefinition(complexName, type("varchar", 100L),
                true, "   ", 2, null);
        TableDefinition sourceTable = table(tableKey,
                List.of(column("id", false, null), added));
        TableDefinition targetTable = table(tableKey, List.of(column("id", false, null)));
        SortedMap<ObjectKey, SchemaObject> sourceObjects = new TreeMap<>();
        sourceObjects.put(tableKey, sourceTable);
        SortedMap<ObjectKey, SchemaObject> targetObjects = new TreeMap<>();
        targetObjects.put(tableKey, targetTable);

        SchemaDiffResult diff = new SchemaDiffEngine().compare(
                snapshot("source", "s".repeat(64), Instant.EPOCH, sourceObjects),
                snapshot("target", "t".repeat(64), Instant.EPOCH, targetObjects));
        SchemaChangePlan plan = planner.plan(diff);

        assertEquals(1, diff.differences().size());
        assertEquals(1, diff.differences().getFirst().properties().size());
        PropertyDifference property = diff.differences().getFirst().properties().getFirst();
        assertEquals("columns[line].\n雪]", property.path());
        assertEquals(added, property.sourceValue());
        assertEquals(null, property.targetValue());
        SchemaChange change = plan.changes().getFirst();
        assertEquals(ChangeKind.ALTER, change.kind());
        assertEquals(RiskLevel.LOW, change.risk());
        assertEquals(AutomationLevel.SAFE_AUTOMATIC, change.automation());
        assertTrue(change.selectedByDefault());
        assertEquals(Set.of(change.id()), plan.selectedChangeIds());
    }

    @Test
    void pseudoPathsNonNullableAndNonBlankDefaultsNeverBecomeSafeColumnAdditions() {
        List<PropertyDifference> unsafe = List.of(
                new PropertyDifference("prefix-columns[note]", column("note", true, null), null, "safe"),
                new PropertyDifference("columns[note]-suffix", column("note", true, null), null, "safe"),
                new PropertyDifference("columns[required]", column("required", false, null), null, "safe"),
                new PropertyDifference("columns[defaulted]", column("defaulted", true, "0"), null, "safe"));
        List<SchemaDifference> differences = new ArrayList<>();
        for (int index = 0; index < unsafe.size(); index++) {
            ObjectKey tableKey = key(ObjectType.TABLE, "unsafe_column_" + index);
            TableDefinition table = table(tableKey, List.of());
            differences.add(difference(DifferenceKind.MODIFIED, tableKey, table, table,
                    List.of(unsafe.get(index)), RiskLevel.HIGH,
                    AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of()));
        }

        SchemaChangePlan plan = planner.plan(result(differences));

        assertEquals(4, plan.changes().size());
        assertTrue(plan.changes().stream()
                .noneMatch(change -> change.automation() == AutomationLevel.SAFE_AUTOMATIC));
        assertTrue(plan.changes().stream().noneMatch(SchemaChange::selectedByDefault));
        assertTrue(plan.selectedChangeIds().isEmpty());
    }

    @Test
    void safeColumnAdditionRequiresTheExactSourceColumnComparisonKeyPath() {
        List<PropertyDifference> mismatches = List.of(
                new PropertyDifference("columns[note].nullable]",
                        column("note", true, null), null, "safe"),
                new PropertyDifference("columns[other]",
                        column("note", true, null), null, "safe"));
        List<SchemaDifference> differences = new ArrayList<>();
        for (int index = 0; index < mismatches.size(); index++) {
            ObjectKey tableKey = key(ObjectType.TABLE, "mismatched_column_path_" + index);
            TableDefinition table = table(tableKey, List.of());
            differences.add(difference(DifferenceKind.MODIFIED, tableKey, table, table,
                    List.of(mismatches.get(index)), RiskLevel.HIGH,
                    AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of()));
        }

        SchemaChangePlan plan = planner.plan(result(differences));

        assertEquals(2, plan.changes().size());
        assertTrue(plan.changes().stream().allMatch(change -> change.kind() == ChangeKind.MANUAL));
        assertTrue(plan.changes().stream()
                .allMatch(change -> change.automation() == AutomationLevel.MANUAL_ONLY));
        assertTrue(plan.selectedChangeIds().isEmpty());
    }

    @Test
    void changeIdsAreStableFullHashesIndependentOfValuesTimesAndInputOrder() {
        ObjectKey tableKey = new ObjectKey(ObjectType.TABLE,
                new QualifiedName("Orders", "orders", false), "sig(integer)");
        TableDefinition table = table(tableKey, List.of());
        PropertyDifference firstValue = new PropertyDifference("columns[id].comment", "secret-a", "secret-b", "safe");
        PropertyDifference secondValue = new PropertyDifference("columns[id].comment", "secret-c", "secret-d", "safe");
        SchemaDifference first = difference(DifferenceKind.MODIFIED, tableKey, table, table,
                List.of(firstValue), RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of());
        SchemaDifference sameIdentity = difference(DifferenceKind.MODIFIED, tableKey, table, table,
                List.of(secondValue), RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of());
        DefinitionObject other = definition(ObjectType.VIEW, "z_view", DefinitionConfidence.HIGH, Set.of());
        SchemaDifference otherDifference = difference(DifferenceKind.MISSING_IN_TARGET,
                other.key(), other, null, List.of(), RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, Set.of());

        SchemaChangePlan firstPlan = planner.plan(result(List.of(first, otherDifference), "source-a", "target-a",
                Instant.EPOCH));
        SchemaChangePlan reordered = planner.plan(result(List.of(otherDifference, sameIdentity),
                "source-a", "target-a", Instant.MAX));

        String firstId = firstPlan.changes().stream().filter(change -> change.object().equals(tableKey))
                .findFirst().orElseThrow().id();
        String secondId = reordered.changes().stream().filter(change -> change.object().equals(tableKey))
                .findFirst().orElseThrow().id();
        assertEquals(firstId, secondId);
        assertTrue(firstId.matches("chg:[0-9a-f]{64}"));
        assertFalse(firstId.contains("secret"));
        assertEquals(firstPlan.changes().stream().map(SchemaChange::id).toList(),
                reordered.changes().stream().map(SchemaChange::id).toList());
    }

    @Test
    void changeIdsIncludeOriginalNameAndQuotedIdentityBeyondComparisonKey() {
        ObjectKey unquotedKey = new ObjectKey(ObjectType.VIEW,
                new QualifiedName("orders", "orders", false), "");
        ObjectKey quotedKey = new ObjectKey(ObjectType.VIEW,
                new QualifiedName("ORDERS", "orders", true), "");
        DefinitionObject unquoted = new DefinitionObject(unquotedKey, "select 1", "secret",
                Set.of(), DefinitionConfidence.HIGH);
        DefinitionObject quoted = new DefinitionObject(quotedKey, "select 2", "secret",
                Set.of(), DefinitionConfidence.HIGH);

        SchemaChangePlan plan = planner.plan(result(List.of(
                difference(DifferenceKind.MISSING_IN_TARGET, unquotedKey, unquoted, null,
                        List.of(), RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, Set.of()),
                difference(DifferenceKind.MISSING_IN_TARGET, quotedKey, quoted, null,
                        List.of(), RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, Set.of()))));

        assertEquals(2, plan.changes().size());
        assertEquals(2, plan.changes().stream().map(SchemaChange::id).distinct().count());
        assertTrue(plan.changes().stream().allMatch(change -> change.id().matches("chg:[0-9a-f]{64}")));
    }

    @Test
    void duplicatePropertyPathsAreRejectedWithFixedSafeMessage() {
        ObjectKey tableKey = key(ObjectType.TABLE, "duplicate-path-secret");
        TableDefinition table = table(tableKey, List.of(column("id", true, null)));
        SchemaDifference duplicatePaths = difference(DifferenceKind.MODIFIED, tableKey, table, table,
                List.of(
                        new PropertyDifference("columns[id].comment", "first", "second", "safe"),
                        new PropertyDifference("columns[id].comment", "third", "fourth", "safe")),
                RiskLevel.HIGH, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(result(List.of(duplicatePaths))));

        assertEquals(DUPLICATE_CHANGE_ID_MESSAGE, failure.getMessage());
        assertFalse(failure.getMessage().contains("duplicate-path-secret"));
        assertFalse(failure.getMessage().contains("columns[id]"));
    }

    @Test
    void duplicateDifferencesAreRejectedBeforeInternalIdMapsCanOverwriteThem() {
        DefinitionObject duplicate = definition(
                ObjectType.VIEW, "duplicate-difference-secret", DefinitionConfidence.HIGH, Set.of());
        SchemaDifference first = difference(DifferenceKind.MISSING_IN_TARGET,
                duplicate.key(), duplicate, null, List.of(), RiskLevel.LOW,
                AutomationLevel.SAFE_AUTOMATIC, Set.of());
        SchemaDifference second = difference(DifferenceKind.MISSING_IN_TARGET,
                duplicate.key(), duplicate, null, List.of(), RiskLevel.LOW,
                AutomationLevel.SAFE_AUTOMATIC, Set.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> planner.plan(result(List.of(first, second))));

        assertEquals(DUPLICATE_CHANGE_ID_MESSAGE, failure.getMessage());
        assertFalse(failure.getMessage().contains("duplicate-difference-secret"));
    }

    @Test
    void selectAllowsDestructiveIgnoresManualRejectsUnknownAndChangesDigest() {
        DefinitionObject extra = definition(ObjectType.VIEW, "obsolete", DefinitionConfidence.HIGH, Set.of());
        DefinitionObject manualObject = definition(ObjectType.VIEW, "unknown", DefinitionConfidence.HIGH, Set.of());
        SchemaChangePlan initial = planner.plan(result(List.of(
                difference(DifferenceKind.EXTRA_IN_TARGET, extra.key(), null, extra, List.of(),
                        RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN, Set.of()),
                difference(DifferenceKind.UNSUPPORTED, manualObject.key(), manualObject, null, List.of(),
                        RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, Set.of()))));
        SchemaChange destructive = only(initial, ChangeKind.DROP);
        SchemaChange manual = only(initial, ChangeKind.MANUAL);

        SchemaChangePlan selected = planner.select(initial, Set.of(destructive.id(), manual.id()));

        assertEquals(Set.of(destructive.id()), selected.selectedChangeIds());
        assertTrue(selected.blockedChangeIds().isEmpty());
        assertNotEquals(initial.digest(), selected.digest());
        assertFalse(destructive.selectedByDefault());
        assertFalse(selected.changes().stream().filter(change -> change.id().equals(destructive.id()))
                .findFirst().orElseThrow().selectedByDefault());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> planner.select(initial, Set.of("jdbc:secret-unknown-id")));
        assertEquals(UNKNOWN_SELECTION_MESSAGE, failure.getMessage());
        assertFalse(failure.getMessage().contains("jdbc:"));
    }

    @Test
    void digestLengthPrefixesFingerprintFieldsAndPreservesTheirOrder() {
        SchemaChangePlan leftBoundary = planner.plan(result(
                List.of(), "a", "b\0c", Instant.EPOCH));
        SchemaChangePlan rightBoundary = planner.plan(result(
                List.of(), "a\0b", "c", Instant.EPOCH));
        SchemaChangePlan swapped = planner.plan(result(
                List.of(), "b\0c", "a", Instant.EPOCH));
        SchemaChangePlan unicodeBoundary = planner.plan(result(
                List.of(), "\0源:雪", "目\0标", Instant.EPOCH));

        assertNotEquals(leftBoundary.digest(), rightBoundary.digest());
        assertNotEquals(leftBoundary.digest(), swapped.digest());
        assertEquals(expectedDigest("a", "b\0c", Set.of()), leftBoundary.digest());
        assertEquals(expectedDigest("a\0b", "c", Set.of()), rightBoundary.digest());
        assertEquals(expectedDigest("\0源:雪", "目\0标", Set.of()), unicodeBoundary.digest());
    }

    @Test
    void digestLengthPrefixesSelectedCountAndEveryStableSelectedId() {
        DefinitionObject missing = definition(
                ObjectType.VIEW, "digest_view", DefinitionConfidence.HIGH, Set.of());
        SchemaChangePlan selected = planner.plan(result(List.of(
                difference(DifferenceKind.MISSING_IN_TARGET, missing.key(), missing, null,
                        List.of(), RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, Set.of())),
                "source\0fingerprint", "target:雪", Instant.EPOCH));
        SchemaChangePlan empty = planner.select(selected, Set.of());

        assertEquals(expectedDigest("source\0fingerprint", "target:雪",
                selected.selectedChangeIds()), selected.digest());
        assertEquals(expectedDigest("source\0fingerprint", "target:雪", Set.of()), empty.digest());
        assertNotEquals(selected.digest(), empty.digest());
    }

    private static SchemaChange only(SchemaChangePlan plan, ChangeKind kind) {
        return plan.changes().stream().filter(change -> change.kind() == kind).findFirst().orElseThrow();
    }

    private static String expectedDigest(
            String sourceFingerprint, String targetFingerprint, Set<String> selectedIds) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeField(output, PLAN_DIGEST_DOMAIN);
            writeField(output, sourceFingerprint);
            writeField(output, targetFingerprint);
            List<String> stableIds = selectedIds.stream().sorted().toList();
            writeField(output, Integer.toString(stableIds.size()));
            for (String id : stableIds) writeField(output, id);
            output.flush();
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void writeField(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static SchemaDiffResult result(List<SchemaDifference> differences) {
        return result(differences, "s".repeat(64), "t".repeat(64), Instant.EPOCH);
    }

    private static SchemaDiffResult result(
            List<SchemaDifference> differences, String sourceFingerprint,
            String targetFingerprint, Instant capturedAt) {
        SortedMap<ObjectKey, SchemaObject> sourceObjects = new TreeMap<>();
        SortedMap<ObjectKey, SchemaObject> targetObjects = new TreeMap<>();
        for (SchemaDifference difference : differences) {
            if (difference.source() != null) sourceObjects.put(difference.object(), difference.source());
            if (difference.target() != null) targetObjects.put(difference.object(), difference.target());
        }
        return new SchemaDiffResult(
                snapshot("source-connection", sourceFingerprint, capturedAt, sourceObjects),
                snapshot("target-connection", targetFingerprint, capturedAt, targetObjects),
                differences, List.of());
    }

    private static SchemaSnapshot snapshot(
            String connectionId, String fingerprint, Instant capturedAt,
            SortedMap<ObjectKey, SchemaObject> objects) {
        return new SchemaSnapshot(DbType.POSTGRESQL, connectionId, name("public"), capturedAt,
                new SnapshotCompleteness(true, new TreeMap<>()), objects, fingerprint);
    }

    private static SchemaDifference difference(
            DifferenceKind kind, ObjectKey key, SchemaObject source, SchemaObject target,
            List<PropertyDifference> properties, RiskLevel risk, AutomationLevel automation,
            Set<ObjectKey> dependencies) {
        return new SchemaDifference(kind, key, source, target, properties, risk, automation,
                dependencies, "fixed difference explanation");
    }

    private static DefinitionObject definition(
            ObjectType type, String value, DefinitionConfidence confidence, Set<ObjectKey> dependencies) {
        return new DefinitionObject(key(type, value), "normalized-" + value, "original-secret-" + value,
                dependencies, confidence);
    }

    private static TableDefinition table(ObjectKey key, List<ColumnDefinition> columns) {
        return new TableDefinition(key, columns, List.of(), List.of(), Set.of());
    }

    private static ColumnDefinition column(String value, boolean nullable, String normalizedDefault) {
        return new ColumnDefinition(name(value), type("varchar", 100L), nullable,
                normalizedDefault, 1, null);
    }

    private static ConstraintDefinition constraint(ObjectKey tableKey, String value) {
        return new ConstraintDefinition(key(ObjectType.PRIMARY_KEY, value), ConstraintKind.PRIMARY_KEY,
                List.of(name("id")), null, List.of(), null, null, null, false, Set.of(tableKey));
    }

    private static CanonicalDataType type(String value, Long length) {
        return new CanonicalDataType(value, length, null, null, false, 0, new TreeMap<>());
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value.toLowerCase(), false);
    }

    private static ObjectKey key(ObjectType type, String value) {
        return new ObjectKey(type, name(value), "");
    }
}
