package com.datacube.fx;

import com.datacube.config.SqlDraftCoordinator;
import com.datacube.config.SqlWorkspaceActivity;
import com.datacube.config.SqlWorkspaceRecovery;
import com.datacube.config.SqlWorkspaceStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Dialog-scoped state only; all reads and writes use the existing application's runtime. */
final class SqlWorkspaceManagerPane implements AutoCloseable {
    private static final String PRIVACY = "工作区仅记录草稿引用、标签顺序和编辑位置；恢复会显示本地 SQL 草稿。清空工作区不删除草稿，SQL 历史独立管理。";
    private final SqlDraftCoordinator runtime;
    private final SqlWorkspaceActivity activity;
    private final SqlWorkspaceUi workspace;
    private final SqlWorkspaceRecoveryTabs recovery;
    private final VBox root = new VBox(6);
    private final Label status = new Label(), notice = new Label();
    private final Label activityStatus = new Label();
    private final Button retrySave = new Button("重试保存布局");
    private final Button restore = new Button("恢复工作区"), refresh = new Button("刷新工作区"),
            toggle = new Button(), clear = new Button("清空工作区");
    private boolean pending, closed, needsInitialRead = true, stale = true;
    private long expectedGeneration, attempt;
    private SqlWorkspaceStore.Snapshot loaded;
    private SqlWorkspaceRecovery.Resolution resolution;
    private SqlDraftCoordinator.ManagementResult applied;

    SqlWorkspaceManagerPane(SqlDraftUi owner, SqlWorkspaceRecoveryTabs recovery) {
        runtime = owner.runtime();
        workspace = Objects.requireNonNull(owner.workspace());
        activity = workspace.owner();
        this.recovery = Objects.requireNonNull(recovery);
        expectedGeneration = runtime.workspaceGeneration();
        root.setId("workspace-manager");
        status.setId("workspace-manager-status"); status.setWrapText(true);
        notice.setId("workspace-manager-notice"); notice.setWrapText(true);
        activityStatus.setId("workspace-manager-activity-status"); activityStatus.setWrapText(true);
        retrySave.setId("workspace-manager-retry-save");
        retrySave.setOnAction(event -> {
            if (blocked() || !workspace.canRetrySave()) return;
            workspace.retrySave();
            render();
        });
        restore.setId("workspace-manager-restore"); refresh.setId("workspace-manager-refresh");
        toggle.setId("workspace-manager-toggle"); clear.setId("workspace-manager-clear");
        restore.setOnAction(event -> { if (!blocked() && usable()) load(true); });
        refresh.setOnAction(event -> { if (!blocked()) { notice.setText(""); load(false); } });
        toggle.setOnAction(event -> {
            if (blocked() || stale || loaded == null || !loaded.preferenceValid()) return;
            boolean enable = activity.status() == SqlWorkspaceActivity.Status.SESSION_PAUSED || !loaded.recordingEnabled();
            manage(() -> activity.setWorkspaceEnabled(enable), enable ? "工作区记录已开启" : "工作区记录已关闭");
        });
        clear.setOnAction(event -> {
            if (!blocked() && mutableManifest() && confirmClear())
                manage(activity::clearWorkspace, "工作区已清空；SQL 草稿和编辑器保持不变。");
        });
        Label privacy = new Label(PRIVACY); privacy.setWrapText(true);
        root.getChildren().addAll(status, activityStatus, retrySave,
                new FlowPane(8, 4, restore, refresh, toggle, clear), notice, privacy);
        refreshView();
    }

    Parent getNode() { return root; }

    void refreshView() {
        if (closed) return;
        if (runtime.mode() == SqlDraftCoordinator.Mode.CLOSED) { render(); return; }
        if (runtime.workspaceGeneration() != expectedGeneration) invalidate();
        var current = runtime.lastManagementResult();
        if (!pending && !stale && applied != null && current != applied) {
            stale = true;
            notice.setText("草稿记录已变化，请刷新工作区后恢复。");
        }
        if (!pending) applied = current;
        if (needsInitialRead && !blocked()) {
            needsInitialRead = false;
            // Never read or enter modal UI reentrantly from the shared owner timer.
            pending = true;
            long token = ++attempt;
            Platform.runLater(() -> {
                if (!valid(token)) return;
                pending = false;
                if (runtime.managementPending()) { needsInitialRead = true; render(); }
                else load(false);
            });
        }
        render();
    }

