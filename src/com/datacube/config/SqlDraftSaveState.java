package com.datacube.config;

import java.util.OptionalLong;

/** Owner-confined timing/status model; never reads editor text or performs I/O. */
final class SqlDraftSaveState {
    enum State { EMPTY, WAITING, SAVING, SAVED, FAILED, DISABLED, UNAVAILABLE }
    record Ticket(long generation, long revision, long attempt) { }
    private static final long IDLE_MILLIS = 1000;
    private static final long MAX_DIRTY_MILLIS = 10000;
    private State state;
    private long generation;
    private long revision;
    private long attempt;
    private long firstDirty = -1;
    private long deadline = -1;
    private long lastNow = -1;
    private Long savedAt;
    private Ticket inFlight;

    SqlDraftSaveState(Long savedAt, boolean enabled, boolean available) {
        if (savedAt != null && savedAt < 0) throw new IllegalArgumentException("Invalid draft timestamp");
        this.savedAt = savedAt;
        state = !available ? State.UNAVAILABLE : !enabled ? State.DISABLED
                : savedAt == null ? State.EMPTY : State.SAVED;
    }

    State state() { return state; }
    OptionalLong dueAt() { return deadline < 0 ? OptionalLong.empty() : OptionalLong.of(deadline); }
    OptionalLong savedAt() { return savedAt == null ? OptionalLong.empty() : OptionalLong.of(savedAt); }

    void edited(long now) {
        observe(now);
        revision = Math.incrementExact(revision);
        if (paused()) return;
        if (firstDirty < 0) firstDirty = now;
        deadline = Math.min(addSaturated(now, IDLE_MILLIS), addSaturated(firstDirty, MAX_DIRTY_MILLIS));
        state = State.WAITING;
    }

    Ticket capture(long now, boolean force) {
        observe(now);
        if (state != State.WAITING && !(force && state == State.FAILED)) return null;
        if (!force && now < deadline) return null;
        attempt = Math.incrementExact(attempt);
        inFlight = new Ticket(generation, revision, attempt);
        firstDirty = -1;
        deadline = -1;
        state = State.SAVING;
        return inFlight;
    }

    boolean succeeded(Ticket ticket, long publishedAt) {
        if (publishedAt < 0) throw new IllegalArgumentException("Invalid draft timestamp");
        if (!current(ticket)) return false;
        savedAt = publishedAt;
        inFlight = null;
        state = State.SAVED;
        return true;
    }

    boolean failed(Ticket ticket) {
        if (!current(ticket)) return false;
        inFlight = null;
        state = State.FAILED;
        return true;
    }

    void retry(long now) {
        observe(now);
        if (state != State.FAILED) return;
        firstDirty = now;
        deadline = now;
        state = State.WAITING;
    }

    void clear() {
        invalidate();
        savedAt = null;
        if (!paused()) state = State.EMPTY;
    }

    void pause(boolean unavailable) {
        invalidate();
        state = unavailable ? State.UNAVAILABLE : State.DISABLED;
    }

    void resume(long now, boolean captureCurrent) {
        observe(now);
        invalidate();
        state = savedAt == null ? State.EMPTY : State.SAVED;
        if (captureCurrent) edited(now);
    }

    private void invalidate() {
        generation = Math.incrementExact(generation);
        firstDirty = -1;
        deadline = -1;
        inFlight = null;
    }

    private boolean current(Ticket ticket) {
        return state == State.SAVING && ticket != null && ticket.equals(inFlight)
                && ticket.generation() == generation && ticket.revision() == revision;
    }

    private boolean paused() { return state == State.DISABLED || state == State.UNAVAILABLE; }

    private void observe(long now) {
        if (now < 0 || now < lastNow) throw new IllegalArgumentException("Invalid draft monotonic time");
        lastNow = now;
    }

    private static long addSaturated(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
