package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Manual-only UI fixture. Not part of the production module or any release artifact. */
public final class TableSelectSqlDesktopFixture extends Application {
    public static void main(String[] args) { launch(args); }

    @Override @SuppressWarnings("unchecked") public void start(Stage stage) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (!profile.getFileName().toString().equals("select-desktop-profile"))
            throw new IllegalStateException("An explicit disposable select-desktop-profile is required");
        ConnConfig pg = connection("synthetic-pg", "Demo PostgreSQL", DbType.POSTGRESQL);
        ConnConfig oracle = connection("synthetic-oracle", "Demo Oracle", DbType.ORACLE);
        ConnectionStore store = new ConnectionStore(profile.resolve(".datacube/connections.json"));
        store.saveAll(List.of(pg, oracle));
        List<ConnConfig> saved = store.loadAll();
        AppShell shell = new AppShell();
        Scene scene = new Scene(shell.getRoot(), 1200, 800);
        shell.getThemeManager().register(scene);
        shell.getThemeManager().installWindowHook();
        stage.setTitle("DataCube - SELECT 合成验收"); stage.setScene(scene);
        shell.getRoot().applyCss(); shell.getRoot().layout();
        TreeView<ConnectionTreePane.NodeData> tree = (TreeView<ConnectionTreePane.NodeData>) shell.getRoot().lookup("#connection-tree");
        // Replace lazy branches with synthetic, already loaded nodes; there is no loader to invoke.
        tree.getRoot().getChildren().setAll(List.of(branch(saved.get(0), " Sales ", "Order\"Line", ConnectionTreePane.Kind.TABLE),
                branch(saved.get(1), "REPORTING", "Monthly View", ConnectionTreePane.Kind.VIEW)));
        stage.setOnCloseRequest(event -> {
            event.consume(); shell.getRoot().setDisable(true);
            shell.shutdownAsync().whenComplete((outcome, failure) -> Platform.runLater(() -> {
                if (failure != null || outcome != ShutdownOutcome.COMPLETED) {
                    shell.getRoot().setDisable(false); return;
                }
                stage.setOnCloseRequest(null); stage.close();
            }));
        });
        stage.show(); shell.enableNativeTitleBarTheming(stage.getTitle());
    }

    private static TreeItem<ConnectionTreePane.NodeData> branch(ConnConfig connection, String schema,
            String name, ConnectionTreePane.Kind kind) {
        var root = new TreeItem<>(new ConnectionTreePane.NodeData(ConnectionTreePane.Kind.CONNECTION,
                connection.name(), connection, connection.id(), null, null));
        var owner = new TreeItem<>(new ConnectionTreePane.NodeData(ConnectionTreePane.Kind.SCHEMA,
                schema, null, connection.id(), schema, schema));
        owner.getChildren().add(new TreeItem<>(new ConnectionTreePane.NodeData(kind, name, null,
                connection.id(), schema, name)));
        root.getChildren().add(owner); owner.setExpanded(true); root.setExpanded(true);
        return root;
    }

    private static ConnConfig connection(String id, String name, DbType type) {
        return new ConnConfig(id, name, type, "example.invalid", 1, "synthetic", "", "", Map.of());
    }
}
