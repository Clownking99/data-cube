# Connection Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成启动引导、非阻塞连接测试及未绑定 SQL 提示，让用户能够明确、安全地开始查询。

**Architecture:** 开始提示由 AppShell 组合，不加入受管标签集合。连接测试复用应用 FxTaskRunner，每个对话框有独立 scope 与小型状态控制器。SQL 提示只是现有候选连接/固定连接的投影，不增加另一套连接准入规则。

**Tech Stack:** Java 25、JavaFX 25、JUnit 5、Gradle 9.2.0、PowerShell；不增加依赖。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-08-28-connection-onboarding-design.md`，用户已确认。计划基线：`ed3f377`。
- 本阶段不添加数据库类型、AI、连接分组、最近连接列表、会话恢复、遥测或新的查询管理功能。
- 不修改数据库协议、连接配置文件格式、凭据加密方案、事务语义和发布版本。不自动推送或打标签。
- “保存”仍只保存有效配置，不隐式测试或连接，也不要求测试成功。
- 一个对话框内最多一个测试请求在途，快速连点不能创建多个请求。
- 提示展示本身不触发建连或元数据读取；现有显式选择连接引起的元数据预热行为不在本阶段重构。
- 保留工作区现有未跟踪 `.testagent/`，不读取、修改或暂存它。
- 不把查询超时当成连接测试超时，不承诺驱动立即响应中断。
- 不显示原始连接异常、完整 JDBC URL、密码或配置对象；不新增敏感日志。
- 测试用临时目录和假操作，不使用已保存的公司凭据；无显示环境只跳过依赖 JavaFX 控件的用例，纯状态用例必须运行。
- 以下相对路径均以 `D:\Projects\朝花夕拾` 为根。每个代码步骤使用 apply_patch。行号仅作导航，执行时先用 CodeGraph 读取相应符号。
- 用户已选择在当前任务内顺序实施，不启动子代理。未勾选步骤及其代码块仍是待执行内容，不代表已实现或通过测试。

**2026-08-28 进度：** 任务 1 的实现与自动化回归完成，在 `codex/connection-onboarding` 本地分支单独提交；任务 2、3 尚未开始。桌面截图全黑且激活窗口报访问拒绝，实际点击与亮暗主题验收待恢复桌面访问后完成。详见 `docs/superpowers/verification/2026-08-28-workspace-start.md`。

---

## 文件职责与顺序

| 任务 | 文件 | 职责 |
| --- | --- | --- |
| 1 | 新增 `src/com/datacube/fx/WorkspaceStartPane.java` | 无 I/O 的开始提示 |
| 1 | 修改 `ContentTabPane.java`、`ConnectionTreePane.java`、`AppShell.java`（同目录） | 只读空标签状态、聚焦树、组合空状态 |
| 1 | 新增 `test/com/datacube/fx/FxUiTestSupport.java`、`WorkspaceStartPaneTest.java` | 真实 FX 控件测试支持与开始页验收 |
| 2 | 新增 `src/com/datacube/fx/ConnectionTestController.java` | 单请求、结果、关闭与失效状态 |
| 2 | 修改 `src/com/datacube/fx/ConnectionDialog.java`、`ConnectionTreePane.java` | 对话框 scope、交互与应用 runner 传递 |
| 2 | 新增 `test/com/datacube/fx/ConnectionTestControllerTest.java`、`ConnectionDialogTest.java` | 状态与实际控件行为测试 |
| 2 | 修改 `test/com/datacube/service/ConnectionManagerDedicatedSessionTest.java` | 测试连接不能发布缓存会话的边界回归 |
| 3 | 新增 `src/com/datacube/fx/SqlConnectionGuidance.java` | 纯函数生成连接提示与可执行条件 |
| 3 | 修改 `src/com/datacube/fx/SqlEditorPane.java` | 组合既有忙碌/关闭状态与连接提示 |
| 3 | 新增 `test/com/datacube/fx/SqlConnectionGuidanceTest.java`、`SqlEditorConnectionGuidanceTest.java` | 状态矩阵和 SQL 页接入验收 |

任务 1 → 2 → 3 顺序执行，避免同时修改 ConnectionTreePane。每个任务都有独立测试和提交，最后统一回归。既有 provider、service、事务实现不列为修改目标。

## 执行前基线

- [x] 核对分支、工作区和当前设计，若已有同路径改动先协调，不覆盖。

```powershell
git status --short
git log -3 --oneline
git branch --show-current
Get-Content -LiteralPath 'docs/superpowers/specs/2026-08-28-connection-onboarding-design.md'
.\gradlew.bat test --no-daemon --console=plain
```

预期：仅既有 `.testagent/` 或明确属于本工作的文档改动；基线测试成功。若基线失败，记录原始失败并先判断与当前任务的关系，不能算作本次回归结果。执行时依所选执行技能决定是否需要隔离 worktree；不在计划编写时创建。

## Task 1: 空工作区引导，不改变标签所有权

**Files:** 文件职责表中任务 1 的六个文件。修改锚点：`ContentTabPane` 字段与 `getNode()` 附近，`ConnectionTreePane.getNode()` 附近，`AppShell.build()` 第 116 行附近。

**Interfaces:**
- Consumes: `ContentTabPane.getNode(): Node`、`ConnectionTreePane.newConnection(): void`。
- Produces: `ContentTabPane.emptyProperty(): ObservableBooleanValue`、`ConnectionTreePane.focusConnections(): void`、`WorkspaceStartPane(Runnable createConnection, Runnable focusConnections)`、`AppShell.startWorkspace(ContentTabPane, Runnable, Runnable): Node`。
- Test support: `FxUiTestSupport.call(Callable<T>): T`，只在测试线程等待；不得用于生产。

- [x] **Step 1: 写真实控件的失败测试及本任务专用测试支持。**

`FxUiTestSupport.java` 完整内容：

```java
package com.datacube.fx;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxUiTestSupport {
    private FxUiTestSupport() {}

    static <T> T call(Callable<T> action) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "JavaFX controls require an available display");
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "FX startup timed out");
        if (Platform.isFxApplicationThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(() -> {
            Platform.setImplicitExit(false);
            return action.call();
        });
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
```

`WorkspaceStartPaneTest.java` 完整内容：

```java
package com.datacube.fx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceStartPaneTest {
    @Test void emptyOpenAndCloseKeepTheSameTabNode() throws Exception {
        FxUiTestSupport.call(() -> {
            ContentTabPane tabs = new ContentTabPane();
            Parent root = (Parent) AppShell.startWorkspace(tabs, () -> {}, () -> {});
            var start = root.lookup("#workspace-start");
            var original = tabs.getNode();
            assertTrue(start.isVisible());
            Tab tab = tabs.openTab("SQL", new Group());
            assertFalse(start.isVisible());
            assertSame(original, tabs.getNode());
            ((TabPane) original).getTabs().remove(tab);
            assertTrue(start.isVisible());
            assertTrue(tabs.emptyProperty().get());
            return null;
        });
    }

    @Test void actionsDoNothingUntilClickedAndInvokeOnlyTheirCallback() throws Exception {
        FxUiTestSupport.call(() -> {
            AtomicInteger create = new AtomicInteger();
            AtomicInteger focus = new AtomicInteger();
            WorkspaceStartPane pane = new WorkspaceStartPane(
                    create::incrementAndGet, focus::incrementAndGet);
            assertEquals(0, create.get());
            assertEquals(0, focus.get());
            ((Button) pane.lookup("#start-new-connection")).fire();
            assertEquals(1, create.get());
            assertEquals(0, focus.get());
            ((Button) pane.lookup("#start-select-connection")).fire();
            assertEquals(1, create.get());
            assertEquals(1, focus.get());
            return null;
        });
    }

    @Test void rejectedManagedCloseDoesNotReplaceTheContent() throws Exception {
        var completion = FxUiTestSupport.call(() -> {
            ContentTabPane tabs = new ContentTabPane();
            Parent root = (Parent) AppShell.startWorkspace(tabs, () -> {}, () -> {});
            tabs.openManagedTab("protected", new Group(),
                    () -> CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED),
                    () -> fail("rejected close must not finalize"));
            return new Object[]{tabs, root, tabs.closeAllManagedTabs()};
        });
        ((java.util.concurrent.CompletionStage<?>) completion[2])
                .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        FxUiTestSupport.call(() -> {
            ContentTabPane tabs = (ContentTabPane) completion[0];
            Parent root = (Parent) completion[1];
            assertFalse(tabs.emptyProperty().get());
            assertFalse(root.lookup("#workspace-start").isVisible());
            return null;
        });
    }
}
```

- [x] **Step 2: 运行并记录红灯。**

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.WorkspaceStartPaneTest' --no-daemon --console=plain
```

