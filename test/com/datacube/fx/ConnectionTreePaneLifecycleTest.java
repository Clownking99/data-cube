package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import org.junit.jupiter.api.Test;

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
}
