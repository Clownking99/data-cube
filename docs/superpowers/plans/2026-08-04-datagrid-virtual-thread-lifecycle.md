# DataGrid Virtual Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move DataGrid page loading, row commits, and row deletion from ad-hoc platform threads to the application JDK 25 virtual-thread runner with deterministic Tab cancellation.

**Architecture:** `DataGridPane` becomes an `AutoCloseable` managed-tab resource and owns one `FxTaskScope` created from the shared application `FxTaskRunner`. Each existing blocking service call is submitted to that scope; success and failure callbacks stay on the JavaFX dispatcher, and closing the Tab cancels active work and suppresses delayed callbacks.

**Tech Stack:** JDK 25 virtual threads, JavaFX 25, `FxTaskRunner`/`FxTaskScope`, JUnit 5, Gradle/jlink.

## Global Constraints

- Work directly on `main`; do not push or modify `.testagent/`.
- Preserve the current 200-row page size, editing semantics, confirmation dialogs, and connection limits.
- JavaFX controls are only read or mutated on the JavaFX Application Thread.
- Blocking `DataBrowseService` and `DataEditService` calls run on `DataCube-io-*` virtual threads.
- Closing the DataGrid Tab cancels outstanding work and prevents callbacks from touching disposed controls.
- Use only standard JDK/JavaFX APIs so Windows packaging and cross-platform execution share one implementation.

---

### Task 1: DataGrid scoped virtual-thread migration

**Files:**
- Create: `test/com/datacube/fx/DataGridPaneLifecycleTest.java`
- Modify: `src/com/datacube/fx/DataGridPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-04-datagrid-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()` and `FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)`.
- Produces: `DataGridPane(..., boolean readOnly, FxTaskRunner runner) implements AutoCloseable` and idempotent `close()`.

- [x] **Step 1: Write the failing lifecycle contract test**

Create a reflection-based test that does not initialize the JavaFX toolkit:

```java
@Test
void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
    assertTrue(AutoCloseable.class.isAssignableFrom(DataGridPane.class));
    assertNotNull(DataGridPane.class.getConstructor(
            DataBrowseService.class, DataEditService.class, String.class, String.class,
            TableRef.class, AppSettings.class, boolean.class, FxTaskRunner.class));
}
```

- [x] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.fx.DataGridPaneLifecycleTest
```

Expected: the test fails because `DataGridPane` is not `AutoCloseable` and does not expose the shared-runner constructor.

- [x] **Step 3: Implement scoped DataGrid tasks**

Add `private final FxTaskScope tasks;`, accept `FxTaskRunner runner`, initialize `tasks = runner.scope()`, and implement:

```java
@Override
public void close() {
    tasks.close();
}
```

Replace each `new Thread`/`Platform.runLater` pair in `load`, `commitRow`, and `deleteSelectedRows` with `tasks.submit`. Use small immutable operation results (`LoadResult`, `DeleteResult`) where callbacks need more than one value. Failure callbacks restore `busy`/controls and retain the existing status text; interruption caused by Tab close produces no callback.

- [x] **Step 4: Wire the managed Tab**

Construct the pane with the application `tasks` runner and replace the plain tab with:

```java
DataGridPane pane = new DataGridPane(
        browseSvc, editSvc, connId, connName, table, settings, readOnly, tasks);
contentTabs.openManagedTab(prefix + table.name(), pane.getNode(), pane::close);
```

- [x] **Step 5: Verify focused behavior and source invariants**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.fx.DataGridPaneLifecycleTest `
  --tests com.datacube.fx.task.FxTaskScopeTest
rg -n "new Thread|Platform\.runLater|ExecutorService|Executors\." src/com/datacube/fx/DataGridPane.java
```

Expected: tests pass and `rg` returns no matches.

- [x] **Step 6: Document and verify the project**

Add DataGrid page/commit/delete to the README virtual-thread scope. Run:

```powershell
.\gradlew.bat clean test
.\gradlew.bat jlink
git diff --check
codegraph sync
```

Expected: all commands exit 0; the live Redis test may remain skipped unless its explicit integration flag is supplied.

- [x] **Step 7: Commit**

```powershell
git add -- README.md docs/superpowers/plans/2026-08-04-datagrid-virtual-thread-lifecycle.md `
  src/com/datacube/fx/AppShell.java src/com/datacube/fx/DataGridPane.java `
  test/com/datacube/fx/DataGridPaneLifecycleTest.java
git commit -m "feat: 数据网格使用受管虚拟线程任务"
```
