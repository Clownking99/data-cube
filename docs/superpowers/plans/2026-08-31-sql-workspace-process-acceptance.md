# SQL Workspace Process Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify P2 layout recovery across owned JVMs and expose actual AppShell to isolated desktop inspection.

**Architecture:** Adapt the complete P1 acceptance launcher template in [the process plan](2026-08-31-sql-draft-process-acceptance.md), retaining its explicit classpath, owner-thread calls, bounded child lifecycle and marker checks. Replace its single-draft matrix with the workspace matrix below. No production or JUnit source changes; launchers and compiled helpers stay outside main sources.

**Tech Stack:** Java25/JavaFX25/Gradle9.2.0, existing `DraftConnectionProbe`, no dependencies.

## Global Constraints

- `.testagent/` 属于用户，不读取、不修改、不暂存、不清理。
- 不读取真实配置、凭据、SQL 历史、业务导出；只用合成数据和独占临时目录验收。
- 不自动连接、预热元数据、执行 SQL、提交/回滚事务或重放 Redis 命令。
- 不访问外部数据库或上传内容；不新增遥测。不推送、tag、发布、安装或升级。
- P2 完整验收和整分支审查通过才本地合并 main；基础模块完成不等于用户入口完成。
- Worktree `D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery` only. Never read or alter main's dirty SqlDraftStore, existing P1 artifacts or profiles.
- Only terminate Process objects created by the launcher. Every child gets explicit owned `user.home` before JavaFX/AppShell initialization, maximum 40-second process wait and a 5-second forced-termination wait. No process-name sweeps.
- Do not use Computer Use in this subtask. Controller does actual desktop observations; no privacy setting changes or deletion confirmations through UI automation.
- Existing behavior acceptance may pass initially. Compilation/setup failures are not product RED. Report real defects for a separate test-first fix/review; do not change production here.

---

### Task 1: Verify workspace restart boundaries and prepare isolated AppShell

**Files:**
- Create ignored `.superpowers/sdd/SqlWorkspaceAcceptanceLauncher.java`.
- Create ignored `.superpowers/sdd/workspace-acceptance.init.gradle`.
- Report ignored `.superpowers/sdd/workspace-process-task-1-report.md`.
- Controller only edits tracked verification documents and ledger.

**Interfaces:**
- `SqlDraftUi(Path)` owns timer/writer; `attachWorkspace(ContentTabPane, LongSupplier, Supplier<CompletionStage<SqlWorkspaceUi.Decision>>)` attaches once. Use deterministic workspace clock for assertions; existing draft timer stays live.
- `SqlDraftRecoveryTabs` and `SqlWorkspaceRecoveryTabs.restore(SqlWorkspaceRecovery.Resolution)` create passive managed editors. `runtime.refresh()`, `workspaceSnapshot()`, `saveWorkspace()`, `setWorkspaceEnabled()`, `clearWorkspace()` share real on-disk backend.
- Actual `ContentTabPane.closeAllManagedTabsMandatory()` handles exit freezing and returns `TabCloseOutcome`. Single close dispatches the actual Tab close-request handler. Inject only the test-owned rejecting guard for cancellation; do not manipulate production lifecycle fields.
- Child startup store pre-seeding occurs before the runtime takes its lock. Fixed synthetic IDs `11111111-1111-1111-1111-111111111111` and `22222222-2222-2222-2222-222222222222` make independently recovered ordering auditable. SQL `select 'synthetic alpha';\n` and `select 'synthetic beta';\n`, schema ` synthetic_schema `, connection ID `missing-synthetic`, database POSTGRESQL. Record wall time current to avoid accidental expiry.
- Initial order is beta then alpha, selected alpha; beta selection (anchor8,caret2), alpha (anchor9,caret3). All offsets are valid UTF-16 positions. Do not normalize exact checkpoint strings.

- [x] **Step 1: Build the test-only launcher from the P1 template with these exact scenarios.** Use production batch recovery and actual CodeArea/Tab identities to assert text, order, selection and both offsets; do not validate only decoded storage. Every scenario checks probe provider/session/metadata/network counters individually equal0. Capture a real `Scene` for controls, but distinguish it from a desktop window. Use observer+latch readiness instead of arbitrary sleeps. Cleanup settles managed-close work and releases writer/probe resources; a cancelled scenario may finalize its owned editor resources without another successful close that overwrites its frozen record.

