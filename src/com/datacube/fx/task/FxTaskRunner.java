package com.datacube.fx.task;

import javafx.application.Platform;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Application-level virtual-thread-per-task runner for blocking I/O work. */
public final class FxTaskRunner implements AutoCloseable {

    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(3);

    private final ExecutorService executor;
    private final Duration shutdownTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FxTaskRunner() {
        this(Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("DataCube-io-", 0).factory()), DEFAULT_SHUTDOWN_TIMEOUT);
    }

    FxTaskRunner(ExecutorService executor, Duration shutdownTimeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    }

    public Future<?> submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (closed.get()) throw new RejectedExecutionException("FxTaskRunner is closed");
        return executor.submit(task);
    }

    void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (closed.get()) throw new RejectedExecutionException("FxTaskRunner is closed");
        executor.execute(task);
    }

    public FxTaskScope scope() {
        return scope(Platform::runLater);
    }

    FxTaskScope scope(Consumer<Runnable> uiDispatcher) {
        return scope(uiDispatcher, FxTaskRunner::reportFatal);
    }

    FxTaskScope scope(Consumer<Runnable> uiDispatcher, Consumer<? super Error> fatalErrorHandler) {
        return new FxTaskScope(this, uiDispatcher, fatalErrorHandler);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void reportFatal(Error error) {
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, error);
    }
}
