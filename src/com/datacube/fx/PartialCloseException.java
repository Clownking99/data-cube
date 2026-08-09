package com.datacube.fx;

/** Signals that a best-effort close sequence ran every step but at least one step failed. */
class PartialCloseException extends RuntimeException {
    private final Runnable mandatoryAbortCleanup;

    PartialCloseException(Throwable firstFailure) {
        this(firstFailure, null);
    }

    PartialCloseException(Throwable firstFailure, Runnable mandatoryAbortCleanup) {
        super("one or more close steps failed", firstFailure);
        this.mandatoryAbortCleanup = mandatoryAbortCleanup;
    }

    boolean requiresMandatoryAbort() { return mandatoryAbortCleanup != null; }

    Runnable mandatoryAbortCleanup() { return mandatoryAbortCleanup; }
}
