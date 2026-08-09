package com.datacube.fx;

import java.util.concurrent.CompletableFuture;

/** Composite ownership acquired before constructing any managed content. */
final class ManagedOpenLease implements AutoCloseable {
    private final boolean acquired;
    private final AutoCloseable registryReservation;
    private final MandatoryAbortTracker.Lease abortLease;
    private boolean terminal;

    private ManagedOpenLease(
            boolean acquired,
            AutoCloseable registryReservation,
            MandatoryAbortTracker.Lease abortLease) {
        this.acquired = acquired;
        this.registryReservation = registryReservation;
        this.abortLease = abortLease;
    }

    static ManagedOpenLease acquire(
            boolean registryAcquired,
            AutoCloseable registryReservation,
            MandatoryAbortTracker tracker) {
        if (!registryAcquired) return new ManagedOpenLease(false, registryReservation, null);
        MandatoryAbortTracker.Lease abortLease = tracker.acquireLease();
        if (!abortLease.acquired()) {
            closeQuietly(registryReservation);
            return new ManagedOpenLease(false, () -> {}, abortLease);
        }
        return new ManagedOpenLease(true, registryReservation, abortLease);
    }

    boolean acquired() { return acquired; }

    synchronized void installed() {
        if (!acquired || terminal) return;
        terminal = true;
        abortLease.installed();
        closeQuietly(registryReservation);
    }

    synchronized void abort(AsyncTabCloseGuard guard) {
        if (!acquired || terminal) return;
        terminal = true;
        abortLease.abort(guard);
        closeQuietly(registryReservation);
    }

    @Override
    public synchronized void close() {
        if (!acquired || terminal) return;
        terminal = true;
        abortLease.abort(() -> CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL));
        closeQuietly(registryReservation);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { }
    }
}
