package com.datacube.fx;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Thread-safe registry; close-all seals ownership and asynchronously waits for open reservations. */
final class AsyncManagedTabRegistry<K> {
    private final Map<K, AsyncTabCloseCoordinator> entries = new IdentityHashMap<>();
    private State state = State.OPEN;
    private CompletableFuture<TabCloseOutcome> closing;
    private ManagedCloseMode closeMode;
    private int reservations;
    private boolean closeStarted;

    synchronized boolean register(K key, AsyncTabCloseCoordinator coordinator) {
        if (state != State.OPEN) return false;
        entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(coordinator, "coordinator"));
        return true;
    }

    synchronized Reservation reserve() {
        if (state != State.OPEN) return new Reservation(false);
        reservations++;
        return new Reservation(true);
    }

    synchronized void unregister(K key) {
        entries.remove(key);
    }

    synchronized boolean isRemovalAuthorized(K key) {
        AsyncTabCloseCoordinator coordinator = entries.get(key);
        return coordinator != null && coordinator.isRemovalAuthorized();
    }

    synchronized boolean isManaged(K key) {
        return entries.containsKey(key);
    }

    CompletionStage<TabCloseOutcome> requestClose(K key) {
        AsyncTabCloseCoordinator coordinator;
        synchronized (this) { coordinator = entries.get(key); }
        return coordinator == null
                ? CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED)
                : coordinator.requestClose().settlement();
    }

    void requestExternalClose(List<? extends K> keys, Runnable restoreBatch) {
        List<AsyncTabCloseCoordinator> coordinators = new ArrayList<>();
        synchronized (this) {
            for (K key : keys) {
                AsyncTabCloseCoordinator coordinator = entries.get(key);
                if (coordinator != null && !coordinator.isRemovalAuthorized()) {
                    coordinators.add(coordinator);
                }
            }
        }
        if (coordinators.isEmpty()) return;
        coordinators.getFirst().requestClose(restoreBatch);
        for (int index = 1; index < coordinators.size(); index++) {
            coordinators.get(index).requestClose();
        }
    }

    CompletionStage<TabCloseOutcome> closeAll() {
        return closeAll(ManagedCloseMode.INTERACTIVE);
    }

    CompletionStage<TabCloseOutcome> closeAll(ManagedCloseMode mode) {
        Objects.requireNonNull(mode, "mode");
        CompletableFuture<TabCloseOutcome> result;
        List<AsyncTabCloseCoordinator> snapshot = null;
        synchronized (this) {
            if (state == State.CLOSED) {
                return CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED);
            }
            if (state == State.CLOSING || state == State.FAILED_PARTIAL) return closing;
            state = State.CLOSING;
            closeStarted = false;
            closeMode = mode;
            result = new CompletableFuture<>();
            closing = result;
            if (reservations == 0) {
                closeStarted = true;
                snapshot = List.copyOf(entries.values());
            }
        }
        if (snapshot != null) startCloseAll(snapshot, result, mode);
        return result;
    }

    private void reservationReleased() {
        List<AsyncTabCloseCoordinator> snapshot = null;
        CompletableFuture<TabCloseOutcome> result = null;
        ManagedCloseMode mode = null;
        synchronized (this) {
            reservations--;
            if (reservations < 0) throw new IllegalStateException("reservation underflow");
            if (state == State.CLOSING && reservations == 0 && !closeStarted) {
                closeStarted = true;
                snapshot = List.copyOf(entries.values());
                result = closing;
                mode = closeMode;
            }
        }
        if (snapshot != null) startCloseAll(snapshot, result, mode);
    }

    private void startCloseAll(
            List<AsyncTabCloseCoordinator> snapshot,
            CompletableFuture<TabCloseOutcome> result,
            ManagedCloseMode mode) {
        List<CompletableFuture<TabCloseOutcome>> closes = new ArrayList<>(snapshot.size());
        for (AsyncTabCloseCoordinator coordinator : snapshot) {
            try {
                CloseAttempt attempt = mode == ManagedCloseMode.MANDATORY
                        ? coordinator.requestMandatoryClose() : coordinator.requestClose();
                closes.add(attempt.settlement().handle(
                        (outcome, failure) -> failure == null && outcome != null
                                ? outcome : TabCloseOutcome.FAILED_PARTIAL).toCompletableFuture());
            } catch (Throwable failure) {
                closes.add(CompletableFuture.completedFuture(TabCloseOutcome.FAILED_PARTIAL));
            }
        }
        CompletableFuture.allOf(closes.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> finishCloseAll(result,
                        failure == null ? aggregate(closes) : TabCloseOutcome.FAILED_PARTIAL));
    }

    private static TabCloseOutcome aggregate(List<CompletableFuture<TabCloseOutcome>> closes) {
        TabCloseOutcome aggregate = TabCloseOutcome.COMPLETED;
        for (CompletableFuture<TabCloseOutcome> close : closes) {
            TabCloseOutcome outcome = close.join();
            if (outcome == TabCloseOutcome.FAILED_PARTIAL) return outcome;
            if (outcome == TabCloseOutcome.CANCELLED) aggregate = TabCloseOutcome.CANCELLED;
        }
        return aggregate;
    }

    private void finishCloseAll(CompletableFuture<TabCloseOutcome> expected, TabCloseOutcome outcome) {
        synchronized (this) {
            if (closing != expected) return;
            state = switch (outcome) {
                case COMPLETED -> State.CLOSED;
                case FAILED_PARTIAL -> State.FAILED_PARTIAL;
                case CANCELLED -> State.OPEN;
            };
            if (state == State.OPEN) {
                closing = null;
                closeStarted = false;
                closeMode = null;
            }
        }
        expected.complete(outcome);
    }

    final class Reservation implements AutoCloseable {
        private final boolean acquired;
        private boolean released;
        private boolean registered;

        private Reservation(boolean acquired) { this.acquired = acquired; }

        boolean acquired() { return acquired; }

        boolean register(K key, AsyncTabCloseCoordinator coordinator) {
            synchronized (AsyncManagedTabRegistry.this) {
                if (!acquired || released || registered) return false;
                entries.put(Objects.requireNonNull(key, "key"),
                        Objects.requireNonNull(coordinator, "coordinator"));
                registered = true;
            }
            return true;
        }

        @Override
        public void close() {
            synchronized (AsyncManagedTabRegistry.this) {
                if (!acquired || released) return;
                released = true;
            }
            reservationReleased();
        }
    }

    private enum State { OPEN, CLOSING, CLOSED, FAILED_PARTIAL }
}
