package com.datacube.fx;

import java.util.Objects;

/** Executes every close step and reports all failures as one non-retryable partial failure. */
final class BestEffortCloseSequence {

    private BestEffortCloseSequence() {}

    static void run(Runnable... steps) {
        Throwable first = null;
        PartialCloseException aggregate = null;
        for (Runnable step : steps) {
            try {
                Objects.requireNonNull(step, "close step").run();
            } catch (Throwable failure) {
                if (first == null) {
                    first = failure;
                    aggregate = new PartialCloseException(failure);
                } else {
                    aggregate.addSuppressed(failure);
                }
            }
        }
        if (aggregate != null) throw aggregate;
    }
}
