package com.datacube.fx;

import com.datacube.config.SqlWorkspace;
import com.datacube.config.SqlWorkspaceActivity;
import com.datacube.config.SqlDraftCoordinator;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;

/** FX-only capture of installed bindings; never performs disk work or owns a timer. */
final class SqlWorkspaceUi implements AutoCloseable {
    enum Decision { RETRY, CANCEL, IGNORE }
    static final String TITLE = "工作区记录未保存";
    static final String MESSAGE = "本次标签顺序和编辑位置尚未保存，已有恢复点保留。可以重试、取消退出，或仅忽略本次工作区更新后退出。";
    private final SqlDraftUi drafts;
    private final TabPane tabs;
    private final SqlWorkspaceActivity activity;
    private final Supplier<CompletionStage<Decision>> decision;
    private final ListChangeListener<Tab> tabChanges = change -> activity();
    private final ChangeListener<Tab> selection = (value, before, after) -> activity();
    private boolean closing, disposed, recovering;
    private SqlWorkspaceActivity.Frozen frozen;
    private boolean captureFailure;

    SqlWorkspaceUi(SqlDraftUi drafts, ContentTabPane tabs, LongSupplier clock,
            Supplier<CompletionStage<Decision>> decision) {
        this.drafts = drafts;
        this.tabs = (TabPane) tabs.getNode();
        this.activity = new SqlWorkspaceActivity(drafts.runtime(), clock);
        this.decision = decision == null ? this::showDecision : decision;
        this.tabs.getTabs().addListener(tabChanges);
        this.tabs.getSelectionModel().selectedItemProperty().addListener(selection);
        tabs.workspaceLifecycle(this::freeze, this::finish);
    }

