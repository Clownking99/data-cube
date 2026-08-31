# SQL Workspace Activity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接通真实 SQL 标签的活动布局保存和安全退出冻结，不实现 P2.4 恢复界面。

**Architecture:** 沿用唯一 SqlDraftCoordinator / SqlDraftWriteQueue；状态 owner 合并候选，FX 适配器捕获已安装标签。关闭 gate 置于 registry 最终状态转换之前，并纳入 mandatory-abort 结算。

**Tech Stack:** Java 25 / JavaFX 25 / JUnit Jupiter 5.11.3 / Gradle 9.2.0，无新依赖。

## Global Constraints

- Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；不添加依赖。
- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 工作区清单只含草稿 UUID、顺序、选中项、时间、光标/选择锚点；不复制 SQL、连接身份、Schema、凭据或结果集。连接身份与 Schema 由 P1 草稿提供。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。
- 工作区与草稿共用同一个store、目录锁、writer队列；不改变P1文件格式、原子发布和事务关闭语义。

## Task 1: 活动捕获与受管关闭最终化

该任务为一个集成交付：状态、FX 和关闭 gate 必须共同通过，不接受仅完成状态类。工作目录 `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`。主目录 SqlDraftStore 有无关用户改动，不读取其内容。

**Files:**

- Create `src/com/datacube/config/SqlWorkspaceActivity.java`：无 FX 的活动保存协调。
- Create `src/com/datacube/fx/SqlWorkspaceUi.java`：实际节点适配、退出决定。
- Modify `src/com/datacube/config/SqlDraftCoordinator.java`：只读 workspaceGeneration；如确认确需 draft checkpoint acknowledgement，限于明确语义的小接口。
- Modify `src/com/datacube/fx/SqlDraftUi.java`：owner 安装与唯一 timer 驱动。
- Modify `src/com/datacube/fx/SqlDraftEditorBinding.java`：窄 checkpoint/position/activity 桥；禁止放松 mandatory draft close。
- Modify `src/com/datacube/fx/ContentTabPane.java`、`src/com/datacube/fx/AsyncManagedTabRegistry.java`：close attempt 生命周期/最终 gate、可靠 abort 所有权。
- Modify `src/com/datacube/fx/AppShell.java`：真实入口布线。
- Test create `test/com/datacube/config/SqlWorkspaceActivityTest.java`、`test/com/datacube/fx/SqlWorkspaceUiTest.java`。
- Test extend `test/com/datacube/fx/AsyncManagedTabRegistryTest.java`，必要时在已有 `ContentTabPane` 相关测试或新 UI 测试覆盖 integration。
- `SqlEditorPane` 若位置/事件只能从这里正确读取，允许最小 package-private accessor；不动查询/事务逻辑。
- Controller owns plan/spec/verification/ledger; implementer writes only source/tests + its ignored report. No edits other files without specific reason sent to controller.

**Interfaces and existing contracts:**

```java
// Existing runtime APIs, UI owner only, future completion for workspace APIs delivered on UI.
CompletableFuture<SqlWorkspaceStore.Snapshot> workspaceSnapshot();
CompletableFuture<Void> saveWorkspace(SqlWorkspace workspace);
CompletableFuture<Void> setWorkspaceEnabled(boolean enabled);
CompletableFuture<Boolean> clearWorkspace();
// Add without changing epoch semantics:
public long workspaceGeneration() { owner(); return workspaceEpoch.get(); }
```

`workspaceSnapshot` read is lazy on first SQL activity/explicit request; constructor and untouched exit do not create layout files. Existing savedAt acknowledgement is not installedContent. `Handle.flush` may settle before status delivery. Avoid relying on callback LIFO; final eligible UUIDs may be obtained via an explicit same-writer validated draft snapshot after close guards, with generation recheck and UI busy settlement. No second directory open.

Read design `docs/superpowers/specs/2026-08-31-sql-workspace-activity-design.md` first. It is binding requirements. Complete executable algorithms below define behavior; integration names may follow local conventions, but the two created classes and responsibility boundaries are fixed.

- [x] **Step 1: Add regression tests and compilation-only API shells, run behavioral RED.**

