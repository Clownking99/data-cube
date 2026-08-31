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
    private java.util.function.Supplier<CompletionStage<Void>> beforeGuards;
    private java.util.function.Function<TabCloseOutcome, CompletionStage<TabCloseOutcome>> gate;
    private java.util.function.BiConsumer<TabCloseOutcome, Runnable> terminal;
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
        return closeAll(mode, () -> CompletableFuture.completedFuture(null),
                CompletableFuture::completedFuture, (outcome, commit) -> commit.run());
    }

    CompletionStage<TabCloseOutcome> closeAll(ManagedCloseMode mode,
            java.util.function.Supplier<CompletionStage<Void>> beforeGuards,
            java.util.function.Function<TabCloseOutcome, CompletionStage<TabCloseOutcome>> gate,
            java.util.function.BiConsumer<TabCloseOutcome, Runnable> terminal) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(beforeGuards, "beforeGuards");
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(terminal, "terminal");
        CompletableFuture<TabCloseOutcome> result;
        List<AsyncTabCloseCoordinator> snapshot = null;
        synchronized (this) {
            // A terminal hook may have committed OPEN/CLOSED but has not returned yet. Keep its
            // attempt private until the hook succeeds, so a late hook exception can fail closed
            // without racing a newer close attempt or exposing premature success.
            if (closing != null && !closing.isDone()) return closing.copy();
            if (state == State.CLOSED) {
                return CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED);
            }
            if (state == State.CLOSING || state == State.FAILED_PARTIAL) return closing.copy();
            this.beforeGuards = beforeGuards;
            this.gate = gate;
            this.terminal = terminal;
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
        if (snapshot != null) freezeThenClose(snapshot, result, mode);
        return result.copy();
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
        if (snapshot != null) freezeThenClose(snapshot, result, mode);
    }

    private void freezeThenClose(List<AsyncTabCloseCoordinator> snapshot,
            CompletableFuture<TabCloseOutcome> result, ManagedCloseMode mode) {
        try {
            Objects.requireNonNull(beforeGuards.get(), "freeze returned null").whenComplete((unused, failure) -> {
                if (failure != null) finishCloseAll(result, TabCloseOutcome.FAILED_PARTIAL);
                else startCloseAll(snapshot, result, mode);
            });
        } catch (Throwable failure) { finishCloseAll(result, TabCloseOutcome.FAILED_PARTIAL); }
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

    static TabCloseOutcome worst(TabCloseOutcome left, TabCloseOutcome right) {
        if (left == null || right == null || left == TabCloseOutcome.FAILED_PARTIAL
                || right == TabCloseOutcome.FAILED_PARTIAL) return TabCloseOutcome.FAILED_PARTIAL;
        return left == TabCloseOutcome.CANCELLED || right == TabCloseOutcome.CANCELLED
                ? TabCloseOutcome.CANCELLED : TabCloseOutcome.COMPLETED;
    }

    private void finishCloseAll(CompletableFuture<TabCloseOutcome> expected, TabCloseOutcome outcome) {
        try {
            Objects.requireNonNull(gate.apply(outcome), "gate returned null").whenComplete((finalOutcome, failure) ->
                    terminate(expected, failure == null ? worst(outcome, finalOutcome) : TabCloseOutcome.FAILED_PARTIAL));
        } catch (Throwable failure) { terminate(expected, TabCloseOutcome.FAILED_PARTIAL); }
    }

    private void terminate(CompletableFuture<TabCloseOutcome> expected, TabCloseOutcome outcome) {
        java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            terminal.accept(outcome, () -> {
                if (committed.compareAndSet(false, true)) commitTransition(expected, outcome);
            });
            if (!committed.get()) throw new IllegalStateException("terminal did not commit");
            expected.complete(outcome);
        } catch (Throwable failure) {
            commitTransition(expected, TabCloseOutcome.FAILED_PARTIAL);
            expected.complete(TabCloseOutcome.FAILED_PARTIAL);
        }
    }

    private void commitTransition(CompletableFuture<TabCloseOutcome> expected, TabCloseOutcome outcome) {
        synchronized (this) {
            if (closing != expected) return;
            state = switch (outcome) {
                case COMPLETED -> State.CLOSED;
                case FAILED_PARTIAL -> State.FAILED_PARTIAL;
                case CANCELLED -> State.OPEN;
            };
            if (state == State.OPEN) {
                closeStarted = false;
                closeMode = null;
            }
        }
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
