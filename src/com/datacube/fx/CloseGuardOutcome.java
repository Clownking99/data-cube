package com.datacube.fx;

/** Terminal result of one background close-guard attempt. */
public enum CloseGuardOutcome {
    /** Blocking cleanup reached a safe terminal state. */
    APPROVED,
    /** No irreversible cleanup occurred; the user may retry. */
    REJECTED,
    /** Cleanup partially changed resources and must not be presented as retryable. */
    FAILED_PARTIAL
}