预期新增 API 不存在导致编译失败；补齐 API 后必须运行到行为断言，不能用“没有找到测试”或显示环境异常当成红灯。

- [x] **Step 3: 添加开始提示和只读空状态。**

`WorkspaceStartPane.java` 完整内容：

```java
package com.datacube.fx;

import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class WorkspaceStartPane extends VBox {
    WorkspaceStartPane(Runnable createConnection, Runnable focusConnections) {
        Objects.requireNonNull(createConnection);
        Objects.requireNonNull(focusConnections);
        setId("workspace-start");
        setSpacing(12);
        setPadding(new Insets(24));
        setAlignment(Pos.CENTER_LEFT);
        setMaxSize(520, USE_PREF_SIZE);
        Label title = new Label("开始使用 DataCube");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label intro = new Label("新建连接，或在左侧选择已保存的连接后开始工作");
        intro.setWrapText(true);
        Button create = new Button("新建连接");
        create.setId("start-new-connection");
        create.setOnAction(event -> createConnection.run());
        Button focus = new Button("选择已有连接");
        focus.setId("start-select-connection");
        focus.setOnAction(event -> focusConnections.run());
        Label hint = new Label("选择 PostgreSQL / Oracle 连接后，点击顶部‘新建 SQL’。\n"
                + "Redis 请使用连接的控制台与键浏览功能。");
        hint.setWrapText(true);
        getChildren().addAll(title, intro, new HBox(8, create, focus), hint);
    }
}
```

`ContentTabPane` 在 `tabPane` 字段后添加，原 `getNode()` 不动：

```java
private final javafx.beans.binding.BooleanBinding empty =
        javafx.beans.binding.Bindings.isEmpty(tabPane.getTabs());

javafx.beans.value.ObservableBooleanValue emptyProperty() {
    return empty;
}
```

`ConnectionTreePane` 添加只聚焦的方法，不调用选择、展开或 reload：

```java
public void focusConnections() {
    tree.requestFocus();
}
```

`AppShell` 添加以下完整组合方法；将 build 中创建 SplitPane 的第二个参数改为该方法的调用：

```java
static Node startWorkspace(ContentTabPane tabs, Runnable create, Runnable focus) {
    WorkspaceStartPane start = new WorkspaceStartPane(create, focus);
    start.visibleProperty().bind(tabs.emptyProperty());
    start.managedProperty().bind(start.visibleProperty());
    Node content = tabs.getNode();
    var hasTabs = javafx.beans.binding.Bindings.not(tabs.emptyProperty());
    content.visibleProperty().bind(hasTabs);
    content.managedProperty().bind(hasTabs);
    return new javafx.scene.layout.StackPane(content, start);
}

// build() 中替换原 SplitPane 初始化语句，其他拆分比例和关闭逻辑不变。
SplitPane split = new SplitPane(connectionTree.getNode(),
        startWorkspace(contentTabs, connectionTree::newConnection,
                connectionTree::focusConnections));
```

