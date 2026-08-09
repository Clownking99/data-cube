package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagedOpenLeaseTest {

    @Test
    void secondLeaseFailureReleasesAlreadyAcquiredRegistryReservation() {
        MandatoryAbortTracker sealed = new MandatoryAbortTracker();
        sealed.hardSeal();
        AtomicInteger registryReleases = new AtomicInteger();

        ManagedOpenLease lease = ManagedOpenLease.acquire(
                true, registryReleases::incrementAndGet, sealed);

        assertFalse(lease.acquired());
        assertTrue(registryReleases.get() == 1);
    }

    @Test
    void successfulInstallReleasesBothOwnershipLeasesExactlyOnce() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        AtomicInteger registryReleases = new AtomicInteger();
        ManagedOpenLease lease = ManagedOpenLease.acquire(
                true, registryReleases::incrementAndGet, tracker);

        assertTrue(lease.acquired());
        lease.installed();
        lease.close();

        assertTrue(registryReleases.get() == 1);
        assertTrue(tracker.hardSeal().toCompletableFuture().isDone());
    }

    @Test
    void safeConstructionFailureReleasesLeaseWithoutStartingAbort() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        AtomicInteger registryReleases = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        ManagedOpenLease lease = ManagedOpenLease.acquire(
                true, registryReleases::incrementAndGet, tracker);

        lease.failed(new SafeConstructionFailure(new IllegalStateException("build")), () -> {
            aborts.incrementAndGet();
            return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
        });

        assertEquals(0, aborts.get());
        assertEquals(1, registryReleases.get());
        assertEquals(TabCloseOutcome.COMPLETED, tracker.hardSeal().toCompletableFuture().join());
    }

    @Test
    void unsafeFactoryFailureUsesMandatoryAbortAndPropagatesFatal() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        ManagedOpenLease lease = ManagedOpenLease.acquire(true, () -> {}, tracker);

        lease.failed(new IllegalStateException("unknown ownership"),
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL));

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                tracker.hardSeal().toCompletableFuture().join());
    }

    @Test
    void deferredSafeConstructionCleanupRunsThroughTrackedAbort() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        AtomicInteger blockingCleanup = new AtomicInteger();
        ManagedOpenLease lease = ManagedOpenLease.acquire(true, () -> {}, tracker);
        SafeConstructionFailure safe = new SafeConstructionFailure(
                new IllegalStateException("build"), blockingCleanup::incrementAndGet);

        lease.failed(safe, () -> CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL));

        assertEquals(TabCloseOutcome.COMPLETED,
                tracker.hardSeal().toCompletableFuture().join());
        assertEquals(1, blockingCleanup.get());
    }

    @Test
    void deferredConstructionCleanupFailureIsReportedAndFatal() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        List<Throwable> failures = new ArrayList<>();
        IllegalStateException socketFailure = new IllegalStateException("socket close");
        ManagedOpenLease lease = ManagedOpenLease.acquire(true, () -> {}, tracker);
        SafeConstructionFailure safe = new SafeConstructionFailure(
                new IllegalStateException("build"), () -> { throw socketFailure; });

        lease.failed(safe,
                () -> CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL),
                failures::add);

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                tracker.hardSeal().toCompletableFuture().join());
        assertEquals(List.of(socketFailure), failures);
    }
}
