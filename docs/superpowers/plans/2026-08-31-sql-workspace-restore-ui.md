# P2.4 SQL Workspace Restore UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose safe, explicit whole-workspace recovery and layout-only settings through startup and SQL drafts.

**Architecture:** One cohesive integration task: a synchronous FX recovery assembler plus an asynchronous dialog-scoped management pane. Reuse P1 restoration, P2 runtime/activity, and the existing app observer, with no new writer or timer.

**Tech Stack:** Java 25, JavaFX 25, JUnit Jupiter 5.11.3, Gradle wrapper 9.2.0.

## Global Constraints

- Java 25、JavaFX 25、JUnit Jupiter 5.11.3、Gradle wrapper 9.2.0；不添加依赖。
- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理；测试计划与证据放在本阶段文档和`.superpowers/sdd/`。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令；不上传内容、不新增遥测。
- 复用SqlDraftUi唯一runtime/writer/timer、P1受管恢复工厂及关闭/abort屏障；不打开第二store或绕过managed tabs。
- 仅独立worktree修改；不碰main未提交SqlDraftStore内容；不合并、推送、tag、发布、安装或升级。P2.5后才整分支验收/本地合并。

Baseline root run: `./gradlew.bat test --no-daemon --console=plain`, scoped non-headless, JDK25.0.1+8, session32637 exit0/52s, actual158suites1499total1496pass3baseline live skips0fail/errors. Root is responsible for public docs/ledger. Implementer owns only this task's allowed source/tests and its unique report. No concurrent Gradle processes.

### Task 1: Explicit workspace restore and layout management in the existing draft dialog

Read `docs/superpowers/specs/2026-08-31-sql-workspace-restore-ui-design.md` completely first; it binds this task, including the Global Constraints copied above. Work only at `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`.

**Files:**
- Create `src/com/datacube/fx/SqlWorkspaceRecoveryTabs.java` — assembly of validated resolution, no disk/connection calls.
- Create `src/com/datacube/fx/SqlWorkspaceManagerPane.java` — async read/controls/result feedback and dialog lifetime.
- Modify `src/com/datacube/fx/SqlDraftUi.java` — nullable existing workspace accessor only.
- Modify `src/com/datacube/fx/SqlWorkspaceUi.java` — synchronous recovery batch suppression and one final activity notification.
- Modify `src/com/datacube/fx/SqlDraftEditorBinding.java` — apply clamped real control selection for newly restored tabs.
- Modify `src/com/datacube/fx/SqlDraftManagerDialog.java` — compose optional workspace block, one subscription, disposal.
- Modify `src/com/datacube/fx/WorkspaceStartPane.java` and `src/com/datacube/fx/AppShell.java` — explicit callback and shared recovery dialog wiring, backward-compatible overloads.
- Create `test/com/datacube/fx/SqlWorkspaceRecoveryTabsTest.java` and `test/com/datacube/fx/SqlWorkspaceManagerTest.java`.
- Modify `test/com/datacube/fx/WorkspaceStartPaneTest.java` for callback/actual composition.
- Existing `SqlDraftRecoveryTabs.java` may receive a narrowly needed non-breaking accessor only if source inspection proves one needed; otherwise compose its existing restore method. Do not change its mandatory abort semantics.
- No config/store/codec/coordinator/ContentTabPane/registry/SQL execution or other feature changes. If an existing defect blocks this API, report concrete evidence to controller first.

**Interfaces consumed:**

Implementation clarification approved after root reproduction inspection: include SqlWorkspaceUi.finish handling for deliberately DISABLED/PAUSED draft protection after restoring old workspace. Preserve non-COMPLETED close outcome first; then settle COMPLETED without new layout publication/decision for these two modes only. Do not bypass UNAVAILABLE, true storage failures or mandatory guard/abort errors. Add real RED/GREEN close regression using counting decision returning CANCEL (not IGNORE), assert COMPLETED/exact preserved manifest/zero publication and decision calls. Existing SqlWorkspaceUiTest may be extended for this concrete regression if required. Root recorded actual pre-fix timeout and nested showDecision stack; test-fixture forced-ignore is not an acceptable fix.

