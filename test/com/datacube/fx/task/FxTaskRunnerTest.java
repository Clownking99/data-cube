package com.datacube.fx.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxTaskRunnerTest {

    @Test
    void runsEachTaskOnNamedVirtualThreadAndRejectsAfterClose() throws Exception {
        FxTaskRunner runner = new FxTaskRunner();
        AtomicBoolean virtual = new AtomicBoolean();
        AtomicReference<String> name = new AtomicReference<>();

        Future<?> future = runner.submit(() -> {
            virtual.set(Thread.currentThread().isVirtual());
            name.set(Thread.currentThread().getName());
        });
        future.get();
        runner.close();

        assertTrue(virtual.get());
        assertTrue(name.get().startsWith("DataCube-io-"));
        assertThrows(RejectedExecutionException.class, () -> runner.submit(() -> { }));
    }
}
