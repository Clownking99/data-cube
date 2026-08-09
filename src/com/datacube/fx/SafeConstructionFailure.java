package com.datacube.fx;

/** Construction failed, but every resource acquired so far was rolled back safely. */
final class SafeConstructionFailure extends RuntimeException {
    private final Runnable mandatoryAbortCleanup;

    SafeConstructionFailure(Throwable constructionFailure) {
        this(constructionFailure, null);
    }

    SafeConstructionFailure(Throwable constructionFailure, Runnable mandatoryAbortCleanup) {
        super("managed content construction failed and was rolled back safely",
                constructionFailure);
        this.mandatoryAbortCleanup = mandatoryAbortCleanup;
    }

    boolean requiresMandatoryAbort() { return mandatoryAbortCleanup != null; }

    Runnable mandatoryAbortCleanup() { return mandatoryAbortCleanup; }
}