- [x] **Step 4: 绿灯及关闭保护回归。**

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.WorkspaceStartPaneTest' --tests 'com.datacube.fx.ContentTabPane*' --tests 'com.datacube.fx.Managed*' --no-daemon --console=plain
git diff --check
```

预期 Windows 显示环境中三个新增行为测试全部通过，既有受管标签测试无回归。实际扩充为 7 项通过，并以真实控件验证空树和已保存连接树的聚焦行为。桌面访问受限，实际按钮点击和主题视觉检查尚未验收；不能为了“看起来有效”自动选择第一条连接。

- [x] **Step 5: 审查本任务 diff 并单独提交。**

```powershell
git add -- src/com/datacube/fx/WorkspaceStartPane.java src/com/datacube/fx/ContentTabPane.java src/com/datacube/fx/ConnectionTreePane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/FxUiTestSupport.java test/com/datacube/fx/WorkspaceStartPaneTest.java
git diff --cached --check
git diff --cached --stat
git commit -m 'feat: 增加空工作区开始引导'
```

## Task 2: 对话框内的异步连接测试与结果反馈

**Files:** 任务 2 的六个文件。生产修改锚点：`ConnectionDialog.show/build/warn`；`ConnectionTreePane` 构造器和两处 `ConnectionDialog.show`。

**Interfaces:**
- Consumes: `FxTaskRunner.scope(): FxTaskScope`、`FxTaskScope.submit(Callable<T>, Consumer<T>, Consumer<Throwable>): Future<?>`、`ConnectionManager.test(ConnConfig): String`（null 成功，非 null 失败）。
- Produces: `ConnectionTestController` 的 `start(ConnConfig)`、`edited()`、`close()`、`phaseProperty()`、`phase()`；所有非后台操作都由 FX 线程调用。
- Dialog API: `show(ConnConfig, CredentialCipher, ConnectionManager, FxTaskRunner): Optional<ConnConfig>`；包内 `create(ConnConfig, CredentialCipher, ConnectionTestController): Dialog<ConnConfig>` 供行为测试构造。
- 不修改 FxTaskRunner/FxTaskScope 的公共契约、provider 或连接存储。

- [ ] **Step 1: 先写不依赖显示环境的状态测试。**

`ConnectionTestControllerTest.java` 完整内容；这里注入的 Submitter 是单线程可控队列，真实后台线程另在 Step 5 验证。

```java
package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionTestControllerTest {
    private static ConnConfig config() {
        return new ConnConfig("test", "test", DbType.POSTGRESQL,
                "example.invalid", 5432, "db", "user", "sentinel-secret", Map.of());
    }

    static final class Pending implements ConnectionTestController.Submitter {
        int calls;
        Callable<String> work;
        Consumer<String> success;
        Consumer<Throwable> failure;
        public void submit(Callable<String> task, Consumer<String> ok,
                           Consumer<Throwable> failed) {
            calls++;
            work = task;
            success = ok;
            failure = failed;
        }
    }

    @Test void singleFlightSnapshotAndNullSuccess() throws Exception {
        Pending pending = new Pending();
        ConnConfig snapshot = config();
        AtomicInteger calls = new AtomicInteger();
        try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> {
            assertSame(snapshot, cfg);
            calls.incrementAndGet();
            return null;
        })) {
            controller.start(snapshot);
            controller.start(snapshot);
            assertEquals(1, pending.calls);
            assertEquals(0, calls.get(), "submission must not run IO inline");
            assertEquals(ConnectionTestController.Phase.TESTING, controller.phase());
            pending.success.accept(pending.work.call());
            assertEquals(1, calls.get());
            assertEquals(ConnectionTestController.Phase.SUCCEEDED, controller.phase());
            controller.edited();
            assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
        }
    }

    @Test void nonNullResultAndExceptionAreSafeFailuresAndCanRetry() {
        Pending pending = new Pending();
        try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
            controller.start(config());
            pending.success.accept("password=sentinel-secret jdbc:private");
            assertEquals(ConnectionTestController.Phase.FAILED, controller.phase());
            assertFalse(controller.phase().text().contains("sentinel-secret"));
            controller.start(config());
            pending.failure.accept(new IllegalStateException("sentinel-secret"));
            assertEquals(ConnectionTestController.Phase.FAILED, controller.phase());
            assertEquals(2, pending.calls);
        }
    }

    @Test void submissionRejectionRecoversWithoutRawException() {
        try (var controller = new ConnectionTestController((work, ok, failed) -> {
            throw new java.util.concurrent.RejectedExecutionException("sentinel-secret");
        }, () -> {}, cfg -> null)) {
            controller.start(config());
            assertEquals(ConnectionTestController.Phase.UNAVAILABLE, controller.phase());
            assertFalse(controller.phase().text().contains("sentinel-secret"));
            controller.edited();
            assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
        }
    }

    @Test void closeDropsBothLateCallbacksAndIsIdempotent() {
        Pending pending = new Pending();
        AtomicInteger stops = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        var controller = new ConnectionTestController(pending, stops::incrementAndGet, cfg -> null);
        controller.phaseProperty().addListener((o, before, after) -> updates.incrementAndGet());
        controller.start(config());
        int beforeClose = updates.get();
        controller.close();
        controller.close();
        pending.success.accept(null);
        pending.failure.accept(new IllegalStateException("late"));
        controller.edited();
        controller.start(config());
        assertEquals(beforeClose, updates.get());
        assertEquals(1, stops.get());
        assertEquals(1, pending.calls);
    }
}
```

- [ ] **Step 2: 红灯后实现状态控制器。**

运行：

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.ConnectionTestControllerTest' --no-daemon --console=plain
```

预期新增类型缺失。随后创建 `ConnectionTestController.java`：

