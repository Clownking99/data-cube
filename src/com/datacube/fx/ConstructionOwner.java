package com.datacube.fx;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/** Constructor-local ownership transaction with reverse-order best-effort rollback. */
final class ConstructionOwner {
    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private final Deque<Runnable> blockingCleanup = new ArrayDeque<>();
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

    void ownBlocking(Runnable action) {
        if (committed) throw new IllegalStateException("construction ownership already committed");
        blockingCleanup.addFirst(Objects.requireNonNull(action, "action"));
    }

    void commit() {
        committed = true;
        cleanup.clear();
        blockingCleanup.clear();
    }

    Rollback close(Throwable constructionFailure) {
        Objects.requireNonNull(constructionFailure, "constructionFailure");
        if (committed) {
            return new Rollback(RollbackOutcome.SAFE,
                    new SafeConstructionFailure(constructionFailure));
        }
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
        Runnable deferred = blockingCleanup.isEmpty() ? null : () ->
                BestEffortCloseSequence.run(blockingCleanup.toArray(Runnable[]::new));
        if (first == null) {
            return new Rollback(RollbackOutcome.SAFE,
                    new SafeConstructionFailure(constructionFailure, deferred));
        }
        PartialCloseException partial = new PartialCloseException(constructionFailure, deferred);
        partial.addSuppressed(first);
        return new Rollback(RollbackOutcome.FAILED_PARTIAL, partial);
    }

    enum RollbackOutcome { SAFE, FAILED_PARTIAL }

    record Rollback(RollbackOutcome outcome, RuntimeException failure) {}
}