Precise outcome correction after reading existing Handle.flush: the above COMPLETED assertion applies to DISABLED with approved guards. Eligible PAUSED drafts are refused by P1 flush, so that real restored-editor test must assert CANCELLED, retained editor, exact old manifest and zero workspace publication/decision. Never weaken the P1 guard to satisfy a completion expectation. Root observed both cases' initial deterministic expected-COMPLETED/actual-CANCELLED XML; only DISABLED is the production-bug RED, PAUSED first expectation was a test-design mistake and is recorded as such.

```java
// SqlDraftUi, FX owner
SqlDraftCoordinator runtime();
Node installedContent(UUID id);
SqlDraftEditorBinding installedBinding(Node content);
AutoCloseable observe(Runnable observer);
// SqlDraftRecoveryTabs (existing managed/offline restoration)
boolean restore(SqlDraft draft);
// SqlDraftCoordinator, must start calls on FX; all return cancellable copies
CompletableFuture<ManagementResult> refresh();
CompletableFuture<SqlWorkspaceStore.Snapshot> workspaceSnapshot();
long workspaceGeneration();
boolean managementPending();
Mode mode();
// SqlWorkspaceRecovery
Resolution resolve(SqlWorkspace workspace, List<SqlDraft> drafts);
// Resolution: tabs(), selectedDraftId(), missingDraftIds()
// ResolvedTab: draft(), anchor(), caret()
// SqlWorkspaceUi.owner(): existing SqlWorkspaceActivity
// activity.setWorkspaceEnabled(boolean), clearWorkspace(), status(), statusText()
```

**Interfaces produced:**
```java
// SqlDraftUi
SqlWorkspaceUi workspace(); // existing instance, nullable for legacy fixtures, never creates another owner
// SqlDraftEditorBinding
void restorePosition(int anchor, int caret);
// SqlWorkspaceUi
boolean beginRecovery(); // false if closing/disposed/already recovering
void endRecovery(boolean successful); // finally releases suppression; successful -> activity once
// new package-private final SqlWorkspaceRecoveryTabs
SqlWorkspaceRecoveryTabs(ContentTabPane tabs, SqlDraftUi drafts, SqlDraftRecoveryTabs recovery);
record Result(int opened, int reused, int missing, int failed) { }
Result restore(SqlWorkspaceRecovery.Resolution resolution);
// new package-private final SqlWorkspaceManagerPane implements AutoCloseable
SqlWorkspaceManagerPane(SqlDraftUi owner, SqlWorkspaceRecoveryTabs recovery);
Parent getNode();
void refreshView();
void close();
```

Use `SqlDraftManagerDialog.show` overload accepting existing single-draft restore function plus `SqlWorkspaceRecoveryTabs`. Original overload delegates null so legacy no-workspace fixtures keep behavior. AppShell always uses the new overload with actual workspace owner and factory; startup calls the same `openSqlDrafts` method. `WorkspaceStartPane` and `AppShell.startWorkspace` gain an overload accepting `Runnable recoverWorkspace`; originals remain and show no inert recovery button. Actual AppShell supplies the callback. New pane consumes real owner, not a duplicated state machine backend.

- [x] **Step 1: Genuine behavioral RED for batch restoration, then the pane/wiring tranche.**

Follow `SqlDraftRecoveryTabsTest` conventions: actual TempDir configs/store, FxUiTestSupport, CodeArea/editor objects, ContentTabPane, DraftConnectionProbe counters and independent cleanup. Extend no project-wide test inventory and create no `.testagent` artifacts. API shells may throw UnsupportedOperationException for compilation only. First test must actually arrange multiple draft records, saved workspace order/positions, and unrelated tabs then assert result/nodes/positions; not merely call a shell and assert a status. Record RED command/output then tell controller the XML and exact failing cases before GREEN. Later tranches may continue genuine RED/GREEN without separate approval after root checks the first tranche.