```java
package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.ConnConfig;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

final class ConnectionTestController implements AutoCloseable {
    enum Phase {
        IDLE("尚未测试当前配置"), TESTING("正在测试连接…"),
        SUCCEEDED("连接成功，可保存配置"),
        FAILED("连接失败。请检查主机和端口、数据库或服务名、凭据及网络后重试。"),
        UNAVAILABLE("无法开始连接测试，请稍后重试");
        private final String text;
        Phase(String text) { this.text = text; }
        String text() { return text; }
    }

    @FunctionalInterface interface Submitter {
        void submit(Callable<String> work, Consumer<String> success,
                    Consumer<Throwable> failure);
    }

    private final Submitter submitter;
    private final Runnable stop;
    private final Function<ConnConfig, String> operation;
    private final ReadOnlyObjectWrapper<Phase> phase = new ReadOnlyObjectWrapper<>(Phase.IDLE);
    private boolean closed;

    ConnectionTestController(FxTaskScope scope, Function<ConnConfig, String> operation) {
        this(scope::submit, scope::close, operation);
    }

    ConnectionTestController(Submitter submitter, Runnable stop,
                             Function<ConnConfig, String> operation) {
        this.submitter = Objects.requireNonNull(submitter);
        this.stop = Objects.requireNonNull(stop);
        this.operation = Objects.requireNonNull(operation);
    }

    ReadOnlyObjectProperty<Phase> phaseProperty() { return phase.getReadOnlyProperty(); }
    Phase phase() { return phase.get(); }

    void start(ConnConfig snapshot) {
        if (closed || phase() == Phase.TESTING) return;
        Objects.requireNonNull(snapshot);
        phase.set(Phase.TESTING);
        try {
            submitter.submit(() -> operation.apply(snapshot),
                    error -> finish(error == null ? Phase.SUCCEEDED : Phase.FAILED),
                    error -> finish(Phase.FAILED));
        } catch (RuntimeException rejected) {
            finish(Phase.UNAVAILABLE);
        }
    }

    void edited() {
        if (!closed && phase() != Phase.TESTING) phase.set(Phase.IDLE);
    }

    private void finish(Phase result) {
        if (!closed) phase.set(result);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        stop.run();
    }
}
```

Scope 保证后台操作和 FX 回调分离，controller 再防止关闭后写状态。关闭不发布新的 UI 状态，不会向已隐藏窗口通知 CLOSED。运行相同命令，预期四个状态测试通过。

- [ ] **Step 3: 写对话框行为失败测试。**

`ConnectionDialogTest.java` 完整内容，使用包内 create API 构造控件，不打开保存的连接存储。`CredentialCipher` 仅用于空密码新建/沿用已有假密文，不测试真实凭据。

```java
package com.datacube.fx;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.Map;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionDialogTest {
    private static ConnConfig config(DbType type) {
        return new ConnConfig("test", "test", type, "example.invalid", type.defaultPort(),
                type == DbType.REDIS ? "0" : "db", "user", "fake-existing-cipher", Map.of());
    }

    @Test void pendingDisablesSaveAndFormButRetainsCancelAndFailureInput() throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                Dialog<ConnConfig> dialog = ConnectionDialog.create(config(DbType.POSTGRESQL),
                        new CredentialCipher(), controller);
                var pane = dialog.getDialogPane();
                Button test = (Button) pane.lookup("#connection-test");
                Button save = (Button) pane.lookup("#connection-save");
                TextField host = (TextField) pane.lookup("#connection-host");
                test.fire();
                assertEquals(1, pending.calls);
                assertTrue(save.isDisabled());
                assertTrue(host.isDisabled());
                assertFalse(pane.lookupButton(ButtonType.CANCEL).isDisabled());
                pending.success.accept("private sentinel-secret");
                assertFalse(save.isDisabled());
                assertFalse(host.isDisabled());
                assertEquals("example.invalid", host.getText());
                assertFalse(((Label) pane.lookup("#connection-test-status")).getText()
                        .contains("sentinel-secret"));
                host.setText("other.invalid");
                assertEquals(ConnectionTestController.Phase.IDLE, controller.phase());
            }
            return null;
        });
    }

    @Test void saveIsIndependentAndEditPreservesCipherForEveryProvider() throws Exception {
        FxUiTestSupport.call(() -> {
            for (DbType type : DbType.values()) {
                var pending = new ConnectionTestControllerTest.Pending();
                try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                    Dialog<ConnConfig> dialog = ConnectionDialog.create(config(type),
                            new CredentialCipher(), controller);
                    ButtonType save = dialog.getDialogPane().getButtonTypes().stream()
                            .filter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                            .findFirst().orElseThrow();
                    ConnConfig result = dialog.getResultConverter().call(save);
                    assertNotNull(result);
                    assertEquals(type, result.type());
                    assertEquals("fake-existing-cipher", result.encryptedPassword());
                    assertEquals(0, pending.calls);
                    assertNull(dialog.getResultConverter().call(ButtonType.CANCEL));
                }
            }
            return null;
        });
    }

    @Test void newDialogHasNoRequestAndFieldsHaveNames() throws Exception {
        FxUiTestSupport.call(() -> {
            var pending = new ConnectionTestControllerTest.Pending();
            try (var controller = new ConnectionTestController(pending, () -> {}, cfg -> null)) {
                var dialog = ConnectionDialog.create(null, new CredentialCipher(), controller);
                assertEquals(0, pending.calls);
                for (String id : new String[]{"name", "host", "port", "database", "user", "password"}) {
                    var field = dialog.getDialogPane().lookup("#connection-" + id);
                    assertNotNull(field);
                    assertNotNull(field.getAccessibleText());
                    assertFalse(field.getAccessibleText().isBlank());
                }
            }
            return null;
        });
    }
}
```

运行并确认 create API 不存在造成红灯：

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.ConnectionDialogTest' --no-daemon --console=plain
```

- [ ] **Step 4a: 接入 runner 和受控的对话框构造。**

`ConnectionTreePane` 保存 constructor 参数 `runner`，仅新建/编辑对话框使用，不在 tree.close 中关闭它：

```java
private final FxTaskRunner runner;

// 构造器在 this.tasks = runner.scope() 之前添加。
this.runner = runner;

