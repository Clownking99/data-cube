# SQL Draft Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete P1.2 policy: exact draft persistence/reopen, strict local preference, bounded capacity, protected invalid files and explicit retention/deletion.

**Architecture:** SqlDraftStore composes the validated codec and locked directory. Synchronized methods provide serial operations but perform blocking I/O, so the later coordinator must call them off FX. No default path or UI is introduced here, and save never evaluates SQL or resolves connection identities.

**Tech Stack:** Java25, existing SqlDraft/SqlDraftCodec/SqlDraftDirectory, JUnit Jupiter5.11.3, Gradle9.2.0.

## Global Constraints

- Java25 / JavaFX25 / JUnit Jupiter5.11.3；不增加第三方依赖，不改 JDBC、历史文件或导出语义。
- 仅使用合成文本、临时目录与替身网关；不读取、不修改、不暂存、不清理 `.testagent/`。
- 不新增网络、遥测、AI、数据库自动请求、密码存储或结果/事务持久化；不推送、打 tag、安装或发布。
- SQL 保留空白、换行和 Unicode 原文；不按 SQL 去重、不截断；编码/容量超限必须显式失败并保留已有版本。
- 每草稿 SQL 最多1MiB UTF-8；每个可空元数据字符串最多4096 UTF-8字节；最多100个草稿、正式草稿文件合计32MiB。
- 7天保留期以草稿内修改时间计算；启动或打开管理页在单写者锁内清理已知有效且未打开的过期草稿。未知版本/损坏文件不自动删除。
- 启停偏好使用同目录单独版本化文件及相同原子发布策略；其损坏或未知版本导致自动保存不可用，而非回退开启。
- Worktree `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`; no UI/real user data, push/tag/merge. Task owns only source/test paths below.

---

### Task 1: Local draft storage policies

**Files:**
- Create: `src/com/datacube/config/SqlDraftStore.java` — bounded persistence, recoverable list, strict preference, safe expiry/deletion.
- Test: `test/com/datacube/config/SqlDraftStoreTest.java` — isolated real files, codec fixtures, directory failure seams.

**Interfaces:**
- Consumes: SqlDraft record and static SqlDraftCodec.encode/decode; SqlDraftDirectory.open, entries, read, publish, delete, close.
- Produces: `public SqlDraftStore.open(Path) throws IOException`, package-private constructor(SqlDraftDirectory) for injected faults, `snapshot(): Snapshot`, `save(SqlDraft)`, `setEnabled(boolean)`, `delete(UUID)`, `clearRecoverable(): int`, `pruneExpired(long,Set<UUID>): int`, close; all I/O methods synchronized and IOException.
- Snapshot contains immutable draft/problem lists, `protectionEnabled`, `writable`. List order descending modifiedAt, ID lexical tie-break. SqlDraft.toString already redacts SQL; Problem is ID+enum only.
- Preference bytes are exactly9 bytes: big-endian int0x44434450 (`DCDP`), int1, byte0/1. Absent means enabled; invalid preference means protectionEnabled=false/writable=false with a problem, but valid drafts can still be listed. setEnabled refuses to overwrite unknown/broken preference automatically and only confirms success after atomic publication.
- Canonical UUID.draft only. Embedded ID must match filename. Unknown/corrupt same-ID files cannot be saved over or explicitly deleted through recoverable-entry APIs. clearRecoverable deletes only verified recoverable entries; problems/unknown files remain and must be visible in later UI. Close keeps all files.
- A read failure leaves an UNREADABLE_DRAFT problem and makes saving unavailable because total capacity cannot be proven; other readable drafts still load. A corrupt but bounded readable file counts toward count/bytes and only protects its own ID. An external directory already beyond100 formal names or32MiB is unavailable; no eviction/cleanup is attempted on an unbounded/over-capacity scan. Directory's512-entry cap still applies.
- Preference toggle does not need to read SQL or enforce draft capacity; persist disable even if draft count is full, provided directory scanning and preference validation succeed. Save checks preference anew. Clear/delete are explicit actions and remain allowed while protection is disabled.
- Retention expires at age>=7 days. Future timestamps, open IDs and unreadable/corrupt entries are preserved. A candidate changed since the snapshot is preserved. Application decides when to prune and must pass all open IDs.
- No durability/UI success claim beyond successfully returned atomic publish. On partial clear/prune failure, propagate a sanitized failure; caller must reload and never claim all entries were deleted. Generation barriers and prevention of queued write resurrection belong to coordinator.

