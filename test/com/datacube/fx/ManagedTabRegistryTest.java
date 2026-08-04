package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ManagedTabRegistryTest {

    @Test
    void disposesOnceWhenTabCloseAndApplicationShutdownBothRun() throws Exception {
        ManagedTabRegistry<Object> registry = new ManagedTabRegistry<>();
        AtomicInteger disposals = new AtomicInteger();
        Runnable closeTab = registry.register(new Object(), disposals::incrementAndGet);
        CountDownLatch start = new CountDownLatch(1);

        Thread tabClose = Thread.startVirtualThread(() -> {
            await(start);
            closeTab.run();
        });
        Thread applicationClose = Thread.startVirtualThread(() -> {
            await(start);
            registry.disposeAll();
        });

        start.countDown();
        tabClose.join(2_000);
        applicationClose.join(2_000);

        assertFalse(tabClose.isAlive());
        assertFalse(applicationClose.isAlive());
        assertEquals(1, disposals.get());
    }

    @Test
    void shutdownDisposesEveryRemainingEntry() {
        ManagedTabRegistry<Object> registry = new ManagedTabRegistry<>();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        registry.register(new Object(), first::incrementAndGet);
        registry.register(new Object(), second::incrementAndGet);

        registry.disposeAll();
        registry.disposeAll();

        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void registerAfterShutdownDisposesImmediately() {
        ManagedTabRegistry<Object> registry = new ManagedTabRegistry<>();
        AtomicInteger disposals = new AtomicInteger();
        registry.disposeAll();

        Runnable closeTab = registry.register(new Object(), disposals::incrementAndGet);
        closeTab.run();

        assertEquals(1, disposals.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
