package com.datacube.fx;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** AppShell-owned admission and physical settlement for table exports only. */
final class TableExportTasks {
    private final Set<ExportDialog.ExportTask> tasks = new HashSet<>();
    private boolean frozen, sealed;
    synchronized boolean admitting() { return !frozen && !sealed; }
    synchronized boolean register(ExportDialog.ExportTask task) {
        if (!admitting()) return false;
        tasks.add(task);
        task.physicalCompletion.whenComplete((ignored, failure) -> { synchronized (this) { tasks.remove(task); } });
        return true;
    }
    synchronized void freeze() { frozen = true; }
    synchronized void resume() { if (!sealed) frozen = false; }
    synchronized int pending() { return tasks.size(); }
    void stopAndAwait() {
        List<ExportDialog.ExportTask> owned;
        synchronized (this) { frozen = true; sealed = true; owned = List.copyOf(tasks); }
        for (var task : owned) task.requestStop();
        // This is called before best-effort teardown, on its background worker. No budget fakes settlement.
        CompletableFuture.allOf(owned.stream().map(task -> task.physicalCompletion).toArray(CompletableFuture[]::new)).join();
    }
}
