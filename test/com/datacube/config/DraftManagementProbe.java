package com.datacube.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Controlled storage boundary for real coordinator/UI tests; never opens a profile. */
public final class DraftManagementProbe implements SqlDraftCoordinator.Backend {
    public enum SaveFault { NONE, WRITE, CAPACITY, INVALID_DRAFT, CLEANUP }
    public final List<SqlDraft> records = new ArrayList<>();
    public final Queue<Runnable> writes = new ConcurrentLinkedQueue<>();
    public boolean enabled = true, writable = true, failPreference, partialClear;
    public SaveFault saveFault = SaveFault.NONE;
    public int deletions, clears, prunes;

    public SqlDraftCoordinator create(Executor ui, BooleanSupplier isUi) {
        return new SqlDraftCoordinator(() -> this, writes::add, ui, isUi, () -> 0, () -> 100_000L);
    }
    public void drain() { Runnable work; while ((work = writes.poll()) != null) work.run(); }
    public void save(SqlDraft draft) throws IOException {
        switch (saveFault) {
            case WRITE -> throw new IOException("synthetic private SQL and path");
            case CAPACITY -> throw new SqlDraftStore.Failure(SqlDraftStore.FailureCode.CAPACITY);
            case INVALID_DRAFT -> throw new SqlDraftStore.Failure(SqlDraftStore.FailureCode.INVALID_DRAFT);
            case CLEANUP -> throw new SqlDraftDirectory.Failure(SqlDraftDirectory.Stage.CLEANUP);
            case NONE -> { }
        }
        records.removeIf(item -> item.id().equals(draft.id()));
        records.add(draft);
    }
    public SqlDraftStore.Snapshot snapshot() {
        return new SqlDraftStore.Snapshot(records, partialClear
                ? List.of(new SqlDraftStore.Problem(null, SqlDraftStore.ProblemCode.CORRUPT_DRAFT))
                : List.of(), enabled, writable);
    }
    public void setEnabled(boolean value) throws IOException {
        if (failPreference) throw new IOException("synthetic preference failure");
        enabled = value;
    }
    public void clear() throws IOException {
        clears++;
        if (partialClear) {
            if (!records.isEmpty()) records.removeFirst();
            throw new IOException("synthetic partial deletion");
        }
        records.clear();
    }
    public void delete(UUID id) { deletions++; records.removeIf(item -> item.id().equals(id)); }
    public void prune(long now, Set<UUID> openIds) { prunes++; }
    public void close() { }
}
