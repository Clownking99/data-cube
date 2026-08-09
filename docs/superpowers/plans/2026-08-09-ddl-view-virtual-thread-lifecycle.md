# DDL View Virtual-Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 DDL 查看器的数据库读取迁移到应用级 JDK 25 虚拟线程运行器，并在关闭标签或应用时取消任务和屏蔽延迟 UI 回调。

**Architecture:** `DdlViewPane` 注入共享 `FxTaskRunner` 并持有独立 `FxTaskScope`，通过作用域执行传入的 `Callable<String>` 和调度 JavaFX UI 更新。`AppShell` 使用 `ContentTabPane.openManagedTab` 注册 `pane::close`，复用现有受管标签生命周期。

**Tech Stack:** Java 25、JavaFX、RichTextFX、Gradle、JUnit 5、现有 `FxTaskRunner`/`FxTaskScope`、jlink、CodeGraph

## Global Constraints

- 默认采用 G1 256MB 平衡模式，不改变现有打包参数。
- 适合的阻塞任务尽可能使用 JDK 25 虚拟线程。
- 虚拟线程不替代 JavaFX Application Thread，也不取消数据库连接资源上限。
- Windows 为主要打包目标，只使用标准 Java/JavaFX API 保留跨平台运行。
- 保留 DDL 加载、复制、成功状态和失败提示的现有交互。
- 本边界不迁移对象编辑器、序列设计器或表设计器；不修改 `.testagent/`。

---

### Task 1: DDL 查看器受管后台任务生命周期

**Files:**
- Create: `test/com/datacube/fx/DdlViewPaneLifecycleTest.java`
- Modify: `src/com/datacube/fx/DdlViewPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-09-ddl-view-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()`、`FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)` 和 `ContentTabPane.openManagedTab(String, Node, Runnable)`
- Produces: `DdlViewPane(String title, Callable<String> fetch, FxTaskRunner runner)` 与幂等的 `DdlViewPane.close()`

- [x] **Step 1: 写入失败的生命周期契约测试**

```java
package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlViewPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(DdlViewPane.class));
        assertNotNull(DdlViewPane.class.getConstructor(
                String.class, Callable.class, FxTaskRunner.class));
    }
}
```

- [x] **Step 2: 运行聚焦测试并确认按预期失败**

Run: `./gradlew.bat test --tests com.datacube.fx.DdlViewPaneLifecycleTest`

Expected: FAIL，因为 `DdlViewPane` 尚未实现 `AutoCloseable`，且不存在注入 `FxTaskRunner` 的构造器。

- [x] **Step 3: 实现最小的 DDL 任务作用域**

在 `DdlViewPane` 中实现 `AutoCloseable`，注入运行器并创建作用域：

```java
private final FxTaskScope tasks;

public DdlViewPane(String title, Callable<String> fetch, FxTaskRunner runner) {
    this.tasks = runner.scope();
    build(title);
    load(fetch);
}

@Override
public void close() {
    tasks.close();
}
```

用作用域替换 `DdlView-Worker` 平台线程和直接 `Platform.runLater`：

```java
private void load(Callable<String> fetch) {
    tasks.submit(fetch, ddl -> {
        codeArea.replaceText(ddl == null ? "" : ddl);
        statusLabel.setText("就绪");
        statusLabel.setStyle("-fx-text-fill: -status-ok; -fx-font-size: 12px;");
    }, failure -> {
        codeArea.replaceText("-- 获取 DDL 失败: " + message(failure));
        statusLabel.setText("错误");
        statusLabel.setStyle("-fx-text-fill: -status-error; -fx-font-size: 12px;");
    });
}
```

失败消息为空时以异常类名回退，避免把异常误判为成功。

- [x] **Step 4: 将 DDL 查看标签纳入受管生命周期**

在 `AppShell.TreeActions.openDdl` 传入共享运行器并注册释放器：

```java
DdlViewPane pane = new DdlViewPane("DDL: " + name, ddlFetch(connId, node), tasks);
contentTabs.openManagedTab("DDL: " + name, pane.getNode(), pane::close);
```

- [x] **Step 5: 运行聚焦测试并检查遗留线程创建**

Run: `./gradlew.bat test --tests com.datacube.fx.DdlViewPaneLifecycleTest`

Expected: PASS。

Run: `rg -n "new Thread|Platform\\.runLater" src/com/datacube/fx/DdlViewPane.java`

Expected: 无匹配。

- [x] **Step 6: 更新后台任务生命周期文档**

在 `README.md` 补充 DDL 查看器使用标签级任务作用域，关闭标签或应用时取消尚未完成的 DDL 获取任务。

- [x] **Step 7: 完成全量验证**

Run: `./gradlew.bat clean test`

Expected: BUILD SUCCESSFUL，所有单元测试通过；仅显式的真实 Redis 集成测试可保持跳过。

Run: `./gradlew.bat jlink`

Expected: BUILD SUCCESSFUL。

Run: `codegraph sync`

Expected: CodeGraph 索引同步成功。

Run: `git diff --check`

Expected: 无空白错误。

- [x] **Step 8: 仅提交本边界文件**

```powershell
git add -- README.md src/com/datacube/fx/DdlViewPane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/DdlViewPaneLifecycleTest.java docs/superpowers/plans/2026-08-09-ddl-view-virtual-thread-lifecycle.md
git commit -m "feat: DDL 查看器使用受管虚拟线程任务"
```

提交前确认 `.testagent/` 仍为未跟踪且未暂存。
