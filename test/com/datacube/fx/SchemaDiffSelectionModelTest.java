package com.datacube.fx;

import com.datacube.schemadiff.RenameSuggestion;
import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffSelectionModelTest {

    @Test
    void destructiveIsDefaultOffAndManualOrBlockedChangesNeverBecomeExecutable() {
        SchemaDiffSelectionModel model = model();

        assertEquals(Set.of("safe"), model.selectedChangeIds());
        assertFalse(model.entry("destructive").selected());
        assertFalse(model.entry("manual").executable());

        assertFalse(model.setSelected("manual", true));
        assertTrue(model.setSelected("dependent", true));
        assertFalse(model.entry("dependent").selected());
        assertTrue(model.entry("dependent").blocked());
        assertFalse(model.entry("dependent").executable());

        assertTrue(model.setSelected("dependency", true));
        assertTrue(model.entry("dependent").selected());
        assertTrue(model.entry("dependent").executable());
    }

    @Test
    void groupingAndFilteringAreStableAndFollowPlannerOrder() {
        SchemaDiffSelectionModel model = model();

        assertEquals(List.of(ObjectType.VIEW, ObjectType.TABLE, ObjectType.FUNCTION,
                        ObjectType.SEQUENCE),
                model.groups(SchemaDiffSelectionModel.Filter.all()).stream()
                        .map(SchemaDiffSelectionModel.Group::objectType).toList());
        assertEquals(List.of("destructive", "safe", "manual", "dependency", "dependent"),
                model.groups(SchemaDiffSelectionModel.Filter.all()).stream()
                        .flatMap(group -> group.entries().stream())
                        .map(entry -> entry.change().id()).toList());

        SchemaDiffSelectionModel.Filter filtered = new SchemaDiffSelectionModel.Filter(
                Set.of(ObjectType.TABLE, ObjectType.SEQUENCE),
                Set.of(RiskLevel.LOW),
                Set.of(AutomationLevel.SAFE_AUTOMATIC),
                SchemaDiffSelectionModel.SelectedState.SELECTED);
        assertEquals(List.of("safe"), model.groups(filtered).stream()
                .flatMap(group -> group.entries().stream())
                .map(entry -> entry.change().id()).toList());
    }

    @Test
    void selectionChangeUpdatesDigestAndInvalidatesConfirmationToken() {
        SchemaDiffSelectionModel model = model();
        String before = model.selectionDigest();
        model.markConfirmed("rendered-plan-token");

        assertTrue(model.confirmationToken().isPresent());
        assertTrue(model.setSelected("destructive", true));

        assertNotEquals(before, model.selectionDigest());
        assertTrue(model.confirmationToken().isEmpty());
    }

    @Test
    void renameSuggestionFocusIsDisplayOnlyAndCannotCreateExecutableSelection() {
        SchemaDiffSelectionModel model = model();
        Set<String> before = model.selectedChangeIds();
        RenameSuggestion suggestion = model.renameSuggestions().getFirst();

        model.focusRenameSuggestion(suggestion);

        assertEquals(suggestion, model.focusedRenameSuggestion().orElseThrow());
        assertEquals(before, model.selectedChangeIds());
        assertFalse(model.entries().stream()
                .anyMatch(entry -> entry.change().kind().name().contains("RENAME")));
    }

    private static SchemaDiffSelectionModel model() {
        ObjectKey oldView = key(ObjectType.VIEW, "old_view");
        ObjectKey newView = key(ObjectType.VIEW, "new_view");
        SchemaDiffResult diff = new SchemaDiffResult(
                snapshot("source", "source-fingerprint"),
                snapshot("target", "target-fingerprint"),
                List.of(),
                List.of(new RenameSuggestion(oldView, newView, 0.9, "display only")));
        List<SchemaChange> changes = List.of(
                change("destructive", ChangeKind.DROP, ObjectType.VIEW, RiskLevel.CRITICAL,
                        AutomationLevel.DESTRUCTIVE_OPT_IN, false, Set.of()),
                change("safe", ChangeKind.CREATE, ObjectType.TABLE, RiskLevel.LOW,
                        AutomationLevel.SAFE_AUTOMATIC, true, Set.of()),
                change("manual", ChangeKind.MANUAL, ObjectType.FUNCTION, RiskLevel.HIGH,
                        AutomationLevel.MANUAL_ONLY, false, Set.of()),
                change("dependency", ChangeKind.CREATE, ObjectType.SEQUENCE, RiskLevel.MEDIUM,
                        AutomationLevel.SAFE_AUTOMATIC, false, Set.of()),
                change("dependent", ChangeKind.CREATE, ObjectType.SEQUENCE, RiskLevel.MEDIUM,
                        AutomationLevel.SAFE_AUTOMATIC, false, Set.of("dependency")));
        SchemaChangePlan plan = new SchemaChangePlan(
                diff, changes, Set.of("safe"), Set.of(), "initial");
        return new SchemaDiffSelectionModel(plan, new SchemaChangePlanner());
    }

    private static SchemaChange change(
            String id,
            ChangeKind kind,
            ObjectType type,
            RiskLevel risk,
            AutomationLevel automation,
            boolean selected,
            Set<String> dependencies) {
        return new SchemaChange(id, kind, key(type, id), null, null, null,
                risk, automation, selected, dependencies, "fixed explanation");
    }

    private static ObjectKey key(ObjectType type, String name) {
        return new ObjectKey(type, new QualifiedName(name, name, false), "");
    }

    private static SchemaSnapshot snapshot(String connectionId, String fingerprint) {
        return new SchemaSnapshot(DbType.POSTGRESQL, connectionId,
                new QualifiedName("public", "public", false), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(), fingerprint);
    }
}