// onAddConnection 与 onEditConnection 仅替换各自调用首行。
ConnectionDialog.show(null, connMgr.cipher(), connMgr, runner).ifPresent(cfg -> {
ConnectionDialog.show(existing, connMgr.cipher(), connMgr, runner).ifPresent(cfg -> {
```

上面最后两行分别替换原有 lambda 开头，保留各自已有 lambda 内容和闭合括号，不合并这两个调用。

`ConnectionDialog` 原 show 主体改名为包内 create，参数为 `existing, cipher, tester`，保留原表单构建与 build 校验逻辑；末尾从 `return dialog.showAndWait()` 改为 `return dialog`。新增公开 show：

```java
public static Optional<ConnConfig> show(ConnConfig existing, CredentialCipher cipher,
                                      ConnectionManager connMgr,
                                      com.datacube.fx.task.FxTaskRunner runner) {
    com.datacube.fx.task.FxTaskScope scope = runner.scope();
    try (ConnectionTestController tester = new ConnectionTestController(scope, connMgr::test)) {
        return create(existing, cipher, tester).showAndWait();
    } finally {
        scope.close();
    }
}

static Dialog<ConnConfig> create(ConnConfig existing, CredentialCipher cipher,
                                 ConnectionTestController tester) {
```

最后一行是原 show 方法的替换签名；保留原方法闭合括号。create 返回的 Dialog 隐藏时也关闭 tester；公开 show 的 finally 兜底构建/显示失败，不在 FX 上调用 runner.close。

- [ ] **Step 4b: 替换同步测试段，增加状态区与标签关联。**

删除原 `dialog.getDialogPane().setContent(grid)` 和原同步测试事件段。替换为下面代码，保留原 resultConverter，并在返回 dialog 前安装隐藏清理：

```java
nameField.setId("connection-name");
hostField.setId("connection-host");
portField.setId("connection-port");
dbField.setId("connection-database");
userField.setId("connection-user");
passField.setId("connection-password");
linkFormLabels(grid);

Button testBtn = (Button) dialog.getDialogPane().lookupButton(testType);
Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
testBtn.setId("connection-test");
saveBtn.setId("connection-save");
Label testStatus = new Label();
testStatus.setId("connection-test-status");
testStatus.setWrapText(true);
testStatus.setMaxWidth(360);
testStatus.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
        () -> tester.phase().text(), tester.phaseProperty()));
var testing = tester.phaseProperty().isEqualTo(ConnectionTestController.Phase.TESTING);
grid.disableProperty().bind(testing);
testBtn.disableProperty().bind(testing);
saveBtn.disableProperty().bind(testing);
javafx.scene.control.ProgressIndicator progress = new javafx.scene.control.ProgressIndicator();
progress.setMaxSize(18, 18);
progress.visibleProperty().bind(testing);
progress.managedProperty().bind(testing);
javafx.scene.layout.HBox feedback = new javafx.scene.layout.HBox(8, progress, testStatus);
feedback.setPadding(new Insets(0, 15, 12, 15));
dialog.getDialogPane().setContent(new javafx.scene.layout.VBox(grid, feedback));
for (javafx.beans.Observable value : java.util.List.<javafx.beans.Observable>of(
        typeBox.valueProperty(), nameField.textProperty(), hostField.textProperty(),
        portField.textProperty(), dbField.textProperty(), userField.textProperty(),
        passField.textProperty(), environmentBox.valueProperty(),
        readOnlyCheck.selectedProperty(), timeoutField.textProperty())) {
    value.addListener(ignored -> tester.edited());
}
testBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
    event.consume();
    if (tester.phase() == ConnectionTestController.Phase.TESTING) return;
    ConnConfig snapshot = build(existing, cipher, typeBox.getValue(), nameField, hostField,
            portField, dbField, userField, passField, environmentBox, readOnlyCheck, timeoutField);
    if (snapshot != null) tester.start(snapshot);
});
dialog.setOnHidden(event -> tester.close());
```

完整 `linkFormLabels` helper，按现有 GridPane 的行列关联，不重建表单：

```java
private static void linkFormLabels(GridPane grid) {
    for (javafx.scene.Node node : grid.getChildren()) {
        if (!(node instanceof Label label)) continue;
        for (javafx.scene.Node candidate : grid.getChildren()) {
            if (!(candidate instanceof Control control) || candidate == node) continue;
            if (java.util.Objects.equals(GridPane.getRowIndex(node), GridPane.getRowIndex(candidate))
                    && Integer.valueOf(1).equals(GridPane.getColumnIndex(candidate))) {
                label.setLabelFor(control);
                control.accessibleTextProperty().bind(label.textProperty());
            }
        }
    }
}
```

校验失败仍使用既有 warn 弹窗，完整替换 warn 方法如下：

```java
private static void warn(String msg, Control field) {
    Alert alert = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
    alert.setHeaderText(null);
    alert.showAndWait();
    field.requestFocus();
}
```

原四类 warn 调用按以下完整替换：

```java
// 必填错误分支。
warn(type == DbType.REDIS ? "名称和主机不能为空" : "名称/主机/数据库/用户名均不能为空",
        name.isEmpty() ? nameField : host.isEmpty() ? hostField : db.isEmpty() ? dbField : userField);
// Redis DB 范围错误分支。
warn("Redis DB 索引必须是 0-15 的整数", dbField);
// 端口解析错误分支。
warn("端口必须为数字", portField);
// 查询超时错误分支。
warn("查询超时必须是 0-3600 的整数秒", timeoutField);
```

校验分支共四个，包含必填、Redis 范围、端口和超时；不新增端口策略或改变 0 秒查询超时含义。保留 resultConverter 的保存行为，不增加测试成功门槛。

- [ ] **Step 5: 验证真实 scope 不阻塞 FX，并验证隐藏清理。**

把以下完整方法加到 `ConnectionDialogTest`，使用真实 runner 和 latch，不连接任何数据库：

```java
@Test void slowOperationLeavesFxResponsiveAndHiddenDialogSuppressesResult() throws Exception {
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var finished = new java.util.concurrent.CountDownLatch(1);
    var operationOnFx = new java.util.concurrent.atomic.AtomicBoolean(true);
    var changes = new java.util.concurrent.atomic.AtomicInteger();
    try (var runner = new com.datacube.fx.task.FxTaskRunner()) {
        Object[] fixture = FxUiTestSupport.call(() -> {
            var controller = new ConnectionTestController(runner.scope(), cfg -> {
                operationOnFx.set(javafx.application.Platform.isFxApplicationThread());
                started.countDown();
                try {
                    while (true) {
                        try { release.await(); break; }
                        catch (InterruptedException ignored) { /* Simulate a slow driver. */ }
                    }
                    return null;
                } finally { finished.countDown(); }
            });
            controller.phaseProperty().addListener((o, a, b) -> changes.incrementAndGet());
            var dialog = ConnectionDialog.create(config(DbType.POSTGRESQL),
                    new CredentialCipher(), controller);
            dialog.show();
            ((Button) dialog.getDialogPane().lookup("#connection-test")).fire();
            return new Object[]{dialog, controller};
        });
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(operationOnFx.get());
            assertEquals("heartbeat", FxUiTestSupport.call(() -> "heartbeat"));
            int beforeClose = changes.get();
            FxUiTestSupport.call(() -> { ((Dialog<?>) fixture[0]).close(); return null; });
            release.countDown();
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> null);
            assertEquals(beforeClose, changes.get());
        } finally {
            release.countDown();
            FxUiTestSupport.call(() -> {
                ((Dialog<?>) fixture[0]).close();
                ((ConnectionTestController) fixture[1]).close();
                return null;
            });
        }
    }
}
```

已有 `FxTaskScopeTest.dropsQueuedUiCallbackWhenClosedBeforeDispatch` 验证排队后关闭；controller 测试验证迟到成功与失败。三者一起运行，不只靠源文件字符串匹配证明线程和清理行为。

在既有 `ConnectionManagerDedicatedSessionTest` 内新增以下完整方法，复用该测试类的 `RecordingConnectionFactory`、`RecordingRunner`、`provider` 和 `config` helper。它验证本次复用的真实 service 入口，而不是另造一个连接管理器：

```java
@Test
void probeUsesTestPathWithoutOpeningOrCachingASession() {
    CredentialCipher cipher = new CredentialCipher();
    RecordingConnectionFactory factory = new RecordingConnectionFactory();
    ConnectionManager manager = new ConnectionManager(
            cipher, type -> provider(factory, new RecordingRunner()));
    ConnConfig original = config(cipher, "probe-secret");
    manager.register(original);
    assertNull(manager.test(original));
    assertEquals(1, factory.tests.get());
    assertTrue(factory.opens.isEmpty());
    assertFalse(manager.isConnected("conn"));
    assertEquals(original.encryptedPassword(), manager.config("conn").encryptedPassword());
    assertFalse(manager.config("conn").props().containsKey("__plainPassword"));
}
```

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.ConnectionTestControllerTest' --tests 'com.datacube.fx.ConnectionDialogTest' --tests 'com.datacube.fx.task.FxTaskScopeTest' --tests 'com.datacube.fx.ConnectionTree*' --tests 'com.datacube.service.ConnectionManager*' --tests 'com.datacube.redis.RedisSessionManagerTest' --tests 'com.datacube.config.CredentialCipherTest' --no-daemon --console=plain
git diff --check
```

