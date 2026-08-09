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
        assertEquals(0, harness.removals.get());
        cleanup.complete(true);
        assertEquals(0, harness.removals.get(), "FX work must only run through the FX dispatcher");
        assertFalse(first.toCompletableFuture().isDone());

        harness.runNextFxTask();
        assertTrue(first.toCompletableFuture().join());
        assertEquals(1, harness.removals.get());
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void staleCompletionAfterTimeoutCannotApproveRetryGeneration() {
        CompletableFuture<Boolean> firstCleanup = new CompletableFuture<>();
        CompletableFuture<Boolean> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<Boolean>> current = new AtomicReference<>(firstCleanup);
        Harness harness = new Harness(current::get);

        CompletionStage<Boolean> first = harness.coordinator.requestClose();
        harness.runNextTimeout();
        assertFalse(first.toCompletableFuture().join());

        current.set(secondCleanup);
        CompletionStage<Boolean> second = harness.coordinator.requestClose();
        firstCleanup.complete(true);
        assertEquals(0, harness.fxTasks.size());

        secondCleanup.complete(true);
        harness.runNextFxTask();
        assertTrue(second.toCompletableFuture().join());
        assertEquals(1, harness.finalizers.get());
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
    void exceptionalAndCancelledCleanupResetForRetry() {
        CompletableFuture<Boolean> exceptional = new CompletableFuture<>();
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        CompletableFuture<Boolean> acceptedCleanup = new CompletableFuture<>();
        Queue<CompletionStage<Boolean>> attempts = new ArrayDeque<>();
        attempts.add(exceptional);
        attempts.add(cancelled);
        attempts.add(acceptedCleanup);
        Harness harness = new Harness(attempts::remove);

        CompletionStage<Boolean> first = harness.coordinator.requestClose();
        exceptional.completeExceptionally(new IllegalArgumentException("async"));
        assertFalse(first.toCompletableFuture().join());

        CompletionStage<Boolean> second = harness.coordinator.requestClose();
        cancelled.cancel(false);
        assertFalse(second.toCompletableFuture().join());

        CompletionStage<Boolean> third = harness.coordinator.requestClose();
        acceptedCleanup.complete(true);
        harness.runNextFxTask();
        assertTrue(third.toCompletableFuture().join());
        assertTrue(harness.failures.stream().anyMatch(IllegalArgumentException.class::isInstance));
        assertTrue(harness.failures.stream().anyMatch(CancellationException.class::isInstance));
    }

    @Test
    void alreadyRemovedTabStillFinalizesExactlyOnceOnFxDispatcher() {
        CompletableFuture<Boolean> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);
        harness.present.set(false);

        CompletionStage<Boolean> close = harness.coordinator.requestClose();
        cleanup.complete(true);
        harness.runNextFxTask();
        harness.runAllTimeouts();

        assertTrue(close.toCompletableFuture().join());
        assertEquals(0, harness.removals.get());
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void uiFinalizerFailureIsReportedWithoutEscapingOrRepeating() {
        CompletableFuture<Boolean> cleanup = new CompletableFuture<>();
        IllegalStateException failure = new IllegalStateException("ui finalizer");
        Harness harness = new Harness(() -> cleanup, () -> { throw failure; });

        CompletionStage<Boolean> close = harness.coordinator.requestClose();
        cleanup.complete(true);
        harness.runNextFxTask();

        assertTrue(close.toCompletableFuture().join());
        assertEquals(List.of(failure), harness.failures);
        assertEquals(1, harness.finalizerInvocations.get());
        assertSame(close, harness.coordinator.requestClose());
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
                },
                fxTasks::add,
                () -> {},
                () -> {},
                failures::add);

        assertFalse(coordinator.requestClose().toCompletableFuture().join());
        current.set(secondCleanup);
        CompletionStage<Boolean> retry = coordinator.requestClose();
        firstCleanup.complete(true);
        secondCleanup.complete(true);
        fxTasks.remove().run();

        assertTrue(retry.toCompletableFuture().join());
        assertTrue(failures.stream().anyMatch(IllegalStateException.class::isInstance));
    }

    private static final class Harness {
        private final AtomicInteger guardCalls = new AtomicInteger();
        private final Queue<Runnable> timeoutTasks = new ArrayDeque<>();
        private final Queue<Runnable> fxTasks = new ArrayDeque<>();
        private final AtomicBoolean present = new AtomicBoolean(true);
        private final AtomicInteger removals = new AtomicInteger();
        private final AtomicInteger finalizers = new AtomicInteger();
        private final AtomicInteger finalizerInvocations = new AtomicInteger();
        private final List<Throwable> failures = new ArrayList<>();
        private final AsyncTabCloseCoordinator coordinator;

        private Harness(Supplier<CompletionStage<Boolean>> cleanup) {
            this(cleanup, finalizersPlaceholder());
        }

        private Harness(Supplier<CompletionStage<Boolean>> cleanup, Runnable uiFinalizer) {
            AtomicReference<Runnable> finalizer = new AtomicReference<>(uiFinalizer);
            AsyncTabCloseGuard guard = () -> {
                guardCalls.incrementAndGet();
                return cleanup.get();
            };
            coordinator = new AsyncTabCloseCoordinator(
                    guard,
                    Duration.ofSeconds(5),
                    (delay, task) -> timeoutTasks.add(task),
                    fxTasks::add,
                    () -> {
                        if (present.compareAndSet(true, false)) removals.incrementAndGet();
                    },
                    () -> {
                        finalizerInvocations.incrementAndGet();
                        Runnable action = finalizer.get();
                        if (action == NO_OP_FINALIZER) finalizers.incrementAndGet();
                        else action.run();
                    },
                    failures::add);
        }

        private void runNextTimeout() {
            timeoutTasks.remove().run();
        }

        private void runAllTimeouts() {
            while (!timeoutTasks.isEmpty()) timeoutTasks.remove().run();
        }

        private void runNextFxTask() {
            fxTasks.remove().run();
        }

        private static Runnable finalizersPlaceholder() {
            return NO_OP_FINALIZER;
        }
    }

    private static final Runnable NO_OP_FINALIZER = () -> {};
}
