# SQL Workspace Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close P2 with deterministic late-completion regression, isolated restart and desktop evidence, a portable image, and whole-branch review before safe local integration.

**Architecture:** Exercise the existing production runtime, managed editors and single writer with synthetic records. Test-only scheduling gates control completion order; process launchers remain outside the distribution. Automated checks, desktop observations and packaging are separate evidence.

**Tech Stack:** Java 25, JavaFX 25, JUnit Jupiter 5.11.3, Gradle wrapper 9.2.0; no new dependencies.

## Global Constraints

- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。
- Work only in `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`. Preserve main's dirty `src/com/datacube/config/SqlDraftStore.java` without reading, staging, stashing or overwriting it. An overlap blocks integration, not isolated acceptance.
- One Gradle process at a time. Scope and restore environment changes. Record native terminal exit and actual XML counts; retain existing live skips and warnings honestly.
- Existing behavior may pass its first regression run. Do not invent a product RED. If production changes become necessary, first reproduce a real failing assertion and use the TDD/debugging/review gates.

---

### Task 1: Protect a new restore attempt from an older completion

**Files:**
- Modify/test: `test/com/datacube/fx/SqlWorkspaceManagerTest.java` only.
- Read: `src/com/datacube/fx/SqlWorkspaceManagerPane.java`, `src/com/datacube/config/SqlDraftCoordinator.java`, existing `SqlWorkspaceRecoveryTabsTest.Fixture`.
- Report: `.superpowers/sdd/workspace-acceptance-task-1-report.md`.

**Interfaces:** Existing manager buttons `workspace-manager-restore` / `workspace-manager-refresh`; real runtime `setWorkspaceEnabled(boolean)`, `workspaceSnapshot()`, `refresh()`; existing fixture `seed`, `save`, `open`, `ready`, `base.fx`, `base.call`, `base.offline`.

- [x] **Step 1: Add one deterministic behavioral test** named `oldRestoreCompletionCannotAffectNewPendingRefresh`. Start with one real saved draft and manifest and no editors. Start an explicit restore and hold its real workspace completion using a test-local scheduling gate. Advance the real runtime generation with persisted `setWorkspaceEnabled(false)`. Refresh the manager view, then start a newer explicit refresh and hold its result. Deliver the old successful result while the newer request is pending. Assert no editor factory ran, no tabs appeared, status still says processing, refresh/restore remain disabled and no old restore count/notice is applied. Release the newer result and assert exact available count, persisted disabled-recording text, refreshed buttons, zero editors and unchanged durable manifest. Always release gates, restore the runtime's original executor if temporarily intercepted, and settle owner cleanup. Use bounded latches/futures, never sleeps, fabricated manager state or direct calls to private `valid`.

The sequence under test is:

```text
real saved draft + manifest -> manager initial read complete
restore A -> actual backend result held before owner delivery
persist recording=false -> generation advances -> manager invalidates A
refresh B begins -> B completion held
deliver A -> FX barrier -> assert B remains pending and editor count=0
deliver B -> ready -> assert available=1, recording=false, editor count=0
```

- [x] **Step 2: Run the narrow test**, then all manager cases:

```powershell
./gradlew.bat test --tests '*SqlWorkspaceManagerTest.oldRestoreCompletionCannotAffectNewPendingRefresh' --no-daemon --console=plain
./gradlew.bat test --tests '*SqlWorkspaceManagerTest' --no-daemon --console=plain
```

Record whether the first run passes existing code or exposes a genuine product defect. If needed, pause and report a proposed narrow production fix; no speculative production changes in this task.

- [x] **Step 3: Verify discrimination**, temporarily removing only `token != attempt` from the current manager guard using a reversible exact patch. Run only the new case; require failure of the intended pending/editor/result assertion, not a timeout. Restore the exact production bytes and rerun the focused class. This is an intentional mutation check, not historical product RED. Never commit the mutation.
- [x] **Step 4: Run the complete suite once**, scoped nonheadless, inspect XML, then commit only the test file. Write the report with exact methods/commands/results, cleanup guarantees and mutation distinction. Controller independently reviews actual changes/results and dispatches a task review before marking complete.

### Task 2: Isolated restart and desktop acceptance

