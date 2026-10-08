package com.datacube.export;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.util.List;

public final class ResultExportOperation {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    private enum State { ACTIVE, CANCELLED, PUBLISHING, PUBLISHED, FAILED }
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private volatile List<Path> cleanupResidues = List.of();
    private volatile boolean cleanupPending;
    private volatile Runnable cleanupPendingListener = () -> {};
    private volatile Action publicationCheck = () -> {};
    void requireBeforePublication(Action check) { publicationCheck = java.util.Objects.requireNonNull(check); }
    void verifyPublicationEligibility() throws Exception { publicationCheck.run(); }
    void recordCleanupResidues(List<Path> paths) { cleanupResidues = List.copyOf(paths); }
    public List<Path> cleanupResidues() { return cleanupResidues; }
    public boolean cancelled() { return state.get() == State.CANCELLED; }
    public boolean cleanupPending() { return cleanupPending; }
    public void onCleanupPending(Runnable listener) { cleanupPendingListener = java.util.Objects.requireNonNull(listener); }
    void markCleanupPending() {
        cleanupPending = true;
        try { cleanupPendingListener.run(); } catch (RuntimeException ignored) {}
    }

    public void check() {
        if (state.get() != State.ACTIVE || Thread.currentThread().isInterrupted())
            throw new CancellationException("Export cancelled");
    }

    public boolean cancel() { return state.compareAndSet(State.ACTIVE, State.CANCELLED); }

    public void publish(Action action) throws Exception {
        check();
        if (!state.compareAndSet(State.ACTIVE, State.PUBLISHING))
            throw new CancellationException("Export is no longer active");
        boolean committed = false;
        try { verifyPublicationEligibility(); action.run(); committed = true; }
        finally { state.set(committed ? State.PUBLISHED : State.FAILED); publicationCheck = () -> {}; }
    }

    public boolean published() { return state.get() == State.PUBLISHED; }
}
