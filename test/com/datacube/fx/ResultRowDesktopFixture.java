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
import java.util.stream.IntStream;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Disposable, unbound row-inspection fixture. Never packaged in the production runtime. */
public final class ResultRowDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("result-row-desktop-profile"))
            throw new IllegalStateException("An explicit disposable result-row-desktop-profile is required");
        var manager = new ConnectionManager(new CredentialCipher()); var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation in fixture"); },
                new SqlHistoryStore(profile.resolve("history.json")), new ShortcutSettings(profile.resolve("shortcuts.properties")), runner);
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（合成验收）"); toggle.setOnAction(event -> theme.toggle());
        VBox root = new VBox(8, new HBox(12, toggle, new Label("行详情合成验收；无连接，原列 9 已隐藏")), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS); Scene scene = new Scene(root, 980, 780);
        theme.register(scene); theme.installWindowHook();
        stage.setTitle("DataCube - 行详情合成验收"); stage.setScene(scene);
        var names = List.of("id", "name", "note", "nullable", "empty", "literal", "created", "binary", "hidden_only", "long_text", "same", "same");
        var columns = IntStream.range(0, names.size()).mapToObj(i -> new ResultColumn(i, names.get(i),
                i == 7 ? Types.BINARY : Types.VARCHAR, i == 7 ? "BINARY" : "VARCHAR")).toList();
        var row = Arrays.<Object>asList("1", "synthetic-one", "<html>只读内容</html>\n  第二行 😀", null, "", "NULL",
                "2026-09-11", new byte[]{1, 2, 15}, "hidden-fixture-value", "first line\n" + "long-text ".repeat(600), "left", "right");
        var publish = SqlEditorPane.class.getDeclaredMethod("showQueryResult", QueryResult.class, String.class);
        publish.setAccessible(true); publish.invoke(pane, QueryResult.queryWithMetadata(columns, List.of(row), 8, false), "select synthetic_fixture");
        var tableField = SqlEditorPane.class.getDeclaredField("resultTable"); tableField.setAccessible(true);
        @SuppressWarnings("unchecked") var table = (TableView<ObservableList<Object>>) tableField.get(pane);
        table.getColumns().get(9).setVisible(false);
        var refresh = SqlEditorPane.class.getDeclaredMethod("renderResultFilterToolbar"); refresh.setAccessible(true); refresh.invoke(pane);
        table.getSelectionModel().clearAndSelect(0, table.getColumns().get(3)); table.getFocusModel().focus(0, table.getColumns().get(3));
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
