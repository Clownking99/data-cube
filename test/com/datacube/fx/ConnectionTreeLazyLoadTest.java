package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import javafx.collections.ListChangeListener;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionTreeLazyLoadTest {
    @TempDir Path directory;

    @Test void attachedConnectionAndNestedObjectsAcceptCurrentCompletion() {
        var root = new TreeItem<ConnectionTreePane.NodeData>();
        var connection = new TreeItem<ConnectionTreePane.NodeData>();
        var schema = new TreeItem<ConnectionTreePane.NodeData>();
        var group = new TreeItem<ConnectionTreePane.NodeData>();
        root.getChildren().add(connection);
        connection.getChildren().add(schema);
        schema.getChildren().add(group);
        assertTrue(ConnectionTreePane.loadCallbackAllowed(connection, root, 4, 4));
        assertTrue(ConnectionTreePane.loadCallbackAllowed(schema, root, 4, 4));
        assertTrue(ConnectionTreePane.loadCallbackAllowed(group, root, 4, 4));
        assertFalse(ConnectionTreePane.loadCallbackAllowed(root, root, 4, 4));
        assertFalse(ConnectionTreePane.loadCallbackAllowed(null, root, 4, 4));
    }

    @Test void detachedReplacedAndStaleNodesRejectCompletion() {
        var root = new TreeItem<ConnectionTreePane.NodeData>();
        var connection = new TreeItem<ConnectionTreePane.NodeData>();
        var schema = new TreeItem<ConnectionTreePane.NodeData>();
        root.getChildren().add(connection);
        connection.getChildren().add(schema);
        assertFalse(ConnectionTreePane.loadCallbackAllowed(schema, root, 3, 4));
        assertFalse(ConnectionTreePane.loadCallbackAllowed(schema, new TreeItem<>(), 4, 4));
        connection.getChildren().remove(schema);
        assertFalse(ConnectionTreePane.loadCallbackAllowed(schema, root, 4, 4));
        connection.getChildren().add(schema);
        root.getChildren().remove(connection);
        assertFalse(ConnectionTreePane.loadCallbackAllowed(connection, root, 4, 4));
        assertFalse(ConnectionTreePane.loadCallbackAllowed(schema, root, 4, 4));
    }

    @Test void attachedFailureIsPublishedAndCollapseReexpandRetries() throws Exception {
        var store = new ConnectionStore(directory.resolve("connections.json"));
        store.saveAll(List.of(new ConnConfig("synthetic", "synthetic", DbType.POSTGRESQL,
                "example.invalid", 1, "synthetic", "", "", Map.of())));
        var probe = new DraftConnectionProbe(); // Rejects metadata without opening any connection.
        var actions = (ConnectionTreePane.Actions) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConnectionTreePane.Actions.class}, (p, m, a) -> {
                    throw new AssertionError("No object action expected");
                });
        try (var runner = new FxTaskRunner()) {
            var pane = FxUiTestSupport.call(() -> new ConnectionTreePane(store, probe.manager,
                    new ObjectTreeService(probe.manager), new SessionContext(), actions, runner));
            try {
                var item = FxUiTestSupport.call(() -> tree(pane).getRoot().getChildren().getFirst());
                for (int attempt = 1; attempt <= 2; attempt++) {
                    var published = new CountDownLatch(1);
                    FxUiTestSupport.call(() -> {
                        item.getChildren().addListener((ListChangeListener<TreeItem<ConnectionTreePane.NodeData>>) change -> {
                            if (item.getChildren().stream().anyMatch(child -> child.getValue().label.startsWith("加载失败：")))
                                published.countDown();
                        });
                        item.setExpanded(false);
                        item.setExpanded(true);
                        return null;
                    });
                    assertTrue(published.await(3, TimeUnit.SECONDS), "Attached node must publish the failure callback");
                    int expected = attempt;
                    FxUiTestSupport.call(() -> {
                        assertEquals(1, item.getChildren().size());
                        var status = item.getChildren().getFirst().getValue();
                        assertEquals(ConnectionTreePane.Kind.STATUS, status.kind());
                        assertTrue(status.label.contains("Synthetic metadata access rejected"));
                        assertFalse(ConnectionTreePane.hasContextActions(status));
                        assertEquals(expected, probe.metadata.get(), "Explicit re-expansion must retry once");
                        return null;
                    });
                }
                assertEquals(0, probe.network.get());
            } finally {
                FxUiTestSupport.call(() -> { pane.close(); return null; });
            }
        }
    }

    @SuppressWarnings("unchecked") private static TreeView<ConnectionTreePane.NodeData> tree(ConnectionTreePane pane) {
        return (TreeView<ConnectionTreePane.NodeData>) pane.getNode().lookup("#connection-tree");
    }
}