**Files:** Create local-only `.superpowers/sdd/SqlWorkspaceAcceptanceLauncher.java` and `.superpowers/sdd/workspace-acceptance.init.gradle`; controller records evidence in `docs/superpowers/verification/2026-08-31-sql-workspace-recovery.md`.

**Interfaces:** Consume the current `SqlDraftUi`, `SqlWorkspaceUi`, `SqlWorkspaceRecoveryTabs`, `ContentTabPane` and `DraftConnectionProbe`. Separate JVM modes write a confirmed checkpoint, close normally or halt only the owned child after confirmation, and recover in another JVM. Reuse the P1 launcher pattern documented in `2026-08-31-sql-draft-process-acceptance.md`; do not copy old ignored artifacts or profiles.

- [x] **Step 1:** Before implementing this separate harness task, derive its exact source/signatures and executable child matrix in a dedicated process plan. Each subprocess has explicit isolated `user.home`, bounded wait, expected exit and evidence marker; no network. Include normal exit preserving frozen order/selection, single-tab close, cancelled exit, abnormal termination, untouched startup, persisted disable and cleared layout retaining drafts.
- [x] **Step 2:** Execute the matrix and independently inspect actual logs and recovered controls. Preserve synthetic directories. A controlled harness is not desktop evidence.
- [ ] **Step 3:** Launch actual AppShell with a new marked isolated profile before initialization; omit only the external update check and disclose that boundary. Use Computer Use to observe startup, explicit restore, real order/selected text, both themes and keyboard access. Do not change privacy settings or confirm deletion through Computer Use; those paths use the automated synthetic harness. Close only the owned application and verify a second launch.

### Task 3: Portable image, whole-branch review and safe integration

**Files:** Existing `build.gradle` is read-only; generated `build/jpackage/DataCube` is an artifact. Update P2 verification, roadmap and `.superpowers/sdd/progress.md` with actual outcomes.

- [x] **Step 1:** Run `./gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain`, with `JAVA_TOOL_OPTIONS` temporarily absent, then restore it. Inspect `build/jpackage/DataCube/app/DataCube.cfg` and use JDK `jimage list` on `runtime/lib/modules`: production workspace classes present, no acceptance launcher/probe/test classes or isolated `user.home`; record SHA-256. Do not install or launch the update-enabled distribution entry.
- [x] **Step 2:** Freeze source HEAD, run full nonheadless regression, record actual XML and skips. Generate whole-branch review package from `7710ecb526d10a22e3fbff65367c50b04e44ed9d`; fresh strongest reviewer evaluates all P2 and Minor roll-up, including new attempt regression and existing unchecked compilation note.
- [ ] **Step 3:** Resolve blocking review findings through one fix task and rerun relevant tests/review. Recheck main status by path names only. If dirty overlap remains, retain the reviewed branch and ask for direction; never stash/discard or integrate over it. Otherwise local fast-forward merge only after all gates, then main regression. No network push/tag/release.

## Acceptance accounting

2026-08-31最新续验：main整合与c8c53aa推送已按维护者后续明确要求完成，CI四作业成功，详见main整合记录；下文“整合等待”仅为当时历史。Task2 Step3现已实际验证显式/重复恢复、顺序/可见选区、明暗主题及正常退出/重启，但复现恢复时补全浮层覆盖管理页，且对话框键盘覆盖仍不足，因此不勾选全部桌面通过。新增[焦点修复计划](2026-08-31-sql-completion-focus.md)，完成后复验。原main重叠文件已核实为换行差异并保存原字节，不再是待解决阻塞。

Latest code `e984c0c`: Task1 and process/image/full-code-review gates completed, including the separately planned save-feedback Important fix. Root verified the final full1557pass/3oldskips, independent74pass, image and17-process rerun. Task2 Step3 remains blocked by Computer Use helper failure after one permitted retry; Task3 Step3's review fixes are done, but integration awaits desktop acceptance and explicit direction for the overlapping dirty main file. No merge/push; do not rerun completed implementation tasks on the next continuation. Exact evidence is in the P2 verification record.

This plan explicitly separates test, process, desktop, image and integration gates. Task 2 receives a complete executable harness subplan before dispatch, as required by the existing staged P2 design; this acceptance checklist does not claim that source already exists. Main's dirty overlap is currently present. Passing test counts alone cannot close P2.
