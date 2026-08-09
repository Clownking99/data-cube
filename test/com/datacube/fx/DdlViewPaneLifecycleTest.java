package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlViewPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(DdlViewPane.class));
        assertNotNull(DdlViewPane.class.getConstructor(
                String.class, Callable.class, FxTaskRunner.class));
    }
}