Required assertion shapes for actual fixtures (implement all fixture helpers with real paths/FX components before running):
```java
assertEquals(List.of(otherA, restoredA, otherB, existingB, otherC),
    List.copyOf(tabPane.getTabs()));
assertEquals("unsaved current B", existingEditor.getText());
assertEquals(7, existingEditor.getAnchor());
assertEquals(2, existingEditor.getCaretPosition());
assertEquals(restoredEditor.getLength(), restoredEditor.getAnchor());
assertEquals(1, restoredEditor.getCaretPosition());
assertEquals(new SqlWorkspaceRecoveryTabs.Result(1, 1, 1, 1), result);
assertEquals(0, probe.providers.get());
assertEquals(0, probe.sessions.get());
assertEquals(0, probe.metadata.get());
assertEquals(0, probe.network.get());
```
Those numbers are illustrative combined-case expectations, not a mandate to reuse them when fixture reality differs. Each test must prove its name with secondary observables (actual text/order/selection/disk contents), not invocation-only mocks. Failure/red counts are not coverage claims.

Test requirements to name in final report (parameterization allowed where it proves each case):
1. Ordered new tabs, selected original UUID, reverse UTF-16 clamping including CRLF-normalized CodeArea and empty checkpoint.
2. Reused edited tab retains body/Schema/anchor/caret; involved-slot permutation preserves unrelated SQL/nonSQL order and guards; second restore adds no duplicate factory.
3. Missing selected and failed selected fall back first success; null selected preserves existing selection, or picks first success without one.
4. Partial restore counts and continues after failing factory; all missing/all failed preserve selection, old manifest and activity; old snapshot remains available for retry.
5. Offline current ID rename, missing ID, same-name different ID, changed type, unavailable schema: exact preserved SQL/intent and four probe counters zero, no global active connection.
6. Manager initial loading after runtime init, status/counts/no creation before restore click; genuine runtime file snapshot, not fabricated button state.
7. Pending double-click single attempt; clear/disable/delete generation change after read invalidates before UI restore; closed pane ignores delayed results and creates no editors.
8. Absent, empty, corrupt, unsupported, unreadable/failed snapshot fixed messages; explicit refresh retry; no automatic failure loop. Invalid preference not shown as enabled.
9. Disabled workspace and disabled draft protection still allow available old restore and preserve no-new-write guarantee.
10. Toggle enabled->disabled success and failed-disable session pause, then successful explicit enable; no success label before persistence completes.
11. Clear cancel changes nothing, clear success clears only manifest and retains SQL/active editor, failure keeps old recoverable count and visible failure; malformed file not overwritten.
12. Actual startup button callback only on click; AppShell helper uses existing TabPane; actual dialog composed block + shared subscription disposed and writer still usable; original single restore behavior intact.

Run narrow tranches, always serialize Gradle. Environment/command:
```powershell
$env:JAVA_HOME='D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8'
$workspaceRestoreOptions=$env:JAVA_TOOL_OPTIONS
try {
  $env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
  ./gradlew.bat test --tests '*SqlWorkspaceRecoveryTabsTest' --tests '*SqlWorkspaceManagerTest' --tests '*WorkspaceStartPaneTest' --no-daemon --console=plain
  $workspaceRestoreExit=$LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS=$workspaceRestoreOptions }
exit $workspaceRestoreExit
```

- [x] **Step 2: Implement FX assembly with preserved ownership.**

Require FX caller. Save selected Tab before starting; ask existing workspace.beginRecovery (if legacy adapter absent, still restore but no activity hooks); if rejected return zero successful with all resolved entries failed. Loop resolved tabs using existing installedContent identity to distinguish new/reused, existing restore to perform actual admission, then locate actual installed Tab by Node identity. Count only success with matching Tab+binding; don't inspect raw SQL in toString/log/error. For new bindings use:
```java
void restorePosition(int anchor, int caret) {
    int length = editor.getLength();
    editor.selectRange(Math.max(0, Math.min(anchor, length)),
                       Math.max(0, Math.min(caret, length)));
}
```
Build desired list by replacing only participating slots of current tabs with successes in resolution order; use FXCollections.sort with precomputed Tab->index map for a permutation. Select using the design's explicit original/null/fallback rules. In finally call endRecovery(success count > 0). During recovery both workspace.activity and pulse return without capturing, then one final activity if successful; failed attempt cannot start/overwrite an inactive session. Do not run showAndWait or await/join in this assembly. Catch bounded per-item RuntimeException to count recoverable open failure while allowing later items; preserve existing factory abort mechanics. Fatal failures must still release suppression via finally.

- [x] **Step 3: Implement dialog-scoped state, reads, management and composition.**

