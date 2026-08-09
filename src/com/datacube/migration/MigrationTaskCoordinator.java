package com.datacube.migration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Waits for bounded migration fan-out and owns deterministic child-task shutdown. */
final class MigrationTaskCoordinator {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(3);

    private MigrationTaskCoordinator() {
    }

    static boolean awaitAll(List<? extends Future<?>> futures, ExecutorService executor,
                            MigrationCancellation cancellation, Duration perTaskTimeout) {
        boolean completed = true;
        boolean interrupted = false;
        try {
            for (Future<?> future : futures) {
                if (cancellation.isCancelled()) {
                    completed = false;
                    break;
                }
                try {
                    future.get(perTaskTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    interrupted = true;
                    cancellation.cancel();
                    completed = false;
                    break;
                } catch (CancellationException | ExecutionException | TimeoutException error) {
                    cancellation.cancel();
                    completed = false;
                    break;
                }
            }
        } finally {
            if (!completed || cancellation.isCancelled()) {
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    futures.forEach(future -> future.cancel(true));
                    executor.shutdownNow();
                    executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException error) {
                interrupted = true;
                cancellation.cancel();
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        return completed && !cancellation.isCancelled();
    }
}
