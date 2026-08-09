package com.datacube.fx;

/** Application shutdown status; only {@link #COMPLETED} permits closing the window. */
public enum ShutdownOutcome {
    COMPLETED,
    CANCELLED,
    FAILED_PARTIAL
}
