# SQL Draft Write Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide bounded pending SQL snapshots and ordered cancellation/barrier/drain operations for the P1.3 application coordinator.

**Architecture:** A package-private queue schedules only one executor drain at a time and retains at most one pending snapshot per UUID, in addition to the one executing job. Snapshot replacement settles the obsolete future immediately. Explicit storage actions cancel affected pending saves before joining the same serial queue; coordinator admission, UI dispatch, preference policy and storage lifecycle stay outside this class.

**Tech Stack:** Java25, SqlDraft/SqlDraftStore, existing JUnit Jupiter5.11.3; no new dependencies.

## Global Constraints

- Java25 / JavaFX25 / JUnit Jupiter5.11.3；不增加第三方依赖，不改 JDBC、历史文件或导出语义。
- 仅使用合成文本、临时目录与替身网关；不读取、不修改、不暂存、不清理 `.testagent/`。
- 不新增网络、遥测、AI、数据库自动请求、密码存储或结果/事务持久化；不推送、打 tag、安装或发布。
- SQL 保留空白、换行和 Unicode 原文；不按 SQL 去重、不截断；编码/容量超限必须显式失败并保留已有版本。
- 快照带编辑revision和清空generation，过期的保存完成回调不能改变新状态。最多保留每个打开草稿的最新待写快照，避免持续输入无限排队。
- 删除/清空/关闭自动保存与写入同队列排序，调用完成后之前的写入不可能再发布；调用期间继续编辑的处理由generation明确判定。
- 文件格式、关闭状态、数据库调用计数与错误路径均须有独立证据；未实现、跳过、工具受限不得计为通过。

---

### Task 1: Coalesced writes and serialized barriers

**Files:**
- Create: `src/com/datacube/config/SqlDraftWriteQueue.java` — queued snapshot ownership, action ordering, executor rejection and drain completion.
- Test: `test/com/datacube/config/SqlDraftWriteQueueTest.java` — controlled executor interleavings plus isolated real store clear/save sequence.

**Interfaces:**
- Consumes: immutable `SqlDraft`; `Executor`; nested `Writer.write(SqlDraft) throws IOException`, normally a coordinator-owned wrapper around `SqlDraftStore.save`.
- Produces: package-private `SqlDraftWriteQueue(Executor, Writer)`, `save(SqlDraft): CompletableFuture<Void>`, `<T> barrier(Set<UUID>,Callable<T>): CompletableFuture<T>`, `<T> barrierAll(Callable<T>): CompletableFuture<T>`, `drainAndClose(): CompletableFuture<Void>`.
- Nested `Failure extends IOException` with `Reason` SUPERSEDED, CANCELLED, CLOSED, REJECTED; messages contain enum only and have no cause. These represent queue outcomes, not successful disk publications. Action/writer exceptions propagate to the coordinator without being printed here; the coordinator classifies storage errors and renders only sanitized messages.
- A pending replacement removes the old queued job and appends the newest at the current tail, settling the previous future as SUPERSEDED. Different UUIDs remain independent. Dequeue removes the job from the pending map before calling Writer, permitting one pending replacement while an older version executes.
- A barrier cancels matching pending saves immediately and appends its action. The executing save is not interrupted and finishes first. Saves submitted after a barrier are appended behind it. barrierAll covers every pending UUID; a set barrier affects only its specified IDs. A failed action settles its own future and does not silently retry or cancel unrelated actions.
- Queue order alone is not a privacy admission gate. Before disable, coordinator pauses admission and calls barrierAll with strict preference persistence. Before clear/delete it increments handle generation. The writer wrapper must classify structural failure and stop admission/cancel pending writes synchronously before returning/throwing; postponing that work to FX allows another unsafe write.
- `drainAndClose` rejects new saves/actions, drains accepted work, and returns the same completion on repeated calls. It does not close an injected executor or store. The background owner releases storage after this future; individual failed saves do not make drain success mean they were saved.
- Executor rejection settles all already queued futures and makes the queue permanently closed. It does not attempt to close external resources from a UI caller; the owner still performs its background cleanup. No FX dependency, timers, wall-clock delays, SQL execution, logging or default user paths.

- [ ] **Step 1: Create compiling stub and behavior tests.**