预期全部相关测试通过。逐一人工检查新建/编辑 PG、Oracle、Redis 的字段显隐、标签名称和键盘顺序；用注入的失败操作观察反馈，不进行真实网络测试。provider 的 try-with-resources 和 Redis 的临时会话清理维持原实现；对照 diff 确认无 service/provider 改动，不声称验证了驱动立即停止。

- [ ] **Step 6: 审查并单独提交连接测试增量。**

```powershell
git add -- src/com/datacube/fx/ConnectionTestController.java src/com/datacube/fx/ConnectionDialog.java src/com/datacube/fx/ConnectionTreePane.java test/com/datacube/fx/ConnectionTestControllerTest.java test/com/datacube/fx/ConnectionDialogTest.java test/com/datacube/service/ConnectionManagerDedicatedSessionTest.java
git diff --cached --check
git diff --cached --stat
git commit -m 'feat: 为连接测试增加异步反馈和关闭保护'
```

## Task 3: SQL 连接提示与执行入口一致性

**Files:** 任务 3 的四个文件；原生产方法锚点 `toolbar`（626）、`renderInitialSessionState`（699）、`renderDisconnectedCandidate`（705）、`renderSessionSnapshot`（741）、`onExecute`（906）、`onExplain`（1280）、`setButtonsRunning`（1714）。

**Interfaces:**
- Consumes: `SessionContext.getActiveConnection()`、`SqlEditorConnectionAdmission.pinned()`、`SerialSessionOperationQueue.snapshot()`；不更改任何准入或固定连接接口。
- Produces: `SqlConnectionGuidance.from(ConnConfig pinned, ConnConfig candidate): SqlConnectionGuidance`，record 字段 `hasConnection, text`，方法 `blocksExecution(boolean busy)`。
- `SqlEditorPane` 新增私有 `guidance()`、`renderConnectionGuidance()`、`rejectMissingConnection()`，保持 public API 不变。

- [ ] **Step 1: 先写纯状态矩阵测试。**

`SqlConnectionGuidanceTest.java` 完整内容：

```java
package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlConnectionGuidanceTest {
    private static ConnConfig config(String name, DbType type) {
        return new ConnConfig(name, name, type, "example.invalid", type.defaultPort(),
                "db", "user", "secret", Map.of());
    }

    @Test void missingAndRedisCannotExecute() {
        var missing = SqlConnectionGuidance.from(null, null);
        assertFalse(missing.hasConnection());
        assertTrue(missing.text().contains("左侧"));
        assertTrue(missing.blocksExecution(false));
        var redis = SqlConnectionGuidance.from(null, config("redis", DbType.REDIS));
        assertFalse(redis.hasConnection());
        assertTrue(redis.text().contains("Redis"));
        assertTrue(redis.text().contains("控制台"));
    }

    @Test void candidatesRemainPendingAndBusyNeverReenablesExecution() {
        for (DbType type : new DbType[]{DbType.POSTGRESQL, DbType.ORACLE}) {
            var guidance = SqlConnectionGuidance.from(null, config("candidate", type));
            assertTrue(guidance.hasConnection());
            assertTrue(guidance.text().contains("首次执行或会话操作"));
            assertFalse(guidance.text().contains("已连接"));
            assertFalse(guidance.blocksExecution(false));
            assertTrue(guidance.blocksExecution(true));
        }
    }

    @Test void pinnedConnectionWinsOverMissingRedisOrOtherCandidate() {
        ConnConfig pinned = config("A", DbType.POSTGRESQL);
        for (ConnConfig candidate : new ConnConfig[]{null, config("B", DbType.ORACLE),
                config("redis", DbType.REDIS)}) {
            var guidance = SqlConnectionGuidance.from(pinned, candidate);
            assertTrue(guidance.hasConnection());
            assertEquals("", guidance.text());
        }
    }
}
```

