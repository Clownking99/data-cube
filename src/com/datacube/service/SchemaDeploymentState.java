package com.datacube.service;

/** Terminal deployment and per-step states. */
public enum SchemaDeploymentState {
    SUCCEEDED,
    BLOCKED_DRIFT,
    BLOCKED_INCOMPLETE,
    FAILED_SQL,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN_AFTER_CANCEL,
    FAILED_PARTIAL,
    SKIPPED_DEPENDENCY,
    SKIPPED_FAIL_FAST
}
