# SQL Workspace Runtime Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成P2.3a：在既有运行时提供可验证的异步工作区存储桥，失效旧任务并保留共享writer故障边界。

**Architecture:** SqlDraftCoordinator的四个新入口复用现有Backend/store/queue；AtomicLong epoch防管理操作后旧任务执行，一个pending保存实施背压。无新线程、FX捕获或自动写盘，P2.3b另写计划。

**Tech Stack:** Java25/JavaFX25、JUnit5.11.3、Gradle9.2.0，无新依赖。

## Global Constraints

- Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；不添加依赖。
- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 工作区清单只含草稿 UUID、顺序、选中项、时间、光标/选择锚点；不复制 SQL、连接身份、Schema、凭据或结果集。连接身份与 Schema 由 P1 草稿提供。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。
- 工作区与草稿共用同一个store、目录锁、writer队列；不改变P1文件格式、原子发布和事务关闭语义。

---

现有worktree `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`，branch `codex/sql-workspace-recovery`。P2.2已完成，勿重做。root拥有docs和ledger，实施者只改本任务两文件。原main有未提交SqlDraftStore改动，不触及/读取它。新鲜基线session50743 exit0/32秒，154suites1430total1427pass3oldlive skips0fail/errors。

### Task 1: Async workspace operations on the shared draft runtime

**Files:**
- Modify `src/com/datacube/config/SqlDraftCoordinator.java`
- Create `test/com/datacube/config/SqlWorkspaceRuntimeTest.java`

**Interfaces and contracts:**
- Consume existing `SqlDraftStore.workspaceSnapshot/saveWorkspace/setWorkspaceEnabled/clearWorkspace`, `SqlWorkspaceStore.Snapshot/Failure/FailureCode`, `SqlDraftWriteQueue.barrier(Set.of(), Callable<T>)` and existing coordinator `stop/structural/owner/active`.
- Produce UI-owner public methods `workspaceSnapshot():CompletableFuture<SqlWorkspaceStore.Snapshot>`, `saveWorkspace(SqlWorkspace):CompletableFuture<Void>`, `setWorkspaceEnabled(boolean):CompletableFuture<Void>`, `clearWorkspace():CompletableFuture<Boolean>`.
- Add FailureReason.DISABLED and CANCELLED; typed safe errors use existing runtime Failure or P2.2 store Failure. No raw backend/queue exceptions escape new API.
- Initialization has no new workspace I/O. Store I/O runs only on existing queue; exposed futures settle on UI delivery, even after closing. Copies protect internal completion from caller cancellation.
- Global busy excludes overlapping workspace/P1 management. One accepted workspace save may be outstanding until UI delivery; another save returns BUSY. No coalescing claim: P2.3b buffers latest candidates above this bridge.
- On accepted workspace clear/toggle and P1 clear/delete/toggle, epoch increments immediately. Old not-started saves fail CANCELLED without backend call. Already-started save completes before queued management; failed management does not resurrect old saves. P1 refresh does not invalidate.
- Normal workspace corruption/disable only affects workspace. Directory structural/CLEANUP and DRAFT_PROTECTION_UNAVAILABLE stop shared writer. Cleanup reason remains sticky. No auto-retry.
- No changes to queue/store/FX or original tests. Internal Backend gets explicit unsupported defaults so legacy P1-only test adapters remain valid; production LocalBackend overrides all.

- [x] **Step 1: Add test and compile-only API stubs**

For RED add the four new public method signatures with `throw new UnsupportedOperationException("Workspace runtime not implemented");`, the two enum values, and Backend default methods from Step3 only. Do NOT add runtime behavior/LocalBackend overrides/epoch/management changes before the behavioral RED. Existing P1 startup/fixture must remain real. Then create the test below.

