package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationPaneLifecycleTest {

    @Test
    void requiresSharedTaskRunner() throws Exception {
        assertNotNull(MigrationPane.class.getConstructor(FxTaskRunner.class));
    }

    @Test
    void shutdownClosesInjectedTaskScope() {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxTaskScope scope = runner.scope();
            MainController controller = new MainController(scope);

            controller.shutdown();

            assertTrue(scope.isClosed());
        }
    }
}
