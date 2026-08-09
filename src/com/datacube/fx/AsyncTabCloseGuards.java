package com.datacube.fx;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Factories for common asynchronous close guards. */
final class AsyncTabCloseGuards {

    private AsyncTabCloseGuards() {}

    static AsyncTabCloseGuard blocking(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        AtomicReference<CompletableFuture<Boolean>> once = new AtomicReference<>();
        return () -> {
            CompletableFuture<Boolean> existing = once.get();
            if (existing != null) return existing;
            CompletableFuture<Boolean> created = new CompletableFuture<>();
            if (!once.compareAndSet(null, created)) return once.get();
            try {
                Thread.startVirtualThread(() -> {
                    try {
                        cleanup.run();
                        created.complete(true);
                    } catch (Throwable failure) {
                        created.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                created.completeExceptionally(failure);
            }
            return created;
        };
    }
}