```java
package com.datacube.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceRuntimeTest {
    @TempDir Path temp;
    static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    static final long WALL = 1788000000000L;
    static SqlWorkspace layout(long at) {
        return new SqlWorkspace(at, List.of(new SqlWorkspace.Entry(A, 7, 2)), A);
    }
    static SqlDraft draft(UUID id, String text) {
        return new SqlDraft(id, WALL, null, null, null, null, text);
    }
    final class Fixture implements AutoCloseable {
        final Path path = temp.resolve(UUID.randomUUID().toString());
        final ArrayDeque<Runnable> diskTasks = new ArrayDeque<>(), uiTasks = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        boolean onUi = true, rejectDisk, rejectUi;
        IOException workspaceFailure, managementFailure;
        Runnable beforeWorkspaceWrite = () -> { };
        SqlDraftStore store;
        final SqlDraftCoordinator runtime = new SqlDraftCoordinator(() -> {
            assertFalse(onUi);
            store = SqlDraftStore.open(path);
            return new SqlDraftCoordinator.Backend() {
                public void save(SqlDraft value) throws IOException {
                    assertFalse(onUi); events.add("draft"); store.save(value);
                }
                public SqlDraftStore.Snapshot snapshot() throws IOException { return store.snapshot(); }
                public void setEnabled(boolean enabled) throws IOException { store.setEnabled(enabled); }
                public void clear() throws IOException { store.clearRecoverable(); }
                public void delete(UUID id) throws IOException { store.delete(id); }
                public void prune(long now, Set<UUID> ids) throws IOException { store.pruneExpired(now, ids); }
                public void close() throws IOException { events.add("close"); store.close(); }
                public SqlWorkspaceStore.Snapshot workspaceSnapshot() throws IOException {
                    assertFalse(onUi); return store.workspaceSnapshot();
                }
                public void saveWorkspace(SqlWorkspace value) throws IOException {
                    assertFalse(onUi); events.add("workspace"); beforeWorkspaceWrite.run();
                    if (workspaceFailure != null) throw workspaceFailure;
                    store.saveWorkspace(value);
                }
                public void setWorkspaceEnabled(boolean enabled) throws IOException {
                    assertFalse(onUi); if (managementFailure != null) throw managementFailure;
                    store.setWorkspaceEnabled(enabled);
                }
                public boolean clearWorkspace() throws IOException {
                    assertFalse(onUi); if (managementFailure != null) throw managementFailure;
                    events.add("clearWorkspace"); return store.clearWorkspace();
                }
            };
        }, action -> { if (rejectDisk) throw new RejectedExecutionException("private disk"); diskTasks.add(action); },
                action -> { if (rejectUi) throw new RejectedExecutionException("private ui"); uiTasks.add(action); },
                () -> onUi, () -> 0, () -> WALL);
        void disk() {
            onUi = false;
            try { while (!diskTasks.isEmpty()) diskTasks.remove().run(); }
            finally { onUi = true; }
        }
        void ui() { while (!uiTasks.isEmpty()) uiTasks.remove().run(); }
        void cycle() { disk(); ui(); }
        void ready() { cycle(); assertEquals(SqlDraftCoordinator.Mode.ENABLED, runtime.mode()); }
        void seed() throws IOException { store.save(draft(A, "old text")); store.saveWorkspace(layout(10)); }
        SqlDraftCoordinator.Handle handle(UUID id, String text) {
            return runtime.attach(id, null, new SqlDraftCoordinator.Source() {
                public boolean hasText() { assertTrue(onUi); return !text.isEmpty(); }
                public SqlDraft capture(UUID key, long at) {
                    assertTrue(onUi); return new SqlDraft(key, at, null, null, null, null, text);
                }
            });
        }
        public void close() throws Exception {
            rejectUi = false;
            var closed = runtime.shutdown(); disk();
            try { closed.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException failure) { if (!rejectDisk) throw failure; }
            ui();
        }
    }
    static Throwable failure(CompletableFuture<?> future) {
        assertTrue(future.isDone(), "outcome must settle");
        Throwable cause = assertThrows(CompletionException.class, future::join).getCause();
        assertNull(cause.getCause());
        assertFalse(cause.getMessage().contains("private"));
        return cause;
    }
    static void reason(SqlDraftCoordinator.FailureReason reason, CompletableFuture<?> future) {
        assertEquals(reason, assertInstanceOf(SqlDraftCoordinator.Failure.class, failure(future)).reason());
    }
    static void code(SqlWorkspaceStore.FailureCode code, CompletableFuture<?> future) {
        assertEquals(code, assertInstanceOf(SqlWorkspaceStore.Failure.class, failure(future)).code());
    }

    @Test void untouchedStartupReadAndShutdownNeverCreateLayoutFiles() throws Exception {
        Path path;
        try (Fixture f = new Fixture()) {
            path = f.path; f.ready();
            var read = f.runtime.workspaceSnapshot(); assertFalse(read.isDone()); f.cycle();
            assertEquals(SqlWorkspaceStore.Status.ABSENT, read.join().status());
            assertTrue(read.join().recordingEnabled()); assertTrue(f.events.isEmpty());
        }
        assertFalse(Files.exists(path.resolve("workspace.bin")));
        assertFalse(Files.exists(path.resolve("workspace-preferences.bin")));
    }
    @Test void saveRunsOffUiAndOnlySettlesAfterDiskAndUiDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var saved = f.runtime.saveWorkspace(layout(20));
            assertFalse(saved.isDone()); assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            f.disk(); assertFalse(saved.isDone()); assertEquals(layout(20), f.store.workspaceSnapshot().workspace());
            f.ui(); saved.join(); assertEquals(List.of("workspace"), f.events);
        }
    }
    @Test void singleOutstandingSaveIsBoundedAndCallerCancellationDoesNotCancelPublication() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var first = f.runtime.saveWorkspace(layout(10)); assertTrue(first.cancel(false));
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.saveWorkspace(layout(20)));
            f.cycle(); assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            var next = f.runtime.saveWorkspace(layout(30)); f.cycle(); next.join();
            assertEquals(layout(30), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void clearInvalidatesQueuedLayoutButPreservesQueuedDraft() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var body = f.handle(B, "latest body").flush();
            var old = f.runtime.saveWorkspace(layout(20)); var clear = f.runtime.clearWorkspace();
            assertTrue(f.runtime.managementPending()); f.disk();
            assertFalse(clear.isDone()); assertTrue(f.runtime.managementPending()); f.ui();
            body.join(); reason(SqlDraftCoordinator.FailureReason.CANCELLED, old); assertTrue(clear.join());
            assertFalse(f.runtime.managementPending());
            assertEquals(new SqlWorkspace(0, List.of(), null), f.store.workspaceSnapshot().workspace());
            assertTrue(f.store.snapshot().drafts().contains(draft(B, "latest body")));
            assertEquals(List.of("draft", "clearWorkspace"), f.events);
        }
    }
    @Test void workspaceDisableCancelsQueuedLayoutWithoutDisablingDraftProtection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var old = f.runtime.saveWorkspace(layout(20));
            var disabled = f.runtime.setWorkspaceEnabled(false); f.cycle(); disabled.join();
            reason(SqlDraftCoordinator.FailureReason.CANCELLED, old);
            assertFalse(f.store.workspaceSnapshot().recordingEnabled());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            var refused = f.runtime.saveWorkspace(layout(30)); f.cycle();
            code(SqlWorkspaceStore.FailureCode.DISABLED, refused);
            assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
            var body = f.handle(B, "body").flush(); f.cycle(); body.join();
            assertTrue(f.store.snapshot().drafts().contains(draft(B, "body")));
            var enabled = f.runtime.setWorkspaceEnabled(true); f.cycle(); enabled.join();
            var next = f.runtime.saveWorkspace(layout(40)); f.cycle(); next.join();
            assertEquals(layout(40), f.store.workspaceSnapshot().workspace());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"CLEAR", "DELETE", "DISABLE"})
    void draftManagementInvalidatesOldWorkspace(String operation) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); var old = f.runtime.saveWorkspace(layout(20));
            var managed = switch (operation) {
                case "CLEAR" -> f.runtime.clear(); case "DELETE" -> f.runtime.delete(A);
                default -> f.runtime.setEnabled(false);
            };
            f.cycle(); assertTrue(managed.join().succeeded());
            reason(SqlDraftCoordinator.FailureReason.CANCELLED, old);
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            assertFalse(f.events.contains("workspace"));
            if (operation.equals("DISABLE")) {
                reason(SqlDraftCoordinator.FailureReason.DISABLED, f.runtime.saveWorkspace(layout(30)));
                var read = f.runtime.workspaceSnapshot(); f.cycle(); assertEquals(layout(10), read.join().workspace());
            }
        }
    }
    @Test void refreshDoesNotInvalidateAcceptedLayout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var save = f.runtime.saveWorkspace(layout(20)); var refresh = f.runtime.refresh();
            f.cycle(); save.join(); assertTrue(refresh.join().succeeded());
            assertEquals(layout(20), f.store.workspaceSnapshot().workspace());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"CLEAR", "PREFERENCE"})
    void failedManagementStillInvalidatesOldSaveAndRetainsOldFiles(String operation) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); f.managementFailure = new IOException("private management");
            var old = f.runtime.saveWorkspace(layout(20));
            CompletableFuture<?> managed = operation.equals("CLEAR") ? f.runtime.clearWorkspace() : f.runtime.setWorkspaceEnabled(false);
            f.cycle(); reason(SqlDraftCoordinator.FailureReason.CANCELLED, old);
            reason(SqlDraftCoordinator.FailureReason.WRITE, managed);
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            assertTrue(f.store.workspaceSnapshot().recordingEnabled()); assertFalse(f.runtime.managementPending());
            f.managementFailure = null;
            var retry = f.runtime.saveWorkspace(layout(30)); f.cycle(); retry.join();
            assertEquals(layout(30), f.store.workspaceSnapshot().workspace());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"workspace.bin", "workspace-preferences.bin"})
    void workspaceCorruptionDoesNotStopDraftProtection(String name) throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); Files.write(f.path.resolve(name), new byte[]{1, 2});
            var refused = f.runtime.saveWorkspace(layout(20)); f.cycle();
            code(name.equals("workspace.bin") ? SqlWorkspaceStore.FailureCode.PROTECTED_WORKSPACE
                    : SqlWorkspaceStore.FailureCode.PREFERENCE_CORRUPT, refused);
            assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
            var body = f.handle(B, "new").flush(); f.cycle(); body.join();
            assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(f.path.resolve(name)));
            assertTrue(f.store.snapshot().drafts().contains(draft(B, "new")));
        }
    }
    @Test void cleanupStopsSharedWriterBeforeLaterDraftCanPublish() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); f.workspaceFailure = new SqlDraftDirectory.Failure(SqlDraftDirectory.Stage.CLEANUP);
            var save = f.runtime.saveWorkspace(layout(20)); var body = f.handle(B, "new").flush(); f.cycle();
            reason(SqlDraftCoordinator.FailureReason.CLEANUP, save); assertTrue(body.isCompletedExceptionally());
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertEquals(SqlDraftCoordinator.FailureReason.CLEANUP, f.runtime.unavailableReason());
            reason(SqlDraftCoordinator.FailureReason.CLEANUP, f.runtime.clearWorkspace());
            assertEquals(List.of(draft(A, "old text")), f.store.snapshot().drafts());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace()); assertEquals(List.of("workspace"), f.events);
        }
    }
    @Test void invalidDraftPreferenceStopsWorkspaceAndSubsequentDraftWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); Files.write(f.path.resolve("preferences.bin"), new byte[]{1, 2});
            var save = f.runtime.saveWorkspace(layout(20)); f.cycle();
            code(SqlWorkspaceStore.FailureCode.DRAFT_PROTECTION_UNAVAILABLE, save);
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertTrue(f.handle(B, "new").flush().isCompletedExceptionally());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void ordinaryWriteFailurePreservesOldLayoutAndAllowsExplicitRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.seed(); f.workspaceFailure = new IOException("private write");
            var save = f.runtime.saveWorkspace(layout(20)); f.cycle(); reason(SqlDraftCoordinator.FailureReason.WRITE, save);
            assertEquals(SqlDraftCoordinator.Mode.ENABLED, f.runtime.mode());
            assertEquals(layout(10), f.store.workspaceSnapshot().workspace());
            f.workspaceFailure = null; var retry = f.runtime.saveWorkspace(layout(30)); f.cycle(); retry.join();
            assertEquals(layout(30), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void acceptedSaveDrainsAndCompletesEvenWhenShutdownPrecedesUiDelivery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); var save = f.runtime.saveWorkspace(layout(20)); var closed = f.runtime.shutdown();
            f.disk(); closed.get(5, TimeUnit.SECONDS); assertFalse(save.isDone()); f.ui(); save.join();
            assertEquals(List.of("workspace", "close"), f.events);
            assertThrows(IllegalStateException.class, f.runtime::workspaceSnapshot);
            try (SqlDraftStore reopened = SqlDraftStore.open(f.path)) {
                assertEquals(layout(20), reopened.workspaceSnapshot().workspace());
            }
        }
    }
    @Test void wrongThreadInitializingNullAndOverlappingManagementAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.workspaceSnapshot());
            f.onUi = false;
            try { assertThrows(IllegalStateException.class, f.runtime::workspaceSnapshot); }
            finally { f.onUi = true; }
            f.ready(); code(SqlWorkspaceStore.FailureCode.INVALID_WORKSPACE, f.runtime.saveWorkspace(null));
            var enabled = f.runtime.setWorkspaceEnabled(false);
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.clear());
            reason(SqlDraftCoordinator.FailureReason.BUSY, f.runtime.workspaceSnapshot());
            f.cycle(); enabled.join(); assertFalse(f.runtime.managementPending());
        }
    }
    @Test void writerRejectionSettlesOutcomeAndMakesRuntimeUnavailable() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.rejectDisk = true; var save = f.runtime.saveWorkspace(layout(20)); f.ui();
            reason(SqlDraftCoordinator.FailureReason.UNAVAILABLE, save);
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertFalse(Files.exists(f.path.resolve("workspace.bin")));
        }
    }
    @Test void uiRejectionSettlesOutcomeAndStopsFurtherWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); f.rejectUi = true; var save = f.runtime.saveWorkspace(layout(20)); f.disk();
            reason(SqlDraftCoordinator.FailureReason.UNAVAILABLE, save);
            assertEquals(SqlDraftCoordinator.Mode.UNAVAILABLE, f.runtime.mode());
            assertEquals(layout(20), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void runningSaveFinishesBeforeClearRatherThanResurrectingAfterIt() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ready(); AtomicReference<CompletableFuture<Boolean>> clear = new AtomicReference<>();
            f.beforeWorkspaceWrite = () -> {
                f.onUi = true;
                try { clear.set(f.runtime.clearWorkspace()); } finally { f.onUi = false; }
            };
            var save = f.runtime.saveWorkspace(layout(20)); f.cycle(); save.join(); assertTrue(clear.get().join());
            assertEquals(List.of("workspace", "clearWorkspace"), f.events);
            assertEquals(new SqlWorkspace(0, List.of(), null), f.store.workspaceSnapshot().workspace());
        }
    }
    @Test void publicPathOwnerUsesSameStoreForReadWritePreferenceAndClear() throws Exception {
        Path path = temp.resolve("public-owner"); ArrayDeque<Runnable> disk = new ArrayDeque<>(), ui = new ArrayDeque<>();
        AtomicBoolean onUi = new AtomicBoolean(true);
        SqlDraftCoordinator runtime = new SqlDraftCoordinator(path, disk::add, ui::add, onUi::get, () -> 0, () -> WALL);
        Runnable cycle = () -> {
            onUi.set(false);
            try { while (!disk.isEmpty()) disk.remove().run(); } finally { onUi.set(true); }
            while (!ui.isEmpty()) ui.remove().run();
        };
        try {
            cycle.run(); var save = runtime.saveWorkspace(layout(20)); cycle.run(); save.join();
            assertThrows(SqlDraftDirectory.Failure.class, () -> SqlDraftStore.open(path));
            var read = runtime.workspaceSnapshot(); cycle.run(); assertEquals(layout(20), read.join().workspace());
            var disable = runtime.setWorkspaceEnabled(false); cycle.run(); disable.join();
            var clear = runtime.clearWorkspace(); cycle.run(); assertTrue(clear.join());
        } finally { var closed = runtime.shutdown(); cycle.run(); closed.get(5, TimeUnit.SECONDS); }
        try (SqlDraftStore reopened = SqlDraftStore.open(path)) {
            assertFalse(reopened.workspaceSnapshot().recordingEnabled());
            assertEquals(new SqlWorkspace(0, List.of(), null), reopened.workspaceSnapshot().workspace());
        }
    }
}
```

