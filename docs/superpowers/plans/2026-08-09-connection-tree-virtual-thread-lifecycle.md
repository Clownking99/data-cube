# Connection Tree Virtual-Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将连接树首次展开触发的关系库元数据和 Redis INFO 阻塞 I/O 迁移到应用级 JDK 25 虚拟线程运行器，并在应用退出时取消未完成任务和屏蔽延迟 UI 回调。

**Architecture:** `ConnectionTreePane` 从 `AppShell` 注入共享 `FxTaskRunner`，自行持有一个 `FxTaskScope`，继续并发执行互不相关的懒加载节点。成功和失败仍更新原有 `TreeItem`，但统一由作用域调度到 JavaFX Application Thread；`close()` 关闭作用域，`AppShell.shutdown()` 在关闭全局运行器及连接前调用它。

**Tech Stack:** Java 25、JavaFX、Gradle、JUnit 5、现有 `FxTaskRunner`/`FxTaskScope`、jlink、CodeGraph

## Global Constraints

- 默认采用 G1 256MB 平衡模式，不改变现有打包参数。
- 适合的阻塞任务尽可能使用 JDK 25 虚拟线程。
- 虚拟线程不替代 JavaFX Application Thread，也不取消数据库、连接和迁移的资源并发上限。
- Windows 为主要打包目标，只使用标准 Java/JavaFX API 保留跨平台运行。
- UI 拆分采用渐进抽取，保留现有交互、懒加载、错误提示和业务行为。
- 每个边界单独测试和提交；本任务不修改 `.testagent/`，不使用真实 Redis 凭据。

---

### Task 1: 连接树受管后台任务生命周期

**Files:**
- Create: `test/com/datacube/fx/ConnectionTreePaneLifecycleTest.java`
- Modify: `src/com/datacube/fx/ConnectionTreePane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-09-connection-tree-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()` 和 `FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)`
- Produces: `ConnectionTreePane(..., Actions actions, FxTaskRunner runner)` 和幂等的 `ConnectionTreePane.close()`

- [x] **Step 1: 写入失败的生命周期契约测试**

```java
package com.datacube.fx;

import com.datacube.config.ConnectionStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.ObjectTreeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTreePaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(ConnectionTreePane.class));
        assertNotNull(ConnectionTreePane.class.getConstructor(
                ConnectionStore.class, ConnectionManager.class, ObjectTreeService.class,
                SessionContext.class, ConnectionTreePane.Actions.class, FxTaskRunner.class));
    }
}
```

- [x] **Step 2: 运行聚焦测试并确认按预期失败**

Run: `./gradlew.bat test --tests com.datacube.fx.ConnectionTreePaneLifecycleTest`

Expected: FAIL，因为 `ConnectionTreePane` 尚未实现 `AutoCloseable`，且不存在接收 `FxTaskRunner` 的构造器。

- [x] **Step 3: 实现最小的面板任务作用域**

在 `ConnectionTreePane` 中导入 `FxTaskRunner`、`FxTaskScope`，实现 `AutoCloseable`，由构造器创建作用域：

```java
private final FxTaskScope tasks;

public ConnectionTreePane(ConnectionStore store, ConnectionManager connMgr,
                          ObjectTreeService treeSvc, SessionContext session, Actions actions,
                          FxTaskRunner runner) {
    this.store = store;
    this.connMgr = connMgr;
    this.treeSvc = treeSvc;
    this.session = session;
    this.actions = actions;
    this.tasks = runner.scope();
    build();
}
```

用作用域替换 `loadInto` 中手工创建的 `Tree-Loader` 平台线程与直接 `Platform.runLater`：

```java
private void loadInto(TreeItem<NodeData> item, Callable<List<TreeItem<NodeData>>> loader) {
    tasks.submit(loader, children -> {
        item.getChildren().setAll(children);
    }, failure -> {
        item.getChildren().setAll(new TreeItem<>(
                new NodeData(item.getValue().kind, "错误: " + message(failure),
                        null, null, null, null)));
    });
}

private static String message(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
}

@Override
public void close() {
    tasks.close();
}
```

- [x] **Step 4: 让应用壳拥有并关闭连接树**

在 `AppShell` 增加字段并传入共享运行器：

```java
private ConnectionTreePane connectionTree;

private void build() {
    connectionTree = new ConnectionTreePane(store, connMgr, treeSvc, session, treeActions, tasks);
    // 保留现有布局和交互代码，统一引用 connectionTree。
}
```

在 `shutdown()` 中按“受管标签 -> 连接树 -> 迁移任务 -> 全局运行器 -> 连接资源”的顺序释放：

```java
contentTabs.disposeAll();
connectionTree.close();
migrationPane.ifInitialized(MigrationPane::shutdown);
tasks.close();
connMgr.closeAll();
```

- [x] **Step 5: 运行聚焦测试并检查遗留线程创建**

Run: `./gradlew.bat test --tests com.datacube.fx.ConnectionTreePaneLifecycleTest`

Expected: PASS。

Run: `rg -n "new Thread|Platform\\.runLater" src/com/datacube/fx/ConnectionTreePane.java`

Expected: 无匹配；连接树后台加载只通过 `FxTaskScope` 调度。

- [x] **Step 6: 更新后台任务生命周期文档**

在 `README.md` 的“后台任务与资源生命周期”补充：连接树关系库元数据和 Redis INFO 使用面板级任务作用域；应用退出先关闭连接树作用域，仍保持 Windows 与其他平台同一实现。

- [x] **Step 7: 完成全量验证**

Run: `./gradlew.bat clean test`

Expected: BUILD SUCCESSFUL，所有单元测试通过；仅显式的真实 Redis 集成测试可保持跳过。

Run: `./gradlew.bat jlink`

Expected: BUILD SUCCESSFUL，生成 Windows 主目标运行时镜像且未引入平台专用代码。

Run: `codegraph sync`

Expected: CodeGraph 索引同步成功。

Run: `git diff --check`

Expected: 无空白错误。

- [x] **Step 8: 仅提交本边界文件**

```powershell
git add -- README.md src/com/datacube/fx/ConnectionTreePane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/ConnectionTreePaneLifecycleTest.java docs/superpowers/plans/2026-08-09-connection-tree-virtual-thread-lifecycle.md
git commit -m "feat: 连接树使用受管虚拟线程任务"
```

提交前确认 `git status --short` 中 `.testagent/` 仍为未跟踪且未暂存。
