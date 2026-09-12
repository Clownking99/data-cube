package com.datacube.fx;

import com.datacube.config.AppSettings;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Isolated real dialog/table; one visible window at a time for deterministic desktop input routing. */
public final class ResultRowLocateDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override public void start(Stage stage) {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("row-locate-desktop-profile"))
            throw new IllegalStateException("An explicit disposable row-locate-desktop-profile is required");
        Platform.setImplicitExit(false);
        var table = new TableView<ObservableList<Object>>(); table.setFixedCellSize(26);
        table.getSelectionModel().setCellSelectionEnabled(true);
        var id = new TableColumn<ObservableList<Object>, String>("id"); id.setUserData(0); id.setPrefWidth(160);
        id.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().getFirst().toString()));
        var value = new TableColumn<ObservableList<Object>, String>("note"); value.setUserData(1); value.setPrefWidth(340);
        value.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue().get(1).toString()));
        table.getColumns().setAll(List.of(id, value));
        for (int row = 1; row <= 150; row++) table.getItems().add(FXCollections.observableArrayList(row, "合成记录 " + row));
        table.getSelectionModel().clearAndSelect(4, value); table.getFocusModel().focus(4, value);
        var selected = new Label();
        Runnable update = () -> selected.setText("当前显示第 " + (table.getFocusModel().getFocusedCell().getRow() + 1) + " 行 · 选中 "
                + table.getSelectionModel().getSelectedCells().size() + " 格");
        table.getFocusModel().focusedCellProperty().addListener(ignored -> update.run()); update.run();
        var open = new Button("打开行定位（合成验收）");
        var root = new VBox(10, open, selected, table); VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        var scene = new Scene(root, 700, 470); stage.setScene(scene); stage.setTitle("结果行定位合成验收");
        var theme = new ThemeManager(new AppSettings(profile.resolve("settings.properties"))); theme.register(scene); theme.installWindowHook();
        Runnable show = () -> {
            stage.hide();
            var dialog = new ResultRowLocateDialog(table, null, (row, column) -> true);
            dialog.setTitle("行定位合成验收 · F6 明暗 · F7 窄窗 · F8 重排失效");
            theme.register(dialog.getDialogPane().getScene());
            dialog.setOnHidden(e -> { stage.show(); update.run(); });
            dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.F6) { event.consume(); theme.toggle(); }
                else if (event.getCode() == KeyCode.F7) {
                    event.consume(); dialog.setWidth(dialog.getWidth() > 500 ? 480 : 720); dialog.setHeight(350);
                } else if (event.getCode() == KeyCode.F8) { event.consume(); FXCollections.reverse(table.getItems()); }
            });
            dialog.show();
        };
        open.setOnAction(e -> show.run()); stage.setOnCloseRequest(e -> Platform.exit());
        // Realize the table skin before the first dialog; scroll requests then survive switching windows.
        root.applyCss(); root.layout(); show.run();
    }
}
