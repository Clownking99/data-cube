package com.datacube.fx;

/** Observable terminal status of a managed tab close request. */
public enum TabCloseOutcome {
    COMPLETED,
    CANCELLED,
    TIMED_OUT_STILL_CLOSING,
    FAILED_PARTIAL
}
