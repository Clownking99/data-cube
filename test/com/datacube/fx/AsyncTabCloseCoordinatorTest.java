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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTabCloseCoordinatorTest {

    @Test
    void duplicateRequestsShareOneAttemptAndOneEventualSettlement() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);

        CloseAttempt first = harness.coordinator.requestClose();
        assertSame(first, harness.coordinator.requestClose());
        harness.drainFx();
        assertTrue(harness.disabled);

        cleanup.complete(CloseGuardOutcome.APPROVED);
        assertEquals(1, harness.timeouts.cancelledCount());
        assertFalse(first.settlement().toCompletableFuture().isDone());
        harness.drainFx();

        assertEquals(TabCloseOutcome.COMPLETED, first.settlement().toCompletableFuture().join());
        assertEquals(CloseAttemptStatus.SETTLED, first.status());
        assertFalse(harness.present);
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void timeoutIsProgressOnlyAndLateApprovalIsTheUniqueSettlement() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);

        CloseAttempt attempt = harness.coordinator.requestClose();
        harness.drainFx();
        harness.timeouts.fireNext();

        assertEquals(CloseAttemptStatus.STILL_CLOSING, attempt.status());
        assertFalse(attempt.settlement().toCompletableFuture().isDone());
        assertTrue(harness.disabled);
        cleanup.complete(CloseGuardOutcome.APPROVED);
        harness.drainFx();

        assertEquals(TabCloseOutcome.COMPLETED, attempt.settlement().toCompletableFuture().join());
        assertFalse(harness.present);
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void timeoutLateRejectionSettlesCancelledThenReenablesForNewGeneration() {
        CompletableFuture<CloseGuardOutcome> firstCleanup = new CompletableFuture<>();
        CompletableFuture<CloseGuardOutcome> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<CloseGuardOutcome>> current = new AtomicReference<>(firstCleanup);
        Harness harness = new Harness(current::get);

        CloseAttempt first = harness.coordinator.requestClose();
        harness.drainFx();
        harness.timeouts.fireNext();
        assertSame(first, harness.coordinator.requestClose());

        firstCleanup.complete(CloseGuardOutcome.REJECTED);
        harness.drainFx();
        assertEquals(TabCloseOutcome.CANCELLED, first.settlement().toCompletableFuture().join());
        assertFalse(harness.disabled);

        current.set(secondCleanup);
        CloseAttempt retry = harness.coordinator.requestClose();
        assertNotSame(first, retry);
        assertEquals(2, harness.guardCalls.get());
        secondCleanup.complete(CloseGuardOutcome.APPROVED);
        harness.drainFx();
        assertEquals(TabCloseOutcome.COMPLETED, retry.settlement().toCompletableFuture().join());
    }

    @Test
    void timeoutLateFatalSettlesFatalAndStaysDisabled() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);

        CloseAttempt attempt = harness.coordinator.requestClose();
        harness.drainFx();
        harness.timeouts.fireNext();
        cleanup.complete(CloseGuardOutcome.FAILED_PARTIAL);
        harness.drainFx();

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                attempt.settlement().toCompletableFuture().join());
        assertSame(attempt, harness.coordinator.requestClose());
        assertTrue(harness.disabled);
    }

    @Test
    void staleTimeoutAfterLateTerminalWasQueuedCannotReverseSettlementAction() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);
        CloseAttempt attempt = harness.coordinator.requestClose();
        harness.drainFx();

        cleanup.complete(CloseGuardOutcome.REJECTED);
        assertEquals(1, harness.fxTasks.size());
        harness.timeouts.fireEvenIfCancelled();
        assertEquals(1, harness.fxTasks.size());
        harness.drainFx();

        assertEquals(TabCloseOutcome.CANCELLED, attempt.settlement().toCompletableFuture().join());
        assertFalse(harness.disabled);
    }

    @Test
    void externalRemovalRestoreRunsInsideCurrentGenerationBeforeTerminalRemoval() {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        Harness harness = new Harness(() -> cleanup);
        AtomicInteger restores = new AtomicInteger();

        CloseAttempt attempt = harness.coordinator.requestClose(restores::incrementAndGet);
        cleanup.complete(CloseGuardOutcome.APPROVED);
        harness.timeouts.fireEvenIfCancelled();

        assertEquals(2, harness.fxTasks.size());
        harness.drainFx();
        assertEquals(1, restores.get());
        assertEquals(TabCloseOutcome.COMPLETED, attempt.settlement().toCompletableFuture().join());
        assertFalse(harness.present);
    }

    @Test
    void externallyRemovedFatalTabIsRestoredDisabledWithinItsTerminalGeneration() {
        Harness harness = new Harness(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL));
        CloseAttempt fatal = harness.coordinator.requestClose();
        harness.drainFx();
        assertEquals(TabCloseOutcome.FAILED_PARTIAL, fatal.settlement().toCompletableFuture().join());
        AtomicInteger restores = new AtomicInteger();

        assertSame(fatal, harness.coordinator.requestClose(restores::incrementAndGet));
        harness.drainFx();

        assertEquals(1, restores.get());
        assertTrue(harness.disabled);
    }

    @Test
    void rejectedExceptionalNullStageAndNullOutcomeAreRetryableSettlements() {
        Queue<Supplier<CompletionStage<CloseGuardOutcome>>> attempts = new ArrayDeque<>();
        attempts.add(() -> CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED));
        attempts.add(() -> { throw new IllegalStateException("sync"); });
        attempts.add(() -> null);
        attempts.add(() -> CompletableFuture.completedFuture(null));
        attempts.add(() -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED));
        Harness harness = new Harness(() -> attempts.remove().get());

        for (int i = 0; i < 4; i++) {
            CloseAttempt rejected = harness.coordinator.requestClose();
            harness.drainFx();
            assertEquals(TabCloseOutcome.CANCELLED,
                    rejected.settlement().toCompletableFuture().join());
            assertFalse(harness.disabled);
        }
        CloseAttempt accepted = harness.coordinator.requestClose();
        harness.drainFx();

        assertEquals(TabCloseOutcome.COMPLETED, accepted.settlement().toCompletableFuture().join());
        assertEquals(5, harness.guardCalls.get());
    }

    @Test
    void rootFxDispatcherRejectionAfterCleanupSettlesFatalWithoutFinalizerInvocation() {
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

        CloseAttempt attempt = coordinator.requestClose();

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                attempt.settlement().toCompletableFuture().join());
        assertEquals(0, finalizers.get());
        assertTrue(failures.stream().anyMatch(f -> "dispatcher rejected".equals(f.getMessage())));
    }

    @Test
    void transientRemovalDispatchFailureBestEffortDeliversFinalizerOnFx() {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                Duration.ofSeconds(5), new ManualTimeoutScheduler(),
                action -> {
                    int call = dispatches.incrementAndGet();
                    if (call == 2) throw new IllegalStateException("remove dispatch");
                    action.run();
                },
                () -> {}, () -> {}, () -> {}, finalizers::incrementAndGet, ignored -> {});

        CloseAttempt attempt = coordinator.requestClose();

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                attempt.settlement().toCompletableFuture().join());
        assertEquals(1, finalizers.get());
        assertEquals(3, dispatches.get());
    }

    @Test
    void removeExecutionFailureStillInvokesFinalizerButSettlesFatal() {
        AtomicInteger finalizers = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                Duration.ofSeconds(5),
                new ManualTimeoutScheduler(),
                Runnable::run,
                () -> {},
                () -> {},
                () -> { throw new IllegalStateException("remove"); },
                finalizers::incrementAndGet,
                failures::add);

        CloseAttempt attempt = coordinator.requestClose();

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                attempt.settlement().toCompletableFuture().join());
        assertEquals(1, finalizers.get());
        assertTrue(failures.stream().anyMatch(f -> "remove".equals(f.getMessage())));
    }

    @Test
    void removeMutationThenThrowKeepsOwnershipTombstoneAndStillFinalizes() {
        AtomicInteger ownershipReleases = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                Duration.ofSeconds(5), new ManualTimeoutScheduler(), Runnable::run,
                () -> {}, () -> {},
                () -> {
                    mutations.incrementAndGet();
                    throw new IllegalStateException("listener after mutation");
                },
                ownershipReleases::incrementAndGet,
                finalizers::incrementAndGet,
                ignored -> {});

        CloseAttempt attempt = coordinator.requestClose();

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                attempt.settlement().toCompletableFuture().join());
        assertEquals(1, mutations.get());
        assertEquals(0, ownershipReleases.get());
        assertEquals(1, finalizers.get());
        assertSame(attempt, coordinator.requestClose());
    }

    @Test
    void failedInstallationCreatesFatalTombstoneWithoutStartingUserGuard() {
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> {
                    guards.incrementAndGet();
                    return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
                }, Duration.ofSeconds(5), new ManualTimeoutScheduler(), Runnable::run,
                () -> {}, () -> {}, () -> {}, () -> {},
                finalizers::incrementAndGet, failures::add);
        IllegalStateException install = new IllegalStateException("install rollback");

        CloseAttempt attempt = coordinator.failInstallation(install);

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                attempt.settlement().toCompletableFuture().join());
        assertEquals(0, guards.get());
        assertEquals(1, finalizers.get());
        assertEquals(List.of(install), failures);
        assertSame(attempt, coordinator.requestClose());
    }

    @Test
    void pendingRejectedCleanupCannotOverrideInstallationFatal() throws Exception {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        AtomicInteger retryable = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> cleanup, Duration.ofSeconds(5), timeouts, Runnable::run,
                () -> {}, retryable::incrementAndGet, () -> {}, () -> {},
                finalizers::incrementAndGet, failures::add);
        CloseAttempt pending = coordinator.requestClose();
        IllegalStateException install = new IllegalStateException("install failed");

        assertSame(pending, coordinator.failInstallation(install));
        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                pending.settlement().toCompletableFuture().get(
                        1, java.util.concurrent.TimeUnit.SECONDS));
        cleanup.complete(CloseGuardOutcome.REJECTED);

        assertEquals(0, retryable.get());
        assertEquals(1, finalizers.get());
        assertEquals(1, timeouts.cancelledCount());
        assertTrue(failures.contains(install));
        assertSame(pending, coordinator.requestClose());
    }

    @Test
    void pendingApprovedCleanupCannotRemoveOrReleaseAfterInstallationFatal() throws Exception {
        CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        AtomicInteger removals = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> cleanup, Duration.ofSeconds(5), new ManualTimeoutScheduler(), Runnable::run,
                () -> {}, () -> {}, removals::incrementAndGet, releases::incrementAndGet,
                () -> {
                    finalizers.incrementAndGet();
                    throw new IllegalStateException("finalizer");
                }, failures::add);
        CloseAttempt pending = coordinator.requestClose();
        IllegalStateException install = new IllegalStateException("install failed");

        coordinator.failInstallation(install);
        cleanup.complete(CloseGuardOutcome.APPROVED);

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                pending.settlement().toCompletableFuture().get(
                        1, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, removals.get());
        assertEquals(0, releases.get());
        assertEquals(1, finalizers.get());
        assertTrue(failures.contains(install));
        assertTrue(failures.stream().anyMatch(f -> "finalizer".equals(f.getMessage())));
    }

    @Test
    void invokedFinalizerFailureIsReportedButSettlementIsCompleted() {
        IllegalStateException failure = new IllegalStateException("ui finalizer");
        Harness harness = new Harness(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                () -> { throw failure; });

        CloseAttempt attempt = harness.coordinator.requestClose();
        harness.drainFx();

        assertEquals(TabCloseOutcome.COMPLETED,
                attempt.settlement().toCompletableFuture().join());
        assertEquals(1, harness.finalizerInvocations.get());
        assertEquals(List.of(failure), harness.failures);
    }

    @Test
    void throwingTimerCancellationIsReportedButCannotStrandSettlement() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED),
                Duration.ofSeconds(5),
                (delay, task) -> () -> { throw new IllegalStateException("cancel timer"); },
                Runnable::run, () -> {}, () -> {}, () -> {}, () -> {}, failures::add);

        CloseAttempt attempt = coordinator.requestClose();

        assertEquals(TabCloseOutcome.COMPLETED,
                attempt.settlement().toCompletableFuture().get(1, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failures.stream().anyMatch(f -> "cancel timer".equals(f.getMessage())));
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
                        finalizers.incrementAndGet();
                        uiFinalizer.run();
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

        private void fireEvenIfCancelled() {
            scheduled.getLast().task.run();
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
