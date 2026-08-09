package com.datacube.fx;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Coordinates background cleanup, timeout state, and FX-only finalization for one managed tab. */
final class AsyncTabCloseCoordinator {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final ScheduledThreadPoolExecutor TIMEOUTS = createTimeoutExecutor();

    private final AsyncTabCloseGuard guard;
    private final Duration timeout;
    private final TimeoutScheduler timeoutScheduler;
    private final Consumer<Runnable> fxDispatcher;
    private final Runnable markClosing;
    private final Runnable markRetryable;
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
            Runnable markClosing,
            Runnable markRetryable,
            Runnable removeTabIfPresent,
            Runnable uiFinalizer,
            Consumer<? super Throwable> failureReporter) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.markClosing = Objects.requireNonNull(markClosing, "markClosing");
        this.markRetryable = Objects.requireNonNull(markRetryable, "markRetryable");
        this.removeTabIfPresent = Objects.requireNonNull(removeTabIfPresent, "removeTabIfPresent");
        this.uiFinalizer = Objects.requireNonNull(uiFinalizer, "uiFinalizer");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    CompletionStage<TabCloseOutcome> requestClose() {
        Attempt attempt;
        synchronized (this) {
            if (current != null) {
                return current.terminalForNewRequests == null
                        ? current.result
                        : CompletableFuture.completedFuture(current.terminalForNewRequests);
            }
            AsyncCloseGate.Request request = gate.beginRequest();
            if (request == null) throw new IllegalStateException("closed coordinator has no result");
            attempt = new Attempt(request);
            current = attempt;
        }

        try {
            fxDispatcher.accept(() -> runReported(markClosing));
        } catch (Throwable failure) {
            report(failure);
            cancelBeforeCleanup(attempt);
            return attempt.result;
        }
        start(attempt);
        return attempt.result;
    }

    private void start(Attempt attempt) {
        CompletionStage<CloseGuardOutcome> cleanup;
        try {
            cleanup = guard.requestClose();
        } catch (Throwable failure) {
            finishRetryable(attempt, failure);
            return;
        }
        if (cleanup == null) {
            finishRetryable(attempt, new NullPointerException("close guard returned null stage"));
            return;
        }

        try {
            TimeoutHandle handle = timeoutScheduler.schedule(timeout, () -> timeOut(attempt));
            synchronized (this) {
                attempt.timeoutHandle = Objects.requireNonNull(handle, "timeout handle");
            }
        } catch (Throwable failure) {
            timeOut(attempt, failure);
        }

        try {
            cleanup.whenComplete((outcome, failure) ->
                    cleanupCompleted(attempt, outcome, unwrap(failure)));
        } catch (Throwable failure) {
            cleanupCompleted(attempt, null, failure);
        }
    }

    private void timeOut(Attempt attempt) {
        timeOut(attempt, new TimeoutException("tab close timed out after " + timeout));
    }

    private void timeOut(Attempt attempt, Throwable failure) {
        synchronized (this) {
            if (current != attempt || attempt.cleanupTerminal || attempt.callerTimedOut) return;
            attempt.callerTimedOut = true;
        }
        report(failure);
        attempt.result.complete(TabCloseOutcome.TIMED_OUT_STILL_CLOSING);
    }

    private void cleanupCompleted(
            Attempt attempt,
            CloseGuardOutcome outcome,
            Throwable failure) {
        synchronized (this) {
            if (current != attempt || attempt.cleanupTerminal) return;
            attempt.cleanupTerminal = true;
            attempt.timeoutHandle.cancel();
        }

        if (failure != null) {
            finishRetryable(attempt, failure);
        } else if (outcome == null) {
            finishRetryable(attempt, new NullPointerException("close guard completed with null outcome"));
        } else if (outcome == CloseGuardOutcome.REJECTED) {
            finishRetryable(attempt, null);
        } else if (outcome == CloseGuardOutcome.FAILED_PARTIAL) {
            finishFatal(attempt);
        } else {
            gate.complete(attempt.request, true, () -> dispatchFinalizer(attempt), failureReporter);
        }
    }

    private void finishRetryable(Attempt attempt, Throwable failure) {
        if (failure != null) report(failure);
        try {
            fxDispatcher.accept(() -> runReported(markRetryable));
        } catch (Throwable dispatchFailure) {
            report(dispatchFailure);
            finishFatal(attempt);
            return;
        }
        synchronized (this) {
            if (current != attempt) return;
            gate.complete(attempt.request, false, () -> {}, failureReporter);
            current = null;
        }
        if (!attempt.callerTimedOut) attempt.result.complete(TabCloseOutcome.CANCELLED);
    }

    private void finishFatal(Attempt attempt) {
        synchronized (this) {
            if (current != attempt) return;
            gate.complete(attempt.request, true, () -> {}, failureReporter);
            attempt.terminalForNewRequests = TabCloseOutcome.FAILED_PARTIAL;
        }
        if (!attempt.callerTimedOut) attempt.result.complete(TabCloseOutcome.FAILED_PARTIAL);
    }

    private void cancelBeforeCleanup(Attempt attempt) {
        synchronized (this) {
            if (current != attempt) return;
            gate.complete(attempt.request, false, () -> {}, failureReporter);
            current = null;
        }
        attempt.result.complete(TabCloseOutcome.CANCELLED);
    }

    private void dispatchFinalizer(Attempt attempt) {
        try {
            fxDispatcher.accept(() -> {
                runReported(removeTabIfPresent);
                runReported(uiFinalizer);
                synchronized (AsyncTabCloseCoordinator.this) {
                    attempt.terminalForNewRequests = TabCloseOutcome.COMPLETED;
                }
                if (!attempt.callerTimedOut) attempt.result.complete(TabCloseOutcome.COMPLETED);
            });
        } catch (Throwable failure) {
            report(failure);
            finishFatal(attempt);
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
        private final CompletableFuture<TabCloseOutcome> result = new CompletableFuture<>();
        private TimeoutHandle timeoutHandle = () -> {};
        private boolean callerTimedOut;
        private boolean cleanupTerminal;
        private TabCloseOutcome terminalForNewRequests;

        private Attempt(AsyncCloseGate.Request request) {
            this.request = request;
        }
    }
}
