package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Synthetic path index only: production menu/dialog, no SQL reads or database services. */
public final class RecentSqlFilesDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("recent-sql-desktop-profile"))
            throw new IllegalStateException("Disposable recent-sql-desktop-profile required");
        var recent = new RecentSqlFiles(profile.resolve("recent"));
        recent.record(profile.resolve("archive/same.sql"));
        recent.record(profile.resolve("reporting/same.sql"));
        recent.record(profile.resolve("中😀/[literal].sql"));
        var theme = new ThemeManager(new AppSettings(profile.resolve("settings")));
        var result = new Label("尚未打开；仅记录返回路径，不读取 SQL 文件。"); result.setWrapText(true);
        var menu = new MenuButton("SQL 文件");
        menu.setOnShowing(event -> AppShell.rebuildSqlFilesMenu(menu, recent,
                () -> result.setText("新建回调"), () -> result.setText("打开选择器回调"),
                path -> result.setText("确认路径（未读取）：" + path)));
        var keyboard = new Button("最近文件（独立键盘验收）");
        keyboard.setOnAction(event -> {
            var dialog = RecentSqlFilesDialog.create(recent.recent(), null, () -> true);
            theme.applyTo(dialog.getDialogPane()); dialog.setWidth(480);
            dialog.showAndWait().ifPresentOrElse(path -> result.setText("确认路径（未读取）：" + path),
                    () -> result.setText("已取消；没有打开文件。"));
        });
        var toggle = new Button("切换明暗（验收）"); toggle.setOnAction(event -> theme.toggle());
        var root = new VBox(12, new FlowPane(8, 8, menu, keyboard, toggle), result,
                new Label("仅合成路径；没有连接管理器、查询服务或真实脚本文件。"));
        root.setPadding(new Insets(12)); var scene = new Scene(root, 720, 220); theme.register(scene);
        stage.setTitle("DataCube - 最近文件合成验收"); stage.setScene(scene); stage.show();
    }
}
