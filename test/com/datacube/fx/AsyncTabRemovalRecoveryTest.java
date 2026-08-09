package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTabRemovalRecoveryTest {

    @Test
    void cancelledRemovalRestoresInteractiveButTimeoutAndFatalRestoreDisabled() {
        Queue<Runnable> fxTasks = new ArrayDeque<>();
        List<Boolean> restoredDisabled = new ArrayList<>();

        AsyncTabRemovalRecovery.restoreOnIncomplete(
                CompletableFuture.completedFuture(TabCloseOutcome.CANCELLED),
                fxTasks::add, restoredDisabled::add, ignored -> {});
        AsyncTabRemovalRecovery.restoreOnIncomplete(
                CompletableFuture.completedFuture(TabCloseOutcome.TIMED_OUT_STILL_CLOSING),
                fxTasks::add, restoredDisabled::add, ignored -> {});
        AsyncTabRemovalRecovery.restoreOnIncomplete(
                CompletableFuture.completedFuture(TabCloseOutcome.FAILED_PARTIAL),
                fxTasks::add, restoredDisabled::add, ignored -> {});
        while (!fxTasks.isEmpty()) fxTasks.remove().run();

        assertEquals(List.of(false, true, true), restoredDisabled);
    }

    @Test
    void completedDoesNotRestoreAndExceptionalOrNullOutcomeReportsAndRestoresDisabled() {
        Queue<Runnable> fxTasks = new ArrayDeque<>();
        List<Boolean> restoredDisabled = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        AsyncTabRemovalRecovery.restoreOnIncomplete(
                CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED),
                fxTasks::add, restoredDisabled::add, failures::add);
        assertTrue(fxTasks.isEmpty());

        CompletableFuture<TabCloseOutcome> failed = new CompletableFuture<>();
        AsyncTabRemovalRecovery.restoreOnIncomplete(
                failed, fxTasks::add, restoredDisabled::add, failures::add);
        failed.completeExceptionally(new IllegalStateException("close"));
        fxTasks.remove().run();

        AsyncTabRemovalRecovery.restoreOnIncomplete(
                CompletableFuture.completedFuture(null),
                fxTasks::add, restoredDisabled::add, failures::add);
        fxTasks.remove().run();

        assertEquals(List.of(true, true), restoredDisabled);
        assertEquals(2, failures.size());
    }

    @Test
    void externalRemovalTimeoutLateApprovalRemovesAgainAndFinalizes() {
        LifecycleHarness harness = new LifecycleHarness();
        harness.present = false;

        var close = harness.coordinator.requestClose();
        AsyncTabRemovalRecovery.restoreOnIncomplete(
                close, harness.fxTasks::add, harness::restore, ignored -> {});
        harness.timeout.fire();
        harness.drainFx();

        assertEquals(TabCloseOutcome.TIMED_OUT_STILL_CLOSING, close.toCompletableFuture().join());
        assertTrue(harness.present);
        assertTrue(harness.disabled);

        harness.cleanup.complete(CloseGuardOutcome.APPROVED);
        harness.drainFx();

        assertFalse(harness.present);
        assertEquals(1, harness.finalizers.get());
    }

    @Test
    void externalRemovalTimeoutLateRejectionReenablesRestoredTabForRetry() {
        LifecycleHarness harness = new LifecycleHarness();
        harness.present = false;

        var close = harness.coordinator.requestClose();
        AsyncTabRemovalRecovery.restoreOnIncomplete(
                close, harness.fxTasks::add, harness::restore, ignored -> {});
        harness.timeout.fire();
        harness.drainFx();
        harness.cleanup.complete(CloseGuardOutcome.REJECTED);
        harness.drainFx();

        assertTrue(harness.present);
        assertFalse(harness.disabled);
    }

    private static final class LifecycleHarness {
        private final CompletableFuture<CloseGuardOutcome> cleanup = new CompletableFuture<>();
        private final Queue<Runnable> fxTasks = new ArrayDeque<>();
        private final AtomicInteger finalizers = new AtomicInteger();
        private final ManualTimeout timeout = new ManualTimeout();
        private boolean present = true;
        private boolean disabled;
        private final AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
                () -> cleanup,
                Duration.ofSeconds(5),
                (delay, task) -> timeout.install(task),
                fxTasks::add,
                () -> disabled = true,
                () -> disabled = false,
                () -> present = false,
                finalizers::incrementAndGet,
                ignored -> {});

        private void restore(boolean disabled) {
            present = true;
            this.disabled = disabled;
        }

        private void drainFx() {
            while (!fxTasks.isEmpty()) fxTasks.remove().run();
        }
    }

    private static final class ManualTimeout implements AsyncTabCloseCoordinator.TimeoutHandle {
        private Runnable task;
        private boolean cancelled;

        private ManualTimeout install(Runnable task) {
            this.task = task;
            return this;
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