Use real `SqlDraftCoordinator` with manual disk/UI executors and injected clocks as in SqlWorkspaceRuntimeTest; actual TempDir store is deliberate local persistence integration. Use representative `SqlDraftRecoveryTabsTest` synthetic FX fixture and DraftConnectionProbe; no real profile. Do not create a second test framework or broad coverage inventory.

Required test matrix (exact descriptive names may be adjusted with report mapping):

| Test | Concrete assertions |
| --- | --- |
| untouchedSessionAndExitPreservePreviousLayout | Seed old layout, construct owner, pulse and close untouched; no workspace save, exact old layout remains |
| firstCheckpointBecomesEligibleWithoutAnotherUserAction | Install unsaved nonempty tab, activity, no layout identity before save acknowledgement; after checkpoint/pulse include UUID, exact position |
| emptyNeverSavedExcludedButClearedCheckpointIncluded | Both kinds simultaneously, only formerly saved UUID present; selected unsaved yields null |
| continuousActivityIsCoalescedWithBoundedDeadline | Clock before/at 1000 idle and 10000 continuous; one pending and latest only; timestamp-only polls do not write |
| busyKeepsLatestCandidateAndDoesNotLoseCompletion | Hold one write, several layouts, finish; only first and newest exact layout published |
| managementInvalidatesUnsubmittedCandidate | Parameterized P1 clear/delete/toggle + P2 clear/toggle; BUSY candidate captured before operation never reappears under new generation, including failed management |
| failedDisableRemainsPausedUntilExplicitSuccessfulEnable | Old preference still true after injected failure; more activity/pulses cannot save; explicit successful enable resumes only new capture |
| ordinaryFailureRequiresExplicitRetry | Fail write, repeated pulse/activity no automatic write; explicit retry publishes exact latest candidate; fixed sanitized status |
| sqlOrderSelectionAndReversePositionsFollowActualTabs | SQL/nonSQL interleaving, reverse selection, first persisted checkpoint; exact manifest entries and null selected for other tab |
| cancelledSingleCloseKeepsLayoutButActualRemovalUpdatesIt | Guard cancellation keeps tab/UUID; approved removal drops it after UI removal, not on request |
| exitFreezesBeforeRemovalAndIncludesFinalDraftCheckpoint | Multiple tabs including newly saved on close; exact original order/selection/positions persisted after final saves; no intermediate empty write |
| cancelledExitRetainsFrozenUntilExplicitActivity | Cancel/partial closure changes UI automatically; pulses do not write shortened layout; next explicit action permits update |
| layoutFailureCancelAllowsNewManagedTab | All tabs close, layout fails, user cancels; registry accepts real factory afterward and abort tracking works on subsequent close |
| layoutFailureRetryAndIgnoreHaveDifferentPersistence | Retry publishes same frozen layout; ignore retains exact prior file, both allow shutdown only after settlement |
| partialAbortFailureNeverReopensRegistryOrRunsTeardown | Pending reservation abort then failure + completion gate path; terminal FAILED_PARTIAL and no teardown |
| reservationFinishingDuringExitIsCapturedBeforeGuardClose | Hold open reservation while exit begins; complete installation, freeze includes it, no accepted tab omitted |
| callerCancellationDoesNotCancelInternalCloseOrPublication | Cancel public returned future, internal attempts settle and registry ownership remains valid |

Start with minimal compiling stubs only; missing-behavior UOE/assertion failures are RED, compilation failures are not the evidence. Send controller command, elapsed/exit, test names/failing output and pause until RED ACK before implementing behavior.

```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$workspaceTestOptions=$env:JAVA_TOOL_OPTIONS
try {
  $env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
  ./gradlew.bat test --tests '*SqlWorkspaceActivityTest' --tests '*SqlWorkspaceUiTest' --tests '*AsyncManagedTabRegistryTest' --no-daemon --console=plain
} finally { $env:JAVA_TOOL_OPTIONS=$workspaceTestOptions }
```

- [x] **Step 2: Implement bounded candidate/state owner and FX capture.**

