package com.datacube.fx;

import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import com.datacube.config.RecentSqlFiles;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Tab;

final class SqlDraftRecoveryTabs {
    private final ContentTabPane tabs;
    private final SqlDraftUi drafts;
    private final Function<SqlDraft, SqlEditorPane> factory;
    private final java.util.function.Consumer<SqlEditorPane> initialize;
    private final SqlScriptFileStore fileStore;
    private final RecentSqlFiles recentFiles;
    private final SqlFileTabRegistry fileRegistry;

    SqlDraftRecoveryTabs(ContentTabPane tabs, SqlDraftUi drafts,
            Function<SqlDraft, SqlEditorPane> factory, java.util.function.Consumer<SqlEditorPane> initialize) {
        this(tabs, drafts, factory, initialize, null, null, null);
    }

    SqlDraftRecoveryTabs(ContentTabPane tabs, SqlDraftUi drafts,
            Function<SqlDraft, SqlEditorPane> factory,
            java.util.function.Consumer<SqlEditorPane> initialize,
            SqlScriptFileStore fileStore, RecentSqlFiles recentFiles,
            SqlFileTabRegistry fileRegistry) {
        this.tabs = tabs;
        this.drafts = drafts;
        this.factory = factory;
        this.initialize = initialize;
        if ((fileStore == null) != (recentFiles == null)
                || (fileStore == null) != (fileRegistry == null)) {
            throw new IllegalArgumentException("file lifecycle dependencies must be supplied together");
        }
        this.fileStore = fileStore;
        this.recentFiles = recentFiles;
        this.fileRegistry = fileRegistry;
    }

    boolean restore(SqlDraft draft) {
        if (draft == null || drafts.runtime().managementPending()
                || drafts.runtime().mode() == SqlDraftCoordinator.Mode.CLOSED) return false;
        Node existing = drafts.installedContent(draft.id());
        if (existing != null) return tabs.selectExistingContent(existing);
        String title = "SQL - 恢复草稿";
        java.util.concurrent.atomic.AtomicReference<Tab> ownedTab = new java.util.concurrent.atomic.AtomicReference<>();
        SqlFileTabRegistry.Owner fileOwner = fileRegistry == null ? null
                : fileRegistry.createOwner(() -> {
                    Tab tab = ownedTab.get();
                    if (tab != null) ((javafx.scene.control.TabPane) tabs.getNode())
                            .getSelectionModel().select(tab);
                });
        Tab opened = tabs.openManagedTab(title, (tab, abort) -> {
            ownedTab.set(tab);
            SqlEditorPane pane = factory.apply(draft);
            abort.bind(() -> abortPane(pane));
            try {
                initialize.accept(pane);
                if (fileRegistry != null) {
                    pane.installSqlScriptFileController(null, fileStore, recentFiles,
                            tab::setText, title, fileRegistry, fileOwner);
                }
                drafts.bind(pane, draft);
                return new ContentTabPane.ManagedTabSpec(pane.getNode(), pane::requestClose,
                        pane::requestMandatoryClose, pane::finalizeCloseOnFx, () -> abortPane(pane));
            } catch (Throwable failure) {
                pane.finalizeCloseOnFx();
                throw failure;
            }
        });
        if (opened == null && fileRegistry != null) fileRegistry.release(fileOwner);
        if (opened == null) return false;
        drafts.installed(opened.getContent());
        return true;
    }

    /** Mandatory abort runs on its existing worker and completes only after FX disposal. */
    private static void abortPane(SqlEditorPane pane) {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Recovery abort cleanup must run off the FX Application Thread");
        }
        BestEffortCloseSequence.run(pane::closeResources, () -> {
            CompletableFuture<Void> finalized = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    pane.finalizeCloseOnFx();
                    finalized.complete(null);
                } catch (Throwable failure) {
                    finalized.completeExceptionally(failure);
                }
            });
            finalized.join();
        });
    }
}
