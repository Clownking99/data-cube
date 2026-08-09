package com.datacube.update;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Coordinates update I/O and callback delivery without depending on JavaFX. */
final class UpdateTaskDispatcher implements AutoCloseable {

    private final Executor background;
    private final Consumer<Runnable> callbacks;
    private final AtomicBoolean closed = new AtomicBoolean();

    UpdateTaskDispatcher(Executor background, Consumer<Runnable> callbacks) {
        this.background = Objects.requireNonNull(background, "background");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    void execute(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) return;
        try {
            background.execute(() -> {
                if (!closed.get()) operation.run();
            });
        } catch (RejectedExecutionException rejected) {
            if (!closed.get()) throw rejected;
        }
    }

    void dispatch(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) return;
        callbacks.accept(() -> {
            if (!closed.get()) callback.run();
        });
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