运行红灯：

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.SqlConnectionGuidanceTest' --no-daemon --console=plain
```

- [ ] **Step 2: 实现纯投影，不能读取密码、存储或网络。**

`SqlConnectionGuidance.java` 完整内容：

```java
package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;

record SqlConnectionGuidance(boolean hasConnection, String text) {
    static SqlConnectionGuidance from(ConnConfig pinned, ConnConfig candidate) {
        if (pinned != null && pinned.type() != DbType.REDIS) {
            return new SqlConnectionGuidance(true, "");
        }
        if (candidate == null) {
            return new SqlConnectionGuidance(false,
                    "请先在左侧选择 PostgreSQL 或 Oracle 连接，再执行 SQL");
        }
        if (candidate.type() == DbType.REDIS) {
            return new SqlConnectionGuidance(false, "Redis 不支持 SQL，请使用其控制台");
        }
        return new SqlConnectionGuidance(true,
                "首次执行或会话操作将固定当前连接，之后切换左侧连接不影响此页");
    }

    boolean blocksExecution(boolean busy) { return busy || !hasConnection; }
}
```

- [ ] **Step 3: 写实际 SQL 页的失败测试。**

`SqlEditorConnectionGuidanceTest.java` 完整内容；未绑定页允许注入 null 连接服务，任何意外使用都会使测试失败。配置和历史均位于临时目录。

```java
package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorConnectionGuidanceTest {
    @TempDir Path directory;

    @Test void missingAndRedisPagesExplainNextStepWithoutCallingConnectionServices() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            SessionContext context = new SessionContext();
            SqlEditorPane pane = FxUiTestSupport.call(() -> new SqlEditorPane(
                    context, null, null, new AppSettings(directory.resolve("settings.properties")),
                    (id, table) -> fail("must not open a designer"), null, null,
                    new SqlHistoryStore(directory.resolve("history.txt")),
                    new ShortcutSettings(directory.resolve("shortcuts.properties")), runner));
            try {
                FxUiTestSupport.call(() -> {
                    var root = pane.getNode();
                    assertTrue(root.lookup("#sql-execute").isDisabled());
                    assertTrue(root.lookup("#sql-explain").isDisabled());
                    assertFalse(root.lookup("#sql-format").isDisabled());
                    assertTrue(((Label) root.lookup("#sql-connection-guidance"))
                            .getText().contains("左侧"));
                    assertFalse(root.lookup("#sql-environment").isVisible());
                    pane.setSqlText("SELECT 1");
                    CodeArea editor = (CodeArea) root.lookup("#sql-editor");
                    Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5,
                            false, false, false, false));
                    ((Button) root.lookup("#sql-explain")).fire();
                    context.setActiveConnection(new ConnConfig("redis", "redis", DbType.REDIS,
                            "example.invalid", 6379, "0", "", "", Map.of()));
                    assertTrue(root.lookup("#sql-execute").isDisabled());
                    assertTrue(((Label) root.lookup("#sql-connection-guidance"))
                            .getText().contains("Redis"));
                    return null;
                });
            } finally {
                var closed = FxUiTestSupport.call(pane::requestClose);
                closed.toCompletableFuture().get(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            }
        }
    }
}
```

先运行确认缺少稳定控件 ID/禁用状态的行为失败（不是因 NPE 出现在测试查找处就停止补齐断言）：

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.SqlEditorConnectionGuidanceTest' --no-daemon --console=plain
```

- [ ] **Step 4a: 添加提示控件及投影方法。**

`SqlEditorPane` 新增字段及方法：

```java
private Label connectionGuidance;

private SqlConnectionGuidance guidance() {
    return SqlConnectionGuidance.from(admission.pinned(), session.getActiveConnection());
}

private void renderConnectionGuidance() {
    if (connectionGuidance == null) return;
    SqlConnectionGuidance state = guidance();
    connectionGuidance.setText(state.text());
    connectionGuidance.setVisible(!state.text().isEmpty());
    connectionGuidance.setManaged(!state.text().isEmpty());
    environmentBadge.setVisible(state.hasConnection());
    environmentBadge.setManaged(state.hasConnection());
    readOnlyBadge.setVisible(state.hasConnection());
    readOnlyBadge.setManaged(state.hasConnection());
}

private boolean rejectMissingConnection() {
    if (guidance().hasConnection()) return false;
    renderConnectionGuidance();
    setButtonsRunning(false);
    return true;
}
```

在 toolbar 中相应控件初始化后设置稳定 ID；原最后 `return new VBox(4, primary, safety)` 替换为以下末四行：

```java
executeBtn.setId("sql-execute");
explainBtn.setId("sql-explain");
formatBtn.setId("sql-format");
environmentBadge.setId("sql-environment");

connectionGuidance = new Label();
connectionGuidance.setId("sql-connection-guidance");
connectionGuidance.setWrapText(true);
return new VBox(4, primary, safety, connectionGuidance);
```

在 `editor()` 的 `editorArea = new CodeArea()` 后添加 `editorArea.setId("sql-editor");`。不要更改现有快捷键注册和美化函数。

- [ ] **Step 4b: 组合现有状态，不能用新提示改写执行准入。**

用以下完整方法替换 `setButtonsRunning`：

