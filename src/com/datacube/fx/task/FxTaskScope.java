package com.datacube.fx.task;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Tracks one Pane/Tab's tasks and prevents callbacks after that owner closes. */
public final class FxTaskScope implements AutoCloseable {

    private final FxTaskRunner runner;
    private final Consumer<Runnable> uiDispatcher;
    private final Consumer<? super Error> fatalErrorHandler;
    private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    FxTaskScope(FxTaskRunner runner, Consumer<Runnable> uiDispatcher,
                Consumer<? super Error> fatalErrorHandler) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.fatalErrorHandler = Objects.requireNonNull(fatalErrorHandler, "fatalErrorHandler");
    }

    public <T> Future<?> submit(Callable<T> operation, Consumer<? super T> success,
                                Consumer<? super Throwable> failure) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        if (closed.get()) throw new RejectedExecutionException("FxTaskScope is closed");

        ScopedFuture future = new ScopedFuture(() -> {
            try {
                T value = operation.call();
                dispatch(() -> success.accept(value));
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                } else if (!(error instanceof CancellationException)) {
                    dispatch(() -> failure.accept(error));
                }
            }
            return null;
        });
        tasks.add(future);
        if (closed.get()) {
            future.cancel(true);
            return future;
        }
        try {
            runner.execute(future);
        } catch (RuntimeException rejected) {
            tasks.remove(future);
            future.cancel(false);
            throw rejected;
        }
        return future;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Future<?> task : tasks) task.cancel(true);
        tasks.clear();
    }

    /** Dispatches owner UI work while suppressing callbacks after this scope closes. */
    public void dispatch(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) return;
        uiDispatcher.accept(() -> {
            if (!closed.get()) callback.run();
        });
    }

    private final class ScopedFuture extends FutureTask<Void> {
        private ScopedFuture(Callable<Void> callable) {
            super(callable);
        }

        @Override
        protected void done() {
            tasks.remove(this);
            if (isCancelled()) return;
            try {
                get();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof Error fatal) fatalErrorHandler.accept(fatal);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (CancellationException ignored) {
            }
        }
    }
}
