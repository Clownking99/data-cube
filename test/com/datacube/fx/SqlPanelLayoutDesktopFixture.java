package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;

/** Isolated desktop acceptance; synthetic data only, no registered connections. */
public final class SqlPanelLayoutDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("layout-desktop-profile"))
            throw new IllegalStateException("Explicit disposable layout-desktop-profile required");
        Files.createDirectories(profile);
        var manager = new ConnectionManager(new CredentialCipher()); var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation in fixture"); },
                new SqlHistoryStore(profile.resolve("history.json")), new ShortcutSettings(profile.resolve("shortcuts")), runner);
        Path file = profile.resolve("synthetic-layout.sql");
        if (!Files.exists(file)) Files.writeString(file, "select id, name, status from sample;\r\n-- Synthetic data, no database connection.\n");
        var title = new Label();
        pane.installSqlScriptFileController(new SqlScriptFileStore().load(file), new SqlScriptFileStore(),
                new RecentSqlFiles(profile.resolve("recent")), title::setText, "SQL");
        var editorField = SqlEditorPane.class.getDeclaredField("editorArea"); editorField.setAccessible(true);
        var editor = (CodeArea) editorField.get(pane); editor.getUndoManager().forgetHistory();
        var show = SqlEditorPane.class.getDeclaredMethod("showQueryResult", QueryResult.class); show.setAccessible(true);
        show.invoke(pane, QueryResult.query(List.of("id", "name", "status"), IntStream.rangeClosed(1, 30)
                .mapToObj(i -> List.<Object>of(i, "Sample " + i, i % 2 == 0 ? "READY" : "DRAFT")).toList(), 1));
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（验收）"); toggle.setOnAction(event -> theme.toggle());
        Button width = new Button("窄/宽窗口（验收）"); width.setOnAction(event -> stage.setWidth(stage.getWidth() > 700 ? 640 : 980));
        VBox root = new VBox(8, new FlowPane(8, 4, toggle, width, title),
                new Label("仅合成 SQL 和 30 行结果，无数据库连接。请使用真实布局菜单、分隔条和快捷键。"), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS); Scene scene = new Scene(root, 980, 850);
        theme.register(scene); theme.installWindowHook(); stage.setTitle("DataCube - SQL 面板布局合成验收");
        stage.setScene(scene); BrandLogo.applyIcons(stage);
        stage.setOnShown(event -> { editor.moveTo(0); editor.requestFocus(); });
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
