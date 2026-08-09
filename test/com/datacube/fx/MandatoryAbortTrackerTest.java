package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MandatoryAbortTrackerTest {

    @Test
    void settlementWaitsForTrackedAbortAndMakesFatalFailureObservable() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        CompletableFuture<CloseGuardOutcome> abort = new CompletableFuture<>();
        tracker.track(abort);

        var settlement = tracker.settlement();
        assertFalse(settlement.toCompletableFuture().isDone());
        abort.complete(CloseGuardOutcome.FAILED_PARTIAL);

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                settlement.toCompletableFuture().join());
    }

    @Test
    void successfulMandatoryAbortSettlesCompleted() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        tracker.track(CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED));

        assertEquals(TabCloseOutcome.COMPLETED,
                tracker.settlement().toCompletableFuture().join());
    }

    @Test
    void completedGenerationReopensAndLaterFatalAbortIsNotLost() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        assertEquals(TabCloseOutcome.COMPLETED,
                tracker.settlement().toCompletableFuture().join());

        tracker.track(CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL));

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                tracker.settlement().toCompletableFuture().join());
    }

    @Test
    void abortTrackedWhileSettlementWaitsJoinsSameGeneration() {
        MandatoryAbortTracker tracker = new MandatoryAbortTracker();
        CompletableFuture<CloseGuardOutcome> first = new CompletableFuture<>();
        CompletableFuture<CloseGuardOutcome> racing = new CompletableFuture<>();
        tracker.track(first);
        var settlement = tracker.settlement();

        tracker.track(racing);
        first.complete(CloseGuardOutcome.APPROVED);
        assertFalse(settlement.toCompletableFuture().isDone());
        racing.complete(CloseGuardOutcome.FAILED_PARTIAL);

        assertEquals(TabCloseOutcome.FAILED_PARTIAL,
                settlement.toCompletableFuture().join());
    }
}
