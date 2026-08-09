package com.datacube.update;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresInjectedExecutors() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(UpdateService.class));
        assertNotNull(UpdateService.class.getConstructor(Executor.class, Consumer.class));
    }
}
