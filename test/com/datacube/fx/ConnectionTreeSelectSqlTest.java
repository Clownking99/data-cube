package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.ConnectionTreePane.Kind;
import com.datacube.fx.ConnectionTreePane.NodeData;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.spi.model.*;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionTreeSelectSqlTest {
    @TempDir Path directory;

    @ParameterizedTest @CsvSource({"POSTGRESQL,TABLE", "POSTGRESQL,VIEW", "ORACLE,TABLE", "ORACLE,VIEW"})
    void actualCellMenuDispatchesOnlyTheClickedObjectRegardlessOfSelection(DbType type, Kind kind) throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                DraftConnectionProbe probe = new DraftConnectionProbe();
                List<List<Object>> calls = new ArrayList<>();
                try (ConnectionTreePane pane = pane(probe, runner, calls)) {
                    TreeView<NodeData> tree = tree(pane);
                    ConnConfig conn = TableSelectSqlTabsTest.config("source", type, "name");
                    TreeItem<NodeData> parent = new TreeItem<>(new NodeData(Kind.CONNECTION, "name", conn, conn.id(), null, null));
                    TreeItem<NodeData> target = node(kind, "Exact schema", "Exact\" object");
                    TreeItem<NodeData> other = node(kind, "different", "different");
                    parent.getChildren().addAll(List.of(target, other)); parent.setExpanded(true);
                    tree.getRoot().getChildren().add(parent);
                    VBox box = new VBox(pane.getNode()); new Scene(box, 400, 650); box.resize(400, 650);
                    box.applyCss(); box.layout();
                    TreeCell<?> cell = tree.lookupAll(".tree-cell").stream()
                            .filter(n -> n instanceof TreeCell<?> c && c.getTreeItem() == target)
                            .map(n -> (TreeCell<?>) n).findFirst().orElseThrow();
                    MenuItem generated = cell.getContextMenu().getItems().stream()
                            .filter(m -> "tree-generate-select".equals(m.getId())).findFirst().orElseThrow();
                    assertTrue(generated.getText().contains("不执行"));
                    assertTrue(calls.isEmpty());
                    tree.getSelectionModel().select(other);
                    generated.fire();
                    assertEquals(List.of(List.of(conn, new TableRef("Exact schema", "Exact\" object"))), calls);
                    assertSame(other, tree.getSelectionModel().getSelectedItem());
                    assertTrue(cell.getContextMenu().getItems().stream().anyMatch(m -> m.getText().equals("查看数据")));
                    assertEquals(0, probe.providers.get()); assertEquals(0, probe.network.get());
                }
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"removed", "replaced-value", "reload", "closed", "status", "wrong-connection", "redis"})
    void staleOrIneligibleMenuCannotOpenSql(String state) throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                DraftConnectionProbe probe = new DraftConnectionProbe();
                List<List<Object>> calls = new ArrayList<>();
                try (ConnectionTreePane pane = pane(probe, runner, calls)) {
                    ConnConfig conn = TableSelectSqlTabsTest.config(state.equals("wrong-connection") ? "wrong" : "source",
                            state.equals("redis") ? DbType.REDIS : DbType.POSTGRESQL, "name");
                    TreeItem<NodeData> parent = new TreeItem<>(new NodeData(Kind.CONNECTION, "name", conn, conn.id(), null, null));
                    TreeItem<NodeData> target = node(state.equals("status") ? Kind.STATUS : Kind.TABLE, "s", "t");
                    parent.getChildren().add(target); tree(pane).getRoot().getChildren().add(parent);
                    MenuItem menu = pane.selectSqlItem(target);
                    switch (state) {
                        case "removed" -> parent.getChildren().clear();
                        case "replaced-value" -> target.setValue(node(Kind.TABLE, "s", "new").getValue());
                        case "reload" -> pane.reload();
                        case "closed" -> pane.close();
                        default -> { }
                    }
                    menu.fire();
                    assertTrue(calls.isEmpty());
                    assertEquals(0, probe.providers.get()); assertEquals(0, probe.network.get());
                }
                return null;
            });
        }
    }

    private ConnectionTreePane pane(DraftConnectionProbe probe, FxTaskRunner runner, List<List<Object>> calls) {
        var actions = (ConnectionTreePane.Actions) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConnectionTreePane.Actions.class}, (proxy, method, args) -> {
                    assertEquals("openSelectSql", method.getName(), "generation must not browse, execute or edit an object");
                    calls.add(List.of(args)); return null;
                });
        return new ConnectionTreePane(new ConnectionStore(directory.resolve("connections")), probe.manager,
                null, new SessionContext(), actions, runner);
    }
    @SuppressWarnings("unchecked") private static TreeView<NodeData> tree(ConnectionTreePane pane) {
        return (TreeView<NodeData>) pane.getNode().lookup("#connection-tree");
    }
    private static TreeItem<NodeData> node(Kind kind, String schema, String name) {
        return new TreeItem<>(new NodeData(kind, name, null, "source", schema, name));
    }
}