`src/com/datacube/config/SqlDraftWriteQueue.java`:

```java
package com.datacube.config;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class SqlDraftWriteQueue {
    enum Reason { SUPERSEDED, CANCELLED, CLOSED, REJECTED }
    static final class Failure extends IOException {
        private final Reason reason;
        Failure(Reason reason) { super("SQL draft queue: " + reason); this.reason = reason; }
        Reason reason() { return reason; }
    }
    @FunctionalInterface interface Writer { void write(SqlDraft draft) throws IOException; }
    SqlDraftWriteQueue(Executor executor, Writer writer) { }
    CompletableFuture<Void> save(SqlDraft draft) { return CompletableFuture.completedFuture(null); }
    <T> CompletableFuture<T> barrier(Set<UUID> ids, Callable<T> action) { return CompletableFuture.completedFuture(null); }
    <T> CompletableFuture<T> barrierAll(Callable<T> action) { return CompletableFuture.completedFuture(null); }
    CompletableFuture<Void> drainAndClose() { return CompletableFuture.completedFuture(null); }
}
```

`test/com/datacube/config/SqlDraftWriteQueueTest.java`:

```java
package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftWriteQueueTest {
    @TempDir Path temp;
    private static SqlDraft draft(int id, String sql) {
        return new SqlDraft(new UUID(0, id), 100, null, null, null, null, sql);
    }
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        int submissions;
        @Override public void execute(Runnable task) { submissions++; tasks.add(task); }
        void drain() {
            assertEquals(1, tasks.size(), "only one drain runnable may be scheduled");
            tasks.remove().run();
            assertTrue(tasks.isEmpty());
        }
    }
    private static void reason(SqlDraftWriteQueue.Reason expected, CompletableFuture<?> future) {
        assertTrue(future.isDone(), "no unresolved queue outcome");
        Throwable cause = assertThrows(CompletionException.class, future::join).getCause();
        SqlDraftWriteQueue.Failure failure = assertInstanceOf(SqlDraftWriteQueue.Failure.class, cause);
        assertEquals(expected, failure.reason());
        assertNull(failure.getCause());
    }

    @Test void oneThousandPendingVersionsRetainOnlyLatestAndSettleSupersededFutures() {
        ManualExecutor executor = new ManualExecutor();
        List<SqlDraft> writes = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, writes::add);
        CompletableFuture<Void> previous = queue.save(draft(1, "version 0"));
        for (int i = 1; i <= 1000; i++) {
            CompletableFuture<Void> latest = queue.save(draft(1, "version " + i));
            reason(SqlDraftWriteQueue.Reason.SUPERSEDED, previous);
            previous = latest;
        }
        assertEquals(1, executor.submissions);
        assertFalse(previous.isDone());
        executor.drain();
        previous.join();
        assertEquals(List.of(draft(1, "version 1000")), writes);
    }

    @Test void independentIdsSurviveAndNewestReplacementOccupiesCurrentTail() {
        ManualExecutor executor = new ManualExecutor();
        List<SqlDraft> writes = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, writes::add);
        CompletableFuture<Void> old = queue.save(draft(1, "old"));
        CompletableFuture<Void> other = queue.save(draft(2, "other"));
        CompletableFuture<Void> latest = queue.save(draft(1, "latest"));
        reason(SqlDraftWriteQueue.Reason.SUPERSEDED, old);
        executor.drain();
        other.join(); latest.join();
        assertEquals(List.of(draft(2, "other"), draft(1, "latest")), writes);
    }

    @Test void clearCancelsPendingAndPostBarrierSaveCannotMoveBeforeAction() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> events.add(value.sql()));
        CompletableFuture<Void> old = queue.save(draft(1, "old"));
        CompletableFuture<Integer> cleared = queue.barrierAll(() -> { events.add("clear"); return 7; });
        reason(SqlDraftWriteQueue.Reason.CANCELLED, old);
        CompletableFuture<Void> after = queue.save(draft(1, "after"));
        assertFalse(cleared.isDone());
        executor.drain();
        assertEquals(7, cleared.join()); after.join();
        assertEquals(List.of("clear", "after"), events);
    }

    @Test void targetedDeleteKeepsOtherIdsAndSerializesNewTargetSnapshotAfterIt() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> events.add(value.sql()));
        CompletableFuture<Void> target = queue.save(draft(1, "old target"));
        CompletableFuture<Void> other = queue.save(draft(2, "other"));
        CompletableFuture<Void> deleted = queue.barrier(Set.of(new UUID(0, 1)), () -> { events.add("delete"); return null; });
        reason(SqlDraftWriteQueue.Reason.CANCELLED, target);
        CompletableFuture<Void> after = queue.save(draft(1, "new target"));
        executor.drain();
        other.join(); deleted.join(); after.join();
        assertEquals(List.of("other", "delete", "new target"), events);
    }

    @Test void clearDuringRunningWriteWaitsForItAndCancelsOnlyPendingVersion() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        AtomicReference<SqlDraftWriteQueue> reference = new AtomicReference<>();
        List<CompletableFuture<?>> completions = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> {
            events.add(value.sql());
            if (value.sql().equals("running")) {
                SqlDraftWriteQueue active = reference.get();
                CompletableFuture<Void> pending = active.save(draft(1, "pending"));
                completions.add(active.barrierAll(() -> { events.add("clear"); return null; }));
                reason(SqlDraftWriteQueue.Reason.CANCELLED, pending);
                completions.add(active.save(draft(1, "after")));
                events.add("running finished");
            }
        });
        reference.set(queue);
        CompletableFuture<Void> running = queue.save(draft(1, "running"));
        executor.drain();
        running.join(); completions.forEach(CompletableFuture::join);
        assertEquals(List.of("running", "running finished", "clear", "after"), events);
        assertEquals(1, executor.submissions);
    }

    @Test void failedWriteSettlesItsFutureWithoutRetryingOrDroppingOtherOperations() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        IOException fault = new IOException("synthetic failure");
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> {
            events.add(value.sql());
            if (value.sql().equals("failed")) throw fault;
        });
        CompletableFuture<Void> failed = queue.save(draft(1, "failed"));
        CompletableFuture<Void> good = queue.save(draft(2, "good"));
        CompletableFuture<Integer> read = queue.barrier(Set.of(), () -> 2);
        executor.drain();
        assertSame(fault, assertThrows(CompletionException.class, failed::join).getCause());
        good.join(); assertEquals(2, read.join());
        assertEquals(List.of("failed", "good"), events);
        assertEquals(1, executor.submissions);
    }

    @Test void failedBarrierDoesNotPretendSuccessAndLaterActionStillRuns() {
        ManualExecutor executor = new ManualExecutor();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, ignored -> fail("unexpected write"));
        IOException fault = new IOException("synthetic barrier failure");
        CompletableFuture<Void> failed = queue.barrierAll(() -> { throw fault; });
        CompletableFuture<Integer> later = queue.barrier(Set.of(), () -> 5);
        executor.drain();
        assertSame(fault, assertThrows(CompletionException.class, failed::join).getCause());
        assertEquals(5, later.join());
    }

    @Test void unexpectedWriterErrorStillSettlesSaveAndDrainFutures() {
        ManualExecutor executor = new ManualExecutor();
        AssertionError fault = new AssertionError("synthetic writer error");
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, ignored -> { throw fault; });
        CompletableFuture<Void> saved = queue.save(draft(1, "last"));
        CompletableFuture<Void> drained = queue.drainAndClose();
        executor.drain();
        assertSame(fault, assertThrows(CompletionException.class, saved::join).getCause());
        assertTrue(drained.isDone());
        drained.join();
    }

    @Test void closeDrainsAcceptedJobsAndRejectsNewJobsWithoutClosingExternalExecutor() {
        ManualExecutor executor = new ManualExecutor();
        List<SqlDraft> writes = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, writes::add);
        CompletableFuture<Void> saved = queue.save(draft(1, "last"));
        CompletableFuture<Void> drained = queue.drainAndClose();
        assertSame(drained, queue.drainAndClose());
        assertFalse(drained.isDone());
        reason(SqlDraftWriteQueue.Reason.CLOSED, queue.save(draft(2, "rejected")));
        reason(SqlDraftWriteQueue.Reason.CLOSED, queue.barrierAll(() -> fail("closed action ran")));
        executor.drain();
        saved.join(); drained.join();
        assertEquals(List.of(draft(1, "last")), writes);
        executor.execute(() -> writes.add(draft(3, "external owner")));
        executor.drain();
        assertEquals(2, writes.size());
    }

    @Test void rejectedExecutorSettlesQueueAndDoesNotLeakItsErrorMessage() {
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(
                ignored -> { throw new RejectedExecutionException("synthetic private SQL"); },
                ignored -> fail("rejected executor wrote"));
        CompletableFuture<Void> first = queue.save(draft(1, "private SQL"));
        reason(SqlDraftWriteQueue.Reason.REJECTED, first);
        Throwable failure = assertThrows(CompletionException.class, first::join).getCause();
        assertFalse(failure.toString().contains("private SQL"));
        reason(SqlDraftWriteQueue.Reason.CLOSED, queue.save(draft(2, "second")));
        reason(SqlDraftWriteQueue.Reason.REJECTED, queue.drainAndClose());
    }

    @Test void isolatedRealStoreClearCannotResurrectOldQueuedText() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(temp.resolve("drafts"))) {
            store.save(draft(1, "old published"));
            ManualExecutor executor = new ManualExecutor();
            SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, store::save);
            CompletableFuture<Void> obsolete = queue.save(draft(1, "old queued"));
            CompletableFuture<Integer> clear = queue.barrierAll(store::clearRecoverable);
            CompletableFuture<Void> latest = queue.save(draft(2, "new edit"));
            CompletableFuture<Void> drain = queue.drainAndClose();
            reason(SqlDraftWriteQueue.Reason.CANCELLED, obsolete);
            executor.drain();
            assertEquals(1, clear.join()); latest.join(); drain.join();
            assertEquals(List.of(draft(2, "new edit")), store.snapshot().drafts());
        }
    }
}
```