- [ ] **Step 1: Add compilable stub and complete behavioral tests.**

`src/com/datacube/config/SqlDraftStore.java`:

```java
package com.datacube.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SqlDraftStore implements AutoCloseable {
    public enum ProblemCode { CORRUPT_DRAFT, UNREADABLE_DRAFT, INVALID_PREFERENCES }
    public enum FailureCode { UNAVAILABLE, DISABLED, CAPACITY, INVALID_DRAFT, PROTECTED_DRAFT, PREFERENCE_CORRUPT }
    public record Problem(UUID draftId, ProblemCode code) { }
    public record Snapshot(List<SqlDraft> drafts, List<Problem> problems, boolean protectionEnabled, boolean writable) { }
    public static final class Failure extends IOException {
        private final FailureCode code;
        Failure(FailureCode code) { super("SQL draft store failed: " + code); this.code = code; }
        public FailureCode code() { return code; }
    }
    SqlDraftStore(SqlDraftDirectory directory) { }
    public static SqlDraftStore open(Path path) throws IOException { return new SqlDraftStore(null); }
    public Snapshot snapshot() throws IOException { return new Snapshot(List.of(), List.of(), true, true); }
    public void save(SqlDraft draft) throws IOException { }
    public void setEnabled(boolean enabled) throws IOException { }
    public void delete(UUID id) throws IOException { }
    public int clearRecoverable() throws IOException { return 0; }
    public int pruneExpired(long now, Set<UUID> openIds) throws IOException { return 0; }
    @Override public void close() throws IOException { }
}
```

`test/com/datacube/config/SqlDraftStoreTest.java`:

```java
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
        byte[][] invalid = { {}, {1}, ByteBuffer.allocate(9).putInt(0x44434450).putInt(2).put((byte) 1).array(),
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
```

- [ ] **Step 2: Run RED before implementing policies.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftStoreTest --rerun-tasks --no-daemon --console=plain
```

Expected exit1 from missing save/reopen/policy behavior against compilable stubs. Record actual output before Step3.

- [ ] **Step 3: Complete store implementation.**

`src/com/datacube/config/SqlDraftStore.java`:

```java
package com.datacube.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Blocking local draft storage; invoke off the JavaFX application thread. */
public final class SqlDraftStore implements AutoCloseable {
    public enum ProblemCode { CORRUPT_DRAFT, UNREADABLE_DRAFT, INVALID_PREFERENCES }
    public enum FailureCode { UNAVAILABLE, DISABLED, CAPACITY, INVALID_DRAFT, PROTECTED_DRAFT, PREFERENCE_CORRUPT }
    public record Problem(UUID draftId, ProblemCode code) { }
    public record Snapshot(List<SqlDraft> drafts, List<Problem> problems, boolean protectionEnabled, boolean writable) {
        public Snapshot { drafts = List.copyOf(drafts); problems = List.copyOf(problems); }
    }
    public static final class Failure extends IOException {
        private final FailureCode code;
        Failure(FailureCode code) { super("SQL draft store failed: " + code); this.code = code; }
        public FailureCode code() { return code; }
    }
    private record Preference(boolean valid, boolean enabled) { }
    private record Inspection(Snapshot snapshot, Map<UUID, Integer> lengths, Set<UUID> rejected, long totalBytes) { }
    private static final int MAX_DRAFTS = 100;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final int PREFERENCE_MAGIC = 0x44434450;
    private static final String PREFERENCE_FILE = "preferences.bin";
    private final SqlDraftDirectory directory;