Keep immutable `(workspace, generation)` candidate; one in-flight and one latest. On every explicit activity and pulse first read runtime generation. Mismatch discards pending/frozen automatic candidate, updates observed baseline and holds until new explicit activity; do not retag old candidate. A successful new checkpoint following real editing can update the current active layout without an additional user event. On failure keep newest but latch failed; only retry or explicit successful re-enable releases latch. BUSY is backpressure, CANCELLED is invalidation, neither says disk corrupt.

Use exact layout equivalence excluding capture time:

```java
private static boolean sameLayout(SqlWorkspace left, SqlWorkspace right) {
    return left != null && right != null
            && left.entries().equals(right.entries())
            && java.util.Objects.equals(left.selectedDraftId(), right.selectedDraftId());
}
private static long addSaturated(long value, long amount) {
    return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
}
// When a genuinely changed candidate arrives in an admitted active generation:
// firstDirty = firstDirty < 0 ? now : firstDirty;
// due = Math.min(addSaturated(now, 1000), addSaturated(firstDirty, 10000));
// On pulse, now >= due && inFlight == null && !failed && !frozen submits latest.
// Upon UI-delivered completion, clear only that inFlight; newer latest is not cleared.
```

Own fixed status including idle/pending/saved/disabled/session-paused/failed/frozen; no raw Throwable in visible text. Store failure codes and runtime failure reasons remain observable for tests. Public callbacks return copied futures so caller cancellation cannot release internal admission. Every async result rechecks its candidate identity/generation before touching newest status. A failed read is not an empty/valid enabled snapshot. Unknown/corrupt preference or layout is protected, not overwritten.

FX observes installed SQL bindings in actual TabPane order, checks acknowledged savedAt, reads anchor/caret, and filters selected UUID to included entries. List/selection events and editor events trigger explicit activity only outside programmatic close/freeze handling. Timer observation may discover new checkpoint, but cannot synthesize fresh activity after management or cancelled exit. Register listeners once and dispose on owner shutdown/detach. Keep no unbounded list of snapshots; no background read of Node. `SqlDraftUi(Path)` test-compatible constructor may remain, with overloaded constructor/explicit attach to ContentTabPane used by AppShell. Untouched `LazyValue.peek()` exit stays lazy.

Settings APIs live on owner for P2.4 reuse: setWorkspaceEnabled(false) sets session pause before invoking runtime; write success confirms off, failure remains paused; successful explicit true resumes. Clear drops in-memory pending immediately and delegates clearWorkspace. Existing runtime calls outside owner still invalidate by workspaceGeneration polling. Do not add full recovery/settings UI here; expose fixed status for existing observers and close failure dialog.

- [x] **Step 3: Integrate attempt-scoped freeze and finalization gate.**

Keep original `closeAll(mode)` as a no-extra-work delegate, preserving existing tests. Add attempt lifecycle hook scoped to one closeAll, called exactly once after reservations settle and before guards start, and a finalization function called after guard outcomes but before registry final state. Callback exceptions/null are fail-closed; never unblock teardown based on an exception.

The finalization gate combines mandatory-abort outcome with registry outcome before any workspace decision. Deferred settlement avoids synchronous empty-registry circular ordering:

```java
CompletableFuture<TabCloseOutcome> abortSettlement = new CompletableFuture<>();
// Register gate using abortSettlement BEFORE starting closeAll; it must not join/block FX.
// After sealing registry admission, bridge tracker.hardSeal() into abortSettlement.
// Gate waits both outcomes. FAILED_PARTIAL dominates CANCELLED dominates COMPLETED.
```

Registry must not reach CLOSED until gate COMPLETED. Gate CANCELLED opens the attempt only after ContentTabPane rotates the successfully settled tracker while holding ownershipLock. No public reset/reopen CLOSED method. Genuine FAILED_PARTIAL keeps registry sealed. A new registration cannot slip between releasing ownership and fresh abort tracker installation. Invoke callbacks outside registry monitor, and dispatch UI work explicitly to FX. Do not wait synchronously for IO or FX.

Architecture review resolution: rotation followed merely by completing the gate future still leaves a race with another closeAll hard-sealing the new tracker. Supply an attempt terminal hook accepting `(outcome, commitRegistryTransition)`; ContentTabPane executes tracker rotation and that transition inside the same `ownershipLock` region, while registry invokes the hook outside its monitor. The no-hook overload executes the transition directly. Gate aggregation must be monotone: FAILED_PARTIAL cannot become CANCELLED/COMPLETED and original CANCELLED cannot become COMPLETED. The pre-close freeze callback must await FX execution before any guard starts, including when final reservation was released off FX.

