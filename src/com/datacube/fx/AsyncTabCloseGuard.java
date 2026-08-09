package com.datacube.fx;

import java.util.concurrent.CompletionStage;

/**
 * Performs the blocking phase of a guarded tab close without blocking its caller.
 *
 * <p>The returned stage may approve closing only after cancellation, rollback and resource cleanup
 * have finished. Implementations must run blocking JDBC work on a JDK 25 virtual thread or the
 * application's existing task runner. A {@code false}, exceptional, cancelled, null or timed-out
 * result rejects that attempt and leaves the tab retryable.
 */
@FunctionalInterface
public interface AsyncTabCloseGuard {
    CompletionStage<Boolean> requestClose();
}
