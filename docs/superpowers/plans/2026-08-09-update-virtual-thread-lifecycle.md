# Update Virtual-Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将自动/手动更新检查、下载和应用准备迁移到应用级 JDK 25 虚拟线程运行器，并在应用关闭后屏蔽未开始操作和已排队 UI 回调。

**Architecture:** update 包新增不依赖 JavaFX 的 `UpdateTaskDispatcher`，接收通用 `Executor` 和 `Consumer<Runnable>`，统一后台执行、回调分发和关闭门控。`AppShell` 注入 `FxTaskRunner.submit` 与 `Platform.runLater`；`UpdateService` 实现 `AutoCloseable` 并通过调度器执行所有网络/文件 I/O，`UpdateUI` 的回调因此直接运行在 JavaFX 线程。

**Tech Stack:** Java 25、JavaFX、Java HttpClient、Gradle、JUnit 5、现有 `FxTaskRunner`、jlink、CodeGraph

## Global Constraints

- 默认采用 G1 256MB 平衡模式，不改变现有打包参数。
- 适合的阻塞网络和文件任务尽可能使用 JDK 25 虚拟线程。
- update 包保持零 JavaFX 依赖，通过注入的回调分发器切换线程。
- 应用退出时先关闭更新服务，再关闭全局任务运行器；关闭后不触发 UI 回调。
- 不提高更新检查或下载并发度，不改变 GitHub Release、安装版、便携版和手动下载页决策。
- Windows 为主要打包目标，并保留现有未知安装形态的网页回退行为。
- 不修改 `.testagent/`，不进行真实更新下载。

---

### Task 1: 更新任务调度器与服务生命周期

**Files:**
- Create: `src/com/datacube/update/UpdateTaskDispatcher.java`
- Create: `test/com/datacube/update/UpdateTaskDispatcherTest.java`
- Create: `test/com/datacube/update/UpdateServiceLifecycleTest.java`
- Modify: `src/com/datacube/update/UpdateService.java`
- Modify: `src/com/datacube/fx/UpdateUI.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-09-update-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `Executor.execute(Runnable)` 和 `Consumer<Runnable>` 回调分发器
- Produces: `UpdateService(Executor, Consumer<Runnable>)`、`UpdateService.close()` 和包内 `UpdateTaskDispatcher`

- [x] **Step 1: 写入失败的生命周期与关闭竞态测试**

`UpdateServiceLifecycleTest.isAutoCloseableAndRequiresInjectedExecutors` 用反射断言服务实现 `AutoCloseable`，并存在 `(Executor, Consumer<Runnable>)` 构造器。

`UpdateTaskDispatcherTest.closePreventsQueuedOperationFromStarting` 通过反射创建尚不存在的调度器，把后台任务放入列表；关闭后执行已排队包装任务，断言实际操作未运行。

`UpdateTaskDispatcherTest.closeSuppressesQueuedCallback` 把 UI 回调放入列表；关闭后执行已排队包装回调，断言用户回调未运行。

两个测试都先用未关闭的调度器执行同类包装任务并断言操作确实运行，再创建关闭场景；该正向对照防止“永远不执行”的错误实现通过测试。

```java
package com.datacube.update;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpdateTaskDispatcherTest {
    @Test
    void closePreventsQueuedOperationFromStarting() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean ran = new AtomicBoolean();
        Object dispatcher = newDispatcher(command -> queued.add(command), ignored -> {});
        invoke(dispatcher, "execute", () -> ran.set(true));
        assertEquals(1, queued.size());
        ((AutoCloseable) dispatcher).close();
        queued.getFirst().run();
        assertFalse(ran.get());
    }

    @Test
    void closeSuppressesQueuedCallback() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean ran = new AtomicBoolean();
        Object dispatcher = newDispatcher(Runnable::run, command -> queued.add(command));
        invoke(dispatcher, "dispatch", () -> ran.set(true));
        assertEquals(1, queued.size());
        ((AutoCloseable) dispatcher).close();
        queued.getFirst().run();
        assertFalse(ran.get());
    }

    private static Object newDispatcher(Executor background, Consumer<Runnable> callbacks)
            throws Exception {
        Class<?> type = Class.forName("com.datacube.update.UpdateTaskDispatcher");
        var constructor = type.getDeclaredConstructor(Executor.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(background, callbacks);
    }

    private static void invoke(Object target, String method, Runnable action) throws Exception {
        var declared = target.getClass().getDeclaredMethod(method, Runnable.class);
        declared.setAccessible(true);
        declared.invoke(target, action);
    }
}
```

```java
package com.datacube.update;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceLifecycleTest {
    @Test
    void isAutoCloseableAndRequiresInjectedExecutors() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(UpdateService.class));
        assertNotNull(UpdateService.class.getConstructor(Executor.class, Consumer.class));
    }
}
```

- [x] **Step 2: 运行聚焦测试并确认按预期失败**

Run: `./gradlew.bat test --tests com.datacube.update.UpdateServiceLifecycleTest --tests com.datacube.update.UpdateTaskDispatcherTest`

Expected: FAIL，因为 `UpdateService` 尚未实现新生命周期契约，且 `UpdateTaskDispatcher` 不存在。

- [x] **Step 3: 实现通用更新任务调度器**

```java
final class UpdateTaskDispatcher implements AutoCloseable {
    private final Executor background;
    private final Consumer<Runnable> callbacks;
    private final AtomicBoolean closed = new AtomicBoolean();

