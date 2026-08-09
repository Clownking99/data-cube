package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncManagedTabRegistryTest {

    @Test
    void closeAllAggregatesWorstExplicitOutcomeAndReopensOnlyWhenRetryable() {
        AsyncManagedTabRegistry<Object> cancelled = new AsyncManagedTabRegistry<>();
        assertTrue(cancelled.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));
        assertTrue(cancelled.register(new Object(), immediateCoordinator(CloseGuardOutcome.REJECTED)));
        assertEquals(TabCloseOutcome.CANCELLED, cancelled.closeAll().toCompletableFuture().join());
        assertTrue(cancelled.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));

        AsyncManagedTabRegistry<Object> fatal = new AsyncManagedTabRegistry<>();
        assertTrue(fatal.register(new Object(), immediateCoordinator(CloseGuardOutcome.FAILED_PARTIAL)));
        assertEquals(TabCloseOutcome.FAILED_PARTIAL, fatal.closeAll().toCompletableFuture().join());
        assertFalse(fatal.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));
    }

    @Test
    void closeAllWaitsForExistingReservationWithoutBlockingCaller() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        Object tab = new Object();
        AsyncManagedTabRegistry<Object>.Reservation reservation = registry.reserve();
        assertTrue(reservation.acquired());

        CompletionStage<TabCloseOutcome> closing = registry.closeAll();

        assertFalse(closing.toCompletableFuture().isDone());
        assertFalse(registry.reserve().acquired());
        assertTrue(reservation.register(tab, immediateCoordinator(CloseGuardOutcome.APPROVED)));
        assertFalse(closing.toCompletableFuture().isDone());
        reservation.close();
        assertEquals(TabCloseOutcome.COMPLETED, closing.toCompletableFuture().join());
    }

    @Test
    void abandonedReservationReleasesCloseAllAndCannotLeakOrRegisterTwice() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        AsyncManagedTabRegistry<Object>.Reservation reservation = registry.reserve();
        CompletionStage<TabCloseOutcome> closing = registry.closeAll();

        reservation.close();
        reservation.close();

        assertEquals(TabCloseOutcome.COMPLETED, closing.toCompletableFuture().join());
        assertFalse(reservation.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));
    }

    @Test
    void reserveRacingCloseAllIsEitherIncludedOrRejectedBeforeConstruction() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger guardCalls = new AtomicInteger();
            AtomicReference<AsyncManagedTabRegistry<Object>.Reservation> reservation =
                    new AtomicReference<>();
            AtomicReference<CompletionStage<TabCloseOutcome>> closing = new AtomicReference<>();

            Thread opener = Thread.startVirtualThread(() -> {
                await(start);
                var acquired = registry.reserve();
                reservation.set(acquired);
                try (acquired) {
                    if (acquired.acquired()) {
                        acquired.register(new Object(), coordinator(() -> {
                            guardCalls.incrementAndGet();
                            return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
                        }));
                    }
                }
            });
            Thread closer = Thread.startVirtualThread(() -> {
                await(start);
                closing.set(registry.closeAll());
            });

            start.countDown();
            opener.join(2_000);
            closer.join(2_000);

            assertEquals(TabCloseOutcome.COMPLETED, closing.get().toCompletableFuture().join());
            assertEquals(reservation.get().acquired() ? 1 : 0, guardCalls.get());
        }
    }

    @Test
    void timeoutKeepsCloseAllSealedUntilUniqueLateSettlement() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        assertTrue(registry.register(new Object(), coordinator(() -> cleanup, timeouts, () -> {})));

        CompletionStage<TabCloseOutcome> closing = registry.closeAll();
        timeouts.fireNext();

        assertFalse(closing.toCompletableFuture().isDone());
        assertFalse(registry.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));
        assertSame(closing, registry.closeAll());

        cleanup.complete(CloseGuardOutcome.APPROVED);
        assertEquals(TabCloseOutcome.COMPLETED, closing.toCompletableFuture().join());
    }

    @Test
    void timeoutLateRejectionReopensExistingEntryForRealRetry() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        CompletableFuture<CloseGuardOutcome> firstCleanup = new CompletableFuture<>();
        AtomicInteger guardCalls = new AtomicInteger();
        assertTrue(registry.register(new Object(), coordinator(() -> {
            guardCalls.incrementAndGet();
            return firstCleanup;
        })));

        CompletionStage<TabCloseOutcome> first = registry.closeAll();
        firstCleanup.complete(CloseGuardOutcome.REJECTED);

        assertEquals(TabCloseOutcome.CANCELLED, first.toCompletableFuture().join());
        assertTrue(registry.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));
        assertEquals(1, guardCalls.get());
    }

    @Test
    void timeoutLateFatalPropagatesToShutdownAndNeverStartsTeardown() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        assertTrue(registry.register(new Object(), coordinator(() -> cleanup, timeouts, () -> {})));
        AtomicInteger teardowns = new AtomicInteger();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                registry::closeAll, Runnable::run, teardowns::incrementAndGet, ignored -> {});

        CompletionStage<ShutdownOutcome> result = shutdown.shutdown();
        timeouts.fireNext();
        assertFalse(result.toCompletableFuture().isDone());
        cleanup.complete(CloseGuardOutcome.FAILED_PARTIAL);

        assertEquals(ShutdownOutcome.FAILED_PARTIAL, result.toCompletableFuture().join());
        assertEquals(0, teardowns.get());
    }

    @Test
    void removalMutationThenListenerThrowRetainsFatalTombstoneForCloseAll() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        Object tab = new Object();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                Duration.ofSeconds(5), new ManualTimeoutScheduler(), Runnable::run,
                () -> {}, () -> {},
                () -> { throw new IllegalStateException("listener after physical mutation"); },
                () -> registry.unregister(tab),
                () -> {}, ignored -> {});
        assertTrue(registry.register(tab, coordinator));

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                registry.requestClose(tab).toCompletableFuture().join());

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                registry.closeAll().toCompletableFuture().join());
    }

    private static AsyncTabCloseCoordinator immediateCoordinator(CloseGuardOutcome outcome) {
        return coordinator(() -> CompletableFuture.completedFuture(outcome));
    }

    private static AsyncTabCloseCoordinator coordinator(AsyncTabCloseGuard guard) {
        return coordinator(guard, new ManualTimeoutScheduler(), () -> {});
    }

    private static AsyncTabCloseCoordinator coordinator(
            AsyncTabCloseGuard guard,
            ManualTimeoutScheduler timeouts,
            Runnable finalizer) {
        return new AsyncTabCloseCoordinator(
                guard, Duration.ofSeconds(5), timeouts, Runnable::run,
                () -> {}, () -> {}, () -> {}, finalizer, ignored -> {});
    }

    private static final class ManualTimeoutScheduler
            implements AsyncTabCloseCoordinator.TimeoutScheduler {
        private final List<ScheduledTimeout> scheduled = new ArrayList<>();

        @Override
        public AsyncTabCloseCoordinator.TimeoutHandle schedule(Duration delay, Runnable task) {
            ScheduledTimeout timeout = new ScheduledTimeout(task);
            scheduled.add(timeout);
            return timeout;
        }

        void fireNext() {
            scheduled.stream().filter(timeout -> !timeout.cancelled).findFirst().orElseThrow().fire();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class ScheduledTimeout implements AsyncTabCloseCoordinator.TimeoutHandle {
        private final Runnable task;
        private boolean cancelled;

        private ScheduledTimeout(Runnable task) { this.task = task; }
        @Override public void cancel() { cancelled = true; }
        private void fire() { if (!cancelled) task.run(); }
    }
}
