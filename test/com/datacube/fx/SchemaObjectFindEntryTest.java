package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.ConnectionTreePane.Kind;
import com.datacube.fx.ConnectionTreePane.NodeData;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.spi.model.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectFindEntryTest {
    @TempDir Path directory;

    @Test void schemaMenuOffersSearchWithoutReadingMetadataOnRender() throws Exception {
        try (var runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                var probe = new DraftConnectionProbe();
                var actions = (ConnectionTreePane.Actions) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{ConnectionTreePane.Actions.class}, (proxy, method, args) -> {
                            throw new AssertionError("No action before confirmation");
                        });
                try (var pane = new ConnectionTreePane(new ConnectionStore(directory.resolve("connections")),
                        probe.manager, null, new SessionContext(), actions, runner)) {
                    @SuppressWarnings("unchecked") var tree = (TreeView<NodeData>) pane.getNode().lookup("#connection-tree");
                    ConnConfig conn = TableSelectSqlTabsTest.config("source", DbType.POSTGRESQL, "name");
                    var connection = new TreeItem<>(new NodeData(Kind.CONNECTION, "name", conn, conn.id(), null, null));
                    var schema = new TreeItem<>(new NodeData(Kind.SCHEMA, "Exact schema", null, conn.id(), "Exact schema", "Exact schema"));
                    connection.getChildren().add(schema); connection.setExpanded(true);
                    tree.getRoot().getChildren().add(connection);
                    VBox root = new VBox(pane.getNode()); new Scene(root, 480, 500); root.resize(480, 500);
                    root.applyCss(); root.layout();
                    TreeCell<?> cell = tree.lookupAll(".tree-cell").stream()
                            .filter(n -> n instanceof TreeCell<?> c && c.getTreeItem() == schema)
                            .map(n -> (TreeCell<?>) n).findFirst().orElseThrow();
                    assertTrue(cell.getContextMenu().getItems().stream()
                            .anyMatch(m -> "tree-find-schema-objects".equals(m.getId())), "Schema needs an object search entry");
                    assertEquals(0, probe.providers.get()); assertEquals(0, probe.network.get());
                }
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"POSTGRESQL", "ORACLE"})
    void confirmationUsesClickedSchemaAndExactRefWithoutChangingTreeSelection(DbType type) throws Exception {
        try (var runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                try (var f = new Fixture(runner, type)) {
                    var other = new TreeItem<>(new NodeData(Kind.SCHEMA, "other", null, "source", "other", "other"));
                    f.connection.getChildren().add(other); f.tree.getSelectionModel().select(other);
                    f.pane.schemaObjectFindItem(f.schema, (connection, schema, allowed) -> {
                        assertEquals(f.config, connection); assertEquals("Exact Schema", schema); assertTrue(allowed.getAsBoolean());
                        assertTrue(f.calls.isEmpty());
                        return java.util.Optional.of(new TableRef(schema, "Exact\" name"));
                    }).fire();
                    assertEquals(List.of(List.of(f.config, new TableRef("Exact Schema", "Exact\" name"))), f.calls);
                    assertSame(other, f.tree.getSelectionModel().getSelectedItem());
                    assertEquals(0, f.probe.providers.get()); assertEquals(0, f.probe.network.get());
                }
                return null;
            });
        }
    }

    @ParameterizedTest @CsvSource({"removed,false", "value,false", "connection,false", "root,false", "closed,false",
            "removed,true", "value,true", "connection,true", "root,true", "closed,true"})
    void staleMenuAndChangedSourceDuringPickerCannotGenerate(String state, boolean duringPicker) throws Exception {
        try (var runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                try (var f = new Fixture(runner, DbType.POSTGRESQL)) {
                    int[] launches = {0};
                    var menu = f.pane.schemaObjectFindItem(f.schema, (connection, schema, allowed) -> {
                        launches[0]++; assertTrue(allowed.getAsBoolean()); f.invalidate(state);
                        assertFalse(allowed.getAsBoolean()); return java.util.Optional.of(new TableRef(schema, "late"));
                    });
                    if (!duringPicker) f.invalidate(state);
                    menu.fire();
                    assertEquals(duringPicker ? 1 : 0, launches[0]); assertTrue(f.calls.isEmpty());
                    assertEquals(0, f.probe.providers.get()); assertEquals(0, f.probe.network.get());
                }
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "wrong-schema", "empty-name", "redis", "status"})
    void ineligibleSourceOrPickerResultCannotGenerate(String state) throws Exception {
        try (var runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                try (var f = new Fixture(runner, state.equals("redis") ? DbType.REDIS : DbType.POSTGRESQL)) {
                    if (state.equals("status")) f.schema.setValue(ConnectionTreePane.statusData(f.schema.getValue(), "loading"));
                    f.pane.schemaObjectFindItem(f.schema, (connection, schema, allowed) -> {
                        assertFalse(state.equals("redis") || state.equals("status"), "unsupported sources must not open picker");
                        return state.equals("cancel") ? java.util.Optional.empty()
                                : java.util.Optional.of(new TableRef(state.equals("wrong-schema") ? "other" : schema,
                                state.equals("empty-name") ? "" : "t"));
                    }).fire();
                    assertTrue(f.calls.isEmpty()); assertEquals(0, f.probe.network.get());
                }
                return null;
            });
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final List<List<Object>> calls = new java.util.ArrayList<>();
        final ConnConfig config;
        final ConnectionTreePane pane;
        final TreeView<NodeData> tree;
        final TreeItem<NodeData> connection, schema;
        @SuppressWarnings("unchecked") Fixture(FxTaskRunner runner, DbType type) {
            var actions = (ConnectionTreePane.Actions) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ConnectionTreePane.Actions.class}, (proxy, method, args) -> {
                        assertEquals("openSelectSql", method.getName(), "search must not open data or execute SQL");
                        calls.add(List.of(args)); return null;
                    });
            pane = new ConnectionTreePane(new ConnectionStore(directory.resolve("empty")), probe.manager,
                    null, new SessionContext(), actions, runner);
            tree = (TreeView<NodeData>) pane.getNode().lookup("#connection-tree");
            config = TableSelectSqlTabsTest.config("source", type, "name");
            connection = new TreeItem<>(new NodeData(Kind.CONNECTION, "name", config, config.id(), null, null));
            schema = new TreeItem<>(new NodeData(Kind.SCHEMA, "Exact Schema", null, config.id(), "Exact Schema", "Exact Schema"));
            connection.getChildren().add(schema); tree.getRoot().getChildren().add(connection);
        }
        void invalidate(String state) {
            switch (state) {
                case "removed" -> connection.getChildren().clear();
                case "value" -> schema.setValue(new NodeData(Kind.SCHEMA, "new", null, "source", "new", "new"));
                case "connection" -> connection.setValue(new NodeData(Kind.CONNECTION, "new",
                        TableSelectSqlTabsTest.config("source", DbType.POSTGRESQL, "new"), "source", null, null));
                case "root" -> tree.setRoot(new TreeItem<>());
                case "closed" -> pane.close();
                default -> throw new AssertionError(state);
            }
        }
        @Override public void close() { pane.close(); }
    }
}
