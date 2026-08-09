package com.datacube.fx;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Thread-safe registry routing every guarded removal and close-all request through its coordinator. */
final class AsyncManagedTabRegistry<K> {

    private final Map<K, AsyncTabCloseCoordinator> entries = new IdentityHashMap<>();
    private State state = State.OPEN;
    private CompletableFuture<Boolean> closing;

    synchronized void register(K key, AsyncTabCloseCoordinator coordinator) {
        if (state != State.OPEN) throw new IllegalStateException("tab registry is closing");
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

    CompletionStage<Boolean> closeAll() {
        List<AsyncTabCloseCoordinator> snapshot;
        CompletableFuture<Boolean> result;
        synchronized (this) {
            if (state == State.CLOSED) return CompletableFuture.completedFuture(true);
            if (state == State.CLOSING) return closing;
            state = State.CLOSING;
            snapshot = List.copyOf(entries.values());
            result = new CompletableFuture<>();
            closing = result;
        }

        List<CompletableFuture<Boolean>> closes = new ArrayList<>(snapshot.size());
        for (AsyncTabCloseCoordinator coordinator : snapshot) {
            try {
                closes.add(coordinator.requestClose().handle(
                        (approved, failure) -> failure == null && Boolean.TRUE.equals(approved))
                        .toCompletableFuture());
            } catch (Throwable failure) {
                closes.add(CompletableFuture.completedFuture(false));
            }
        }
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> {
                    boolean approved = failure == null
                            && closes.stream().allMatch(close -> Boolean.TRUE.equals(close.join()));
                    finishCloseAll(result, approved);
                });
        return result;
    }

    private void finishCloseAll(CompletableFuture<Boolean> expected, boolean approved) {
        synchronized (this) {
            if (closing != expected) return;
            state = approved ? State.CLOSED : State.OPEN;
            if (!approved) closing = null;
        }
        expected.complete(approved);
    }

    private enum State { OPEN, CLOSING, CLOSED }
}
