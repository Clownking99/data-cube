package com.datacube.fx;

import com.datacube.config.SqlWorkspaceRecovery;
import java.util.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/** Synchronous FX assembly only: the caller owns snapshot validation and disk reads. */
final class SqlWorkspaceRecoveryTabs {
    private final TabPane tabs;
    private final SqlDraftUi drafts;
    private final SqlDraftRecoveryTabs recovery;

    SqlWorkspaceRecoveryTabs(ContentTabPane tabs, SqlDraftUi drafts, SqlDraftRecoveryTabs recovery) {
        this.tabs = (TabPane) tabs.getNode();
        this.drafts = drafts;
        this.recovery = recovery;
    }

    record Result(int opened, int reused, int missing, int failed) { }

    Result restore(SqlWorkspaceRecovery.Resolution resolution) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX restoration required");
        Objects.requireNonNull(resolution);
        Tab previous = tabs.getSelectionModel().getSelectedItem();
        SqlWorkspaceUi workspace = drafts.workspace();
        if (workspace != null && !workspace.beginRecovery())
            return new Result(0, 0, resolution.missingDraftIds().size(), resolution.tabs().size());
        int opened = 0, reused = 0, failed = 0;
        Map<UUID, Tab> successes = new LinkedHashMap<>();
        try {
            for (var entry : resolution.tabs()) {
                try {
                    Node before = drafts.installedContent(entry.draft().id());
                    if (!recovery.restore(entry.draft())) { failed++; continue; }
                    Node content = drafts.installedContent(entry.draft().id());
                    Tab tab = tabs.getTabs().stream().filter(value -> value.getContent() == content).findFirst().orElse(null);
                    SqlDraftEditorBinding binding = drafts.installedBinding(content);
                    if (tab == null || binding == null) { failed++; continue; }
                    if (before == null) { binding.restorePosition(entry.anchor(), entry.caret()); opened++; }
                    else reused++;
                    successes.put(entry.draft().id(), tab);
                } catch (RuntimeException failure) {
                    // The managed single-draft factory retains ownership of mandatory abort cleanup.
                    failed++;
                }
            }
            if (!successes.isEmpty()) {
                List<Tab> desired = new ArrayList<>(tabs.getTabs());
                Set<Tab> involved = new HashSet<>(successes.values());
                Iterator<Tab> order = successes.values().iterator();
                for (int index = 0; index < desired.size(); index++)
                    if (involved.contains(desired.get(index))) desired.set(index, order.next());
                Map<Tab, Integer> indexes = new IdentityHashMap<>();
                for (int index = 0; index < desired.size(); index++) indexes.put(desired.get(index), index);
                FXCollections.sort(tabs.getTabs(), Comparator.comparingInt(indexes::get));
                Tab selected = resolution.selectedDraftId() == null && tabs.getTabs().contains(previous)
                        ? previous : successes.get(resolution.selectedDraftId());
                tabs.getSelectionModel().select(selected == null ? successes.values().iterator().next() : selected);
            } else if (previous != null && tabs.getTabs().contains(previous)) {
                tabs.getSelectionModel().select(previous);
            } else {
                tabs.getSelectionModel().clearSelection();
            }
            return new Result(opened, reused, resolution.missingDraftIds().size(), failed);
        } finally {
            if (workspace != null) workspace.endRecovery(!successes.isEmpty());
        }
    }
}
