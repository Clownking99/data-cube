package com.datacube.fx;

/** Pure state machine deciding whether shutdown may recover UI, close, or remain fatally quarantined. */
public final class ShutdownQuarantine {
    public enum Action { RECOVER, CLOSE, FATAL }
    private State state = State.OPEN;

    public synchronized boolean begin() {
        if (state != State.OPEN) return false;
        state = State.QUARANTINED;
        return true;
    }

    public synchronized boolean isQuarantined() {
        return state != State.OPEN;
    }

    public synchronized Action settle(ShutdownOutcome outcome, Throwable failure) {
        if (state != State.QUARANTINED) throw new IllegalStateException("no shutdown in progress");
        if (failure != null || outcome == null || outcome == ShutdownOutcome.CANCELLED) {
            state = State.OPEN;
            return Action.RECOVER;
        }
        if (outcome == ShutdownOutcome.COMPLETED) {
            state = State.CLOSED;
            return Action.CLOSE;
        }
        state = State.FATAL;
        return Action.FATAL;
    }

    private enum State { OPEN, QUARANTINED, CLOSED, FATAL }
}
