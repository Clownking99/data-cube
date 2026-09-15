package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.concurrent.Callable;

/**
 * DDL 查看面板（只读）：异步获取对象的 CREATE 语句，提供复制与后台文本查找。
 * 使用高亮 {@link CodeArea}（行号 + SQL 语法着色）展示。
 */
public final class DdlViewPane implements AutoCloseable {

    private final VBox root = new VBox(8);
    private final CodeArea codeArea = HighlightedSqlArea.create(false);
    private final Label statusLabel = new Label("加载中...");
    private final FxTaskScope tasks;
    private final SqlFindBar findBar;

    /**
     * @param title  面板标题（对象名）
     * @param fetch  DDL 获取逻辑（在工作线程执行，可抛异常）
     * @param runner 应用级虚拟线程运行器
     */
    public DdlViewPane(String title, Callable<String> fetch, FxTaskRunner runner) {
        ConstructionOwner construction = new ConstructionOwner();
        try {
            this.tasks = runner.scope();
            construction.own(tasks::close);
            this.findBar = SqlFindBar.readOnlyDdl(codeArea, tasks);
            construction.own(findBar::detachUi);
            build(title);
            load(fetch);
            construction.commit();
        } catch (Throwable failure) {
            throw construction.close(failure).failure();
        }
    }

    public Node getNode() {
        return root;
    }

    @Override
    public void close() {
        findBar.close();
        tasks.close();
    }

    /** Lightweight FX phase, paired with the thread-safe resource close by the tab owner. */
    void finalizeCloseOnFx() {
        if (!Platform.isFxApplicationThread())
            throw new IllegalStateException("DDL FX finalizer requires the FX thread");
        findBar.detachUi();
        root.setDisable(true);
    }

    private void build(String title) {
        root.setId("ddl-view-pane");
        root.setMinWidth(0);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold;");
        titleLabel.setMinWidth(0);
        titleLabel.maxWidthProperty().bind(root.widthProperty().subtract(20));
        titleLabel.setTooltip(new Tooltip(title));
        codeArea.setId("ddl-text");
        statusLabel.setId("ddl-status");

        Button findBtn = new Button("查找");
        findBtn.setId("ddl-find");
        findBtn.setTooltip(new Tooltip("查找当前只读 DDL (Ctrl+F)"));
        findBtn.setOnAction(e -> { if (!tasks.isClosed()) findBar.show(); });

        Button copyBtn = new Button("复制");
        copyBtn.setOnAction(e -> {
            if (tasks.isClosed()) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(codeArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
            statusLabel.setText("已复制到剪贴板");
        });

        FlowPane toolbar = new FlowPane(8, 4, titleLabel, copyBtn, findBtn);
        toolbar.setMinWidth(0);
        toolbar.setMinHeight(Region.USE_PREF_SIZE);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (tasks.isClosed()) return;
            if (event.getCode() == KeyCode.F && event.isShortcutDown()
                    && !event.isAltDown() && !event.isShiftDown()) {
                findBar.show();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && findBar.getNode().isVisible()) {
                findBar.hide();
                event.consume();
            }
        });

        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        root.getChildren().addAll(toolbar, findBar.getNode(), scroll, statusLabel);
        VBox.setVgrow(scroll, Priority.ALWAYS);
    }

    private void load(Callable<String> fetch) {
        tasks.submit(fetch, ddl -> {
            displayText(ddl == null ? "" : ddl);
            statusLabel.setText("就绪");
            statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
        }, failure -> {
            displayText("-- 获取 DDL 失败: " + message(failure));
            statusLabel.setText("错误");
            statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
        });
    }

    private void displayText(String text) {
        codeArea.replaceText(text);
        codeArea.moveTo(0);
        codeArea.requestFollowCaret();
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
