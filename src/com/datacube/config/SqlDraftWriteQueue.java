package com.datacube.config;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Serial disk-work ordering with one pending immutable snapshot per ID. */
final class SqlDraftWriteQueue {
    enum Reason { SUPERSEDED, CANCELLED, CLOSED, REJECTED }
    static final class Failure extends IOException {
        private final Reason reason;
        Failure(Reason reason) { super("SQL draft queue: " + reason); this.reason = reason; }
        Reason reason() { return reason; }
    }
    @FunctionalInterface interface Writer { void write(SqlDraft draft) throws IOException; }
    private abstract static class Job<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        abstract T run() throws Exception;
        final void execute() {
            try { future.complete(run()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        }
        final void fail(Reason reason) { future.completeExceptionally(new Failure(reason)); }
    }
    private final class SaveJob extends Job<Void> {
        final SqlDraft draft;
        SaveJob(SqlDraft draft) { this.draft = draft; }
        @Override Void run() throws IOException { writer.write(draft); return null; }
    }
    private static final class ActionJob<T> extends Job<T> {
        private final Callable<T> action;
        ActionJob(Callable<T> action) { this.action = action; }
        @Override T run() throws Exception { return action.call(); }
    }
    private final Object lock = new Object();
    private final Executor executor;
    private final Writer writer;
    private final ArrayDeque<Job<?>> jobs = new ArrayDeque<>();
    private final Map<UUID, SaveJob> pending = new HashMap<>();
    private boolean draining;
    private boolean closed;
    private boolean rejected;
    private CompletableFuture<Void> shutdown;

    SqlDraftWriteQueue(Executor executor, Writer writer) {
        this.executor = Objects.requireNonNull(executor);
        this.writer = Objects.requireNonNull(writer);
    }

    CompletableFuture<Void> save(SqlDraft draft) {
        SaveJob job = new SaveJob(Objects.requireNonNull(draft));
        SaveJob previous;
        boolean start;
        synchronized (lock) {
            if (closed) return CompletableFuture.failedFuture(new Failure(Reason.CLOSED));
            previous = pending.put(draft.id(), job);
            if (previous != null) jobs.remove(previous);
            jobs.add(job);
            start = arm();
        }
        if (previous != null) previous.fail(Reason.SUPERSEDED);
        if (start) schedule();
        return job.future;
    }

    <T> CompletableFuture<T> barrier(Set<UUID> ids, Callable<T> action) {
        return enqueueBarrier(Set.copyOf(ids), action);
    }

    <T> CompletableFuture<T> barrierAll(Callable<T> action) { return enqueueBarrier(null, action); }

    private <T> CompletableFuture<T> enqueueBarrier(Set<UUID> ids, Callable<T> action) {
        ActionJob<T> job = new ActionJob<>(Objects.requireNonNull(action));
        List<SaveJob> cancelled = new ArrayList<>();
        boolean start;
        synchronized (lock) {
            if (closed) return CompletableFuture.failedFuture(new Failure(Reason.CLOSED));
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (ids == null || ids.contains(entry.getKey())) {
                    SaveJob obsolete = entry.getValue();
                    iterator.remove();
                    jobs.remove(obsolete);
                    cancelled.add(obsolete);
                }
            }
            jobs.add(job);
            start = arm();
        }
        cancelled.forEach(obsolete -> obsolete.fail(Reason.CANCELLED));
        if (start) schedule();
        return job.future;
    }

    CompletableFuture<Void> drainAndClose() {
        boolean start;
        synchronized (lock) {
            if (shutdown != null) return shutdown;
            if (rejected) {
                shutdown = CompletableFuture.failedFuture(new Failure(Reason.REJECTED));
                return shutdown;
            }
            closed = true;
            ActionJob<Void> last = new ActionJob<>(() -> null);
            shutdown = last.future;
            jobs.add(last);
            start = arm();
        }
        if (start) schedule();
        return shutdown;
    }

    /** Called only under lock; execute outside lock even for inline executors. */
    private boolean arm() {
        if (draining) return false;
        draining = true;
        return true;
    }

    private void schedule() {
        try { executor.execute(this::drain); }
        catch (RuntimeException schedulingFailure) {
            List<Job<?>> abandoned;
            synchronized (lock) {
                rejected = true;
                closed = true;
                draining = false;
                abandoned = List.copyOf(jobs);
                jobs.clear();
                pending.clear();
            }
            abandoned.forEach(job -> job.fail(Reason.REJECTED));
        }
    }

    private void drain() {
        while (true) {
            Job<?> job;
            synchronized (lock) {
                job = jobs.poll();
                if (job == null) { draining = false; return; }
                if (job instanceof SqlDraftWriteQueue.SaveJob save) pending.remove(save.draft.id(), save);
            }
            job.execute();
        }
    }
}