- [x] **Step 2: Record behavioral RED before implementation**

```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
./gradlew.bat test --tests com.datacube.config.SqlWorkspaceRuntimeTest --no-daemon --console=plain
```

Expected nonzero with named runtime-UOE failures, not compilation/fixture timeouts. Record actual counts/exit/duration and failure snippets in unique report; send root RED before GREEN. Fix fixture/compiler mistakes separately, do not claim them as behavioral RED.

- [x] **Step 3: Implement coordinator bridge**

Add import `java.util.concurrent.atomic.AtomicLong`; append DISABLED and CANCELLED to FailureReason; add fields:

```java
    private final AtomicLong workspaceEpoch = new AtomicLong();
    private boolean workspaceSavePending;
```

Add Backend defaults (explicit failure, no false success):

```java
        default SqlWorkspaceStore.Snapshot workspaceSnapshot() throws IOException {
            throw new IOException("Workspace backend unsupported");
        }
        default void saveWorkspace(SqlWorkspace workspace) throws IOException {
            throw new IOException("Workspace backend unsupported");
        }
        default void setWorkspaceEnabled(boolean enabled) throws IOException {
            throw new IOException("Workspace backend unsupported");
        }
        default boolean clearWorkspace() throws IOException {
            throw new IOException("Workspace backend unsupported");
        }
```

