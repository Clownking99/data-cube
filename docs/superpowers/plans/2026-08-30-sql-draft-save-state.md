# SQL Draft Save State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the deterministic per-editor timing and revision/generation state contract for P1.3, independently of I/O and JavaFX.

**Architecture:** A small package-private state machine owns no SQL, threads, futures or files. The application coordinator will feed monotonic elapsed milliseconds, capture text only when a ticket is issued, and report publication results with that ticket. This task proves timing and stale-result rules; bounded worker queues, serialized storage barriers, UI dispatch and editor eligibility remain separate integration work and are not claimed here.

**Tech Stack:** Java25, JUnit Jupiter5.11.3; no new dependencies.

## Global Constraints

- Java25 / JavaFX25 / JUnit Jupiter5.11.3；不增加第三方依赖，不改 JDBC、历史文件或导出语义。
- 仅使用合成文本、临时目录与替身网关；不读取、不修改、不暂存、不清理 `.testagent/`。
- 不新增网络、遥测、AI、数据库自动请求、密码存储或结果/事务持久化；不推送、打 tag、安装或发布。
- SQL 保留空白、换行和 Unicode 原文；不按 SQL 去重、不截断；编码/容量超限必须显式失败并保留已有版本。
- 草稿恢复不得调用 JDBC 会话创建、连接工厂、元数据、SQL 执行、提交或回滚；缺失连接不得回退到左侧当前连接。
- 文件格式、关闭状态、数据库调用计数与错误路径均须有独立证据；未实现、跳过、工具受限不得计为通过。

---

### Task 1: Deterministic timing and publication tickets

**Files:**
- Create: `src/com/datacube/config/SqlDraftSaveState.java` — only timer deadlines, display-state inputs and invalidation tokens.
- Test: `test/com/datacube/config/SqlDraftSaveStateTest.java` — deterministic timestamp sequences, no sleeps/threads/files/JavaFX.

**Interfaces:**
- Consumes: nonnegative monotonic elapsed milliseconds supplied by its owner (not wall-clock time); nullable nonnegative saved wall-clock timestamp for an existing checkpoint.
- Produces: package-private `SqlDraftSaveState(Long savedAt, boolean enabled, boolean available)`; `state()`, `dueAt(): OptionalLong`, `savedAt(): OptionalLong`; `edited(long now)`, `capture(long now, boolean force): Ticket or null`, `succeeded(Ticket,long savedAt): boolean`, `failed(Ticket): boolean`, `retry(long now)`, `clear()`, `pause(boolean unavailable)`, `resume(long now, boolean captureCurrent)`.
- `State` values EMPTY, WAITING, SAVING, SAVED, FAILED, DISABLED, UNAVAILABLE. `Ticket` carries generation, revision, attempt, but never SQL or connection information.
- Owner-confined (coordinator must serialize calls); not a concurrent collection or disk barrier. Timer owner reads dueAt and cancels/replaces its scheduled callback. Capture is only permission to take/enqueue a snapshot, not proof of a disk write.
- Deadline is min(last edit + 1,000ms, first uncaptured edit + 10,000ms). Taking a ticket resets the dirty timing window, so input during an outstanding write starts a new window. Continuous input does not postpone beyond the 10-second target.
- A successful result is current only when its exact generation/revision/attempt ticket is still SAVING. New input, retry, clear or pause invalidates prior display results. An ordinary failure has no timer until a user retry or a new edit; force capture allows close-time retry, but cannot invent a save when nothing is pending.
- Clear invalidates outstanding tickets and cancels pending writes without enabling a paused state. It also removes the known checkpoint timestamp. Resume is an explicit owner action after successful preference/recovery handling; it invalidates old tickets and optionally schedules the current text. Input while paused does not schedule writes or implicitly enable them.
- Owner remains responsible for eligibility: an initial empty editor is not passed as a qualifying edit; clearing an already captured/saved draft is qualifying. The runtime must call clear for a newly emptied never-captured editor. Schema-only changes qualify when there is text or a checkpoint. Initial restoration uses the constructor timestamp, not edited, and therefore does not update mtime.
- Structural directory failures (including CLEANUP) are owner-level unavailability, not ordinary failed(ticket): the coordinator must call pause(true) for affected sessions even if the failed write's UI ticket became stale. Serialized clear/delete/disable operations remain required; this class alone does not prevent disk resurrection.