    SqlWorkspaceActivity owner() { return activity; }
    boolean beginRecovery() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX restoration required");
        if (closing || disposed || recovering) return false;
        recovering = true;
        return true;
    }
    void endRecovery(boolean successful) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX restoration required");
        if (!recovering) return;
        recovering = false;
        if (successful) activity();
    }
    SqlWorkspace capture() { return capture(false); }
    private SqlWorkspace capture(boolean provisional) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX capture required");
        List<SqlWorkspace.Entry> entries = new ArrayList<>();
        UUID selected = null;
        for (Tab tab : tabs.getTabs()) {
            var binding = drafts.installedBinding(tab.getContent());
            if (binding == null || (!provisional && !binding.checkpointed())) continue;
            entries.add(binding.position());
            if (tabs.getSelectionModel().getSelectedItem() == tab) selected = binding.id();
        }
        return new SqlWorkspace(System.currentTimeMillis(), entries, selected);
    }

    void activity() {
        if (disposed || closing || recovering) return;
        // Non-SQL startup tabs alone do not activate recording. Once active, removing the last SQL
        // tab must still produce the empty layout, so its activity is admitted by the owner.
        if (activity.status() == SqlWorkspaceActivity.Status.IDLE && tabs.getTabs().stream()
                .noneMatch(tab -> drafts.installedBinding(tab.getContent()) != null)) return;
        try { activity.activity(capture()); }
        catch (IllegalArgumentException invalid) { activity.captureFailed(); }
    }

    void pulse() {
        if (disposed || closing || recovering) return;
        try { activity.checkpointObserved(capture()); }
        catch (IllegalArgumentException invalid) { activity.captureFailed(); }
        activity.pulse();
    }

    private CompletionStage<Void> freeze() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        // Always enqueue: installed() follows reservation release in the same FX turn.
        Platform.runLater(() -> {
            try {
                closing = true;
                captureFailure = false;
                if (frozen == null || activity.status() != SqlWorkspaceActivity.Status.FROZEN)
                    frozen = activity.freezeForExit(capture(true));
                result.complete(null);
            } catch (IllegalArgumentException invalid) {
                captureFailure = true; activity.captureFailed(); result.complete(null);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.copy();
    }

    private CompletionStage<TabCloseOutcome> finish(TabCloseOutcome outcome) {
        CompletableFuture<TabCloseOutcome> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (outcome != TabCloseOutcome.COMPLETED) { closing = false; result.complete(outcome); return; }
            // Explicitly disabled/paused draft protection cannot publish a new layout. Preserve
            // all prior draft/abort outcomes above; genuine unavailable/write failures still ask.
            if (drafts.runtime().mode() == SqlDraftCoordinator.Mode.DISABLED
                    || drafts.runtime().mode() == SqlDraftCoordinator.Mode.PAUSED) {
                closing = false; result.complete(TabCloseOutcome.COMPLETED); return;
            }
            if (captureFailure) decide(result, null);
            else if (frozen == null || !frozen.recording()
                    || frozen.generation() != drafts.runtime().workspaceGeneration()) {
                closing = false; result.complete(TabCloseOutcome.COMPLETED);
            } else validateAndSave(result);
        });
        return result.copy();
    }

    private void validateAndSave(CompletableFuture<TabCloseOutcome> result) {
        drafts.runtime().refresh().whenComplete((snapshot, failure) -> Platform.runLater(() -> {
            if (failure != null || snapshot == null || !snapshot.succeeded() || snapshot.snapshot() == null
                    || !snapshot.snapshot().writable()) { decide(result, null); return; }
            Set<UUID> ids = new HashSet<>();
            snapshot.snapshot().drafts().forEach(draft -> ids.add(draft.id()));
            List<SqlWorkspace.Entry> entries = frozen.workspace().entries().stream()
                    .filter(entry -> ids.contains(entry.draftId())).toList();
            UUID selected = frozen.workspace().selectedDraftId();
            SqlWorkspace validated = new SqlWorkspace(frozen.workspace().capturedAt(), entries,
                    ids.contains(selected) ? selected : null);
            publish(result, validated);
        }));
    }

    private void publish(CompletableFuture<TabCloseOutcome> result, SqlWorkspace validated) {
        activity.saveFrozen(frozen, validated).whenComplete((unused, failure) -> Platform.runLater(() -> {
            if (failure == null) { closing = false; result.complete(TabCloseOutcome.COMPLETED); }
            else decide(result, validated);
        }));
    }

    private void decide(CompletableFuture<TabCloseOutcome> result, SqlWorkspace validated) {
        // This is always an ordinary queued FX turn, never a Timeline/layout callback.
        Platform.runLater(() -> {
            try {
                Objects.requireNonNull(decision.get()).whenComplete((choice, failure) -> Platform.runLater(() -> {
                    if (failure != null || choice == null || choice == Decision.CANCEL) {
                        closing = false; result.complete(TabCloseOutcome.CANCELLED);
                    } else if (choice == Decision.IGNORE) {
                        closing = false; result.complete(TabCloseOutcome.COMPLETED);
                    } else if (captureFailure) decide(result, null);
                    else if (validated == null) validateAndSave(result);
                    else publish(result, validated);
                }));
            } catch (Throwable failure) { closing = false; result.complete(TabCloseOutcome.CANCELLED); }
        });
    }

    private CompletionStage<Decision> showDecision() {
        ButtonType retry = new ButtonType("重试", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("取消退出", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType ignore = new ButtonType("忽略本次工作区更新并退出", ButtonBar.ButtonData.OTHER);
        Alert dialog = new Alert(Alert.AlertType.WARNING, MESSAGE, retry, cancel, ignore);
        dialog.setTitle(TITLE); dialog.setHeaderText(null);
        if (tabs.getScene() != null && tabs.getScene().getWindow() != null) dialog.initOwner(tabs.getScene().getWindow());
        ((Button) dialog.getDialogPane().lookupButton(retry)).setDefaultButton(false);
        ((Button) dialog.getDialogPane().lookupButton(ignore)).setDefaultButton(false);
        ((Button) dialog.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        ButtonType choice = dialog.showAndWait().orElse(cancel);
        return CompletableFuture.completedFuture(choice == retry ? Decision.RETRY : choice == ignore ? Decision.IGNORE : Decision.CANCEL);
    }

    public void close() {
        disposed = true;
        tabs.getTabs().removeListener(tabChanges);
        tabs.getSelectionModel().selectedItemProperty().removeListener(selection);
    }
}