LocalBackend adds delegates on its existingstore:

```java
        public SqlWorkspaceStore.Snapshot workspaceSnapshot() throws IOException { return store.workspaceSnapshot(); }
        public void saveWorkspace(SqlWorkspace workspace) throws IOException { store.saveWorkspace(workspace); }
        public void setWorkspaceEnabled(boolean enabled) throws IOException { store.setWorkspaceEnabled(enabled); }
        public boolean clearWorkspace() throws IOException { return store.clearWorkspace(); }
```

Replace compile-only publicstubs with this API and shared operation helper (place before P1 clear):

```java
    public CompletableFuture<SqlWorkspaceStore.Snapshot> workspaceSnapshot() {
        return workspaceOperation(false, false, () -> backend.workspaceSnapshot());
    }

    public CompletableFuture<Void> saveWorkspace(SqlWorkspace workspace) {
        active();
        if (workspace == null) return CompletableFuture.failedFuture(
                new SqlWorkspaceStore.Failure(SqlWorkspaceStore.FailureCode.INVALID_WORKSPACE));
        return workspaceOperation(true, false, () -> { backend.saveWorkspace(workspace); return null; });
    }

    public CompletableFuture<Void> setWorkspaceEnabled(boolean enabled) {
        return workspaceOperation(false, true, () -> { backend.setWorkspaceEnabled(enabled); return null; });
    }

    public CompletableFuture<Boolean> clearWorkspace() {
        return workspaceOperation(false, true, () -> backend.clearWorkspace());
    }

    /** One pending layout publication; capture/coalescing belongs to the UI state owner. */
    private <T> CompletableFuture<T> workspaceOperation(boolean saving, boolean managing, Callable<T> action) {
        active();
        if (busy || (saving && workspaceSavePending)) return refused(FailureReason.BUSY);
        if (faulted.get()) return refused(unavailableReason());
        if (saving && mode() != Mode.ENABLED) return refused(modeReason());
        if (managing) {
            busy = true;
            workspaceEpoch.incrementAndGet();
        }
        if (saving) workspaceSavePending = true;
        long epoch = workspaceEpoch.get();
        CompletableFuture<T> result = queue.barrier(Set.of(), () -> {
            if (faulted.get()) throw new Failure(unavailableFailure.get());
            if (saving && epoch != workspaceEpoch.get()) throw new Failure(FailureReason.CANCELLED);
            if (saving && !admitted.get()) throw new Failure(FailureReason.UNAVAILABLE);
            try { return action.call(); }
            catch (Exception | Error failure) {
                if (structural(failure)) stop(failure);
                throw failure;
            }
        });
        CompletableFuture<T> exposed = new CompletableFuture<>();
        result.whenComplete((value, failure) -> {
            if (failure != null && structural(failure)) stop(failure);
            Runnable delivered = () -> {
                try {
                    owner();
                    if (saving) workspaceSavePending = false;
                    if (managing && !closing) busy = false;
                    if (failure == null) exposed.complete(value);
                    else exposed.completeExceptionally(workspaceFailure(failure));
                } catch (RuntimeException deliveryFailure) {
                    stop(deliveryFailure);
                    exposed.completeExceptionally(new Failure(FailureReason.UNAVAILABLE));
                }
            };
            // Unlike ordinary observer posts, accepted results must settle while closing too.
            try { ui.execute(delivered); }
            catch (RuntimeException rejected) {
                stop(rejected);
                exposed.completeExceptionally(new Failure(FailureReason.UNAVAILABLE));
            }
        });
        return exposed.copy();
    }

    private static IOException workspaceFailure(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) return workspaceFailure(error.getCause());
        if (error instanceof SqlWorkspaceStore.Failure failure) return failure;
        if (error instanceof Failure failure) return failure;
        if (classify(error) == FailureReason.CLEANUP) return new Failure(FailureReason.CLEANUP);
        return new Failure(structural(error) ? FailureReason.UNAVAILABLE : FailureReason.WRITE);
    }
```