- [ ] **Step 1: Add compilable stub and complete deterministic tests.**

`src/com/datacube/config/SqlDraftSaveState.java`:

```java
package com.datacube.config;

import java.util.OptionalLong;

final class SqlDraftSaveState {
    enum State { EMPTY, WAITING, SAVING, SAVED, FAILED, DISABLED, UNAVAILABLE }
    record Ticket(long generation, long revision, long attempt) { }
    SqlDraftSaveState(Long savedAt, boolean enabled, boolean available) { }
    State state() { return State.EMPTY; }
    OptionalLong dueAt() { return OptionalLong.empty(); }
    OptionalLong savedAt() { return OptionalLong.empty(); }
    void edited(long now) { }
    Ticket capture(long now, boolean force) { return null; }
    boolean succeeded(Ticket ticket, long savedAt) { return false; }
    boolean failed(Ticket ticket) { return false; }
    void retry(long now) { }
    void clear() { }
    void pause(boolean unavailable) { }
    void resume(long now, boolean captureCurrent) { }
}
```

`test/com/datacube/config/SqlDraftSaveStateTest.java`:

```java
package com.datacube.config;

import org.junit.jupiter.api.Test;
import static com.datacube.config.SqlDraftSaveState.State.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftSaveStateTest {
    private static SqlDraftSaveState fresh() { return new SqlDraftSaveState(null, true, true); }

    @Test void freshEditorHasNoCheckpointAndRestoreDoesNotScheduleOrRewrite() {
        SqlDraftSaveState empty = fresh();
        assertEquals(EMPTY, empty.state());
        assertTrue(empty.savedAt().isEmpty());
        assertTrue(empty.dueAt().isEmpty());
        assertNull(empty.capture(0, true));
        SqlDraftSaveState restored = new SqlDraftSaveState(123L, true, true);
        assertEquals(SAVED, restored.state());
        assertEquals(123, restored.savedAt().orElseThrow());
        assertTrue(restored.dueAt().isEmpty());
        assertNull(restored.capture(0, true));
    }

    @Test void idleDeadlineMovesWithInputAndOnlyPublicationMarksSaved() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        assertEquals(WAITING, state.state());
        assertEquals(1000, state.dueAt().orElseThrow());
        assertNull(state.capture(899, false));
        state.edited(900);
        assertEquals(1900, state.dueAt().orElseThrow());
        assertNull(state.capture(1899, false));
        SqlDraftSaveState.Ticket ticket = state.capture(1900, false);
        assertNotNull(ticket);
        assertEquals(SAVING, state.state());
        assertTrue(state.dueAt().isEmpty());
        assertTrue(state.savedAt().isEmpty());
        assertNull(state.capture(1900, true));
        assertTrue(state.succeeded(ticket, 9999));
        assertEquals(SAVED, state.state());
        assertEquals(9999, state.savedAt().orElseThrow());
    }

    @Test void continuousInputCannotPostponeCapturePastTenSeconds() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        for (long now = 900; now <= 9900; now += 900) state.edited(now);
        assertEquals(10000, state.dueAt().orElseThrow());
        assertNull(state.capture(9999, false));
        assertNotNull(state.capture(10000, false));
        state.edited(10001);
        assertEquals(11001, state.dueAt().orElseThrow());
    }

    @Test void inputDuringPublicationStartsNewWindowAndRejectsOldSuccess() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(1000, false);
        state.edited(1001);
        assertFalse(state.succeeded(old, 20000));
        assertEquals(WAITING, state.state());
        assertEquals(2001, state.dueAt().orElseThrow());
        assertTrue(state.savedAt().isEmpty());
        SqlDraftSaveState.Ticket latest = state.capture(2001, false);
        assertTrue(state.succeeded(latest, 20001));
        assertEquals(SAVED, state.state());
        assertEquals(20001, state.savedAt().orElseThrow());
    }

    @Test void oldFailureAndRepeatedCompletionCannotOverwriteNewResult() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(0, true);
        state.edited(1);
        SqlDraftSaveState.Ticket latest = state.capture(1, true);
        assertFalse(state.failed(old));
        assertEquals(SAVING, state.state());
        assertTrue(state.succeeded(latest, 42));
        assertFalse(state.failed(latest));
        assertFalse(state.succeeded(old, 43));
        assertFalse(state.succeeded(latest, 44));
        assertEquals(42, state.savedAt().orElseThrow());
        assertEquals(SAVED, state.state());
    }

    @Test void ordinaryFailureWaitsForExplicitRetryWithANewAttemptTicket() {
        SqlDraftSaveState state = new SqlDraftSaveState(20L, true, true);
        state.edited(0);
        SqlDraftSaveState.Ticket failed = state.capture(0, true);
        assertTrue(state.failed(failed));
        assertEquals(FAILED, state.state());
        assertEquals(20, state.savedAt().orElseThrow());
        assertTrue(state.dueAt().isEmpty());
        assertNull(state.capture(50000, false));
        state.retry(50001);
        assertEquals(50001, state.dueAt().orElseThrow());
        SqlDraftSaveState.Ticket retried = state.capture(50001, false);
        assertEquals(failed.revision(), retried.revision());
        assertNotEquals(failed.attempt(), retried.attempt());
        assertFalse(state.succeeded(failed, 21));
        assertTrue(state.succeeded(retried, 22));
        assertEquals(22, state.savedAt().orElseThrow());
    }

    @Test void newEditOrCloseForceCanRetryButIdleSuccessCannotBeResaved() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        assertTrue(state.failed(state.capture(0, true)));
        state.edited(1);
        assertEquals(1001, state.dueAt().orElseThrow());
        assertTrue(state.failed(state.capture(1, true)));
        SqlDraftSaveState.Ticket close = state.capture(2, true);
        assertNotNull(close);
        assertTrue(state.succeeded(close, 30));
        state.retry(3);
        assertTrue(state.dueAt().isEmpty());
        assertNull(state.capture(3, true));
    }

    @Test void clearInvalidatesTicketsAndCloseCannotResurrectUneditedText() {
        SqlDraftSaveState state = new SqlDraftSaveState(9L, true, true);
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(0, true);
        state.edited(1);
        state.clear();
        assertEquals(EMPTY, state.state());
        assertTrue(state.dueAt().isEmpty());
        assertTrue(state.savedAt().isEmpty());
        assertFalse(state.succeeded(old, 10));
        assertNull(state.capture(10000, true));
        state.edited(10001);
        SqlDraftSaveState.Ticket newGeneration = state.capture(10001, true);
        assertNotEquals(old.generation(), newGeneration.generation());
        assertTrue(state.succeeded(newGeneration, 11));
    }

    @Test void disableCancelsPendingAndEditsDoNotImplicitlyResume() {
        SqlDraftSaveState state = fresh();
        state.edited(0);
        SqlDraftSaveState.Ticket old = state.capture(0, true);
        state.pause(false);
        state.edited(1);
        state.retry(2);
        assertEquals(DISABLED, state.state());
        assertTrue(state.dueAt().isEmpty());
        assertNull(state.capture(3, true));
        assertFalse(state.failed(old));
        state.clear();
        assertEquals(DISABLED, state.state());
        state.resume(4, true);
        assertEquals(WAITING, state.state());
        assertEquals(1004, state.dueAt().orElseThrow());
        assertTrue(state.succeeded(state.capture(4, true), 7));
        assertFalse(state.succeeded(old, 8));
    }

    @Test void unavailableRequiresOwnerRecoveryAndCanResumeWithoutSavingEmptyText() {
        SqlDraftSaveState state = new SqlDraftSaveState(12L, true, false);
        assertEquals(UNAVAILABLE, state.state());
        state.edited(0);
        state.retry(1);
        assertNull(state.capture(2, true));
        state.clear();
        assertEquals(UNAVAILABLE, state.state());
        state.resume(3, false);
        assertEquals(EMPTY, state.state());
        assertTrue(state.dueAt().isEmpty());
        state.edited(4);
        SqlDraftSaveState.Ticket old = state.capture(4, true);
        state.edited(5);
        state.pause(true);
        assertEquals(UNAVAILABLE, state.state());
        assertFalse(state.succeeded(old, 13));
        assertTrue(state.dueAt().isEmpty());
    }

    @Test void pausedCheckpointTimestampSurvivesWithoutPretendingAnEditWasSaved() {
        SqlDraftSaveState state = new SqlDraftSaveState(100L, false, true);
        assertEquals(DISABLED, state.state());
        assertEquals(100, state.savedAt().orElseThrow());
        state.resume(0, false);
        assertEquals(SAVED, state.state());
        assertEquals(100, state.savedAt().orElseThrow());
        state.pause(true);
        state.resume(1, true);
        assertEquals(WAITING, state.state());
        assertEquals(100, state.savedAt().orElseThrow());
    }

    @Test void invalidTimesAreRejectedAndDeadlineAdditionCannotWrap() {
        assertThrows(IllegalArgumentException.class, () -> new SqlDraftSaveState(-1L, true, true));
        SqlDraftSaveState state = fresh();
        assertThrows(IllegalArgumentException.class, () -> state.edited(-1));
        state.edited(10);
        assertThrows(IllegalArgumentException.class, () -> state.capture(9, false));
        assertEquals(1010, state.dueAt().orElseThrow());
        state.edited(Long.MAX_VALUE - 1);
        assertEquals(10010, state.dueAt().orElseThrow());
        SqlDraftSaveState.Ticket ticket = state.capture(Long.MAX_VALUE - 1, false);
        assertThrows(IllegalArgumentException.class, () -> state.succeeded(ticket, -1));
        assertTrue(state.succeeded(ticket, 0));
        state.edited(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, state.dueAt().orElseThrow());
        assertNotNull(state.capture(Long.MAX_VALUE, false));
    }
}
```

