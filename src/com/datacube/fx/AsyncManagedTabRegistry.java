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
    private CompletableFuture<TabCloseOutcome> closing;

    synchronized boolean register(K key, AsyncTabCloseCoordinator coordinator) {
        if (state != State.OPEN) return false;
        entries.put(key, coordinator);
        return true;
    }

    synchronized void unregister(K key) {
        entries.remove(key);
    }

    CompletionStage<TabCloseOutcome> requestClose(K key) {
        AsyncTabCloseCoordinator coordinator;
        synchronized (this) {
            coordinator = entries.get(key);
        }
        return coordinator == null
                ? CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED)
                : coordinator.requestClose();
    }

    CompletionStage<TabCloseOutcome> closeAll() {
        List<AsyncTabCloseCoordinator> snapshot;
        CompletableFuture<TabCloseOutcome> result;
        synchronized (this) {
            if (state == State.CLOSED) {
                return CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED);
            }
            if (state == State.CLOSING || state == State.FAILED_PARTIAL) return closing;
            state = State.CLOSING;
            snapshot = List.copyOf(entries.values());
            result = new CompletableFuture<>();
            closing = result;
        }

        List<CompletableFuture<TabCloseOutcome>> closes = new ArrayList<>(snapshot.size());
        for (AsyncTabCloseCoordinator coordinator : snapshot) {
            try {
                closes.add(coordinator.requestClose().handle(
                        (outcome, failure) -> failure == null && outcome != null
                                ? outcome
                                : TabCloseOutcome.FAILED_PARTIAL)
                        .toCompletableFuture());
            } catch (Throwable failure) {
                closes.add(CompletableFuture.completedFuture(TabCloseOutcome.FAILED_PARTIAL));
            }
        }
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> finishCloseAll(
                        result,
                        failure == null ? aggregate(closes) : TabCloseOutcome.FAILED_PARTIAL));
        return result;
    }

    private static TabCloseOutcome aggregate(List<CompletableFuture<TabCloseOutcome>> closes) {
        TabCloseOutcome aggregate = TabCloseOutcome.COMPLETED;
        for (CompletableFuture<TabCloseOutcome> close : closes) {
            TabCloseOutcome outcome = close.join();
            if (outcome == TabCloseOutcome.FAILED_PARTIAL) return outcome;
            if (outcome == TabCloseOutcome.TIMED_OUT_STILL_CLOSING) {
                aggregate = TabCloseOutcome.TIMED_OUT_STILL_CLOSING;
            } else if (outcome == TabCloseOutcome.CANCELLED
                    && aggregate == TabCloseOutcome.COMPLETED) {
                aggregate = TabCloseOutcome.CANCELLED;
            }
        }
        return aggregate;
    }

    private void finishCloseAll(
            CompletableFuture<TabCloseOutcome> expected,
            TabCloseOutcome outcome) {
        synchronized (this) {
            if (closing != expected) return;
            state = switch (outcome) {
                case COMPLETED -> State.CLOSED;
                case FAILED_PARTIAL -> State.FAILED_PARTIAL;
                case CANCELLED, TIMED_OUT_STILL_CLOSING -> State.OPEN;
            };
            if (state == State.OPEN) closing = null;
        }
        expected.complete(outcome);
    }

    private enum State { OPEN, CLOSING, CLOSED, FAILED_PARTIAL }
}
