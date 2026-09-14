package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlScriptFileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Disposable synthetic file; no saved user connections, queries or application startup hooks. */
public final class SqlFileReloadDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("sql-reload-desktop-profile"))
            throw new IllegalStateException("Explicit disposable sql-reload-desktop-profile required");
        Files.createDirectories(profile);
        Path file = profile.resolve("synthetic-reload.sql");
        if (Files.exists(file)) throw new IllegalStateException("Use a fresh fixture profile");
        Files.writeString(file, "select 'VERSION_1' as version;\r\n-- Original editor snapshot\n");
        var manager = new ConnectionManager(new CredentialCipher()); var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation"); }, new SqlHistoryStore(profile.resolve("history")),
                new ShortcutSettings(profile.resolve("shortcuts")), runner);
        Label title = new Label();
        pane.installSqlScriptFileController(new SqlScriptFileStore().load(file), new SqlScriptFileStore(),
                new RecentSqlFiles(profile.resolve("recent")), title::setText, "SQL");
        Files.writeString(file, "select 'VERSION_2_DISK' as version;\r\n-- Externally updated synthetic file\n");
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（验收）"); toggle.setOnAction(event -> theme.toggle());
        Button width = new Button("窄/宽窗口（验收）"); width.setOnAction(event -> stage.setWidth(stage.getWidth() > 700 ? 640 : 980));
        VBox root = new VBox(8, new FlowPane(8, 4, toggle, width, title),
                new Label("合成文件：磁盘为 VERSION_2_DISK；使用重新加载核对。无连接，不执行 SQL。"), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS);
        Scene scene = new Scene(root, 980, 850); theme.register(scene); theme.installWindowHook();
        stage.setScene(scene); stage.setTitle("DataCube - SQL 文件重新加载合成验收"); BrandLogo.applyIcons(stage);
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
