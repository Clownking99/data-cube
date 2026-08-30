# SQL draft coordinator implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect draft timing, bounded writes, preferences and lifecycle into one application-owned runtime with deterministic integration tests.

**Architecture:** UI owns handles and calls `pulse()` from one application timer; only due/forced captures read SQL. One queue owns the blocking backend. Atomic admission stops structural failures before the next write; public futures settle without waiting for UI callbacks. This task implements the runtime, not the editor/dialog wiring.

**Tech Stack:** Java 25, JUnit Jupiter 5.11.3, existing Gradle wrapper; no dependencies.

## Global Constraints

- Input events only mark timing/eligibility; snapshot strings are read on FX at due/force time, not captured into an unbounded per-keystroke queue.
- A flush of a captured revision completes in the background after its publication (or cancellation/failure), regardless of whether FX has processed the status callback. A closing worker may await it; FX may not block on it.
- Clearing stored drafts resets per-handle eligibility and generation immediately. An unedited open editor must not recreate its old text on force flush/close. A subsequent user edit or explicit re-enable can qualify it again.
- Disable pauses admission in the coordinator immediately, cancels pending saves, then appends strict `store.setEnabled(false)`. Successful completion confirms permanent disable; failure retains a paused current session.
- Structural loss (root/lock identity, scan limit, CLEANUP, closed storage) pauses the coordinator and cancels pending saves before another write can begin; this classification belongs on the writer-result path, not only a delayed FX callback.
- No database, provider, credentials, real user history or default user profile access. Tests use owned temporary paths and synthetic text only. Never access `.testagent/`.
- No main merge, push, tag or release in this runtime task. Full P1 editor/recovery acceptance remains required.

## Task 1: Application-owned draft runtime

**Files:**
- Create `src/com/datacube/config/SqlDraftCoordinator.java`.
- Create `test/com/datacube/config/SqlDraftCoordinatorTest.java`.

**Interfaces:** Consumes existing `SqlDraft`, `SqlDraftSaveState`, `SqlDraftWriteQueue`, `SqlDraftStore` and `SqlDraftDirectory.Failure`. Public source/handle, mode/status, management and shutdown interfaces are defined in the complete implementation below. The application must pass a non-inline background writer and `Platform::runLater`, UI thread predicate, elapsed milliseconds relative to startup, and wall clock. One UI timer calls `pulse()`; shutdown the caller's executor only after `shutdown()` settles. `detach()` is for successful finalization/construction abort, never a rejected close. Runtime shutdown assumes managed-tab guards have already settled.

**Decisions:** An unavailable runtime is fail-closed until application restart; ordinary save errors support `Handle.retry()`. Management results contain the actual refreshed snapshot (nullable if unreadable) plus success, never raw exception text. Recovery UI disables restored-tab creation while management is pending, closing the prune/restore race; normal new editors remain available. The backend factory is a package seam for deterministic fault injection, not a database abstraction. Shutdown uses the common pool solely to release the backend after queue termination, including writer-executor rejection; it never submits SQL there. Caller-owned UI and writer executors are not closed by this class.

- [x] **Step 1: Write the tests below and this intentionally failing compiled API.** Do not install real behavior yet.

```java
package com.datacube.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class SqlDraftCoordinator {
    public enum Mode { INITIALIZING, ENABLED, DISABLED, PAUSED, UNAVAILABLE, CLOSED }
    public enum SaveStatus { EMPTY, WAITING, SAVING, SAVED, FAILED }
    public enum FailureReason { INITIALIZING, PAUSED, UNAVAILABLE, CLOSED, BUSY, CAPTURE, WRITE, SHUTDOWN }
    public record Status(Mode mode, SaveStatus saveStatus, Long savedAt) { }
    public record ManagementResult(boolean succeeded, SqlDraftStore.Snapshot snapshot) { }
    public static final class Failure extends IOException {
        public FailureReason reason() { throw missing(); }
    }
    public interface Source { boolean hasText(); SqlDraft capture(UUID id, long modifiedAt); }
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
    public SqlDraftCoordinator(Path path, Executor worker, Executor ui, BooleanSupplier isUi,
            LongSupplier elapsed, LongSupplier wall) { throw missing(); }
    SqlDraftCoordinator(Factory factory, Executor worker, Executor ui, BooleanSupplier isUi,
            LongSupplier elapsed, LongSupplier wall) { throw missing(); }
    public Mode mode() { throw missing(); }
    public ManagementResult lastManagementResult() { throw missing(); }
    public Handle attach(UUID id, Long savedAt, Source source) { throw missing(); }
    public void pulse() { throw missing(); }
    public CompletableFuture<ManagementResult> clear() { throw missing(); }
    public CompletableFuture<ManagementResult> delete(UUID id) { throw missing(); }
    public CompletableFuture<ManagementResult> refresh() { throw missing(); }
    public CompletableFuture<ManagementResult> setEnabled(boolean enabled) { throw missing(); }
    public CompletableFuture<Void> shutdown() { throw missing(); }
    public final class Handle {
        public UUID id() { throw missing(); }
        public Status status() { throw missing(); }
        public void edited() { throw missing(); }
        public void retry() { throw missing(); }
        public CompletableFuture<Void> flush() { throw missing(); }
        public void detach() { throw missing(); }
    }
    private static UnsupportedOperationException missing() {
        return new UnsupportedOperationException("Draft coordinator not implemented");
    }
}
```

