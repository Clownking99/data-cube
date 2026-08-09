# Table Designer Virtual-Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将表设计器的表结构加载和 DDL 执行迁移到应用级 JDK 25 虚拟线程运行器，并统一新建/编辑标签的关闭生命周期。

**Architecture:** `TableDesignerPane` 注入共享 `FxTaskRunner` 并持有标签级 `FxTaskScope`，关系库加载和 DDL 执行通过作用域提交，DDL 预览继续在 JavaFX 线程进行纯本地计算。脚本遇错策略仍用 `Platform.runLater` 同步询问用户，但增加关闭标记：关闭后不再弹出已排队对话框，虚拟线程被中断时返回 ABORT。

**Tech Stack:** Java 25、JavaFX、RichTextFX、Gradle、JUnit 5、现有 `FxTaskRunner`/`FxTaskScope`、jlink、CodeGraph

## Global Constraints

- 默认采用 G1 256MB 平衡模式，不改变现有打包参数。
- 适合的阻塞任务尽可能使用 JDK 25 虚拟线程。
- 虚拟线程不替代 JavaFX Application Thread，也不取消数据库连接资源上限。
- Windows 为主要打包目标，只使用标准 Java/JavaFX API 保留跨平台运行。
- 保留新建/编辑表、Oracle/PostgreSQL 类型选项、DDL 预览、执行确认和遇错策略。
- 不修改 `.testagent/`，不扩展到导出或更新下载。

---

### Task 1: 表设计器受管后台任务生命周期

**Files:**
- Create: `test/com/datacube/fx/TableDesignerPaneLifecycleTest.java`
- Modify: `src/com/datacube/fx/TableDesignerPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-09-table-designer-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()`、`FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)` 和 `ContentTabPane.openManagedTab(String, Node, Runnable)`
- Produces: `TableDesignerPane(TableDesignService, String, String, TableRef, String, DbType, FxTaskRunner)` 与幂等的 `close()`

- [x] **Step 1: 写入失败的生命周期契约测试**

```java
package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.TableDesignService;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableDesignerPaneLifecycleTest {

    @Test
    void isAutoCloseableAndRequiresSharedTaskRunnerForNewAndExistingTables() throws Exception {
        assertTrue(AutoCloseable.class.isAssignableFrom(TableDesignerPane.class));
        assertNotNull(TableDesignerPane.class.getConstructor(
                TableDesignService.class, String.class, String.class, TableRef.class,
                String.class, DbType.class, FxTaskRunner.class));
    }
}
```

- [x] **Step 2: 运行聚焦测试并确认按预期失败**

Run: `./gradlew.bat test --tests com.datacube.fx.TableDesignerPaneLifecycleTest`

Expected: FAIL，因为面板尚未实现 `AutoCloseable`，且新建/编辑共用的构造器未注入 `FxTaskRunner`。

- [x] **Step 3: 实现最小的表设计器任务作用域**

在面板中增加：

```java
private final FxTaskScope tasks;
private final AtomicBoolean closed = new AtomicBoolean();

public TableDesignerPane(TableDesignService svc, String connId, String connName, TableRef table,
                         String schema, DbType dbType, FxTaskRunner runner) {
    this.svc = svc;
    this.connId = connId;
    this.connName = connName;
    this.table = table;
    this.schema = schema;
    this.dbType = dbType;
    this.isNew = table == null;
    this.tasks = runner.scope();
    build();
    if (!isNew) reload();
}

@Override
public void close() {
    closed.set(true);
    tasks.close();
}
```

用 `tasks.submit(() -> svc.load(connId, table), success, failure)` 替换表结构加载线程；成功继续更新 `original` 和表单，失败保持原提示。

用 `tasks.submit(() -> svc.execute(connId, ddl, this::askScriptError), success, failure)` 替换执行线程；成功继续统计结果并在编辑模式重新加载，失败恢复按钮。空异常消息回退到异常类名。

- [x] **Step 4: 关闭时屏蔽脚本错误对话框**

`askScriptError` 在提交 UI 回调前和 UI 回调执行时检查 `closed`：关闭后返回 `ABORT` 或只释放 latch，不再显示对话框。任务中断仍恢复中断标记并返回 `ABORT`。

这里保留 `Platform.runLater`，因为 `ScriptErrorPolicy` 需要在工作虚拟线程同步等待 JavaFX 用户选择；它不是数据库 I/O 执行线程。

- [x] **Step 5: 将两种表设计标签纳入受管生命周期**

编辑入口：

```java
TableDesignerPane pane = new TableDesignerPane(
        designSvc, connId, connName, table, table.schema(), dbType, tasks);
contentTabs.openManagedTab("设计: " + table.name(), pane.getNode(), pane::close);
```

新建入口：

```java
TableDesignerPane pane = new TableDesignerPane(
        designSvc, connId, connName, null, schema, dbType, tasks);
contentTabs.openManagedTab("新建表", pane.getNode(), pane::close);
```

- [x] **Step 6: 运行聚焦测试并检查遗留线程创建**

Run: `./gradlew.bat test --tests com.datacube.fx.TableDesignerPaneLifecycleTest`

Expected: PASS。

Run: `rg -n "new Thread" src/com/datacube/fx/TableDesignerPane.java`

Expected: 无匹配；`Platform.runLater` 仅保留在同步错误策略对话框中。

- [x] **Step 7: 更新文档并完成全量验证**

在 `README.md` 补充表设计器加载和执行使用标签级任务作用域。

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
git add -- README.md src/com/datacube/fx/TableDesignerPane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/TableDesignerPaneLifecycleTest.java docs/superpowers/plans/2026-08-09-table-designer-virtual-thread-lifecycle.md
git commit -m "feat: 表设计器使用受管虚拟线程任务"
```

提交前确认 `.testagent/` 仍为未跟踪且未暂存。
