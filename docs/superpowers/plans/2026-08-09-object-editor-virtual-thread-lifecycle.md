# Object Editor Virtual-Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将对象编辑器的初始 DDL 获取、重新加载和 DDL 执行迁移到应用级 JDK 25 虚拟线程运行器，并在标签关闭时取消任务和屏蔽 UI 回调。

**Architecture:** `ObjectEditorPane` 注入共享 `FxTaskRunner` 并持有独立 `FxTaskScope`。初始加载、确认后的重新加载及执行均通过作用域提交；现有忙碌状态继续保证同一标签不会同时发起加载和执行。`AppShell` 用受管标签注册 `pane::close`。

**Tech Stack:** Java 25、JavaFX、RichTextFX、Gradle、JUnit 5、现有 `FxTaskRunner`/`FxTaskScope`、jlink、CodeGraph

## Global Constraints

- 默认采用 G1 256MB 平衡模式，不改变现有打包参数。
- 适合的阻塞任务尽可能使用 JDK 25 虚拟线程。
- 虚拟线程不替代 JavaFX Application Thread，也不取消数据库连接资源上限。
- Windows 为主要打包目标，只使用标准 Java/JavaFX API 保留跨平台运行。
- 保留执行、重新加载确认、复制、逐条结果统计和错误提示行为。
- 本边界不迁移序列设计器或表设计器；不修改 `.testagent/`。

---

### Task 1: 对象编辑器受管后台任务生命周期

**Files:**
- Create: `test/com/datacube/fx/ObjectEditorPaneLifecycleTest.java`
- Modify: `src/com/datacube/fx/ObjectEditorPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-09-object-editor-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()`、`FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)` 和 `ContentTabPane.openManagedTab(String, Node, Runnable)`
- Produces: `ObjectEditorPane(String, Callable<String>, Function<String, List<ScriptOutcome>>, FxTaskRunner)` 与幂等的 `close()`

- [x] **Step 1: 写入失败的生命周期契约测试**

```java
package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectEditorPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(ObjectEditorPane.class));
        assertNotNull(ObjectEditorPane.class.getConstructor(
                String.class, Callable.class, Function.class, FxTaskRunner.class));
    }
}
```

- [x] **Step 2: 运行聚焦测试并确认按预期失败**

Run: `./gradlew.bat test --tests com.datacube.fx.ObjectEditorPaneLifecycleTest`

Expected: FAIL，因为 `ObjectEditorPane` 尚未实现 `AutoCloseable`，且不存在注入 `FxTaskRunner` 的构造器。

- [x] **Step 3: 实现最小的对象编辑器任务作用域**

在构造器中创建作用域并实现关闭：

```java
private final FxTaskScope tasks;

public ObjectEditorPane(String title, Callable<String> fetch,
                        Function<String, List<ScriptOutcome>> executor,
                        FxTaskRunner runner) {
    this.title = title;
    this.fetch = fetch;
    this.executor = executor;
    this.tasks = runner.scope();
    build();
    load();
}

@Override
public void close() {
    tasks.close();
}
```

将加载任务改为：

```java
tasks.submit(fetch, ddl -> {
    setBusy(false);
    codeArea.replaceText(ddl == null ? "" : ddl);
    statusLabel.setText("就绪");
    statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
}, failure -> {
    setBusy(false);
    codeArea.replaceText("-- 获取 DDL 失败: " + message(failure));
    statusLabel.setText("错误");
    statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
});
```

将执行任务改为 `tasks.submit(() -> executor.apply(ddl), success, failure)`；成功继续调用 `renderOutcomes`，失败继续更新状态区。失败消息为空时回退到异常类名。

- [x] **Step 4: 将对象编辑标签纳入受管生命周期**

```java
ObjectEditorPane pane = new ObjectEditorPane(
        "编辑: " + name, ddlFetch(connId, node), executor, tasks);
contentTabs.openManagedTab("编辑: " + name, pane.getNode(), pane::close);
```

- [x] **Step 5: 运行聚焦测试并检查遗留线程创建**

Run: `./gradlew.bat test --tests com.datacube.fx.ObjectEditorPaneLifecycleTest`

Expected: PASS。

Run: `rg -n "new Thread|Platform\\.runLater" src/com/datacube/fx/ObjectEditorPane.java`

Expected: 无匹配。

- [x] **Step 6: 更新后台任务生命周期文档**

在 `README.md` 补充对象编辑器加载与执行使用标签级任务作用域，关闭标签或应用时取消未完成任务。

- [x] **Step 7: 完成全量验证**

Run: `./gradlew.bat clean test`

Expected: BUILD SUCCESSFUL，所有单元测试通过；仅显式真实 Redis 集成测试可保持跳过。

Run: `./gradlew.bat jlink`

Expected: BUILD SUCCESSFUL。

Run: `codegraph sync`

Expected: CodeGraph 同步成功。

Run: `git diff --check`

Expected: 无空白错误。

- [x] **Step 8: 仅提交本边界文件**

```powershell
git add -- README.md src/com/datacube/fx/ObjectEditorPane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/ObjectEditorPaneLifecycleTest.java docs/superpowers/plans/2026-08-09-object-editor-virtual-thread-lifecycle.md
git commit -m "feat: 对象编辑器使用受管虚拟线程任务"
```

提交前确认 `.testagent/` 仍为未跟踪且未暂存。
