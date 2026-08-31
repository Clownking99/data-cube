package com.datacube.config;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** UI-owner workspace admission; shares the draft runtime's writer and management generation. */
public final class SqlWorkspaceActivity {
    public enum Status { IDLE, PENDING, SAVED, DISABLED, SESSION_PAUSED, FAILED, FROZEN }
    private record Candidate(SqlWorkspace workspace, long generation) { }
    private final SqlDraftCoordinator runtime;
    private final LongSupplier clock;
    private Candidate latest;
    private SqlWorkspace saved;
    private long due = -1, firstDirty = -1, generation;
    private boolean active, paused, failed, frozen, enabled = true;
    private CompletableFuture<Void> inFlight;
    private Status status = Status.IDLE;
    private SqlDraftCoordinator.FailureReason failureReason;
    private SqlWorkspaceStore.FailureCode failureCode;
    private boolean inspected;
    private CompletableFuture<Void> reading;
    public record Frozen(SqlWorkspace workspace, long generation, boolean recording) { }

    public SqlWorkspaceActivity(SqlDraftCoordinator runtime, LongSupplier clock) {
        this.runtime = Objects.requireNonNull(runtime);
        this.clock = Objects.requireNonNull(clock);
        generation = runtime.workspaceGeneration();
    }

    public void activity(SqlWorkspace workspace) {
        observeGeneration();
        if (paused || !enabled) return;
        active = true;
        frozen = false;
        offer(Objects.requireNonNull(workspace));
        updateStatus();
        if (!failed) inspect();
    }

    private void offer(SqlWorkspace workspace) {
        if (sameLayout(latest == null ? saved : latest.workspace(), workspace)) return;
        latest = new Candidate(workspace, generation);
        long now = clock.getAsLong();
        if (firstDirty < 0) firstDirty = now;
        due = Math.min(addSaturated(now, 1000), addSaturated(firstDirty, 10000));
        updateStatus();
    }

    public void pulse() {
        if (observeGeneration() || !active || paused || frozen || failed || !enabled
                || latest == null || inFlight != null || clock.getAsLong() < due) return;
        if (!inspected) { inspect(); return; }
        submit(latest);
    }

    /** Checkpoint acknowledgements enrich only an already-active, non-frozen generation. */
    public void checkpointObserved(SqlWorkspace workspace) {
        if (observeGeneration() || !active || paused || frozen || !enabled) return;
        offer(workspace);
    }

    private CompletableFuture<Void> inspect() {
        if (inspected) return CompletableFuture.completedFuture(null);
        if (reading != null) return reading.copy();
        long expected = generation;
        CompletableFuture<Void> pending = new CompletableFuture<>();
        reading = pending;
        runtime.workspaceSnapshot().whenComplete((snapshot, error) -> {
            if (reading == pending) reading = null;
            observeGeneration();
            if (generation != expected || paused) {
                pending.complete(null); return;
            }
            if (error == null && snapshot != null && snapshot.preferenceValid()
                    && (snapshot.status() == SqlWorkspaceStore.Status.AVAILABLE
                        || snapshot.status() == SqlWorkspaceStore.Status.ABSENT)) {
                inspected = true; enabled = snapshot.recordingEnabled(); saved = snapshot.workspace();
                if (latest != null && sameLayout(saved, latest.workspace())) latest = null;
                updateStatus(); pending.complete(null); pulse();
            } else {
                if (error == null) error = new SqlWorkspaceStore.Failure(snapshot != null && !snapshot.preferenceValid()
                        ? SqlWorkspaceStore.FailureCode.PREFERENCE_CORRUPT : SqlWorkspaceStore.FailureCode.PROTECTED_WORKSPACE);
                if (reason(error) != SqlDraftCoordinator.FailureReason.BUSY) {
                    failed = true; failureReason = reason(error);
                    failureCode = error instanceof SqlWorkspaceStore.Failure f ? f.code() : null;
                    updateStatus();
                }
                pending.completeExceptionally(error);
            }
        });
        return pending.copy();
    }

