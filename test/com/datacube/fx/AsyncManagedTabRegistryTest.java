package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void registerRacingCloseAllIsEitherIncludedOrSafelyRejected() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
            CompletableFuture<CloseGuardOutcome> firstCleanup = new CompletableFuture<>();
            assertTrue(registry.register(new Object(), coordinator(() -> firstCleanup)));
            AtomicInteger secondGuardCalls = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean registered = new AtomicBoolean();
            AtomicReference<CompletionStage<TabCloseOutcome>> closeAll = new AtomicReference<>();

            Thread closer = Thread.startVirtualThread(() -> {
                await(start);
                closeAll.set(registry.closeAll());
            });
            Thread registrar = Thread.startVirtualThread(() -> {
                await(start);
                registered.set(registry.register(new Object(), coordinator(() -> {
                    secondGuardCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
                })));
            });

            start.countDown();
            closer.join(2_000);
            registrar.join(2_000);
            firstCleanup.complete(CloseGuardOutcome.APPROVED);

            assertEquals(TabCloseOutcome.COMPLETED, closeAll.get().toCompletableFuture().join());
            assertEquals(registered.get() ? 1 : 0, secondGuardCalls.get());
        }
    }

    @Test
    void timeoutLateApprovalUnregistersAutomaticallyAndNextCloseAllCompletes() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        Object tab = new Object();
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        AtomicInteger finalizers = new AtomicInteger();
        AsyncTabCloseCoordinator coordinator = coordinator(
                () -> cleanup,
                timeouts,
                () -> {
                    registry.unregister(tab);
                    finalizers.incrementAndGet();
                });
        assertTrue(registry.register(tab, coordinator));

        CompletionStage<TabCloseOutcome> firstCloseAll = registry.closeAll();
        timeouts.fireNext();
        assertEquals(TabCloseOutcome.TIMED_OUT_STILL_CLOSING,
                firstCloseAll.toCompletableFuture().join());

        cleanup.complete(CloseGuardOutcome.APPROVED);

        assertEquals(TabCloseOutcome.COMPLETED, registry.closeAll().toCompletableFuture().join());
        assertEquals(1, finalizers.get());
    }

    @Test
    void timeoutLateRejectionReopensExistingEntryForRealRetry() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        CompletableFuture<CloseGuardOutcome> firstCleanup = new CompletableFuture<>();
        CompletableFuture<CloseGuardOutcome> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<CloseGuardOutcome>> current = new AtomicReference<>(firstCleanup);
        ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        AtomicInteger guardCalls = new AtomicInteger();
        assertTrue(registry.register(new Object(), coordinator(() -> {
            guardCalls.incrementAndGet();
            return current.get();
        }, timeouts, () -> {})));

        CompletionStage<TabCloseOutcome> first = registry.closeAll();
        timeouts.fireNext();
        assertEquals(TabCloseOutcome.TIMED_OUT_STILL_CLOSING, first.toCompletableFuture().join());
        assertEquals(TabCloseOutcome.TIMED_OUT_STILL_CLOSING,
                registry.closeAll().toCompletableFuture().join());
        assertEquals(1, guardCalls.get());

        firstCleanup.complete(CloseGuardOutcome.REJECTED);
        current.set(secondCleanup);
        CompletionStage<TabCloseOutcome> retry = registry.closeAll();
        secondCleanup.complete(CloseGuardOutcome.APPROVED);

        assertEquals(TabCloseOutcome.COMPLETED, retry.toCompletableFuture().join());
        assertEquals(2, guardCalls.get());
    }

    @Test
    void registerReturnsFalseRatherThanThrowingWhileRegistryIsClosing() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        assertTrue(registry.register(new Object(), coordinator(() -> cleanup)));

        CompletionStage<TabCloseOutcome> closing = registry.closeAll();
        assertFalse(registry.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));
        cleanup.complete(CloseGuardOutcome.REJECTED);
        assertEquals(TabCloseOutcome.CANCELLED, closing.toCompletableFuture().join());

        assertTrue(registry.register(new Object(), immediateCoordinator(CloseGuardOutcome.APPROVED)));
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
                guard,
                Duration.ofSeconds(5),
                timeouts,
                Runnable::run,
                () -> {},
                () -> {},
                () -> {},
                finalizer,
                ignored -> {});
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
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

    private static final class ScheduledTimeout implements AsyncTabCloseCoordinator.TimeoutHandle {
        private final Runnable task;
        private boolean cancelled;

        private ScheduledTimeout(Runnable task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void fire() {
            if (!cancelled) task.run();
        }
    }
}
