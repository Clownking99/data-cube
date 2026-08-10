package com.datacube.fx;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** One observable, single-flight settlement that retains strict cleanup ownership until success. */
final class StrictCleanupRetryChannel {
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(100);

    private final Runnable cleanup;
    private final Consumer<? super Throwable> failureReporter;
    private final Duration retryDelay;
    private CompletableFuture<Void> settlement;

    StrictCleanupRetryChannel(Runnable cleanup, Consumer<? super Throwable> failureReporter) {
        this(cleanup, failureReporter, DEFAULT_RETRY_DELAY);
    }

    StrictCleanupRetryChannel(
            Runnable cleanup,
            Consumer<? super Throwable> failureReporter,
            Duration retryDelay) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must not be negative");
    }

    synchronized CompletionStage<Void> start() {
        if (settlement != null) return settlement;
        settlement = new CompletableFuture<>();
        CompletableFuture<Void> created = settlement;
        try {
            Thread.startVirtualThread(() -> retryUntilSettled(created));
        } catch (Throwable startupFailure) {
            report(startupFailure);
            retryUntilSettled(created);
        }
        return created;
    }

    private void retryUntilSettled(CompletableFuture<Void> target) {
        while (!target.isDone()) {
            try {
                cleanup.run();
                target.complete(null);
            } catch (Throwable failure) {
                report(failure);
                waitBeforeRetry();
            }
        }
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException interrupted) {
            report(interrupted);
            Thread.interrupted();
        }
    }

    private void report(Throwable failure) {
        try {
            failureReporter.accept(failure);
        } catch (Throwable ignored) {
        }
    }
}
