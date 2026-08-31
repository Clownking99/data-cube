package com.datacube.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceRuntimeTest {
    @TempDir Path temp;
    static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    static final long WALL = 1788000000000L;
    static SqlWorkspace layout(long at) {
        return new SqlWorkspace(at, List.of(new SqlWorkspace.Entry(A, 7, 2)), A);
    }
    static SqlDraft draft(UUID id, String text) {
        return new SqlDraft(id, WALL, null, null, null, null, text);
    }
    final class Fixture implements AutoCloseable {
        final Path path = temp.resolve(UUID.randomUUID().toString());
        final ArrayDeque<Runnable> diskTasks = new ArrayDeque<>(), uiTasks = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        boolean onUi = true, rejectDisk, rejectUi;
        IOException workspaceFailure, managementFailure;
        Runnable beforeWorkspaceWrite = () -> { };
        SqlDraftStore store;
        final SqlDraftCoordinator runtime = new SqlDraftCoordinator(() -> {
            assertFalse(onUi);
            store = SqlDraftStore.open(path);
            return new SqlDraftCoordinator.Backend() {
                public void save(SqlDraft value) throws IOException {
                    assertFalse(onUi); events.add("draft"); store.save(value);
                }
                public SqlDraftStore.Snapshot snapshot() throws IOException { return store.snapshot(); }
                public void setEnabled(boolean enabled) throws IOException { store.setEnabled(enabled); }
                public void clear() throws IOException { store.clearRecoverable(); }
                public void delete(UUID id) throws IOException { store.delete(id); }
                public void prune(long now, Set<UUID> ids) throws IOException { store.pruneExpired(now, ids); }
                public void close() throws IOException { events.add("close"); store.close(); }
                public SqlWorkspaceStore.Snapshot workspaceSnapshot() throws IOException {
                    assertFalse(onUi); return store.workspaceSnapshot();
                }
                public void saveWorkspace(SqlWorkspace value) throws IOException {
                    assertFalse(onUi); events.add("workspace"); beforeWorkspaceWrite.run();
                    if (workspaceFailure != null) throw workspaceFailure;
                    store.saveWorkspace(value);
                }
                public void setWorkspaceEnabled(boolean enabled) throws IOException {
                    assertFalse(onUi); if (managementFailure != null) throw managementFailure;
                    store.setWorkspaceEnabled(enabled);
                }
                public boolean clearWorkspace() throws IOException {
                    assertFalse(onUi); if (managementFailure != null) throw managementFailure;
                    events.add("clearWorkspace"); return store.clearWorkspace();
                }
            };
        }, action -> { if (rejectDisk) throw new RejectedExecutionException("private disk"); diskTasks.add(action); },
                action -> { if (rejectUi) throw new RejectedExecutionException("private ui"); uiTasks.add(action); },
                () -> onUi, () -> 0, () -> WALL);
        void disk() {
            onUi = false;
            try { while (!diskTasks.isEmpty()) diskTasks.remove().run(); }
            finally { onUi = true; }
        }
        void ui() { while (!uiTasks.isEmpty()) uiTasks.remove().run(); }
        void cycle() { disk(); ui(); }
        void ready() { cycle(); assertEquals(SqlDraftCoordinator.Mode.ENABLED, runtime.mode()); }
        void seed() throws IOException { store.save(draft(A, "old text")); store.saveWorkspace(layout(10)); }
        SqlDraftCoordinator.Handle handle(UUID id, String text) {
            return runtime.attach(id, null, new SqlDraftCoordinator.Source() {
                public boolean hasText() { assertTrue(onUi); return !text.isEmpty(); }
                public SqlDraft capture(UUID key, long at) {
                    assertTrue(onUi); return new SqlDraft(key, at, null, null, null, null, text);
                }
            });
        }
        public void close() throws Exception {
            rejectUi = false;
            var closed = runtime.shutdown(); disk();
            try { closed.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException failure) { if (!rejectDisk) throw failure; }
            ui();
        }
    }
    static Throwable failure(CompletableFuture<?> future) {
        assertTrue(future.isDone(), "outcome must settle");
        Throwable cause = assertThrows(CompletionException.class, future::join).getCause();
        assertNull(cause.getCause());
        assertFalse(cause.getMessage().contains("private"));
        return cause;
    }
    static void reason(SqlDraftCoordinator.FailureReason reason, CompletableFuture<?> future) {
        assertEquals(reason, assertInstanceOf(SqlDraftCoordinator.Failure.class, failure(future)).reason());
    }
    static void code(SqlWorkspaceStore.FailureCode code, CompletableFuture<?> future) {
        assertEquals(code, assertInstanceOf(SqlWorkspaceStore.Failure.class, failure(future)).code());
    }

    @Test void untouchedStartupReadAndShutdownNeverCreateLayoutFiles() throws Exception {
        Path path;
        try (Fixture f = new Fixture()) {
            path = f.path; f.ready();
            var read = f.runtime.workspaceSnapshot(); assertFalse(read.isDone()); f.cycle();
            assertEquals(SqlWorkspaceStore.Status.ABSENT, read.join().status());
            assertTrue(read.join().recordingEnabled()); assertTrue(f.events.isEmpty());
        }
        assertFalse(Files.exists(path.resolve("workspace.bin")));
        assertFalse(Files.exists(path.resolve("workspace-preferences.bin")));
    }
    @Test void saveRunsOffUiAndOnlySettlesAfterDiskAndUiDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var saved = f.runtime.saveWorkspace(layout(20));
            assertFalse(saved.isDone()); assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            f.disk(); assertFalse(saved.isDone()); assertEquals(layout(20), f.store.workspaceSnapshot().workspace());
            f.ui(); saved.join(); assertEquals(List.of("workspace"), f.events);
        }
    }
    @Test void singleOutstandingSaveIsBoundedAndCallerCancellationDoesNotCancelPublication() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var first = f.runtime.saveWorkspace(layout(10)); assertTrue(first.cancel(false));
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.saveWorkspace(layout(20)));
            f.cycle(); assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            var next = f.runtime.saveWorkspace(layout(30)); f.cycle(); next.join();
            assertEquals(layout(30), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void clearInvalidatesQueuedLayoutButPreservesQueuedDraft() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var body = f.handle(B, "latest body").flush();
            var old = f.runtime.saveWorkspace(layout(20)); var clear = f.runtime.clearWorkspace();
            assertTrue(f.runtime.managementPending()); f.disk();
            assertFalse(clear.isDone()); assertTrue(f.runtime.managementPending()); f.ui();
            body.join(); reason(SqlDraftCoordinator.FailureReason.CANCELLED, old); assertTrue(clear.join());
            assertFalse(f.runtime.managementPending());
            assertEquals(new SqlWorkspace(0, List.of(), null), f.store.workspaceSnapshot().workspace());
            assertTrue(f.store.snapshot().drafts().contains(draft(B, "latest body")));
            assertEquals(List.of("draft", "clearWorkspace"), f.events);
        }
    }
    @Test void workspaceDisableCancelsQueuedLayoutWithoutDisablingDraftProtection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var old = f.runtime.saveWorkspace(layout(20));
            var disabled = f.runtime.setWorkspaceEnabled(false); f.cycle(); disabled.join();
            reason(SqlDraftCoordinator.FailureReason.CANCELLED, old);
            assertFalse(f.store.workspaceSnapshot().recordingEnabled());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            var refused = f.runtime.saveWorkspace(layout(30)); f.cycle();
            code(SqlWorkspaceStore.FailureCode.DISABLED, refused);
            assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
            var body = f.handle(B, "body").flush(); f.cycle(); body.join();
            assertTrue(f.store.snapshot().drafts().contains(draft(B, "body")));
            var enabled = f.runtime.setWorkspaceEnabled(true); f.cycle(); enabled.join();
            var next = f.runtime.saveWorkspace(layout(40)); f.cycle(); next.join();
            assertEquals(layout(40), f.store.workspaceSnapshot().workspace());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"CLEAR", "DELETE", "DISABLE"})
    void draftManagementInvalidatesOldWorkspace(String operation) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var old = f.runtime.saveWorkspace(layout(20));
            var managed = switch (operation) {
                case "CLEAR" -> f.runtime.clear(); case "DELETE" -> f.runtime.delete(A);
                default -> f.runtime.setEnabled(false);
            };
            f.cycle(); assertTrue(managed.join().succeeded());
            reason(SqlDraftCoordinator.FailureReason.CANCELLED, old);
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            assertFalse(f.events.contains("workspace"));
            if (operation.equals("DISABLE")) {
                reason(SqlDraftCoordinator.FailureReason.DISABLED, f.runtime.saveWorkspace(layout(30)));
                var read = f.runtime.workspaceSnapshot(); f.cycle(); assertEquals(layout(10), read.join().workspace());
            }
        }
    }
    @Test void refreshDoesNotInvalidateAcceptedLayout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var save = f.runtime.saveWorkspace(layout(20)); var refresh = f.runtime.refresh();
            f.cycle(); save.join(); assertTrue(refresh.join().succeeded());
            assertEquals(layout(20), f.store.workspaceSnapshot().workspace());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"CLEAR", "PREFERENCE"})
    void failedManagementStillInvalidatesOldSaveAndRetainsOldFiles(String operation) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); f.managementFailure = new IOException("private management");
            var old = f.runtime.saveWorkspace(layout(20));
            CompletableFuture<?> managed = operation.equals("CLEAR") ? f.runtime.clearWorkspace() : f.runtime.setWorkspaceEnabled(false);
            f.cycle(); reason(SqlDraftCoordinator.FailureReason.CANCELLED, old);
            reason(SqlDraftCoordinator.FailureReason.WRITE, managed);
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            assertTrue(f.store.workspaceSnapshot().recordingEnabled()); assertFalse(f.runtime.managementPending());
            f.managementFailure = null;
            var retry = f.runtime.saveWorkspace(layout(30)); f.cycle(); retry.join();
            assertEquals(layout(30), f.store.workspaceSnapshot().workspace());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"workspace.bin", "workspace-preferences.bin"})
    void workspaceCorruptionDoesNotStopDraftProtection(String name) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); Files.write(f.path.resolve(name), new byte[]{1, 2});
            var refused = f.runtime.saveWorkspace(layout(20)); f.cycle();
            code(name.equals("workspace.bin") ? SqlWorkspaceStore.FailureCode.PROTECTED_WORKSPACE
                    : SqlWorkspaceStore.FailureCode.PREFERENCE_CORRUPT, refused);
            assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
            var body = f.handle(B, "new").flush(); f.cycle(); body.join();
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(f.path.resolve(name)));
            assertTrue(f.store.snapshot().drafts().contains(draft(B, "new")));
        }
    }
    @Test void cleanupStopsSharedWriterBeforeLaterDraftCanPublish() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); f.workspaceFailure = new SqlDraftDirectory.Failure(SqlDraftDirectory.Stage.CLEANUP);
            var save = f.runtime.saveWorkspace(layout(20)); var body = f.handle(B, "new").flush(); f.cycle();
            reason(SqlDraftCoordinator.FailureReason.CLEANUP, save); assertTrue(body.isCompletedExceptionally());
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertEquals(SqlDraftCoordinator.FailureReason.CLEANUP, f.runtime.unavailableReason());
            reason(SqlDraftCoordinator.FailureReason.CLEANUP, f.runtime.clearWorkspace());
            assertEquals(List.of(draft(A, "old text")), f.store.snapshot().drafts());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace()); assertEquals(List.of("workspace"), f.events);
        }
    }
    @Test void invalidDraftPreferenceStopsWorkspaceAndSubsequentDraftWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); Files.write(f.path.resolve("preferences.bin"), new byte[]{1, 2});
            var save = f.runtime.saveWorkspace(layout(20)); f.cycle();
            code(SqlWorkspaceStore.FailureCode.DRAFT_PROTECTION_UNAVAILABLE, save);
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertTrue(f.handle(B, "new").flush().isCompletedExceptionally());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void ordinaryWriteFailurePreservesOldLayoutAndAllowsExplicitRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); f.workspaceFailure = new IOException("private write");
            var save = f.runtime.saveWorkspace(layout(20)); f.cycle(); reason(SqlDraftCoordinator.FailureReason.WRITE, save);
            assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            f.workspaceFailure = null; var retry = f.runtime.saveWorkspace(layout(30)); f.cycle(); retry.join();
            assertEquals(layout(30), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void acceptedSaveDrainsAndCompletesEvenWhenShutdownPrecedesUiDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var save = f.runtime.saveWorkspace(layout(20)); var closed = f.runtime.shutdown();
            f.disk(); closed.get(5, TimeUnit.SECONDS); assertFalse(save.isDone()); f.ui(); save.join();
            assertEquals(List.of("workspace", "close"), f.events);
            assertThrows(IllegalStateException.class, f.runtime::workspaceSnapshot);
            try (SqlDraftStore reopened = SqlDraftStore.open(f.path)) {
                assertEquals(layout(20), reopened.workspaceSnapshot().workspace());
            }
        }
    }
    @Test void wrongThreadInitializingNullAndOverlappingManagementAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.workspaceSnapshot());
            f.onUi = false;
            try { assertThrows(IllegalStateException.class, f.runtime::workspaceSnapshot); }
            finally { f.onUi = true; }
            f.ready(); code(SqlWorkspaceStore.FailureCode.INVALID_WORKSPACE, f.runtime.saveWorkspace(null));
            var enabled = f.runtime.setWorkspaceEnabled(false);
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.clear());
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.workspaceSnapshot());
            f.cycle(); enabled.join(); assertFalse(f.runtime.managementPending());
        }
    }
    @Test void writerRejectionSettlesOutcomeAndMakesRuntimeUnavailable() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.rejectDisk = true; var save = f.runtime.saveWorkspace(layout(20)); f.ui();
            reason(SqlDraftCoordinator.FailureReason.UNAVAILABLE, save);
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertFalse(Files.exists(f.path.resolve("workspace.bin")));
        }
    }
    @Test void uiRejectionSettlesOutcomeAndStopsFurtherWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.rejectUi = true; var save = f.runtime.saveWorkspace(layout(20)); f.disk();
            reason(SqlDraftCoordinator.FailureReason.UNAVAILABLE, save);
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertEquals(layout(20), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void runningSaveFinishesBeforeClearRatherThanResurrectingAfterIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); AtomicReference<CompletableFuture<Boolean>> clear = new AtomicReference<>();
            f.beforeWorkspaceWrite = () -> {
                f.onUi = true;
                try { clear.set(f.runtime.clearWorkspace()); } finally { f.onUi = false; }
            };
            var save = f.runtime.saveWorkspace(layout(20)); f.cycle(); save.join(); assertTrue(clear.get().join());
            assertEquals(List.of("workspace", "clearWorkspace"), f.events);
            assertEquals(new SqlWorkspace(0, List.of(), null), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void publicPathOwnerUsesSameStoreForReadWritePreferenceAndClear() throws Exception {
        Path path = temp.resolve("public-owner"); ArrayDeque<Runnable> disk = new ArrayDeque<>(), ui = new ArrayDeque<>();
        AtomicBoolean onUi = new AtomicBoolean(true);
        SqlDraftCoordinator runtime = new SqlDraftCoordinator(path, disk::add, ui::add, onUi::get, () -> 0, () -> WALL);
        Runnable cycle = () -> {
            onUi.set(false);
            try { while (!disk.isEmpty()) disk.remove().run(); } finally { onUi.set(true); }
            while (!ui.isEmpty()) ui.remove().run();
        };
        try {
            cycle.run(); var save = runtime.saveWorkspace(layout(20)); cycle.run(); save.join();
            assertThrows(SqlDraftDirectory.Failure.class, () -> SqlDraftStore.open(path));
            var read = runtime.workspaceSnapshot(); cycle.run(); assertEquals(layout(20), read.join().workspace());
            var disable = runtime.setWorkspaceEnabled(false); cycle.run(); disable.join();
            var clear = runtime.clearWorkspace(); cycle.run(); assertTrue(clear.join());
        } finally { var closed = runtime.shutdown(); cycle.run(); closed.get(5, TimeUnit.SECONDS); }
        try (SqlDraftStore reopened = SqlDraftStore.open(path)) {
            assertFalse(reopened.workspaceSnapshot().recordingEnabled());
            assertEquals(new SqlWorkspace(0, List.of(), null), reopened.workspaceSnapshot().workspace());
        }
    }
}