```java
package com.datacube.config;

import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.config.SqlDraftCoordinator.Mode.*;
import static com.datacube.config.SqlDraftCoordinator.SaveStatus.*;

class SqlDraftCoordinatorTest {
    @TempDir Path temp;
    private static final long WALL = 1788000000000L;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static UUID id(int n) { return new UUID(0, n); }
    private static SqlDraft value(int n, long at, String sql) {
        return new SqlDraft(id(n), at, "synthetic", DbType.ORACLE, "Synthetic", " raw schema ", sql);
    }
    private final class Fixture implements AutoCloseable {
        final ArrayDeque<Runnable> disk = new ArrayDeque<>(), ui = new ArrayDeque<>();
        final Path path = temp.resolve(UUID.randomUUID().toString());
        final Backend backend = new Backend();
        boolean onUi = true, reject, failOpen;
        long elapsed;
        final SqlDraftCoordinator runtime = new SqlDraftCoordinator(() -> {
            assertFalse(onUi);
            if (failOpen) throw new IOException("synthetic private path");
            backend.store = SqlDraftStore.open(path);
            return backend;
        }, task -> { if (reject) throw new RejectedExecutionException(); disk.add(task); },
                ui::add, () -> onUi, () -> elapsed, () -> WALL + elapsed);
        final class Backend implements SqlDraftCoordinator.Backend {
            SqlDraftStore store;
            IOException saveFailure;
            boolean preferenceFailure, partialClear, partialPrune;
            int writes;
            Set<UUID> protectedIds = Set.of();
            @Override public void save(SqlDraft draft) throws IOException {
                assertFalse(onUi); writes++;
                if (saveFailure != null) throw saveFailure;
                store.save(draft);
            }
            @Override public SqlDraftStore.Snapshot snapshot() throws IOException { return store.snapshot(); }
            @Override public void setEnabled(boolean enabled) throws IOException {
                assertFalse(onUi);
                if (preferenceFailure) throw new IOException("synthetic secret");
                store.setEnabled(enabled);
            }
            @Override public void clear() throws IOException {
                if (partialClear) {
                    store.delete(store.snapshot().drafts().getFirst().id());
                    throw new IOException("synthetic partial deletion");
                }
                store.clearRecoverable();
            }
            @Override public void delete(UUID id) throws IOException { store.delete(id); }
            @Override public void prune(long now, Set<UUID> ids) throws IOException {
                protectedIds = Set.copyOf(ids);
                if (partialPrune) {
                    store.delete(store.snapshot().drafts().getFirst().id());
                    throw new IOException("synthetic partial prune");
                }
                store.pruneExpired(now, ids);
            }
            @Override public void close() throws IOException { store.close(); }
        }
        final class Source implements SqlDraftCoordinator.Source {
            String text;
            int captures;
            boolean invalid;
            Source(String text) { this.text = text; }
            @Override public boolean hasText() { assertTrue(onUi); return !text.isEmpty(); }
            @Override public SqlDraft capture(UUID id, long at) {
                assertTrue(onUi); captures++;
                if (invalid) throw new IllegalStateException("synthetic secret");
                return new SqlDraft(id, at, "synthetic", DbType.ORACLE, "Synthetic", " raw schema ", text);
            }
        }
        void disk() {
            onUi = false;
            try { while (!disk.isEmpty()) disk.remove().run(); }
            finally { onUi = true; }
        }
        void ui() { while (!ui.isEmpty()) ui.remove().run(); }
        void ready() { disk(); ui(); assertEquals(ENABLED, runtime.mode()); }
        SqlDraftCoordinator.Handle attach(int n, Source source) { return runtime.attach(id(n), null, source); }
        List<SqlDraft> saved() throws IOException { return backend.store.snapshot().drafts(); }
        @Override public void close() throws Exception {
            CompletableFuture<Void> closed = runtime.shutdown(); disk(); closed.get(5, TimeUnit.SECONDS); ui();
        }
    }

    @Test void initializationKeepsOnlyLatestInputAndNeverPromisesAFlush() throws Exception {
        try (Fixture f = new Fixture()) {
            var source = f.new Source("first"); var handle = f.attach(1, source);
            for (int i = 0; i < 20; i++) { source.text = "latest " + i; handle.edited(); }
            assertEquals(INITIALIZING, f.runtime.mode()); assertEquals(0, source.captures);
            assertTrue(handle.flush().isCompletedExceptionally());
            f.ready(); f.elapsed = 999; f.runtime.pulse(); assertEquals(0, source.captures);
            f.elapsed = 1000; f.runtime.pulse(); assertEquals(1, source.captures);
            f.disk(); assertEquals("latest 19", f.saved().getFirst().sql());
            assertEquals(" raw schema ", f.saved().getFirst().schema());
        }
    }

    @Test void continuousEditsCaptureAtTenSecondsAndSavedStatusWaitsForLatestRevision() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("a"); var handle = f.attach(1, source);
            for (int i = 1; i <= 20; i++) { f.elapsed = i * 500; source.text = "v" + i; handle.edited(); f.runtime.pulse(); }
            assertEquals(1, source.captures); assertEquals(SAVING, handle.status().saveStatus());
            source.text = "newer"; handle.edited(); f.disk(); f.ui();
            assertEquals(WAITING, handle.status().saveStatus());
            var flushed = handle.flush(); f.disk();
            assertTrue(flushed.isDone()); assertFalse(flushed.isCompletedExceptionally());
            assertEquals(SAVING, handle.status().saveStatus());
            f.ui(); assertEquals(SAVED, handle.status().saveStatus());
            assertEquals("newer", f.saved().getFirst().sql());
        }
    }

    @Test void flushSharesPendingPublicationAndExternalCancellationCannotCancelIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("exact\r\n中文😀"); var handle = f.attach(1, source);
            var first = handle.flush(); first.cancel(false); var second = handle.flush();
            assertFalse(second.isDone()); assertEquals(1, source.captures);
            f.disk(); second.get(1, TimeUnit.SECONDS);
            assertEquals("exact\r\n中文😀", f.saved().getFirst().sql());
            f.ui(); assertEquals(SAVED, handle.status().saveStatus());
        }
    }

    @Test void emptyBeforeFirstCaptureDoesNotSaveButEmptyAfterOfferReplacesOldText() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("temporary"); var handle = f.attach(1, source);
            source.text = ""; handle.edited(); f.elapsed = 1000; f.runtime.pulse();
            assertEquals(0, source.captures); assertTrue(handle.flush().isDone());
            source.text = "old"; handle.edited(); var old = handle.flush();
            source.text = ""; handle.edited(); var empty = handle.flush();
            assertTrue(old.isCompletedExceptionally()); f.disk(); empty.get(1, TimeUnit.SECONDS);
            assertEquals("", f.saved().getFirst().sql());
        }
    }

    @Test void clearCancelsOldSnapshotsAndCloseDoesNotRecreateUneditedText() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("old"); var handle = f.attach(1, source);
            var old = handle.flush(); var clear = f.runtime.clear();
            assertTrue(old.isCompletedExceptionally()); assertTrue(handle.flush().isDone());
            f.disk(); assertTrue(clear.get().succeeded()); f.ui();
            assertTrue(f.saved().isEmpty()); assertEquals(1, source.captures);
            source.text = "new"; handle.edited(); handle.flush(); f.disk();
            assertEquals("new", f.saved().getFirst().sql());
        }
    }

    @Test void postClearEditIsOrderedAfterClearAndDeleteOnlyInvalidatesTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var a = f.new Source("a"); var b = f.new Source("b");
            var one = f.attach(1, a); var two = f.attach(2, b);
            one.flush(); two.flush(); var clear = f.runtime.clear();
            a.text = "after"; one.edited(); one.flush(); f.disk(); f.ui();
            assertTrue(clear.get().succeeded()); assertEquals(List.of("after"), f.saved().stream().map(SqlDraft::sql).toList());
            b.text = "keep"; two.edited(); two.flush(); var deleted = f.runtime.delete(id(1));
            f.disk(); f.ui(); assertTrue(deleted.get().succeeded());
            assertEquals(List.of(id(2)), f.saved().stream().map(SqlDraft::id).toList());
            assertEquals(EMPTY, one.status().saveStatus()); assertEquals(SAVED, two.status().saveStatus());
        }
    }

    @Test void failedDisableStaysPausedUntilExplicitSuccessfulEnableRecapturesText() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("old"); var handle = f.attach(1, source);
            var write = handle.flush(); f.backend.preferenceFailure = true;
            var disable = f.runtime.setEnabled(false);
            assertEquals(PAUSED, f.runtime.mode()); assertTrue(write.isCompletedExceptionally());
            f.disk(); f.ui(); assertFalse(disable.get().succeeded()); assertEquals(PAUSED, f.runtime.mode());
            assertTrue(handle.flush().isCompletedExceptionally());
            source.text = "latest"; handle.edited(); f.elapsed = 30000; f.runtime.pulse(); f.disk();
            assertEquals(0, f.backend.writes); assertTrue(f.backend.store.snapshot().protectionEnabled());
            f.backend.preferenceFailure = false; var enable = f.runtime.setEnabled(true);
            assertEquals(PAUSED, f.runtime.mode()); f.disk(); f.ui(); assertTrue(enable.get().succeeded());
            handle.flush(); f.disk(); assertEquals("latest", f.saved().getFirst().sql());
        }
    }

    @Test void successfulDisablePersistsAndAllowsCloseWithoutClaimingSaved() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("unsaved"); var handle = f.attach(1, source);
            var disable = f.runtime.setEnabled(false); f.disk(); f.ui();
            assertTrue(disable.get().succeeded()); assertEquals(DISABLED, f.runtime.mode());
            assertFalse(f.backend.store.snapshot().protectionEnabled());
            assertTrue(handle.flush().isDone()); assertNull(handle.status().savedAt());
            assertTrue(f.saved().isEmpty());
        }
    }

    @Test void managementCompletionQueuesOwnerStateBeforeConsumerUiAction() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var disable = f.runtime.setEnabled(false);
            int[] observed = {0};
            disable.thenRun(() -> f.ui.add(() -> {
                assertEquals(DISABLED, f.runtime.mode());
                assertTrue(f.runtime.lastManagementResult().succeeded());
                observed[0]++;
            }));
            f.disk(); assertTrue(disable.isDone()); assertEquals(0, observed[0]);
            f.ui(); assertEquals(1, observed[0]);
        }
    }

    @Test void ordinaryWriteFailureKeepsCheckpointAndOnlyRetriesOnRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("old"); var handle = f.attach(1, source);
            handle.flush(); f.disk(); f.ui();
            f.backend.saveFailure = new IOException("synthetic private SQL"); source.text = "new"; handle.edited();
            var failed = handle.flush(); f.disk(); f.ui();
            assertTrue(failed.isCompletedExceptionally()); assertEquals(FAILED, handle.status().saveStatus());
            assertEquals("old", f.saved().getFirst().sql());
            for (int i = 0; i < 10; i++) { f.elapsed += 10000; f.runtime.pulse(); f.disk(); }
            assertEquals(2, f.backend.writes);
            f.backend.saveFailure = null; handle.retry(); f.runtime.pulse(); f.disk(); f.ui();
            assertEquals("new", f.saved().getFirst().sql()); assertEquals(SAVED, handle.status().saveStatus());
        }
    }

    @Test void structuralFailureCancelsOtherWritesBeforeUiProcessesAnyCallbacks() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var one = f.attach(1, f.new Source("one")); var two = f.attach(2, f.new Source("two"));
            f.backend.saveFailure = new SqlDraftDirectory.Failure(SqlDraftDirectory.Stage.CLEANUP);
            var a = one.flush(); var b = two.flush(); f.disk();
            assertTrue(a.isCompletedExceptionally()); assertTrue(b.isCompletedExceptionally());
            assertEquals(1, f.backend.writes); assertEquals(UNAVAILABLE, f.runtime.mode());
            one.edited(); f.elapsed = 20000; f.runtime.pulse(); f.disk();
            assertEquals(1, f.backend.writes); assertTrue(one.flush().isCompletedExceptionally());
        }
    }

    @Test void captureFailureIsSanitizedAndDoesNotTouchStorage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var source = f.new Source("sql"); var handle = f.attach(1, source); source.invalid = true;
            var failure = assertThrows(CompletionException.class, () -> handle.flush().join());
            assertEquals(SqlDraftCoordinator.FailureReason.CAPTURE, ((SqlDraftCoordinator.Failure) failure.getCause()).reason());
            assertNull(failure.getCause().getCause()); assertFalse(failure.toString().contains("secret"));
            assertEquals(FAILED, handle.status().saveStatus()); assertEquals(0, f.backend.writes);
        }
    }

    @Test void partialManagementFailureReturnsActualSurvivorsAndRejectsOverlap() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.backend.store.save(value(1, WALL, "one")); f.backend.store.save(value(2, WALL + 1, "two"));
            f.backend.partialClear = true; var clear = f.runtime.clear();
            assertTrue(f.runtime.refresh().isCompletedExceptionally()); f.disk(); f.ui();
            assertFalse(clear.get().succeeded());
            assertEquals(List.of(id(1)), clear.get().snapshot().drafts().stream().map(SqlDraft::id).toList());
            assertEquals(List.of(id(1)), f.saved().stream().map(SqlDraft::id).toList());
        }
    }

    @Test void startupAndRefreshPruneExpiredWhileProtectingAllOpenIds() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.createDirectory(f.path);
            try (var seed = SqlDraftStore.open(f.path)) {
                seed.save(value(1, WALL - WEEK, "expired")); seed.save(value(2, WALL - WEEK + 1, "live"));
            }
            f.ready(); assertEquals(List.of(id(2)), f.saved().stream().map(SqlDraft::id).toList());
            var source = f.new Source("live"); var restored = f.runtime.attach(id(2), WALL - WEEK + 1, source);
            f.elapsed = 1; var refreshed = f.runtime.refresh();
            assertThrows(IllegalStateException.class, () -> f.runtime.attach(id(3), WALL, f.new Source("restore")));
            f.disk(); f.ui(); assertTrue(refreshed.get().succeeded()); assertEquals(Set.of(id(2)), f.backend.protectedIds);
            assertEquals(0, source.captures); assertEquals(SAVED, restored.status().saveStatus());
            restored.detach(); var next = f.runtime.refresh(); f.disk(); f.ui();
            assertTrue(next.get().snapshot().drafts().isEmpty());
        }
    }

    @Test void rejectedWriterSettlesFlushAndShutdownStillReleasesLock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var handle = f.attach(1, f.new Source("one")); f.reject = true;
            assertTrue(handle.flush().isCompletedExceptionally()); assertEquals(UNAVAILABLE, f.runtime.mode());
            assertThrows(ExecutionException.class, () -> f.runtime.shutdown().get(5, TimeUnit.SECONDS));
            try (var reopened = SqlDraftStore.open(f.path)) { assertTrue(reopened.snapshot().drafts().isEmpty()); }
            f.reject = false;
        } catch (ExecutionException expectedShutdownFailure) {
            assertInstanceOf(SqlDraftCoordinator.Failure.class, expectedShutdownFailure.getCause());
        }
    }

    @Test void startupPartialPruneKeepsActualSurvivorsVisibleWithoutSuccessClaim() throws Exception {
        try (Fixture f = new Fixture()) {
            Files.createDirectory(f.path);
            try (var seed = SqlDraftStore.open(f.path)) {
                seed.save(value(1, WALL - WEEK, "one")); seed.save(value(2, WALL - WEEK + 1, "two"));
            }
            f.backend.partialPrune = true; f.ready();
            var result = f.runtime.lastManagementResult();
            assertFalse(result.succeeded());
            assertEquals(List.of(id(1)), result.snapshot().drafts().stream().map(SqlDraft::id).toList());
            assertEquals(List.of(id(1)), f.saved().stream().map(SqlDraft::id).toList());
        }
    }

    @Test void restoredCheckpointAndExternalDisableNeverTriggerImplicitWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.backend.store.save(value(1, WALL, "restored"));
            var source = f.new Source("restored"); var handle = f.runtime.attach(id(1), WALL, source);
            f.elapsed = 20000; f.runtime.pulse(); handle.flush();
            assertEquals(0, source.captures); assertEquals(SAVED, handle.status().saveStatus());
            f.backend.store.setEnabled(false); var refresh = f.runtime.refresh(); f.disk(); f.ui();
            assertTrue(refresh.get().succeeded()); assertEquals(DISABLED, f.runtime.mode());
            source.text = "changed"; handle.edited(); handle.flush(); f.disk();
            assertEquals(0, f.backend.writes); assertEquals("restored", f.saved().getFirst().sql());
        }
    }

    @Test void shutdownDrainsAcceptedSaveAndPreventsNewCaptureWhileCallbacksArePending() throws Exception {
        Fixture f = new Fixture(); f.ready(); var source = f.new Source("last"); var handle = f.attach(1, source);
        var flushed = handle.flush(); var shutdown = f.runtime.shutdown();
        assertEquals(CLOSED, f.runtime.mode()); assertThrows(IllegalStateException.class, handle::edited);
        f.disk(); shutdown.get(5, TimeUnit.SECONDS); flushed.get(1, TimeUnit.SECONDS); f.ui();
        try (var reopened = SqlDraftStore.open(f.path)) { assertEquals("last", reopened.snapshot().drafts().getFirst().sql()); }
    }

    @Test void openFailureNeverClaimsSavedAndOwnerThreadIsEnforced() throws Exception {
        try (Fixture f = new Fixture()) {
            f.failOpen = true; var handle = f.attach(1, f.new Source("text")); f.disk(); f.ui();
            assertEquals(UNAVAILABLE, f.runtime.mode()); assertTrue(handle.flush().isCompletedExceptionally());
            f.onUi = false;
            try { assertThrows(IllegalStateException.class, f.runtime::pulse); }
            finally { f.onUi = true; }
        }
    }

    @Test void publicFactoryCreatesFreshParentOnlyWhenBackgroundWorkRuns() throws Exception {
        ArrayDeque<Runnable> disk = new ArrayDeque<>(), ui = new ArrayDeque<>();
        Path path = temp.resolve("fresh/config/drafts");
        var runtime = new SqlDraftCoordinator(path, disk::add, ui::add, () -> true, () -> 0, () -> WALL);
        assertFalse(Files.exists(path)); while (!disk.isEmpty()) disk.remove().run(); while (!ui.isEmpty()) ui.remove().run();
        assertEquals(ENABLED, runtime.mode()); assertTrue(Files.isDirectory(path));
        var closed = runtime.shutdown(); while (!disk.isEmpty()) disk.remove().run(); closed.get(5, TimeUnit.SECONDS);
        try (var reopened = SqlDraftStore.open(path)) { assertTrue(reopened.snapshot().drafts().isEmpty()); }
    }
}
```

