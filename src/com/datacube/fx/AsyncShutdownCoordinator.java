package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs destructive application teardown only after every managed tab approves closing. */
final class AsyncShutdownCoordinator {

    private final Supplier<CompletionStage<Boolean>> closeTabs;
    private final Consumer<Runnable> blockingStarter;
    private final Runnable destructiveTeardown;
    private final Consumer<? super Throwable> failureReporter;

    private CompletableFuture<Boolean> current;

    AsyncShutdownCoordinator(
            Supplier<CompletionStage<Boolean>> closeTabs,
            Consumer<Runnable> blockingStarter,
            Runnable destructiveTeardown,
            Consumer<? super Throwable> failureReporter) {
        this.closeTabs = Objects.requireNonNull(closeTabs, "closeTabs");
        this.blockingStarter = Objects.requireNonNull(blockingStarter, "blockingStarter");
        this.destructiveTeardown = Objects.requireNonNull(destructiveTeardown, "destructiveTeardown");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    CompletionStage<Boolean> shutdown() {
        CompletableFuture<Boolean> attempt;
        synchronized (this) {
            if (current != null) return current;
            attempt = new CompletableFuture<>();
            current = attempt;
        }

        CompletionStage<Boolean> tabs;
        try {
            tabs = Objects.requireNonNull(closeTabs.get(), "closeTabs returned null");
            tabs.whenComplete((approved, failure) -> tabsCompleted(attempt, approved, failure));
        } catch (Throwable failure) {
            reject(attempt, failure);
        }
        return attempt;
    }

    private void tabsCompleted(
            CompletableFuture<Boolean> attempt,
            Boolean approved,
            Throwable failure) {
        if (failure != null || !Boolean.TRUE.equals(approved)) {
            reject(attempt, failure);
            return;
        }
        try {
            blockingStarter.accept(() -> {
                try {
                    destructiveTeardown.run();
                } catch (Throwable teardownFailure) {
                    report(teardownFailure);
                }
                attempt.complete(true);
            });
        } catch (Throwable startFailure) {
            reject(attempt, startFailure);
        }
    }

    private void reject(CompletableFuture<Boolean> attempt, Throwable failure) {
        synchronized (this) {
            if (current != attempt) return;
            current = null;
        }
        if (failure != null) report(failure);
        if (failure == null) attempt.complete(false);
        else attempt.completeExceptionally(failure);
    }

    private void report(Throwable failure) {
        try {
            failureReporter.accept(failure);
        } catch (Throwable ignored) {
            // Reporting must not change whether shutdown is safe to retry.
        }
    }
}
