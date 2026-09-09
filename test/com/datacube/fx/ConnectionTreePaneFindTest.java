package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionTreePaneFindTest {
    @TempDir Path directory;

    @Test void savedConnectionFindRefreshAndLegacyTypingNeverConnectExecuteOrWriteConfiguration() throws Exception {
        Path file = directory.resolve("connections.json");
        ConnectionStore store = new ConnectionStore(file);
        store.saveAll(List.of(config("a", "East PostgreSQL", DbType.POSTGRESQL),
                config("b", "East Oracle", DbType.ORACLE), config("c", "West Redis", DbType.REDIS)));
        byte[] before = Files.readAllBytes(file);
        DraftConnectionProbe probe = new DraftConnectionProbe();
        var actions = (ConnectionTreePane.Actions) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConnectionTreePane.Actions.class}, (proxy, method, args) -> {
                    throw new AssertionError("Find must not invoke object action: " + method.getName());
                });
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxUiTestSupport.call(() -> {
                SessionContext session = new SessionContext();
                try (ConnectionTreePane pane = new ConnectionTreePane(store, probe.manager,
                        new ObjectTreeService(probe.manager), session, actions, runner)) {
                    new Scene(new VBox(pane.getNode()));
                    TreeView<?> tree = (TreeView<?>) pane.getNode().lookup("#connection-tree");
                    TextField query = (TextField) pane.getNode().lookup("#connection-tree-find-query");
                    query.setText("east");
                    assertNull(session.getActiveConnection());
                    assertNull(tree.getSelectionModel().getSelectedItem());
                    query.fireEvent(new ActionEvent());
                    assertEquals("a", session.getActiveConnection().id());
                    query.fireEvent(new ActionEvent());
                    assertEquals("b", session.getActiveConnection().id());
                    Object old = tree.getSelectionModel().getSelectedItem();
                    pane.reload();
                    query.fireEvent(new ActionEvent());
                    assertNotSame(old, tree.getSelectionModel().getSelectedItem());
                    assertEquals("a", session.getActiveConnection().id());
                    assertEquals(List.of("a", "b", "c"), pane.connectionConfigsSnapshot().stream().map(ConnConfig::id).toList());
                    assertTrue(tree.getRoot().getChildren().stream().noneMatch(item -> item.isExpanded()));
                    query.clear();
                    tree.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "w", "w", KeyCode.UNDEFINED, false, false, false, false));
                    assertEquals("c", session.getActiveConnection().id(), "legacy type-to-find still works");
                    assertFalse(probe.manager.isConnected("a"));
                    assertFalse(probe.manager.isConnected("b"));
                    assertFalse(probe.manager.isConnected("c"));
                }
                return null;
            });
        }
        assertEquals(0, probe.providers.get());
        assertEquals(0, probe.sessions.get());
        assertEquals(0, probe.metadata.get());
        assertEquals(0, probe.network.get());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private static ConnConfig config(String id, String name, DbType type) {
        return new ConnConfig(id, name, type, "example.invalid", 1, "synthetic", "", "", Map.of());
    }
}
