package com.datacube.config;

import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftStoreTest {
    @TempDir Path temp;
    private static final long NOW = 1788000000000L;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private Path root() { return temp.resolve("drafts"); }
    private static SqlDraft draft(int id, long modified, String sql) {
        return new SqlDraft(new UUID(0, id), modified, "synthetic-id", DbType.ORACLE,
                "Synthetic connection", " schema ", sql);
    }
    private Path file(UUID id) { return root().resolve(id + ".draft"); }

    @Test void savesDistinctIdsReplacesExactlyAndRecoversAfterReopen() throws Exception {
        SqlDraft first = draft(1, NOW, " \r\nselect '中文😀';\n ");
        SqlDraft second = draft(2, NOW + 1, first.sql());
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            assertTrue(store.snapshot().protectionEnabled());
            store.save(first); store.save(second);
            assertEquals(List.of(second, first), store.snapshot().drafts());
            SqlDraft cleared = draft(1, NOW + 2, "");
            store.save(cleared);
            assertEquals(List.of(cleared, second), store.snapshot().drafts());
            assertArrayEquals(SqlDraftCodec.encode(cleared), Files.readAllBytes(file(first.id())));
            assertThrows(UnsupportedOperationException.class, () -> store.snapshot().drafts().clear());
        }
        try (SqlDraftStore reopened = SqlDraftStore.open(root())) {
            assertEquals(List.of(draft(1, NOW + 2, ""), second), reopened.snapshot().drafts());
        }
    }

    @Test void disableIsPersistedExactlyAndKeepsRecoverableDrafts() throws Exception {
        SqlDraft saved = draft(1, NOW, "secret synthetic SQL");
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.save(saved);
            store.setEnabled(false);
            assertArrayEquals(ByteBuffer.allocate(9).putInt(0x44434450).putInt(1).put((byte) 0).array(),
                    Files.readAllBytes(root().resolve("preferences.bin")));
            assertFalse(store.snapshot().protectionEnabled());
            assertCode(SqlDraftStore.FailureCode.DISABLED, () -> store.save(draft(2, NOW, "select 2")));
            assertEquals(List.of(saved), store.snapshot().drafts());
        }
        try (SqlDraftStore reopened = SqlDraftStore.open(root())) {
            assertFalse(reopened.snapshot().protectionEnabled());
            reopened.delete(saved.id());
            assertTrue(reopened.snapshot().drafts().isEmpty());
            reopened.setEnabled(true);
            reopened.save(saved);
            assertEquals(List.of(saved), reopened.snapshot().drafts());
        }
    }

    @Test void invalidPreferenceNeverDefaultsOnOrHidesValidDrafts() throws Exception {
        SqlDraft saved = draft(1, NOW, "select 1");
        try (SqlDraftStore store = SqlDraftStore.open(root())) { store.save(saved); }
        byte[][] invalid = { {}, {1}, ByteBuffer.allocate(9).putInt(0).putInt(1).put((byte) 1).array(),
                ByteBuffer.allocate(9).putInt(0x44434450).putInt(2).put((byte) 1).array(),
                ByteBuffer.allocate(9).putInt(0x44434450).putInt(1).put((byte) 2).array(),
                ByteBuffer.allocate(10).putInt(0x44434450).putInt(1).put((byte) 1).array() };
        for (byte[] bytes : invalid) {
            Files.write(root().resolve("preferences.bin"), bytes);
            try (SqlDraftStore store = SqlDraftStore.open(root())) {
                SqlDraftStore.Snapshot snapshot = store.snapshot();
                assertFalse(snapshot.protectionEnabled()); assertFalse(snapshot.writable());
                assertEquals(List.of(saved), snapshot.drafts());
                assertTrue(snapshot.problems().stream().anyMatch(p -> p.code() == SqlDraftStore.ProblemCode.INVALID_PREFERENCES));
                assertCode(SqlDraftStore.FailureCode.PREFERENCE_CORRUPT, () -> store.setEnabled(true));
                assertCode(SqlDraftStore.FailureCode.PREFERENCE_CORRUPT, () -> store.setEnabled(false));
                assertCode(SqlDraftStore.FailureCode.UNAVAILABLE, () -> store.save(saved));
                assertArrayEquals(bytes, Files.readAllBytes(root().resolve("preferences.bin")));
            }
        }
    }

    @Test void atomicFailureKeepsOldDraftAndPreference() throws Exception {
        SqlDraft saved = draft(1, NOW, "select 1");
        try (SqlDraftStore seed = SqlDraftStore.open(root())) { seed.save(saved); seed.setEnabled(true); }
        byte[] before = Files.readAllBytes(root().resolve("preferences.bin"));
        try (SqlDraftStore store = new SqlDraftStore(SqlDraftDirectory.open(root(), SqlDraftDirectory::writeForced,
                (source, target) -> { throw new AtomicMoveNotSupportedException("synthetic", "synthetic", "test"); }, Files::deleteIfExists))) {
            assertThrows(IOException.class, () -> store.save(draft(1, NOW + 1, "select changed")));
            assertThrows(IOException.class, () -> store.setEnabled(false));
            assertTrue(store.snapshot().protectionEnabled());
            assertEquals(List.of(saved), store.snapshot().drafts());
            assertArrayEquals(before, Files.readAllBytes(root().resolve("preferences.bin")));
        }
    }

    @Test void corruptUnknownAndMismatchedFilesArePreservedWithValidNeighbors() throws Exception {
        SqlDraft good = draft(1, NOW, "select 1");
        try (SqlDraftStore store = SqlDraftStore.open(root())) { store.save(good); }
        byte[] unknown = SqlDraftCodec.encode(draft(2, NOW, "synthetic private text"));
        ByteBuffer.wrap(unknown).putInt(4, 2);
        Files.write(file(new UUID(0, 2)), unknown);
        byte[] malformed = {1, 2};
        Files.write(file(new UUID(0, 3)), malformed);
        byte[] mismatch = SqlDraftCodec.encode(draft(40, NOW, "select 40"));
        Files.write(file(new UUID(0, 4)), mismatch);
        Files.writeString(root().resolve("unrelated.txt"), "keep unrelated");
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            assertEquals(List.of(good), store.snapshot().drafts());
            assertEquals(3, store.snapshot().problems().size());
            assertTrue(store.snapshot().writable());
            assertCode(SqlDraftStore.FailureCode.PROTECTED_DRAFT, () -> store.save(draft(2, NOW + 1, "changed")));
            assertCode(SqlDraftStore.FailureCode.PROTECTED_DRAFT, () -> store.delete(new UUID(0, 3)));
            assertEquals(1, store.clearRecoverable());
            assertTrue(store.snapshot().drafts().isEmpty());
            assertEquals(3, store.snapshot().problems().size());
            assertArrayEquals(unknown, Files.readAllBytes(file(new UUID(0, 2))));
            assertArrayEquals(malformed, Files.readAllBytes(file(new UUID(0, 3))));
            assertArrayEquals(mismatch, Files.readAllBytes(file(new UUID(0, 4))));
            assertEquals("keep unrelated", Files.readString(root().resolve("unrelated.txt")));
        }
    }

    @Test void countLimitAllowsReplacementButNeverEvictsOtherDrafts() throws Exception {
        Files.createDirectory(root());
        for (int i = 1; i <= 100; i++) Files.write(file(new UUID(0, i)), SqlDraftCodec.encode(draft(i, NOW, "select " + i)));
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            assertEquals(100, store.snapshot().drafts().size());
            assertCode(SqlDraftStore.FailureCode.CAPACITY, () -> store.save(draft(101, NOW, "select 101")));
            store.save(draft(1, NOW + 1, "changed"));
            assertEquals(100, store.snapshot().drafts().size());
            assertEquals("changed", store.snapshot().drafts().getFirst().sql());
            assertFalse(Files.exists(file(new UUID(0, 101))));
            assertEquals(draft(100, NOW, "select 100"), SqlDraftCodec.decode(Files.readAllBytes(file(new UUID(0, 100)))));
            store.setEnabled(false);
            assertFalse(store.snapshot().protectionEnabled());
        }
    }

    @Test void totalByteBoundaryUsesPublishedBytesAndRetainsOldVersion() throws Exception {
        Files.createDirectory(root());
        String payload = "x".repeat(1024 * 1024 - 52);
        for (int i = 1; i <= 32; i++) {
            SqlDraft value = new SqlDraft(new UUID(0, i), NOW, null, null, null, null, payload);
            byte[] bytes = SqlDraftCodec.encode(value);
            assertEquals(1024 * 1024, bytes.length);
            Files.write(file(value.id()), bytes);
        }
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            assertEquals(32, store.snapshot().drafts().size());
            SqlDraft tooLarge = new SqlDraft(new UUID(0, 1), NOW + 1, null, null, null, null, payload + "x");
            assertCode(SqlDraftStore.FailureCode.CAPACITY, () -> store.save(tooLarge));
            assertEquals(payload, SqlDraftCodec.decode(Files.readAllBytes(file(tooLarge.id()))).sql());
            SqlDraft smaller = new SqlDraft(tooLarge.id(), NOW + 2, null, null, null, null, payload.substring(1));
            store.save(smaller);
            assertEquals(smaller, store.snapshot().drafts().getFirst());
        }
    }

    @Test void invalidNewSnapshotNeverReplacesLastSuccessfulBytes() throws Exception {
        SqlDraft saved = draft(1, NOW, "select 1");
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.save(saved);
            byte[] before = Files.readAllBytes(file(saved.id()));
            assertCode(SqlDraftStore.FailureCode.INVALID_DRAFT,
                    () -> store.save(draft(1, NOW + 1, "x".repeat(1024 * 1024 + 1))));
            assertCode(SqlDraftStore.FailureCode.INVALID_DRAFT, () -> store.save(null));
            assertArrayEquals(before, Files.readAllBytes(file(saved.id())));
            assertEquals(List.of(saved), store.snapshot().drafts());
        }
    }

    @Test void externallyOverfullDirectoryIsPreservedAndCanStillPersistDisable() throws Exception {
        Files.createDirectory(root());
        for (int i = 1; i <= 101; i++) Files.write(file(new UUID(0, i)), SqlDraftCodec.encode(draft(i, NOW, "select " + i)));
        byte[] before = Files.readAllBytes(file(new UUID(0, 1)));
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            assertCode(SqlDraftStore.FailureCode.CAPACITY, store::snapshot);
            assertCode(SqlDraftStore.FailureCode.CAPACITY, () -> store.save(draft(1, NOW + 1, "changed")));
            store.setEnabled(false);
            assertArrayEquals(ByteBuffer.allocate(9).putInt(0x44434450).putInt(1).put((byte) 0).array(),
                    Files.readAllBytes(root().resolve("preferences.bin")));
            assertArrayEquals(before, Files.readAllBytes(file(new UUID(0, 1))));
            assertTrue(Files.isRegularFile(file(new UUID(0, 101))));
        }
    }

    @Test void expiryUsesEmbeddedTimeAndPreservesOpenFutureAndInvalidEntries() throws Exception {
        SqlDraft expired = draft(1, NOW - WEEK, "expired");
        SqlDraft recent = draft(2, NOW - WEEK + 1, "recent");
        SqlDraft open = draft(3, NOW - WEEK - 1, "open");
        SqlDraft future = draft(4, NOW + 1, "future");
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            for (SqlDraft value : List.of(expired, recent, open, future)) store.save(value);
            Files.write(file(new UUID(0, 5)), new byte[]{9});
            assertEquals(1, store.pruneExpired(NOW, Set.of(open.id())));
            assertEquals(List.of(future, recent, open), store.snapshot().drafts());
            assertFalse(Files.exists(file(expired.id())));
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(file(new UUID(0, 5))));
            assertEquals(0, store.pruneExpired(WEEK - 1, Set.of()));
            assertEquals(1, store.snapshot().problems().size());
        }
    }

    @Test void unreadableOversizeEntryDisablesSavingWithoutHidingNeighbor() throws Exception {
        SqlDraft good = draft(1, NOW, "select 1");
        try (SqlDraftStore store = SqlDraftStore.open(root())) {
            store.save(good);
            Files.write(file(new UUID(0, 2)), new byte[SqlDraftCodec.MAX_FILE_BYTES + 1]);
            assertEquals(List.of(good), store.snapshot().drafts());
            assertFalse(store.snapshot().writable());
            assertTrue(store.snapshot().problems().stream().anyMatch(p -> p.code() == SqlDraftStore.ProblemCode.UNREADABLE_DRAFT));
            assertCode(SqlDraftStore.FailureCode.UNAVAILABLE, () -> store.save(draft(3, NOW, "new")));
            store.setEnabled(false);
            assertFalse(store.snapshot().protectionEnabled());
        }
    }

    private static void assertCode(SqlDraftStore.FailureCode code, org.junit.jupiter.api.function.Executable action) {
        SqlDraftStore.Failure failure = assertThrows(SqlDraftStore.Failure.class, action);
        assertEquals(code, failure.code());
        assertNull(failure.getCause());
    }
}
