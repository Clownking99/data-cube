package com.datacube.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Application-owned draft runtime. Public operations belong to the UI thread. */
public final class SqlDraftCoordinator {
    public enum Mode { INITIALIZING, ENABLED, DISABLED, PAUSED, UNAVAILABLE, CLOSED }
    public enum SaveStatus { EMPTY, WAITING, SAVING, SAVED, FAILED }
    public enum FailureReason { INITIALIZING, PAUSED, UNAVAILABLE, CLOSED, BUSY, CAPTURE, WRITE, SHUTDOWN }
    public record Status(Mode mode, SaveStatus saveStatus, Long savedAt) { }
    public record ManagementResult(boolean succeeded, SqlDraftStore.Snapshot snapshot) { }
    public static final class Failure extends IOException {
        private final FailureReason reason;
        Failure(FailureReason reason) { super("SQL draft runtime: " + reason); this.reason = reason; }
        public FailureReason reason() { return reason; }
    }
    public interface Source {
        boolean hasText();
        SqlDraft capture(UUID id, long modifiedAt);
    }
    interface Backend extends AutoCloseable {
        void save(SqlDraft draft) throws IOException;
        SqlDraftStore.Snapshot snapshot() throws IOException;
        void setEnabled(boolean enabled) throws IOException;
        void clear() throws IOException;
        void delete(UUID id) throws IOException;
        void prune(long now, Set<UUID> openIds) throws IOException;
        @Override void close() throws IOException;
    }
    @FunctionalInterface interface Factory { Backend open() throws IOException; }
    private static final class LocalBackend implements Backend {
        private final SqlDraftStore store;
        LocalBackend(SqlDraftStore store) { this.store = store; }
        public void save(SqlDraft draft) throws IOException { store.save(draft); }
        public SqlDraftStore.Snapshot snapshot() throws IOException { return store.snapshot(); }
        public void setEnabled(boolean enabled) throws IOException { store.setEnabled(enabled); }
        public void clear() throws IOException { store.clearRecoverable(); }
        public void delete(UUID id) throws IOException { store.delete(id); }
        public void prune(long now, Set<UUID> openIds) throws IOException { store.pruneExpired(now, openIds); }
        public void close() throws IOException { store.close(); }
    }
    private final SqlDraftWriteQueue queue;
    private final Executor ui;
    private final BooleanSupplier isUi;
    private final LongSupplier elapsed, wall;
    private final Map<UUID, Handle> handles = new LinkedHashMap<>();
    private final Set<UUID> openIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean admitted = new AtomicBoolean(), faulted = new AtomicBoolean();
    private Backend backend;
    private Mode mode = Mode.INITIALIZING;
    private ManagementResult lastManagementResult;
    private boolean busy = true, closing;
    private CompletableFuture<Void> shutdown;

    public SqlDraftCoordinator(Path directory, Executor writer, Executor ui, BooleanSupplier isUi,
            LongSupplier elapsed, LongSupplier wall) {
        this(() -> {
            Path path = directory.toAbsolutePath().normalize();
            if (path.getParent() == null) throw new IOException("Invalid draft directory");
            Files.createDirectories(path.getParent());
            return new LocalBackend(SqlDraftStore.open(path));
        }, writer, ui, isUi, elapsed, wall);
    }

    SqlDraftCoordinator(Factory factory, Executor writer, Executor ui, BooleanSupplier isUi,
            LongSupplier elapsed, LongSupplier wall) {
        this.ui = Objects.requireNonNull(ui); this.isUi = Objects.requireNonNull(isUi);
        this.elapsed = Objects.requireNonNull(elapsed); this.wall = Objects.requireNonNull(wall);
        Objects.requireNonNull(factory); owner();
        queue = new SqlDraftWriteQueue(writer, this::write);
        queue.barrier(Set.of(), () -> {
            backend = factory.open();
            return inspect(() -> { backend.prune(wall.getAsLong(), Set.copyOf(openIds)); return null; });
        }).whenComplete((result, failure) -> {
            if (failure != null) stop();
            post(() -> {
                busy = false; lastManagementResult = result;
                if (failure != null || result.snapshot() == null || !result.snapshot().writable()) { stop(); return; }
                SqlDraftStore.Snapshot snapshot = result.snapshot();
                mode = snapshot.protectionEnabled() ? Mode.ENABLED : Mode.DISABLED;
                admitted.set(mode == Mode.ENABLED && !faulted.get());
                resume(false);
            });
        });
    }

    public Mode mode() {
        owner();
        return closing ? Mode.CLOSED : faulted.get() ? Mode.UNAVAILABLE : mode;
    }

    public ManagementResult lastManagementResult() { owner(); return lastManagementResult; }

    public boolean managementPending() { owner(); return busy; }

