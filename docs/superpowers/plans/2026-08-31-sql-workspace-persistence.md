# SQL Workspace Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成P2.2的共享锁、严格工作区偏好、有界清单读写和原子清空，为P2.3提供阻塞存储接口。

**Architecture:** SqlDraftStore持有一个包内可操作SqlWorkspaceStore，共用SqlDraftDirectory及store monitor。仅扩展精确文件名白名单，保持P1格式/行为及P2.1 codec。没有FX、后台任务、自动捕获或恢复入口。

**Tech Stack:** Java25/JavaFX25、JUnit Jupiter5.11.3、Gradle9.2.0，无新依赖。

## Global Constraints

- Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；不添加依赖。
- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 工作区清单只含草稿 UUID、顺序、选中项、时间、光标/选择锚点；不复制 SQL、连接身份、Schema、凭据或结果集。连接身份与 Schema 由 P1 草稿提供。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。
- 保留损坏/未知/不可读清单与损坏偏好，不静默覆盖。共享目录锁、NOFOLLOW_LINKS、身份戳、扫描上限和原子移动机制不变。

---

Worktree `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`，branch `codex/sql-workspace-recovery`。基线184c142，fresh baseline4833 exit0/32s，152suites1405total1402pass3oldliveskips0fail/errors。P2.1 COMPLETE，不重复dispatch。

本计划仅一个存储交付任务；P2.3运行时代次/清空竞态/退出冻结另写计划。root拥有spec/plan/ledger/verification，实施代理仅改下列五个源/测试文件。

### Task 1: Shared-lock workspace store with strict preferences and atomic clear

**Files:**
- Create `src/com/datacube/config/SqlWorkspaceStore.java`
- Modify `src/com/datacube/config/SqlDraftStore.java` constructor/fields and four public storage entry methods
- Modify `src/com/datacube/config/SqlDraftDirectory.java` exact-name whitelist only
- Create `test/com/datacube/config/SqlWorkspaceStoreTest.java`
- Create `test/com/datacube/config/SqlWorkspaceStoreFaultTest.java`

**Interfaces and binding behavior:**
- Consume `SqlWorkspaceCodec.encode/decode`, `MAX_FILE_BYTES=2424`, `Code.CORRUPT/UNSUPPORTED_VERSION`; `SqlWorkspace` and `SqlDraft` as already implemented. Do not edit them.
- Public entry points on existingSqlDraftStore, all synchronized and throws IOException: `workspaceSnapshot():SqlWorkspaceStore.Snapshot`, `saveWorkspace(SqlWorkspace):void`, `setWorkspaceEnabled(boolean):void`, `clearWorkspace():boolean`.
- New helper constructor/methods package-only; public result/enum/failure types, no public open/close. One helper instance per SqlDraftStore, no new lock or thread.
- Snapshot(workspace,status,recordingEnabled,preferenceValid); status `ABSENT/AVAILABLE/CORRUPT/UNSUPPORTED_VERSION/UNREADABLE`. Own preference only, not whole-runtime admission. Absentfile defaults valid+enabled with no write.
- Files `workspace.bin` usingexistingcodec and `workspace-preferences.bin` big-endian9bytes magic0x44435750/version1/enabledbyte0or1. Reject malformed/trailing/unknown prefs and never overwrite them via toggle.
- saveWorkspace checks directory and valid/enabled P1 preference before helper save; helper checks own preference and existingmanifest protection; malformedinput fails; dangling draft references allowed.
- clearWorkspace atomically publishes `SqlWorkspace(0,List.of(),null)`, only forverifiedavailable noncanonical manifests. Absent/canonicalempty returnsfalse without write. May clear with either recording switch off or broken preference, but not broken manifest. Never deletes draft/setting files.
- FailureCode `DISABLED/INVALID_WORKSPACE/PROTECTED_WORKSPACE/PREFERENCE_CORRUPT/DRAFT_PROTECTION_UNAVAILABLE`, fixedmessage `SQL workspace store failed: <CODE>`, no cause. Preserve directory structural/write/publish/cleanup Failure stages; only directory READ becomes protected unreadablefile/invalidpref.
- No runtime auto-retry or stale queue admission here: P2.3 must make CLEANUP session-sticky unavailable and invalidate queued work on clear/toggle. Do not claim that integration complete.

- [x] **Step 1: Write tests; use compile-only API stubs to obtain behavioral RED**

Create `SqlWorkspaceStoreTest.java`:

```java
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
```

Create `SqlWorkspaceStoreFaultTest.java`:

```java
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
```

Compile-only stubs: create SqlWorkspaceStore with the same enums/Snapshot/Failure definitions as Step3, package constructor doing nothing, snapshot returning `new Snapshot(null,Status.ABSENT,true,true)`, save/setEnabled no-op, clear returnsfalse; add four synchronized SqlDraftStore API methods delegating to this helper and initialize helper in constructor. Do not change whitelist or add preference guards before RED. These stubs are only compile scaffolding; no final commit may contain them. Exact stub bodies:

