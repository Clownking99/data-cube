package com.datacube.fx;

/** Observable terminal status of a managed tab close request. */
public enum TabCloseOutcome {
    COMPLETED,
    CANCELLED,
    FAILED_PARTIAL
}
