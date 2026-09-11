package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.ConnectionTreePane.Kind;
import com.datacube.fx.ConnectionTreePane.NodeData;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.spi.model.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionTreeQualifiedCopyTest {
    @TempDir Path directory;

    @ParameterizedTest @CsvSource({"POSTGRESQL,TABLE", "POSTGRESQL,VIEW", "ORACLE,TABLE", "ORACLE,VIEW"})
    void actualCellMenuCopiesItsOriginalTargetWithoutChangingSelectionOrCallingOtherActions(DbType type, Kind kind) throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                DraftConnectionProbe probe = new DraftConnectionProbe();
                List<String> writes = new ArrayList<>();
                ConnConfig source = TableSelectSqlTabsTest.config("source", type, "Same name");
                ConnConfig other = TableSelectSqlTabsTest.config("other", type, "Same name");
                probe.manager.register(source); probe.manager.register(other);
                SessionContext session = new SessionContext();
                try (ConnectionTreePane pane = new ConnectionTreePane(new ConnectionStore(directory.resolve("connections")),
                        probe.manager, null, session, null, runner, text -> { writes.add(text); return true; })) {
                    TreeView<NodeData> tree = tree(pane);
                    TreeItem<NodeData> parent = connection(source);
                    TreeItem<NodeData> target = node(kind, "source", " Sales ", "Order\"Line");
                    parent.getChildren().add(target); parent.setExpanded(true);
                    TreeItem<NodeData> otherConnection = connection(other);
                    tree.getRoot().getChildren().addAll(List.of(parent, otherConnection));
                    VBox box = new VBox(pane.getNode()); new Scene(box, 400, 650); box.resize(400, 650);
                    box.applyCss(); box.layout();
                    TreeCell<?> cell = tree.lookupAll(".tree-cell").stream()
                            .filter(n -> n instanceof TreeCell<?> c && c.getTreeItem() == target)
                            .map(n -> (TreeCell<?>) n).findFirst().orElseThrow();
                    MenuItem copy = cell.getContextMenu().getItems().stream()
                            .filter(m -> "tree-copy-qualified-name".equals(m.getId())).findFirst().orElseThrow();
                    assertEquals("复制限定名称", copy.getText());
                    assertTrue(writes.isEmpty());
                    tree.getSelectionModel().select(otherConnection);
                    copy.fire();
                    assertEquals(List.of("\" Sales \".\"Order\"\"Line\""), writes);
                    assertSame(otherConnection, tree.getSelectionModel().getSelectedItem());
                    assertEquals(other, session.getActiveConnection());
                    assertEquals("已复制限定名称；粘贴前请确认目标连接。",
                            ((Label) pane.getNode().lookup("#tree-object-copy-status")).getText());
                    assertTrue(cell.getContextMenu().getItems().stream().anyMatch(m -> "tree-generate-select".equals(m.getId())));
                    assertTrue(cell.getContextMenu().getItems().stream().anyMatch(m -> "查看数据".equals(m.getText())));
                    ConnectionTreeClipboardTest.assertOffline(probe);
                    pane.reload();
                    assertFalse(pane.getNode().lookup("#tree-object-copy-status").isManaged());
                    assertEquals(1, writes.size());
                }
                return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"removed", "replaced-value", "changed-parent", "reload", "closed", "status", "wrong-connection", "redis", "null-target", "registry-changed"})
    void staleOrIneligibleMenuCannotWriteClipboard(String state) throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                DraftConnectionProbe probe = new DraftConnectionProbe();
                ConnConfig source = TableSelectSqlTabsTest.config("source", state.equals("redis") ? DbType.REDIS : DbType.POSTGRESQL, "Demo");
                probe.manager.register(source);
                try (ConnectionTreePane pane = new ConnectionTreePane(new ConnectionStore(directory.resolve("connections")),
                        probe.manager, null, new SessionContext(), null, runner, text -> { fail("stale menu wrote clipboard"); return false; })) {
                    TreeItem<NodeData> parent = connection(source);
                    TreeItem<NodeData> target = node(state.equals("status") ? Kind.STATUS : Kind.TABLE,
                            state.equals("wrong-connection") ? "wrong" : "source", "s", "t");
                    parent.getChildren().add(target); tree(pane).getRoot().getChildren().add(parent);
                    MenuItem copy = pane.copyQualifiedNameItem(state.equals("null-target") ? null : target);
                    switch (state) {
                        case "removed" -> parent.getChildren().clear();
                        case "replaced-value" -> target.setValue(node(Kind.TABLE, "source", "s", "changed").getValue());
                        case "changed-parent" -> parent.setValue(connection(TableSelectSqlTabsTest.config("source", DbType.POSTGRESQL, "Changed")).getValue());
                        case "reload" -> pane.reload();
                        case "closed" -> pane.close();
                        case "registry-changed" -> probe.manager.register(TableSelectSqlTabsTest.config("source", DbType.ORACLE, "Demo"));
                        default -> { }
                    }
                    copy.fire();
                    ConnectionTreeClipboardTest.assertOffline(probe);
                }
                return null;
            });
        }
    }

    @SuppressWarnings("unchecked") private static TreeView<NodeData> tree(ConnectionTreePane pane) {
        return (TreeView<NodeData>) pane.getNode().lookup("#connection-tree");
    }
    private static TreeItem<NodeData> connection(ConnConfig connection) {
        return new TreeItem<>(new NodeData(Kind.CONNECTION, connection.name(), connection, connection.id(), null, null));
    }
    private static TreeItem<NodeData> node(Kind kind, String connectionId, String schema, String name) {
        return new TreeItem<>(new NodeData(kind, name, null, connectionId, schema, name));
    }
}
