package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Only synthetic returned outcomes; never submits SQL or registers a connection. */
public final class SqlScriptDetailsDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("script-details-desktop-profile"))
            throw new IllegalStateException("Explicit disposable script-details-desktop-profile required");
        Files.createDirectories(profile);
        var manager = new ConnectionManager(new CredentialCipher()); var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation in fixture"); },
                new SqlHistoryStore(profile.resolve("history.json")), new ShortcutSettings(profile.resolve("shortcuts")), runner);
        Path file = profile.resolve("synthetic-details.sql");
        if (!Files.exists(file)) Files.writeString(file, "select id from sample;\r\nselect status from sample;\ndrop table sample cascade constraints;\n-- Synthetic script; no SQL will be executed.\n");
        pane.installSqlScriptFileController(new SqlScriptFileStore().load(file), new SqlScriptFileStore(),
                new RecentSqlFiles(profile.resolve("recent")), ignored -> { }, "SQL");
        var show = SqlEditorPane.class.getDeclaredMethod("showScriptResults", List.class, long.class); show.setAccessible(true);
        List<ScriptOutcome> outcomes = List.of(
                new ScriptOutcome(1, "select id from sample;", QueryResult.query(List.of("id"), List.of(List.of(1)), 12)),
                new ScriptOutcome(2, "update sample set status = 'READY';", QueryResult.update(18, 3)),
                new ScriptOutcome(3, "select status from sample;", QueryResult.query(List.of("status", "message"), List.of(List.of("READY", "second query result"), List.of("WAITING", "another row")), 8)),
                new ScriptOutcome(4, "select missing_column from sample;\n-- <script>literal text</script>", QueryResult.error("Synthetic diagnostic: missing_column does not exist.\n" + "Detailed context for investigating the returned failure. ".repeat(5) + "\nSQLState=42703", 5)),
                new ScriptOutcome(5, "select slow_result from sample;", QueryResult.timeout("Synthetic timeout; transaction outcome is not inferred.", 1000)),
                new ScriptOutcome(6, "select cancelled_result from sample;", QueryResult.cancelled("Synthetic cancellation; no rollback is assumed.", 50)));
        show.invoke(pane, outcomes, 1093L);
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（验收）"); toggle.setOnAction(event -> theme.toggle());
        Button width = new Button("窄/宽窗口（验收）"); width.setOnAction(event -> stage.setWidth(stage.getWidth() > 700 ? 640 : 980));
        Button replace = new Button("替换为查询（验收）"); replace.setOnAction(event -> {
            try { show.invoke(pane, List.of(outcomes.getFirst()), 12L); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        Button restore = new Button("恢复批量结果（验收）"); restore.setOnAction(event -> {
            try { show.invoke(pane, outcomes, 1093L); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        Button normal = new Button("仅正常批次（验收）"); normal.setOnAction(event -> {
            try { show.invoke(pane, outcomes.subList(0, 2), 30L); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        VBox root = new VBox(8, new FlowPane(8, 4, toggle, width, replace, restore, normal),
                new Label("合成执行结果验收：无数据库连接、不执行 SQL、不写源文件。"), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS); Scene scene = new Scene(root, 980, 850);
        theme.register(scene); theme.installWindowHook(); stage.setTitle("DataCube - 脚本执行详情合成验收");
        stage.setScene(scene); BrandLogo.applyIcons(stage);
        stage.setOnCloseRequest(event -> {
            event.consume(); root.setDisable(true);
            Thread.startVirtualThread(() -> {
                pane.closeResources(); runner.close(); manager.closeAll();
                Platform.runLater(() -> { pane.finalizeCloseOnFx(); stage.setOnCloseRequest(null); stage.close(); });
            });
        });
        stage.show();
    }
}
