package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Production picker, synthetic metadata only; there is no connection manager or execution service. */
public final class SqlConnectionPickerDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("connection-picker-desktop-profile"))
            throw new IllegalStateException("An isolated connection-picker-desktop-profile is required");
        var settings = new AppSettings(profile.resolve("settings")); var theme = new ThemeManager(settings);
        var configs = new ArrayList<ConnConfig>();
        configs.add(config("demo-pg", DbType.POSTGRESQL, "演示连接"));
        configs.add(config("demo-oracle", DbType.ORACLE, "演示连接"));
        for (int i = 1; i <= 60; i++) configs.add(config("demo-" + i, DbType.POSTGRESQL, "合成报表 " + i));
        configs.add(config("demo-needle", DbType.ORACLE, "归档 中😀 [.*]"));
        Label result = new Label("尚无确认目标；仅验收返回值，不绑定数据库。"); result.setWrapText(true);
        Button open = new Button("选择连接（合成）"), empty = new Button("空列表（合成）");
        Button toggle = new Button("切换明暗（验收）"), width = new Button("窄窗：关（验收）");
        toggle.setOnAction(e -> theme.toggle());
        width.setOnAction(e -> width.setText(width.getText().contains("关") ? "窄窗：开（验收）" : "窄窗：关（验收）"));
        java.util.function.Consumer<List<ConnConfig>> show = choices -> {
            // No owner so the desktop driver can address the dialog itself for keyboard verification.
            // Production callers' ownership, revalidation and passive admission are covered by integration tests.
            var dialog = SqlDraftConnectionChooser.create(choices, null, "DataCube - 合成连接选择");
            theme.applyTo(dialog.getDialogPane()); dialog.setWidth(width.getText().contains("开") ? 480 : 680);
            dialog.showAndWait().ifPresentOrElse(c -> result.setText("确认返回：" + c.id() + " / " + c.type()),
                    () -> result.setText("已取消；没有返回新目标。"));
        };
        open.setOnAction(e -> show.accept(configs)); empty.setOnAction(e -> show.accept(List.of()));
        VBox root = new VBox(12, new FlowPane(8, 8, open, empty, toggle, width), result,
                new Label("仅合成名称、类型和 ID；不使用真实配置，不执行 SQL。"));
        root.setPadding(new Insets(12)); var scene = new Scene(root, 750, 220); theme.register(scene);
        stage.setTitle("DataCube - 连接搜索合成验收"); stage.setScene(scene); stage.show();
    }

    private static ConnConfig config(String id, DbType type, String name) {
        return new ConnConfig(id, name, type, "example.invalid", 1, "synthetic", "", "", Map.of());
    }
}
