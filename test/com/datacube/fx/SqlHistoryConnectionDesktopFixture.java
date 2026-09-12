package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.RecentSqlFiles;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Real history dialog + managed SQL tabs, with all provider/network attempts trapped by a test probe. */
public final class SqlHistoryConnectionDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("history-connection-desktop-profile"))
            throw new IllegalStateException("An isolated history-connection-desktop-profile is required");
        var probe = new DraftConnectionProbe(); var runner = new FxTaskRunner();
        var tabs = new ContentTabPane(); var drafts = new SqlDraftUi(profile.resolve("drafts"), tabs);
        var registry = new SqlFileTabRegistry(); var created = new ArrayList<SqlEditorPane>();
        var settings = new AppSettings(profile.resolve("settings")); var theme = new ThemeManager(settings);
        var history = new SqlHistoryStore(profile.resolve("history")); var recent = new RecentSqlFiles(profile.resolve("recent"));
        var choices = List.of(
                new ConnConfig("demo-pg", "演示连接", DbType.POSTGRESQL, "example.invalid", 1, "synthetic", "", "", Map.of()),
                new ConnConfig("demo-oracle", "演示连接", DbType.ORACLE, "example.invalid", 1, "synthetic", "", "", Map.of()));
        choices.forEach(probe.manager::register);
        var entries = List.of(new SqlHistoryStore.Entry(1789200000000L, "演示连接", "SALES",
                "select 'history 中😀' as label;\n-- 合成历史：未连接、未执行"));
        Button restore = new Button("找回合成历史（验收）");
        restore.setOnAction(event -> {
            var dialog = SqlHistoryDialog.create(entries, stage, theme);
            dialog.showAndWait().ifPresent(entry -> SqlHistoryTabs.open(tabs, entry, schema -> {
                var pane = SqlEditorPane.openSqlHistory(new SessionContext(), probe.manager,
                        new ObjectTreeService(probe.manager), settings, (id, table) -> { throw new AssertionError("No designer"); },
                        schema, history, new ShortcutSettings(profile.resolve("shortcuts")), runner);
                created.add(pane); return pane;
            }, () -> choices, new SqlScriptFileStore(), recent, new AppShell.SqlFileDraftLifecycle() {
                @Override public void bind(SqlEditorPane pane) { drafts.bind(pane); }
                @Override public void installed(javafx.scene.Node content) { drafts.installed(content); }
            }, registry));
        });
        Label counts = new Label();
        Runnable refresh = () -> counts.setText("仅合成数据 · provider=" + probe.providers.get() + " session=" + probe.sessions.get()
                + " metadata=" + probe.metadata.get() + " network=" + probe.network.get());
        refresh.run(); Button check = new Button("检查离线计数（验收）"); check.setOnAction(e -> refresh.run());
        Button toggle = new Button("切换明暗（验收）"); toggle.setOnAction(e -> theme.toggle());
        Button width = new Button("窄/宽窗口（验收）"); width.setOnAction(e -> stage.setWidth(stage.getWidth() > 700 ? 640 : 1000));
        VBox root = new VBox(6, new FlowPane(8, 4, restore, check, toggle, width), counts, tabs.getNode());
        root.setPadding(new Insets(6)); VBox.setVgrow(tabs.getNode(), Priority.ALWAYS);
        var scene = new Scene(root, 1000, 800); theme.register(scene); theme.installWindowHook();
        stage.setTitle("DataCube - 历史连接合成验收"); stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            event.consume(); root.setDisable(true);
            Thread.startVirtualThread(() -> {
                created.forEach(SqlEditorPane::closeResources); drafts.closeFromBackground(); runner.close(); probe.manager.closeAll();
                Platform.runLater(() -> { created.forEach(SqlEditorPane::finalizeCloseOnFx); stage.setOnCloseRequest(null); stage.close(); });
            });
        });
        stage.show();
    }
}
