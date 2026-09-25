package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.*;
import java.util.*;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Real editing/result controls; only in-memory providers and newly created synthetic files. */
public final class G8WorkflowDesktopFixture extends Application {
    public static final class Launcher {
        public static void main(String[] args) { Application.launch(G8WorkflowDesktopFixture.class, args); }
    }
    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("g8-workflow-desktop-profile"))
            throw new IllegalStateException("Exclusive G8 workflow profile required");
        var settings = new AppSettings(profile.resolve("settings"));
        var theme = new ThemeManager(settings); var runner = new FxTaskRunner();
        var writable = new GridSaveProbe(DbType.POSTGRESQL, false, "PRODUCTION");
        var readOnly = new GridSaveProbe(DbType.ORACLE, true, "PRODUCTION");
        var grid = new DataGridPane(new DataBrowseService(writable.manager), writable.edit, "target", "synthetic",
                new TableRef("synthetic", "items"), settings, false, runner);
        var roGrid = new DataGridPane(new DataBrowseService(readOnly.manager), readOnly.edit, "target", "synthetic-readonly",
                new TableRef("synthetic", "items"), settings, true, runner);
        var probe = new DraftConnectionProbe();
        var sql = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager), settings,
                (id, table) -> { throw new AssertionError("No live navigation"); },
                new SqlHistoryStore(profile.resolve("synthetic-history")), new ShortcutSettings(profile.resolve("shortcuts")), runner);
        Path file = profile.resolve("synthetic.sql");
        Files.writeString(file, "select 'alpha' as label;\nselect 'beta' as label;\n-- 合成结果由夹具注入；执行入口保持离线。\n");
        var title = new Label(); var fileStore = new SqlScriptFileStore();
        sql.installSqlScriptFileController(fileStore.load(file), fileStore, new RecentSqlFiles(profile.resolve("recent")), title::setText, "SQL");
        var display = SqlEditorPane.class.getDeclaredMethod("showScriptResults", List.class, long.class, String.class);
        display.setAccessible(true);
        display.invoke(sql, List.of(
                new ScriptOutcome(1, "select 'alpha' as label", QueryResult.query(List.of("id", "label"), List.of(List.of(1, "alpha"), List.of(2, "中 😀")), 12)),
                new ScriptOutcome(2, "select 'beta' as label", QueryResult.query(List.of("id", "label"), List.of(List.of(3, "beta"), List.of(4, "second")), 7)),
                new ScriptOutcome(3, "synthetic failure", QueryResult.error("合成失败：结果演示，不来自数据库", 2))), 21L, "synthetic");
        TabPane tabs = new TabPane(tab("SQL / 批次结果", sql.getNode()), tab("生产目标 / 显式保存（mock）", grid.getNode()), tab("Oracle 只读（mock）", roGrid.getNode()));
        Button toggle = new Button("切换明暗"), width = new Button("760/1200 宽"), count = new Button("刷新合成计数");
        Label counts = new Label("零真实连接；表格读写均为内存 mock。SQL 未连接且不会执行。");
        toggle.setOnAction(e -> theme.toggle()); width.setOnAction(e -> stage.setWidth(stage.getWidth() > 850 ? 760 : 1200));
        Runnable report = () -> {
            String value = "SQL network=" + probe.network.get() + " mock writes=" + writable.jdbc.executes.get()
                    + " commits=" + writable.jdbc.commits.get() + " readonly writes=" + readOnly.jdbc.executes.get();
            counts.setText(value); System.out.println(value);
        };
        count.setOnAction(e -> report.run());
        VBox root = new VBox(6, new FlowPane(8, 4, toggle, width, count, title), counts, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        Scene scene = new Scene(root, 1200, 850); theme.register(scene); theme.installWindowHook();
        stage.setScene(scene); stage.setTitle("DataCube G8 合成工作流验收");
        stage.setOnShown(e -> System.out.println("SYNTHETIC_OUTPUT_SCALE=" + stage.getOutputScaleX() + "x" + stage.getOutputScaleY()));
        stage.setOnCloseRequest(e -> {
            e.consume(); root.setDisable(true); report.run();
            Thread.startVirtualThread(() -> {
                sql.closeResources(); grid.closeResources(); roGrid.closeResources(); runner.close();
                probe.manager.closeAll(); writable.close(); readOnly.close();
                Platform.runLater(() -> { sql.finalizeCloseOnFx(); grid.finalizeCloseOnFx(); roGrid.finalizeCloseOnFx(); stage.setOnCloseRequest(null); stage.close(); });
            });
        }); stage.show();
    }
    private static Tab tab(String title, Node content) { Tab tab = new Tab(title, content); tab.setClosable(false); return tab; }
}
