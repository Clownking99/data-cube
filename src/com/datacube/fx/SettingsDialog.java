package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.AppSettings.CommentMode;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * 全局设置对话框（模态）。当前仅含“SQL 结果字段注释显示”一项。
 *
 * <p>点击“确定”将选择写回 {@link AppSettings}（触发持久化与属性通知，
 * 已打开的结果表会即时重渲染表头）；“取消”不改动。
 */
public final class SettingsDialog {

    private SettingsDialog() {}

    /** 打开模态设置对话框。 */
    public static void show(AppSettings settings, Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("设置");
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);

        ToggleGroup group = new ToggleGroup();
        RadioButton off = new RadioButton("不显示");
        off.setUserData(CommentMode.OFF);
        off.setToggleGroup(group);
        RadioButton hover = new RadioButton("悬停显示（鼠标移到表头显示注释）");
        hover.setUserData(CommentMode.HOVER);
        hover.setToggleGroup(group);
        RadioButton inline = new RadioButton("固定显示（表头两行：字段名 + 注释）");
        inline.setUserData(CommentMode.INLINE);
        inline.setToggleGroup(group);

        switch (settings.getCommentMode()) {
            case OFF -> off.setSelected(true);
            case INLINE -> inline.setSelected(true);
            default -> hover.setSelected(true);
        }

        VBox options = new VBox(8, off, hover, inline);
        options.setPadding(new Insets(10));
        TitledPane group1 = new TitledPane("SQL 结果字段注释", options);
        group1.setCollapsible(false);

        Label hint = new Label("仅对映射到真实表列的字段有效；表达式、别名、聚合列无注释。");
        hint.setStyle("-fx-text-fill: #888; -fx-font-size: 12px;");
        hint.setWrapText(true);

        VBox content = new VBox(10, group1, hint);
        content.setPadding(new Insets(12));
        content.setPrefWidth(360);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK && group.getSelectedToggle() != null) {
                settings.setCommentMode((CommentMode) group.getSelectedToggle().getUserData());
            }
        });
    }
}