- [ ] **Step 2: Observe RED with compiling stub.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftWriteQueueTest --rerun-tasks --no-daemon --console=plain
```

Expected exit1 with assertion failures for absent scheduling/ordering. Capture actual XML before replacing stub.

- [ ] **Step 3: Implement the bounded serial queue.**

`src/com/datacube/config/SqlDraftWriteQueue.java`:

```java
package com.datacube.config;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Serial disk-work ordering with one pending immutable snapshot per ID. */
final class SqlDraftWriteQueue {
    enum Reason { SUPERSEDED, CANCELLED, CLOSED, REJECTED }
    static final class Failure extends IOException {
        private final Reason reason;
        Failure(Reason reason) { super("SQL draft queue: " + reason); this.reason = reason; }
        Reason reason() { return reason; }
    }
    @FunctionalInterface interface Writer { void write(SqlDraft draft) throws IOException; }
    private abstract static class Job<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        abstract T run() throws Exception;
        final void execute() {
            try { future.complete(run()); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        }
        final void fail(Reason reason) { future.completeExceptionally(new Failure(reason)); }
    }
    private final class SaveJob extends Job<Void> {
        final SqlDraft draft;
        SaveJob(SqlDraft draft) { this.draft = draft; }
        @Override Void run() throws IOException { writer.write(draft); return null; }
    }
    private static final class ActionJob<T> extends Job<T> {
        private final Callable<T> action;
        ActionJob(Callable<T> action) { this.action = action; }
        @Override T run() throws Exception { return action.call(); }
    }
    private final Object lock = new Object();
    private final Executor executor;
    private final Writer writer;
    private final ArrayDeque<Job<?>> jobs = new ArrayDeque<>();
    private final Map<UUID, SaveJob> pending = new HashMap<>();
    private boolean draining;
    private boolean closed;
    private boolean rejected;
    private CompletableFuture<Void> shutdown;