```java
private void setButtonsRunning(boolean isRunning) {
    var operation = sessionOperations.snapshot();
    boolean busy = isRunning || running || !operation.accepting() || operation.pending();
    boolean disabled = guidance().blocksExecution(busy);
    executeBtn.setDisable(disabled);
    explainBtn.setDisable(disabled);
    formatBtn.setDisable(busy);
    clearBtn.setDisable(busy);
}
```

`renderDisconnectedCandidate` 做三处局部修改：

```java
// 无关系型候选分支：替换 environmentBadge.setText("开发")。
environmentBadge.setText("");

// 替换 transactionModeBox.setDisable(...)。
var operation = sessionOperations.snapshot();
transactionModeBox.setDisable(!guidance().hasConnection() || running
        || !operation.accepting() || operation.pending());

// 在方法结束前追加。
renderConnectionGuidance();
setButtonsRunning(false);
```

`renderSessionSnapshot` 在现有 `setButtonsRunning(busy)` 之前追加 `renderConnectionGuidance();`。`renderInitialSessionState` 保留原 session 分支；候选提示的 Redis 判断由 `guidance()` 读取原活动连接，而非被 `currentConn()` 过滤后的值。

`onExecute` 和 `onExplain` 在各自最开始的 busy 守卫之后、读取 SQL 之前，分别插入同一行：

```java
if (rejectMissingConnection()) return;
```

必须保留之后 `admitCurrentConnection()` 的 try/catch、安全策略校验和 sessionOperations 提交路径。新守卫只解释“缺少连接”；关闭时的拒绝仍由现有准入机制负责，不能捕获后继续执行。

- [ ] **Step 5: 新测试绿灯与既有绑定、事务、关闭竞态回归。**

```powershell
.\gradlew.bat test --tests 'com.datacube.fx.SqlConnectionGuidanceTest' --tests 'com.datacube.fx.SqlEditor*' --tests 'com.datacube.fx.task.SerialSessionOperationQueueTest' --tests 'com.datacube.service.JdbcEditorSessionTest' --tests 'com.datacube.service.ConnectionManagerDedicatedSessionTest' --no-daemon --console=plain
git diff --check
```

预期：纯投影矩阵、真实 SQL 空页和事务关闭保护均通过。固定连接 A 后选择 B 的证据是既有 `SqlEditorConnectionAdmissionTest.firstRelationalAdmissionPinsAndLaterActionsCannotSwitchConnection`；准入后关闭不得发布会话的证据是同类 `closeAfterExecuteAdmissionButBeforeSessionPublicationCannotCreateSession`。记录具体用例的结果，不以测试类名代替已通过的证据。

- [ ] **Step 6: 审查并单独提交 SQL 引导增量。**

```powershell
git add -- src/com/datacube/fx/SqlConnectionGuidance.java src/com/datacube/fx/SqlEditorPane.java test/com/datacube/fx/SqlConnectionGuidanceTest.java test/com/datacube/fx/SqlEditorConnectionGuidanceTest.java
git diff --cached --check
git diff --cached --stat
git commit -m 'feat: 明确 SQL 连接提示与执行可用状态'
```

## 最终回归与交付

- [ ] 在 Windows 有显示环境运行全量测试并检查新增套件未被跳过：

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
git diff --check
git status --short
Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml' |
    ForEach-Object {
        [xml]$report = Get-Content -LiteralPath $_.FullName
        [pscustomobject]@{Suite=$report.testsuite.name; Tests=$report.testsuite.tests;
            Failures=$report.testsuite.failures; Errors=$report.testsuite.errors;
            Skipped=$report.testsuite.skipped}
    }
```

预期：没有失败或错误；新增控件测试在本机实际执行。Linux 无显示环境的跳过必须单独报告，不能把跳过算通过，也不通过改 CI、关测试或缩短断言来消除失败。

- [ ] 依照 Computer Use 技能启动当前源码，验证启动、打开/关闭空 SQL、打开/取消新建连接、深浅主题下长提示不裁切及键盘焦点；不展开真实连接、不测试真实网络、不执行 SQL。保存本次截图到 `build/product-verification/2026-08-28/`，不能把实施前截图当成新功能证据。
- [ ] 检查 scope 关闭、失败/成功提示和重复点击的确定性测试结果；对话框隐藏后不应出现新弹窗。不承诺实际驱动取消耗时。
- [ ] 对照设计第 5 节逐项记录：通过、失败、跳过或未验证。产品验收记录写入 `docs/superpowers/verification/2026-08-28-connection-onboarding.md`，记录真实提交、命令、结果与截图路径；没有运行的数据不填数字。
- [ ] 报告实际改动、测试结果、遗留风险及各任务提交。保持 `.testagent/` 原状，不自动推送、不打 tag。

## 计划自审映射

| 设计要求 | 执行位置 |
| --- | --- |
| 空工作区与两个无 I/O 入口 | Task 1 Step 1–4 |
| 最后标签关闭后恢复，拒绝关闭不替换内容 | Task 1 真实控件测试及既有受管标签回归 |
| 异步、单请求、null/错误字符串语义 | Task 2 Step 1–2、Step 5 |
| 独立保存、失败保留输入、修改失效 | Task 2 Step 3–4 |
| scope 所有权、隐藏清理、迟到回调、任务拒绝 | Task 2 控制器测试、真实 scope 测试及既有 FxTaskScopeTest |
| 凭据不出现在新提示或日志 | Task 2 固定文案及敏感值哨兵断言 |
| 字段标签与键盘焦点 | Task 2 linkFormLabels、warn 聚焦及最终人工验收 |
| 无连接/Redis 不可 SQL 执行 | Task 3 纯矩阵与实际编辑页测试 |
| 候选/固定连接区分、忙碌/关闭状态不被覆盖 | Task 3 Step 4b–5 |
| 不改变 provider 临时资源和事务清理规则 | Task 2/3 服务层回归、逐文件 diff 检查 |
| 主题与全量回归 | 最终回归与交付 |

执行检查点：每个任务测试通过后进行代码审查再进入下一个任务。执行方式由用户确认；可选择本任务内顺序执行，或按任务交给子代理并逐项审查。
