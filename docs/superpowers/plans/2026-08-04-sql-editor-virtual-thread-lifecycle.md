# SQL Editor Virtual Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move SQL execution, explain, result export, table navigation, and metadata loading onto application-managed JDK 25 virtual threads and release all editor listeners/tasks when its Tab closes.

**Architecture:** `SqlEditorPane` becomes an `AutoCloseable` managed-tab resource. One `FxTaskScope` owns independent editor I/O, while one `FxSerialTaskQueue` preserves the existing single-thread ordering for metadata prewarm and column lookup; both consume the shared `FxTaskRunner` and suppress callbacks after close.

**Tech Stack:** JDK 25 virtual threads, JavaFX 25, RichTextFX, `FxTaskScope`, `FxSerialTaskQueue`, JUnit 5, Gradle/jlink.

## Global Constraints

- Work directly on `main`; do not push or modify `.testagent/`.
- Preserve SQL history, script error decisions, execution/explain result rendering, export formats, and completion caches.
- Keep metadata operations serial; do not increase JDBC connection or statement concurrency.
- Keep JavaFX control access on the JavaFX Application Thread.
- Closing the editor snapshots history, unregisters property listeners, cancels tasks, and suppresses delayed callbacks.
- Standard JDK/JavaFX APIs only; Windows remains primary while cross-platform execution is retained.

---

### Task 1: SQL editor scoped and serial virtual-thread tasks

**Files:**
- Create: `test/com/datacube/fx/SqlEditorPaneLifecycleTest.java`
- Modify: `src/com/datacube/fx/SqlEditorPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-04-sql-editor-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: shared `FxTaskRunner`, `FxTaskScope`, and `FxSerialTaskQueue`.
- Produces: `SqlEditorPane(..., ShortcutSettings shortcuts, FxTaskRunner runner) implements AutoCloseable` and idempotent `close()`.

- [x] **Step 1: Write and verify the failing lifecycle contract test**

Create a reflection test named `isAutoCloseableAndRequiresSharedTaskRunner` that asserts `AutoCloseable` and the constructor ending in `FxTaskRunner`. Run:

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorPaneLifecycleTest
```

Expected: failure because the lifecycle contract and constructor do not exist.

- [x] **Step 2: Add editor lifecycle ownership**

Add `FxTaskScope tasks = runner.scope()` and `FxSerialTaskQueue metadataTasks = new FxSerialTaskQueue(runner)`. Store the settings comment-mode listener and unbound-session connection listener as fields. Implement `close()` to snapshot history, remove both listeners, hide completion UI, close the metadata queue, then close the task scope.

- [x] **Step 3: Migrate SQL execution, explain, navigation, and export**

Replace the four `new Thread` blocks with `tasks.submit`:

- SQL execution returns `ExecutionResult(outcomes, error, elapsedMillis)` so existing status behavior is preserved.
- Explain returns `ExplainResult(result, error)` and retains current result-kind rendering.
- Ctrl+click returns a boolean table-exists result; lookup failure remains silent.
- Result export performs file I/O in the operation, removes a partial file on failure, and updates status only through callbacks.

Keep the script-error modal bridge as `Platform.runLater` plus `CountDownLatch`, but check `tasks.isClosed()` before queueing and again inside the callback so a closed editor returns `ABORT` without opening a delayed dialog.

- [x] **Step 4: Migrate metadata while preserving ordering**

Replace `metaPool.submit` in prewarm and column lookup with `metadataTasks.submit`. Prewarm returns collected names and removes the retry marker on failure. Column lookup returns the column list, always clears `columnLoading` on success/failure, stores non-empty results, and refreshes completion through the UI callback.

- [x] **Step 5: Wire managed editor Tabs**

Pass the application runner from both AppShell editor creation paths and use `pane::close` as the managed disposer instead of calling `snapshotToHistory` directly.

- [x] **Step 6: Verify focused behavior and source invariants**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorPaneLifecycleTest `
  --tests com.datacube.fx.task.FxTaskScopeTest `
  --tests com.datacube.fx.task.FxSerialTaskQueueTest
rg -n "new Thread|ExecutorService|Executors\." src/com/datacube/fx/SqlEditorPane.java
```

Expected: tests pass and the source search returns no matches. `Platform.runLater` remains only for the synchronous script-error decision bridge.

- [x] **Step 7: Document, verify, and commit**

Update README, then run `clean test`, `jlink`, `git diff --check`, and `codegraph sync`. Commit only the listed files with:

```powershell
git commit -m "feat: SQL 编辑器使用受管虚拟线程任务"
```
