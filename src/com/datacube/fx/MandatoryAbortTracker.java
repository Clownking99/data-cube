package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Generation-aware accounting for mandatory abort cleanup. */
final class MandatoryAbortTracker {
    private int pending;
    private boolean settlementRequested;
    private boolean fatal;
    private CompletableFuture<TabCloseOutcome> closing;

    boolean track(CompletionStage<CloseGuardOutcome> abort) {
        Objects.requireNonNull(abort, "abort");
        synchronized (this) {
            reopenCompletedGeneration();
            pending++;
        }
        try {
            abort.whenComplete((outcome, failure) -> finishOne(
                    failure != null || outcome != CloseGuardOutcome.APPROVED));
        } catch (Throwable failure) {
            finishOne(true);
        }
        return true;
    }

    synchronized CompletionStage<TabCloseOutcome> settlement() {
        reopenCompletedGeneration();
        if (closing == null) closing = new CompletableFuture<>();
        settlementRequested = true;
        completeIfReady();
        return closing;
    }

    private void finishOne(boolean failed) {
        synchronized (this) {
            fatal |= failed;
            pending--;
            if (pending < 0) throw new IllegalStateException("mandatory abort underflow");
            completeIfReady();
        }
    }

    private void completeIfReady() {
        if (settlementRequested && pending == 0 && !closing.isDone()) {
            closing.complete(fatal ? TabCloseOutcome.FAILED_PARTIAL : TabCloseOutcome.COMPLETED);
        }
    }

    private void reopenCompletedGeneration() {
        if (closing != null && closing.isDone()) {
            closing = null;
            settlementRequested = false;
            fatal = false;
        }
    }
}