| Independent owned directory | Child sequence | Required evidence |
| --- | --- | --- |
| normal | seed-and-normal → restore | Actual normal exit removes live tabs but saves both ordered references and original selected/offset state; next JVM initially creates0 editors, explicit restore creates2 correctly, repeat restore reuses both |
| abrupt | seed-and-abrupt → restore | After actual draft+layout checkpoints confirmed, edit in-memory tail then `Runtime.halt(37)` in owned child only; restart returns confirmed strings/order/positions, not tail; lock released |
| single | seed-and-single-close → restore-single | Actual close beta leaves alpha; pulse+barrier commits single-entry layout and selected alpha; both SQL draft files retained; second JVM restores only alpha |
| cancelled | seed-and-cancel-exit → restore | Actual reject guard cancels exit (CANCELLED), previously confirmed two-entry layout unchanged after timer pulses; subsequent JVM can recover both confirmed records |
| untouched | seed-and-normal → untouched → restore | Middle JVM initializes but never restores/opens a SQL editor, exits; exact original manifest bytes unchanged; third JVM still restores both |
| disabled | seed-and-normal → disable → restore-disabled | Persist recording=false through activity owner, old manifest and drafts retained; second restore still creates passive editors with exact state; no new layout publication on pulse/exit |
| cleared | seed-and-normal → clear → restore-cleared | Clear through activity owner writes an empty layout; actual draft records remain2; next JVM has zero batch-restored editors but single-draft restore still returns exact alpha text |

Each child emits `CHILD_PASS=<mode>` after all assertions and cleanup. Abrupt emits `WORKSPACE_CHECKPOINT_CONFIRMED` before halt, parent accepts37 only with that marker. Parent prints each exact mode/exit and final `WORKSPACE_PROCESS_ACCEPTANCE_PASS=<owned-root>` only on total success. Preserve logs and all synthetic directories. Distinguish successful cleanup from force-kill of a timed-out/failing child.

- [x] **Step 2: Create Gradle init entry tasks.** Use P1 exact JavaCompile/JavaExec configuration with class renamed `com.datacube.fx.SqlWorkspaceAcceptanceLauncher`, source `.superpowers/sdd/SqlWorkspaceAcceptanceLauncher.java`, output `build/workspace-acceptance/classes`, and explicit child classpath property `workspace.acceptance.classpath`. Task names are `compileWorkspaceAcceptance`, `verifySqlWorkspaceProcesses`, `runSqlWorkspaceDesktop`. Desktop requires `-PworkspaceAcceptanceHome=<absolute-owned-directory>` and passes `-Duser.home` before launching.

```powershell
./gradlew.bat -I .superpowers/sdd/workspace-acceptance.init.gradle verifySqlWorkspaceProcesses --no-daemon --console=plain
```

Expected exit0 plus all matrix results; missing markers, wrong exits or wrong real control state fail the command. Helpers compile on test runtime classpath only, never sourceSets.main.

- [x] **Step 3: Prepare (do not launch) desktop mode.** Require absolute normalized `user.home` exactly equal passed directory, directory name prefix `datacube-workspace-ui-` and regular `ISOLATED_TEST_PROFILE` marker. Reject symlink/reparse aliases using real-path comparisons before any configuration access. Seed two synthetic drafts and ordered manifest only for a brand-new marked profile; record a separate seeding marker and never reseed on restart. Construct actual `AppShell`, theme hooks and Stage title `DataCube SQL工作区隔离验收`; retain P1 shutdownAsync close handler. Do not call the outer DataCubeFx update checker. Emit exact PID/profile. Controller creates the new profile/marker via New-Item and apply_patch before first launch.
- [x] **Step 4: Write a full report** with source HEAD, exact command/native exit, owned root and child evidence, any compilation/setup correction, actual control/probe assertions, whether production defects arose, and the exact desktop launch command. No whole-repository suite needed for ignored helper-only changes; controller runs full/image after this task. No commit of ignored helper source, no main merge. Independent reviewer gets complete helper files plus plan/report rather than an empty tracked diff.

## Self-review

Task review: Spec compliant / Approved, zero remaining findings after real ThemeManager fixture registration and narrow audited startup-diagnostic capture. Root independent final matrix session4397 exit0/54s, all17 children correct with zero connection probes and no other warning/error diagnostics. See [verification evidence](../verification/2026-08-31-sql-workspace-recovery.md). Desktop preparation is complete, but actual controller inspection was blocked by the external Computer Use helper; it is not a desktop pass. Rerun the matrix after the separately planned save-feedback product fix.

Process assertions prove real persisted state and real recovery controls in distinct JVMs, not actual mouse/keyboard discoverability. Desktop mode does not prove the update-enabled distribution entry or installation. Existing privacy guards remain active; only synthetic automated harness operations exercise clear/disable. Main integration remains blocked while the unrelated overlapping dirty file exists.