    private boolean blocked() {
        return closed || pending || runtime.managementPending()
                || runtime.mode() == SqlDraftCoordinator.Mode.INITIALIZING
                || runtime.mode() == SqlDraftCoordinator.Mode.UNAVAILABLE
                || runtime.mode() == SqlDraftCoordinator.Mode.CLOSED;
    }

    private boolean usable() { return !stale && resolution != null && loaded.workspace() != null && !loaded.workspace().entries().isEmpty(); }
    private boolean mutableManifest() {
        return loaded != null && !stale && (loaded.status() == SqlWorkspaceStore.Status.AVAILABLE || loaded.status() == SqlWorkspaceStore.Status.ABSENT);
    }

    private void load(boolean restoreAfterRead) {
        if (blocked()) return;
        needsInitialRead = false;
        pending = true;
        expectedGeneration = runtime.workspaceGeneration();
        long token = ++attempt;
        render();
        try {
            runtime.refresh().whenComplete((drafts, failure) -> Platform.runLater(() -> {
                if (!valid(token)) return;
                applied = runtime.lastManagementResult();
                if (failure != null || drafts == null || !drafts.succeeded() || drafts.snapshot() == null) {
                    readFailed(); return;
                }
                try {
                    runtime.workspaceSnapshot().whenComplete((snapshot, error) -> Platform.runLater(() -> {
                        if (!valid(token)) return;
                        if (error != null || snapshot == null) { readFailed(); return; }
                        loaded = snapshot;
                        resolution = snapshot.status() == SqlWorkspaceStore.Status.AVAILABLE && snapshot.workspace() != null
                                ? SqlWorkspaceRecovery.resolve(snapshot.workspace(), drafts.snapshot().drafts()) : null;
                        stale = false; pending = false;
                        if (restoreAfterRead && usable()) {
                            try {
                                var result = recovery.restore(resolution);
                                notice.setText("已打开 " + result.opened() + "，已定位 " + result.reused()
                                        + "，缺失 " + result.missing() + "，失败 " + result.failed());
                            } catch (RuntimeException failureToRestore) {
                                notice.setText("工作区恢复未完成，已有恢复点保留；请刷新后重试。");
                            }
                        }
                        render();
                    }));
                } catch (RuntimeException error) { readFailed(); }
            }));
        } catch (RuntimeException error) { readFailed(); }
    }

    private boolean valid(long token) {
        if (closed || token != attempt) return false;
        if (runtime.mode() == SqlDraftCoordinator.Mode.CLOSED) { pending = false; render(); return false; }
        if (runtime.workspaceGeneration() != expectedGeneration) { invalidate(); render(); return false; }
        return true;
    }

    private void invalidate() {
        ++attempt; pending = false; stale = true;
        expectedGeneration = runtime.workspaceGeneration();
        notice.setText("工作区或草稿记录已变化，本次恢复已取消；请刷新后重试。");
    }

    private void readFailed() {
        pending = false; stale = true;
        notice.setText("工作区读取失败，已有恢复点保留；请显式刷新重试。");
        render();
    }

    private void manage(Supplier<? extends CompletableFuture<?>> operation, String success) {
        if (blocked()) return;
        pending = true; notice.setText("正在保存工作区设置…");
        long token = ++attempt;
        try {
            CompletableFuture<?> future = operation.get();
            expectedGeneration = runtime.workspaceGeneration();
            future.whenComplete((unused, failure) -> Platform.runLater(() -> {
                if (!valid(token)) return;
                pending = false;
                if (failure != null) {
                    stale = true;
                    notice.setText(activity.status() == SqlWorkspaceActivity.Status.SESSION_PAUSED
                            ? "本次已暂停，设置未保存，下次启动可能恢复"
                            : "工作区操作未完成，已有恢复点保留；请刷新后重试。");
                    render();
                } else {
                    notice.setText(success);
                    load(false);
                }
            }));
        } catch (RuntimeException failure) {
            expectedGeneration = runtime.workspaceGeneration();
            pending = false; stale = true;
            notice.setText("工作区操作未完成，已有恢复点保留；请刷新后重试。");
        }
        render();
    }

