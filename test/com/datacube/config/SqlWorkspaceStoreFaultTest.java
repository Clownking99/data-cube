package com.datacube.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SqlWorkspaceStoreFaultTest {
    @TempDir Path temp;
    Path root() { return temp.resolve("drafts"); }
    private void seed() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.save(SqlWorkspaceStoreTest.draft());
            store.saveWorkspace(SqlWorkspaceStoreTest.sample(10));
            store.setWorkspaceEnabled(true);
        }
    }

    @ParameterizedTest
    @CsvSource({"SAVE,WRITE", "SAVE,PUBLISH", "SAVE,CLEANUP", "PREFERENCE,WRITE", "PREFERENCE,PUBLISH",
            "PREFERENCE,CLEANUP", "CLEAR,WRITE", "CLEAR,PUBLISH", "CLEAR,CLEANUP"})
    void publicationFailuresPreserveOldFilesAndExposeExactStage(String operation, String phase) throws Exception {
        seed();
        byte[] oldManifest = Files.readAllBytes(root().resolve("workspace.bin"));
        byte[] oldPreference = Files.readAllBytes(root().resolve("workspace-preferences.bin"));
        try (SqlDraftStore store = new SqlDraftStore(SqlDraftDirectory.open(root(),
                (path, bytes) -> {
                    if (!phase.equals("PUBLISH")) { Files.write(path, new byte[]{9}); throw new IOException("synthetic private detail"); }
                    SqlDraftDirectory.writeForced(path, bytes);
                },
                (source, target) -> { throw new AtomicMoveNotSupportedException("private-source", "private-target", "synthetic"); },
                path -> { if (phase.equals("CLEANUP")) throw new IOException("private cleanup"); Files.deleteIfExists(path); }))) {
            var failure = assertThrows(SqlDraftDirectory.Failure.class, () -> {
                switch (operation) {
                    case "SAVE" -> store.saveWorkspace(SqlWorkspaceStoreTest.sample(20));
                    case "PREFERENCE" -> store.setWorkspaceEnabled(false);
                    case "CLEAR" -> store.clearWorkspace();
                    default -> throw new AssertionError(operation);
                }
            });
            assertEquals(SqlDraftDirectory.Stage.valueOf(phase), failure.stage());
            assertEquals("SQL draft I/O failed: " + phase, failure.getMessage());
            assertNull(failure.getCause());
            assertArrayEquals(oldManifest, Files.readAllBytes(root().resolve("workspace.bin")));
            assertArrayEquals(oldPreference, Files.readAllBytes(root().resolve("workspace-preferences.bin")));
            assertEquals(SqlWorkspaceStoreTest.sample(10), store.workspaceSnapshot().workspace());
            assertTrue(store.workspaceSnapshot().recordingEnabled());
            assertEquals(List.of(SqlWorkspaceStoreTest.draft()), store.snapshot().drafts());
            try (var paths = Files.list(root())) {
                assertEquals(phase.equals("CLEANUP") ? 1 : 0,
                        paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
            }
        }
    }

    @Test void externalTargetChangeDuringWriteIsNotOverwritten() throws Exception {
        seed();
        byte[] external = {8, 8, 8, 8, 8};
        try (SqlDraftStore store = new SqlDraftStore(SqlDraftDirectory.open(root(),
                (path, bytes) -> { SqlDraftDirectory.writeForced(path, bytes); Files.write(root().resolve("workspace.bin"), external); },
                SqlDraftDirectory::moveAtomic, Files::deleteIfExists))) {
            var failure = assertThrows(SqlDraftDirectory.Failure.class,
                    () -> store.saveWorkspace(SqlWorkspaceStoreTest.sample(20)));
            assertEquals(SqlDraftDirectory.Stage.UNSAFE, failure.stage());
            assertArrayEquals(external, Files.readAllBytes(root().resolve("workspace.bin")));
            assertEquals(SqlWorkspaceStore.Status.CORRUPT, store.workspaceSnapshot().status());
            assertEquals(List.of(SqlWorkspaceStoreTest.draft()), store.snapshot().drafts());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"workspace.bin", "workspace-preferences.bin"})
    void caseAliasIsNotTreatedAsMissingOrReplaced(String name) throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            Path alias = root().resolve(name.toUpperCase(java.util.Locale.ROOT));
            Files.write(alias, new byte[]{7, 6});
            var failure = assertThrows(SqlDraftDirectory.Failure.class, store::workspaceSnapshot);
            assertEquals(SqlDraftDirectory.Stage.UNSAFE, failure.stage());
            assertThrows(IOException.class, () -> store.saveWorkspace(SqlWorkspaceStoreTest.sample(10)));
            assertArrayEquals(new byte[]{7, 6}, Files.readAllBytes(alias));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"workspace.bin", "workspace-preferences.bin"})
    void directoryTargetIsNotFollowedOrOverwritten(String name) throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            Path target = Files.createDirectory(root().resolve(name));
            Files.write(target.resolve("sentinel"), new byte[]{7});
            var failure = assertThrows(SqlDraftDirectory.Failure.class, store::workspaceSnapshot);
            assertEquals(SqlDraftDirectory.Stage.UNSAFE, failure.stage());
            assertThrows(IOException.class, () -> store.saveWorkspace(SqlWorkspaceStoreTest.sample(10)));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(target.resolve("sentinel")));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"workspace.bin", "workspace-preferences.bin"})
    void symbolicLinkTargetCannotRedirectWorkspaceWrites(String name) throws Exception {
        Path outside = temp.resolve("outside.bin"); Files.write(outside, new byte[]{3, 4});
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            try { Files.createSymbolicLink(root().resolve(name), outside); }
            catch (UnsupportedOperationException | FileSystemException unsupported) {
                assumeTrue(false, "symbolic links unavailable in test environment");
            }
            var failure = assertThrows(SqlDraftDirectory.Failure.class, store::workspaceSnapshot);
            assertEquals(SqlDraftDirectory.Stage.UNSAFE, failure.stage());
            assertThrows(IOException.class, () -> store.saveWorkspace(SqlWorkspaceStoreTest.sample(10)));
            assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(outside));
        }
    }
}