- [x] **Step 2: Run RED.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftCoordinatorTest --rerun-tasks --no-daemon --console=plain
```

Expected: 20 tests fail with the intentional constructor exception, not compilation errors. Record exact XML counts and one relevant failure before replacing the stub.

- [x] **Step 3: Replace stub with the complete implementation.**

```java
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
```

- [x] **Step 4: Focused GREEN and full regression.**

```powershell
.\gradlew.bat test --tests 'com.datacube.config.SqlDraft*Test' --rerun-tasks --no-daemon --console=plain
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```

Expected: focused draft suites pass; full suite exit 0, with only the existing three opt-in live integration skips. Disclose existing unchecked compiler note and Gradle informational notices rather than claiming pristine output. Record exact XML tests/passed/failures/errors/skips and all skipped names. No coverage percentage claim.

- [x] **Step 5: Self-review, exact two-file commit and report.**

```powershell
git diff --check
git add src/com/datacube/config/SqlDraftCoordinator.java test/com/datacube/config/SqlDraftCoordinatorTest.java
git commit -m "feat: coordinate SQL draft capture and lifecycle barriers"
```

Report RED/GREEN commands/output, full XML counts, exact files/commit, requirement-to-test matrix, concerns and UI integration still outstanding. Do not stage controller documentation or mark the complete P1 feature done.

## Self-review and acceptance boundary

Task complete: `6de52ab..533210c`, independent `draft_coordinator_review` Approved, no Critical/Important findings. Root fresh forced full regression: 144 suites, 1307 total, 1304 passed, 0 failures/errors, 3 existing live skips, exit0/39s. Source and test match the amended implementation/test blocks exactly. Known compiler informational note remains disclosed. No editor/UI/P1 completion claim.

- Timing, coalescing, clear/delete generations, close flush, strict disable/enable, startup, fault stop, management snapshots, pruning, resource release and owner confinement are exercised above with the real state/queue/store plus controllable dispatchers. Fault injection is confined to the backend boundary.
- The UI timer, editor text/schema listeners, close transaction integration, privacy copy, manager preview, connection-free restoration and actual FX/provider call-counter acceptance are separate next integration work, not proof claimed by these runtime tests.
- User delegated routine design choices; continue subagent-driven execution without another confirmation. P1 stays isolated until complete integrated review and acceptance.
