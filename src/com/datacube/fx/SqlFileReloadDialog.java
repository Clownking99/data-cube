package com.datacube.fx;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/** Explicit replacement confirmation. Enter defaults to cancel, never to discarding edits. */
final class SqlFileReloadDialog {
    static final ButtonType RELOAD = new ButtonType("从磁盘重新加载", ButtonBar.ButtonData.OTHER);
    static final ButtonType CANCEL = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

    static Alert create(Window owner) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "用磁盘上的版本替换当前 SQL？\n\n当前未保存的修改以及撤销/重做记录将被丢弃。"
                        + "如需保留，请取消后先另存为。\n读取失败会保留当前内容；不会写入磁盘或执行 SQL。",
                RELOAD, CANCEL);
        alert.setTitle("重新加载 SQL 文件"); alert.setHeaderText(null); alert.setResizable(true);
        if (owner != null) {
            alert.initOwner(owner);
            if (owner.getScene() != null) alert.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        ((Button) alert.getDialogPane().lookupButton(RELOAD)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(CANCEL)).setDefaultButton(true);
        alert.setOnShown(event -> alert.getDialogPane().lookupButton(CANCEL).requestFocus());
        return alert;
    }

    static boolean confirm(Window owner) { return create(owner).showAndWait().orElse(CANCEL) == RELOAD; }
    private SqlFileReloadDialog() { }
}