Pane owns only UI: fields pending, closed, needsInitialRead, expectedGeneration, loaded immutable workspace/resolution/counts, last observed P1 management result, fixed notice. On first refreshView when runtime ready/notbusy schedule exactly one load, via queued FX call so no reentrant modal from timer. All callback continuations queued on FX with closed/runtime closed/expectedGeneration guards. P1 refresh -> FX continuation -> workspaceSnapshot -> FX continuation -> resolve/render. Restore click repeats this chain to use fresh snapshots; no editing while load pending. Generation changing invalidates outstanding attempt; UI records needs refresh but never restores without another explicit click. Older completion cannot reset a newer pending/read state (attempt token).

Root control IDs/text:
```java
"workspace-manager"; "workspace-manager-status"; "workspace-manager-notice";
"workspace-manager-restore"; "workspace-manager-refresh";
"workspace-manager-toggle"; "workspace-manager-clear";
"恢复工作区"; "刷新工作区"; "清空工作区";
"start-restore-workspace"; "恢复 SQL 工作区…";
```
Controls consume `SqlWorkspaceActivity` management methods. Render preference from valid persisted snapshot, overlaid by SESSION_PAUSED failure state; toggling false pauses immediately; operation failure remains distinguishable after subsequent read. Clear confirmation owned to pane window, default CANCEL; no generic directory deletion. Corrupt/unknown manifest stays protected with P1 available. Successful clear returns false for already-empty but still counts as successful operation; show empty without removing SQL records. Busy/init/unavailable/closed states disable mutations appropriately, without calling runtime APIs after close.

Provide fixed feedback without raw exception values. Show opened/reused/missing/failed counts after restore, leave dialog open. If no usable snapshot, never present stale counts as current ready-to-restore; ordinary failure retains clearly labeled prior recovery point, refresh offered. Render draft-off versus workspace-off distinctly. Startup only callback, no owner initialization until click. Combine P1 root and workspace block in dialog VBox, retaining P1 grow space; use existing theme.applyTo. Owner.observe callback refreshes both panes, finally closes both/subscription. No second observer timer. Ensure original overload remains functional without a workspace adapter.

- [x] **Step 4: Focused GREEN, full regression, assertion self-review.**

Repeat narrow command after RED/GREEN iterations. Run adjacent coverage once with `*SqlWorkspaceUiTest`, `*SqlDraftRecoveryTabsTest`, `*SqlDraftManagerTest`, `*SqlDraftFailureFeedbackTest`, `*ContentTabPaneCloseAttemptTest`. Then full `./gradlew.bat test --rerun-tasks --no-daemon --console=plain` scoped non-headless and JDK above, one active process only; wait for terminal exit and no remaining session before any new test. Verify actual XML totals and exact existing three live skips (Redis standalone and two SchemaDiff live methods), no new skips/failures. Keep pre-existing unchecked SqlEditorResultFilterContractTest/Gradle notice honest. Self-review every required behavior against concrete exact test names; no fabricated 80% claim.

- [x] **Step 5: Commit exact source/tests and report for independent spec/quality review.**

Run `git diff --check`; stage only named changed files from this task, inspect cached stat then commit `feat: restore SQL workspaces from startup and draft manager`. Controller docs/ledger remain uncommitted by implementer. Full report `.superpowers/sdd/workspace-restore-ui-task-1-report.md`: source SHA(s), RED/GREEN/full commands, exit/elapsed and XML counts, exact requirement-to-test table, files, self-review and concerns. Return only status, short SHA(s), compact tests and report path. Task ends only after independent Spec compliant/Approved review with any fixes retested and rereviewed. P2.5 remains next, no merge/push this task.

## Completion and next gate

Completed `32360ea..553e062`: 11 source/test files, final full160suites/1542tests/1539pass/3original skips/0failures/errors; root independent8suites107passed. Task review Spec compliant/Approved, no Critical/Important. One Minor static test-gap recommendation (old completion after a new attempt starts) and the existing unchecked compiler note must be considered in P2.5 whole-branch review. Full provenance, requirement-to-test matrix and remaining desktop/process/package gates: [verification](../verification/2026-08-31-sql-workspace-recovery.md). No main merge/push/tag.
