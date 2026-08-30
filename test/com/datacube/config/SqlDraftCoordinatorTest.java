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
            assertEquals(SqlDraftCoordinator.FailureReason.CLEANUP, f.runtime.unavailableReason());
            assertEquals(SqlDraftCoordinator.FailureReason.CLEANUP, one.status().failureReason());
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
