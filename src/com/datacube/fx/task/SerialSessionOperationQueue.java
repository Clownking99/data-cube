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
            Callable<T> operation,
            Consumer<? super T> success,
            Consumer<? super Throwable> failure) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        if (!accepting) throw new RejectedExecutionException("session operation queue is closing");
        if (current == null && queued.isEmpty()) idle = new CompletableFuture<>();
        QueuedOperation<T> submitted = new QueuedOperation<>(operation, success, failure);
        queued.addLast(submitted);
        scheduleNext();
        return submitted.completion;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(accepting, current != null, queued.size());
    }

    public synchronized CompletionStage<Void> idle() {
        return idle;
    }

    public synchronized CompletionStage<Void> stopAcceptingAndCancelQueued() {
        accepting = false;
        callbacksEnabled = false;
        while (!queued.isEmpty()) queued.removeFirst().completion.cancel(false);
        if (current == null) idle.complete(null);
        return idle;
    }

    public synchronized void reopen() {
        accepting = true;
        callbacksEnabled = true;
        scheduleNext();
    }

    @Override
    public void close() {
        stopAcceptingAndCancelQueued();
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

    public record Snapshot(boolean accepting, boolean running, int queued) {
        public boolean pending() {
            return running || queued > 0;
        }
    }

    private final class QueuedOperation<T> {
        private final Callable<T> operation;
        private final Consumer<? super T> success;
        private final Consumer<? super Throwable> failure;
        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private QueuedOperation(
                Callable<T> operation,
                Consumer<? super T> success,
                Consumer<? super Throwable> failure) {
            this.operation = operation;
            this.success = success;
            this.failure = failure;
        }

        private void run() {
            try {
                if (completion.isCancelled()) return;
                T value = operation.call();
                if (completion.complete(value)) dispatch(() -> success.accept(value));
            } catch (Throwable error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                if (completion.completeExceptionally(error)) dispatch(() -> failure.accept(error));
            } finally {
                finished(this);
            }
        }
    }
}