    public Handle attach(UUID id, Long savedAt, Source source) {
        active(); Objects.requireNonNull(id); Objects.requireNonNull(source);
        if (savedAt != null && busy) throw new IllegalStateException("Draft management in progress");
        if (handles.containsKey(id)) throw new IllegalArgumentException("Draft already open");
        Handle handle = new Handle(id, savedAt, source);
        handles.put(id, handle); openIds.add(id);
        try { if (savedAt == null && source.hasText()) handle.edited(); }
        catch (RuntimeException invalid) { handle.detach(); throw new IllegalArgumentException("Draft source unavailable"); }
        return handle;
    }

    public void pulse() {
        active(); if (mode() != Mode.ENABLED || !admitted.get()) return;
        long now = elapsed.getAsLong();
        for (Handle handle : List.copyOf(handles.values())) {
            if (handle.eligible && handle.state.dueAt().isPresent()
                    && now >= handle.state.dueAt().getAsLong()) handle.capture(false);
        }
    }

    public final class Handle {
        private final UUID id;
        private final Source source;
        private final SqlDraftSaveState state;
        private boolean eligible, offered, changed, detached;
        private CompletableFuture<Void> inFlight;
        private Handle(UUID id, Long savedAt, Source source) {
            this.id = id; this.source = source; eligible = offered = savedAt != null;
            state = new SqlDraftSaveState(savedAt, mode() == Mode.ENABLED, mode() != Mode.INITIALIZING && mode() != Mode.UNAVAILABLE);
        }
        public UUID id() { owner(); return id; }
        public Status status() {
            owner();
            SaveStatus saved = switch (state.state()) {
                case WAITING -> SaveStatus.WAITING;
                case SAVING -> SaveStatus.SAVING;
                case SAVED -> SaveStatus.SAVED;
                case FAILED -> SaveStatus.FAILED;
                default -> SaveStatus.EMPTY;
            };
            return new Status(mode(), saved, state.savedAt().isPresent() ? state.savedAt().getAsLong() : null);
        }
        public void edited() {
            attached();
            if (!offered && !source.hasText()) { reset(); return; }
            eligible = changed = true; state.edited(elapsed.getAsLong());
        }
        public void retry() { attached(); state.retry(elapsed.getAsLong()); }
        public CompletableFuture<Void> flush() {
            attached();
            if (!eligible || mode() == Mode.DISABLED) return CompletableFuture.completedFuture(null);
            if (mode() != Mode.ENABLED || !admitted.get()) return refused(modeReason());
            return capture(true).copy();
        }
        public void detach() {
            owner(); if (detached) return;
            detached = true; state.pause(true); handles.remove(id, this); openIds.remove(id);
            if (!closing) queue.barrier(Set.of(id), () -> null);
        }
        private void attached() {
            active(); if (detached) throw new IllegalStateException("Draft handle detached");
        }
        private void reset() {
            state.clear(); eligible = offered = changed = false; inFlight = null;
        }
        private CompletableFuture<Void> capture(boolean force) {
            SqlDraftSaveState.Ticket ticket = state.capture(elapsed.getAsLong(), force);
            if (ticket == null) return inFlight == null ? CompletableFuture.completedFuture(null) : inFlight;
            SqlDraft draft;
            try {
                long at = wall.getAsLong(); draft = source.capture(id, at);
                if (draft == null || !id.equals(draft.id()) || draft.modifiedAt() != at) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                state.failed(ticket); changed = true; inFlight = refused(FailureReason.CAPTURE); return inFlight;
            }
            offered = true; changed = false;
            CompletableFuture<Void> publication = queue.save(draft);
            inFlight = publication.handle((unused, failure) -> {
                if (failure != null) {
                    if (structural(failure)) stop();
                    throw new CompletionException(new Failure(FailureReason.WRITE));
                }
                return null;
            });
            publication.whenComplete((unused, failure) -> post(() -> {
                if (detached) return;
                if (failure == null) state.succeeded(ticket, draft.modifiedAt());
                else if (state.failed(ticket)) changed = true;
            }));
            return inFlight;
        }
    }

    public CompletableFuture<ManagementResult> clear() {
        return manage(null, () -> { backend.clear(); return null; }, null);
    }
    public CompletableFuture<ManagementResult> delete(UUID id) {
        Objects.requireNonNull(id);
        return manage(Set.of(id), () -> { backend.delete(id); return null; }, null);
    }
    public CompletableFuture<ManagementResult> refresh() {
        return manage(Set.of(), () -> { backend.prune(wall.getAsLong(), Set.copyOf(openIds)); return null; }, null);
    }
    public CompletableFuture<ManagementResult> setEnabled(boolean enabled) {
        return manage(Set.of(), () -> { backend.setEnabled(enabled); return null; }, enabled);
    }

