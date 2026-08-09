package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTabCloseGuardsTest {

    @Test
    void blockingCleanupRunsOnceOnVirtualThreadWithoutBlockingCaller() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        AtomicInteger invocations = new AtomicInteger();
        AsyncTabCloseGuard guard = AsyncTabCloseGuards.blocking(() -> {
            invocations.incrementAndGet();
            virtual.set(Thread.currentThread().isVirtual());
            started.countDown();
            await(release);
        });

        var first = guard.requestClose();
        var duplicate = guard.requestClose();

        assertSame(first, duplicate);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertFalse(first.toCompletableFuture().isDone());
        release.countDown();
        assertTrue(first.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertTrue(virtual.get());
        assertTrue(invocations.get() == 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