    UpdateTaskDispatcher(Executor background, Consumer<Runnable> callbacks) {
        this.background = Objects.requireNonNull(background, "background");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    void execute(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) return;
        try {
            background.execute(() -> {
                if (!closed.get()) operation.run();
            });
        } catch (RejectedExecutionException rejected) {
            if (!closed.get()) throw rejected;
        }
    }

    void dispatch(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) return;
        callbacks.accept(() -> {
            if (!closed.get()) callback.run();
        });
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
```

- [x] **Step 4: 迁移 UpdateService**

`UpdateService` 实现 `AutoCloseable`，构造器创建 `UpdateTaskDispatcher`。三个公开异步入口都用 `tasks.execute`，检查结果、下载进度、就绪、网页回退和错误都用 `tasks.dispatch`。移除全部 `new Thread`，保留原有异常分支与静默启动检查语义。

```java
public UpdateService(Executor background, Consumer<Runnable> callbacks) {
    this.tasks = new UpdateTaskDispatcher(background, callbacks);
}

@Override
public void close() {
    tasks.close();
}
```

- [x] **Step 5: 简化 UpdateUI 并接入 AppShell**

`UpdateUI` 删除服务回调内层的 `Platform.runLater`；保留 `Platform.exit()`。`AppShell` 使用：

```java
private final LazyValue<UpdateService> updateService =
        new LazyValue<>(() -> new UpdateService(tasks::submit, Platform::runLater));
```

启动静默检查直接调用 `UpdateUI.promptUpdate`，因为服务保证 UI 分发。关闭顺序增加 `updateService.ifInitialized(UpdateService::close)`，并确保它发生在 `tasks.close()` 之前。

- [x] **Step 6: 运行聚焦测试并检查遗留线程**

Run: `./gradlew.bat test --tests com.datacube.update.UpdateServiceLifecycleTest --tests com.datacube.update.UpdateTaskDispatcherTest`

Expected: 3 tests PASS。

Run: `rg -n "new Thread|Platform\\.runLater" src/com/datacube/update src/com/datacube/fx/UpdateUI.java`

Expected: update 包无匹配；`UpdateUI` 无 `Platform.runLater`，仅保留 `Platform.exit()`。

- [x] **Step 7: 更新文档并完成全量验证**

在 `README.md` 补充更新检查/下载使用应用级虚拟线程、应用关闭后屏蔽回调。

Run: `./gradlew.bat clean test`

Expected: BUILD SUCCESSFUL；仅显式真实 Redis 集成测试可保持跳过。

Run: `./gradlew.bat jlink`

Expected: BUILD SUCCESSFUL。

Run: `codegraph sync`

Expected: CodeGraph 同步成功。

Run: `git diff --check`

Expected: 无空白错误。

- [x] **Step 8: 仅提交本边界文件**

```powershell
git add -- README.md src/com/datacube/update/UpdateTaskDispatcher.java src/com/datacube/update/UpdateService.java src/com/datacube/fx/UpdateUI.java src/com/datacube/fx/AppShell.java test/com/datacube/update/UpdateTaskDispatcherTest.java test/com/datacube/update/UpdateServiceLifecycleTest.java docs/superpowers/plans/2026-08-09-update-virtual-thread-lifecycle.md
git commit -m "feat: 更新任务使用受管虚拟线程"
```

提交前确认 `.testagent/` 仍为未跟踪且未暂存。
