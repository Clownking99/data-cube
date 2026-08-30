package com.datacube.export;

import java.util.concurrent.CancellationException;

public final class ResultExportOperation {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    private enum State { ACTIVE, CANCELLED, PUBLISHED }
    private State state = State.ACTIVE;

    public synchronized void check() {
        if (state != State.ACTIVE || Thread.currentThread().isInterrupted())
            throw new CancellationException("Export cancelled");
    }

    public synchronized boolean cancel() {
        if (state != State.ACTIVE) return false;
        state = State.CANCELLED;
        return true;
    }

    public synchronized void publish(Action action) throws Exception {
        check();
        action.run();
        state = State.PUBLISHED;
    }

    public synchronized boolean published() { return state == State.PUBLISHED; }
}