    private void render() {
        boolean blocked = blocked();
        retrySave.setDisable(blocked || !workspace.canRetrySave());
        activityStatus.setText("当前布局：" + switch (runtime.mode()) {
            case DISABLED, PAUSED -> "草稿保护已关闭或暂停，不记录新的布局；已有恢复点保留";
            case INITIALIZING -> "工作区初始化中，尚未记录新的布局";
            case UNAVAILABLE -> "工作区记录不可用，已有恢复点保留；请检查本机目录后重启";
            case CLOSED -> "工作区记录已关闭，不记录新的布局";
            case ENABLED -> activity.statusText();
        });
        restore.setDisable(blocked || !usable());
        refresh.setDisable(blocked);
        toggle.setDisable(blocked || stale || loaded == null || !loaded.preferenceValid());
        clear.setDisable(blocked || !mutableManifest());
        boolean paused = !closed && runtime.mode() != SqlDraftCoordinator.Mode.CLOSED
                && activity.status() == SqlWorkspaceActivity.Status.SESSION_PAUSED;
        String preference = loaded == null ? "工作区记录偏好尚未读取"
                : !loaded.preferenceValid() ? "工作区记录偏好不可确认"
                : paused ? "本次已暂停，设置未保存，下次启动可能恢复"
                : loaded.recordingEnabled() ? "工作区记录已开启" : "工作区记录已关闭，已有工作区仍可恢复";
        toggle.setText(loaded == null || !loaded.preferenceValid() ? "记录 SQL 工作区（偏好不可确认）"
                : !paused && loaded.recordingEnabled() ? "关闭记录 SQL 工作区" : "开启记录 SQL 工作区");
        String state = loaded == null ? "尚未读取工作区" : switch (loaded.status()) {
            case ABSENT -> "没有保存的工作区";
            case AVAILABLE -> loaded.workspace().entries().isEmpty() ? "工作区为空"
                    : "共 " + loaded.workspace().entries().size() + "，可用 " + resolution.tabs().size() + "，缺失 " + resolution.missingDraftIds().size();
            case CORRUPT -> "工作区清单已损坏；可在下方逐条恢复草稿";
            case UNSUPPORTED_VERSION -> "工作区清单版本不受支持；可在下方逐条恢复草稿";
            case UNREADABLE -> "工作区清单无法读取；可刷新重试或在下方逐条恢复草稿";
        };
        if (stale && loaded != null) state = "上次读取的恢复点（需刷新）：" + state;
        String draft = runtime.mode() == SqlDraftCoordinator.Mode.DISABLED || runtime.mode() == SqlDraftCoordinator.Mode.PAUSED
                ? " · 草稿保护已关闭或暂停，恢复不会记录新的布局" : "";
        status.setText(runtime.mode() == SqlDraftCoordinator.Mode.CLOSED || closed ? "工作区管理已关闭"
                : runtime.mode() == SqlDraftCoordinator.Mode.INITIALIZING ? "工作区初始化中，尚未读取"
                : runtime.mode() == SqlDraftCoordinator.Mode.UNAVAILABLE ? "工作区读取不可用；已有恢复点保留"
                : state + " · " + preference + draft + (pending || runtime.managementPending() ? " · 处理中" : ""));
    }

    private boolean confirmClear() {
        ButtonType accept = new ButtonType("清空工作区", ButtonBar.ButtonData.OTHER);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                "仅清空布局引用，不关闭编辑器、不删除 SQL 草稿；这不是安全擦除。是否继续？", ButtonType.CANCEL, accept);
        dialog.setTitle("清空工作区"); dialog.setHeaderText(null);
        if (root.getScene() != null && root.getScene().getWindow() != null) dialog.initOwner(root.getScene().getWindow());
        ((Button) dialog.getDialogPane().lookupButton(accept)).setDefaultButton(false);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == accept;
    }

    @Override public void close() {
        closed = true;
        retrySave.setDisable(true);
        ++attempt;
    }
}