    private CompletableFuture<Void> submit(Candidate submitted) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        inFlight = settled;
        runtime.saveWorkspace(submitted.workspace()).whenComplete((unused, error) -> {
            if (inFlight == settled) inFlight = null;
            observeGeneration();
            if (submitted.generation() == generation && !paused) {
                if (error == null) {
                    saved = submitted.workspace();
                    if (latest == submitted) { latest = null; firstDirty = due = -1; }
                } else if (reason(error) == SqlDraftCoordinator.FailureReason.CANCELLED) {
                    invalidate();
                } else if (reason(error) != SqlDraftCoordinator.FailureReason.BUSY) {
                    failed = true;
                    failureReason = reason(error);
                    failureCode = error instanceof SqlWorkspaceStore.Failure f ? f.code() : null;
                }
                updateStatus();
            }
            if (error == null) settled.complete(null); else settled.completeExceptionally(error);
        });
        return settled.copy();
    }

    /** Untouched sessions must remain entirely lazy. */
    public CompletableFuture<Void> freezeForExit() {
        observeGeneration(); frozen = true; updateStatus();
        return CompletableFuture.completedFuture(null);
    }

    public Frozen freezeForExit(SqlWorkspace provisional) {
        observeGeneration();
        Frozen result = new Frozen(provisional, generation, active && !paused && enabled);
        frozen = true; latest = null; firstDirty = due = -1; updateStatus();
        return result;
    }

    /** Final publication cannot be cancelled by a caller, and never recaptures detached nodes. */
    public CompletableFuture<Void> saveFrozen(Frozen token, SqlWorkspace validated) {
        observeGeneration();
        if (!token.recording() || token.generation() != generation || paused || !enabled)
            return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> wait = inFlight == null ? CompletableFuture.completedFuture(null)
                : inFlight.handle((unused, error) -> null);
        CompletableFuture<Void> result = wait.thenCompose(unused -> inspect()).thenCompose(unused -> {
            observeGeneration();
            if (token.generation() != generation || paused || !enabled) return CompletableFuture.completedFuture(null);
            return submit(new Candidate(validated, generation));
        });
        return result.copy();
    }

    public CompletableFuture<Boolean> clearWorkspace() {
        observeGeneration(); invalidate(); updateStatus();
        var result = runtime.clearWorkspace(); observeGeneration(); return result.copy();
    }

    public void captureFailed() { observeGeneration(); failed = true; failureCode = SqlWorkspaceStore.FailureCode.INVALID_WORKSPACE; updateStatus(); }

    public CompletableFuture<Void> retry() {
        observeGeneration();
        if (!paused && enabled) { failed = false; due = clock.getAsLong(); updateStatus(); }
        return CompletableFuture.completedFuture(null);
    }

    /** Disable intent immediately pauses this session, including failed preference writes. */
    public CompletableFuture<Void> setWorkspaceEnabled(boolean enable) {
        observeGeneration();
        if (!enable) paused = true;
        invalidate(); updateStatus();
        CompletableFuture<Void> result = runtime.setWorkspaceEnabled(enable);
        observeGeneration();
        long accepted = generation;
        result.whenComplete((unused, error) -> {
            observeGeneration();
            if (generation != accepted) return;
            if (error == null) {
                enabled = enable;
                paused = false;
                if (enable) failed = false;
            }
            updateStatus();
        });
        return result.copy();
    }

    public Status status() { observeGeneration(); return status; }
    public SqlDraftCoordinator.FailureReason failureReason() { return failureReason; }
    public SqlWorkspaceStore.FailureCode failureCode() { return failureCode; }
    public String statusText() {
        return switch (status()) {
            case IDLE -> "工作区记录尚未开始";
            case PENDING -> "工作区记录待保存";
            case SAVED -> "工作区记录已保存";
            case DISABLED -> "工作区记录已关闭";
            case SESSION_PAUSED -> "关闭设置未保存，下次启动可能恢复";
            case FAILED -> "工作区记录未保存，已有恢复点保留，可重试";
            case FROZEN -> "工作区记录已冻结";
        };
    }

    private void updateStatus() {
        status = paused ? Status.SESSION_PAUSED : !enabled ? Status.DISABLED : frozen ? Status.FROZEN
                : failed ? Status.FAILED : latest != null ? Status.PENDING
                : saved != null && active ? Status.SAVED : Status.IDLE;
    }
    private boolean observeGeneration() {
        long current = runtime.workspaceGeneration();
        if (current == generation) return false;
        generation = current; invalidate(); updateStatus(); return true;
    }
    private void invalidate() {
        latest = null; saved = null; active = false; frozen = false; inspected = false;
        reading = null; enabled = true; firstDirty = due = -1;
    }
    private static SqlDraftCoordinator.FailureReason reason(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) error = error.getCause();
        return error instanceof SqlDraftCoordinator.Failure f ? f.reason() : SqlDraftCoordinator.FailureReason.WRITE;
    }
    private static boolean sameLayout(SqlWorkspace left, SqlWorkspace right) {
        return left != null && right != null && left.entries().equals(right.entries())
                && Objects.equals(left.selectedDraftId(), right.selectedDraftId());
    }
    private static long addSaturated(long value, long amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
}
