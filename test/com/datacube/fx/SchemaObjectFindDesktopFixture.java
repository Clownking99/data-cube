package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.spi.model.TableInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Actual dialog and runner, synthetic names only; never constructs database services. */
public final class SchemaObjectFindDesktopFixture extends Application {
    private final FxTaskRunner runner = new FxTaskRunner();
    private SchemaObjectSearchDialog picker;
    private ThemeManager theme;
    private Stage resultStage;
    private TextArea result;
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("schema-object-find-desktop-profile"))
            throw new IllegalStateException("Explicit disposable schema-object-find-desktop-profile required");
        Platform.setImplicitExit(false);
        theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        resultStage = stage; result = new TextArea(); result.setEditable(false); result.setWrapText(true);
        Button reopen = new Button("重新打开合成查找"); reopen.setOnAction(e -> { stage.hide(); open(); });
        Button exit = new Button("退出验收"); exit.setOnAction(e -> Platform.exit());
        VBox box = new VBox(12, result, reopen, exit); box.setPadding(new Insets(12));
        Scene scene = new Scene(box, 640, 280); theme.register(scene);
        stage.setScene(scene); stage.setTitle("Schema 查找合成验收结果 · 无数据库或执行服务");
        stage.setOnCloseRequest(e -> Platform.exit());
        open();
    }
    private void open() {
        CountDownLatch release = new CountDownLatch(1); AtomicBoolean fail = new AtomicBoolean();
        picker = SchemaObjectSearchDialog.create("synthetic_long_connection_name_for_layout_only", "Exact Schema", null, () -> {
            release.await();
            if (fail.getAndSet(false)) throw new java.sql.SQLException("synthetic private diagnostic");
            return List.of(new TableInfo("Exact Schema", "orders", TableInfo.Kind.TABLE, null),
                    new TableInfo("Exact Schema", "order_report", TableInfo.Kind.VIEW, null),
                    new TableInfo("Exact Schema", "Order \"Quoted\" 中😀", TableInfo.Kind.TABLE, null),
                    new TableInfo("Exact Schema", "customers", TableInfo.Kind.TABLE, null));
        }, runner, () -> true);
        var dialog = picker.dialog();
        dialog.setTitle("Schema 合成查找 · F8 加载 · F9 失败 · F6 明暗 · F7 窄窗");
        theme.register(dialog.getDialogPane().getScene());
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F8) { release.countDown(); event.consume(); }
            else if (event.getCode() == KeyCode.F9) { fail.set(true); release.countDown(); event.consume(); }
            else if (event.getCode() == KeyCode.F6) { theme.toggle(); event.consume(); }
            else if (event.getCode() == KeyCode.F7) {
                dialog.setWidth(dialog.getWidth() > 600 ? 480 : 680); event.consume();
            }
        });
        var cleanup = dialog.getOnHidden();
        dialog.setOnHidden(event -> {
            cleanup.handle(event);
            theme.unregister(dialog.getDialogPane().getScene());
            result.setText(dialog.getResult() == null ? "已取消，未返回对象。" : "已确认对象：\nSchema："
                    + dialog.getResult().schema() + "\n名称：" + dialog.getResult().name()
                    + "\n生产入口将生成未执行的 SELECT；本夹具仅展示返回值。");
            result.positionCaret(0); resultStage.show();
        });
        dialog.show();
    }
    @Override public void stop() { if (picker != null) picker.close(); runner.close(); }
}
