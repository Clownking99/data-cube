package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** One-way sealed accounting for mandatory-abort ownership leases. */
final class MandatoryAbortTracker {
    private final CompletableFuture<TabCloseOutcome> settlement = new CompletableFuture<>();
    private boolean sealed;
    private boolean fatal;
    private int pending;

    synchronized Lease acquireLease() {
        if (sealed) return new Lease(false);
        pending++;
        return new Lease(true);
    }

    synchronized CompletionStage<TabCloseOutcome> settlement() {
        return settlement;
    }

    synchronized CompletionStage<TabCloseOutcome> hardSeal() {
        sealed = true;
        completeIfReady();
        return settlement;
    }

    boolean trackLegacyAbort(
            AsyncTabCloseGuard abortGuard,
            Consumer<? super Throwable> reporter) {
        Objects.requireNonNull(abortGuard, "abortGuard");
        Objects.requireNonNull(reporter, "reporter");
        Lease lease = acquireLease();
        if (!lease.acquired()) {
            report(reporter, new IllegalStateException(
                    "mandatory abort rejected after hard seal"));
            return false;
        }
        lease.abort(abortGuard);
        return true;
    }

    private static void report(Consumer<? super Throwable> reporter, Throwable failure) {
        try { reporter.accept(failure); } catch (Throwable ignored) { }
    }

    private synchronized void finishLease(boolean failed) {
        fatal |= failed;
        pending--;
        if (pending < 0) throw new IllegalStateException("mandatory abort lease underflow");
        completeIfReady();
    }

    private void completeIfReady() {
        if (sealed && pending == 0 && !settlement.isDone()) {
            settlement.complete(fatal ? TabCloseOutcome.FAILED_PARTIAL : TabCloseOutcome.COMPLETED);
        }
    }

    final class Lease {
        private final boolean acquired;
        private boolean terminal;

        private Lease(boolean acquired) { this.acquired = acquired; }

        boolean acquired() { return acquired; }

        void installed() {
            synchronized (MandatoryAbortTracker.this) {
                if (!acquired || terminal) return;
                terminal = true;
            }
            finishLease(false);
        }

        void abort(AsyncTabCloseGuard abortGuard) {
            Objects.requireNonNull(abortGuard, "abortGuard");
            CompletableFuture<CloseGuardOutcome> bound = new CompletableFuture<>();
            synchronized (MandatoryAbortTracker.this) {
                if (!acquired || terminal) return;
                terminal = true;
                // The placeholder is bound to this pending lease before cleanup can start.
                bound.whenComplete((outcome, failure) -> finishLease(
                        failure != null || outcome != CloseGuardOutcome.APPROVED));
            }
            CompletionStage<CloseGuardOutcome> started;
            try {
                started = Objects.requireNonNull(
                        abortGuard.requestClose(), "mandatory abort returned null stage");
            } catch (Throwable failure) {
                bound.completeExceptionally(failure);
                return;
            }
            try {
                started.whenComplete((outcome, failure) -> {
                    if (failure != null) bound.completeExceptionally(failure);
                    else bound.complete(outcome);
                });
            } catch (Throwable failure) {
                bound.completeExceptionally(failure);
            }
        }
    }
}
