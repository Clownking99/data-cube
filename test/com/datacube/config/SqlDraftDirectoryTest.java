package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SqlDraftDirectoryTest {
    @TempDir Path temp;
    private static final String NAME = "00112233-4455-6677-8899-aabbccddeeff.draft";
    private static final byte[] OLD = {1, 2, 3};
    private static final byte[] NEW = {4, 5, 6, 7};

    @Test void publishesReopensReadsAndKeepsLockFile() throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            assertNull(directory.read(NAME, 10));
            directory.publish(NAME, OLD);
            directory.publish(NAME, NEW);
            directory.publish("preferences.bin", new byte[]{1});
            assertArrayEquals(NEW, Files.readAllBytes(root.resolve(NAME)));
            assertArrayEquals(NEW, directory.read(NAME, 4));
            assertEquals(3, directory.entries().size());
        }
        assertTrue(Files.isRegularFile(root.resolve(".writer.lock")));
        try (SqlDraftDirectory reopened = SqlDraftDirectory.open(root)) {
            assertArrayEquals(NEW, reopened.read(NAME, 4));
            reopened.delete(NAME);
            reopened.delete(NAME);
            assertNull(reopened.read(NAME, 4));
            assertArrayEquals(new byte[]{1}, reopened.read("preferences.bin", 8));
        }
    }

    @Test void secondWriterFailsWithoutBreakingFirstAndCloseIsIdempotent() throws Exception {
        Path root = temp.resolve("drafts");
        SqlDraftDirectory first = SqlDraftDirectory.open(root);
        assertNotNull(first);
        try {
            assertStage(SqlDraftDirectory.Stage.BUSY, () -> SqlDraftDirectory.open(root));
            first.publish(NAME, OLD);
            assertArrayEquals(OLD, first.read(NAME, 3));
        } finally { first.close(); }
        first.close();
        assertStage(SqlDraftDirectory.Stage.CLOSED, first::entries);
        assertStage(SqlDraftDirectory.Stage.CLOSED, () -> first.publish(NAME, NEW));
        try (SqlDraftDirectory second = SqlDraftDirectory.open(root)) {
            assertArrayEquals(OLD, second.read(NAME, 3));
        }
    }

    @Test void operatingSystemLockRejectsAnotherProcessUntilClose() throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            assertEquals(23, probeLock(root.resolve(".writer.lock")));
            directory.publish(NAME, OLD);
            assertArrayEquals(OLD, directory.read(NAME, 3));
        }
        assertEquals(0, probeLock(root.resolve(".writer.lock")));
    }

    private static int probeLock(Path lockPath) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classes = Path.of(LockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(java.toString(), "-cp", classes, LockProbe.class.getName(), lockPath.toString()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "synthetic lock probe timed out");
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class LockProbe {
        public static void main(String[] args) throws IOException {
            int exit;
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                exit = lock == null ? 23 : 0;
            }
            System.exit(exit);
        }
    }

    @Test void unsupportedAtomicMoveKeepsOldFileAndCleansTemporary() throws Exception {
        Path root = temp.resolve("drafts");
        seed(root);
        AtomicInteger calls = new AtomicInteger();
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root, SqlDraftDirectory::writeForced,
                (source, target) -> { calls.incrementAndGet(); throw new AtomicMoveNotSupportedException("synthetic", "synthetic", "test"); }, Files::deleteIfExists)) {
            assertStage(SqlDraftDirectory.Stage.PUBLISH, () -> directory.publish(NAME, NEW));
            assertEquals(1, calls.get());
            assertArrayEquals(OLD, directory.read(NAME, 3));
            assertEquals(2, directory.entries().size());
        }
    }

    @Test void failedWritePreservesOldFileAndCleanupFailureIsVisible() throws Exception {
        Path root = temp.resolve("drafts");
        seed(root);
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root,
                (path, bytes) -> { Files.write(path, new byte[]{9}); throw new IOException("private-sql"); },
                SqlDraftDirectory::moveAtomic, Files::deleteIfExists)) {
            assertStage(SqlDraftDirectory.Stage.WRITE, () -> directory.publish(NAME, NEW));
            assertArrayEquals(OLD, directory.read(NAME, 3));
            assertEquals(2, directory.entries().size());
        }
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root,
                (path, bytes) -> { throw new IOException("private-sql"); }, SqlDraftDirectory::moveAtomic,
                path -> { throw new IOException("private-path"); })) {
            SqlDraftDirectory.Failure failure = assertStage(SqlDraftDirectory.Stage.CLEANUP, () -> directory.publish(NAME, NEW));
            assertNull(failure.getCause());
            assertEquals("SQL draft I/O failed: CLEANUP", failure.getMessage());
            assertArrayEquals(OLD, directory.read(NAME, 3));
            assertEquals(3, directory.entries().size());
        }
    }

    @Test void changedTargetIsNotOverwrittenAfterTemporaryWrite() throws Exception {
        Path root = temp.resolve("drafts");
        seed(root);
        byte[] external = {8, 8, 8, 8, 8, 8};
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root,
                (path, bytes) -> { SqlDraftDirectory.writeForced(path, bytes); Files.write(root.resolve(NAME), external); },
                SqlDraftDirectory::moveAtomic, Files::deleteIfExists)) {
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(NAME, NEW));
            assertArrayEquals(external, directory.read(NAME, 6));
            assertEquals(2, directory.entries().size());
        }
    }

    @Test void readAndPublishRejectOversizeWithoutTruncating() throws Exception {
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(temp.resolve("drafts"))) {
            assertNotNull(directory);
            directory.publish(NAME, NEW);
            assertStage(SqlDraftDirectory.Stage.READ, () -> directory.read(NAME, 3));
            assertArrayEquals(NEW, directory.read(NAME, 4));
            assertStage(SqlDraftDirectory.Stage.WRITE, () -> directory.publish(NAME, new byte[SqlDraftCodec.MAX_FILE_BYTES + 1]));
            assertArrayEquals(NEW, directory.read(NAME, 4));
        }
    }

    @Test void scanHasExactBoundAndDoesNotDeleteUnknownFiles() throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            for (int i = 0; i < 511; i++) Files.createFile(root.resolve("unrelated-" + i));
            assertEquals(512, directory.entries().size());
            Files.createFile(root.resolve("entry-513"));
            assertStage(SqlDraftDirectory.Stage.SCAN_LIMIT, directory::entries);
            assertTrue(Files.exists(root.resolve("entry-513")));
            assertTrue(Files.exists(root.resolve("unrelated-0")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "child/file", "child\\file", ".writer.lock", "not-a-uuid.draft", "1-1-1-1-1.draft"})
    void rejectsNamesOutsideOwnedFiles(String name) throws Exception {
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(temp.resolve("drafts"))) {
            assertNotNull(directory);
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(name, NEW));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.read(name, 10));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.delete(name));
            assertEquals(1, directory.entries().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"00112233-4455-6677-8899-AABBCCDDEEFF.draft", "Preferences.bin"})
    void rejectsCaseAliasesWithoutOverwritingOrDeletingExistingBytes(String alias) throws Exception {
        Path root = temp.resolve("drafts");
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            Files.write(root.resolve(alias), OLD);
            String canonical = alias.equals("Preferences.bin") ? "preferences.bin" : NAME;
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.read(canonical, 10));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(canonical, NEW));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.delete(canonical));
            assertArrayEquals(OLD, Files.readAllBytes(root.resolve(alias)));
            assertEquals(2, directory.entries().size());
        }
    }

    @Test void rejectsSymlinksWithoutReadingWritingOrDeletingTheirTargets() throws Exception {
        Path root = temp.resolve("drafts");
        Path outside = temp.resolve("outside");
        Files.write(outside, OLD);
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            Path link = root.resolve(NAME);
            boolean supported = true;
            try { Files.createSymbolicLink(link, outside); }
            catch (UnsupportedOperationException | FileSystemException unavailable) { supported = false; }
            assumeTrue(supported, "symbolic links unavailable on this test host");
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.read(NAME, 10));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.publish(NAME, NEW));
            assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> directory.delete(NAME));
            assertArrayEquals(OLD, Files.readAllBytes(outside));
            assertTrue(Files.isSymbolicLink(link));
        }
        Path directoryLink = temp.resolve("directory-link");
        Files.createSymbolicLink(directoryLink, root);
        assertStage(SqlDraftDirectory.Stage.UNSAFE, () -> SqlDraftDirectory.open(directoryLink));
    }

    private static void seed(Path root) throws IOException {
        try (SqlDraftDirectory directory = SqlDraftDirectory.open(root)) {
            assertNotNull(directory);
            directory.publish(NAME, OLD);
        }
    }

    private static SqlDraftDirectory.Failure assertStage(SqlDraftDirectory.Stage stage,
            org.junit.jupiter.api.function.Executable operation) {
        SqlDraftDirectory.Failure failure = assertThrows(SqlDraftDirectory.Failure.class, operation);
        assertEquals(stage, failure.stage());
        assertNull(failure.getCause());
        return failure;
    }
}
