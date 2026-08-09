# Migration Virtual Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run Oracle/PostgreSQL migration operations on managed JDK 25 virtual threads and make cancellation close nested JDBC work without allowing delayed UI callbacks or later phases to restart.

**Architecture:** `AppShell` injects its existing `FxTaskRunner` into `MigrationPane`; the pane creates one `FxTaskScope` and gives it to `MainController`. Each user operation creates one shared `MigrationCancellation` that race-safely owns controller and per-table JDBC connections across `OracleExporter`, `PgImporter`, and `PgVerifier`. `MigrationTaskCoordinator` cancels and joins nested virtual-thread work while existing user-configured `Semaphore` limits remain unchanged.

**Tech Stack:** JDK 25 virtual threads, Java concurrency primitives, JavaFX 25, JUnit 5, Gradle/jlink.

## Global Constraints

- Work directly on `main`; do not push or modify `.testagent/`.
- Use the shared `FxTaskRunner`; do not create a migration-owned executor or platform thread.
- Keep JavaFX control mutation on the JavaFX Application Thread.
- Closing the migration pane cancels active scope tasks and suppresses delayed success/failure callbacks.
- Preserve `OracleExporter` and `PgImporter` cancellation flags, retry behavior, user concurrency setting, and `Semaphore` limits.
- Keep Windows as the primary packaged platform while avoiding platform-specific migration APIs.

---

### Task 1: Inject and close the migration task scope

**Files:**
- Create: `test/com/datacube/fx/MigrationPaneLifecycleTest.java`
- Modify: `test/com/datacube/fx/task/FxTaskScopeTest.java`
- Modify: `src/com/datacube/fx/task/FxTaskScope.java`
- Modify: `src/com/datacube/fx/FxLogger.java`
- Modify: `src/com/datacube/fx/MigrationPane.java`
- Modify: `src/com/datacube/fx/MainController.java`
- Modify: `src/com/datacube/fx/AppShell.java`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()`, `FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)`, and lifecycle-gated `FxTaskScope.dispatch(Runnable)`.
- Produces: `MigrationPane(FxTaskRunner)` and a package-private `MainController(FxTaskScope)` constructor.

- [x] **Step 1: Write the failing lifecycle test**

Add tests named `requiresSharedTaskRunner`, `shutdownClosesInjectedTaskScope`, and `directDispatchDropsQueuedAndPostCloseCallbacks`. The first requires a public `MigrationPane(FxTaskRunner)` constructor; the second creates an `FxTaskScope`, injects it into `MainController`, calls `shutdown()`, and asserts `scope.isClosed()`; the third proves migration log callbacks queued before close and submitted after close are both suppressed.

- [x] **Step 2: Run the focused test and verify RED**

Run: `./gradlew.bat test --tests com.datacube.fx.MigrationPaneLifecycleTest`

Expected: compilation fails because the required constructors do not exist.

- [x] **Step 3: Implement scope injection and lifecycle ownership**

Change `MigrationPane` construction to:

```java
public MigrationPane(FxTaskRunner runner) {
    this.controller = new MainController(runner.scope());
    this.content = controller.createMigrationContent();
}
```

Store the injected scope in `MainController`, replace `new Thread(...).start()` and the nested `Platform.runLater` completion with `tasks.submit(...)`, route `FxLogger` UI work through public lifecycle-gated `tasks.dispatch(...)`, and close the scope before JDBC/log resources in `shutdown()`. Wire the lazy pane in `AppShell` with `new MigrationPane(tasks)`.

- [x] **Step 4: Run the focused test and verify GREEN**

Run: `./gradlew.bat test --tests com.datacube.fx.MigrationPaneLifecycleTest --tests com.datacube.fx.task.FxTaskScopeTest --tests com.datacube.fx.task.FxTaskRunnerTest`

Expected: all focused tests pass; lifecycle assertions use real scope state and no timing sleeps.

- [x] **Step 5: Confirm the migration UI no longer creates a platform worker**

Run: `rg -n "new Thread|DataCube-Worker" src/com/datacube/fx/MainController.java src/com/datacube/fx/MigrationPane.java`

Expected: no matches. `Timeline` remains JavaFX-managed and is started only from the scope's UI callback; the pre-existing alert helper may still dispatch alerts with `Platform.runLater`.

### Task 2: Make nested migration cancellation deterministic

**Files:**
- Create: `src/com/datacube/migration/MigrationCancellation.java`
- Create: `src/com/datacube/migration/MigrationTaskCoordinator.java`
- Create: `test/com/datacube/migration/MigrationCancellationTest.java`
- Create: `test/com/datacube/migration/MigrationTaskCoordinatorTest.java`
- Modify: `src/com/datacube/migration/OracleExporter.java`
- Modify: `src/com/datacube/migration/PgImporter.java`
- Modify: `src/com/datacube/migration/PgVerifier.java`
- Modify: `src/com/datacube/fx/MainController.java`
- Modify: `src/com/datacube/DataCubeFx.java`

**Interfaces:**
- Produces: `MigrationCancellation.register/release/checkCancelled/cancel` and non-resetting phase cancellation.
- Produces: `MigrationTaskCoordinator.awaitAll(...)` with child cancellation, `shutdownNow`, bounded termination, and interrupt preservation.

- [x] **Step 1: Write cancellation and child-task RED tests**

Test race-safe resource closure, late resource rejection, normal child completion, interrupted wait cancellation, preserved interrupt state, executor termination, and fatal-error propagation outside ordinary UI failure callbacks.

- [x] **Step 2: Implement shared cancellation and bounded fan-out shutdown**

Pass one cancellation object from `MainController` into exporter/importer/verifier; register every root and per-table JDBC connection. Remove automatic cancellation resets from phase methods, make semaphore acquisition interruptible, add row/batch checkpoints, and replace swallowed `Future.get` exceptions with the coordinator.

- [x] **Step 3: Fix UI and window-close thread confinement**

Read form controls and initialize modules before submission on the JavaFX thread. Check cancellation between every “一键全部” phase and call `AppShell.shutdown()` synchronously from the confirmed close-request handler.

- [x] **Step 4: Verify focused and workspace tests**

Run the migration cancellation/coordinator, migration pane, task scope, and runner tests, then `./gradlew.bat test`.

Expected: all commands exit 0 and no cancellation is reported as an ordinary operation error.

### Task 3: Document and verify the phase

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-09-migration-virtual-thread-lifecycle.md`

**Interfaces:**
- Produces: documented migration lifecycle and verification evidence.

- [x] **Step 1: Document the migration execution boundary**

State that top-level migration JDBC work uses the application virtual-thread runner, pane shutdown cancels its scope, and table-level export/import concurrency remains bounded by the existing user setting and semaphore.

- [x] **Step 2: Run full verification**

Run: `./gradlew.bat clean test`, then `./gradlew.bat jlink`, `git diff --check`, and `codegraph sync`.

Expected: Gradle commands exit 0, XML test results contain no failures/errors, the Windows runtime image links successfully, the diff check is clean, and CodeGraph is current.

- [x] **Step 3: Commit only this phase**

Stage the plan, lifecycle test, migration UI/controller wiring, AppShell wiring, and README. Confirm `.testagent/` remains untracked, then commit with `feat: 迁移任务使用受管虚拟线程`.
