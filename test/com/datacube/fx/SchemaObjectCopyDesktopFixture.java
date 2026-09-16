package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.spi.model.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Real dialog and clipboard adapter with a memory-only writer; no OS clipboard or database access. */
public final class SchemaObjectCopyDesktopFixture extends Application {
    private final FxTaskRunner runner = new FxTaskRunner();
    private final DraftConnectionProbe probe = new DraftConnectionProbe();
    private SchemaObjectSearchDialog picker;
    private boolean failWrites;
    private int copies;
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage unused) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("schema-object-copy-desktop-profile"))
            throw new IllegalStateException("Explicit disposable schema-object-copy-desktop-profile required");
        var connection = new ConnConfig("synthetic-id", "synthetic_copy_demo", DbType.POSTGRESQL,
                "example.invalid", 1, "synthetic", "", "", Map.of());
        probe.manager.register(connection);
        var clipboard = new ConnectionTreeClipboard(probe.manager, text -> {
            if (failWrites) throw new IllegalStateException("synthetic private diagnostic");
            picker.dialog().setTitle("内存剪贴板 [" + (++copies) + "]：" + text);
            return true;
        });
        picker = SchemaObjectSearchDialog.create(connection.name(), "Exact Schema", null,
                () -> List.of(new TableInfo("Exact Schema", "Order \"Quoted\" 中😀", TableInfo.Kind.TABLE, null),
                        new TableInfo("Exact Schema", "order_report", TableInfo.Kind.VIEW, null),
                        new TableInfo("Exact Schema", "customers", TableInfo.Kind.TABLE, null)), runner, () -> true);
        picker.installCopyAction(ref -> clipboard.copyResult(connection, ref));
        var dialog = picker.dialog();
        dialog.setTitle("合成复制验收 · F6 明暗 · F7 窄窗 · F8 复制故障");
        ThemeManager theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties")));
        theme.register(dialog.getDialogPane().getScene());
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) { theme.toggle(); event.consume(); }
            else if (event.getCode() == KeyCode.F7) {
                dialog.setWidth(dialog.getWidth() > 600 ? 480 : 680); event.consume();
            } else if (event.getCode() == KeyCode.F8) {
                failWrites = !failWrites;
                dialog.setTitle("合成复制验收 · 复制故障：" + failWrites + " · 成功写入：" + copies); event.consume();
            }
        });
        var cleanup = dialog.getOnHidden();
        dialog.setOnHidden(event -> { cleanup.handle(event); Platform.exit(); });
        dialog.show();
    }
    @Override public void stop() {
        if (picker != null) picker.close(); runner.close();
        if (probe.providers.get() + probe.sessions.get() + probe.metadata.get() + probe.network.get() != 0)
            throw new AssertionError("Fixture must stay offline");
    }
}
