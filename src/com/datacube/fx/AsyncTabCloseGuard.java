package com.datacube.fx;

import java.util.concurrent.CompletionStage;

/**
 * Performs the blocking phase of a guarded tab close without blocking its caller.
 *
 * <p>The returned stage may approve closing only after cancellation, rollback and resource cleanup
 * have finished. Implementations must run blocking JDBC work on a JDK 25 virtual thread or the
 * application's existing task runner. {@link CloseGuardOutcome#REJECTED} and failures known to
 * precede irreversible cleanup are retryable. Implementations must return
 * {@link CloseGuardOutcome#FAILED_PARTIAL} after any partial destructive cleanup.
 */
@FunctionalInterface
public interface AsyncTabCloseGuard {
    CompletionStage<CloseGuardOutcome> requestClose();
}
