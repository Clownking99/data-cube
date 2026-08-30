package com.datacube.fx;

import com.datacube.config.SqlDraftCoordinator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;

/** FX-only subscription and close bridge; the application runtime owns disk work. */
final class SqlDraftEditorBinding implements AutoCloseable {
  static final String PRIVACY = "SQL 草稿仅保存于本机，可能含敏感文本；保留7天，可关闭或清空。关闭草稿不停止原有 SQL 历史记录。";
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
  private final SqlDraftCoordinator runtime;
  private final SqlDraftCoordinator.Handle handle;
  private final CodeArea editor;
  private final TextField schema;
  private final Consumer<SqlDraftEditorBinding> detached;
  private final Label status = new Label();
  private final Label notice = new Label();
  private final Button retry = new Button("重试保存");
  private final Button toggle = new Button();
  private final Button clear = new Button("清空草稿");
  private final VBox root;
  private final ChangeListener<String> changes = (observable, before, after) -> edited();
  private boolean closed, closing, priorEditable, priorSchemaDisabled, managementPending;
  private CompletableFuture<Boolean> closeAttempt;

  SqlDraftEditorBinding(
      SqlDraftCoordinator runtime,
      UUID id,
      Long savedAt,
      CodeArea editor,
      TextField schema,
      SqlDraftCoordinator.Source source,
      Consumer<SqlDraftEditorBinding> detached) {
    this.runtime = runtime;
    this.editor = editor;
    this.schema = schema;
    this.detached = detached;
    status.setId("sql-draft-status");
    status.setWrapText(true);
    retry.setId("sql-draft-retry");
    toggle.setId("sql-draft-toggle");
    clear.setId("sql-draft-clear");
    Label privacy = new Label(PRIVACY);
    privacy.setWrapText(true);
    privacy.setId("sql-draft-privacy");
    notice.setWrapText(true);
    notice.setId("sql-draft-notice");
    FlowPane controls = new FlowPane(8, 4, status, retry, toggle, clear);
    root = new VBox(3, controls, notice, privacy);
    root.setId("sql-draft-protection");
    handle = runtime.attach(id, savedAt, source);
    try {
      editor.textProperty().addListener(changes);
      schema.textProperty().addListener(changes);
      retry.setOnAction(
          event -> {
            if (!closing && !closed) {
              handle.retry();
              runtime.pulse();
              refresh();
            }
          });
      toggle.setOnAction(
          event -> {
            if (closing || closed || managementPending) return;
            manage(runtime.setEnabled(runtime.mode() != SqlDraftCoordinator.Mode.ENABLED));
          });
      clear.setOnAction(
          event -> {
            if (closing || closed || managementPending) return;
            if (confirm("清空草稿", "清空仅删除本机可恢复草稿，不清空编辑器；之后的新修改仍会保存。是否继续？", "清空"))
              manage(runtime.clear());
          });
      refresh();
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  Node getNode() {
    return root;
  }

  UUID id() { return handle.id(); }

  boolean closing() {
    return closing || closed;
  }

  void edited() {
    if (!closed && !closing) {
      handle.edited();
      refresh();
    }
  }

  void refresh() {
    if (closed) return;
    var snapshot = handle.status();
    String text =
        switch (snapshot.mode()) {
          case INITIALIZING -> "草稿保护初始化中，尚未确认保存";
          case DISABLED -> "草稿保护已关闭";
          case PAUSED -> "本次已暂停，关闭设置未保存，下次启动可能恢复";
          case UNAVAILABLE -> failureMessage(snapshot.failureReason());
          case CLOSED -> "草稿保护已停止";
          case ENABLED ->
              switch (snapshot.saveStatus()) {
                case EMPTY -> "草稿保护已开启";
                case WAITING -> "草稿待保存";
                case SAVING -> "正在保存草稿";
                case SAVED -> "草稿已保存于 " + TIME.format(Instant.ofEpochMilli(snapshot.savedAt()));
                case FAILED -> failureMessage(snapshot.failureReason());
              };
        };
    status.setText(text);
    boolean canRetry =
        snapshot.mode() == SqlDraftCoordinator.Mode.ENABLED
            && snapshot.saveStatus() == SqlDraftCoordinator.SaveStatus.FAILED;
    retry.setVisible(canRetry);
    retry.setManaged(canRetry);
    retry.setDisable(closing);
    toggle.setText(snapshot.mode() == SqlDraftCoordinator.Mode.ENABLED ? "关闭草稿保护" : "开启草稿保护");
    boolean unavailable =
        snapshot.mode() == SqlDraftCoordinator.Mode.INITIALIZING
            || snapshot.mode() == SqlDraftCoordinator.Mode.UNAVAILABLE
            || snapshot.mode() == SqlDraftCoordinator.Mode.CLOSED;
    toggle.setDisable(closing || managementPending || unavailable);
    clear.setDisable(closing || managementPending || unavailable);
    notice.setVisible(!notice.getText().isEmpty());
    notice.setManaged(notice.isVisible());
  }

  static String failureMessage(SqlDraftCoordinator.FailureReason reason) {
    if (reason == null) return "草稿保存失败，最新修改尚未保存，可重试";
    return switch (reason) {
      case CLEANUP -> "草稿保护不可用：可能残留含敏感 SQL 的临时文件。请检查本机草稿目录，修复后重启；不会自动重试。";
      case CAPACITY -> "草稿容量不足（最多100条、合计32 MiB），最新修改尚未保存。请先复制文本另存，再清理不需要的草稿或重试。";
      case INVALID_DRAFT -> "草稿内容无法保存（SQL最多1 MiB UTF-8、每项元数据最多4096字节，需有效Unicode）。请先复制文本另存并检查长度和字符；原记录保留。";
      case CAPTURE -> "无法获取草稿快照，最新修改尚未保存。请先复制文本另存，再重试。";
      case UNAVAILABLE -> "草稿保护不可用，请检查本地目录后重启";
      default -> "草稿保存失败，最新修改尚未保存，可重试";
    };
  }

  private void manage(CompletableFuture<SqlDraftCoordinator.ManagementResult> operation) {
    managementPending = true;
    notice.setText("");
    refresh();
    operation.whenComplete(
        (result, failure) ->
            Platform.runLater(
                () -> {
                  if (closed) return;
                  managementPending = false;
                  if (failure != null || result == null || !result.succeeded() || result.snapshot() == null) {
                    notice.setText("草稿操作未完成，已有可恢复草稿及其他文件可能仍然保留。");
                  } else if (!result.snapshot().problems().isEmpty()) {
                    notice.setText("可恢复草稿操作已完成；仍保留损坏、未知或不可读取的文件，可能包含敏感 SQL。本次未删除这些文件，请检查本机草稿目录。");
                  }
                  refresh();
                }));
  }

  void freeze() {
    if (closing || closed) return;
    priorEditable = editor.isEditable();
    priorSchemaDisabled = schema.isDisable();
    closing = true;
    editor.setEditable(false);
    schema.setDisable(true);
    refresh();
  }

  CompletableFuture<Boolean> prepareClose(boolean mandatory) {
    if (closeAttempt != null) return closeAttempt.copy();
    freeze();
    CompletableFuture<Boolean> attempt = new CompletableFuture<>();
    closeAttempt = attempt;
    try {
      handle
          .flush()
          .whenComplete(
              (unused, failure) -> {
                try {
                  Platform.runLater(
                      () -> {
                        if (closed) {
                          attempt.complete(false);
                          return;
                        }
                        try {
                          refresh();
                          boolean allow =
                              failure == null
                                  || (!mandatory
                                      && confirm(
                                          "最新草稿未保存",
                                          "最新修改尚未保存。取消关闭后可重试保存；放弃仅跳过本次保存，不删除已有草稿，也不关闭草稿保护。",
                                          "放弃本次最新修改并关闭"));
                          if (!allow) reopen();
                          attempt.complete(allow);
                        } catch (Throwable decisionFailure) {
                          reopen();
                          attempt.completeExceptionally(decisionFailure);
                        }
                      });
                } catch (Throwable dispatchFailure) {
                  attempt.completeExceptionally(dispatchFailure);
                }
              });
    } catch (Throwable failure) {
      reopen();
      attempt.completeExceptionally(failure);
    }
    return attempt.copy();
  }

  void reopen() {
    if (!closing || closed) return;
    closing = false;
    closeAttempt = null;
    editor.setEditable(priorEditable);
    schema.setDisable(priorSchemaDisabled);
    refresh();
  }

  private boolean confirm(String title, String message, String acceptText) {
    ButtonType accept = new ButtonType(acceptText, ButtonBar.ButtonData.OTHER);
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, accept);
    alert.setTitle(title);
    alert.setHeaderText(null);
    if (editor.getScene() != null && editor.getScene().getWindow() != null)
      alert.initOwner(editor.getScene().getWindow());
    ((Button) alert.getDialogPane().lookupButton(accept)).setDefaultButton(false);
    ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
    return alert.showAndWait().orElse(ButtonType.CANCEL) == accept;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    editor.textProperty().removeListener(changes);
    schema.textProperty().removeListener(changes);
    handle.detach();
    detached.accept(this);
  }
}
