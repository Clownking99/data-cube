package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTabCloseGuardsTest {

    @Test
    void blockingCleanupRunsOnceOnVirtualThreadAndCachesSuccess() throws Exception {
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
        assertEquals(CloseGuardOutcome.APPROVED, first.toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertSame(first, guard.requestClose());
        assertTrue(virtual.get());
        assertEquals(1, invocations.get());
    }

    @Test
    void blockingFailureClearsCacheSoSecondCallReallyRetries() {
        AtomicInteger invocations = new AtomicInteger();
        AsyncTabCloseGuard guard = AsyncTabCloseGuards.blocking(() -> {
            if (invocations.incrementAndGet() == 1) throw new IllegalStateException("retry");
        });

        var first = guard.requestClose();
        assertThrows(CompletionException.class, () -> first.toCompletableFuture().join());
        var retry = guard.requestClose();

        assertNotSame(first, retry);
        assertEquals(CloseGuardOutcome.APPROVED, retry.toCompletableFuture().join());
        assertEquals(2, invocations.get());
    }

    @Test
    void rejectedAttemptIsNotCachedButFatalPartialIs() {
        Queue<CompletableFuture<CloseGuardOutcome>> attempts = new ArrayDeque<>();
        attempts.add(CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED));
        attempts.add(CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL));
        AtomicInteger starts = new AtomicInteger();
        AsyncTabCloseGuard guard = AsyncTabCloseGuards.retryable(() -> {
            starts.incrementAndGet();
            return attempts.remove();
        });

        var rejected = guard.requestClose();
        assertEquals(CloseGuardOutcome.REJECTED, rejected.toCompletableFuture().join());
        var fatal = guard.requestClose();
        assertNotSame(rejected, fatal);
        assertEquals(CloseGuardOutcome.FAILED_PARTIAL, fatal.toCompletableFuture().join());
        assertSame(fatal, guard.requestClose());
        assertEquals(2, starts.get());
    }

    @Test
    void bestEffortPartialFailureBecomesFatalOutcome() {
        AsyncTabCloseGuard guard = AsyncTabCloseGuards.blocking(() ->
                BestEffortCloseSequence.run(
                        () -> { throw new IllegalStateException("partial"); },
                        () -> {}));

        assertEquals(CloseGuardOutcome.FAILED_PARTIAL,
                guard.requestClose().toCompletableFuture().join());
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
