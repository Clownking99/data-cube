# SQL Workspace Save Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ordinary active-session layout-save failures visible and explicitly retryable without weakening persistence or lifecycle guards.

**Architecture:** The existing manager renders a separate activity status; a guarded SqlWorkspaceUi adapter captures fresh installed/checkpointed controls and delegates to the existing activity retry/pulse. No new storage, queue, timer or background owner.

**Tech Stack:** Java25/JavaFX25/JUnit5.11.3/Gradle9.2.0, existing fixtures only.

## Global Constraints

- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- Only `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`; never main dirty SqlDraftStore. One Gradle at a time; scope/restore nonheadless options, retain native exits/XML. No goal changes.
- Preserve existing persistence/generation/pause/frozen/managed-abort semantics. No edits to config/store/runtime classes or general lifecycle guards. No hidden auto-retry; no success before durable publication.
- This addresses the complete whole-branch Important list (one finding). Existing unchecked note is unrelated Minor, not part of this fix.

---

### Task 1: Surface layout-save state and wire guarded explicit retry

**Files:**
- Modify `src/com/datacube/fx/SqlWorkspaceUi.java` (small guarded adapter only).
- Modify `src/com/datacube/fx/SqlWorkspaceManagerPane.java` (separate status/control, existing observer).
- Modify `test/com/datacube/fx/SqlWorkspaceManagerTest.java`; if needed adjacent guard coverage in `test/com/datacube/fx/SqlWorkspaceUiTest.java`.
- Report `.superpowers/sdd/workspace-save-feedback-task-1-report.md`. Controller owns specs/plans/evidence/ledger.

**Interfaces:** existing `SqlWorkspaceActivity.status/statusText/activity/retry/pulse/captureFailed`, `SqlWorkspaceUi.capture()` and owner flags, manager `blocked/render/refreshView`, existing real Fixture/backend failMethod/writes and CodeArea positions. No API that claims retry's immediately completed future means a saved layout.

- [ ] **Step 1: Add real failing behavior cases before production edits.** Primary case `activitySaveFailureIsVisibleAndExplicitRetryPersistsLatestLayout`: seed+restore real draft(s), save initial layout, fail `saveWorkspace`, change actual selection and pulse; wait for actual FAILED, assert a visible `#workspace-manager-activity-status` containing “未保存” and retained recovery-point wording, and enabled `#workspace-manager-retry-save`. Compare original manifest bytes and write count after extra pulses and ordinary manager refresh: neither implicitly retries. Repair backend, change actual selection again, click retry, await real SAVED and assert saved entry equals current anchor/caret (not initial or failed candidate), unchanged SQL, zero connection probes. First run should fail at missing feedback/control, not compile or timing. Report exact RED before implementation.
- [ ] **Step 2: Add the guarded adapter and manager controls.** The adapter must enforce the following logic (same method names consumed by manager):

```java
boolean canRetrySave() {
    if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX retry required");
    return !disposed && !closing && !recovering
            && drafts.runtime().mode() == SqlDraftCoordinator.Mode.ENABLED
            && !drafts.runtime().managementPending()
            && activity.status() == SqlWorkspaceActivity.Status.FAILED;
}

void retrySave() {
    if (!canRetrySave()) return;
    try {
        SqlWorkspace current = capture();
        activity.activity(current);
        activity.retry();
        activity.pulse();
    } catch (IllegalArgumentException invalid) {
        activity.captureFailed();
    }
}
```

Manager holds the existing `SqlWorkspaceUi` adapter from owner, adds Label ID `workspace-manager-activity-status` and Button “重试保存布局” ID `workspace-manager-retry-save` to the current workspace section. Its handler rechecks `!blocked()` and adapter capability, invokes adapter retrySave, then renders; no call to the generic `manage` success-label path. Render uses actual owner state for this separate line, and disables retry unless both checks hold. Existing preference/count status remains distinct. For enabled runtime, use fixed activity.statusText; for draft-disabled/paused or runtime-unavailable/closed show accurate fixed unavailable/no-new-layout text instead of inviting an impossible retry. Do not expose failure cause/message. No dialog/timer/private-state shortcut.

- [ ] **Step 3: Cover remaining interactions with real assertions.** Add `activityRetryWaitsForPublicationAndFailedRetryPreservesRecoveryPoint` using a held backend publication: no “已保存” before release, double click produces one accepted attempt, repeated failure retains original bytes and visible failure without timer loop, repaired explicit retry succeeds. Add `activityReadFailureCanRetryAfterRepairWithoutAutomaticLoop` using the real activity inspect path and repaired backend. Exercise current-capture retry after `captureFailed()` and verify latest real positions, rather than replaying cached failed positions. Parameterized guarded cases cover draft DISABLED/PAUSED, workspace disabled/session-paused, runtime CLOSED/unavailable, frozen owner, active recovery and closed manager: disabled control/handler must not add writes or clear rejection. Use current public owner lifecycle operations and actual fixtures rather than fabricating manager state. A transient busy state is already a blocker but must remain covered while retry publication is held. If production capture failure injection requires expanding beyond this seam, report instead of inventing a bypass.
- [ ] **Step 4: Validate.** Iterate the narrow new cases, then `./gradlew.bat test --tests '*SqlWorkspaceManagerTest' --tests '*SqlWorkspaceUiTest' --tests '*SqlWorkspaceRecoveryTabsTest' --no-daemon --console=plain`, scoped nonheadless. Run one complete `./gradlew.bat test --rerun-tasks --no-daemon --console=plain` before commit. Record exact tests/failures/skips and existing unchecked note. No simultaneous Gradle.
- [ ] **Step 5: Self-review and exact commit.** Stage only the permitted production/test files, not controller docs or ignored helper artifacts. Full report includes RED/GREEN, exact Requirement|Evidence methods, guarded states, runtime semantics, native exits/XML and any limitations. Root independently checks results then returns this one fix to the whole-branch reviewer; new image/process run is required after code changes. Desktop remains independently blocked, no merge/push.

## Self-review

Stored preference, recoverable old layout and active save health are separate. Explicit retry recaptures current safe bindings but never performs SQL execution or changes draft text. Failure, pending, durable success and lifecycle rejection all have behavioral evidence; no modal spam, auto-loop or weakened storage checks. Routine design choices follow the standing user authorization; only main-overlap integration still needs new direction.