- [ ] **Step 2: Run RED before replacing stub.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftSaveStateTest --rerun-tasks --no-daemon --console=plain
```

Expected exit1 with behavior assertion failures after successful compilation. Record exact failures; no timer sleeps or arbitrary timeouts are needed.

- [ ] **Step 3: Implement state transitions.**

`src/com/datacube/config/SqlDraftSaveState.java`:

```java
package com.datacube.config;

import java.util.OptionalLong;

/** Owner-confined timing/status model; never reads editor text or performs I/O. */
final class SqlDraftSaveState {
    enum State { EMPTY, WAITING, SAVING, SAVED, FAILED, DISABLED, UNAVAILABLE }
    record Ticket(long generation, long revision, long attempt) { }
    private static final long IDLE_MILLIS = 1000;
    private static final long MAX_DIRTY_MILLIS = 10000;
    private State state;
    private long generation;
    private long revision;
    private long attempt;
    private long firstDirty = -1;
    private long deadline = -1;
    private long lastNow = -1;
    private Long savedAt;
    private Ticket inFlight;

    SqlDraftSaveState(Long savedAt, boolean enabled, boolean available) {
        if (savedAt != null && savedAt < 0) throw new IllegalArgumentException("Invalid draft timestamp");
        this.savedAt = savedAt;
        state = !available ? State.UNAVAILABLE : !enabled ? State.DISABLED
                : savedAt == null ? State.EMPTY : State.SAVED;
    }

