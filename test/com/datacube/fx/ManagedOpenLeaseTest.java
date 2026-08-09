package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedOpenLeaseTest {

    @Test
    void secondLeaseFailureReleasesAlreadyAcquiredRegistryReservation() {
        MandatoryAbortTracker sealed = new MandatoryAbortTracker();
        sealed.seal();
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
        assertTrue(tracker.seal().toCompletableFuture().isDone());
    }
}
