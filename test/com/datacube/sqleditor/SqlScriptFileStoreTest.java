package com.datacube.sqleditor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                SqlScriptFileStoreTest::moveNoReplace,
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
        }, SqlScriptFileStoreTest::writeBytes, SqlScriptFileStoreTest::moveNoReplace,
                path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.load(target));
        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals("after-change", Files.readString(target));
    }

    @Test
    void rejectsAbaReaderBytesThatDifferFromTheCapturedTarget() throws Exception {
        Path target = Files.writeString(directory.resolve("aba.sql"), "select 1;");
        byte[] replacement = "select 2;".getBytes(StandardCharsets.UTF_8);
        SqlScriptFileStore store = store(path -> replacement,
                SqlScriptFileStoreTest::writeBytes, SqlScriptFileStoreTest::moveNoReplace,
                path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.load(target));

        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals("select 1;", Files.readString(target, StandardCharsets.UTF_8));
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
    void rejectsUnpairedSurrogatesWithoutPublishingOrChangingTheExpectedTarget() throws Exception {
        SqlScriptFileStore store = new SqlScriptFileStore();
        Path target = Files.writeString(directory.resolve("strict-utf8.sql"), "old", StandardCharsets.UTF_8);
        SqlScriptFileStore.Target expected = store.load(target).target();

        for (String malformed : List.of("before\uD800after", "before\uDC00after")) {
            SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                    () -> store.save(expected, malformed));
            assertEquals(SqlScriptFileStore.FailureCode.WRITE, failure.code());
            assertEquals("old", Files.readString(target, StandardCharsets.UTF_8));
        }
    }

    @Test
    void refusesToOverwriteAWriterCreatedAfterTheFinalCheck() throws Exception {
        Path target = Files.writeString(directory.resolve("final-check-race.sql"), "old");
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                SqlScriptFileStoreTest::moveNoReplace, path -> Files.deleteIfExists(path), ignored -> { },
                path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(attributes.fileKey(),
                            attributes.creationTime(), null);
                }, () -> {
                    try {
                        Files.writeString(target, "external-after-check");
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("external-after-check", Files.readString(target));
        assertTrue(Files.isRegularFile(failure.recoveryPath()));
    }

    @Test
    void activeDefaultProviderNoReplaceMovePreservesATargetCreatedAtTheBoundary() throws Exception {
        Path source = Files.writeString(directory.resolve("provider-source.sql"), "ours");
        Path destination = directory.resolve("provider-destination.sql");

        assertThrows(IOException.class, () -> SqlScriptFileStore.moveNoReplace(source, destination,
                () -> Files.writeString(destination, "external")));

        assertEquals("ours", Files.readString(source));
        assertEquals("external", Files.readString(destination));
    }

    @Test
    void genericProviderUsesAtomicLinkCreationWhenTheMovePrimitiveCouldReplace() throws Exception {
        Path source = Files.writeString(directory.resolve("generic-source.sql"), "ours");
        Path destination = directory.resolve("generic-destination.sql");

        assertThrows(IOException.class, () -> SqlScriptFileStore.moveNoReplace(source, destination,
                () -> { }, false,
                (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING),
                (link, existing) -> {
                    Files.writeString(link, "external");
                    Files.createLink(link, existing);
                }));

        assertEquals("ours", Files.readString(source));
        assertEquals("external", Files.readString(destination));
    }

    @Test
    void genericProviderHardLinkProtocolCompletesARealSameDirectoryMove() throws Exception {
        Path source = Files.writeString(directory.resolve("generic-success-source.sql"), "ours");
        Path destination = directory.resolve("generic-success-destination.sql");

        SqlScriptFileStore.moveNoReplace(source, destination, () -> { }, false,
                (from, to) -> fail("generic providers must not use a replacing move primitive"),
                Files::createLink);

        assertFalse(Files.exists(source));
        assertEquals("ours", Files.readString(destination));
    }

    @Test
    void publishUnlinkFailureRetainsTheNullKeyWitnessUntilTemporaryCleanupIsReconciled()
            throws Exception {
        Path target = Files.writeString(directory.resolve("publish-unlink-failure.sql"), "old");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> foreignTemporary = new AtomicReference<>();
        AtomicInteger unlinks = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            Files.write(path, bytes);
        }, (source, destination, finalCheck) -> SqlScriptFileStore.moveNoReplace(
                source, destination, finalCheck, false,
                (from, to) -> fail("generic providers must not use a move primitive"),
                Files::createLink, path -> {
                    int unlink = unlinks.incrementAndGet();
                    if (unlink == 2) return false;
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                }), path -> {
                    if (path.equals(temporary.get())) {
                        Files.delete(path);
                        Files.writeString(path, "foreign-temporary");
                        foreignTemporary.set(path);
                        throw new IOException("cleanup stopped after replacement");
                    }
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertEquals("old", Files.readString(target));
        assertEquals("foreign-temporary", Files.readString(foreignTemporary.get()));
        assertFalse(foreignTemporary.get().equals(failure.temporaryPath()));
        assertEquals("ours", Files.readString(failure.temporaryPath()));
    }

    @Test
    void publishAndRollbackUnlinkFailuresLeaveNoUnreportedOwnedTemporaryLinks()
            throws Exception {
        Path target = Files.writeString(directory.resolve("double-unlink-failure.sql"), "old");
        AtomicInteger unlinks = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> SqlScriptFileStore.moveNoReplace(
                        source, destination, finalCheck, false,
                        (from, to) -> fail("generic providers must not use a move primitive"),
                        Files::createLink, path -> {
                            int unlink = unlinks.incrementAndGet();
                            if (unlink == 1) {
                                try {
                                    return Files.deleteIfExists(path);
                                } catch (IOException failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                            return false;
                        }), path -> Files.deleteIfExists(path), ignored -> { }, path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertNull(failure.temporaryPath());
        assertEquals("ours", Files.readString(target));
        assertEquals("old", Files.readString(failure.recoveryPath()));
        List<Path> artifacts = transactionArtifacts();
        assertTrue(artifacts.stream().allMatch(path -> isSameFile(path, failure.recoveryPath())));
    }

    @Test
    void compoundedUnlinkAndCleanupFailuresRetainEveryProvenDirectoryEntry()
            throws Exception {
        Path target = Files.writeString(directory.resolve("compounded-artifacts.sql"), "old");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> backupWitness = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicReference<Path> temporaryWitness = new AtomicReference<>();
        AtomicReference<Path> temporaryGuard = new AtomicReference<>();
        AtomicInteger unlinks = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                (path, bytes) -> {
                    temporary.set(path);
                    Files.write(path, bytes);
                }, (source, destination, finalCheck) -> SqlScriptFileStore.moveNoReplace(
                        source, destination, finalCheck, false,
                        (from, to) -> fail("generic providers must not use a move primitive"),
                        Files::createLink, path -> {
                            int unlink = unlinks.incrementAndGet();
                            if (unlink == 1) {
                                try {
                                    return Files.deleteIfExists(path);
                                } catch (IOException failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                            return false;
                        }), path -> {
                    if (path.equals(temporary.get())) {
                        throw new IOException("temporary cleanup stopped");
                    }
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-")) backup.set(candidate);
                    else if (prefix.equals(".datacube-sql-backup-owner-")) {
                        backupWitness.set(candidate);
                    } else if (prefix.equals(".datacube-sql-rollback-")) {
                        rollback.set(candidate);
                    } else if (prefix.equals(".datacube-sql-owner-")) {
                        temporaryWitness.set(candidate);
                    } else if (prefix.equals(".datacube-sql-owner-guard-")) {
                        temporaryGuard.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    if (path.equals(rollback.get())) return false;
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(rollback.get(), failure.temporaryPath());
        assertEquals(backup.get(), failure.recoveryPath());
        assertEquals(6, failure.retainedPaths().size());
        assertTrue(failure.retainedPaths().contains(temporary.get()));
        assertTrue(failure.retainedPaths().contains(rollback.get()));
        assertTrue(failure.retainedPaths().contains(backup.get()));
        assertTrue(failure.retainedPaths().contains(backupWitness.get()));
        assertTrue(failure.retainedPaths().contains(temporaryWitness.get()));
        assertTrue(failure.retainedPaths().contains(temporaryGuard.get()));
        assertThrows(UnsupportedOperationException.class,
                () -> failure.retainedPaths().add(target));
        assertEquals("ours", Files.readString(temporary.get()));
        assertEquals("ours", Files.readString(rollback.get()));
        assertEquals("old", Files.readString(backup.get()));
        assertEquals("old", Files.readString(backupWitness.get()));
        assertEquals("ours", Files.readString(temporaryWitness.get()));
        assertEquals("ours", Files.readString(temporaryGuard.get()));
    }

    @Test
    void genericProviderHardLinkProtocolDisplacesAndPublishesAnExistingTarget() throws Exception {
        Path target = Files.writeString(directory.resolve("generic-existing.sql"), "old");
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> SqlScriptFileStore.moveNoReplace(
                        source, destination, finalCheck, false,
                        (from, to) -> fail("generic providers must not use a move primitive"),
                        Files::createLink), path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Loaded saved = store.save(store.capture(target), "ours");

        assertEquals("ours", saved.text());
        assertEquals("ours", Files.readString(target));
        assertTrue(transactionArtifacts().isEmpty());
    }

    @Test
    void unsupportedGenericHardLinksFailClosedAndKeepTheOldTarget() throws Exception {
        Path target = Files.writeString(directory.resolve("unsupported-link.sql"), "old");
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> SqlScriptFileStore.moveNoReplace(
                        source, destination, finalCheck, false,
                        (from, to) -> fail("generic providers must not use a move primitive"),
                        (link, existing) -> { throw new IOException("hard links unsupported"); }),
                path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.PUBLISH, failure.code());
        assertEquals("old", Files.readString(target));
        assertTrue(transactionArtifacts().isEmpty());
    }

    @Test
    void missingTargetCreatedAfterTheFinalCheckIsPreservedAndTemporaryIsRemoved() throws Exception {
        Path target = directory.resolve("missing-final-check.sql");
        AtomicReference<Path> temporary = new AtomicReference<>();
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            Files.write(path, bytes);
        }, (source, destination, finalCheck) -> {
            finalCheck.verify();
            Files.writeString(destination, "external-after-check");
            Files.move(source, destination);
        }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals("external-after-check", Files.readString(target));
        assertFalse(Files.exists(temporary.get()));
    }

    @Test
    void displacedMismatchIsPreservedWhenAnotherWriterOccupiesTheTarget() throws Exception {
        Path target = Files.writeString(directory.resolve("mismatch-occupied.sql"), "old");
        AtomicReference<Path> displaced = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    if (moves.getAndIncrement() == 0) {
                        Files.delete(source);
                        Files.writeString(source, "external-displaced");
                        Files.move(source, destination);
                        displaced.set(destination);
                        Files.writeString(target, "external-occupier");
                    } else {
                        fail("mismatched displacement must stop before publish");
                    }
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("old", Files.readString(failure.recoveryPath()));
        assertTrue(failure.recoveryPath().getFileName().toString()
                .startsWith(".datacube-sql-backup-owner-"));
        assertEquals("external-displaced", Files.readString(displaced.get()));
        assertEquals("external-occupier", Files.readString(target));
    }

    @Test
    void displacementFailureNeverTreatsAReplacementAtTheBackupNameAsTheOriginal() throws Exception {
        Path target = Files.writeString(directory.resolve("displacement-q-race.sql"), "original");
        AtomicReference<Path> racedBackup = new AtomicReference<>();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination);
                    racedBackup.set(destination);
                    Files.delete(destination);
                    Files.writeString(destination, "foreign-q");
                    Files.writeString(target, "external-target");
                    throw new IOException("displacement boundary failure");
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("original", Files.readString(failure.recoveryPath()));
        assertTrue(failure.recoveryPath().getFileName().toString()
                .startsWith(".datacube-sql-backup-owner-"));
        assertEquals("foreign-q", Files.readString(racedBackup.get()));
        assertEquals("external-target", Files.readString(target));
    }

    @Test
    void restoreFailureRequiresTheRestoredTargetToBeTheOriginalWitness() throws Exception {
        Path target = Files.writeString(directory.resolve("restore-q-race.sql"), "original");
        AtomicInteger moves = new AtomicInteger();
        AtomicReference<Path> racedBackup = new AtomicReference<>();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 1) {
                        finalCheck.verify();
                        Files.move(source, destination);
                        racedBackup.set(destination);
                    } else if (move == 2) {
                        throw new IOException("publish failed before moving");
                    } else if (move == 3) {
                        finalCheck.verify();
                        Files.delete(source);
                        Files.writeString(source, "foreign-q");
                        Files.writeString(destination, "external-target");
                        throw new IOException("restore boundary failure");
                    } else {
                        fail("unexpected move " + move);
                    }
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("original", Files.readString(failure.recoveryPath()));
        assertTrue(failure.recoveryPath().getFileName().toString()
                .startsWith(".datacube-sql-backup-owner-"));
        assertEquals("foreign-q", Files.readString(racedBackup.get()));
        assertEquals("external-target", Files.readString(target));
    }

    @Test
    void normalRestoreNeverReportsAForeignReplacementAtTheDeletedWitnessPath()
            throws Exception {
        assertRestoreWitnessDeleteThenReplacement(false);
    }

    @Test
    void moveThenThrowRestoreNeverReportsAForeignReplacementAtTheDeletedWitnessPath()
            throws Exception {
        assertRestoreWitnessDeleteThenReplacement(true);
    }

    @Test
    void mutationThroughAnOldHandleAfterDisplacementIsRestoredAndReportedChanged() throws Exception {
        Path target = Files.writeString(directory.resolve("open-handle.sql"), "old-content");
        try (FileChannel oldHandle = FileChannel.open(target, StandardOpenOption.WRITE)) {
            SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                    SqlScriptFileStoreTest::moveNoReplace, path -> Files.deleteIfExists(path),
                    ignored -> { }, SqlScriptFileStoreTest::identity, () -> {
                        try {
                            oldHandle.position(0);
                            oldHandle.write(ByteBuffer.wrap("mutated-old".getBytes(StandardCharsets.UTF_8)));
                            oldHandle.truncate("mutated-old".length());
                        } catch (IOException failure) {
                            throw new AssertionError(failure);
                        }
                    });

            SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                    () -> store.save(store.capture(target), "ours"));
            assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
            assertEquals("mutated-old", Files.readString(target));
            assertNull(failure.recoveryPath());
        }
    }

    @Test
    void backupMutationAfterPublishRollsOursAsideRestoresTheMutationAndLeavesNoArtifacts()
            throws Exception {
        Path target = Files.writeString(directory.resolve("rollback.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination);
                    int move = moves.incrementAndGet();
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "mutated-backup");
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals("mutated-backup", Files.readString(target));
        assertTrue(transactionArtifacts().isEmpty());
    }

    @Test
    void rollbackMoveThatThrowsAfterMovingIsReconciledRestoredAndCleaned() throws Exception {
        Path target = Files.writeString(directory.resolve("rollback-move-throw.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) {
                        backup.set(destination);
                    } else if (move == 2) {
                        Files.writeString(backup.get(), "external-old");
                    } else if (move == 3) {
                        rollback.set(destination);
                        throw new IOException("rollback completed before boundary failure");
                    }
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals("external-old", Files.readString(target));
        assertFalse(Files.exists(rollback.get()));
        assertTrue(transactionArtifacts().isEmpty());
    }

    @Test
    void foreignRollbackCreatedAfterVacancyIsPreservedButNeverReportedAsOurs() throws Exception {
        Path target = Files.writeString(directory.resolve("foreign-rollback.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> foreignRollback = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    if (move == 3) {
                        foreignRollback.set(destination);
                        Files.writeString(destination, "foreign-r");
                        throw new IOException("rollback destination appeared");
                    }
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "external-old");
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertNull(failure.temporaryPath());
        assertEquals("external-old", Files.readString(failure.recoveryPath()));
        assertEquals("ours", Files.readString(target));
        assertEquals("foreign-r", Files.readString(foreignRollback.get()));
    }

    @Test
    void identityOwnedRollbackIsReportedEvenWhenItsBytesChangedThroughAnOldHandle()
            throws Exception {
        Path target = Files.writeString(directory.resolve("owned-mutated-rollback.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "external-old");
                    else if (move == 3) {
                        rollback.set(destination);
                        Files.writeString(destination, "mutated-r");
                        throw new IOException("rollback moved then mutated");
                    }
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(rollback.get(), failure.temporaryPath());
        assertEquals("mutated-r", Files.readString(failure.temporaryPath()));
        assertEquals("external-old", Files.readString(failure.recoveryPath()));
        assertFalse(Files.exists(target));
    }

    @Test
    void restoreFailureCombinesTheStillOwnedRollbackWithTheBackupRecoveryPath() throws Exception {
        Path target = Files.writeString(directory.resolve("restore-failure-owned-r.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        FileTime stable = FileTime.fromMillis(444_444_444L);
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 4) throw new IOException("restore stopped");
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "external-old");
                    else if (move == 3) rollback.set(destination);
                }, path -> Files.deleteIfExists(path), ignored -> { }, path ->
                new SqlScriptFileStore.TemporaryIdentity("owned-temp", stable, null), () -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(rollback.get(), failure.temporaryPath());
        assertEquals("ours", Files.readString(failure.temporaryPath()));
        assertEquals(backup.get(), failure.recoveryPath());
        assertEquals("external-old", Files.readString(failure.recoveryPath()));
        assertFalse(Files.exists(target));
    }

    @Test
    void rollbackRecheckNeverReportsAForeignReplacementCreatedDuringRestore() throws Exception {
        Path target = Files.writeString(directory.resolve("restore-foreign-r.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicBoolean rollbackForeign = new AtomicBoolean();
        AtomicInteger moves = new AtomicInteger();
        FileTime stable = FileTime.fromMillis(555_555_555L);
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 4) {
                        Files.delete(rollback.get());
                        Files.writeString(rollback.get(), "foreign-r");
                        rollbackForeign.set(true);
                        throw new IOException("restore stopped after R replacement");
                    }
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "external-old");
                    else if (move == 3) rollback.set(destination);
                }, path -> Files.deleteIfExists(path), ignored -> { }, path ->
                new SqlScriptFileStore.TemporaryIdentity(
                        rollbackForeign.get() && path.equals(rollback.get())
                                ? "foreign-r" : "owned-temp", stable, null), () -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertNull(failure.temporaryPath());
        assertEquals(backup.get(), failure.recoveryPath());
        assertEquals("foreign-r", Files.readString(rollback.get()));
        assertFalse(Files.exists(target));
    }

    @Test
    void rollbackCleanupDeleteThenForeignReplaceNeverReportsTheForeignPath() throws Exception {
        Path target = Files.writeString(directory.resolve("cleanup-foreign-r.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicBoolean rollbackForeign = new AtomicBoolean();
        AtomicInteger moves = new AtomicInteger();
        FileTime stable = FileTime.fromMillis(666_666_666L);
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "external-old");
                    else if (move == 3) rollback.set(destination);
                }, path -> {
                    if (path.equals(rollback.get())) {
                        Files.delete(path);
                        Files.writeString(path, "foreign-r");
                        rollbackForeign.set(true);
                        throw new IOException("cleanup stopped after replacement");
                    }
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> new SqlScriptFileStore.TemporaryIdentity(
                        rollbackForeign.get() && path.equals(rollback.get())
                                ? "foreign-r" : "owned-temp", stable, null), () -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertNull(failure.temporaryPath());
        assertFalse(rollback.get().equals(failure.recoveryPath()));
        assertEquals("external-old", Files.readString(failure.recoveryPath()));
        assertEquals("foreign-r", Files.readString(rollback.get()));
        assertEquals("external-old", Files.readString(target));
    }

    @Test
    void nullKeyRollbackCleanerReturnCannotAuthenticateSameCreationForeignReplacement()
            throws Exception {
        assertNullKeyRollbackCleanerReplacement(false);
    }

    @Test
    void nullKeyRollbackCleanerThrowCannotAuthenticateSameCreationForeignReplacement()
            throws Exception {
        assertNullKeyRollbackCleanerReplacement(true);
    }

    @Test
    void rollbackCleanupFailureReportsAnIdentityOwnedMutationAsTemporary() throws Exception {
        Path target = Files.writeString(directory.resolve("cleanup-owned-r.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        FileTime stable = FileTime.fromMillis(777_777_777L);
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "external-old");
                    else if (move == 3) rollback.set(destination);
                }, path -> {
                    if (path.equals(rollback.get())) {
                        Files.writeString(path, "mutated-r");
                        throw new IOException("cleanup stopped after mutation");
                    }
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> new SqlScriptFileStore.TemporaryIdentity(
                        "owned-temp", stable, null), () -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(rollback.get(), failure.temporaryPath());
        assertEquals("mutated-r", Files.readString(failure.temporaryPath()));
        assertEquals("external-old", Files.readString(failure.recoveryPath()));
        assertEquals("external-old", Files.readString(target));
    }

    @Test
    void nullKeyRetainedRollbackKeepsItsLiveWitnessAndGuard() throws Exception {
        Path target = Files.writeString(directory.resolve("rollback-witness-order.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicReference<Path> temporaryWitness = new AtomicReference<>();
        AtomicReference<Path> temporaryGuard = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        List<String> events = new java.util.ArrayList<>();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "changed-old");
                    else if (move == 3) rollback.set(destination);
                }, path -> {
                    if (path.equals(rollback.get())) {
                        events.add("rollback");
                        throw new IOException("rollback cleanup stopped");
                    }
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-owner-")) temporaryWitness.set(candidate);
                    else if (prefix.equals(".datacube-sql-owner-guard-")) {
                        temporaryGuard.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(temporaryWitness.get())) {
                            events.add("witness");
                            FileTime originalCreation = Files.readAttributes(path,
                                    BasicFileAttributes.class).creationTime();
                            Files.delete(path);
                            Files.writeString(path, "foreign-witness");
                            Files.getFileAttributeView(path, BasicFileAttributeView.class)
                                    .setTimes(null, null, FileTime.fromMillis(
                                            originalCreation.toMillis() + 60_000));
                            return false;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(List.of("rollback"), events);
        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(rollback.get(), failure.temporaryPath());
        assertEquals("ours", Files.readString(failure.temporaryPath()));
        assertEquals("ours", Files.readString(temporaryWitness.get()));
        assertEquals("ours", Files.readString(temporaryGuard.get()));
        assertTrue(failure.retainedPaths().contains(temporaryWitness.get()));
        assertTrue(failure.retainedPaths().contains(temporaryGuard.get()));
    }

    @Test
    void sameContentForeignRollbackAndWitnessAreNeverAcceptedAsRelocatedTemporary()
            throws Exception {
        Path target = Files.writeString(directory.resolve("foreign-relocated-rollback.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicReference<Path> temporaryWitness = new AtomicReference<>();
        AtomicReference<Path> temporaryWitnessGuard = new AtomicReference<>();
        AtomicBoolean rollbackCleanerCalled = new AtomicBoolean();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "changed-old");
                    else if (move == 3) {
                        rollback.set(destination);
                        FileTime originalCreation = Files.readAttributes(
                                temporaryWitness.get(), BasicFileAttributes.class).creationTime();
                        Files.delete(destination);
                        Files.delete(temporaryWitness.get());
                        Files.writeString(destination, "ours");
                        Files.createLink(temporaryWitness.get(), destination);
                        Files.getFileAttributeView(destination, BasicFileAttributeView.class)
                                .setTimes(null, null, FileTime.fromMillis(
                                        originalCreation.toMillis() + 60_000));
                    }
                }, path -> {
                    if (path.equals(rollback.get())) rollbackCleanerCalled.set(true);
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-owner-")) temporaryWitness.set(candidate);
                    else if (prefix.equals(".datacube-sql-owner-guard-")) {
                        temporaryWitnessGuard.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertFalse(rollbackCleanerCalled.get());
        assertEquals(3, moves.get());
        assertFalse(Files.exists(target));
        assertEquals("changed-old", Files.readString(failure.recoveryPath()));
        assertEquals("ours", Files.readString(rollback.get()));
        assertEquals("ours", Files.readString(temporaryWitness.get()));
        assertEquals(temporaryWitnessGuard.get(), failure.temporaryPath());
        assertEquals("ours", Files.readString(temporaryWitnessGuard.get()));
        assertFalse(failure.retainedPaths().contains(rollback.get()));
        assertFalse(failure.retainedPaths().contains(temporaryWitness.get()));
    }

    @Test
    void orphanCleanupRechecksAReplacedWitnessAfterCleanerReturns() throws Exception {
        assertOrphanWitnessReplacementAfterCleanup(false);
    }

    @Test
    void orphanCleanupRechecksAReplacedWitnessAfterCleanerThrows() throws Exception {
        assertOrphanWitnessReplacementAfterCleanup(true);
    }

    @Test
    void backupAndRollbackNameCollisionsAreNeverDeletedOrOverwritten() throws Exception {
        Path target = Files.writeString(directory.resolve("collision-target.sql"), "old");
        Path qCollision = Files.writeString(directory.resolve("q-collision.tmp"), "unrelated-q");
        SqlScriptFileStore qStore = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                SqlScriptFileStoreTest::moveNoReplace, path -> Files.deleteIfExists(path), ignored -> { },
                SqlScriptFileStoreTest::identity, () -> { },
                (parent, prefix) -> prefix.equals(".datacube-sql-backup-")
                        ? qCollision : parent.resolve(prefix + "unused.tmp"));
        SqlScriptFileStore.Failure qFailure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> qStore.save(qStore.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.PUBLISH, qFailure.code());
        assertEquals("old", Files.readString(target));
        assertEquals("unrelated-q", Files.readString(qCollision));

        Path rollbackTarget = Files.writeString(directory.resolve("r-target.sql"), "old-r");
        Path rollbackCollision = Files.writeString(directory.resolve("r-collision.tmp"), "unrelated-r");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore rStore = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (moves.getAndIncrement() == 0) backup.set(destination);
                    else if (moves.get() == 2) Files.writeString(backup.get(), "changed-r-backup");
                }, path -> Files.deleteIfExists(path), ignored -> { }, SqlScriptFileStoreTest::identity,
                () -> { }, (parent, prefix) -> switch (prefix) {
                    case ".datacube-sql-backup-" -> parent.resolve("chosen-q.tmp");
                    case ".datacube-sql-backup-owner-" -> parent.resolve("chosen-q-owner.tmp");
                    case ".datacube-sql-rollback-" -> rollbackCollision;
                    default -> parent.resolve(prefix + "unused.tmp");
                });
        SqlScriptFileStore.Failure rFailure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> rStore.save(rStore.capture(rollbackTarget), "ours-r"));
        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, rFailure.code());
        assertEquals("unrelated-r", Files.readString(rollbackCollision));
        assertEquals("ours-r", Files.readString(rollbackTarget));
        assertEquals("changed-r-backup", Files.readString(rFailure.recoveryPath()));
    }

    @Test
    void nullFileKeySuccessRemovesTemporaryBackupRollbackAndWitnessArtifacts() throws Exception {
        Path target = Files.writeString(directory.resolve("null-key-success.sql"), "old");
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                SqlScriptFileStoreTest::moveNoReplace, path -> Files.deleteIfExists(path), ignored -> { },
                path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { });

        SqlScriptFileStore.Loaded saved = store.save(store.capture(target), "new");

        assertEquals("new", saved.text());
        assertEquals("new", Files.readString(target));
        assertTrue(transactionArtifacts().isEmpty());
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
    void displacementSeamNeverRestoresAReplacementMovedToTheBackupName() throws Exception {
        Path target = Files.writeString(directory.resolve("boundary.sql"), "old");
        AtomicReference<Path> displaced = new AtomicReference<>();
        SqlScriptFileStore store = store(Files::readAllBytes, SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.delete(source);
                    Files.writeString(source, "external");
                    Files.move(source, destination);
                    displaced.set(destination);
                }, path -> Files.deleteIfExists(path), ignored -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("old", Files.readString(failure.recoveryPath()));
        assertEquals("external", Files.readString(displaced.get()));
        assertFalse(Files.exists(target));
    }

    @Test
    void writeAndNoReplaceMoveFailuresPreserveOldBytesAndRemoveOwnedTemps() throws Exception {
        Path target = Files.writeString(directory.resolve("safe.sql"), "old");
        AtomicReference<Path> writtenTemp = new AtomicReference<>();
        SqlScriptFileStore brokenWriter = store(Files::readAllBytes, (path, bytes) -> {
            writtenTemp.set(path);
            Files.writeString(path, "partial");
            throw new IOException("private SQL fragment");
        }, SqlScriptFileStoreTest::moveNoReplace, path -> Files.deleteIfExists(path), ignored -> { });
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
            throw new IOException("test move failure");
        }, path -> Files.deleteIfExists(path), ignored -> { });
        SqlScriptFileStore.Failure publishFailure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> brokenMove.save(brokenMove.capture(target), "new"));
        assertEquals(SqlScriptFileStore.FailureCode.PUBLISH, publishFailure.code());
        assertEquals("old", Files.readString(target));
        assertFalse(Files.exists(moveTemp.get()));
        assertTrue(transactionArtifacts().isEmpty());
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
        }, SqlScriptFileStoreTest::moveNoReplace, path -> Files.deleteIfExists(path), ignored -> { });
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
        }, SqlScriptFileStoreTest::moveNoReplace,
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
    void changedFailureRetainsItsCodeAndAlsoReportsTheUncleanedTemporary() throws Exception {
        Path target = directory.resolve("changed-cleanup.sql");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> diagnosed = new AtomicReference<>();
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            Files.write(path, bytes);
        }, (source, destination, finalCheck) -> {
            finalCheck.verify();
            Files.writeString(destination, "external");
            Files.move(source, destination);
        }, path -> { throw new IOException("private cleanup detail"); }, diagnosed::set);

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.CHANGED, failure.code());
        assertEquals(temporary.get(), failure.temporaryPath());
        assertNull(failure.recoveryPath());
        assertEquals(temporary.get(), diagnosed.get());
        assertEquals("ours", Files.readString(temporary.get()));
        assertEquals("external", Files.readString(target));
        assertFalse(failure.getMessage().contains(temporary.get().toString()));
        assertFalse(failure.getMessage().contains(target.toString()));
    }

    @Test
    void recoveryFailureRetainsItsArtifactAndAlsoReportsTheUncleanedTemporary() throws Exception {
        Path target = Files.writeString(directory.resolve("recovery-cleanup.sql"), "original");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> diagnosed = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            Files.write(path, bytes);
        }, (source, destination, finalCheck) -> {
            finalCheck.verify();
            Files.move(source, destination);
            if (moves.getAndIncrement() == 0) backup.set(destination);
        }, path -> { throw new IOException("private cleanup detail"); }, diagnosed::set,
                SqlScriptFileStoreTest::identity, () -> {
                    try {
                        Files.writeString(target, "external");
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(temporary.get(), failure.temporaryPath());
        assertEquals(backup.get(), failure.recoveryPath());
        assertEquals(temporary.get(), diagnosed.get());
        assertEquals("ours", Files.readString(temporary.get()));
        assertEquals("original", Files.readString(failure.recoveryPath()));
        assertEquals("external", Files.readString(target));
        assertFalse(failure.getMessage().contains(temporary.get().toString()));
        assertFalse(failure.getMessage().contains(failure.recoveryPath().toString()));
    }

    @Test
    void backupDeletionFailureKeepsItsWitnessAndReportsTheBackupNotTheActiveTarget()
            throws Exception {
        Path target = Files.writeString(directory.resolve("backup-cleanup-failure.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (moves.getAndIncrement() == 0) backup.set(destination);
                }, path -> Files.deleteIfExists(path), ignored -> { },
                SqlScriptFileStoreTest::identity, () -> { },
                (parent, prefix) -> parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp"),
                path -> !path.equals(backup.get()));

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(backup.get(), failure.recoveryPath());
        assertEquals("old", Files.readString(failure.recoveryPath()));
        assertEquals("ours", Files.readString(target));
        assertTrue(transactionArtifacts().stream()
                .filter(path -> !path.equals(backup.get()))
                .anyMatch(path -> isSameFile(path, backup.get())));
    }

    @Test
    void backupDeleteThenForeignReplacementReportsTheStillOriginalWitness() throws Exception {
        Path target = Files.writeString(directory.resolve("backup-delete-replaced.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (moves.getAndIncrement() == 0) backup.set(destination);
                }, path -> Files.deleteIfExists(path), ignored -> { },
                SqlScriptFileStoreTest::identity, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) witness.set(candidate);
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(backup.get())) {
                            Files.delete(path);
                            Files.writeString(path, "foreign-q");
                            return false;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(witness.get(), failure.recoveryPath());
        assertEquals("old", Files.readString(failure.recoveryPath()));
        assertEquals("foreign-q", Files.readString(backup.get()));
        assertFalse(backup.get().equals(failure.recoveryPath()));
        assertEquals("ours", Files.readString(target));
    }

    @Test
    void witnessDeleteThenForeignReplacementIsNeitherDeletedNorReported() throws Exception {
        Path target = Files.writeString(directory.resolve("witness-delete-replaced.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (moves.getAndIncrement() == 0) backup.set(destination);
                }, path -> Files.deleteIfExists(path), ignored -> { },
                SqlScriptFileStoreTest::identity, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) witness.set(candidate);
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(backup.get())) return Files.deleteIfExists(path);
                        if (path.equals(witness.get())) {
                            FileTime originalCreation = Files.readAttributes(path,
                                    BasicFileAttributes.class).creationTime();
                            Files.delete(path);
                            Files.writeString(path, "foreign-witness");
                            Files.getFileAttributeView(path, BasicFileAttributeView.class)
                                    .setTimes(null, null, FileTime.fromMillis(
                                            originalCreation.toMillis() + 60_000));
                            return false;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertNull(failure.recoveryPath());
        assertEquals("foreign-witness", Files.readString(witness.get()));
        assertEquals("ours", Files.readString(target));
    }

    @Test
    void restoredTargetReplacementIsNotAuthenticatedWhenWitnessDeleterReturnsTrue()
            throws Exception {
        assertRestoredTargetReplacement(WitnessDeleteResult.TRUE);
    }

    @Test
    void restoredTargetReplacementIsNotAuthenticatedWhenWitnessDeleterReturnsFalse()
            throws Exception {
        assertRestoredTargetReplacement(WitnessDeleteResult.FALSE);
    }

    @Test
    void restoredTargetReplacementIsNotAuthenticatedWhenWitnessDeleterThrows()
            throws Exception {
        assertRestoredTargetReplacement(WitnessDeleteResult.THROW);
    }

    @Test
    void restoredGuardCreationFailureReconcilesTheAlreadyCreatedGuard() throws Exception {
        assertPartialRestoredGuardCreation(false);
    }

    @Test
    void restoredGuardIdentityFailureReconcilesBothCreatedAliases() throws Exception {
        assertPartialRestoredGuardCreation(true);
    }

    @Test
    void restoredGuardCleanupReportsTheGuardWhenItsDeletionReturnsFalse() throws Exception {
        assertPartialRestoredGuardCleanup(GuardDeleteResult.FALSE);
    }

    @Test
    void restoredGuardCleanupReportsTheGuardWhenItsDeletionThrows() throws Exception {
        assertPartialRestoredGuardCleanup(GuardDeleteResult.THROW);
    }

    @Test
    void restoredGuardCleanupNeverReportsOrDeletesAReplacementAtTheGuardPath()
            throws Exception {
        assertPartialRestoredGuardCleanup(GuardDeleteResult.REPLACE);
    }

    @Test
    void backupWitnessWithoutCapturedIdentityIsDeletedWhileStillAnchoredToTheTarget()
            throws Exception {
        for (BackupWitnessIdentityFailure identityFailure
                : BackupWitnessIdentityFailure.values()) {
            assertBackupWitnessIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.SUCCESS);
        }
    }

    @Test
    void backupWitnessWithoutCapturedIdentityIsReportedWhenAnchoredDeletionReturnsFalse()
            throws Exception {
        for (BackupWitnessIdentityFailure identityFailure
                : BackupWitnessIdentityFailure.values()) {
            assertBackupWitnessIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.FALSE);
        }
    }

    @Test
    void backupWitnessWithoutCapturedIdentityIsReportedWhenAnchoredDeletionThrows()
            throws Exception {
        for (BackupWitnessIdentityFailure identityFailure
                : BackupWitnessIdentityFailure.values()) {
            assertBackupWitnessIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.THROW);
        }
    }

    @Test
    void backupWitnessReplacementAfterUncapturedIdentityIsUntouchedAndUnreported()
            throws Exception {
        for (BackupWitnessIdentityFailure identityFailure
                : BackupWitnessIdentityFailure.values()) {
            assertBackupWitnessIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.REPLACE);
        }
    }

    @Test
    void temporaryWitnessGuardWithoutCapturedIdentityIsCleanedBeforeWriting()
            throws Exception {
        for (TemporaryGuardIdentityFailure identityFailure
                : TemporaryGuardIdentityFailure.values()) {
            assertTemporaryGuardIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.SUCCESS);
        }
    }

    @Test
    void temporaryWitnessGuardWithoutCapturedIdentityReportsOwnedSurvivorsOnFalse()
            throws Exception {
        for (TemporaryGuardIdentityFailure identityFailure
                : TemporaryGuardIdentityFailure.values()) {
            assertTemporaryGuardIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.FALSE);
        }
    }

    @Test
    void temporaryWitnessGuardWithoutCapturedIdentityReportsOwnedSurvivorsOnThrow()
            throws Exception {
        for (TemporaryGuardIdentityFailure identityFailure
                : TemporaryGuardIdentityFailure.values()) {
            assertTemporaryGuardIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.THROW);
        }
    }

    @Test
    void temporaryWitnessGuardReplacementIsUntouchedAndUnreported()
            throws Exception {
        for (TemporaryGuardIdentityFailure identityFailure
                : TemporaryGuardIdentityFailure.values()) {
            assertTemporaryGuardIdentityFailure(identityFailure,
                    PartialAliasDeleteResult.REPLACE);
        }
    }

    @Test
    void capturedTemporaryWitnessTrueDeletionCannotAuthorizeAReplacementNullKeyGuard()
            throws Exception {
        assertCapturedTemporaryGuardReplacement(CapturedGuardIdentity.NULL_KEY,
                WitnessDeleteResult.TRUE);
    }

    @Test
    void capturedTemporaryWitnessTrueDeletionCannotAuthorizeAReplacementReusedFileKeyGuard()
            throws Exception {
        assertCapturedTemporaryGuardReplacement(CapturedGuardIdentity.REUSED_FILE_KEY,
                WitnessDeleteResult.TRUE);
    }

    @Test
    void capturedTemporaryWitnessFalseDeletionLeavesAReplacementGuardUntouched()
            throws Exception {
        for (CapturedGuardIdentity identity : CapturedGuardIdentity.values()) {
            assertCapturedTemporaryGuardReplacement(identity, WitnessDeleteResult.FALSE);
        }
    }

    @Test
    void capturedTemporaryWitnessThrowingDeletionLeavesAReplacementGuardUntouched()
            throws Exception {
        for (CapturedGuardIdentity identity : CapturedGuardIdentity.values()) {
            assertCapturedTemporaryGuardReplacement(identity, WitnessDeleteResult.THROW);
        }
    }

    @Test
    void restoredGuardFileKeyReuseWithDifferentCreationTimeIsNotDeletedOrReported()
            throws Exception {
        Path target = Files.writeString(directory.resolve("restored-guard-key-reuse.sql"), "old");
        AtomicReference<Path> backupWitness = new AtomicReference<>();
        AtomicReference<Path> restoredGuard = new AtomicReference<>();
        AtomicReference<Path> restoredGuardWitness = new AtomicReference<>();
        AtomicBoolean guardReplaced = new AtomicBoolean();
        AtomicBoolean foreignGuardDeleteAttempted = new AtomicBoolean();
        AtomicInteger moves = new AtomicInteger();
        FileTime ownedCreation = FileTime.fromMillis(410_000_000L);
        FileTime foreignCreation = FileTime.fromMillis(420_000_000L);
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 2) throw new IOException("publish stopped before moving");
                    finalCheck.verify();
                    Files.move(source, destination);
                }, path -> Files.deleteIfExists(path), ignored -> { }, path -> {
                    if (path.equals(restoredGuard.get())) {
                        return new SqlScriptFileStore.TemporaryIdentity("reused-file-key",
                                guardReplaced.get() ? foreignCreation : ownedCreation, null);
                    }
                    return identity(path);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) {
                        backupWitness.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-")) {
                        restoredGuard.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-guard-")) {
                        restoredGuardWitness.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(backupWitness.get())) {
                            Files.delete(path);
                            Files.delete(restoredGuard.get());
                            Files.writeString(restoredGuard.get(), "foreign-guard");
                            guardReplaced.set(true);
                            return true;
                        }
                        if (path.equals(restoredGuard.get())) {
                            foreignGuardDeleteAttempted.set(true);
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("old", Files.readString(target));
        assertEquals("foreign-guard", Files.readString(restoredGuard.get()));
        assertFalse(foreignGuardDeleteAttempted.get());
        assertFalse(failure.retainedPaths().contains(restoredGuard.get()));
        assertFalse(Files.exists(restoredGuardWitness.get()));
    }

    @Test
    void foreignHardLinkedRestoredGuardPairIsUntouchedAndUnreported() throws Exception {
        assertForeignRestoredGuardPair(false);
    }

    @Test
    void foreignHardLinkedRestoredGuardPairAndTargetSwapAreUntouchedAndUnreported()
            throws Exception {
        assertForeignRestoredGuardPair(true);
    }

    @Test
    void replacedTemporaryFileIsNeverCleanedOrReportedAsRetained() throws Exception {
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
        }, SqlScriptFileStoreTest::moveNoReplace, path -> {
            cleanerCalled.set(true);
            Files.deleteIfExists(path);
        }, diagnosed::set);

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertNull(failure.temporaryPath());
        assertTrue(failure.retainedPaths().isEmpty());
        assertEquals(temporary.get(), diagnosed.get());
        assertFalse(cleanerCalled.get());
        assertEquals("external temporary", Files.readString(temporary.get()));
        assertEquals("old", Files.readString(target));
    }

    @Test
    void replacedFallbackWitnessIsLeftUntouchedButNotReportedAsRetained() throws Exception {
        assumeNullFileKeyProvider();
        Path target = Files.writeString(directory.resolve("witness-target.sql"), "old");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicReference<Path> diagnosed = new AtomicReference<>();
        AtomicBoolean cleanerCalled = new AtomicBoolean();
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            witness.set(findWitness(path));
            Files.delete(witness.get());
            Files.writeString(witness.get(), "external witness");
            throw new IOException("writer stopped");
        }, (source, destination, finalCheck) -> fail("must not publish"), path -> {
            cleanerCalled.set(true);
            Files.deleteIfExists(path);
        }, diagnosed::set);

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertNull(failure.temporaryPath());
        assertTrue(failure.retainedPaths().isEmpty());
        assertEquals(temporary.get(), diagnosed.get());
        assertFalse(cleanerCalled.get());
        assertTrue(Files.exists(temporary.get()));
        assertEquals("external witness", Files.readString(witness.get()));
        assertFalse(witness.get().equals(temporary.get().resolveSibling(
                temporary.get().getFileName() + ".owner")));
        assertEquals("old", Files.readString(target));
    }

    @Test
    void repointedFallbackWitnessCannotAuthorizeReplacementTempCleanup() throws Exception {
        assumeNullFileKeyProvider();
        Path target = Files.writeString(directory.resolve("repoint-target.sql"), "old");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicReference<Path> diagnosed = new AtomicReference<>();
        AtomicBoolean cleanerCalled = new AtomicBoolean();
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            witness.set(findWitness(path));
            FileTime originalCreation = Files.readAttributes(path, BasicFileAttributes.class)
                    .creationTime();
            Files.delete(path);
            Files.writeString(path, "external temporary");
            Files.getFileAttributeView(path, BasicFileAttributeView.class)
                    .setTimes(null, null, FileTime.fromMillis(originalCreation.toMillis() + 60_000));
            Files.delete(witness.get());
            Files.createLink(witness.get(), path);
            throw new IOException("writer stopped");
        }, (source, destination, finalCheck) -> fail("must not publish"), path -> {
            cleanerCalled.set(true);
            Files.deleteIfExists(path);
        }, diagnosed::set);

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertNull(failure.temporaryPath());
        assertTrue(failure.retainedPaths().isEmpty());
        assertEquals(temporary.get(), diagnosed.get());
        assertFalse(cleanerCalled.get());
        assertEquals("external temporary", Files.readString(temporary.get()));
        assertEquals("external temporary", Files.readString(witness.get()));
        assertEquals("old", Files.readString(target));
    }

    @Test
    void deletionOrReplacementAfterPublicationPreservesTheBackupForRecovery() throws Exception {
        assertPostPublicationChange("deleted", false);
        assertPostPublicationChange("replaced", true);
    }

    @Test
    void nullFileKeyPostPublicationLossReportsTheOwnedWitnessInsteadOfTheMissingTemporary()
            throws Exception {
        assertNullFileKeyPostPublicationLoss("deleted", false);
        assertNullFileKeyPostPublicationLoss("replaced", true);
    }

    @Test
    void nullFileKeyForeignWitnessesAreNeitherReportedNorDeleted() throws Exception {
        assertForeignNullFileKeyWitness("ours");
        assertForeignNullFileKeyWitness("different-foreign-content");
    }

    private SqlScriptFileStore store(
            SqlScriptFileStore.ByteReader reader,
            SqlScriptFileStore.ContentWriter writer,
            SqlScriptFileStore.NoReplaceMover mover,
            SqlScriptFileStore.TempCleaner cleaner,
            java.util.function.Consumer<Path> diagnostic) {
        return new SqlScriptFileStore(reader, writer, mover, cleaner, diagnostic);
    }

    private SqlScriptFileStore store(
            SqlScriptFileStore.ByteReader reader,
            SqlScriptFileStore.ContentWriter writer,
            SqlScriptFileStore.NoReplaceMover mover,
            SqlScriptFileStore.TempCleaner cleaner,
            java.util.function.Consumer<Path> diagnostic,
            SqlScriptFileStore.TemporaryIdentityReader identityReader,
            Runnable beforePublish) {
        return new SqlScriptFileStore(reader, writer, mover, cleaner, diagnostic, identityReader,
                beforePublish);
    }

    private SqlScriptFileStore store(
            SqlScriptFileStore.ByteReader reader,
            SqlScriptFileStore.ContentWriter writer,
            SqlScriptFileStore.NoReplaceMover mover,
            SqlScriptFileStore.TempCleaner cleaner,
            java.util.function.Consumer<Path> diagnostic,
            SqlScriptFileStore.TemporaryIdentityReader identityReader,
            Runnable beforePublish,
            SqlScriptFileStore.UniquePathFactory uniquePathFactory) {
        return new SqlScriptFileStore(reader, writer, mover, cleaner, diagnostic, identityReader,
                beforePublish, uniquePathFactory);
    }

    private static void writeBytes(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes);
    }

    private static SqlScriptFileStore.TemporaryIdentity identity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        return new SqlScriptFileStore.TemporaryIdentity(attributes.fileKey(),
                attributes.creationTime(), null);
    }

    private static boolean isSameFile(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException failure) {
            return false;
        }
    }

    private static void moveNoReplace(Path source, Path destination,
            SqlScriptFileStore.FinalTargetVerifier finalCheck) throws IOException {
        finalCheck.verify();
        Files.move(source, destination);
    }

    private void assumeNullFileKeyProvider() throws IOException {
        Path probe = Files.createTempFile(directory, "file-key-probe-", ".tmp");
        try {
            Assumptions.assumeTrue(Files.readAttributes(probe, BasicFileAttributes.class).fileKey() == null,
                    "fallback witness applies only when fileKey is unavailable");
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private Path findWitness(Path temporary) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> !path.equals(temporary)).filter(path -> {
                try {
                    return Files.isSameFile(path, temporary);
                } catch (IOException failure) {
                    return false;
                }
            }).findFirst().orElseThrow();
        }
    }

    private List<Path> transactionArtifacts() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(".datacube-sql-"))
                    .toList();
        }
    }

    private void assertBackupWitnessIdentityFailure(
            BackupWitnessIdentityFailure identityFailure,
            PartialAliasDeleteResult deleteResult) throws Exception {
        String suffix = identityFailure.name().toLowerCase() + "-"
                + deleteResult.name().toLowerCase();
        Path target = Files.writeString(directory.resolve(
                "backup-witness-identity-" + suffix + ".sql"), "old");
        AtomicReference<Path> backupWitness = new AtomicReference<>();
        AtomicBoolean witnessDeleteAttempted = new AtomicBoolean();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, SqlScriptFileStoreTest::moveNoReplace,
                path -> Files.deleteIfExists(path), ignored -> { }, path -> {
                    if (path.equals(backupWitness.get())) {
                        if (identityFailure == BackupWitnessIdentityFailure.THROW) {
                            throw new IOException("backup witness identity unavailable");
                        }
                        return null;
                    }
                    return identity(path);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) {
                        backupWitness.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    try {
                        if (!path.equals(backupWitness.get())) return Files.deleteIfExists(path);
                        witnessDeleteAttempted.set(true);
                        if (deleteResult == PartialAliasDeleteResult.FALSE) return false;
                        if (deleteResult == PartialAliasDeleteResult.THROW) {
                            throw new IllegalStateException("backup witness deletion stopped");
                        }
                        if (deleteResult == PartialAliasDeleteResult.REPLACE) {
                            Files.delete(path);
                            Files.writeString(path, "foreign-witness");
                            return false;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals("old", Files.readString(target));
        assertTrue(witnessDeleteAttempted.get());
        if (deleteResult == PartialAliasDeleteResult.SUCCESS) {
            assertFalse(Files.exists(backupWitness.get()));
            assertFalse(failure.retainedPaths().contains(backupWitness.get()));
        } else if (deleteResult == PartialAliasDeleteResult.REPLACE) {
            assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
            assertEquals("foreign-witness", Files.readString(backupWitness.get()));
            assertFalse(failure.retainedPaths().contains(backupWitness.get()));
            assertNull(failure.recoveryPath());
        } else {
            assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
            assertEquals("old", Files.readString(backupWitness.get()));
            assertEquals(backupWitness.get(), failure.recoveryPath());
            assertTrue(failure.retainedPaths().contains(backupWitness.get()));
        }
    }

    private enum BackupWitnessIdentityFailure {
        NULL, THROW
    }

    private enum PartialAliasDeleteResult {
        SUCCESS, FALSE, THROW, REPLACE
    }

    private void assertTemporaryGuardIdentityFailure(
            TemporaryGuardIdentityFailure identityFailure,
            PartialAliasDeleteResult deleteResult) throws Exception {
        String suffix = identityFailure.name().toLowerCase() + "-"
                + deleteResult.name().toLowerCase();
        Path target = directory.resolve("temporary-guard-identity-" + suffix + ".sql");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicReference<Path> guard = new AtomicReference<>();
        AtomicBoolean guardIdentityRead = new AtomicBoolean();
        AtomicBoolean writerCalled = new AtomicBoolean();
        AtomicInteger guardDeleteCalls = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                (path, bytes) -> {
                    writerCalled.set(true);
                    throw new IOException("writer must not run after guard identity failure");
                }, SqlScriptFileStoreTest::moveNoReplace,
                path -> Files.deleteIfExists(path), ignored -> { }, path -> {
                    if (temporary.get() == null) temporary.set(path);
                    if (path.equals(guard.get())) {
                        guardIdentityRead.set(true);
                        if (identityFailure == TemporaryGuardIdentityFailure.THROW) {
                            throw new IOException("temporary guard identity unavailable");
                        }
                        return null;
                    }
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-owner-")) witness.set(candidate);
                    else if (prefix.equals(".datacube-sql-owner-guard-")) guard.set(candidate);
                    return candidate;
                }, path -> {
                    try {
                        if (!path.equals(guard.get())) return Files.deleteIfExists(path);
                        guardDeleteCalls.incrementAndGet();
                        if (deleteResult == PartialAliasDeleteResult.FALSE) return false;
                        if (deleteResult == PartialAliasDeleteResult.THROW) {
                            throw new IllegalStateException("temporary guard deletion stopped");
                        }
                        if (deleteResult == PartialAliasDeleteResult.REPLACE) {
                            Files.delete(path);
                            Files.writeString(path, "foreign-guard");
                            return false;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertTrue(guardIdentityRead.get());
        assertFalse(writerCalled.get());
        assertEquals(1, guardDeleteCalls.get());
        if (deleteResult == PartialAliasDeleteResult.SUCCESS) {
            assertEquals(SqlScriptFileStore.FailureCode.WRITE, failure.code());
            assertFalse(Files.exists(guard.get()));
            assertFalse(Files.exists(witness.get()));
            assertFalse(Files.exists(temporary.get()));
            assertTrue(failure.retainedPaths().isEmpty());
        } else if (deleteResult == PartialAliasDeleteResult.REPLACE) {
            assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
            assertEquals("foreign-guard", Files.readString(guard.get()));
            assertFalse(Files.exists(witness.get()));
            assertFalse(Files.exists(temporary.get()));
            assertFalse(failure.retainedPaths().contains(guard.get()));
        } else {
            assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
            assertEquals(3, failure.retainedPaths().size());
            assertTrue(failure.retainedPaths().contains(temporary.get()));
            assertTrue(failure.retainedPaths().contains(witness.get()));
            assertTrue(failure.retainedPaths().contains(guard.get()));
            assertTrue(isSameFile(temporary.get(), witness.get()));
            assertTrue(isSameFile(witness.get(), guard.get()));
        }
    }

    private enum TemporaryGuardIdentityFailure {
        NULL, THROW
    }

    private void assertCapturedTemporaryGuardReplacement(CapturedGuardIdentity identity,
            WitnessDeleteResult deleteResult) throws Exception {
        String suffix = identity.name().toLowerCase() + "-"
                + deleteResult.name().toLowerCase();
        Path target = directory.resolve("captured-guard-replacement-" + suffix + ".sql");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicReference<Path> guard = new AtomicReference<>();
        AtomicBoolean guardReplaced = new AtomicBoolean();
        AtomicInteger guardReadsAfterReplacement = new AtomicInteger();
        AtomicInteger witnessDeleteAttempts = new AtomicInteger();
        AtomicBoolean foreignGuardDeleteAttempted = new AtomicBoolean();
        AtomicReference<Object> capturedGuardKey = new AtomicReference<>();
        FileTime ownedCreation = FileTime.fromMillis(510_000_000L);
        FileTime foreignCreation = FileTime.fromMillis(520_000_000L);
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                (path, bytes) -> Files.write(path, bytes),
                (source, destination, finalCheck) -> {
                    throw new IOException("publish stopped before moving");
                }, path -> Files.deleteIfExists(path), ignored -> { }, path -> {
                    if (temporary.get() == null) temporary.set(path);
                    if (path.equals(guard.get())) {
                        if (guardReplaced.get()) guardReadsAfterReplacement.incrementAndGet();
                        Object fileKey = null;
                        if (identity == CapturedGuardIdentity.REUSED_FILE_KEY) {
                            if (!guardReplaced.get()) {
                                fileKey = Files.readAttributes(path, BasicFileAttributes.class,
                                        java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
                                capturedGuardKey.set(fileKey);
                            } else {
                                fileKey = capturedGuardKey.get();
                            }
                        }
                        return new SqlScriptFileStore.TemporaryIdentity(
                                fileKey,
                                guardReplaced.get() ? foreignCreation : ownedCreation, null);
                    }
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-owner-")) witness.set(candidate);
                    else if (prefix.equals(".datacube-sql-owner-guard-")) guard.set(candidate);
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(witness.get())) {
                            witnessDeleteAttempts.incrementAndGet();
                            Files.delete(path);
                            Files.deleteIfExists(guard.get());
                            Files.writeString(guard.get(), "foreign-guard");
                            guardReplaced.set(true);
                            if (deleteResult == WitnessDeleteResult.THROW) {
                                throw new IllegalStateException("witness deletion stopped");
                            }
                            return deleteResult == WitnessDeleteResult.TRUE;
                        }
                        if (path.equals(guard.get()) && guardReplaced.get()) {
                            foreignGuardDeleteAttempted.set(true);
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertFalse(foreignGuardDeleteAttempted.get(),
                "witness deletion must not authorize deletion of the replacement guard");
        assertEquals(1, witnessDeleteAttempts.get());
        assertEquals("foreign-guard", Files.readString(guard.get()));
        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertFalse(Files.exists(witness.get()));
        assertFalse(Files.exists(temporary.get()));
        assertFalse(failure.retainedPaths().contains(guard.get()));
        assertFalse(failure.retainedPaths().contains(witness.get()));
        assertFalse(failure.retainedPaths().contains(temporary.get()));
        if (deleteResult == WitnessDeleteResult.TRUE) {
            assertTrue(guardReadsAfterReplacement.get() > 0);
        }
    }

    private enum CapturedGuardIdentity {
        NULL_KEY, REUSED_FILE_KEY
    }

    private void assertForeignRestoredGuardPair(boolean swapTarget) throws Exception {
        Path target = Files.writeString(directory.resolve(swapTarget
                ? "foreign-restored-pair-target-swap.sql"
                : "foreign-restored-pair.sql"), "old");
        AtomicReference<Path> backupWitness = new AtomicReference<>();
        AtomicReference<Path> restoredGuard = new AtomicReference<>();
        AtomicReference<Path> restoredGuardWitness = new AtomicReference<>();
        AtomicBoolean foreignPairInstalled = new AtomicBoolean();
        AtomicBoolean foreignAliasDeleteAttempted = new AtomicBoolean();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 2) throw new IOException("publish stopped before moving");
                    finalCheck.verify();
                    Files.move(source, destination);
                }, path -> Files.deleteIfExists(path), ignored -> { },
                SqlScriptFileStoreTest::identity, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) {
                        backupWitness.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-")) {
                        restoredGuard.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-guard-")) {
                        restoredGuardWitness.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(backupWitness.get())) {
                            Files.delete(path);
                            Files.delete(restoredGuardWitness.get());
                            Files.delete(restoredGuard.get());
                            if (swapTarget) Files.delete(target);
                            Files.writeString(restoredGuard.get(), "foreign-pair");
                            Files.createLink(restoredGuardWitness.get(), restoredGuard.get());
                            if (swapTarget) Files.createLink(target, restoredGuard.get());
                            foreignPairInstalled.set(true);
                            return true;
                        }
                        if (foreignPairInstalled.get()
                                && (path.equals(restoredGuard.get())
                                || path.equals(restoredGuardWitness.get()))) {
                            foreignAliasDeleteAttempted.set(true);
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertFalse(foreignAliasDeleteAttempted.get());
        assertEquals("foreign-pair", Files.readString(restoredGuard.get()));
        assertEquals("foreign-pair", Files.readString(restoredGuardWitness.get()));
        assertTrue(isSameFile(restoredGuard.get(), restoredGuardWitness.get()));
        assertFalse(failure.retainedPaths().contains(restoredGuard.get()));
        assertFalse(failure.retainedPaths().contains(restoredGuardWitness.get()));
        if (swapTarget) {
            assertEquals("foreign-pair", Files.readString(target));
            assertFalse(failure.retainedPaths().contains(target));
            assertNull(failure.recoveryPath());
        } else {
            assertEquals("old", Files.readString(target));
            assertNull(failure.recoveryPath());
        }
    }

    private void assertPartialRestoredGuardCreation(boolean identityFailure) throws Exception {
        Path target = Files.writeString(directory.resolve(identityFailure
                ? "restored-guard-identity-failure.sql" : "restored-guard-path-failure.sql"),
                "old");
        AtomicReference<Path> backupWitness = new AtomicReference<>();
        AtomicReference<Path> restoredGuard = new AtomicReference<>();
        AtomicReference<Path> restoredGuardWitness = new AtomicReference<>();
        AtomicBoolean guardDeleteAttempted = new AtomicBoolean();
        AtomicBoolean guardWitnessDeleteAttempted = new AtomicBoolean();
        AtomicBoolean guardWitnessIdentityRead = new AtomicBoolean();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 2) throw new IOException("publish stopped before moving");
                    finalCheck.verify();
                    Files.move(source, destination);
                }, path -> Files.deleteIfExists(path), ignored -> { }, path -> {
                    if (path.equals(restoredGuardWitness.get())) {
                        guardWitnessIdentityRead.set(true);
                        throw new IOException("guard witness identity unavailable");
                    }
                    return identity(path);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) {
                        backupWitness.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-")) {
                        restoredGuard.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-guard-")) {
                        if (!identityFailure) {
                            throw new IllegalStateException("guard witness path unavailable");
                        }
                        restoredGuardWitness.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    if (path.equals(restoredGuard.get())) {
                        guardDeleteAttempted.set(true);
                        return false;
                    }
                    if (path.equals(restoredGuardWitness.get())) {
                        guardWitnessDeleteAttempted.set(true);
                        return false;
                    }
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("old", Files.readString(target));
        assertTrue(guardDeleteAttempted.get());
        assertTrue(failure.retainedPaths().contains(restoredGuard.get()));
        assertEquals("old", Files.readString(restoredGuard.get()));
        if (identityFailure) {
            assertTrue(guardWitnessIdentityRead.get());
            assertTrue(guardWitnessDeleteAttempted.get());
            assertTrue(failure.retainedPaths().contains(restoredGuardWitness.get()));
            assertEquals("old", Files.readString(restoredGuardWitness.get()));
        } else {
            assertNull(restoredGuardWitness.get());
            assertFalse(guardWitnessDeleteAttempted.get());
        }
    }

    private void assertPartialRestoredGuardCleanup(GuardDeleteResult deleteResult)
            throws Exception {
        Path target = Files.writeString(directory.resolve(
                "restored-guard-cleanup-" + deleteResult.name().toLowerCase() + ".sql"), "old");
        AtomicReference<Path> backupWitness = new AtomicReference<>();
        AtomicReference<Path> restoredGuard = new AtomicReference<>();
        AtomicReference<Path> restoredGuardWitness = new AtomicReference<>();
        AtomicInteger guardDeleteCalls = new AtomicInteger();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 2) throw new IOException("publish stopped before moving");
                    finalCheck.verify();
                    Files.move(source, destination);
                }, path -> Files.deleteIfExists(path), ignored -> { },
                SqlScriptFileStoreTest::identity, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) {
                        backupWitness.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-")) {
                        restoredGuard.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-guard-")) {
                        restoredGuardWitness.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(restoredGuard.get())) {
                            guardDeleteCalls.incrementAndGet();
                            if (deleteResult == GuardDeleteResult.FALSE) return false;
                            if (deleteResult == GuardDeleteResult.THROW) {
                                throw new IllegalStateException("guard deletion stopped");
                            }
                            Files.delete(path);
                            Files.writeString(path, "foreign-guard");
                            return false;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("old", Files.readString(target));
        assertEquals(1, guardDeleteCalls.get());
        assertFalse(Files.exists(restoredGuardWitness.get()));
        if (deleteResult == GuardDeleteResult.REPLACE) {
            assertEquals("foreign-guard", Files.readString(restoredGuard.get()));
            assertFalse(failure.retainedPaths().contains(restoredGuard.get()));
            assertNull(failure.recoveryPath());
        } else {
            assertEquals("old", Files.readString(restoredGuard.get()));
            assertEquals(restoredGuard.get(), failure.recoveryPath());
            assertTrue(failure.retainedPaths().contains(restoredGuard.get()));
        }
    }

    private enum GuardDeleteResult {
        FALSE, THROW, REPLACE
    }

    private void assertNullKeyRollbackCleanerReplacement(boolean throwAfterReplacement)
            throws Exception {
        Path target = Files.writeString(directory.resolve(throwAfterReplacement
                ? "null-key-cleaner-throw.sql" : "null-key-cleaner-return.sql"), "old");
        AtomicReference<Path> backup = new AtomicReference<>();
        AtomicReference<Path> rollback = new AtomicReference<>();
        AtomicReference<Path> temporaryWitness = new AtomicReference<>();
        AtomicReference<Path> temporaryGuard = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        FileTime stable = FileTime.fromMillis(777_777_777L);
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 1) backup.set(destination);
                    else if (move == 2) Files.writeString(backup.get(), "external-old");
                    else if (move == 3) rollback.set(destination);
                }, path -> {
                    if (path.equals(rollback.get())) {
                        Files.delete(path);
                        Files.writeString(path, "foreign-r");
                        if (throwAfterReplacement) {
                            throw new IOException("cleaner stopped after replacing R");
                        }
                        return;
                    }
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> new SqlScriptFileStore.TemporaryIdentity(
                        null, stable, null), () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-owner-")) temporaryWitness.set(candidate);
                    else if (prefix.equals(".datacube-sql-owner-guard-")) {
                        temporaryGuard.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("external-old", Files.readString(target));
        assertEquals("foreign-r", Files.readString(rollback.get()));
        assertFalse(failure.retainedPaths().contains(rollback.get()));
        assertTrue(failure.retainedPaths().contains(temporaryWitness.get()));
        assertTrue(failure.retainedPaths().contains(temporaryGuard.get()));
        assertEquals("ours", Files.readString(temporaryWitness.get()));
        assertEquals("ours", Files.readString(temporaryGuard.get()));
    }

    private void assertRestoredTargetReplacement(WitnessDeleteResult deleteResult)
            throws Exception {
        Path target = Files.writeString(directory.resolve(
                "restored-target-replaced-" + deleteResult.name().toLowerCase() + ".sql"), "old");
        AtomicReference<Path> backupWitness = new AtomicReference<>();
        AtomicReference<Path> restoredGuard = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        java.util.List<Path> deletedPaths = new java.util.ArrayList<>();
        FileTime stable = FileTime.fromMillis(888_888_888L);
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 2) throw new IOException("publish stopped before moving");
                    finalCheck.verify();
                    Files.move(source, destination);
                }, path -> Files.deleteIfExists(path), ignored -> { }, path ->
                new SqlScriptFileStore.TemporaryIdentity(null, stable, null), () -> { },
                (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) {
                        backupWitness.set(candidate);
                    } else if (prefix.equals(".datacube-sql-restored-owner-")) {
                        restoredGuard.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    deletedPaths.add(path);
                    try {
                        if (path.equals(backupWitness.get())) {
                            Files.delete(path);
                            Files.delete(target);
                            Files.writeString(target, "foreign-restored-target");
                            if (deleteResult == WitnessDeleteResult.THROW) {
                                throw new IllegalStateException("witness deletion boundary stopped");
                            }
                            return deleteResult == WitnessDeleteResult.TRUE;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals("foreign-restored-target", Files.readString(target));
        assertNotNull(restoredGuard.get());
        assertEquals(restoredGuard.get(), failure.recoveryPath());
        assertEquals("old", Files.readString(restoredGuard.get()));
        assertFalse(failure.retainedPaths().contains(target));
        assertFalse(deletedPaths.contains(target));
    }

    private enum WitnessDeleteResult {
        TRUE, FALSE, THROW
    }

    private void assertRestoreWitnessDeleteThenReplacement(boolean throwAfterRestoreMove)
            throws Exception {
        Path target = Files.writeString(directory.resolve(
                throwAfterRestoreMove ? "restore-throw-witness-race.sql"
                        : "restore-witness-race.sql"), "old");
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes, (source, destination, finalCheck) -> {
                    int move = moves.incrementAndGet();
                    if (move == 2) throw new IOException("publish stopped before moving");
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (move == 3 && throwAfterRestoreMove) {
                        throw new IOException("restore moved then stopped");
                    }
                }, path -> Files.deleteIfExists(path), ignored -> { },
                SqlScriptFileStoreTest::identity, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-backup-owner-")) witness.set(candidate);
                    return candidate;
                }, path -> {
                    try {
                        if (path.equals(witness.get())) {
                            FileTime originalCreation = Files.readAttributes(path,
                                    BasicFileAttributes.class).creationTime();
                            Files.delete(path);
                            Files.writeString(path, "foreign-witness");
                            Files.getFileAttributeView(path, BasicFileAttributeView.class)
                                    .setTimes(null, null, FileTime.fromMillis(
                                            originalCreation.toMillis() + 60_000));
                            return false;
                        }
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(target, failure.recoveryPath());
        assertEquals("old", Files.readString(failure.recoveryPath()));
        assertEquals("foreign-witness", Files.readString(witness.get()));
        assertFalse(witness.get().equals(failure.recoveryPath()));
    }

    private void assertOrphanWitnessReplacementAfterCleanup(boolean throwAfterReplacement)
            throws Exception {
        Path target = directory.resolve(throwAfterReplacement
                ? "orphan-witness-throw.sql" : "orphan-witness-return.sql");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicReference<Path> witnessGuard = new AtomicReference<>();
        AtomicBoolean witnessDeleterCalled = new AtomicBoolean();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                (path, bytes) -> {
                    temporary.set(path);
                    Files.write(path, bytes);
                }, (source, destination, finalCheck) -> {
                    throw new IOException("publish stopped before moving");
                }, path -> {
                    if (path.equals(temporary.get())) {
                        FileTime originalCreation = Files.readAttributes(witness.get(),
                                BasicFileAttributes.class).creationTime();
                        Files.delete(path);
                        Files.delete(witness.get());
                        Files.writeString(witness.get(), "foreign-witness");
                        Files.getFileAttributeView(witness.get(), BasicFileAttributeView.class)
                                .setTimes(null, null, FileTime.fromMillis(
                                        originalCreation.toMillis() + 60_000));
                        if (throwAfterReplacement) {
                            throw new IOException("cleanup stopped after witness replacement");
                        }
                        return;
                    }
                    Files.deleteIfExists(path);
                }, ignored -> { }, path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    return new SqlScriptFileStore.TemporaryIdentity(null,
                            attributes.creationTime(), null);
                }, () -> { }, (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-owner-")) witness.set(candidate);
                    else if (prefix.equals(".datacube-sql-owner-guard-")) {
                        witnessGuard.set(candidate);
                    }
                    return candidate;
                }, path -> {
                    if (path.equals(witness.get())) witnessDeleterCalled.set(true);
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.CLEANUP, failure.code());
        assertFalse(witnessDeleterCalled.get());
        assertEquals("foreign-witness", Files.readString(witness.get()));
        assertEquals(witnessGuard.get(), failure.temporaryPath());
        assertEquals("ours", Files.readString(witnessGuard.get()));
        assertFalse(failure.retainedPaths().contains(witness.get()));
    }

    private void assertPostPublicationChange(String name, boolean replace) throws Exception {
        Path target = Files.writeString(directory.resolve("post-" + name + ".sql"), "old");
        Path external = replace
                ? Files.writeString(directory.resolve("external-" + name + ".sql"), "external replacement")
                : null;
        AtomicInteger moves = new AtomicInteger();
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes,
                SqlScriptFileStoreTest::writeBytes,
                (source, destination, finalCheck) -> {
                    finalCheck.verify();
                    Files.move(source, destination);
                    if (moves.incrementAndGet() == 2) {
                        Files.delete(destination);
                        if (external != null) Files.move(external, destination);
                    }
                }, path -> Files.deleteIfExists(path), ignored -> { }, path -> {
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    return new SqlScriptFileStore.TemporaryIdentity(attributes.fileKey(),
                            attributes.creationTime(), null);
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));
        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertTrue(Files.isRegularFile(failure.recoveryPath()));
        assertEquals("old", Files.readString(failure.recoveryPath()));
        if (replace) assertEquals("external replacement", Files.readString(target));
    }

    private void assertNullFileKeyPostPublicationLoss(String name, boolean replace) throws Exception {
        Path target = Files.writeString(directory.resolve("null-post-" + name + ".sql"), "old");
        Path external = replace
                ? Files.writeString(directory.resolve("null-external-" + name + ".sql"), "external")
                : null;
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> temporaryWitness = new AtomicReference<>();
        AtomicInteger moves = new AtomicInteger();
        FileTime stableCreation = FileTime.fromMillis(123_456_789L);
        SqlScriptFileStore store = store(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            temporaryWitness.set(findWitness(path));
            Files.write(path, bytes);
        }, (source, destination, finalCheck) -> {
            finalCheck.verify();
            Files.move(source, destination);
            if (moves.incrementAndGet() == 2) {
                Files.delete(destination);
                if (external != null) Files.move(external, destination);
            }
        }, path -> Files.deleteIfExists(path), ignored -> { }, path ->
                new SqlScriptFileStore.TemporaryIdentity(null, stableCreation, null), () -> { });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(temporaryWitness.get(), failure.temporaryPath());
        assertTrue(Files.exists(failure.temporaryPath()));
        assertEquals("ours", Files.readString(failure.temporaryPath()));
        assertFalse(Files.exists(temporary.get()));
        assertEquals("old", Files.readString(failure.recoveryPath()));
        if (replace) assertEquals("external", Files.readString(target));
        else assertFalse(Files.exists(target));
    }

    private void assertForeignNullFileKeyWitness(String foreignContent) throws Exception {
        Path target = Files.writeString(directory.resolve(
                "foreign-witness-" + foreignContent.length() + ".sql"), "old");
        Path external = Files.writeString(directory.resolve(
                "foreign-witness-external-" + foreignContent.length() + ".sql"), "external");
        AtomicReference<Path> temporary = new AtomicReference<>();
        AtomicReference<Path> witness = new AtomicReference<>();
        AtomicReference<Path> guard = new AtomicReference<>();
        AtomicBoolean witnessReplaced = new AtomicBoolean();
        AtomicInteger moves = new AtomicInteger();
        FileTime ownedCreation = FileTime.fromMillis(222_222_222L);
        FileTime foreignCreation = FileTime.fromMillis(333_333_333L);
        SqlScriptFileStore store = new SqlScriptFileStore(Files::readAllBytes, (path, bytes) -> {
            temporary.set(path);
            Files.write(path, bytes);
        }, (source, destination, finalCheck) -> {
            finalCheck.verify();
            Files.move(source, destination);
            if (moves.incrementAndGet() == 2) {
                Files.delete(witness.get());
                Files.writeString(witness.get(), foreignContent);
                witnessReplaced.set(true);
                Files.delete(destination);
                Files.move(external, destination);
            }
        }, path -> Files.deleteIfExists(path), ignored -> { }, path ->
                new SqlScriptFileStore.TemporaryIdentity(null,
                        witnessReplaced.get() && path.equals(witness.get())
                                ? foreignCreation : ownedCreation, null), () -> { },
                (parent, prefix) -> {
                    Path candidate = parent.resolve(prefix + java.util.UUID.randomUUID() + ".tmp");
                    if (prefix.equals(".datacube-sql-owner-")) witness.set(candidate);
                    else if (prefix.equals(".datacube-sql-owner-guard-")) guard.set(candidate);
                    return candidate;
                }, path -> {
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                });

        SqlScriptFileStore.Failure failure = assertThrows(SqlScriptFileStore.Failure.class,
                () -> store.save(store.capture(target), "ours"));

        assertEquals(SqlScriptFileStore.FailureCode.RECOVERY, failure.code());
        assertEquals(guard.get(), failure.temporaryPath());
        assertEquals("ours", Files.readString(guard.get()));
        assertFalse(failure.retainedPaths().contains(witness.get()));
        assertFalse(Files.exists(temporary.get()));
        assertEquals(foreignContent, Files.readString(witness.get()));
        assertEquals("external", Files.readString(target));
        assertEquals("old", Files.readString(failure.recoveryPath()));
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
