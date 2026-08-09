package com.datacube.fx;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Coordinates one guarded tab's blocking cleanup and FX-only finalization phases.
 * Exactly-once finalization means the finalizer is invoked once; its failure is reported, not retried.
 */
final class AsyncTabCloseCoordinator {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final AsyncTabCloseGuard guard;
    private final Duration timeout;
    private final BiConsumer<Duration, Runnable> timeoutScheduler;
    private final Consumer<Runnable> fxDispatcher;
    private final Runnable removeTabIfPresent;
    private final Runnable uiFinalizer;
    private final Consumer<? super Throwable> failureReporter;
    private final AsyncCloseGate gate = new AsyncCloseGate();

    private Attempt current;

    AsyncTabCloseCoordinator(
            AsyncTabCloseGuard guard,
            Duration timeout,
            BiConsumer<Duration, Runnable> timeoutScheduler,
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
            attempt = new Attempt(request, new CompletableFuture<>());
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
            reject(attempt, failure);
            return;
        }
        if (cleanup == null) {
            reject(attempt, new NullPointerException("close guard returned null stage"));
            return;
        }

        try {
            cleanup.whenComplete((approved, failure) -> {
                Throwable actualFailure = unwrap(failure);
                if (actualFailure != null) reject(attempt, actualFailure);
                else if (approved == null) reject(
                        attempt, new NullPointerException("close guard completed with null"));
                else finish(attempt, approved);
            });
            timeoutScheduler.accept(timeout, () -> reject(
                    attempt, new TimeoutException("tab close timed out after " + timeout)));
        } catch (Throwable failure) {
            reject(attempt, failure);
        }
    }

    private void reject(Attempt attempt, Throwable failure) {
        boolean handled;
        synchronized (this) {
            handled = gate.complete(attempt.request, false, () -> {}, failureReporter);
            if (handled && current == attempt) current = null;
        }
        if (!handled) return;
        report(failure);
        attempt.result.complete(false);
    }

    private void finish(Attempt attempt, boolean approved) {
        if (!approved) {
            boolean handled;
            synchronized (this) {
                handled = gate.complete(attempt.request, false, () -> {}, failureReporter);
                if (handled && current == attempt) current = null;
            }
            if (handled) attempt.result.complete(false);
            return;
        }

        gate.complete(attempt.request, true, () -> dispatchFinalizer(attempt), failureReporter);
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

    static void scheduleTimeout(Duration delay, Runnable action) {
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(action);
    }

    private record Attempt(AsyncCloseGate.Request request, CompletableFuture<Boolean> result) {}
}