    State state() { return state; }
    OptionalLong dueAt() { return deadline < 0 ? OptionalLong.empty() : OptionalLong.of(deadline); }
    OptionalLong savedAt() { return savedAt == null ? OptionalLong.empty() : OptionalLong.of(savedAt); }

    void edited(long now) {
        observe(now);
        revision = Math.incrementExact(revision);
        if (paused()) return;
        if (firstDirty < 0) firstDirty = now;
        deadline = Math.min(addSaturated(now, IDLE_MILLIS), addSaturated(firstDirty, MAX_DIRTY_MILLIS));
        state = State.WAITING;
    }

    Ticket capture(long now, boolean force) {
        observe(now);
        if (state != State.WAITING && !(force && state == State.FAILED)) return null;
        if (!force && now < deadline) return null;
        attempt = Math.incrementExact(attempt);
        inFlight = new Ticket(generation, revision, attempt);
        firstDirty = -1;
        deadline = -1;
        state = State.SAVING;
        return inFlight;
    }

    boolean succeeded(Ticket ticket, long publishedAt) {
        if (publishedAt < 0) throw new IllegalArgumentException("Invalid draft timestamp");
        if (!current(ticket)) return false;
        savedAt = publishedAt;
        inFlight = null;
        state = State.SAVED;
        return true;
    }