    SqlDraftStore(SqlDraftDirectory directory) { this.directory = Objects.requireNonNull(directory); }

    public static SqlDraftStore open(Path path) throws IOException {
        return new SqlDraftStore(SqlDraftDirectory.open(path));
    }

    public synchronized Snapshot snapshot() throws IOException { return inspect().snapshot(); }

    public synchronized void save(SqlDraft draft) throws IOException {
        byte[] bytes;
        try { bytes = SqlDraftCodec.encode(draft); }
        catch (IOException invalid) { throw new Failure(FailureCode.INVALID_DRAFT); }
        Inspection inspection = inspect();
        if (!inspection.snapshot().writable()) throw new Failure(FailureCode.UNAVAILABLE);
        if (!inspection.snapshot().protectionEnabled()) throw new Failure(FailureCode.DISABLED);
        if (inspection.rejected().contains(draft.id())) throw new Failure(FailureCode.PROTECTED_DRAFT);
        Integer previous = inspection.lengths().get(draft.id());
        if ((previous == null && inspection.lengths().size() >= MAX_DRAFTS)
                || inspection.totalBytes() - (previous == null ? 0 : previous) + bytes.length > MAX_TOTAL_BYTES) {
            throw new Failure(FailureCode.CAPACITY);
        }
        directory.publish(filename(draft.id()), bytes);
    }

    public synchronized void setEnabled(boolean enabled) throws IOException {
        directory.entries();
        Preference preference = preference();
        if (!preference.valid()) throw new Failure(FailureCode.PREFERENCE_CORRUPT);
        byte[] bytes = ByteBuffer.allocate(9).putInt(PREFERENCE_MAGIC).putInt(1).put((byte) (enabled ? 1 : 0)).array();
        directory.publish(PREFERENCE_FILE, bytes);
    }

    public synchronized void delete(UUID id) throws IOException {
        SqlDraft value = readVerified(Objects.requireNonNull(id));
        if (value != null) directory.delete(filename(id));
    }

    public synchronized int clearRecoverable() throws IOException {
        List<SqlDraft> candidates = inspect().snapshot().drafts();
        int deleted = 0;
        for (SqlDraft candidate : candidates) {
            if (candidate.equals(readVerified(candidate.id()))) {
                directory.delete(filename(candidate.id()));
                deleted++;
            }
        }
        return deleted;
    }

    public synchronized int pruneExpired(long now, Set<UUID> openIds) throws IOException {
        if (now < 0) throw new IllegalArgumentException("Invalid draft retention time");
        Set<UUID> opened = Set.copyOf(openIds);
        if (now < RETENTION_MILLIS) return 0;
        long cutoff = now - RETENTION_MILLIS;
        int deleted = 0;
        for (SqlDraft candidate : inspect().snapshot().drafts()) {
            if (candidate.modifiedAt() <= cutoff && !opened.contains(candidate.id())
                    && candidate.equals(readVerified(candidate.id()))) {
                directory.delete(filename(candidate.id()));
                deleted++;
            }
        }
        return deleted;
    }

    @Override public synchronized void close() throws IOException { directory.close(); }