Inside P1 `manage`, after active/busy/faulted checks and before `busy=true`, add:

```java
        if (enabled != null || resetIds == null || !resetIds.isEmpty()) workspaceEpoch.incrementAndGet();
```

Add `case DISABLED -> FailureReason.DISABLED;` to `modeReason` switch. In `structural` before existing queueFailure handling add:

```java
        if (error instanceof SqlWorkspaceStore.Failure failure)
            return failure.code() == SqlWorkspaceStore.FailureCode.DRAFT_PROTECTION_UNAVAILABLE;
```

Do not alter original handle, queue, stop, shutdown or P1 manage completion behavior. Existingqueue still drains and closes the singlebackend; new workspace callbacks deliberately settle even whileclosing.

- [x] **Step 4: Focused GREEN, adjacent regression and one full run**

```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
./gradlew.bat test --tests com.datacube.config.SqlWorkspaceRuntimeTest --no-daemon --console=plain
./gradlew.bat test --tests com.datacube.config.SqlDraftCoordinatorTest --tests com.datacube.config.SqlDraftWriteQueueTest --tests com.datacube.config.SqlWorkspaceRuntimeTest --tests com.datacube.config.SqlWorkspaceStoreTest --tests com.datacube.config.SqlWorkspaceStoreFaultTest --no-daemon --console=plain
$p23Options=$env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
    ./gradlew.bat test --rerun-tasks --no-daemon --console=plain
} finally { $env:JAVA_TOOL_OPTIONS=$p23Options }
```

Record exact command, exit, duration, actual XML suite/test/failure/error/skip counts and complete skipcase names. Expected all new tests pass with no new skips; the three old live-service skips and existing unchecked compile note remain explicitly reported. No concurrent Gradle withroot.

- [x] **Step 5: Review assertions, commit exact files, hand off**

Check every requirement above against exacttestnames, including failure outcomes before futures are consumed, no UI I/O, oldfile bytes/content, singular owner and lockrelease. Record Requirement|Evidence table, RED/GREEN excerpts and limitations in `.superpowers/sdd/workspace-runtime-bridge-task-1-report.md`.

```powershell
git diff --check
git add -- src/com/datacube/config/SqlDraftCoordinator.java test/com/datacube/config/SqlWorkspaceRuntimeTest.java
git commit -m "feat: coordinate workspace persistence on the shared draft writer"
git rev-parse HEAD
```

Root performs independent review using frozenBASE at dispatch and actualcommit, verifies evidence, updates docs/ledger. No mainmerge yet. P2.3b is not part of this executable task; do not implement UI from a prose promise.
