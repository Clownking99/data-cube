package com.datacube.fx;

import com.datacube.config.SqlDraft;
import com.datacube.config.SqlDraftCoordinator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class SqlDraftManagerPane implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final SqlDraftCoordinator runtime;
    private final Function<SqlDraft, Boolean> restore;
    private final Runnable restored;
    private final VBox root = new VBox(8);
    private final ListView<SqlDraft> list = new ListView<>();
    private final TextArea sql = new TextArea();
    private final Label status = new Label();
    private final Label notice = new Label();
    private final Button recover = new Button("恢复");
    private final Button refresh = new Button("刷新");
    private final Button delete = new Button("删除所选");
    private final Button clear = new Button("清空草稿");
    private final Button toggle = new Button();
    private SqlDraftCoordinator.ManagementResult applied;
    private boolean initialRefresh = true, pending, closed, operationFailed;

    SqlDraftManagerPane(SqlDraftCoordinator runtime, Function<SqlDraft, Boolean> restore, Runnable restored) {
        this.runtime = runtime;
        this.restore = restore;
        this.restored = restored;
        list.setId("draft-manager-list");
        list.setPlaceholder(new Label("没有可恢复草稿"));
        list.setCellFactory(ignored -> new ListCell<>() {
            @Override protected void updateItem(SqlDraft draft, boolean empty) {
                super.updateItem(draft, empty);
                setText(empty || draft == null ? null : TIME.format(Instant.ofEpochMilli(draft.modifiedAt()))
                        + "  " + preview(draft.connectionName(), 80) + " · " + draft.connectionType()
                        + "\nSchema: " + preview(draft.schema(), 80) + "\n"
                        + (draft.sql().isEmpty() ? "空草稿" : preview(draft.sql(), 120)));
                setGraphic(null);
            }
        });
        sql.setId("draft-manager-sql");
        sql.setEditable(false);
        sql.setPromptText("选择草稿后预览完整 SQL；恢复不会自动连接数据库。");
        list.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            sql.setText(after == null ? "" : after.sql().replace("\r\n", "\n").replace("\r", "\n"));
            renderControls();
        });
        status.setId("draft-manager-status");
        notice.setId("draft-manager-notice");
        notice.setWrapText(true);
        recover.setId("draft-manager-restore");
        refresh.setId("draft-manager-refresh");
        delete.setId("draft-manager-delete");
        clear.setId("draft-manager-clear");
        toggle.setId("draft-manager-toggle");
        recover.setOnAction(event -> {
            if (blocked() || list.getSelectionModel().getSelectedItem() == null) return;
            try {
                if (Boolean.TRUE.equals(restore.apply(list.getSelectionModel().getSelectedItem()))) restored.run();
                else notice.setText("恢复失败，现有草稿和标签保持不变。");
            } catch (RuntimeException failure) {
                notice.setText("恢复失败，现有草稿和标签保持不变。");
            }
        });
        refresh.setOnAction(event -> perform(runtime::refresh));
        delete.setOnAction(event -> {
            SqlDraft selected = list.getSelectionModel().getSelectedItem();
            if (!mutable() || selected == null) return;
            if (confirm("删除所选草稿", "仅删除本机这份恢复记录，不清空已打开的编辑器。"))
                perform(() -> runtime.delete(selected.id()));
        });
        clear.setOnAction(event -> {
            if (mutable() && confirm("清空草稿", "仅删除本机可恢复草稿，不清空编辑器；之后的新修改仍会保存。"))
                perform(runtime::clear);
        });
        toggle.setOnAction(event -> perform(() -> runtime.setEnabled(runtime.mode() != SqlDraftCoordinator.Mode.ENABLED)));
        Label privacy = new Label(SqlDraftEditorBinding.PRIVACY);
        privacy.setWrapText(true);
        SplitPane split = new SplitPane(list, sql);
        split.setDividerPositions(0.42);
        VBox.setVgrow(split, Priority.ALWAYS);
        root.setPadding(new Insets(10));
        root.setPrefSize(860, 520);
        root.getChildren().addAll(status, new FlowPane(8, 4, recover, refresh, delete, clear, toggle),
                notice, split, privacy);
        refreshView();
    }

    Parent getNode() { return root; }

    static String preview(String text, int maximum) {
        if (text == null) return "";
        int length = Math.min(text.length(), maximum);
        StringBuilder value = new StringBuilder(length + 1);
        for (int index = 0; index < length; index++) {
            char c = text.charAt(index);
            value.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (text.length() > length) value.append('…');
        return value.toString();
    }

    void refreshView() {
        if (closed) return;
        SqlDraftCoordinator.ManagementResult current = runtime.lastManagementResult();
        if (current != applied && current != null) {
            applied = current;
            if (current.snapshot() != null) {
                SqlDraft selected = list.getSelectionModel().getSelectedItem();
                UUID selectedId = selected == null ? null : selected.id();
                list.getItems().setAll(current.snapshot().drafts().stream()
                        .sorted(Comparator.comparingLong(SqlDraft::modifiedAt).reversed()).toList());
                list.getSelectionModel().clearSelection();
                if (selectedId != null) list.getItems().stream().filter(item -> item.id().equals(selectedId))
                        .findFirst().ifPresent(list.getSelectionModel()::select);
            }
            operationFailed = !current.succeeded() || current.snapshot() == null;
        }
        renderControls();
        if (initialRefresh && !runtime.managementPending()
                && runtime.mode() != SqlDraftCoordinator.Mode.INITIALIZING) {
            initialRefresh = false;
            if (mutable()) perform(runtime::refresh);
        }
    }

    private boolean blocked() {
        return closed || pending || runtime.managementPending() || runtime.mode() == SqlDraftCoordinator.Mode.CLOSED;
    }

    private boolean mutable() {
        return !blocked() && runtime.mode() != SqlDraftCoordinator.Mode.UNAVAILABLE
                && runtime.mode() != SqlDraftCoordinator.Mode.INITIALIZING;
    }

    private void renderControls() {
        boolean blocked = blocked();
        boolean writable = mutable();
        recover.setDisable(blocked || list.getSelectionModel().getSelectedItem() == null);
        refresh.setDisable(!writable);
        delete.setDisable(!writable || list.getSelectionModel().getSelectedItem() == null);
        clear.setDisable(!writable);
        toggle.setDisable(!writable);
        toggle.setText(runtime.mode() == SqlDraftCoordinator.Mode.ENABLED ? "关闭草稿保护" : "开启草稿保护");
        String state = switch (runtime.mode()) {
            case INITIALIZING -> "草稿保护初始化中，尚未加载草稿";
            case ENABLED -> "草稿保护已开启";
            case DISABLED -> "草稿保护已关闭，已有草稿仍可恢复";
            case PAUSED -> "本次已暂停，设置未保存，下次启动可能恢复";
            case UNAVAILABLE -> "草稿保护不可用；仍可恢复已读取的草稿，请检查本地目录后重启";
            case CLOSED -> "草稿保护已停止";
        };
        status.setText(state + (pending || runtime.managementPending() ? " · 处理中" : "")
                + (applied == null || applied.snapshot() == null ? "" : " · 共 " + list.getItems().size() + " 份草稿"));
        boolean problems = applied != null && applied.snapshot() != null && !applied.snapshot().problems().isEmpty();
        if (operationFailed || problems)
            notice.setText("部分记录未能读取或清理，已保留可恢复内容及未知/损坏文件；请检查后重试。");
    }

    private void perform(Supplier<CompletableFuture<SqlDraftCoordinator.ManagementResult>> operation) {
        if (!mutable()) return;
        pending = true;
        operationFailed = false;
        notice.setText("");
        try {
            operation.get().whenComplete((result, failure) -> Platform.runLater(() -> {
                if (closed) return;
                pending = false;
                refreshView();
                operationFailed = failure != null || result == null || !result.succeeded() || result.snapshot() == null;
                renderControls();
            }));
        } catch (RuntimeException failure) {
            pending = false;
            operationFailed = true;
        }
        renderControls();
    }

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (root.getScene() != null && root.getScene().getWindow() != null)
            alert.initOwner(root.getScene().getWindow());
        alert.setTitle(title);
        alert.setHeaderText(message);
        ButtonType confirm = new ButtonType("确认删除", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(confirm, ButtonType.CANCEL);
        ((Button) alert.getDialogPane().lookupButton(confirm)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return alert.showAndWait().filter(confirm::equals).isPresent();
    }

    @Override public void close() { closed = true; }
}