For final eligibility prefer existing `runtime.refresh()` after guard/abort settlement. Require `result.succeeded()` and non-null writable snapshot, intersect its persisted UUID set with the frozen entries. Explicitly dispatch the continuation to FX before invoking saveWorkspace: refresh posts its busy-clear before completing its exposed future. Do not depend on Handle.flush callback registration order or change the P1 flush contract. Check generation again after refresh and before final save.

Freeze includes provisional identities of all installed SQL tabs, full order/selection/positions, generation, and active flag before individual close. Only after all guards/aborts succeed resolve truly saved identities and publish. Final write waits any accepted previous save, replaces coalesced automatic candidates with frozen one, and awaits actual UI-delivered outcome before shutdown writer. Initial untouched, recording disabled/paused, or invalidated generation skips new layout without deleting old. Existing protected/corrupt layout produces failure decision rather than overwrite. Retry always uses same immutable frozen layout, not now-empty TabPane.

Fixed dialog content:

```java
String title = "工作区记录未保存";
String message = "本次标签顺序和编辑位置尚未保存，已有恢复点保留。可以重试、取消退出，或仅忽略本次工作区更新后退出。";
String retry = "重试";
String cancel = "取消退出";
String ignore = "忽略本次工作区更新并退出";
```

Default button is cancel. Inject the decision function into UI adapter for deterministic tests; production implementation shows owned FX Alert without exposing exceptions. On CANCELLED/FAILED_PARTIAL preserve frozen object/hold; no timer recapture until explicit activity. First later explicit action uses current installed nodes, not detached freeze references.

- [x] **Step 4: Focused GREEN, full regression once, self-review.**

Run same focused command, then full command scoped non-headless:

```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$workspaceTestOptions=$env:JAVA_TOOL_OPTIONS
try {
  $env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
  ./gradlew.bat test --rerun-tasks --no-daemon --console=plain
} finally { $env:JAVA_TOOL_OPTIONS=$workspaceTestOptions }
git diff --check
```

Expected exit 0, zero new skips. Baseline full: 155 suites / 1452 total / 1449 pass / 3 existing live DB skips. Inspect actual XML for counts and exact skipped names. Preserve existing compile notes explicitly; no claim pristine if emitted. Check assertions against behavior matrix and record exact tests. No concurrent Gradle with controller. No broad whole-repo crawling.

- [x] **Step 5: Commit exact source/tests and report for independent task review.**

Stage only allowed changed source/test file names, never `.testagent/`, parent worktree or controller docs; run `git diff --cached --stat` before commit. Commit message `feat: capture SQL workspace activity and freeze safe shutdown`. Full report path `.superpowers/sdd/workspace-activity-task-1-report.md`, includes RED/GREEN/full command+exit+elapsed, XML totals/skips, requirement-to-test names, source files, self-review and concerns. Return status/SHA/one-line result/report path only. Controller records frozen BASE before dispatch, creates unique review package and requests independent sol review of spec+quality; no merge/push.

## Review boundary

Completed `4c14aca..2fa333c`: initial integration `0a7203b`, review fix `2fa333c` adds ContentTabPane attempt deduplication and `test/com/datacube/fx/ContentTabPaneCloseAttemptTest.java`. Final full158 suites/1499total/1496pass/3baseline skips/0fail; root independent80/80. Initial shallow tests were rejected, genuine RED replaced them; one overlapping pre-fix test launch was excluded and rerun serially. Independent review found one attempt/tracker race, reproduced deterministically and fixed; re-review Spec compliant / Approved, no remaining Critical/Important. Detailed provenance and requirement mapping: [verification](../verification/2026-08-31-sql-workspace-recovery.md).

P2.3b ends only after actual AppShell capture+exit integration, full regression, and task-scoped spec/quality approval. P2.4 remains explicit restoration entry/settings UI; P2.5 remains full branch/desktop/package acceptance before main merge. This plan does not authorize a new remote action or waive those gates.
