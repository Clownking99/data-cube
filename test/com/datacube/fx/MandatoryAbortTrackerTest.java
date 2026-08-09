package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MandatoryAbortTrackerTest {

    @Test
    void sealedSettlementWaitsForEveryExistingLeaseAndNeverReopens() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        MandatoryAbortTracker.Lease installed = tracker.acquireLease();
        MandatoryAbortTracker.Lease abandoned = tracker.acquireLease();

        var settlement = tracker.seal();
        assertSame(settlement, tracker.seal());
        assertFalse(settlement.toCompletableFuture().isDone());
        assertFalse(tracker.acquireLease().acquired());
        installed.installed();
        assertFalse(settlement.toCompletableFuture().isDone());
        abandoned.installed();

        assertEquals(TabCloseOutcome.COMPLETED, settlement.toCompletableFuture().join());
        assertFalse(tracker.acquireLease().acquired());
        assertSame(settlement, tracker.seal());
    }

    @Test
    void abortIsBoundToLeaseBeforeGuardStartsAndLateFatalOwnsSettlement() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        MandatoryAbortTracker.Lease lease = tracker.acquireLease();
        var settlement = tracker.seal();
        CompletableFuture<CloseGuardOutcome> abort = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();

        lease.abort(() -> {
            started.set(true);
            assertFalse(settlement.toCompletableFuture().isDone());
            return abort;
        });

        assertTrue(started.get());
        assertFalse(settlement.toCompletableFuture().isDone());
        abort.complete(CloseGuardOutcome.FAILED_PARTIAL);
        assertEquals(TabCloseOutcome.FAILED_PARTIAL, settlement.toCompletableFuture().join());
    }
}