    SqlDraftWriteQueue(Executor executor, Writer writer) {
        this.executor = Objects.requireNonNull(executor);
        this.writer = Objects.requireNonNull(writer);
    }

    CompletableFuture<Void> save(SqlDraft draft) {
        SaveJob job = new SaveJob(Objects.requireNonNull(draft));
        SaveJob previous;
        boolean start;
        synchronized (lock) {
            if (closed) return CompletableFuture.failedFuture(new Failure(Reason.CLOSED));
            previous = pending.put(draft.id(), job);
            if (previous != null) jobs.remove(previous);
            jobs.add(job);
            start = arm();
        }
        if (previous != null) previous.fail(Reason.SUPERSEDED);
        if (start) schedule();
        return job.future;
    }

    <T> CompletableFuture<T> barrier(Set<UUID> ids, Callable<T> action) {
        return enqueueBarrier(Set.copyOf(ids), action);
    }

    <T> CompletableFuture<T> barrierAll(Callable<T> action) { return enqueueBarrier(null, action); }

    private <T> CompletableFuture<T> enqueueBarrier(Set<UUID> ids, Callable<T> action) {
        ActionJob<T> job = new ActionJob<>(Objects.requireNonNull(action));
        List<SaveJob> cancelled = new ArrayList<>();
        boolean start;
        synchronized (lock) {
            if (closed) return CompletableFuture.failedFuture(new Failure(Reason.CLOSED));
            var iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (ids == null || ids.contains(entry.getKey())) {
                    SaveJob obsolete = entry.getValue();
                    iterator.remove();
                    jobs.remove(obsolete);
                    cancelled.add(obsolete);
                }
            }
            jobs.add(job);
            start = arm();
        }
        cancelled.forEach(obsolete -> obsolete.fail(Reason.CANCELLED));
        if (start) schedule();
        return job.future;
    }

    CompletableFuture<Void> drainAndClose() {
        boolean start;
        synchronized (lock) {
            if (shutdown != null) return shutdown;
            if (rejected) {
                shutdown = CompletableFuture.failedFuture(new Failure(Reason.REJECTED));
                return shutdown;
            }
            closed = true;
            ActionJob<Void> last = new ActionJob<>(() -> null);
            shutdown = last.future;
            jobs.add(last);
            start = arm();
        }
        if (start) schedule();
        return shutdown;
    }

    /** Called only under lock; execute outside lock even for inline executors. */
    private boolean arm() {
        if (draining) return false;
        draining = true;
        return true;
    }

    private void schedule() {
        try { executor.execute(this::drain); }
        catch (RuntimeException schedulingFailure) {
            List<Job<?>> abandoned;
            synchronized (lock) {
                rejected = true;
                closed = true;
                draining = false;
                abandoned = List.copyOf(jobs);
                jobs.clear();
                pending.clear();
            }
            abandoned.forEach(job -> job.fail(Reason.REJECTED));
        }
    }

    private void drain() {
        while (true) {
            Job<?> job;
            synchronized (lock) {
                job = jobs.poll();
                if (job == null) { draining = false; return; }
                if (job instanceof SqlDraftWriteQueue.SaveJob save) pending.remove(save.draft.id(), save);
            }
            job.execute();
        }
    }
}
```

- [ ] **Step 4: Focused GREEN and forced full regression.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftWriteQueueTest --tests com.datacube.config.SqlDraftSaveStateTest --tests com.datacube.config.SqlDraftStoreTest --tests com.datacube.config.SqlDraftDirectoryTest --tests com.datacube.config.SqlDraftCodecTest --rerun-tasks --no-daemon --console=plain
```

Expected exit0. Run full suite, restore environment, record exact totals/skips:

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```

- [ ] **Step 5: Self-review, commit and report.**

```powershell
git diff --check
git add -- src/com/datacube/config/SqlDraftWriteQueue.java test/com/datacube/config/SqlDraftWriteQueueTest.java
git commit -m "feat: serialize bounded SQL draft writes and barriers"
```

Report actual RED/GREEN/full XML, Requirement | Evidence, commit and concerns. No UI, cross-thread stress, crash/power-loss or complete P1.3 claim. Existing compiler notes remain disclosed.

## Self-review

The eleven tests directly observe saved values, action order and settled futures, including a controlled in-progress write and a real isolated Store. They do not merely inspect internal flags. Executor rejection is distinct from operation failure; unexpected writer Errors also settle the associated future so a queued drain is not stranded. The coordinator still classifies fatal/structural failures before another disk operation. The queue is bounded by pending draft IDs, not a global limit on explicit user actions; the coordinator must prevent overlapping management operations and handle admission/privacy state. Multi-thread lifecycle and FX dispatch tests remain required at coordinator integration, alongside the already separate timing-state tests.
