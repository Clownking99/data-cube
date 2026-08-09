package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTabCloseCoordinatorTest {

    @Test
    void duplicateRequestsMergeAndApprovalFinalizesOnFxAfterCleanup() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);

        CompletionStage<TabCloseOutcome> first = harness.coordinator.requestClose();
        assertSame(first, harness.coordinator.requestClose());
        harness.drainFx();
        assertTrue(harness.disabled);

        cleanup.complete(CloseGuardOutcome.APPROVED);
        assertEquals(1, harness.timeouts.cancelledCount());
        assertFalse(first.toCompletableFuture().isDone());
        harness.drainFx();

        assertEquals(TabCloseOutcome.COMPLETED, first.toCompletableFuture().join());
        assertFalse(harness.present);
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void timeoutLateApprovalAutomaticallyRemovesAndFinalizesDisabledTab() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);

        CompletionStage<TabCloseOutcome> close = harness.coordinator.requestClose();
        harness.drainFx();
        harness.timeouts.fireNext();

        assertEquals(TabCloseOutcome.TIMED_OUT_STILL_CLOSING, close.toCompletableFuture().join());
        assertTrue(harness.disabled);
        assertTrue(harness.present);
        cleanup.complete(CloseGuardOutcome.APPROVED);
        harness.drainFx();

        assertFalse(harness.present);
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void timeoutLateRejectionReenablesAndAllowsANewGeneration() {
        CompletableFuture<CloseGuardOutcome> firstCleanup = new CompletableFuture<>();
        CompletableFuture<CloseGuardOutcome> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<CloseGuardOutcome>> current = new AtomicReference<>(firstCleanup);
        Harness harness = new Harness(current::get);

        CompletionStage<TabCloseOutcome> first = harness.coordinator.requestClose();
        harness.drainFx();
        harness.timeouts.fireNext();
        assertEquals(TabCloseOutcome.TIMED_OUT_STILL_CLOSING, first.toCompletableFuture().join());
        assertSame(first, harness.coordinator.requestClose());

        firstCleanup.complete(CloseGuardOutcome.REJECTED);
        harness.drainFx();
        assertFalse(harness.disabled);

        current.set(secondCleanup);
        CompletionStage<TabCloseOutcome> retry = harness.coordinator.requestClose();
        assertNotSame(first, retry);
        assertEquals(2, harness.guardCalls.get());
        secondCleanup.complete(CloseGuardOutcome.APPROVED);
        harness.drainFx();
        assertEquals(TabCloseOutcome.COMPLETED, retry.toCompletableFuture().join());
    }

    @Test
    void lateFatalPartialStaysDisabledAndIsVisibleToLaterCallers() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);

        CompletionStage<TabCloseOutcome> timedOut = harness.coordinator.requestClose();
        harness.drainFx();
        harness.timeouts.fireNext();
        cleanup.complete(CloseGuardOutcome.FAILED_PARTIAL);

        assertEquals(TabCloseOutcome.TIMED_OUT_STILL_CLOSING, timedOut.toCompletableFuture().join());
        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                harness.coordinator.requestClose().toCompletableFuture().join());
        assertTrue(harness.disabled);
    }

    @Test
    void rejectedExceptionalNullStageAndNullOutcomeAreRetryable() {
        Queue<Supplier<CompletionStage<CloseGuardOutcome>>> attempts = new ArrayDeque<>();
        attempts.add(() -> CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED));
        attempts.add(() -> { throw new IllegalStateException("sync"); });
        attempts.add(() -> null);
        attempts.add(() -> CompletableFuture.completedFuture(null));
        attempts.add(() -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED));
        Harness harness = new Harness(() -> attempts.remove().get());

        for (int i = 0; i < 4; i++) {
            CompletionStage<TabCloseOutcome> rejected = harness.coordinator.requestClose();
            harness.drainFx();
            assertEquals(TabCloseOutcome.CANCELLED, rejected.toCompletableFuture().join());
            assertFalse(harness.disabled);
        }
        CompletionStage<TabCloseOutcome> accepted = harness.coordinator.requestClose();
        harness.drainFx();

        assertEquals(TabCloseOutcome.COMPLETED, accepted.toCompletableFuture().join());
        assertEquals(5, harness.guardCalls.get());
    }

    @Test
    void rootFxDispatcherRejectionAfterCleanupCannotCompleteSuccessfully() {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                Duration.ofSeconds(5),
                new ManualTimeoutScheduler(),
                action -> {
                    if (dispatches.incrementAndGet() == 1) action.run();
                    else throw new IllegalStateException("dispatcher rejected");
                },
                () -> {},
                () -> {},
                () -> {},
                finalizers::incrementAndGet,
                failures::add);

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                coordinator.requestClose().toCompletableFuture().join());
        assertEquals(0, finalizers.get());
        assertTrue(failures.stream().anyMatch(f -> "dispatcher rejected".equals(f.getMessage())));
    }

    @Test
    void invokedFinalizerFailureIsReportedButStillCountsAsCompleted() {
        IllegalStateException failure = new IllegalStateException("ui finalizer");
        Harness harness = new Harness(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                () -> { throw failure; });

        CompletionStage<TabCloseOutcome> close = harness.coordinator.requestClose();
        harness.drainFx();

        assertEquals(TabCloseOutcome.COMPLETED, close.toCompletableFuture().join());
        assertEquals(1, harness.finalizerInvocations.get());
        assertEquals(List.of(failure), harness.failures);
    }

    private static final class Harness {
        private final AtomicInteger guardCalls = new AtomicInteger();
        private final ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        private final Queue<Runnable> fxTasks = new ArrayDeque<>();
        private boolean present = true;
        private boolean disabled;
        private final AtomicInteger finalizers = new AtomicInteger();
        private final AtomicInteger finalizerInvocations = new AtomicInteger();
        private final List<Throwable> failures = new ArrayList<>();
        private final AsyncTabCloseCoordinator coordinator;

        private Harness(Supplier<CompletionStage<CloseGuardOutcome>> cleanup) {
            this(cleanup, () -> {});
        }

        private Harness(Supplier<CompletionStage<CloseGuardOutcome>> cleanup, Runnable uiFinalizer) {
            AsyncTabCloseGuard guard = () -> {
                guardCalls.incrementAndGet();
                return cleanup.get();
            };
            coordinator = new AsyncTabCloseCoordinator(
                    guard,
                    Duration.ofSeconds(5),
                    timeouts,
                    fxTasks::add,
                    () -> disabled = true,
                    () -> disabled = false,
                    () -> present = false,
                    () -> {
                        finalizerInvocations.incrementAndGet();
                        if (uiFinalizer == null) finalizers.incrementAndGet();
                        else {
                            finalizers.incrementAndGet();
                            uiFinalizer.run();
                        }
                    },
                    failures::add);
        }

        private void drainFx() {
            while (!fxTasks.isEmpty()) fxTasks.remove().run();
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
            if (!cancelled) task.run();
        }
    }
}
