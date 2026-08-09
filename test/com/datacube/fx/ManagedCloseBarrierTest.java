package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCloseBarrierTest {

    @Test
    void registrySealReentrantLegacyAbortJoinsBeforeHardSeal() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        CompletableFuture<CloseGuardOutcome> abort = new CompletableFuture<>();

        var close = ManagedCloseBarrier.close(() -> {
            assertTrue(tracker.trackLegacyAbort(() -> abort, ignored -> {}));
            return CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED);
        }, tracker);

        assertFalse(close.toCompletableFuture().isDone());
        abort.complete(CloseGuardOutcome.FAILED_PARTIAL);
        assertEquals(TabCloseOutcome.FAILED_PARTIAL, close.toCompletableFuture().join());
    }

    @Test
    void registrySealFailureWaitsForReentrantAbortAndFatalWins() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        CompletableFuture<CloseGuardOutcome> abort = new CompletableFuture<>();

        var close = ManagedCloseBarrier.close(() -> {
            assertTrue(tracker.trackLegacyAbort(() -> abort, ignored -> {}));
            throw new IllegalStateException("registry seal");
        }, tracker);

        assertFalse(close.toCompletableFuture().isDone());
        abort.complete(CloseGuardOutcome.FAILED_PARTIAL);
        assertEquals(TabCloseOutcome.FAILED_PARTIAL, close.toCompletableFuture().join());
    }
}
