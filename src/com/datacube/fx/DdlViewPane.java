package com.datacube.fx;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.concurrent.Callable;

/**
 * DDL 查看面板（只读）：异步获取对象的 CREATE 语句，提供复制按钮。
 */
public final class DdlViewPane {

    private final VBox root = new VBox(8);
    private final TextArea textArea = new TextArea();
    private final Label statusLabel = new Label("加载中...");

    /**
     * @param title  面板标题（对象名）
     * @param fetch  DDL 获取逻辑（在工作线程执行，可抛异常）
     */
    public DdlViewPane(String title, Callable<String> fetch) {
        build(title);
        load(fetch);
    }

    public Node getNode() {
        return root;
    }

    private void build(String title) {
        root.setPadding(new Insets(10));
        root.setStyle("-fx-font-family: 'Microsoft YaHei', 'Segoe UI', sans-serif; -fx-font-size: 13px;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold;");

        Button copyBtn = new Button("复制");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(textArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
            statusLabel.setText("已复制到剪贴板");
        });

        HBox toolbar = new HBox(8, titleLabel, copyBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setFont(Font.font("Consolas", 13));
        textArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");

        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        root.getChildren().addAll(toolbar, textArea, statusLabel);
        VBox.setVgrow(textArea, Priority.ALWAYS);
    }

    private void load(Callable<String> fetch) {
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
                if (fErr != null) {
                    textArea.setText("-- 获取 DDL 失败: " + fErr);
                    statusLabel.setText("错误");
                    statusLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 12px;");
                } else {
                    textArea.setText(fDdl);
                    statusLabel.setText("就绪");
                    statusLabel.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 12px;");
                }
            });
        }, "DdlView-Worker").start();
    }
}
