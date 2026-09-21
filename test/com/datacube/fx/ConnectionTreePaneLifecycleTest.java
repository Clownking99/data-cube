package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import org.junit.jupiter.api.Test;

import javafx.scene.control.TreeItem;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTreePaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(ConnectionTreePane.class));
        assertNotNull(ConnectionTreePane.class.getConstructor(
                ConnectionStore.class, ConnectionManager.class, ObjectTreeService.class,
                SessionContext.class, ConnectionTreePane.Actions.class, FxTaskRunner.class));
    }

    @Test
    void transientRowsNeverMasqueradeAsActionableConnectionRows() {
        ConnectionTreePane.NodeData parent = new ConnectionTreePane.NodeData(
                ConnectionTreePane.Kind.CONNECTION, "saved", null, "connection-id", null, null);

        ConnectionTreePane.NodeData loading = ConnectionTreePane.statusData(parent, "加载中...");

        org.junit.jupiter.api.Assertions.assertEquals(ConnectionTreePane.Kind.STATUS, loading.kind());
        org.junit.jupiter.api.Assertions.assertEquals("connection-id", loading.connId());
        org.junit.jupiter.api.Assertions.assertFalse(ConnectionTreePane.hasContextActions(loading));
    }

    @Test
    void failedLazyLoadCanRetryOnlyAfterCollapseAndReexpand() {
        ConnectionTreePane.LazyLoadState state = new ConnectionTreePane.LazyLoadState();

        assertTrue(state.onExpanded(true));
        assertFalse(state.onExpanded(true), "one expansion must start only one load");
        state.failed();
        assertFalse(state.onExpanded(true), "a failed expanded node must not duplicate work");
        assertFalse(state.onExpanded(false));
        assertTrue(state.onExpanded(true), "collapse then re-expand is an explicit retry");
        state.completed();
        assertFalse(state.onExpanded(false));
        assertFalse(state.onExpanded(true), "a successful node remains loaded");
    }

    @Test
    void emptyLazyLoadShowsStableStatusInsteadOfBlankChildren() {
        ConnectionTreePane.NodeData parent = new ConnectionTreePane.NodeData(
                ConnectionTreePane.Kind.TABLES, "表", null, "connection-id", "PUBLIC", null);

        List<TreeItem<ConnectionTreePane.NodeData>> displayed =
                ConnectionTreePane.displayChildren(parent, List.of());

        assertEquals(1, displayed.size());
        assertEquals(ConnectionTreePane.Kind.STATUS, displayed.getFirst().getValue().kind());
        assertEquals("没有可用对象", displayed.getFirst().getValue().label);
        assertFalse(ConnectionTreePane.hasContextActions(displayed.getFirst().getValue()));
    }

    @Test
    void staleLazyCallbackIsRejectedAfterTreeReplacementOrDetach() {
        assertTrue(ConnectionTreePane.loadCallbackAllowed(4, 4, true));
        assertFalse(ConnectionTreePane.loadCallbackAllowed(3, 4, true));
        assertFalse(ConnectionTreePane.loadCallbackAllowed(4, 4, false));
    }
}
