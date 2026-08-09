package com.datacube.fx;

/** Signals that a best-effort close sequence ran every step but at least one step failed. */
final class PartialCloseException extends RuntimeException {
    PartialCloseException(Throwable firstFailure) {
        super("one or more close steps failed", firstFailure);
    }
}
