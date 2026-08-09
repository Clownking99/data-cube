# Export Virtual-Thread Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将单表导出的 JDBC、文件写入和 pg_dump 等阻塞 I/O 迁移到应用级 JDK 25 虚拟线程运行器，并让进度框关闭能够取消任务和屏蔽回调。

**Architecture:** `ExportDialog.show` 接收共享 `FxTaskRunner`，每次确认导出后创建独立 `FxTaskScope`。导出继续保持单表、500 行分页流式读取，不增加并发；进度框关闭时关闭作用域并中断任务，完成回调由作用域调度到 JavaFX 线程。失败或协作式取消继续删除半成品文件。

**Tech Stack:** Java 25、JavaFX、Gradle、JUnit 5、现有 `FxTaskRunner`/`FxTaskScope`、JDBC、pg_dump、jlink、CodeGraph

## Global Constraints

- 默认采用 G1 256MB 平衡模式，不改变现有打包参数。
- 适合的阻塞任务尽可能使用 JDK 25 虚拟线程。
- 虚拟线程不替代 JavaFX Application Thread，也不提高数据库或外部进程并发度。
- 保持单表导出与每页 500 行流式背压，不一次性加载全部数据。
- Windows 为主要打包目标，SQL/XLSX 继续跨平台；pg_dump 仍仅在 PostgreSQL 选项中出现。
- JDBC 驱动或外部进程不响应中断时仅保证屏蔽 UI 回调并最终由连接/进程资源层回收。
- 不修改 `.testagent/`，不使用真实 Redis 连接。

---

### Task 1: 导出对话框受管虚拟线程生命周期

**Files:**
- Create: `test/com/datacube/fx/ExportDialogLifecycleTest.java`
- Modify: `src/com/datacube/fx/ExportDialog.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-09-export-virtual-thread-lifecycle.md`

**Interfaces:**
- Consumes: `FxTaskRunner.scope()`、`FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>)`
- Produces: `ExportDialog.show(ConnectionManager, String, TableRef, Window, FxTaskRunner)`

- [x] **Step 1: 写入失败的共享运行器契约测试**

```java
package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.model.TableRef;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExportDialogLifecycleTest {

    @Test
    void showRequiresSharedTaskRunner() throws Exception {
        assertNotNull(ExportDialog.class.getMethod("show",
                ConnectionManager.class, String.class, TableRef.class,
                Window.class, FxTaskRunner.class));
    }
}
```

- [x] **Step 2: 运行聚焦测试并确认按预期失败**

Run: `./gradlew.bat test --tests com.datacube.fx.ExportDialogLifecycleTest`

Expected: FAIL，因为 `show` 尚未接收共享 `FxTaskRunner`。

- [x] **Step 3: 注入运行器并创建单次导出作用域**

将 `show` 和 `runExport` 增加 `FxTaskRunner runner` 参数。`runExport` 创建：

```java
FxTaskScope task = runner.scope();
AtomicBoolean completed = new AtomicBoolean();
```

进度框使用取消按钮，并在隐藏时取消尚未完成的导出：

```java
progress.getButtonTypes().setAll(ButtonType.CANCEL);
progress.setOnHidden(event -> {
    if (!completed.get()) task.close();
});
```

- [x] **Step 4: 用作用域替换手工导出线程**

```java
task.submit(() -> {
    try {
        TableExporter.export(conns, connId, table, content, format, out);
        return out;
    } catch (Exception failure) {
        if (out.exists()) out.delete();
        throw failure;
    }
}, exported -> {
    completed.set(true);
    task.close();
    progress.close();
    showResult(null, exported);
}, failure -> {
    completed.set(true);
    task.close();
    progress.close();
    showResult(message(failure), out);
});
```

结果展示和空异常消息回退使用：

```java
private static void showResult(String error, File out) {
    Alert done = new Alert(error == null ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR);
    done.setTitle("导出");
    done.setHeaderText(null);
    done.setContentText(error == null
            ? "导出完成:\n" + out.getAbsolutePath()
            : "导出失败:\n" + error);
    done.showAndWait();
}

private static String message(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
}
```

作用域关闭会中断虚拟线程并屏蔽已排队的成功/失败回调。正常完成或失败时关闭作用域，避免对话框级资源悬挂。

- [x] **Step 5: 更新应用调用点**

```java
ExportDialog.show(connMgr, connId, table,
        root.getScene() == null ? null : root.getScene().getWindow(), tasks);
```

- [x] **Step 6: 运行聚焦测试并检查遗留线程创建**

Run: `./gradlew.bat test --tests com.datacube.fx.ExportDialogLifecycleTest`

Expected: PASS。

Run: `rg -n "new Thread|Platform\\.runLater" src/com/datacube/fx/ExportDialog.java`

Expected: 无匹配；UI 回调全部由 `FxTaskScope` 调度。

- [x] **Step 7: 更新文档并完成全量验证**

在 `README.md` 补充单表导出使用独立任务作用域、500 行分页背压和进度框取消语义。

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
git add -- README.md src/com/datacube/fx/ExportDialog.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/ExportDialogLifecycleTest.java docs/superpowers/plans/2026-08-09-export-virtual-thread-lifecycle.md
git commit -m "feat: 导出任务使用受管虚拟线程"
```

提交前确认 `.testagent/` 仍为未跟踪且未暂存。
