# Sequence Designer Virtual-Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将序列设计器的元数据加载和 ALTER DDL 执行迁移到应用级 JDK 25 虚拟线程运行器，并在标签关闭时取消任务和屏蔽 UI 回调。

**Architecture:** `SequenceDesignerPane` 注入共享 `FxTaskRunner` 并持有标签级 `FxTaskScope`。关系库加载和执行通过作用域提交，纯内存的表单快照与 DDL 预览继续在 JavaFX 线程完成；现有 busy/running 状态保持同一标签单任务交互。`AppShell` 使用受管标签注册 `pane::close`。

**Tech Stack:** Java 25、JavaFX、RichTextFX、Gradle、JUnit 5、现有 `FxTaskRunner`/`FxTaskScope`、jlink、CodeGraph

## Global Constraints

- 默认采用 G1 256MB 平衡模式，不改变现有打包参数。
- 适合的阻塞任务尽可能使用 JDK 25 虚拟线程。
- 虚拟线程不替代 JavaFX Application Thread，也不取消数据库连接资源上限。
- Windows 为主要打包目标，只使用标准 Java/JavaFX API 保留跨平台运行。
- 保留 Oracle ORDER 支持、PostgreSQL 隐藏 ORDER、DDL 预览和确认执行行为。
- 本边界不迁移表设计器；不修改 `.testagent/`。

---

### Task 1: 序列设计器受管后台任务生命周期

**Files:**
- Create: `test/com/datacube/fx/SequenceDesignerPaneLifecycleTest.java`
- Modify: `src/com/datacube/fx/SequenceDesignerPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-09-sequence-designer-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()`、`FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)` 和 `ContentTabPane.openManagedTab(String, Node, Runnable)`
- Produces: `SequenceDesignerPane(DdlService, String, String, String, String, DbType, FxTaskRunner)` 与幂等的 `close()`

- [x] **Step 1: 写入失败的生命周期契约测试**

```java
package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DdlService;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequenceDesignerPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunner() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(SequenceDesignerPane.class));
        assertNotNull(SequenceDesignerPane.class.getConstructor(
                DdlService.class, String.class, String.class, String.class, String.class,
                DbType.class, FxTaskRunner.class));
    }
}
```

- [x] **Step 2: 运行聚焦测试并确认按预期失败**

Run: `./gradlew.bat test --tests com.datacube.fx.SequenceDesignerPaneLifecycleTest`

Expected: FAIL，因为面板尚未实现 `AutoCloseable`，且不存在注入 `FxTaskRunner` 的构造器。

- [x] **Step 3: 实现最小的序列设计器任务作用域**

构造器注入运行器并实现关闭：

```java
private final FxTaskScope tasks;

public SequenceDesignerPane(DdlService svc, String connId, String connName,
                            String schema, String name, DbType dbType,
                            FxTaskRunner runner) {
    this.svc = svc;
    this.connId = connId;
    this.connName = connName;
    this.schema = schema;
    this.name = name;
    this.supportsOrder = dbType == DbType.ORACLE;
    this.tasks = runner.scope();
    build();
    reload();
}

@Override
public void close() {
    tasks.close();
}
```

用 `tasks.submit(() -> svc.loadSequence(connId, schema, name), success, failure)` 替换加载线程；成功继续更新 `original`、表单和预览状态，失败恢复按钮并显示原错误。

用 `tasks.submit(() -> svc.executeDdl(connId, ddl), success, failure)` 替换执行线程；成功继续检查 `firstError` 并在全成功时重新加载，失败恢复 `running` 和按钮。空异常消息回退到异常类名。

- [x] **Step 4: 将序列设计标签纳入受管生命周期**

```java
SequenceDesignerPane pane = new SequenceDesignerPane(
        ddlSvc, connId, connName, node.schema(), name, dbType, tasks);
contentTabs.openManagedTab("编辑序列: " + name, pane.getNode(), pane::close);
```

- [x] **Step 5: 运行聚焦测试并检查遗留线程创建**

Run: `./gradlew.bat test --tests com.datacube.fx.SequenceDesignerPaneLifecycleTest`

Expected: PASS。

Run: `rg -n "new Thread|Platform\\.runLater" src/com/datacube/fx/SequenceDesignerPane.java`

Expected: 无匹配。

- [x] **Step 6: 更新后台任务生命周期文档**

在 `README.md` 补充序列设计器加载和执行使用标签级作用域。

- [x] **Step 7: 完成全量验证**

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
git add -- README.md src/com/datacube/fx/SequenceDesignerPane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/SequenceDesignerPaneLifecycleTest.java docs/superpowers/plans/2026-08-09-sequence-designer-virtual-thread-lifecycle.md
git commit -m "feat: 序列设计器使用受管虚拟线程任务"
```

提交前确认 `.testagent/` 仍为未跟踪且未暂存。
