# Virtual Thread Lifecycle Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Redis pane-owned platform executors with one application-level JDK 25 virtual-thread runner and make task/Tab shutdown deterministic.

**Architecture:** `FxTaskRunner` owns a named virtual-thread-per-task executor. `FxTaskScope` suppresses callbacks after close, `FxSerialTaskQueue` schedules one Redis command at a time in FIFO order, and `ContentTabPane` owns exactly-once disposers for managed tabs.

**Tech Stack:** JDK 25 virtual threads, Java concurrency primitives, JavaFX 25, JUnit 5, Gradle/jlink.

## Global Constraints

- Virtual threads are named `DataCube-io-*`.
- JavaFX controls are only mutated through the injected UI dispatcher / `Platform.runLater`.
- Closing a scope, Tab, or application prevents delayed UI callbacks.
- A Redis session executes at most one command at a time and preserves submission order.
- Application shutdown waits at most 3 seconds before interrupting remaining tasks.
- No new dependency and no relaxation of database, Redis, or migration resource limits.
- Work directly on `main`; do not push or modify `.testagent/`.

---

### Task 1: Application virtual-thread runner and task scope

**Files:**
- Create: `src/com/datacube/fx/task/FxTaskRunner.java`
- Create: `src/com/datacube/fx/task/FxTaskScope.java`
- Create: `test/com/datacube/fx/task/FxTaskRunnerTest.java`
- Create: `test/com/datacube/fx/task/FxTaskScopeTest.java`

**Interfaces:**
- Produces: `FxTaskRunner.submit(Runnable)`, `scope()`, and idempotent `close()`.
- Produces: `FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)`, `isClosed()`, and `close()`.

- [x] **Step 1: Write failing runner and scope tests**

Test that work runs on a virtual thread named `DataCube-io-*`, successful callbacks use the supplied UI dispatcher, closing a scope interrupts active work, and callbacks queued before close are dropped when eventually dispatched.

- [x] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests com.datacube.fx.task.FxTaskRunnerTest --tests com.datacube.fx.task.FxTaskScopeTest`

Expected: compilation fails because the runner and scope do not exist.

- [x] **Step 3: Implement minimal runner and scope**

Create the executor with `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("DataCube-io-", 0).factory())`. Track scope futures, ignore cancellation as a normal terminal state, and re-check `closed` inside the UI callback.

- [x] **Step 4: Verify GREEN and review assertions**

Run the two focused test classes. Confirm every callback assertion observes application behavior and all synchronization uses latches/queues rather than sleeps.

- [x] **Step 5: Commit**

Commit: `feat: 新增应用级虚拟线程任务作用域`

### Task 2: FIFO Redis queue and pane migration

**Files:**
- Create: `src/com/datacube/fx/task/FxSerialTaskQueue.java`
- Create: `test/com/datacube/fx/task/FxSerialTaskQueueTest.java`
- Modify: `src/com/datacube/fx/RedisKeyBrowserPane.java`
- Modify: `src/com/datacube/fx/RedisConsolePane.java`
- Modify: `src/com/datacube/fx/AppShell.java`

**Interfaces:**
- Produces: `FxSerialTaskQueue.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)` and `close()`.
- Consumes: one shared `FxTaskRunner`; each queued item still runs on its own virtual thread.

- [x] **Step 1: Write failing serial queue tests**

Submit multiple blocked tasks and prove FIFO completion with `maxConcurrent == 1`. Close with one active and one queued task and prove both futures are cancelled/interrupted and no UI callbacks run.

- [x] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests com.datacube.fx.task.FxSerialTaskQueueTest`

Expected: compilation fails because `FxSerialTaskQueue` does not exist.

- [x] **Step 3: Implement the queue and migrate Redis panes**

Use an explicit synchronized `ArrayDeque` over the shared runner. Replace both pane-owned single-platform-thread executors and direct `Platform.runLater` calls with the serial queue; close the queue before closing the Redis session.

- [x] **Step 4: Verify GREEN and Redis regression tests**

Run the queue test plus `RedisSessionManagerTest`, `RedisSessionTest`, and `RespClientTest`. Confirm the pane source contains no `new Thread` or local `ExecutorService`.

- [ ] **Step 5: Commit**

Commit: `feat: Redis 面板使用串行虚拟线程队列`

### Task 3: Managed tabs and application shutdown

**Files:**
- Create: `src/com/datacube/fx/ManagedTabRegistry.java`
- Create: `test/com/datacube/fx/ManagedTabRegistryTest.java`
- Modify: `src/com/datacube/fx/ContentTabPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-08-04-virtual-thread-lifecycle-foundation.md`

**Interfaces:**
- Produces: `ContentTabPane.openManagedTab(String, Node, Runnable)` and `disposeAll()`.
- Consumes: exactly-once `RedisKeyBrowserPane.close`, `RedisConsolePane.close`, and SQL history snapshot callbacks.

- [ ] **Step 1: Write failing lifecycle registry tests**

Test that a disposer runs once when both individual close and global shutdown race, that global shutdown disposes every remaining entry, and that registering after shutdown immediately disposes the resource.

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests com.datacube.fx.ManagedTabRegistryTest`

Expected: compilation fails because the lifecycle registry does not exist.

- [ ] **Step 3: Implement managed tabs and wire shutdown**

Use the registry behind `openManagedTab`; replace AppShell `setOnClosed` calls for SQL history and Redis panes. `AppShell.shutdown()` disposes managed tabs, closes the shared runner with the 3-second policy, then closes connection resources.

- [ ] **Step 4: Document and verify the project**

Document the virtual-thread/lifecycle rules. Run `./gradlew.bat clean test`, `./gradlew.bat jlink`, `git diff --check`, and `codegraph sync`.

- [ ] **Step 5: Commit**

Commit: `feat: 统一管理标签页任务与资源生命周期`
