package com.datacube.config;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceStoreTest {
    @TempDir Path temp;
    static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    Path root() { return temp.resolve("drafts"); }
    static SqlWorkspace sample(long at) {
        return new SqlWorkspace(at, List.of(new SqlWorkspace.Entry(B, 7, 2), new SqlWorkspace.Entry(A, 0, 10)), A);
    }
    static SqlDraft draft() { return new SqlDraft(A, 10, null, null, null, null, " synthetic SQL "); }
    static byte[] pref(boolean enabled) {
        return ByteBuffer.allocate(9).putInt(0x44435750).putInt(1).put((byte) (enabled ? 1 : 0)).array();
    }

    @Test void absentReadHasNoWorkspaceFileSideEffects() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            var snapshot = store.workspaceSnapshot();
            assertEquals(SqlWorkspaceStore.Status.ABSENT, snapshot.status());
            assertNull(snapshot.workspace());
            assertTrue(snapshot.preferenceValid());
            assertTrue(snapshot.recordingEnabled());
            assertFalse(store.clearWorkspace());
            assertFalse(Files.exists(root().resolve("workspace.bin")));
            assertFalse(Files.exists(root().resolve("workspace-preferences.bin")));
            assertEquals(List.of(), store.snapshot().drafts());
        }
    }

    @Test void writesExactManifestAndReopensWithoutDuplicatingDraftData() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.save(draft());
            store.saveWorkspace(sample(10));
            assertArrayEquals(SqlWorkspaceCodec.encode(sample(10)), Files.readAllBytes(root().resolve("workspace.bin")));
            assertEquals(72, Files.size(root().resolve("workspace.bin")));
            assertEquals(List.of(draft()), store.snapshot().drafts());
            assertFalse(Files.exists(root().resolve("workspace-preferences.bin")));
        }
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            assertEquals(SqlWorkspaceStore.Status.AVAILABLE, store.workspaceSnapshot().status());
            assertEquals(sample(10), store.workspaceSnapshot().workspace());
            store.saveWorkspace(sample(20));
            assertEquals(sample(20), store.workspaceSnapshot().workspace());
            assertEquals(List.of(draft()), store.snapshot().drafts());
        }
    }

    @Test void ownDisablePersistsAndDoesNotDisableDraftProtection() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.saveWorkspace(sample(10));
            store.setWorkspaceEnabled(false);
            assertArrayEquals(pref(false), Files.readAllBytes(root().resolve("workspace-preferences.bin")));
            code(SqlWorkspaceStore.FailureCode.DISABLED, () -> store.saveWorkspace(sample(20)));
            assertTrue(store.snapshot().protectionEnabled());
            store.save(draft());
            assertEquals(sample(10), store.workspaceSnapshot().workspace());
        }
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            assertFalse(store.workspaceSnapshot().recordingEnabled());
            store.setWorkspaceEnabled(true);
            assertArrayEquals(pref(true), Files.readAllBytes(root().resolve("workspace-preferences.bin")));
            store.saveWorkspace(sample(20));
            assertEquals(sample(20), store.workspaceSnapshot().workspace());
        }
    }

    @Test void draftSwitchAndInvalidDraftPreferencePreventNewWorkspaceWrites() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.saveWorkspace(sample(10));
            store.setEnabled(false);
            code(SqlWorkspaceStore.FailureCode.DISABLED, () -> store.saveWorkspace(sample(20)));
            assertTrue(store.workspaceSnapshot().recordingEnabled());
            assertEquals(sample(10), store.workspaceSnapshot().workspace());
            store.setEnabled(true);
            store.saveWorkspace(sample(20));
            Files.write(root().resolve("preferences.bin"), new byte[]{1, 2});
            code(SqlWorkspaceStore.FailureCode.DRAFT_PROTECTION_UNAVAILABLE, () -> store.saveWorkspace(sample(30)));
            assertEquals(sample(20), store.workspaceSnapshot().workspace());
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(root().resolve("preferences.bin")));
        }
    }

    @Test void clearPublishesCanonicalEmptyEvenWhenDisabledAndIsIdempotent() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.save(draft());
            store.saveWorkspace(sample(10));
            store.setEnabled(false);
            store.setWorkspaceEnabled(false);
            byte[] originalDraft = Files.readAllBytes(root().resolve(A + ".draft"));
            byte[] originalPreference = Files.readAllBytes(root().resolve("preferences.bin"));
            assertTrue(store.clearWorkspace());
            SqlWorkspace empty = new SqlWorkspace(0, List.of(), null);
            assertArrayEquals(SqlWorkspaceCodec.encode(empty), Files.readAllBytes(root().resolve("workspace.bin")));
            assertEquals(SqlWorkspaceStore.Status.AVAILABLE, store.workspaceSnapshot().status());
            assertEquals(empty, store.workspaceSnapshot().workspace());
            assertFalse(store.clearWorkspace());
            assertArrayEquals(originalDraft, Files.readAllBytes(root().resolve(A + ".draft")));
            assertArrayEquals(originalPreference, Files.readAllBytes(root().resolve("preferences.bin")));
            assertArrayEquals(pref(false), Files.readAllBytes(root().resolve("workspace-preferences.bin")));
        }
        try (SqlDraftStore reopened = SqlDraftStore.open(root())) {
            assertEquals(new SqlWorkspace(0, List.of(), null), reopened.workspaceSnapshot().workspace());
            assertEquals(List.of(draft()), reopened.snapshot().drafts());
        }
    }

    @Test void corruptUnknownAndOversizedManifestAreProtectedWithoutHidingDrafts() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) { store.save(draft()); }
        byte[] unknown = SqlWorkspaceCodec.encode(sample(10));
        ByteBuffer.wrap(unknown).putInt(4, 2);
        byte[][] inputs = {new byte[]{1, 2}, unknown, new byte[2425]};
        SqlWorkspaceStore.Status[] statuses = {SqlWorkspaceStore.Status.CORRUPT,
                SqlWorkspaceStore.Status.UNSUPPORTED_VERSION, SqlWorkspaceStore.Status.UNREADABLE};
        for (int i = 0; i < inputs.length; i++) {
            byte[] bytes = inputs[i];
            Files.write(root().resolve("workspace.bin"), bytes);
            try (SqlDraftStore store = SqlDraftStore.open(root())) {
                assertEquals(statuses[i], store.workspaceSnapshot().status());
                assertNull(store.workspaceSnapshot().workspace());
                code(SqlWorkspaceStore.FailureCode.PROTECTED_WORKSPACE, () -> store.saveWorkspace(sample(20)));
                code(SqlWorkspaceStore.FailureCode.PROTECTED_WORKSPACE, store::clearWorkspace);
                store.setWorkspaceEnabled(false);
                store.setWorkspaceEnabled(true);
                assertArrayEquals(bytes, Files.readAllBytes(root().resolve("workspace.bin")));
                assertEquals(List.of(draft()), store.snapshot().drafts());
            }
        }
    }

    @Test void corruptPreferencesNeverDefaultOnAndMayNotBeOverwritten() throws Exception {
        byte[] unknown = pref(true); ByteBuffer.wrap(unknown).putInt(4, 2);
        byte[] badMagic = pref(true); ByteBuffer.wrap(badMagic).putInt(0, 0);
        byte[] invalidBit = pref(true); invalidBit[8] = 2;
        byte[][] inputs = {new byte[0], new byte[]{1}, unknown, badMagic, invalidBit, new byte[10]};
        try (SqlDraftStore store = SqlDraftStore.open(root())) { store.save(draft()); }
        for (byte[] bytes : inputs) {
            Files.write(root().resolve("workspace.bin"), SqlWorkspaceCodec.encode(sample(10)));
            Files.write(root().resolve("workspace-preferences.bin"), bytes);
            try (SqlDraftStore store = SqlDraftStore.open(root())) {
                assertFalse(store.workspaceSnapshot().preferenceValid());
                assertFalse(store.workspaceSnapshot().recordingEnabled());
                code(SqlWorkspaceStore.FailureCode.PREFERENCE_CORRUPT, () -> store.setWorkspaceEnabled(true));
                code(SqlWorkspaceStore.FailureCode.PREFERENCE_CORRUPT, () -> store.setWorkspaceEnabled(false));
                code(SqlWorkspaceStore.FailureCode.PREFERENCE_CORRUPT, () -> store.saveWorkspace(sample(20)));
                assertEquals(sample(10), store.workspaceSnapshot().workspace());
                assertTrue(store.clearWorkspace());
                assertArrayEquals(bytes, Files.readAllBytes(root().resolve("workspace-preferences.bin")));
                assertEquals(List.of(draft()), store.snapshot().drafts());
            }
        }
    }

    @Test void nullAndClosedOperationsNeverCreateOrChangeWorkspace() throws Exception {
        SqlDraftStore store = SqlDraftStore.open(root());
        try {
            code(SqlWorkspaceStore.FailureCode.INVALID_WORKSPACE, () -> store.saveWorkspace(null));
            assertFalse(Files.exists(root().resolve("workspace.bin")));
        } finally { store.close(); }
        assertThrows(IOException.class, store::workspaceSnapshot);
        assertThrows(IOException.class, () -> store.saveWorkspace(sample(10)));
        assertThrows(IOException.class, () -> store.setWorkspaceEnabled(false));
        assertThrows(IOException.class, store::clearWorkspace);
        assertFalse(Files.exists(root().resolve("workspace.bin")));
    }

    @Test void sameJvmAndNewJvmShareDraftWriterLockAndReadAfterRelease() throws Exception {
        try (SqlDraftStore first = SqlDraftStore.open(root())) {
            first.saveWorkspace(sample(10)); first.setWorkspaceEnabled(false);
            var busy = assertThrows(SqlDraftDirectory.Failure.class, () -> SqlDraftStore.open(root().resolve(".")));
            assertEquals(SqlDraftDirectory.Stage.BUSY, busy.stage());
            assertEquals(23, probe());
            assertEquals(sample(10), first.workspaceSnapshot().workspace());
        }
        assertEquals(0, probe());
    }

    private int probe() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(SqlDraftStore.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(java, "-cp", classes, Probe.class.getName(), root().toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "synthetic workspace probe timed out");
            return process.exitValue();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            int result;
            try (SqlDraftStore store = SqlDraftStore.open(Path.of(args[0]))) {
                var value = store.workspaceSnapshot();
                result = value.status() == SqlWorkspaceStore.Status.AVAILABLE && value.workspace().capturedAt() == 10
                        && value.workspace().entries().size() == 2 && !value.recordingEnabled() ? 0 : 4;
            } catch (SqlDraftDirectory.Failure failure) {
                if (failure.stage() != SqlDraftDirectory.Stage.BUSY) throw failure;
                result = 23;
            }
            System.exit(result);
        }
    }

    static void code(SqlWorkspaceStore.FailureCode expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(SqlWorkspaceStore.Failure.class, action);
        assertEquals(expected, failure.code());
        assertEquals("SQL workspace store failed: " + expected, failure.getMessage());
        assertNull(failure.getCause());
    }
}

