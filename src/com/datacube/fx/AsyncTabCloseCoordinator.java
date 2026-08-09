package com.datacube.fx;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Coordinates one guarded tab's blocking cleanup and FX-only finalization phases.
 * Exactly-once finalization means the finalizer is invoked once; its failure is reported, not retried.
 */
final class AsyncTabCloseCoordinator {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final ScheduledThreadPoolExecutor TIMEOUTS = createTimeoutExecutor();

    private final AsyncTabCloseGuard guard;
    private final Duration timeout;
    private final TimeoutScheduler timeoutScheduler;
    private final Consumer<Runnable> fxDispatcher;
    private final Runnable removeTabIfPresent;
    private final Runnable uiFinalizer;
    private final Consumer<? super Throwable> failureReporter;
    private final AsyncCloseGate gate = new AsyncCloseGate();

    private Attempt current;

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform().daemon(true).name("DataCube-tab-close-timeout").factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    AsyncTabCloseCoordinator(
            AsyncTabCloseGuard guard,
            Duration timeout,
            TimeoutScheduler timeoutScheduler,
            Consumer<Runnable> fxDispatcher,
            Runnable removeTabIfPresent,
            Runnable uiFinalizer,
            Consumer<? super Throwable> failureReporter) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.removeTabIfPresent = Objects.requireNonNull(removeTabIfPresent, "removeTabIfPresent");
        this.uiFinalizer = Objects.requireNonNull(uiFinalizer, "uiFinalizer");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    CompletionStage<Boolean> requestClose() {
        Attempt attempt;
        synchronized (this) {
            if (current != null) return current.result;
            AsyncCloseGate.Request request = gate.beginRequest();
            if (request == null) throw new IllegalStateException("closed coordinator has no result");
            attempt = new Attempt(request);
            current = attempt;
        }
        start(attempt);
        return attempt.result;
    }

    private void start(Attempt attempt) {
        CompletionStage<Boolean> cleanup;
        try {
            cleanup = guard.requestClose();
        } catch (Throwable failure) {
            rejectBeforeCleanup(attempt, failure);
            return;
        }
        if (cleanup == null) {
            rejectBeforeCleanup(attempt, new NullPointerException("close guard returned null stage"));
            return;
        }

        try {
            TimeoutHandle handle = timeoutScheduler.schedule(timeout, () -> timeOut(attempt));
            synchronized (this) {
                attempt.timeoutHandle = Objects.requireNonNull(handle, "timeout handle");
            }
        } catch (Throwable failure) {
            abandonCallerButAwaitCleanup(attempt, failure);
        }

        try {
            cleanup.whenComplete((approved, failure) ->
                    cleanupCompleted(attempt, approved, unwrap(failure)));
        } catch (Throwable failure) {
            cleanupCompleted(attempt, false, failure);
        }
    }

    private void timeOut(Attempt attempt) {
        abandonCallerButAwaitCleanup(
                attempt, new TimeoutException("tab close timed out after " + timeout));
    }

    private void abandonCallerButAwaitCleanup(Attempt attempt, Throwable failure) {
        synchronized (this) {
            if (current != attempt || attempt.cleanupTerminal || attempt.callerAbandoned) return;
            attempt.callerAbandoned = true;
        }
        report(failure);
        attempt.result.complete(false);
    }

    private void cleanupCompleted(Attempt attempt, Boolean approved, Throwable failure) {
        boolean abandoned;
        boolean closeApproved;
        synchronized (this) {
            if (current != attempt || attempt.cleanupTerminal) return;
            attempt.cleanupTerminal = true;
            attempt.timeoutHandle.cancel();
            abandoned = attempt.callerAbandoned;
            closeApproved = failure == null && Boolean.TRUE.equals(approved) && !abandoned;

            if (!closeApproved) {
                gate.complete(attempt.request, false, () -> {}, failureReporter);
                current = null;
            }
        }

        if (failure != null) report(failure);
        else if (approved == null) report(new NullPointerException("close guard completed with null"));
        if (abandoned) return;
        if (!closeApproved) {
            attempt.result.complete(false);
            return;
        }
        gate.complete(attempt.request, true, () -> dispatchFinalizer(attempt), failureReporter);
    }

    private void rejectBeforeCleanup(Attempt attempt, Throwable failure) {
        synchronized (this) {
            if (current != attempt) return;
            attempt.cleanupTerminal = true;
            gate.complete(attempt.request, false, () -> {}, failureReporter);
            current = null;
        }
        report(failure);
        attempt.result.complete(false);
    }

    private void dispatchFinalizer(Attempt attempt) {
        try {
            fxDispatcher.accept(() -> {
                runReported(removeTabIfPresent);
                runReported(uiFinalizer);
                attempt.result.complete(true);
            });
        } catch (Throwable failure) {
            report(failure);
            attempt.result.complete(true);
        }
    }

    private void runReported(Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            report(failure);
        }
    }

    private void report(Throwable failure) {
        try {
            failureReporter.accept(failure);
        } catch (Throwable ignored) {
            // Failure reporting is deliberately isolated from lifecycle completion.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    static TimeoutHandle scheduleTimeout(Duration delay, Runnable action) {
        ScheduledFuture<?> scheduled = TIMEOUTS.schedule(
                action, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> scheduled.cancel(false);
    }

    @FunctionalInterface
    interface TimeoutScheduler {
        TimeoutHandle schedule(Duration delay, Runnable task);
    }

    @FunctionalInterface
    interface TimeoutHandle {
        void cancel();
    }

    private static final class Attempt {
        private final AsyncCloseGate.Request request;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private TimeoutHandle timeoutHandle = () -> {};
        private boolean callerAbandoned;
        private boolean cleanupTerminal;

        private Attempt(AsyncCloseGate.Request request) {
            this.request = request;
        }
    }
}