    private Inspection inspect() throws IOException {
        List<UUID> ids = new ArrayList<>();
        for (String name : directory.entries()) {
            UUID id = idFromName(name);
            if (id != null) ids.add(id);
        }
        if (ids.size() > MAX_DRAFTS) throw new Failure(FailureCode.CAPACITY);
        Preference preference = preference();
        List<Problem> problems = new ArrayList<>();
        if (!preference.valid()) problems.add(new Problem(null, ProblemCode.INVALID_PREFERENCES));
        List<SqlDraft> drafts = new ArrayList<>();
        Map<UUID, Integer> lengths = new HashMap<>();
        Set<UUID> rejected = new HashSet<>();
        long totalBytes = 0;
        boolean sizesKnown = true;
        for (UUID id : ids) {
            byte[] bytes;
            try { bytes = directory.read(filename(id), SqlDraftCodec.MAX_FILE_BYTES); }
            catch (IOException unreadable) {
                problems.add(new Problem(id, ProblemCode.UNREADABLE_DRAFT));
                rejected.add(id); sizesKnown = false;
                continue;
            }
            if (bytes == null) continue;
            lengths.put(id, bytes.length);
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_BYTES) throw new Failure(FailureCode.CAPACITY);
            try {
                SqlDraft value = SqlDraftCodec.decode(bytes);
                if (!id.equals(value.id())) throw new Failure(FailureCode.PROTECTED_DRAFT);
                drafts.add(value);
            } catch (IOException corrupt) {
                rejected.add(id);
                problems.add(new Problem(id, ProblemCode.CORRUPT_DRAFT));
            }
        }
        drafts.sort(Comparator.comparingLong(SqlDraft::modifiedAt).reversed().thenComparing(draft -> draft.id().toString()));
        Snapshot snapshot = new Snapshot(drafts, problems, preference.valid() && preference.enabled(), preference.valid() && sizesKnown);
        return new Inspection(snapshot, Map.copyOf(lengths), Set.copyOf(rejected), totalBytes);
    }

    private Preference preference() throws IOException {
        byte[] bytes;
        try { bytes = directory.read(PREFERENCE_FILE, 9); }
        catch (IOException unreadable) { return new Preference(false, false); }
        if (bytes == null) return new Preference(true, true);
        if (bytes.length != 9) return new Preference(false, false);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != PREFERENCE_MAGIC || buffer.getInt() != 1) return new Preference(false, false);
        byte enabled = buffer.get();
        return enabled == 0 || enabled == 1 ? new Preference(true, enabled == 1) : new Preference(false, false);
    }

    private SqlDraft readVerified(UUID id) throws IOException {
        byte[] bytes = directory.read(filename(id), SqlDraftCodec.MAX_FILE_BYTES);
        if (bytes == null) return null;
        try {
            SqlDraft value = SqlDraftCodec.decode(bytes);
            if (!id.equals(value.id())) throw new Failure(FailureCode.PROTECTED_DRAFT);
            return value;
        } catch (IOException corrupt) { throw new Failure(FailureCode.PROTECTED_DRAFT); }
    }

    private static String filename(UUID id) { return id + ".draft"; }

    private static UUID idFromName(String name) {
        if (!name.endsWith(".draft")) return null;
        String text = name.substring(0, name.length() - 6);
        try {
            UUID id = UUID.fromString(text);
            return id.toString().equals(text) ? id : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
```

- [ ] **Step 4: Focused GREEN and full forced regression.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftStoreTest --tests com.datacube.config.SqlDraftDirectoryTest --tests com.datacube.config.SqlDraftCodecTest --rerun-tasks --no-daemon --console=plain
```

Expected exit0; then:

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```

Expected exit0; record actual XML totals and named skips, not just console BUILD SUCCESSFUL.

- [ ] **Step 5: Self-review, commit and report exact evidence.**

```powershell
git diff --check
git add -- src/com/datacube/config/SqlDraftStore.java test/com/datacube/config/SqlDraftStoreTest.java
git commit -m "feat: persist bounded SQL drafts with strict local preferences"
```

Report RED/GREEN/full command/output, Requirement | Evidence mapping, file list, commit and concerns. No UI recovery or scheduler claim. Controller independently reviews before P1.3 scheduling work.

## Self-review

P1.2 policy cases map to named tests for exact reopen/multiple IDs/cleared SQL, strict disable and re-enable, invalid preferences, atomic failure, unknown/corrupt/mismatched files, count/byte limits, expiry/open/future protection and unreadable neighbors. File ownership/lock/size/atomic error contracts are consumed from directory task. Multi-process crash acceptance and UI notification/privacy copy are P1.5/P1.4; generation barriers are P1.3, not implied by this synchronous store.
