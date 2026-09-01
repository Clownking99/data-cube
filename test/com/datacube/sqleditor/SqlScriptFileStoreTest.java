package com.datacube.sqleditor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SqlScriptFileStoreTest {

    @TempDir Path directory;

    @Test
    void loadsStrictUtf8WithOptionalBomAndPreservesText() throws Exception {
        Path plain = Files.writeString(directory.resolve("plain.sql"), "select '甲';\r\n", StandardCharsets.UTF_8);
        byte[] body = "select '乙';\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Path bom = Files.write(directory.resolve("bom.sql"), withBom);

        SqlScriptFileStore store = new SqlScriptFileStore();
        SqlScriptFileStore.Loaded first = store.load(plain);
        SqlScriptFileStore.Loaded second = store.load(bom);

        assertEquals("select '甲';\r\n", first.text());
        assertEquals(plain.toRealPath(), first.path());
        assertTrue(first.target().exists());
        assertEquals("select '乙';\n", second.text());
    }

    @Test
    void rejectsMalformedUtf8AndReportsReadFailuresWithoutDetails() throws Exception {
        Path malformed = Files.write(directory.resolve("bad.sql"), new byte[]{(byte) 0xC3, 0x28});
        SqlScriptFileStore.Failure invalid = assertThrows(SqlScriptFileStore.Failure.class,
                () -> new SqlScriptFileStore().load(malformed));
        assertEquals(SqlScriptFileStore.FailureCode.INVALID_UTF8, invalid.code());
        assertNull(invalid.getCause());

        SqlScriptFileStore broken = store(path -> { throw new IOException("private read detail"); },
                SqlScriptFileStoreTest::writeBytes,
                SqlScriptFileStoreTest::moveAtomic,
                path -> Files.deleteIfExists(path), ignored -> { });
        SqlScriptFileStore.Failure unreadable = assertThrows(SqlScriptFileStore.Failure.class,
                () -> broken.load(malformed));
        assertEquals(SqlScriptFileStore.FailureCode.READ, unreadable.code());
        assertFalse(unreadable.getMessage().contains("private"));
        assertNull(unreadable.getCause());
    }

    @Test
    void enforcesExactEightMibibyteLimitForReadAndWrite() throws Exception {
        byte[] allowed = new byte[(int) SqlScriptFileStore.MAX_BYTES];
        Arrays.fill(allowed, (byte) 'a');
        Path boundary = Files.write(directory.resolve("boundary.sql"), allowed);
        assertEquals(allowed.length, new SqlScriptFileStore().load(boundary).text().length());

        Path oversized = Files.write(directory.resolve("oversized.sql"),
                Arrays.copyOf(allowed, allowed.length + 1));
        SqlScriptFileStore.Failure readFailure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> new SqlScriptFileStore().load(oversized));
        assertEquals(SqlScriptFileStore.FailureCode.TOO_LARGE, readFailure.code());

        String tooLarge = "a".repeat((int) SqlScriptFileStore.MAX_BYTES + 1);
        SqlScriptFileStore.Target target = new SqlScriptFileStore().capture(directory.resolve("new.sql"));
        SqlScriptFileStore.Failure writeFailure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> new SqlScriptFileStore().save(target, tooLarge));
        assertEquals(SqlScriptFileStore.FailureCode.TOO_LARGE, writeFailure.code());
        assertFalse(Files.exists(target.path()));
    }

    @Test
    void rejectsDirectoriesAndSymbolicLinksWithoutTouchingTheirTargets() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        assertEquals(SqlScriptFileStore.FailureCode.INVALID_TARGET,
                assertThrows(SqlScriptFileStore.Failure.class, () -> store.capture(directory)).code());

        Path actual = Files.writeString(directory.resolve("actual.sql"), "keep");
        Path link = directory.resolve("link.sql");
        try {
            Files.createSymbolicLink(link, actual.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable for this account");
        }
        assertEquals(SqlScriptFileStore.FailureCode.INVALID_TARGET,
                assertThrows(SqlScriptFileStore.Failure.class, () -> store.capture(link)).code());
        assertEquals("keep", Files.readString(actual));
    }

    @Test
    void detectsChangesDuringRead() throws Exception {
        Path target = Files.writeString(directory.resolve("changing.sql"), "before");
        SqlScriptFileStore store = store(path -> {
            byte[] bytes = Files.readAllBytes(path);
            Files.writeString(path, "after-change");
            return bytes;
        }, SqlScriptFileStoreTest::writeBytes, SqlScriptFileStoreTest::moveAtomic,
                path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.load(target));
        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals("after-change", Files.readString(target));
    }

    @Test
    void savesNewAndExistingFilesAsUtf8WithoutBom() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path selected = directory.resolve("saved.sql");
        SqlScriptFileStore.Loaded created = store.save(store.capture(selected), "select '甲';\n");

        assertEquals(selected.toAbsolutePath().normalize(), created.path());
        assertEquals("select '甲';\n", created.text());
        assertTrue(created.target().exists());
        assertArrayEquals("select '甲';\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(selected));

        SqlScriptFileStore.Loaded updated = store.save(created.target(), "select '乙';\r\n");
        assertEquals("select '乙';\r\n", Files.readString(selected, StandardCharsets.UTF_8));
        assertEquals("select '乙';\r\n", updated.text());
    }

    @Test
    void externalModificationDeletionAndLateCreationNeverGetOverwritten() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path modified = Files.writeString(directory.resolve("modified.sql"), "ours-old");
        SqlScriptFileStore.Target expectedModified = store.load(modified).target();
        Files.writeString(modified, "external-longer");
        assertChanged(() -> store.save(expectedModified, "ours-new"));
        assertEquals("external-longer", Files.readString(modified));

        Path deleted = Files.writeString(directory.resolve("deleted.sql"), "old");
        SqlScriptFileStore.Target expectedDeleted = store.load(deleted).target();
        Files.delete(deleted);
        assertChanged(() -> store.save(expectedDeleted, "must-not-recreate"));
        assertFalse(Files.exists(deleted));

        Path appeared = directory.resolve("appeared.sql");
        SqlScriptFileStore.Target expectedMissing = store.capture(appeared);
        Files.writeString(appeared, "other");
        assertChanged(() -> store.save(expectedMissing, "ours"));
        assertEquals("other", Files.readString(appeared));
    }

    @Test
    void publicationSeamChecksTargetImmediatelyBeforeMoving() throws Exception {
        Path target = Files.writeString(directory.resolve("boundary.sql"), "old");
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    Files.writeString(destination, "external");
                    finalCheck.verify();
                    fail("changed target must stop publication before moving");
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals("external", Files.readString(target));
    }

    @Test
    void writeAndAtomicMoveFailuresPreserveOldBytesAndRemoveOwnedTemps() throws Exception {
        Path target = Files.writeString(directory.resolve("safe.sql"), "old");
        AtomicReference<Path> writtenTemp = new AtomicReference<>();
        SqlScriptFileStore brokenWriter = store(Files::readAllBytes, (path, bytes) -> {
            writtenTemp.set(path);
            Files.writeString(path, "partial");
            throw new IOException("private SQL fragment");
        }, SqlScriptFileStoreTest::moveAtomic, path -> Files.deleteIfExists(path), ignored -> { });
        SqlScriptFileStore.Failure writeFailure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> brokenWriter.save(brokenWriter.capture(target), "new"));
        assertEquals(SqlScriptFileStore.FailureCode.WRITE, writeFailure.code());
        assertEquals("old", Files.readString(target));
        assertFalse(Files.exists(writtenTemp.get()));

        AtomicReference<Path> moveTemp = new AtomicReference<>();
        SqlScriptFileStore brokenMove = store(Files::readAllBytes, (path, bytes) -> {
            moveTemp.set(path);
            Files.write(path, bytes);
        }, (source, destination, finalCheck) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test");
        }, path -> Files.deleteIfExists(path), ignored -> { });
        SqlScriptFileStore.Failure publishFailure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> brokenMove.save(brokenMove.capture(target), "new"));
        assertEquals(SqlScriptFileStore.FailureCode.PUBLISH, publishFailure.code());
        assertEquals("old", Files.readString(target));
        assertFalse(Files.exists(moveTemp.get()));
    }

    @Test
    void sameCanonicalTargetIsExclusiveAndReleasesBusyState() throws Exception {
        Path target = Files.writeString(directory.resolve("busy.sql"), "old");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            Files.write(path, bytes);
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("release timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
        }, SqlScriptFileStoreTest::moveAtomic, path -> Files.deleteIfExists(path), ignored -> { });
        SqlScriptFileStore.Target expected = store.capture(target);
        FutureTask<SqlScriptFileStore.Loaded> first = new FutureTask<>(() -> store.save(expected, "first"));
        Thread.ofVirtual().start(first);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SqlScriptFileStore.Failure busy = assertThrows(SqlScriptFileStore.Failure.class,
                    () -> store.save(expected, "second"));
            assertEquals(SqlScriptFileStore.FailureCode.BUSY, busy.code());
            release.countDown();
            SqlScriptFileStore.Loaded saved = first.get(5, TimeUnit.SECONDS);
            assertEquals("first", saved.text());
            assertEquals("first", Files.readString(target));
            store.save(saved.target(), "third");
            assertEquals("third", Files.readString(target));
        } finally {
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void cleanupFailureReportsOnlyTheOwnedTemporaryPath() throws Exception {
        Path target = Files.writeString(directory.resolve("cleanup.sql"), "old");
        AtomicReference<Path> owned = new AtomicReference<>();
        AtomicReference<Path> diagnosed = new AtomicReference<>();
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            owned.set(path);
            Files.write(path, bytes);
            throw new IOException("private write detail");
        }, SqlScriptFileStoreTest::moveAtomic,
                path -> { throw new IOException("private cleanup detail"); }, diagnosed::set);

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "secret SQL"));
        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertEquals(owned.get(), failure.temporaryPath());
        assertEquals(owned.get(), diagnosed.get());
        assertTrue(Files.exists(owned.get()));
        assertEquals("old", Files.readString(target));
        assertFalse(failure.getMessage().contains("private"));
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
    }

    @Test
    void replacedTemporaryFileIsNeverCleaned() throws Exception {
        Path target = Files.writeString(directory.resolve("owned.sql"), "old");
        Path replacement = Files.writeString(directory.resolve("foreign-temporary.sql"), "external temporary");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> diagnosed = new AtomicReference<>();
        AtomicReference<Boolean> cleanerCalled = new AtomicReference<>(false);
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            Files.delete(path);
            Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("writer failed");
        }, SqlScriptFileStoreTest::moveAtomic, path -> {
            cleanerCalled.set(true);
            Files.deleteIfExists(path);
        }, diagnosed::set);

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertEquals(temporary.get(), failure.temporaryPath());
        assertEquals(temporary.get(), diagnosed.get());
        assertFalse(cleanerCalled.get());
        assertEquals("external temporary", Files.readString(temporary.get()));
        assertEquals("old", Files.readString(target));
    }

    @Test
    void deletionOrReplacementAfterPublicationReturnsChanged() throws Exception {
        assertPostPublicationChange("deleted", false);
        assertPostPublicationChange("replaced", true);
    }

    private SqlScriptFileStore store(
            SqlScriptFileStore.ByteReader reader,
            SqlScriptFileStore.ContentWriter writer,
            SqlScriptFileStore.AtomicMover mover,
            SqlScriptFileStore.TempCleaner cleaner,
            java.util.function.Consumer<Path> diagnostic) {
        return new SqlScriptFileStore(reader, writer, mover, cleaner, diagnostic);
    }

    private static void writeBytes(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes);
    }

    private static void moveAtomic(Path source, Path destination,
            SqlScriptFileStore.FinalTargetVerifier finalCheck) throws IOException {
        finalCheck.verify();
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void assertPostPublicationChange(String name, boolean replace) throws Exception {
        Path target = Files.writeString(directory.resolve("post-" + name + ".sql"), "old");
        Path external = replace
                ? Files.writeString(directory.resolve("external-" + name + ".sql"), "external replacement")
                : null;
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    Files.delete(destination);
                    if (external != null) Files.move(external, destination, StandardCopyOption.REPLACE_EXISTING);
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        if (replace) assertEquals("external replacement", Files.readString(target));
    }

    private static void assertChanged(ThrowingAction action) {
        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class, action::run);
        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

}
