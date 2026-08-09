package com.datacube.fx;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/** Constructor-local ownership transaction with reverse-order best-effort rollback. */
final class ConstructionOwner implements AutoCloseable {
    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private final Consumer<? super Throwable> reporter;
    private boolean committed;

    ConstructionOwner() {
        this(failure -> {
            System.err.println("[DataCube] construction rollback failure: " + failure);
            failure.printStackTrace(System.err);
        });
    }

    ConstructionOwner(Consumer<? super Throwable> reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    void own(Runnable action) {
        if (committed) throw new IllegalStateException("construction ownership already committed");
        cleanup.addFirst(Objects.requireNonNull(action, "action"));
    }

    void commit() {
        committed = true;
        cleanup.clear();
    }

    @Override
    public void close() {
        if (committed) return;
        Throwable first = null;
        while (!cleanup.isEmpty()) {
            try {
                cleanup.removeFirst().run();
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
                try { reporter.accept(failure); } catch (Throwable ignored) { }
            }
        }
        if (first != null) throw new PartialCloseException(first);
    }
}
