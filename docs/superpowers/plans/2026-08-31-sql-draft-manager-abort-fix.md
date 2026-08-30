# SQL Draft Recovery Abort Finalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Release FX-owned editor listeners before a failed recovery attempt's abort barrier completes.

**Architecture:** Keep the existing blocking abort worker and lifecycle registry. A recovery-local abort callback invokes resource cleanup and then awaits FX finalization from the background worker. Reuse BestEffortCloseSequence so a resource failure does not skip listener cleanup or become a false successful abort.

**Tech Stack:** Existing Java25/JavaFX25/JUnit5; no dependencies.

## Global Constraints

- Work only in `D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`, branch `codex/sql-draft-recovery`; no main merge until entire P1 acceptance and broad review.
- Never read, modify, stage or delete `.testagent/` contents. No push/tag/release/install/real database/credentials/history/telemetry.
- Preserve reservation and mandatory rollback behavior, draft-first close guards, raw SQL and offline recovery. No disk I/O or joins on FX; no per-dialog executors.
- This fixes the reviewer-confirmed plan-mandated lifecycle defect. Under the user's explicit routine-design waiver, the controller chooses complete abort disposal, superseding the earlier resource-only callback example. No additional user confirmation is required.

---

### Task 1: Include FX finalization in recovery abort ownership

**Files:** Modify only `src/com/datacube/fx/SqlDraftRecoveryTabs.java` and `test/com/datacube/fx/SqlDraftRecoveryTabsTest.java`. Append fix evidence to `.superpowers/sdd/draft-manager-task-1-report.md`. Controller owns tracked docs.

**Interfaces:** Existing `BestEffortCloseSequence.run(Runnable...)` attempts all steps and propagates PartialCloseException. `ContentTabPane.AbortBinding.bind(Runnable)` executes on a virtual-thread mandatory abort guard; the close-all barrier waits for that callback. `SqlEditorPane.closeResources()` blocks safely off FX; `finalizeCloseOnFx()` is idempotent and removes the settings listener. No changes to those existing APIs.

- [ ] **Step 1: Extend both existing failure tests before production edits.** In each test's final `f.fx` block below, after the existing assertions and before fixture cleanup, add:

```java
assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
        fieldUnchecked(f.created.getFirst(), "resourcesClosed")).get());
assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
        fieldUnchecked(f.created.getFirst(), "uiFinalized")).get(),
        "abort barrier must include FX finalization before fixture cleanup");
```

Apply to `failedSelectionInstallationAbortsBoundPaneAndReleasesDraftSubscription` and `initializerFailureHasEarlyAbortOwnershipAndNoInstalledMapping`. The latter already asserts resourcesClosed; do not duplicate it. Keep all existing tab/map/handle/offline assertions and finally cleanup. These tests observe the production lifecycle before the fixture's explicit fallback finalizer; no GC timing or private JavaFX listener internals.

- [ ] **Step 2: Run behavioral RED.** Use nonheadless wrapper below with focused command. Expected9tests2fail, the two new uiFinalized assertions false; no readiness timeouts. Notify controller with XML evidence before implementation and wait for acknowledgement if requested.

```powershell
$draftPriorOptions=$env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS="$draftPriorOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --tests '*SqlDraftRecoveryTabsTest' --rerun-tasks --no-daemon --console=plain
    $draftExit=$LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS=$draftPriorOptions }
exit $draftExit
```

- [ ] **Step 3: Replace both resource-only abort callback references.** Add imports `java.util.concurrent.CompletableFuture` and `javafx.application.Platform`. The early binding becomes:

```java
pane -> abort.bind(() -> abortPane(pane)),
```

The ManagedTabSpec's final argument becomes `() -> abortPane(pane)` (normal approved-close UI finalizer remains `pane::finalizeCloseOnFx`). Add this complete recovery-local helper:

```java
/** Mandatory abort runs on its existing worker and completes only after FX disposal. */
private static void abortPane(SqlEditorPane pane) {
    if (Platform.isFxApplicationThread()) {
        throw new IllegalStateException("Recovery abort cleanup must run off the FX Application Thread");
    }
    BestEffortCloseSequence.run(pane::closeResources, () -> {
        CompletableFuture<Void> finalized = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                pane.finalizeCloseOnFx();
                finalized.complete(null);
            } catch (Throwable failure) {
                finalized.completeExceptionally(failure);
            }
        });
        finalized.join();
    });
}
```

The helper must not call requestClose/mandatoryClose, persist history or save failed editor contents. If cleanup or finalization fails the abort remains FAILED_PARTIAL via the existing guard; do not swallow errors. No generic framework extraction.

- [ ] **Step 4: GREEN and covering regression.** Reuse the wrapper with:

```powershell
.\gradlew.bat test --tests '*SqlDraftRecoveryTabsTest' --tests '*SqlDraftManagerTest' --tests '*SqlEditorDraftIntegrationTest' --tests '*SqlEditorPaneLifecycleTest' --tests '*ManagedTabInstallerTest' --tests '*ManagedOpenLeaseTest' --rerun-tasks --no-daemon --console=plain
```

Report actual XML counts, no assumed count. If an exact class filter is absent, omit only that absent class after read-only verification, not another failing test. No overlapping Gradle. Root handles fresh full suite after source commit.

- [ ] **Step 5: Self-review and exact commit.** `git diff --check`, stage exactly the two modified source/test files, commit `fix: finalize aborted SQL draft recovery panes`. Append command/output/RED provenance, exact commit, named tests and remaining gates to the existing report, then return concise status. Do not merge or stage controller docs.

## Self-review

The constructor has committed a strong comment-mode listener before manager initialization. Both initializer failure and install rollback lack an installed coordinator to invoke the finalizer; both early and spec fallback aborts now use the complete callback. Existing resource cleanup still precedes finalization, the background worker is the only future-join site, all attempts remain tracked, and raw SQL/offline rules are unchanged. The boolean lifecycle assertion plus source inspection verifies finalizer invocation; it is not heap/GC measurement. Earlier mandatory abort utilities already verify aggregate failure behavior.
