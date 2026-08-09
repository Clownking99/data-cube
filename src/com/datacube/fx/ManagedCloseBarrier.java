package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Seals registry first, then hard-seals abort acceptance and joins both settlements. */
final class ManagedCloseBarrier {
    private ManagedCloseBarrier() {}

    static CompletionStage<TabCloseOutcome> close(
            Supplier<CompletionStage<TabCloseOutcome>> sealRegistry,
            MandatoryAbortTracker aborts) {
        CompletionStage<TabCloseOutcome> tabs;
        try {
            tabs = Objects.requireNonNull(sealRegistry.get(), "registry close returned null");
        } catch (Throwable failure) {
            aborts.hardSeal();
            return CompletableFuture.failedFuture(failure);
        }
        CompletionStage<TabCloseOutcome> abortSettlement = aborts.hardSeal();
        return tabs.thenCombine(abortSettlement, ManagedCloseBarrier::aggregate);
    }

    private static TabCloseOutcome aggregate(TabCloseOutcome left, TabCloseOutcome right) {
        if (left == TabCloseOutcome.FAILED_PARTIAL || right == TabCloseOutcome.FAILED_PARTIAL) {
            return TabCloseOutcome.FAILED_PARTIAL;
        }
        if (left == TabCloseOutcome.CANCELLED || right == TabCloseOutcome.CANCELLED) {
            return TabCloseOutcome.CANCELLED;
        }
        return TabCloseOutcome.COMPLETED;
    }
}