    private CompletableFuture<ManagementResult> manage(Set<UUID> resetIds, Callable<Void> action, Boolean enabled) {
        active();
        if (busy) return refused(FailureReason.BUSY);
        if (faulted.get()) return refused(FailureReason.UNAVAILABLE);
        busy = true;
        if (enabled != null) {
            admitted.set(false); mode = Mode.PAUSED;
            handles.values().forEach(handle -> handle.state.pause(false));
        } else if (resetIds == null) handles.values().forEach(Handle::reset);
        else for (UUID id : resetIds) { Handle handle = handles.get(id); if (handle != null) handle.reset(); }
        Callable<ManagementResult> operation = () -> inspect(action);
        CompletableFuture<ManagementResult> result = enabled != null || resetIds == null
                ? queue.barrierAll(operation) : queue.barrier(resetIds, operation);
        CompletableFuture<ManagementResult> exposed = new CompletableFuture<>();
        result.whenComplete((outcome, failure) -> {
            if (failure != null) stop();
            boolean posted = post(() -> {
                busy = false; lastManagementResult = outcome;
                if (enabled != null && failure == null && outcome.succeeded() && outcome.snapshot() != null
                        && outcome.snapshot().writable() && outcome.snapshot().protectionEnabled() == enabled && !faulted.get()) {
                    mode = enabled ? Mode.ENABLED : Mode.DISABLED; admitted.set(enabled);
                    if (enabled) resume(true);
                } else if (enabled == null && outcome != null && outcome.snapshot() != null
                        && !outcome.snapshot().protectionEnabled() && mode == Mode.ENABLED) {
                    admitted.set(false); mode = Mode.DISABLED;
                    handles.values().forEach(handle -> handle.state.pause(false));
                }
            });
            if (failure != null || !posted) exposed.completeExceptionally(new Failure(FailureReason.UNAVAILABLE));
            else exposed.complete(outcome);
        });
        return exposed.copy();
    }

    public CompletableFuture<Void> shutdown() {
        owner(); if (shutdown != null) return shutdown.copy();
        closing = true;
        shutdown = queue.drainAndClose().handleAsync((unused, failure) -> {
            admitted.set(false);
            try { if (backend != null) backend.close(); }
            catch (IOException closeFailure) { throw new CompletionException(new Failure(FailureReason.SHUTDOWN)); }
            if (failure != null) throw new CompletionException(new Failure(FailureReason.SHUTDOWN));
            return null;
        });
        return shutdown.copy();
    }

    private ManagementResult inspect(Callable<Void> action) {
        boolean success = !faulted.get();
        if (success) try { action.call(); }
        catch (Exception failure) { success = false; if (structural(failure)) stop(); }
        SqlDraftStore.Snapshot snapshot = null;
        try { snapshot = backend.snapshot(); if (!snapshot.writable()) { success = false; stop(); } }
        catch (IOException failure) { success = false; if (structural(failure)) stop(); }
        return new ManagementResult(success, snapshot);
    }

    private void resume(boolean recapture) {
        for (Handle handle : handles.values()) {
            if (recapture) { handle.eligible = handle.offered || handle.source.hasText(); handle.changed = handle.eligible; }
            if (mode == Mode.ENABLED) handle.state.resume(elapsed.getAsLong(), handle.eligible && handle.changed);
            else handle.state.pause(false);
        }
    }
    private void write(SqlDraft draft) throws IOException {
        if (!admitted.get()) throw new Failure(FailureReason.UNAVAILABLE);
        try { backend.save(draft); }
        catch (IOException | RuntimeException failure) { if (structural(failure)) stop(); throw failure; }
    }
    private void stop() {
        admitted.set(false);
        if (faulted.compareAndSet(false, true)) queue.barrierAll(() -> null);
    }
    private boolean post(Runnable action) {
        try { ui.execute(() -> { if (!closing) { owner(); action.run(); } }); return true; }
        catch (RuntimeException rejected) { stop(); return false; }
    }
    private FailureReason modeReason() {
        return switch (mode()) {
            case INITIALIZING -> FailureReason.INITIALIZING;
            case PAUSED -> FailureReason.PAUSED;
            case CLOSED -> FailureReason.CLOSED;
            default -> FailureReason.UNAVAILABLE;
        };
    }
    private static boolean structural(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) return structural(error.getCause());
        if (error instanceof SqlDraftDirectory.Failure failure) return switch (failure.stage()) {
            case OPEN, BUSY, CLOSED, UNSAFE, SCAN_LIMIT, CLEANUP, CLOSE -> true;
            default -> false;
        };
        if (error instanceof SqlDraftStore.Failure failure) return switch (failure.code()) {
            case UNAVAILABLE, DISABLED, PREFERENCE_CORRUPT -> true;
            default -> false;
        };
        if (error instanceof SqlDraftWriteQueue.Failure failure)
            return failure.reason() == SqlDraftWriteQueue.Reason.REJECTED || failure.reason() == SqlDraftWriteQueue.Reason.CLOSED;
        return error instanceof RuntimeException || error instanceof Error;
    }
    private static <T> CompletableFuture<T> refused(FailureReason reason) {
        return CompletableFuture.failedFuture(new Failure(reason));
    }
    private void owner() { if (!isUi.getAsBoolean()) throw new IllegalStateException("Draft UI thread required"); }
    private void active() { owner(); if (closing) throw new IllegalStateException("Draft runtime closed"); }
}
