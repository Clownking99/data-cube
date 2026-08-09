package com.datacube.fx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** One close generation: timeout is progress; {@link #settlement()} has exactly one terminal result. */
public final class CloseAttempt {
    private final long generation;
    private final AtomicReference<CloseAttemptStatus> status =
            new AtomicReference<>(CloseAttemptStatus.CLOSING);
    private final CompletableFuture<TabCloseOutcome> settlement = new CompletableFuture<>();

    CloseAttempt(long generation) {
        this.generation = generation;
    }

    public CloseAttemptStatus status() {
        return status.get();
    }

    public CompletionStage<TabCloseOutcome> settlement() {
        return settlement;
    }

    long generation() {
        return generation;
    }

    void markStillClosing() {
        status.compareAndSet(CloseAttemptStatus.CLOSING, CloseAttemptStatus.STILL_CLOSING);
    }

    boolean settle(TabCloseOutcome outcome) {
        CloseAttemptStatus previous = status.getAndSet(CloseAttemptStatus.SETTLED);
        if (previous == CloseAttemptStatus.SETTLED) return false;
        settlement.complete(outcome);
        return true;
    }
}
