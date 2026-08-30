package com.datacube.fx;

import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import java.util.function.Function;
import javafx.scene.Node;
import javafx.scene.control.Tab;

final class SqlDraftRecoveryTabs {
    private final ContentTabPane tabs;
    private final SqlDraftUi drafts;
    private final Function<SqlDraft, SqlEditorPane> factory;
    private final java.util.function.Consumer<SqlEditorPane> initialize;

    SqlDraftRecoveryTabs(ContentTabPane tabs, SqlDraftUi drafts,
            Function<SqlDraft, SqlEditorPane> factory, java.util.function.Consumer<SqlEditorPane> initialize) {
        this.tabs = tabs;
        this.drafts = drafts;
        this.factory = factory;
        this.initialize = initialize;
    }

    boolean restore(SqlDraft draft) {
        if (draft == null || drafts.runtime().managementPending()
                || drafts.runtime().mode() == SqlDraftCoordinator.Mode.CLOSED) return false;
        Node existing = drafts.installedContent(draft.id());
        if (existing != null) return tabs.selectExistingContent(existing);
        Tab opened = tabs.openManagedTab("SQL - 恢复草稿", abort -> ManagedTabFactorySequence.create(
                () -> factory.apply(draft),
                pane -> abort.bind(pane::closeResources),
                pane -> {
                    initialize.accept(pane);
                    drafts.bind(pane, draft);
                },
                pane -> new ContentTabPane.ManagedTabSpec(pane.getNode(), pane::requestClose,
                        pane::requestMandatoryClose, pane::finalizeCloseOnFx, pane::closeResources)));
        if (opened == null) return false;
        drafts.installed(opened.getContent());
        return true;
    }
}
