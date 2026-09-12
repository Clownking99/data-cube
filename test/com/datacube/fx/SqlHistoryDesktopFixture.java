package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.SqlHistoryStore.Entry;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Uses the production dialog with synthetic entries only; never reads user SQL history. */
public final class SqlHistoryDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("history-desktop-profile"))
            throw new IllegalStateException("An explicit disposable history-desktop-profile is required");
        Platform.setImplicitExit(false);
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var theme = new ThemeManager(settings);
        var entries = List.of(
                new Entry(1789200000000L, "演示 PostgreSQL", "public", "select '中😀' as label;\n-- 最新合成历史，无数据库连接"),
                new Entry(1789196400000L, "演示 Oracle", "SALES", "select order_id, amount\nfrom orders\nwhere amount > 100;"),
                new Entry(1789192800000L, null, null, "select '[.*]' as literal;"));
        Label status = new Label("合成历史验收：不连接、不执行、不读取真实历史");
        status.setWrapText(true);
        java.util.function.BiConsumer<AppSettings.Theme, Boolean> open = (mode, empty) -> {
            settings.setTheme(mode);
            var dialog = SqlHistoryDialog.create(empty ? List.of() : entries, null, theme);
            dialog.setOnHidden(event -> {
                status.setText(dialog.getResult() == null ? "已取消：没有载入候选"
                        : "确认候选（未执行）：\n" + dialog.getResult().sql());
                stage.show();
            });
            stage.hide(); dialog.show();
            if (mode == AppSettings.Theme.LIGHT) { dialog.setWidth(600); dialog.setHeight(500); }
        };
        Button dark = new Button("打开历史（暗色）"); dark.setOnAction(e -> open.accept(AppSettings.Theme.DARK, false));
        Button light = new Button("打开历史（亮色窄窗）"); light.setOnAction(e -> open.accept(AppSettings.Theme.LIGHT, false));
        Button empty = new Button("空历史（验收）"); empty.setOnAction(e -> open.accept(AppSettings.Theme.DARK, true));
        VBox root = new VBox(12, new FlowPane(8, 8, dark, light, empty), status); root.setPadding(new Insets(16));
        var scene = new Scene(root, 680, 220); theme.register(scene); theme.installWindowHook();
        stage.setScene(scene); stage.setTitle("DataCube - SQL 历史合成验收");
        stage.setOnCloseRequest(e -> Platform.exit());
        open.accept(AppSettings.Theme.DARK, false);
    }
}
