package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.AppSettings.CommentMode;
import com.datacube.config.JvmOptions;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
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
    public static void show(AppSettings settings, Window owner, ThemeManager themeManager) {
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

        // ---------- 性能与资源 ----------
        Spinner<Integer> rowsSpinner = new Spinner<>(0, 1_000_000, settings.getMaxResultRows(), 500);
        rowsSpinner.setEditable(true);
        rowsSpinner.setPrefWidth(140);
        HBox rowsRow = new HBox(8, new Label("最大结果行数："), rowsSpinner, new Label("（0 = 不限制）"));
        rowsRow.setAlignment(Pos.CENTER_LEFT);

        Spinner<Integer> heapSpinner = new Spinner<>(128, 8192, settings.getMaxHeapMb(), 128);
        heapSpinner.setEditable(true);
        heapSpinner.setPrefWidth(140);
        HBox heapRow = new HBox(8, new Label("最大内存(MB)："), heapSpinner, new Label("（重启后生效）"));
        heapRow.setAlignment(Pos.CENTER_LEFT);

        Label memHint = new Label("最大内存写入启动器配置，需重启程序才能生效；开发环境（非安装/免安装包）不支持修改。");
        memHint.setStyle("-fx-text-fill: #888; -fx-font-size: 12px;");
        memHint.setWrapText(true);

        VBox perfBox = new VBox(8, rowsRow, heapRow, memHint);
        perfBox.setPadding(new Insets(10));
        TitledPane group2 = new TitledPane("性能与资源", perfBox);
        group2.setCollapsible(false);

        // ---------- 外观主题 ----------
        ToggleGroup themeGroup = new ToggleGroup();
        RadioButton darkTheme = new RadioButton("暗色");
        darkTheme.setUserData(AppSettings.Theme.DARK);
        darkTheme.setToggleGroup(themeGroup);
        RadioButton lightTheme = new RadioButton("亮色");
        lightTheme.setUserData(AppSettings.Theme.LIGHT);
        lightTheme.setToggleGroup(themeGroup);
        if (settings.getTheme() == AppSettings.Theme.LIGHT) {
            lightTheme.setSelected(true);
        } else {
            darkTheme.setSelected(true);
        }
        HBox themeRow = new HBox(16, darkTheme, lightTheme);
        themeRow.setAlignment(Pos.CENTER_LEFT);
        themeRow.setPadding(new Insets(10));
        TitledPane themePane = new TitledPane("外观主题", themeRow);
        themePane.setCollapsible(false);

        VBox content = new VBox(10, themePane, group1, hint, group2);
        content.setPadding(new Insets(12));
        content.setPrefWidth(380);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (themeManager != null) themeManager.applyTo(dialog.getDialogPane());

        dialog.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                if (themeGroup.getSelectedToggle() != null) {
                    settings.setTheme((AppSettings.Theme) themeGroup.getSelectedToggle().getUserData());
                }
                if (group.getSelectedToggle() != null) {
                    settings.setCommentMode((CommentMode) group.getSelectedToggle().getUserData());
                }
                Integer rows = rowsSpinner.getValue();
                if (rows != null) settings.setMaxResultRows(rows);
                Integer heap = heapSpinner.getValue();
                boolean heapChanged = heap != null && heap != settings.getMaxHeapMb();
                if (heap != null) settings.setMaxHeapMb(heap);
                // 将最大堆写入启动器配置（下次启动生效），仅在变更时提示
                if (heapChanged) {
                    boolean applied = JvmOptions.applyMaxHeap(settings.getMaxHeapMb());
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            applied
                                    ? "最大内存已写入启动器配置，重启程序后生效。"
                                    : "当前为开发/非打包运行，无法写入启动器配置；已保存设置值。",
                            ButtonType.OK);
                    info.setHeaderText(null);
                    if (owner != null) info.initOwner(owner);
                    info.showAndWait();
                }
            }
        });
    }
}
