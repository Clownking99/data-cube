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

/** Manual-only wide result fixture; no connection or real user profile, excluded from production. */
public final class ResultColumnDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("result-column-desktop-profile"))
            throw new IllegalStateException("An explicit disposable result-column-desktop-profile is required");
        var manager = new ConnectionManager(new CredentialCipher());
        var runner = new FxTaskRunner();
        var settings = new AppSettings(profile.resolve("settings.properties"));
        var pane = SqlEditorPane.openSqlFile(new SessionContext(), manager, new ObjectTreeService(manager), settings,
                (id, table) -> { throw new AssertionError("No database navigation in fixture"); },
                new SqlHistoryStore(profile.resolve("history.json")), new ShortcutSettings(profile.resolve("shortcuts.properties")), runner);
        var theme = new ThemeManager(settings);
        Button toggle = new Button("切换明暗（合成验收）"); toggle.setOnAction(event -> theme.toggle());
        VBox root = new VBox(8, new HBox(12, toggle, new Label("12 列合成宽表；原列 11 隐藏，末两列同名")), pane.getNode());
        VBox.setVgrow(pane.getNode(), Priority.ALWAYS);
        Scene scene = new Scene(root, 980, 760);
        theme.register(scene); theme.installWindowHook();
        stage.setTitle("DataCube - 查找列合成验收"); stage.setScene(scene);
        var columns = IntStream.range(0, 12).mapToObj(i -> new ResultColumn(i,
                i >= 10 ? "customer_note" : "field_" + (i + 1), Types.VARCHAR, "VARCHAR")).toList();
        var rows = IntStream.range(0, 3).mapToObj(row -> IntStream.range(0, 12)
                .mapToObj(col -> (Object) ("row-" + (row + 1) + " / original-column-" + (col + 1))).toList()).toList();
        var publish = SqlEditorPane.class.getDeclaredMethod("showQueryResult", QueryResult.class, String.class);
        publish.setAccessible(true); publish.invoke(pane, QueryResult.queryWithMetadata(columns, rows, 8, false), "select synthetic_fixture");
        var tableField = SqlEditorPane.class.getDeclaredField("resultTable"); tableField.setAccessible(true);
        @SuppressWarnings("unchecked") var table = (TableView<ObservableList<Object>>) tableField.get(pane);
        table.getColumns().stream().filter(column -> column.getUserData() instanceof Integer index && index >= 0)
                .forEach(column -> column.setPrefWidth(180));
        table.getColumns().get(11).setVisible(false);
        var refresh = SqlEditorPane.class.getDeclaredMethod("renderResultFilterToolbar"); refresh.setAccessible(true); refresh.invoke(pane);
        table.getSelectionModel().select(1, table.getColumns().get(1));
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
