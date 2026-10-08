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
    void recordCleanupResidues(List<Path> paths) { cleanupResidues = List.copyOf(paths); }
    public List<Path> cleanupResidues() { return cleanupResidues; }

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
        try { action.run(); committed = true; }
        finally { state.set(committed ? State.PUBLISHED : State.FAILED); }
    }

    public boolean published() { return state.get() == State.PUBLISHED; }
}
