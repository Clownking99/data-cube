package com.datacube.fx;

import com.datacube.schemadiff.RenameSuggestion;
import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure deterministic selection and filtering model for one planner-ordered schema change plan. */
public final class SchemaDiffSelectionModel {
    private final SchemaChangePlan basePlan;
    private final SchemaChangePlanner planner;
    private final Set<String> requestedIds = new LinkedHashSet<>();
    private SchemaChangePlan currentPlan;
    private String confirmationToken;
    private RenameSuggestion focusedRenameSuggestion;

    public SchemaDiffSelectionModel(SchemaChangePlan plan, SchemaChangePlanner planner) {
        this.basePlan = Objects.requireNonNull(plan, "plan");
        this.planner = Objects.requireNonNull(planner, "planner");
        for (SchemaChange change : plan.changes()) {
            if (plan.selectedChangeIds().contains(change.id())
                    && change.automation() == AutomationLevel.SAFE_AUTOMATIC
                    && change.kind() != ChangeKind.MANUAL) {
                requestedIds.add(change.id());
            }
        }
        currentPlan = planner.select(basePlan, requestedIds);
    }

    public synchronized List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(currentPlan.changes().size());
        for (SchemaChange change : currentPlan.changes()) entries.add(entryFor(change));
        return List.copyOf(entries);
    }

    public synchronized Entry entry(String changeId) {
        return currentPlan.changes().stream()
                .filter(change -> change.id().equals(changeId))
                .findFirst()
                .map(this::entryFor)
                .orElseThrow(() -> new IllegalArgumentException("Unknown schema change"));
    }

    public synchronized Set<String> selectedChangeIds() {
        return currentPlan.selectedChangeIds();
    }

    public synchronized boolean setSelected(String changeId, boolean selected) {
        SchemaChange change = currentPlan.changes().stream()
                .filter(candidate -> candidate.id().equals(changeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown schema change"));
        if (change.automation() == AutomationLevel.MANUAL_ONLY
                || change.kind() == ChangeKind.MANUAL) {
            return false;
        }
        boolean changed = selected ? requestedIds.add(changeId) : requestedIds.remove(changeId);
        if (!changed) return false;
        currentPlan = planner.select(basePlan, requestedIds);
        confirmationToken = null;
        return true;
    }

    public synchronized List<Group> groups(Filter filter) {
        Objects.requireNonNull(filter, "filter");
        Map<ObjectType, List<Entry>> grouped = new LinkedHashMap<>();
        for (SchemaChange change : currentPlan.changes()) {
            Entry entry = entryFor(change);
            if (!filter.matches(entry)) continue;
            grouped.computeIfAbsent(change.object().type(), ignored -> new ArrayList<>()).add(entry);
        }
        List<Group> result = new ArrayList<>(grouped.size());
        grouped.forEach((type, entries) -> result.add(new Group(type, entries)));
        return List.copyOf(result);
    }

    public synchronized String selectionDigest() {
        return currentPlan.digest();
    }

    public synchronized void markConfirmed(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Confirmation token is invalid");
        }
        confirmationToken = token;
    }

    public synchronized Optional<String> confirmationToken() {
        return Optional.ofNullable(confirmationToken);
    }

    public List<RenameSuggestion> renameSuggestions() {
        return basePlan.diff().renameSuggestions();
    }

    public synchronized void focusRenameSuggestion(RenameSuggestion suggestion) {
        Objects.requireNonNull(suggestion, "suggestion");
        if (!basePlan.diff().renameSuggestions().contains(suggestion)) {
            throw new IllegalArgumentException("Unknown rename suggestion");
        }
        focusedRenameSuggestion = suggestion;
    }

    public synchronized Optional<RenameSuggestion> focusedRenameSuggestion() {
        return Optional.ofNullable(focusedRenameSuggestion);
    }

    private Entry entryFor(SchemaChange change) {
        boolean selected = currentPlan.selectedChangeIds().contains(change.id());
        boolean blocked = currentPlan.blockedChangeIds().contains(change.id());
        boolean executable = selected && !blocked
                && change.automation() != AutomationLevel.MANUAL_ONLY
                && change.kind() != ChangeKind.MANUAL;
        return new Entry(change, selected, blocked, executable);
    }

    @Override
    public synchronized String toString() {
        return "SchemaDiffSelectionModel[changeCount=" + currentPlan.changes().size()
                + ", selectedCount=" + currentPlan.selectedChangeIds().size()
                + ", blockedCount=" + currentPlan.blockedChangeIds().size() + "]";
    }

    public enum SelectedState { ALL, SELECTED, UNSELECTED }

    public record Filter(
            Set<ObjectType> objectTypes,
            Set<RiskLevel> risks,
            Set<AutomationLevel> automationLevels,
            SelectedState selectedState) {
        public Filter {
            objectTypes = Set.copyOf(Objects.requireNonNull(objectTypes, "objectTypes"));
            risks = Set.copyOf(Objects.requireNonNull(risks, "risks"));
            automationLevels = Set.copyOf(
                    Objects.requireNonNull(automationLevels, "automationLevels"));
            selectedState = Objects.requireNonNull(selectedState, "selectedState");
        }

        public static Filter all() {
            return new Filter(Set.of(), Set.of(), Set.of(), SelectedState.ALL);
        }

        boolean matches(Entry entry) {
            SchemaChange change = entry.change();
            return (objectTypes.isEmpty() || objectTypes.contains(change.object().type()))
                    && (risks.isEmpty() || risks.contains(change.risk()))
                    && (automationLevels.isEmpty()
                    || automationLevels.contains(change.automation()))
                    && switch (selectedState) {
                        case ALL -> true;
                        case SELECTED -> entry.selected();
                        case UNSELECTED -> !entry.selected();
                    };
        }
    }

    public record Entry(SchemaChange change, boolean selected, boolean blocked, boolean executable) {
        public Entry {
            change = Objects.requireNonNull(change, "change");
        }
    }

    public record Group(ObjectType objectType, List<Entry> entries) {
        public Group {
            objectType = Objects.requireNonNull(objectType, "objectType");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }
}