    boolean failed(Ticket ticket) {
        if (!current(ticket)) return false;
        inFlight = null;
        state = State.FAILED;
        return true;
    }

    void retry(long now) {
        observe(now);
        if (state != State.FAILED) return;
        firstDirty = now;
        deadline = now;
        state = State.WAITING;
    }

    void clear() {
        invalidate();
        savedAt = null;
        if (!paused()) state = State.EMPTY;
    }

    void pause(boolean unavailable) {
        invalidate();
        state = unavailable ? State.UNAVAILABLE : State.DISABLED;
    }

    void resume(long now, boolean captureCurrent) {
        observe(now);
        invalidate();
        state = savedAt == null ? State.EMPTY : State.SAVED;
        if (captureCurrent) edited(now);
    }

    private void invalidate() {
        generation = Math.incrementExact(generation);
        firstDirty = -1;
        deadline = -1;
        inFlight = null;
    }

    private boolean current(Ticket ticket) {
        return state == State.SAVING && ticket != null && ticket.equals(inFlight)
                && ticket.generation() == generation && ticket.revision() == revision;
    }

    private boolean paused() { return state == State.DISABLED || state == State.UNAVAILABLE; }

    private void observe(long now) {
        if (now < 0 || now < lastNow) throw new IllegalArgumentException("Invalid draft monotonic time");
        lastNow = now;
    }

    private static long addSaturated(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
```

- [ ] **Step 4: Run focused GREEN and full forced regression.**

```powershell
.\gradlew.bat test --tests com.datacube.config.SqlDraftSaveStateTest --tests com.datacube.config.SqlDraftStoreTest --tests com.datacube.config.SqlDraftDirectoryTest --tests com.datacube.config.SqlDraftCodecTest --rerun-tasks --no-daemon --console=plain
```

Expected exit0. Then run full suite with JavaFX enabled and restore the prior environment:

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```

Expected exit0; record exact XML totals and named skips. Existing unrelated unchecked compiler note is not a new regression or pristine output.

- [ ] **Step 5: Compare complete code, self-review and commit only owned files.**

```powershell
git diff --check
git add -- src/com/datacube/config/SqlDraftSaveState.java test/com/datacube/config/SqlDraftSaveStateTest.java
git commit -m "feat: model SQL draft debounce and stale publication states"
```

Report RED/GREEN/full evidence, a Requirement | Evidence mapping, changed files, commit and any concerns. Do not claim asynchronous storage barriers, autosave UI or user recovery are delivered by this pure state model.

## Self-review

The twelve named tests cover initial/restored state, idle and continuous timing, in-flight editing, stale and repeated callbacks, explicit retry/force, generation reset, disabled/unavailable edits, checkpoint timestamps and monotonic/overflow boundaries. No test starts a thread, sleeps or needs a database. P1.3 still needs a bounded worker coordinator using these tokens and actual Store operations; P1.4/P1.5 remain editor/desktop integration and product acceptance. This is an independently rejectable state-machine contract, not a replacement for those full-path gates.
