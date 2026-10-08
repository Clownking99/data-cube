package com.datacube.export;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.util.List;

public final class ResultExportOperation {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    private enum State { ACTIVE, CANCELLED, TARGET_CHANGED, TIMED_OUT, PUBLISHING, PUBLISHED, FAILED }
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private volatile List<Path> cleanupResidues = List.of();
    private volatile boolean cleanupPending;
    private volatile Runnable cleanupPendingListener = () -> {};
    private volatile Action publicationCheck = () -> {};
    private volatile Runnable beforeClaim = () -> {};
    void requireBeforeClaim(Runnable check){beforeClaim=java.util.Objects.requireNonNull(check);}
    void requireBeforePublication(Action check) {
        Action previous=publicationCheck;
        publicationCheck=()->{previous.run();check.run();};
    }
    void verifyPublicationEligibility() throws Exception { publicationCheck.run(); }
    void recordCleanupResidues(List<Path> paths) { cleanupResidues = List.copyOf(paths); }
    public List<Path> cleanupResidues() { return cleanupResidues; }
    public boolean cancelled() { State value=state.get();return value==State.CANCELLED||value==State.TARGET_CHANGED||value==State.TIMED_OUT; }
    public boolean cleanupPending() { return cleanupPending; }
    public void onCleanupPending(Runnable listener) { cleanupPendingListener = java.util.Objects.requireNonNull(listener); }
    void markCleanupPending() {
        cleanupPending = true;
        try { cleanupPendingListener.run(); } catch (RuntimeException ignored) {}
    }

    public void check() {
        State value=state.get();
        if(value==State.TARGET_CHANGED)throw new TableExportFailure(TableExportFailure.Kind.TARGET_CHANGED);
        if(value==State.TIMED_OUT)throw new TableExportFailure(TableExportFailure.Kind.TIMEOUT);
        if (value != State.ACTIVE || Thread.currentThread().isInterrupted())
            throw new CancellationException("Export cancelled");
    }

    public boolean cancel() { return state.compareAndSet(State.ACTIVE, State.CANCELLED); }
    public void invalidateTarget(){state.compareAndSet(State.ACTIVE,State.TARGET_CHANGED);}
    void timedOut(){state.compareAndSet(State.ACTIVE,State.TIMED_OUT);}

    public void publish(Action action) throws Exception {
        beforeClaim.run();
        check();
        if (!state.compareAndSet(State.ACTIVE, State.PUBLISHING))
            throw new CancellationException("Export is no longer active");
        boolean committed = false;
        try { verifyPublicationEligibility(); action.run(); committed = true; }
        finally { state.set(committed ? State.PUBLISHED : State.FAILED); publicationCheck = () -> {}; }
    }

    public boolean published() { return state.get() == State.PUBLISHED; }
}
