package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.CredentialCipher;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.nio.file.Path;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Manual-only fixture: unbound editor with synthetic results, never included in production images. */
public final class ResultCellDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("result-cell-desktop-profile"))
            throw new IllegalStateException("An explicit disposable result-cell-desktop-profile is required");
        // No connections are registered, so neither provider admission nor credentials are used.
        var manager = new ConnectionManager(new CredentialCipher());
        var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation in fixture"); },
                new SqlHistoryStore(profile.resolve("history.json")), new ShortcutSettings(profile.resolve("shortcuts.properties")), runner);
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（合成验收）");
        toggle.setOnAction(event -> theme.toggle());
        VBox root = new VBox(8, new HBox(12, toggle, new Label("合成结果，无连接；NULL / 空字符串 / 长文本")), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS);
        Scene scene = new Scene(root, 980, 800);
        theme.register(scene); theme.installWindowHook();
        stage.setTitle("DataCube - 单元格合成验收"); stage.setScene(scene);
        var result = QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "id", Types.INTEGER, "INTEGER"),
                new ResultColumn(1, "kind", Types.VARCHAR, "VARCHAR"), new ResultColumn(2, "value", Types.VARCHAR, "VARCHAR")),
                List.of(Arrays.asList(1, "NULL", null), List.of(2, "empty", ""), List.of(3, "literal", "NULL"),
                        List.of(4, "multiline", "{\n  \"message\": \"合成内容 😀\",\n  \"note\": \"只读，不执行\"\n}\n" + "long-text ".repeat(20)),
                        List.of(5, "limited", "x".repeat(65_537))), 8, false);
        var publish = SqlEditorPane.class.getDeclaredMethod("showQueryResult", QueryResult.class, String.class);
        publish.setAccessible(true); publish.invoke(pane, result, "select synthetic_fixture");
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
