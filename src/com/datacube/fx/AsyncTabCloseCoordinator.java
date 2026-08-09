package com.datacube.fx;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Coordinates background cleanup and one generation-checked FX settlement for a managed tab. */
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
    private final Runnable releaseOwnership;
    private final Runnable uiFinalizer;
    private final Consumer<? super Throwable> failureReporter;
    private final AsyncCloseGate gate = new AsyncCloseGate();
    private Attempt current;

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
        this(guard, timeout, timeoutScheduler, fxDispatcher, markClosing, markRetryable,
                removeTabIfPresent, () -> {}, uiFinalizer, failureReporter);
    }

    AsyncTabCloseCoordinator(
            AsyncTabCloseGuard guard,
            Duration timeout,
            TimeoutScheduler timeoutScheduler,
            Consumer<Runnable> fxDispatcher,
            Runnable markClosing,
            Runnable markRetryable,
            Runnable removeTabIfPresent,
            Runnable releaseOwnership,
            Runnable uiFinalizer,
            Consumer<? super Throwable> failureReporter) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.fxDispatcher = Objects.requireNonNull(fxDispatcher, "fxDispatcher");
        this.markClosing = Objects.requireNonNull(markClosing, "markClosing");
        this.markRetryable = Objects.requireNonNull(markRetryable, "markRetryable");
        this.removeTabIfPresent = Objects.requireNonNull(removeTabIfPresent, "removeTabIfPresent");
        this.releaseOwnership = Objects.requireNonNull(releaseOwnership, "releaseOwnership");
        this.uiFinalizer = Objects.requireNonNull(uiFinalizer, "uiFinalizer");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    CloseAttempt requestClose() {
        return requestClose(null);
    }

    CloseAttempt requestClose(Runnable restoreBeforeClosing) {
        Attempt attempt;
        synchronized (this) {
            if (current != null) {
                attempt = current;
                if (restoreBeforeClosing != null) {
                    if (attempt.exposed.status() == CloseAttemptStatus.SETTLED) {
                        dispatchFatalRestore(attempt, restoreBeforeClosing);
                    } else {
                        dispatch(attempt, restoreBeforeClosing, true);
                    }
                }
                return attempt.exposed;
            }
            AsyncCloseGate.Request request = gate.beginRequest();
            if (request == null) throw new IllegalStateException("closed coordinator has no result");
            attempt = new Attempt(request);
            current = attempt;
        }
        Runnable prepare = () -> {
            if (restoreBeforeClosing != null) restoreBeforeClosing.run();
            markClosing.run();
        };
        if (!dispatch(attempt, prepare, true)) return attempt.exposed;
        synchronized (this) {
            if (!isCurrentUnsettled(attempt)) return attempt.exposed;
        }
        start(attempt);
        return attempt.exposed;
    }

    synchronized boolean isRemovalAuthorized() {
        return current != null && current.removalAuthorized;
    }

    CloseAttempt failInstallation(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Attempt attempt;
        Throwable cancelFailure = null;
        synchronized (this) {
            if (current == null) {
                AsyncCloseGate.Request request = gate.beginRequest();
                if (request == null) throw new IllegalStateException("closed coordinator has no result");
                current = new Attempt(request);
            }
            attempt = current;
            attempt.installationFatal = true;
            attempt.cleanupTerminal = true;
            try {
                attempt.timeoutHandle.cancel();
            } catch (Throwable timerFailure) {
                cancelFailure = timerFailure;
            }
            settleFatalOnFx(attempt);
        }
        report(failure);
        if (cancelFailure != null) report(cancelFailure);
        invokeUiFinalizer(attempt);
        return attempt.exposed;
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
            TimeoutHandle handle = Objects.requireNonNull(
                    timeoutScheduler.schedule(timeout, () -> timeOut(attempt)), "timeout handle");
            synchronized (this) {
                if (current == attempt && !attempt.cleanupTerminal) attempt.timeoutHandle = handle;
                else handle.cancel();
            }
        } catch (Throwable failure) {
            report(failure);
            // Scheduling a warning failed, but the real cleanup remains the only terminal authority.
        }
        try {
            cleanup.whenComplete((outcome, failure) ->
                    cleanupCompleted(attempt, outcome, unwrap(failure)));
        } catch (Throwable failure) {
            cleanupCompleted(attempt, null, failure);
        }
    }

    private void timeOut(Attempt attempt) {
        synchronized (this) {
            if (current != attempt || attempt.cleanupTerminal
                    || attempt.exposed.status() == CloseAttemptStatus.SETTLED) return;
            attempt.exposed.markStillClosing();
        }
        report(new TimeoutException("tab close still running after " + timeout));
    }

    private void cleanupCompleted(Attempt attempt, CloseGuardOutcome outcome, Throwable failure) {
        synchronized (this) {
            if (current != attempt || attempt.cleanupTerminal) return;
            attempt.cleanupTerminal = true;
            try {
                attempt.timeoutHandle.cancel();
            } catch (Throwable cancelFailure) {
                report(cancelFailure);
            }
        }
        if (failure != null) finishRetryable(attempt, failure);
        else if (outcome == null) finishRetryable(
                attempt, new NullPointerException("close guard completed with null outcome"));
        else switch (outcome) {
            case REJECTED -> finishRetryable(attempt, null);
            case FAILED_PARTIAL -> finishFatal(attempt);
            case APPROVED -> finishApproved(attempt);
        }
    }

    private void finishRetryable(Attempt attempt, Throwable failure) {
        if (failure != null) report(failure);
        dispatch(attempt, () -> {
            try {
                markRetryable.run();
            } catch (Throwable retryFailure) {
                report(retryFailure);
                settleFatalOnFx(attempt);
                return;
            }
            synchronized (AsyncTabCloseCoordinator.this) {
                if (!isCurrentUnsettled(attempt)) return;
                gate.complete(attempt.request, false, () -> {}, failureReporter);
                current = null;
            }
            attempt.exposed.settle(TabCloseOutcome.CANCELLED);
        }, true);
    }

    private void finishApproved(Attempt attempt) {
        dispatch(attempt, () -> {
            synchronized (AsyncTabCloseCoordinator.this) {
                if (!isCurrentUnsettled(attempt)) return;
                attempt.removalAuthorized = true;
                gate.complete(attempt.request, true, () -> {}, failureReporter);
            }
            boolean removeFailed = false;
            try {
                removeTabIfPresent.run();
            } catch (Throwable failure) {
                removeFailed = true;
                report(failure);
            }
            if (!removeFailed) {
                try {
                    releaseOwnership.run();
                } catch (Throwable failure) {
                    removeFailed = true;
                    report(failure);
                }
            }
            invokeUiFinalizer(attempt);
            attempt.exposed.settle(removeFailed
                    ? TabCloseOutcome.FAILED_PARTIAL : TabCloseOutcome.COMPLETED);
        }, true);
    }

    private void finishFatal(Attempt attempt) {
        dispatch(attempt, () -> settleFatalOnFx(attempt), true);
    }

    private void settleFatalOnFx(Attempt attempt) {
        synchronized (this) {
            if (!isCurrentUnsettled(attempt)) return;
            gate.complete(attempt.request, true, () -> {}, failureReporter);
        }
        attempt.exposed.settle(TabCloseOutcome.FAILED_PARTIAL);
    }

    private boolean dispatch(Attempt attempt, Runnable action, boolean fatalOnFailure) {
        try {
            fxDispatcher.accept(() -> {
                synchronized (AsyncTabCloseCoordinator.this) {
                    if (!isCurrentUnsettled(attempt)) return;
                }
                try {
                    action.run();
                } catch (Throwable failure) {
                    report(failure);
                    if (fatalOnFailure) settleFatalOnFx(attempt);
                    else cancelBeforeCleanup(attempt);
                }
            });
            return true;
        } catch (Throwable failure) {
            report(failure);
            if (fatalOnFailure) settleFatalWithoutFx(attempt);
            else cancelBeforeCleanup(attempt);
            return false;
        }
    }

    private void dispatchFatalRestore(Attempt attempt, Runnable restore) {
        try {
            fxDispatcher.accept(() -> {
                synchronized (AsyncTabCloseCoordinator.this) {
                    if (current != attempt
                            || attempt.exposed.settlement().toCompletableFuture().getNow(null)
                            != TabCloseOutcome.FAILED_PARTIAL) return;
                }
                try { restore.run(); } catch (Throwable failure) { report(failure); }
            });
        } catch (Throwable failure) {
            report(failure);
        }
    }

    private void cancelBeforeCleanup(Attempt attempt) {
        synchronized (this) {
            if (!isCurrentUnsettled(attempt)) return;
            gate.complete(attempt.request, false, () -> {}, failureReporter);
            current = null;
        }
        attempt.exposed.settle(TabCloseOutcome.CANCELLED);
    }

    private void settleFatalWithoutFx(Attempt attempt) {
        synchronized (this) {
            if (!isCurrentUnsettled(attempt)) return;
            gate.complete(attempt.request, true, () -> {}, failureReporter);
        }
        attempt.exposed.settle(TabCloseOutcome.FAILED_PARTIAL);
        try {
            fxDispatcher.accept(() -> invokeUiFinalizer(attempt));
        } catch (Throwable finalizerDispatchFailure) {
            report(finalizerDispatchFailure);
        }
    }

    private void invokeUiFinalizer(Attempt attempt) {
        synchronized (this) {
            if (attempt.finalizerInvoked) return;
            attempt.finalizerInvoked = true;
        }
        try {
            uiFinalizer.run();
        } catch (Throwable failure) {
            // Invocation is exactly-once; a light UI finalizer failure is observable but not a cleanup rollback.
            report(failure);
        }
    }

    private boolean isCurrentUnsettled(Attempt attempt) {
        return current == attempt && attempt.exposed.status() != CloseAttemptStatus.SETTLED;
    }

    private void report(Throwable failure) {
        try { failureReporter.accept(failure); } catch (Throwable ignored) { }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon(true).name("DataCube-tab-close-timeout").factory());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    static TimeoutHandle scheduleTimeout(Duration delay, Runnable action) {
        ScheduledFuture<?> scheduled = TIMEOUTS.schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> scheduled.cancel(false);
    }

    @FunctionalInterface interface TimeoutScheduler { TimeoutHandle schedule(Duration delay, Runnable task); }
    @FunctionalInterface interface TimeoutHandle { void cancel(); }

    private static final class Attempt {
        private final AsyncCloseGate.Request request;
        private final CloseAttempt exposed;
        private TimeoutHandle timeoutHandle = () -> {};
        private boolean cleanupTerminal;
        private boolean installationFatal;
        private boolean removalAuthorized;
        private boolean finalizerInvoked;

        private Attempt(AsyncCloseGate.Request request) {
            this.request = request;
            this.exposed = new CloseAttempt(request.generation());
        }
    }
}
