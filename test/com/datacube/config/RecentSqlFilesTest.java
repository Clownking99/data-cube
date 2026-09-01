package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentSqlFilesTest {
    private static final String HEADER = "DATACUBE_SQL_RECENT_V1";

    @TempDir Path directory;

    @Test
    void recordsNewestFirstDeduplicatesNormalizesCapsAndReturnsSnapshots() {
        RecentSqlFiles recent = new RecentSqlFiles(index("recent.index"));
        Path first = Path.of("synthetic-first.sql");
        Path expectedFirst = first.toAbsolutePath().normalize();

        recent.record(first);
        recent.record(expectedFirst.getParent().resolve(".").resolve(expectedFirst.getFileName()));
        for (int i = 2; i <= 11; i++) recent.record(directory.resolve("entry" + i + ".sql"));

        List<Path> paths = recent.recent();
        assertEquals(10, paths.size());
        assertEquals(directory.resolve("entry11.sql").toAbsolutePath().normalize(), paths.getFirst());
        assertFalse(paths.contains(expectedFirst));
        assertThrows(UnsupportedOperationException.class, paths::clear);
        assertEquals(paths, recent.recent());
    }

    @Test
    void createsAMissingTrustedParentForTheFirstRecord() throws Exception {
        Path missingParent = directory.resolve("fresh-profile").resolve("nested");
        Path index = missingParent.resolve("recent.index");
        RecentSqlFiles recent = new RecentSqlFiles(index);

        Path recorded = directory.resolve("first.sql").toAbsolutePath().normalize();
        recent.record(recorded);

        assertTrue(Files.isDirectory(missingParent));
        assertEquals(List.of(recorded), recent.recent());
        assertTrue(Files.isRegularFile(index));
    }

    @Test
    void persistsOnlyVersionedBase64PathsAndReloads() throws Exception {
        Path index = index("persist.index");
        Path older = directory.resolve("older.sql");
        Path newer = directory.resolve("newer.sql");
        RecentSqlFiles recent = new RecentSqlFiles(index);

        recent.record(older);
        recent.record(newer);

        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        assertEquals(HEADER, lines.getFirst());
        assertEquals(Base64.getEncoder().encodeToString(newer.toAbsolutePath().normalize()
                .toString().getBytes(StandardCharsets.UTF_8)), lines.get(1));
        assertEquals(3, lines.size());
        assertFalse(Files.readString(index).contains("select private SQL"));
        assertEquals(List.of(newer.toAbsolutePath().normalize(), older.toAbsolutePath().normalize()),
                new RecentSqlFiles(index).recent());
    }

    @Test
    void ignoresMalformedEntriesAndFailsClosedForInvalidHeaderOrOversizedIndex() throws Exception {
        Path valid = directory.resolve("valid.sql").toAbsolutePath().normalize();
        Path index = index("invalid.index");
        Files.writeString(index, HEADER + "\nnot base64\n" + encoded("relative.sql") + "\n"
                + encoded(valid.toString()) + "\n" + encoded(valid.toString()) + "\n",
                StandardCharsets.UTF_8);
        assertEquals(List.of(valid), new RecentSqlFiles(index).recent());

        Files.writeString(index, "WRONG\n" + encoded(valid.toString()) + "\n", StandardCharsets.UTF_8);
        assertTrue(new RecentSqlFiles(index).recent().isEmpty());

        Files.write(index, new byte[128 * 1024 + 1]);
        assertTrue(new RecentSqlFiles(index).recent().isEmpty());
    }

    @Test
    void boundedNoFollowLoadAcceptsExactMaximumAndRejectsMaximumPlusOneAndSymlinks()
            throws Exception {
        Path valid = directory.resolve("boundary.sql").toAbsolutePath().normalize();
        byte[] prefix = (HEADER + "\n" + encoded(valid.toString()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] exact = new byte[RecentSqlFiles.MAX_INDEX_BYTES];
        Arrays.fill(exact, (byte) '\n');
        System.arraycopy(prefix, 0, exact, 0, prefix.length);
        Path index = Files.write(index("exact.index"), exact);
        assertEquals(List.of(valid), new RecentSqlFiles(index).recent());

        Files.write(index, Arrays.copyOf(exact, exact.length + 1));
        assertTrue(new RecentSqlFiles(index).recent().isEmpty());

        Path actual = seeded("actual.index", valid);
        Path link = index("linked.index");
        try {
            Files.createSymbolicLink(link, actual.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable for this account");
        }
        assertTrue(new RecentSqlFiles(link).recent().isEmpty());
    }

    @Test
    void rejectsNonDirectoryAndSymlinkParentsWithoutPublishing() throws Exception {
        Path nonDirectory = Files.writeString(directory.resolve("not-a-directory"), "keep");
        List<String> diagnostics = new ArrayList<>();
        RecentSqlFiles invalid = new RecentSqlFiles(nonDirectory.resolve("recent.index"),
                RecentSqlFilesTest::write, RecentSqlFilesTest::moveAtomically,
                RecentSqlFilesTest::delete, diagnostics::add);
        invalid.record(directory.resolve("ignored.sql"));
        assertTrue(invalid.recent().isEmpty());
        assertEquals("keep", Files.readString(nonDirectory));
        assertEquals(List.of("Unable to save recent SQL files."), diagnostics);

        Path actualParent = Files.createDirectory(directory.resolve("actual-parent"));
        Path linkedParent = directory.resolve("linked-parent");
        try {
            Files.createSymbolicLink(linkedParent, actualParent.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable for this account");
        }
        diagnostics.clear();
        RecentSqlFiles linked = new RecentSqlFiles(linkedParent.resolve("recent.index"),
                RecentSqlFilesTest::write, RecentSqlFilesTest::moveAtomically,
                RecentSqlFilesTest::delete, diagnostics::add);
        linked.record(directory.resolve("ignored.sql"));
        assertTrue(linked.recent().isEmpty());
        assertFalse(Files.exists(actualParent.resolve("recent.index")));
        assertEquals(List.of("Unable to save recent SQL files."), diagnostics);

        diagnostics.clear();
        Path missingThroughLink = linkedParent.resolve("must-not-create");
        RecentSqlFiles nestedLinked = new RecentSqlFiles(missingThroughLink.resolve("recent.index"),
                RecentSqlFilesTest::write, RecentSqlFilesTest::moveAtomically,
                RecentSqlFilesTest::delete, diagnostics::add);
        nestedLinked.record(directory.resolve("ignored.sql"));
        assertTrue(nestedLinked.recent().isEmpty());
        assertFalse(Files.exists(actualParent.resolve("must-not-create")));
        assertEquals(List.of("Unable to save recent SQL files."), diagnostics);
    }

    @Test
    void clearDeletesOnlyConfiguredIndexAfterSuccessfulDeletion() throws Exception {
        Path index = index("clear.index");
        Path unrelated = directory.resolve("unrelated.txt");
        byte[] unrelatedBytes = "keep this sibling".getBytes(StandardCharsets.UTF_8);
        Files.write(unrelated, unrelatedBytes);
        RecentSqlFiles recent = new RecentSqlFiles(index);
        recent.record(directory.resolve("one.sql"));

        recent.clear();

        assertTrue(recent.recent().isEmpty());
        assertFalse(Files.exists(index));
        assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated));
    }

    @Test
    void writeMoveAndDeleteFailuresKeepPreviousMemoryAndPrivateDetailsOutOfDiagnostics() throws Exception {
        Path old = directory.resolve("old.sql").toAbsolutePath().normalize();
        Path replacement = directory.resolve("replacement.sql").toAbsolutePath().normalize();
        Path unrelated = directory.resolve("unrelated.bin");
        byte[] unrelatedBytes = {7, 8, 9};
        Files.write(unrelated, unrelatedBytes);

        Path writeIndex = seeded("write.index", old);
        List<String> writeDiagnostics = new ArrayList<>();
        RecentSqlFiles writeFailure = new RecentSqlFiles(writeIndex,
                (path, bytes) -> { throw new IOException("private write select secret"); },
                RecentSqlFilesTest::moveAtomically, RecentSqlFilesTest::delete, writeDiagnostics::add);
        byte[] beforeWrite = Files.readAllBytes(writeIndex);
        writeFailure.record(replacement);
        assertEquals(List.of(old), writeFailure.recent());
        assertArrayEquals(beforeWrite, Files.readAllBytes(writeIndex));
        assertFixedDiagnostic(writeDiagnostics, "Unable to save recent SQL files.", writeIndex,
                "private write select secret", "IOException");

        Path moveIndex = seeded("move.index", old);
        List<String> moveDiagnostics = new ArrayList<>();
        RecentSqlFiles moveFailure = new RecentSqlFiles(moveIndex, RecentSqlFilesTest::write,
                (source, destination) -> { throw new AtomicMoveNotSupportedException("private", "path", "secret"); },
                RecentSqlFilesTest::delete, moveDiagnostics::add);
        byte[] beforeMove = Files.readAllBytes(moveIndex);
        moveFailure.record(replacement);
        assertEquals(List.of(old), moveFailure.recent());
        assertArrayEquals(beforeMove, Files.readAllBytes(moveIndex));
        assertFixedDiagnostic(moveDiagnostics, "Unable to save recent SQL files.", moveIndex,
                "private path secret", "AtomicMoveNotSupportedException");

        Path deleteIndex = seeded("delete.index", old);
        List<String> deleteDiagnostics = new ArrayList<>();
        RecentSqlFiles deleteFailure = new RecentSqlFiles(deleteIndex, RecentSqlFilesTest::write,
                RecentSqlFilesTest::moveAtomically,
                path -> { throw new IOException("private delete select secret"); }, deleteDiagnostics::add);
        byte[] beforeDelete = Files.readAllBytes(deleteIndex);
        deleteFailure.clear();
        assertEquals(List.of(old), deleteFailure.recent());
        assertArrayEquals(beforeDelete, Files.readAllBytes(deleteIndex));
        assertFixedDiagnostic(deleteDiagnostics, "Unable to clear recent SQL files.", deleteIndex,
                "private delete select secret", "IOException");
        assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated));
    }

    @Test
    void rejectsReplacedOwnedTemporaryBeforePublishingOrCleaningTheReplacement() throws Exception {
        Path index = seeded("replacement.index", directory.resolve("old.sql").toAbsolutePath().normalize());
        byte[] originalIndex = Files.readAllBytes(index);
        Path unrelated = directory.resolve("unrelated-replacement.txt");
        byte[] unrelatedBytes = "unchanged sibling".getBytes(StandardCharsets.UTF_8);
        Files.write(unrelated, unrelatedBytes);
        List<String> diagnostics = new ArrayList<>();
        List<Path> replacement = new ArrayList<>();
        RecentSqlFiles recent = new RecentSqlFiles(index, (temporary, bytes) -> {
            Files.delete(temporary);
            Files.writeString(temporary, "ordinary replacement", StandardCharsets.UTF_8);
            replacement.add(temporary);
        }, RecentSqlFilesTest::moveAtomically, RecentSqlFilesTest::delete, diagnostics::add);

        recent.record(directory.resolve("new.sql"));

        assertEquals(List.of(directory.resolve("old.sql").toAbsolutePath().normalize()), recent.recent());
        assertArrayEquals(originalIndex, Files.readAllBytes(index));
        assertEquals(1, replacement.size());
        assertEquals("ordinary replacement", Files.readString(replacement.getFirst(), StandardCharsets.UTF_8));
        assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated));
        assertFixedDiagnostic(diagnostics, "Unable to save recent SQL files.", index,
                "ordinary replacement", "IOException");
    }

    @Test
    void rejectsOversizedAndHostileRecordCandidatesWithoutChangingPriorState() throws Exception {
        Path old = directory.resolve("old.sql").toAbsolutePath().normalize();
        Path index = seeded("bounded.index", old);
        byte[] originalIndex = Files.readAllBytes(index);
        List<String> diagnostics = new ArrayList<>();
        RecentSqlFiles recent = new RecentSqlFiles(index, RecentSqlFilesTest::write,
                RecentSqlFilesTest::moveAtomically, RecentSqlFilesTest::delete, diagnostics::add);

        recent.record(Path.of("x".repeat(RecentSqlFiles.MAX_PATH_CHARS + 1)));
        assertUnchangedAfterFailedRecord(recent, List.of(old), index, originalIndex, diagnostics,
                "oversized path");

        Path serializedIndex = seeded("serialized.index", old);
        RecentSqlFiles serialized = new RecentSqlFiles(serializedIndex, RecentSqlFilesTest::write,
                RecentSqlFilesTest::moveAtomically, RecentSqlFilesTest::delete, diagnostics::add);
        Path root = directory.toAbsolutePath().getRoot();
        String prefix = "汉".repeat(RecentSqlFiles.MAX_PATH_CHARS - root.toString().length() - 1);
        for (int i = 0; i < 7; i++) serialized.record(root.resolve(prefix + i));
        List<Path> beforeSerializedFailure = serialized.recent();
        byte[] beforeSerializedFailureBytes = Files.readAllBytes(serializedIndex);
        diagnostics.clear();
        serialized.record(root.resolve(prefix + 7));
        assertUnchangedAfterFailedRecord(serialized, beforeSerializedFailure, serializedIndex,
                beforeSerializedFailureBytes, diagnostics, "oversized index");

        diagnostics.clear();
        Path hostile = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("toAbsolutePath")) {
                        throw new IllegalStateException("private hostile SQL path detail");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        recent.record(hostile);
        assertUnchangedAfterFailedRecord(recent, List.of(old), index, originalIndex, diagnostics,
                "private hostile SQL path detail");
    }

    @Test
    void nullFileKeySuccessfulPublicationRemovesItsOwnedWitness() throws Exception {
        Path index = index("null-key-success.index");
        RecentSqlFiles recent = recentWithNullFileKeys(index, new ArrayList<>());

        recent.record(directory.resolve("saved.sql"));

        assertEquals(List.of(directory.resolve("saved.sql").toAbsolutePath().normalize()), recent.recent());
        assertTrue(ownerWitnesses().isEmpty());
    }

    @Test
    void nullFileKeyTemporaryReplacementKeepsReplacementButRemovesOwnedWitness() throws Exception {
        Path old = directory.resolve("old.sql").toAbsolutePath().normalize();
        Path index = seeded("null-key-temp-replacement.index", old);
        byte[] originalIndex = Files.readAllBytes(index);
        List<String> diagnostics = new ArrayList<>();
        List<Path> replacement = new ArrayList<>();
        RecentSqlFiles recent = recentWithNullFileKeys(index, diagnostics, (temporary, bytes) -> {
            Files.delete(temporary);
            Files.writeString(temporary, "temporary replacement", StandardCharsets.UTF_8);
            replacement.add(temporary);
        });

        recent.record(directory.resolve("new.sql"));

        assertEquals(List.of(old), recent.recent());
        assertArrayEquals(originalIndex, Files.readAllBytes(index));
        assertEquals("temporary replacement", Files.readString(replacement.getFirst(), StandardCharsets.UTF_8));
        assertTrue(ownerWitnesses().isEmpty());
        assertFixedDiagnostic(diagnostics, "Unable to save recent SQL files.", index,
                "temporary replacement", "IOException");
    }

    @Test
    void nullFileKeyWitnessReplacementIsRetainedAndPreventsPublication() throws Exception {
        Path old = directory.resolve("old.sql").toAbsolutePath().normalize();
        Path index = seeded("null-key-witness-replacement.index", old);
        byte[] originalIndex = Files.readAllBytes(index);
        List<String> diagnostics = new ArrayList<>();
        RecentSqlFiles recent = recentWithNullFileKeys(index, diagnostics, (temporary, bytes) -> {
            Files.write(temporary, bytes);
            Path witness = ownerWitnesses().getFirst();
            Files.delete(witness);
            Files.writeString(witness, "witness replacement", StandardCharsets.UTF_8);
        });

        recent.record(directory.resolve("new.sql"));

        assertEquals(List.of(old), recent.recent());
        assertArrayEquals(originalIndex, Files.readAllBytes(index));
        List<Path> witnesses = ownerWitnesses();
        assertEquals(1, witnesses.size());
        assertEquals("witness replacement", Files.readString(witnesses.getFirst(), StandardCharsets.UTF_8));
        assertFixedDiagnostic(diagnostics, "Unable to save recent SQL files.", index,
                "witness replacement", "IOException");
    }

    @Test
    void rejectsNullRequiredInputs() {
        assertThrows(NullPointerException.class, () -> new RecentSqlFiles(null));
        RecentSqlFiles recent = new RecentSqlFiles(index("null.index"));
        assertThrows(NullPointerException.class, () -> recent.record(null));
    }

    private Path index(String name) {
        return directory.resolve(name);
    }

    private Path seeded(String name, Path path) throws IOException {
        Path index = index(name);
        Files.writeString(index, HEADER + "\n" + encoded(path.toString()) + "\n", StandardCharsets.UTF_8);
        return index;
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes);
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    private RecentSqlFiles recentWithNullFileKeys(Path index, List<String> diagnostics) {
        return recentWithNullFileKeys(index, diagnostics, RecentSqlFilesTest::write);
    }

    private RecentSqlFiles recentWithNullFileKeys(Path index, List<String> diagnostics,
            RecentSqlFiles.ContentWriter writer) {
        return new RecentSqlFiles(index, writer, RecentSqlFilesTest::moveAtomically,
                RecentSqlFilesTest::delete, diagnostics::add,
                path -> new RecentSqlFiles.TemporaryIdentity(null, FileTime.fromMillis(
                        Files.readString(path, StandardCharsets.UTF_8).contains("replacement") ? 2 : 1), null));
    }

    private List<Path> ownerWitnesses() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(".datacube-recent-owner-"))
                    .toList();
        }
    }

    private static void assertUnchangedAfterFailedRecord(RecentSqlFiles recent, List<Path> expected, Path index,
            byte[] originalIndex, List<String> diagnostics, String exceptionDetail) throws IOException {
        assertEquals(expected, recent.recent());
        assertArrayEquals(originalIndex, Files.readAllBytes(index));
        assertFixedDiagnostic(diagnostics, "Unable to save recent SQL files.", index,
                "select secret", exceptionDetail);
    }

    private static void assertFixedDiagnostic(List<String> diagnostics, String expected, Path privatePath,
            String injectedSql, String exceptionDetail) {
        assertEquals(1, diagnostics.size());
        assertEquals(expected, diagnostics.getFirst());
        assertFalse(diagnostics.getFirst().contains(injectedSql));
        assertFalse(diagnostics.getFirst().contains(privatePath.toString()));
        assertFalse(diagnostics.getFirst().contains(exceptionDetail));
    }
}
