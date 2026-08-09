package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTabCloseCoordinatorTest {

    @Test
    void mergesDuplicateRequestsAndRunsUiFinalizerOnlyAfterCleanupApproval() {
        CompletableFuture<Boolean> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);

        CompletionStage<Boolean> first = harness.coordinator.requestClose();
        CompletionStage<Boolean> duplicate = harness.coordinator.requestClose();

        assertSame(first, duplicate);
        assertEquals(1, harness.guardCalls.get());
        cleanup.complete(true);
        assertEquals(1, harness.timeouts.cancelledCount());
        assertFalse(first.toCompletableFuture().isDone());

        harness.runNextFxTask();
        assertTrue(first.toCompletableFuture().join());
        assertEquals(1, harness.removals.get());
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void timeoutKeepsUnderlyingCleanupSingleFlightUntilItsTerminalState() {
        CompletableFuture<Boolean> firstCleanup = new CompletableFuture<>();
        CompletableFuture<Boolean> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<Boolean>> current = new AtomicReference<>(firstCleanup);
        Harness harness = new Harness(current::get);

        CompletionStage<Boolean> first = harness.coordinator.requestClose();
        harness.timeouts.fireNext();
        assertFalse(first.toCompletableFuture().join());

        current.set(secondCleanup);
        CompletionStage<Boolean> whileCleanupStillRunning = harness.coordinator.requestClose();
        assertSame(first, whileCleanupStillRunning);
        assertEquals(1, harness.guardCalls.get());

        firstCleanup.complete(true);
        CompletionStage<Boolean> retry = harness.coordinator.requestClose();
        assertNotSame(first, retry);
        assertEquals(2, harness.guardCalls.get());
        secondCleanup.complete(true);
        harness.runNextFxTask();

        assertTrue(retry.toCompletableFuture().join());
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void normalFalseExceptionalAndCancelledTerminalsCancelTheirTimers() {
        CompletableFuture<Boolean> rejected = new CompletableFuture<>();
        CompletableFuture<Boolean> exceptional = new CompletableFuture<>();
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        Queue<CompletionStage<Boolean>> attempts = new ArrayDeque<>();
        attempts.add(rejected);
        attempts.add(exceptional);
        attempts.add(cancelled);
        Harness harness = new Harness(attempts::remove);

        CompletionStage<Boolean> first = harness.coordinator.requestClose();
        rejected.complete(false);
        assertFalse(first.toCompletableFuture().join());

        CompletionStage<Boolean> second = harness.coordinator.requestClose();
        exceptional.completeExceptionally(new IllegalArgumentException("async"));
        assertFalse(second.toCompletableFuture().join());

        CompletionStage<Boolean> third = harness.coordinator.requestClose();
        cancelled.cancel(false);
        assertFalse(third.toCompletableFuture().join());

        assertEquals(3, harness.timeouts.cancelledCount());
        assertTrue(harness.failures.stream().anyMatch(IllegalArgumentException.class::isInstance));
        assertTrue(harness.failures.stream().anyMatch(CancellationException.class::isInstance));
    }

    @Test
    void synchronousFailureNullStageAndNullResultResetForRetry() {
        Queue<Supplier<CompletionStage<Boolean>>> attempts = new ArrayDeque<>();
        attempts.add(() -> { throw new IllegalStateException("sync"); });
        attempts.add(() -> null);
        attempts.add(() -> CompletableFuture.completedFuture(null));
        attempts.add(() -> CompletableFuture.completedFuture(true));
        Harness harness = new Harness(() -> attempts.remove().get());

        assertFalse(harness.coordinator.requestClose().toCompletableFuture().join());
        assertFalse(harness.coordinator.requestClose().toCompletableFuture().join());
        assertFalse(harness.coordinator.requestClose().toCompletableFuture().join());
        CompletionStage<Boolean> accepted = harness.coordinator.requestClose();
        harness.runNextFxTask();

        assertTrue(accepted.toCompletableFuture().join());
        assertEquals(4, harness.guardCalls.get());
        assertEquals(3, harness.failures.size());
    }

    @Test
    void timeoutSchedulerFailureRejectsAttemptAndAllowsRetry() {
        CompletableFuture<Boolean> firstCleanup = new CompletableFuture<>();
        CompletableFuture<Boolean> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<Boolean>> current = new AtomicReference<>(firstCleanup);
        AtomicBoolean failScheduling = new AtomicBoolean(true);
        Queue<Runnable> fxTasks = new ArrayDeque<>();
        List<Throwable> failures = new ArrayList<>();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                current::get,
                Duration.ofSeconds(5),
                (delay, task) -> {
                    if (failScheduling.getAndSet(false)) throw new IllegalStateException("scheduler");
                    return () -> {};
                },
                fxTasks::add,
                () -> {},
                () -> {},
                failures::add);

        CompletionStage<Boolean> first = coordinator.requestClose();
        assertFalse(first.toCompletableFuture().join());
        current.set(secondCleanup);
        assertSame(first, coordinator.requestClose());
        firstCleanup.complete(true);
        CompletionStage<Boolean> retry = coordinator.requestClose();
        secondCleanup.complete(true);
        fxTasks.remove().run();

        assertTrue(retry.toCompletableFuture().join());
        assertTrue(failures.stream().anyMatch(IllegalStateException.class::isInstance));
    }

    @Test
    void alreadyRemovedTabStillFinalizesExactlyOnceAndReportsFinalizerFailure() {
        CompletableFuture<Boolean> cleanup = new CompletableFuture<>();
        IllegalStateException failure = new IllegalStateException("ui finalizer");
        Harness harness = new Harness(() -> cleanup, () -> { throw failure; });
        harness.present.set(false);

        CompletionStage<Boolean> close = harness.coordinator.requestClose();
        cleanup.complete(true);
        harness.runNextFxTask();

        assertTrue(close.toCompletableFuture().join());
        assertEquals(0, harness.removals.get());
        assertEquals(1, harness.finalizerInvocations.get());
        assertEquals(List.of(failure), harness.failures);
        assertSame(close, harness.coordinator.requestClose());
    }

    private static final class Harness {
        private final AtomicInteger guardCalls = new AtomicInteger();
        private final ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        private final Queue<Runnable> fxTasks = new ArrayDeque<>();
        private final AtomicBoolean present = new AtomicBoolean(true);
        private final AtomicInteger removals = new AtomicInteger();
        private final AtomicInteger finalizers = new AtomicInteger();
        private final AtomicInteger finalizerInvocations = new AtomicInteger();
        private final List<Throwable> failures = new ArrayList<>();
        private final AsyncTabCloseCoordinator coordinator;

        private Harness(Supplier<CompletionStage<Boolean>> cleanup) {
            this(cleanup, NO_OP_FINALIZER);
        }

        private Harness(Supplier<CompletionStage<Boolean>> cleanup, Runnable uiFinalizer) {
            AsyncTabCloseGuard guard = () -> {
                guardCalls.incrementAndGet();
                return cleanup.get();
            };
            coordinator = new AsyncTabCloseCoordinator(
                    guard,
                    Duration.ofSeconds(5),
                    timeouts,
                    fxTasks::add,
                    () -> {
                        if (present.compareAndSet(true, false)) removals.incrementAndGet();
                    },
                    () -> {
                        finalizerInvocations.incrementAndGet();
                        if (uiFinalizer == NO_OP_FINALIZER) finalizers.incrementAndGet();
                        else uiFinalizer.run();
                    },
                    failures::add);
        }

        private void runNextFxTask() {
            fxTasks.remove().run();
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

        private void fireNext() {
            scheduled.stream().filter(timeout -> !timeout.cancelled).findFirst().orElseThrow().fire();
        }

        private int cancelledCount() {
            return (int) scheduled.stream().filter(timeout -> timeout.cancelled).count();
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
            task.run();
        }
    }

    private static final Runnable NO_OP_FINALIZER = () -> {};
}
