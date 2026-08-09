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
}