```java
SqlWorkspaceStore(SqlDraftDirectory directory) { }
Snapshot snapshot() throws IOException { return new Snapshot(null, Status.ABSENT, true, true); }
void save(SqlWorkspace workspace) throws IOException { }
void setEnabled(boolean enabled) throws IOException { }
boolean clear() throws IOException { return false; }
```

- [x] **Step 2: Run focused behavior RED; preserve evidence before GREEN**

```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
./gradlew.bat test --tests com.datacube.config.SqlWorkspaceStoreTest --tests com.datacube.config.SqlWorkspaceStoreFaultTest --no-daemon --console=plain
```

Expected exit1 with behavior failures: missing persisted file, ignored disable/protection/clear, missing expected exceptions. Capture test names/counts and representative output/XML in the report before GREEN overwrites XML. Compilation errors are not RED; fix fixture mechanics first. Tell controller when RED is observed; no human confirmation needed.

- [x] **Step 3: Implement helper, facade guards and exact-name admission**

Create full `SqlWorkspaceStore.java`:

```java
package com.datacube.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

/** Blocking workspace storage owned and serialized by SqlDraftStore; never opens a second lock. */
public final class SqlWorkspaceStore {
    public enum Status { ABSENT, AVAILABLE, CORRUPT, UNSUPPORTED_VERSION, UNREADABLE }
    public enum FailureCode { DISABLED, INVALID_WORKSPACE, PROTECTED_WORKSPACE, PREFERENCE_CORRUPT, DRAFT_PROTECTION_UNAVAILABLE }
    public record Snapshot(SqlWorkspace workspace, Status status, boolean recordingEnabled, boolean preferenceValid) { }
    public static final class Failure extends IOException {
        private final FailureCode code;
        Failure(FailureCode code) { super("SQL workspace store failed: " + code); this.code = code; }
        public FailureCode code() { return code; }
    }
    private record Preference(boolean valid, boolean enabled) { }
    private record Manifest(SqlWorkspace value, Status status) { }
    private static final String WORKSPACE = "workspace.bin";
    private static final String PREFERENCE = "workspace-preferences.bin";
    private static final int PREFERENCE_MAGIC = 0x44435750;
    private static final SqlWorkspace CLEARED = new SqlWorkspace(0, List.of(), null);
    private final SqlDraftDirectory directory;

    SqlWorkspaceStore(SqlDraftDirectory directory) { this.directory = Objects.requireNonNull(directory); }

    Snapshot snapshot() throws IOException {
        Preference preference = preference();
        Manifest manifest = manifest();
        return new Snapshot(manifest.value(), manifest.status(), preference.enabled(), preference.valid());
    }

    void save(SqlWorkspace workspace) throws IOException {
        byte[] bytes;
        try { bytes = SqlWorkspaceCodec.encode(workspace); }
        catch (IOException invalid) { throw new Failure(FailureCode.INVALID_WORKSPACE); }
        Preference preference = preference();
        if (!preference.valid()) throw new Failure(FailureCode.PREFERENCE_CORRUPT);
        if (!preference.enabled()) throw new Failure(FailureCode.DISABLED);
        requireMutable(manifest());
        directory.publish(WORKSPACE, bytes);
    }

    void setEnabled(boolean enabled) throws IOException {
        if (!preference().valid()) throw new Failure(FailureCode.PREFERENCE_CORRUPT);
        directory.publish(PREFERENCE, ByteBuffer.allocate(9).putInt(PREFERENCE_MAGIC)
                .putInt(1).put((byte) (enabled ? 1 : 0)).array());
    }

    boolean clear() throws IOException {
        Manifest manifest = manifest();
        requireMutable(manifest);
        if (manifest.status() == Status.ABSENT || CLEARED.equals(manifest.value())) return false;
        directory.publish(WORKSPACE, SqlWorkspaceCodec.encode(CLEARED));
        return true;
    }

    private Manifest manifest() throws IOException {
        byte[] bytes;
        try { bytes = directory.read(WORKSPACE, SqlWorkspaceCodec.MAX_FILE_BYTES); }
        catch (SqlDraftDirectory.Failure failure) {
            if (failure.stage() != SqlDraftDirectory.Stage.READ) throw failure;
            return new Manifest(null, Status.UNREADABLE);
        }
        if (bytes == null) return new Manifest(null, Status.ABSENT);
        try { return new Manifest(SqlWorkspaceCodec.decode(bytes), Status.AVAILABLE); }
        catch (SqlWorkspaceCodec.Failure invalid) {
            return new Manifest(null, invalid.code() == SqlWorkspaceCodec.Code.UNSUPPORTED_VERSION
                    ? Status.UNSUPPORTED_VERSION : Status.CORRUPT);
        }
    }

    private Preference preference() throws IOException {
        byte[] bytes;
        try { bytes = directory.read(PREFERENCE, 9); }
        catch (SqlDraftDirectory.Failure failure) {
            if (failure.stage() != SqlDraftDirectory.Stage.READ) throw failure;
            return new Preference(false, false);
        }
        if (bytes == null) return new Preference(true, true);
        if (bytes.length != 9) return new Preference(false, false);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != PREFERENCE_MAGIC || buffer.getInt() != 1) return new Preference(false, false);
        byte enabled = buffer.get();
        return enabled == 0 || enabled == 1 ? new Preference(true, enabled == 1) : new Preference(false, false);
    }

    private static void requireMutable(Manifest manifest) throws Failure {
        if (manifest.status() != Status.ABSENT && manifest.status() != Status.AVAILABLE)
            throw new Failure(FailureCode.PROTECTED_WORKSPACE);
    }
}
```

