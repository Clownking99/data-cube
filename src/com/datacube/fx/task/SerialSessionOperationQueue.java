package com.datacube.fx.task;

import javafx.application.Platform;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Per-editor FIFO for blocking JDBC session operations. Cancellation intentionally bypasses it. */
public final class SerialSessionOperationQueue implements AutoCloseable {
    public enum OperationKind {
        EXECUTE(true),
        EXPLAIN(true),
        SET_MODE(false),
        COMMIT(false),
        ROLLBACK(false);

        private final boolean cancellable;

        OperationKind(boolean cancellable) {
            this.cancellable = cancellable;
        }

        public boolean cancellable() {
            return cancellable;
        }
    }

    private final FxTaskRunner runner;
    private final Consumer<Runnable> uiDispatcher;
    private final Deque<QueuedOperation<?>> queued = new ArrayDeque<>();

    private boolean accepting = true;
    private boolean callbacksEnabled = true;
    private QueuedOperation<?> current;
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);

    public SerialSessionOperationQueue(FxTaskRunner runner) {
        this(runner, Platform::runLater);
    }

    SerialSessionOperationQueue(FxTaskRunner runner, Consumer<Runnable> uiDispatcher) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    }

    public synchronized <T> Future<T> submit(
            OperationKind kind,
            Callable<T> operation,
            Consumer<? super T> success,
            Consumer<? super Throwable> failure) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        if (!accepting) throw new RejectedExecutionException("session operation queue is closing");
        if (current == null && queued.isEmpty()) idle = new CompletableFuture<>();
        QueuedOperation<T> submitted = new QueuedOperation<>(kind, operation, success, failure);
        queued.addLast(submitted);
        scheduleNext();
        return submitted.completion;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(accepting, current == null ? null : current.kind, queued.size());
    }

    public synchronized CompletionStage<Void> idle() {
        return idle;
    }

    public synchronized CompletionStage<Void> stopAcceptingAndCancelQueued() {
        accepting = false;
        while (!queued.isEmpty()) queued.removeFirst().completion.cancel(false);
        if (current == null) idle.complete(null);
        return idle;
    }

    public synchronized void reopen() {
        accepting = true;
        callbacksEnabled = true;
        scheduleNext();
    }

    /** Suppresses terminal callbacks only after destructive cleanup has been accepted. */
    public synchronized void suppressCallbacks() {
        callbacksEnabled = false;
    }

    @Override
    public void close() {
        stopAcceptingAndCancelQueued();
        suppressCallbacks();
    }

    private void scheduleNext() {
        if (current != null) return;
        QueuedOperation<?> next;
        do {
            next = queued.pollFirst();
        } while (next != null && next.completion.isCancelled());
        if (next == null) {
            idle.complete(null);
            return;
        }
        QueuedOperation<?> selected = next;
        current = selected;
        try {
            runner.submit(selected::run);
        } catch (RuntimeException rejected) {
            current = null;
            selected.completion.completeExceptionally(rejected);
            dispatch(() -> selected.failure.accept(rejected));
            scheduleNext();
        }
    }

    private synchronized void finished(QueuedOperation<?> operation) {
        if (current == operation) current = null;
        scheduleNext();
    }

    private synchronized boolean finishBeforeCallbackIfStopped(QueuedOperation<?> operation) {
        if (accepting || current != operation) return false;
        current = null;
        scheduleNext();
        return true;
    }

    private void dispatch(Runnable callback) {
        synchronized (this) {
            if (!callbacksEnabled) return;
        }
        uiDispatcher.accept(() -> {
            synchronized (SerialSessionOperationQueue.this) {
                if (!callbacksEnabled) return;
            }
            callback.run();
        });
    }

    public record Snapshot(boolean accepting, OperationKind currentKind, int queued) {
        public boolean running() {
            return currentKind != null;
        }

        public boolean currentCancellable() {
            return currentKind != null && currentKind.cancellable();
        }

        public boolean pending() {
            return running() || queued > 0;
        }
    }

    private final class QueuedOperation<T> {
        private final OperationKind kind;
        private final Callable<T> operation;
        private final Consumer<? super T> success;
        private final Consumer<? super Throwable> failure;
        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private QueuedOperation(
                OperationKind kind,
                Callable<T> operation,
                Consumer<? super T> success,
                Consumer<? super Throwable> failure) {
            this.kind = kind;
            this.operation = operation;
            this.success = success;
            this.failure = failure;
        }

        private void run() {
            Runnable terminalCallback = null;
            boolean finishedBeforeCallback = false;
            try {
                try {
                    if (completion.isCancelled()) return;
                    T value = operation.call();
                    if (completion.complete(value)) terminalCallback = () -> success.accept(value);
                } catch (Throwable error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (completion.completeExceptionally(error)) {
                        terminalCallback = () -> failure.accept(error);
                    }
                }
                finishedBeforeCallback = finishBeforeCallbackIfStopped(this);
                if (terminalCallback != null) dispatch(terminalCallback);
            } finally {
                if (!finishedBeforeCallback) finished(this);
            }
        }
    }
}
