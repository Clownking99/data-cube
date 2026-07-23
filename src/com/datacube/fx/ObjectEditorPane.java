package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * 对象编辑器面板：在可编辑高亮 {@link CodeArea} 中修改对象的 DDL 并执行。
 *
 * <p>工具栏提供「执行 / 重新加载 / 复制」；底部状态区逐条汇总执行结果（成功/失败与耗时），
 * 任一单元失败时状态区标红并展示错误信息。执行整段文本（含 spec+body 多单元）。
 */
public final class ObjectEditorPane {

    private final VBox root = new VBox(8);
    private final CodeArea codeArea = HighlightedSqlArea.create(true);
    private final TextArea statusArea = new TextArea();
    private final Label statusLabel = new Label("加载中...");
    private final Button executeBtn = new Button("执行");
    private final Button reloadBtn = new Button("重新加载");

    private final String title;
    private final Callable<String> fetch;
    private final Function<String, List<ScriptOutcome>> executor;

    /**
     * @param title    面板标题（对象名）
     * @param fetch    初始 DDL 获取逻辑（工作线程执行，可抛异常）
     * @param executor 执行器：接收整段文本，返回每单元执行结果（工作线程执行）
     */
    public ObjectEditorPane(String title, Callable<String> fetch,
                            Function<String, List<ScriptOutcome>> executor) {
        this.title = title;
        this.fetch = fetch;
        this.executor = executor;
        build();
        load();
    }

    public Node getNode() {
        return root;
    }

    private void build() {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold;");

        executeBtn.setOnAction(e -> onExecute());
        reloadBtn.setOnAction(e -> onReload());
        Button copyBtn = new Button("复制");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(codeArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
            statusLabel.setText("已复制到剪贴板");
        });

        HBox toolbar = new HBox(8, titleLabel, executeBtn, reloadBtn, copyBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");

        statusArea.setEditable(false);
        statusArea.setWrapText(true);
        statusArea.setPrefRowCount(6);
        statusArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        root.getChildren().addAll(toolbar, scroll, statusLabel, statusArea);
        VBox.setVgrow(scroll, Priority.ALWAYS);
    }

    private void setBusy(boolean busy) {
        executeBtn.setDisable(busy);
        reloadBtn.setDisable(busy);
    }

    /** 加载初始 DDL 到编辑区。 */
    private void load() {
        setBusy(true);
        new Thread(() -> {
            String ddl;
            String err = null;
            try {
                ddl = fetch.call();
            } catch (Exception e) {
                ddl = null;
                err = e.getMessage();
            }
            final String fDdl = ddl;
            final String fErr = err;
            Platform.runLater(() -> {
                setBusy(false);
                if (fErr != null) {
                    codeArea.replaceText("-- 获取 DDL 失败: " + fErr);
                    statusLabel.setText("错误");
                    statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
                } else {
                    codeArea.replaceText(fDdl == null ? "" : fDdl);
                    statusLabel.setText("就绪");
                    statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
                }
            });
        }, "ObjectEditor-Load").start();
    }

    /** 重新加载：确认后重跑初始 DDL 覆盖编辑区（避免误丢改动）。 */
    private void onReload() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "重新加载将丢弃当前编辑内容，确定继续？", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isPresent() && r.get() == ButtonType.OK) {
            load();
        }
    }

    /** 执行整段编辑内容，逐条汇总结果到状态区。 */
    private void onExecute() {
        String ddl = codeArea.getText();
        if (ddl == null || ddl.isBlank()) {
            statusLabel.setText("无内容可执行");
            return;
        }
        setBusy(true);
        statusLabel.setText("执行中...");
        statusLabel.setStyle("-fx-text-fill: -brand-fg-muted; -fx-font-size: 12px;");
        new Thread(() -> {
            List<ScriptOutcome> outcomes;
            String err = null;
            try {
                outcomes = executor.apply(ddl);
            } catch (Exception e) {
                outcomes = null;
                err = e.getMessage();
            }
            final List<ScriptOutcome> fOutcomes = outcomes;
            final String fErr = err;
            Platform.runLater(() -> {
                setBusy(false);
                if (fErr != null) {
                    statusArea.setText("执行失败: " + fErr);
                    statusLabel.setText("失败");
                    statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
                    return;
                }
                renderOutcomes(fOutcomes);
            });
        }, "ObjectEditor-Execute").start();
    }

    private void renderOutcomes(List<ScriptOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        int failed = 0;
        for (ScriptOutcome o : outcomes) {
            QueryResult r = o.result();
            if (r.kind == QueryResult.Kind.ERROR) {
                failed++;
                sb.append("[").append(o.index()).append("] 失败 (").append(r.elapsedMillis)
                        .append("ms): ").append(r.errorMessage).append('\n');
            } else if (r.kind == QueryResult.Kind.UPDATE) {
                ok++;
                sb.append("[").append(o.index()).append("] 成功 (").append(r.elapsedMillis)
                        .append("ms), 影响 ").append(r.updateCount).append(" 行\n");
            } else {
                ok++;
                sb.append("[").append(o.index()).append("] 成功 (").append(r.elapsedMillis)
                        .append("ms), 返回 ").append(r.rows.size()).append(" 行\n");
            }
        }
        statusArea.setText(sb.toString());
        if (failed > 0) {
            statusLabel.setText("完成：成功 " + ok + "，失败 " + failed);
            statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
        } else {
            statusLabel.setText("全部成功：" + ok + " 条");
            statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
        }
    }
}
