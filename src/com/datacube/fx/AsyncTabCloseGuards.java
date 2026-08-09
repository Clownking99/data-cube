package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Factories for retry-aware asynchronous close guards. */
final class AsyncTabCloseGuards {

    private AsyncTabCloseGuards() {}

    static AsyncTabCloseGuard blocking(Runnable cleanup) {
        return fatalOnce(Objects.requireNonNull(cleanup, "cleanup"), ignored -> {});
    }

    static AsyncTabCloseGuard blocking(
            Runnable cleanup,
            Consumer<? super Throwable> reporter) {
        return fatalOnce(Objects.requireNonNull(cleanup, "cleanup"),
                Objects.requireNonNull(reporter, "reporter"));
    }

    /** Mandatory ownership-abort cleanup: never asks the user and any failure is permanently fatal. */
    static AsyncTabCloseGuard mandatoryAbort(Runnable cleanup) {
        return fatalOnce(Objects.requireNonNull(cleanup, "cleanup"), ignored -> {});
    }

    static AsyncTabCloseGuard mandatoryAbort(
            Runnable cleanup,
            Consumer<? super Throwable> reporter) {
        return fatalOnce(Objects.requireNonNull(cleanup, "cleanup"),
                Objects.requireNonNull(reporter, "reporter"));
    }

    /** Explicitly retryable background action for work known not to have destructive partial effects. */
    static AsyncTabCloseGuard blockingAttempt(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        return blockingAttempt(() -> cleanup);
    }

    /** Captures one attempt on the caller thread, then runs that attempt on a virtual thread. */
    static AsyncTabCloseGuard blockingAttempt(Supplier<? extends Runnable> cleanupFactory) {
        Objects.requireNonNull(cleanupFactory, "cleanupFactory");
        return retryable(() -> {
            Runnable cleanup = Objects.requireNonNull(cleanupFactory.get(), "cleanup attempt");
            CompletableFuture<CloseGuardOutcome> result = new CompletableFuture<>();
            try {
                Thread.startVirtualThread(() -> {
                    try {
                        cleanup.run();
                        result.complete(CloseGuardOutcome.APPROVED);
                    } catch (PartialCloseException partial) {
                        result.complete(CloseGuardOutcome.FAILED_PARTIAL);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
            return result;
        });
    }

    private static AsyncTabCloseGuard fatalOnce(
            Runnable cleanup,
            Consumer<? super Throwable> reporter) {
        AtomicReference<CompletableFuture<CloseGuardOutcome>> current = new AtomicReference<>();
        return () -> {
            CompletableFuture<CloseGuardOutcome> existing = current.get();
            if (existing != null) return existing;
            CompletableFuture<CloseGuardOutcome> created = new CompletableFuture<>();
            if (!current.compareAndSet(null, created)) return current.get();
            try {
                Thread.startVirtualThread(() -> {
                    try {
                        cleanup.run();
                        created.complete(CloseGuardOutcome.APPROVED);
                    } catch (Throwable failure) {
                        report(reporter, failure);
                        created.complete(CloseGuardOutcome.FAILED_PARTIAL);
                    }
                });
            } catch (Throwable failure) {
                report(reporter, failure);
                created.complete(CloseGuardOutcome.FAILED_PARTIAL);
            }
            return created;
        };
    }

    private static void report(Consumer<? super Throwable> reporter, Throwable failure) {
        try { reporter.accept(failure); } catch (Throwable ignored) { }
    }

    /** Caches only an in-flight, approved, or fatal-partial attempt; retryable terminals are cleared. */
    static AsyncTabCloseGuard retryable(AsyncTabCloseGuard delegate) {
        Objects.requireNonNull(delegate, "delegate");
        AtomicReference<CompletableFuture<CloseGuardOutcome>> current = new AtomicReference<>();
        return () -> {
            CompletableFuture<CloseGuardOutcome> existing = current.get();
            if (existing != null) return existing;

            CompletableFuture<CloseGuardOutcome> created = new CompletableFuture<>();
            if (!current.compareAndSet(null, created)) return current.get();
            CompletionStage<CloseGuardOutcome> attempt;
            try {
                attempt = Objects.requireNonNull(delegate.requestClose(), "close guard returned null stage");
            } catch (Throwable failure) {
                current.compareAndSet(created, null);
                created.completeExceptionally(failure);
                return created;
            }
            try {
                attempt.whenComplete((outcome, failure) -> {
                    boolean retryable = failure != null
                            || outcome == null
                            || outcome == CloseGuardOutcome.REJECTED;
                    if (retryable) current.compareAndSet(created, null);
                    if (failure != null) created.completeExceptionally(failure);
                    else if (outcome == null) created.completeExceptionally(
                            new NullPointerException("close guard completed with null outcome"));
                    else created.complete(outcome);
                });
            } catch (Throwable failure) {
                current.compareAndSet(created, null);
                created.completeExceptionally(failure);
            }
            return created;
        };
    }
}