Modify SqlDraftStore field/constructor and add complete methods below after `snapshot()`; no other existing method changes:

```java
private final SqlWorkspaceStore workspaceStore;

SqlDraftStore(SqlDraftDirectory directory) {
    this.directory = Objects.requireNonNull(directory);
    this.workspaceStore = new SqlWorkspaceStore(directory);
}

/** Own workspace preference only; runtime admission must also check draft protection. */
public synchronized SqlWorkspaceStore.Snapshot workspaceSnapshot() throws IOException {
    return workspaceStore.snapshot();
}

public synchronized void saveWorkspace(SqlWorkspace workspace) throws IOException {
    directory.entries();
    Preference preference = preference();
    if (!preference.valid()) throw new SqlWorkspaceStore.Failure(SqlWorkspaceStore.FailureCode.DRAFT_PROTECTION_UNAVAILABLE);
    if (!preference.enabled()) throw new SqlWorkspaceStore.Failure(SqlWorkspaceStore.FailureCode.DISABLED);
    workspaceStore.save(workspace);
}

public synchronized void setWorkspaceEnabled(boolean enabled) throws IOException {
    workspaceStore.setEnabled(enabled);
}

/** Publishes a canonical empty manifest; never deletes draft contents or preferences. */
public synchronized boolean clearWorkspace() throws IOException {
    return workspaceStore.clear();
}
```

SqlDraftDirectory.target starts with the following exact replacement; leave all UUID, alias and filesystem checks unchanged:

```java
boolean allowed = "preferences.bin".equals(name) || "workspace.bin".equals(name)
        || "workspace-preferences.bin".equals(name);
```

- [x] **Step 4: Focused GREEN and regression**

Run Step2 command; expect all runnable new cases pass. If symlink assumptions skip, record exact names/reason, not pass. Then:

```powershell
./gradlew.bat test --tests com.datacube.config.SqlDraftDirectoryTest --tests com.datacube.config.SqlDraftStoreTest --tests com.datacube.config.SqlWorkspaceStoreTest --tests com.datacube.config.SqlWorkspaceStoreFaultTest --tests com.datacube.config.SqlWorkspaceCodecTest --tests com.datacube.config.SqlWorkspaceRecoveryTest --no-daemon --console=plain
```

Finally one full regression:

```powershell
$p22PreviousOptions=$env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
    ./gradlew.bat test --rerun-tasks --no-daemon --console=plain
} finally { $env:JAVA_TOOL_OPTIONS=$p22PreviousOptions }
```

Use the stated JDK for all commands. Expect exit0; report fresh XML totals, named skips, exact stdout including existing unchecked compile note. Independent child probes only operate on @TempDir and return23whilelocked/0afterrelease; never kill arbitrary java processes.

- [x] **Step 5: Self-review, exact commit and report**

```powershell
git diff --check
git add src/com/datacube/config/SqlWorkspaceStore.java src/com/datacube/config/SqlDraftStore.java src/com/datacube/config/SqlDraftDirectory.java test/com/datacube/config/SqlWorkspaceStoreTest.java test/com/datacube/config/SqlWorkspaceStoreFaultTest.java
git commit -m "feat: persist SQL workspace safely with shared draft locking"
```

Report exact command, exit, failure/pass cases, XML counts, Requirement|Evidence mapping (every method above), file scope and concerns. Preserve original RED evidence before reruns; do not claim unavailable logs retained. Root generates task-review package from frozenBASE, notHEAD~1, then independent reviewer checks both spec and quality. No main merge until fullP2 integration gate.

## Plan self-review

EveryP2.2 behavior has one test or the explicit runtime boundary: synchronousstore serializes operations, future queue invalidation staysP2.3. Tests share only pure fixtures insamepackage, use actual filesystem+existingfaultseams, and never use realprofiles. No sourceformatmigration or newdeletepath. All new imports/types exist inP2.1/JDK/JUnit. Emptyclear isreadableAVAILABLE zeroitems, notABSENT; ownrecordingEnabled isnotoveralladmission. Errorstage/CLEANUP distinction remainsvisible forfuture runtime.
