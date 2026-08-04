package com.datacube.fx.task;

import javafx.application.Platform;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Runs blocking operations one at a time while still using a fresh virtual
 * thread for every operation.
 */
public final class FxSerialTaskQueue implements AutoCloseable {

    private final FxTaskRunner runner;
    private final Consumer<Runnable> uiDispatcher;
    private final Deque<QueuedTask<?>> pending = new ArrayDeque<>();

    private volatile boolean closed;
    private QueuedTask<?> active;

    public FxSerialTaskQueue(FxTaskRunner runner) {
        this(runner, Platform::runLater);
    }

    FxSerialTaskQueue(FxTaskRunner runner, Consumer<Runnable> uiDispatcher) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    }

    public <T> Future<T> submit(Callable<T> operation,
                                Consumer<? super T> success,
                                Consumer<? super Throwable> failure) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");

        QueuedTask<T> task = new QueuedTask<>(operation, success, failure);
        synchronized (this) {
            if (closed) throw new RejectedExecutionException("FxSerialTaskQueue is closed");
            pending.addLast(task);
            scheduleNext();
        }
        return task.completion;
    }

    private void scheduleNext() {
        if (closed || active != null) return;
        QueuedTask<?> next;
        do {
            next = pending.pollFirst();
        } while (next != null && next.completion.isCancelled());
        if (next == null) return;

        QueuedTask<?> selected = next;
        active = selected;
        try {
            runner.execute(selected::execute);
        } catch (RuntimeException rejected) {
            active = null;
            if (selected.completion.completeExceptionally(rejected)) {
                dispatch(() -> selected.failure.accept(rejected));
            }
            scheduleNext();
        }
    }

    private <T> void runTask(QueuedTask<T> task) {
        try {
            if (closed || task.completion.isCancelled()) return;
            T value = task.operation.call();
            if (!closed && task.completion.complete(value)) {
                dispatch(() -> task.success.accept(value));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.completion.cancel(false);
        } catch (java.util.concurrent.CancellationException cancelled) {
            task.completion.cancel(false);
        } catch (Throwable error) {
            if (closed) {
                task.completion.cancel(false);
            } else if (task.completion.completeExceptionally(error)) {
                dispatch(() -> task.failure.accept(error));
            }
        }
    }

    private synchronized void taskFinished(QueuedTask<?> task) {
        if (active == task) active = null;
        scheduleNext();
    }

    private void dispatch(Runnable callback) {
        if (closed) return;
        uiDispatcher.accept(() -> {
            if (!closed) callback.run();
        });
    }

    @Override
    public void close() {
        QueuedTask<?> running;
        Deque<QueuedTask<?>> queued;
        synchronized (this) {
            if (closed) return;
            closed = true;
            running = active;
            active = null;
            queued = new ArrayDeque<>(pending);
            pending.clear();
        }

        if (running != null) running.completion.cancel(true);
        queued.forEach(task -> task.completion.cancel(false));
    }

    private final class QueuedTask<T> {
        private final Callable<T> operation;
        private final Consumer<? super T> success;
        private final Consumer<? super Throwable> failure;
        private final QueuedFuture<T> completion = new QueuedFuture<>();
        private final FutureTask<Void> execution;

        private QueuedTask(Callable<T> operation, Consumer<? super T> success,
                           Consumer<? super Throwable> failure) {
            this.operation = operation;
            this.success = success;
            this.failure = failure;
            this.execution = new FutureTask<>(() -> {
                runTask(this);
                return null;
            });
            completion.execution = execution;
        }

        private void execute() {
            try {
                execution.run();
            } finally {
                taskFinished(this);
            }
        }
    }

    private static final class QueuedFuture<T> extends CompletableFuture<T> {
        private volatile Future<?> execution;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            Future<?> current = execution;
            if (cancelled && current != null) current.cancel(mayInterruptIfRunning);
            return cancelled;
        }
    }
}
