package com.datacube.migration;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Operation-wide cancellation state with race-safe ownership of blocking resources. */
public final class MigrationCancellation implements AutoCloseable {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<AutoCloseable> resources;
    private final Object cleanupLock = new Object();
    private CompletableFuture<Void> cleanupCompletion = CompletableFuture.completedFuture(null);

    public MigrationCancellation() {
        this(ConcurrentHashMap.newKeySet());
    }

    MigrationCancellation(Set<AutoCloseable> resources) {
        this.resources = resources;
    }

    public <T extends AutoCloseable> T register(T resource) {
        if (resource == null) throw new NullPointerException("resource");
        if (cancelled.get()) {
            closeQuietly(resource);
            throw new CancellationException("Migration operation is cancelled");
        }
        resources.add(resource);
        if (cancelled.get()) {
            if (resources.remove(resource)) closeQuietly(resource);
            throw new CancellationException("Migration operation is cancelled");
        }
        return resource;
    }

    public void release(AutoCloseable resource) {
        if (resource != null && resources.remove(resource)) closeQuietly(resource);
    }

    public void checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Migration operation is cancelled");
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void reset() {
        if (!resources.isEmpty()) {
            throw new IllegalStateException("Cannot reset cancellation while resources are active");
        }
        cancelled.set(false);
    }

    public void cancel() {
        synchronized (cleanupLock) {
            cancelled.set(true);
            for (AutoCloseable resource : resources) {
                if (resources.remove(resource)) closeQuietly(resource);
            }
        }
    }

    public CompletableFuture<Void> cancelAsync(Executor cleanupExecutor) {
        if (cleanupExecutor == null) throw new NullPointerException("cleanupExecutor");
        synchronized (cleanupLock) {
            cancelled.set(true);
            List<CompletableFuture<Void>> scheduled = new ArrayList<>();
            for (AutoCloseable resource : resources) {
                if (!resources.remove(resource)) continue;
                CompletableFuture<Void> closed = new CompletableFuture<>();
                scheduled.add(closed);
                try {
                    cleanupExecutor.execute(() -> {
                        closeQuietly(resource);
                        closed.complete(null);
                    });
                } catch (RejectedExecutionException rejected) {
                    closeQuietly(resource);
                    closed.complete(null);
                }
            }
            CompletableFuture<Void> currentBatch = CompletableFuture.allOf(
                    scheduled.toArray(CompletableFuture[]::new));
            cleanupCompletion = CompletableFuture.allOf(cleanupCompletion, currentBatch);
            return cleanupCompletion;
        }
    }

    public void awaitCleanup() throws InterruptedException {
        CompletableFuture<Void> completion;
        synchronized (cleanupLock) {
            completion = cleanupCompletion;
        }
        try {
            completion.get();
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("Migration cleanup failed", impossible.getCause());
        }
    }

    @Override
    public void close() {
        cancel();
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
