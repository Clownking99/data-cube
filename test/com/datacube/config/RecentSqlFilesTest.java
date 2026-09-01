package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
        assertFixedDiagnostic(writeDiagnostics, writeIndex);

        Path moveIndex = seeded("move.index", old);
        List<String> moveDiagnostics = new ArrayList<>();
        RecentSqlFiles moveFailure = new RecentSqlFiles(moveIndex, RecentSqlFilesTest::write,
                (source, destination) -> { throw new AtomicMoveNotSupportedException("private", "path", "secret"); },
                RecentSqlFilesTest::delete, moveDiagnostics::add);
        byte[] beforeMove = Files.readAllBytes(moveIndex);
        moveFailure.record(replacement);
        assertEquals(List.of(old), moveFailure.recent());
        assertArrayEquals(beforeMove, Files.readAllBytes(moveIndex));
        assertFixedDiagnostic(moveDiagnostics, moveIndex);

        Path deleteIndex = seeded("delete.index", old);
        List<String> deleteDiagnostics = new ArrayList<>();
        RecentSqlFiles deleteFailure = new RecentSqlFiles(deleteIndex, RecentSqlFilesTest::write,
                RecentSqlFilesTest::moveAtomically,
                path -> { throw new IOException("private delete select secret"); }, deleteDiagnostics::add);
        byte[] beforeDelete = Files.readAllBytes(deleteIndex);
        deleteFailure.clear();
        assertEquals(List.of(old), deleteFailure.recent());
        assertArrayEquals(beforeDelete, Files.readAllBytes(deleteIndex));
        assertFixedDiagnostic(deleteDiagnostics, deleteIndex);
        assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated));
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

    private static void assertFixedDiagnostic(List<String> diagnostics, Path privatePath) {
        assertEquals(1, diagnostics.size());
        assertFalse(diagnostics.getFirst().contains("private"));
        assertFalse(diagnostics.getFirst().contains("secret"));
        assertFalse(diagnostics.getFirst().contains(privatePath.toString()));
    }
}
