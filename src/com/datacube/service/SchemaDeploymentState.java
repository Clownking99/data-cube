package com.datacube.service;

/** Terminal deployment and per-step states. */
public enum SchemaDeploymentState {
    SUCCEEDED,
    BLOCKED_DRIFT,
    BLOCKED_INCOMPLETE,
    FAILED_SQL,
    TIMED_OUT,
    CANCELLED,
    FAILED_PARTIAL,
    SKIPPED_DEPENDENCY,
    SKIPPED_FAIL_FAST
}
