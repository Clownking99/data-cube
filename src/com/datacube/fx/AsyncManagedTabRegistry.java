package com.datacube.fx;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Thread-safe registry routing every guarded removal and close-all request through its coordinator. */
final class AsyncManagedTabRegistry<K> {

    private final Map<K, AsyncTabCloseCoordinator> entries = new IdentityHashMap<>();

    synchronized void register(K key, AsyncTabCloseCoordinator coordinator) {
        entries.put(key, coordinator);
    }

    synchronized void unregister(K key) {
        entries.remove(key);
    }

    CompletionStage<Boolean> requestClose(K key) {
        AsyncTabCloseCoordinator coordinator;
        synchronized (this) {
            coordinator = entries.get(key);
        }
        return coordinator == null
                ? CompletableFuture.completedFuture(true)
                : coordinator.requestClose();
    }

    CompletionStage<Void> closeAll() {
        List<AsyncTabCloseCoordinator> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(entries.values());
        }
        CompletableFuture<?>[] closes = snapshot.stream()
                .map(AsyncTabCloseCoordinator::requestClose)
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(closes);
    }
}
