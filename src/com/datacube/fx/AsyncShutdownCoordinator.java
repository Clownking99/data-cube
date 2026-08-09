package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs destructive application teardown only after every managed tab completes closing. */
final class AsyncShutdownCoordinator {

    private final Supplier<CompletionStage<TabCloseOutcome>> closeTabs;
    private final Consumer<Runnable> blockingStarter;
    private final Runnable destructiveTeardown;
    private final Consumer<? super Throwable> failureReporter;

    private CompletableFuture<ShutdownOutcome> current;

    AsyncShutdownCoordinator(
            Supplier<CompletionStage<TabCloseOutcome>> closeTabs,
            Consumer<Runnable> blockingStarter,
            Runnable destructiveTeardown,
            Consumer<? super Throwable> failureReporter) {
        this.closeTabs = Objects.requireNonNull(closeTabs, "closeTabs");
        this.blockingStarter = Objects.requireNonNull(blockingStarter, "blockingStarter");
        this.destructiveTeardown = Objects.requireNonNull(destructiveTeardown, "destructiveTeardown");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    CompletionStage<ShutdownOutcome> shutdown() {
        CompletableFuture<ShutdownOutcome> attempt;
        synchronized (this) {
            if (current != null) return current;
            attempt = new CompletableFuture<>();
            current = attempt;
        }

        CompletionStage<TabCloseOutcome> tabs;
        try {
            tabs = Objects.requireNonNull(closeTabs.get(), "closeTabs returned null");
            tabs.whenComplete((outcome, failure) -> tabsCompleted(attempt, outcome, failure));
        } catch (Throwable failure) {
            rejectExceptionally(attempt, failure);
        }
        return attempt;
    }

    private void tabsCompleted(
            CompletableFuture<ShutdownOutcome> attempt,
            TabCloseOutcome outcome,
            Throwable failure) {
        if (failure != null) {
            rejectExceptionally(attempt, failure);
            return;
        }
        if (outcome == null) {
            rejectExceptionally(attempt, new NullPointerException("closeTabs completed with null outcome"));
            return;
        }
        if (outcome == TabCloseOutcome.FAILED_PARTIAL) {
            attempt.complete(ShutdownOutcome.FAILED_PARTIAL);
            return;
        }
        if (outcome != TabCloseOutcome.COMPLETED) {
            cancel(attempt);
            return;
        }
        try {
            blockingStarter.accept(() -> {
                try {
                    destructiveTeardown.run();
                    attempt.complete(ShutdownOutcome.COMPLETED);
                } catch (Throwable teardownFailure) {
                    report(teardownFailure);
                    attempt.complete(ShutdownOutcome.FAILED_PARTIAL);
                }
            });
        } catch (Throwable startFailure) {
            rejectExceptionally(attempt, startFailure);
        }
    }

    private void cancel(CompletableFuture<ShutdownOutcome> attempt) {
        synchronized (this) {
            if (current != attempt) return;
            current = null;
        }
        attempt.complete(ShutdownOutcome.CANCELLED);
    }

    private void rejectExceptionally(
            CompletableFuture<ShutdownOutcome> attempt,
            Throwable failure) {
        synchronized (this) {
            if (current != attempt) return;
            current = null;
        }
        report(failure);
        attempt.completeExceptionally(failure);
    }

    private void report(Throwable failure) {
        try {
            failureReporter.accept(failure);
        } catch (Throwable ignored) {
            // Reporting must not change the shutdown terminal state.
        }
    }
}
