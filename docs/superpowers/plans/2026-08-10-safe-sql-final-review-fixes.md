# 安全 SQL 会话终审修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复全分支终审发现的事务关闭、扩展事务语法、辅助查询取消、应用退出和空脚本状态问题，使安全 SQL 会话满足已确认的方案 A。

**Architecture:** 事务决策成为破坏性资源清理前的硬门禁；普通标签关闭和应用退出分别使用交互式与 mandatory 守卫，但共享同一标签所有权协调器。SQL 分析器只放行状态机能完整接管的标准事务完成语句；PostgreSQL/Oracle 列注释查询作为独立受控 Statement 阶段接入 timeout/cancel。

**Tech Stack:** JDK 25、JavaFX 25、JDBC、JUnit 5、Gradle、JDK 虚拟线程、CodeGraph。

## Global Constraints

- 直接在 `main` 工作树推进；不得创建或切换分支。
- Windows 为主平台，同时保留 Linux/macOS 可编译运行；不得写入 Windows 专用业务路径。
- 阻塞 JDBC 清理继续运行在 JDK 25 虚拟线程或既有 `FxTaskRunner`，不得阻塞 JavaFX Application Thread。
- 不新增第三方依赖，不改变 jlink、G1、`-Xms16m`、`-Xmx256m` 和跨平台 launcher 配置。
- 保留 `ConnectionManager.acquire`、旧 `SqlRunner` overload、Redis 和非 SQL 标签的兼容行为。
- `.testagent/` 是用户拥有的未跟踪目录：不得读取、修改、暂存或提交。
- 不在测试、报告或提交中写入密码、完整连接 URL 或用户提供的 Redis 凭据。
- 每个生产改动必须先有确定性 RED，再做最小 GREEN；每个任务独立提交并经 fresh reviewer 审查。
- 理解代码和调用路径时先使用 CodeGraph。

---

### Task 1: 封堵扩展事务语法并修正空脚本事务状态

**Files:**

- Modify: `src/com/datacube/sqleditor/SqlSafetyAnalyzer.java`
- Modify: `src/com/datacube/sqleditor/SqlSafetyPolicy.java`
- Modify: `src/com/datacube/service/JdbcEditorSession.java`
- Test: `test/com/datacube/sqleditor/SqlSafetyAnalyzerTest.java`
- Test: `test/com/datacube/sqleditor/SqlSafetyPolicyTest.java`
- Test: `test/com/datacube/service/JdbcEditorSessionTest.java`

**Interfaces:**

- Consumes: `SqlSafetyAnalyzer.transactionCompletionKeyword(String, boolean)`，仅对唯一标准 `COMMIT`/`ROLLBACK` 返回关键字。
- Produces: 所有未被状态机接管的 `TRANSACTION_CONTROL` 都携带 `Risk.SESSION_STATE_CONFLICT`；`JdbcEditorSession.updateTransactionState(List<ScriptOutcome>)` 对空列表保持原状态。

- [x] **Step 1: 写扩展事务语法的失败测试**

在 analyzer/policy 测试中加入 PostgreSQL 与 Oracle 模式矩阵：

```java
@Test
void extendedTransactionCompletionIsAlwaysAStateConflict() {
    for (boolean oracleMode : List.of(false, true)) {
        for (String sql : List.of(
                "commit work", "rollback work", "commit and chain", "rollback and no chain")) {
            var statement = SqlSafetyAnalyzer.analyze(sql, oracleMode).statements().getFirst();
            assertEquals(SqlSafetyAnalyzer.StatementKind.TRANSACTION_CONTROL, statement.kind());
            assertTrue(statement.risks().contains(
                    SqlSafetyAnalyzer.Risk.SESSION_STATE_CONFLICT), sql);
            var decision = SqlSafetyPolicy.decide(
                    SqlSafetyAnalyzer.analyze(sql, oracleMode),
                    new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, false, 60));
            assertTrue(decision.blocked(), sql);
            assertFalse(decision.confirmationRequired(), sql);
        }
    }
}

@Test
void canonicalCommitAndRollbackRemainOwnedByTheSession() {
    for (String sql : List.of("commit", "rollback", "/*x*/ commit;", "rollback; --x")) {
        var statement = SqlSafetyAnalyzer.analyze(sql, false).statements().getFirst();
        assertFalse(statement.risks().contains(
                SqlSafetyAnalyzer.Risk.SESSION_STATE_CONFLICT), sql);
    }
}
```

- [x] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.sqleditor.SqlSafetyAnalyzerTest --tests com.datacube.sqleditor.SqlSafetyPolicyTest --rerun-tasks --no-daemon --console=plain
```

Expected: `COMMIT WORK`/`ROLLBACK WORK` 未携带 `SESSION_STATE_CONFLICT`，测试失败。

- [x] **Step 3: 最小实现精确事务完成判定**

在 `analyzeStatement` 完成 kind 分类后使用同一 lexer helper；标准完成语句不加风险，其他事务控制一律冲突：

```java
if (kind == StatementKind.TRANSACTION_CONTROL
        && transactionCompletionKeyword(sql, oracleMode).isEmpty()) {
    risks.add(Risk.SESSION_STATE_CONFLICT);
}
```

删除只枚举 `BEGIN/START/SET/SAVEPOINT/RELEASE` 的旧风险分支。`SqlSafetyPolicy.isCommitOrRollback` 不得仅看 `firstKeyword`，改为只信任 analyzer 已确认没有 session conflict 的标准完成语句：

```java
private static boolean isCommitOrRollback(SqlSafetyAnalyzer.StatementAnalysis statement) {
    return statement.kind() == SqlSafetyAnalyzer.StatementKind.TRANSACTION_CONTROL
            && !statement.risks().contains(SqlSafetyAnalyzer.Risk.SESSION_STATE_CONFLICT)
            && ("COMMIT".equals(statement.firstKeyword())
            || "ROLLBACK".equals(statement.firstKeyword()));
}
```

- [x] **Step 4: 写空脚本不制造事务的失败测试**

复用 `JdbcEditorSessionTest` 的 JDBC stub，runner 对纯 trivia 返回空 outcome：

```java
@Test
void manualTriviaOnlyScriptKeepsIdleTransaction() throws Exception {
    JdbcStub jdbc = new JdbcStub();
    SqlRunner runner = new SqlRunner() {
        @Override
        public QueryResult execute(Connection connection, String sql, String schema,
                SqlExecutionOptions options) {
            return QueryResult.update(1, 0);
        }

        @Override
        public List<ScriptOutcome> executeScript(Connection connection, String script,
                String schema, SqlExecutionOptions options, ScriptErrorPolicy policy) {
            return List.of();
        }

        @Override
        public QueryResult explain(Connection connection, String sql, String schema,
                boolean analyze, SqlExecutionOptions options) {
            return QueryResult.update(1, 0);
        }
    };
    JdbcEditorSession session = new JdbcEditorSession(
            "conn", ConnectionSafetyOptions.from(config()), jdbc::open, runner);
    session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

    session.executeScript("-- comment only\n/* still trivia */", null, 100, null, false);

    assertEquals(JdbcEditorSession.TransactionState.IDLE,
            session.snapshot().transactionState());
    assertEquals(0, jdbc.commits.get());
    assertEquals(0, jdbc.rollbacks.get());
    session.close();
}
```

- [x] **Step 5: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.service.JdbcEditorSessionTest --rerun-tasks --no-daemon --console=plain
```

Expected: 纯注释执行后状态错误变为 `ACTIVE`。

- [x] **Step 6: 对空 outcome 保持事务状态**

在 `updateTransactionState` 的 manual 判断之后立即返回：

```java
private void updateTransactionState(List<ScriptOutcome> outcomes) {
    if (transactionMode != TransactionMode.MANUAL || outcomes.isEmpty()) return;
    // 保留既有 failure/ACTIVE 计算和 transactionConnection 所有权逻辑。
}
```

- [x] **Step 7: 运行聚焦与全量测试**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.sqleditor.SqlSafetyAnalyzerTest --tests com.datacube.sqleditor.SqlSafetyPolicyTest --tests com.datacube.service.JdbcEditorSessionTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL；标准 `COMMIT`/`ROLLBACK`、既有 BEGIN/CTE/方言词法测试无回归。

- [x] **Step 8: 提交**

```powershell
git add -- src/com/datacube/sqleditor/SqlSafetyAnalyzer.java src/com/datacube/sqleditor/SqlSafetyPolicy.java src/com/datacube/service/JdbcEditorSession.java test/com/datacube/sqleditor/SqlSafetyAnalyzerTest.java test/com/datacube/sqleditor/SqlSafetyPolicyTest.java test/com/datacube/service/JdbcEditorSessionTest.java
git diff --cached --check
git commit -m "fix: 封堵事务语法状态分叉"
```

---

### Task 2: 将列注释查询纳入 timeout/cancel 控制

**Files:**

- Modify: `src/com/datacube/provider/postgres/PgSqlRunner.java`
- Modify: `src/com/datacube/provider/postgres/PgColumnComments.java`
- Modify: `src/com/datacube/provider/oracle/OracleSqlRunner.java`
- Modify: `src/com/datacube/provider/oracle/OracleColumnComments.java`
- Test: `test/com/datacube/provider/postgres/PgSqlRunnerExecutionControlTest.java`
- Test: `test/com/datacube/provider/oracle/OracleSqlRunnerExecutionControlTest.java`

**Interfaces:**

- Consumes: `SqlExecutionOptions(int maxRows, int queryTimeoutSeconds, SqlExecutionControl control)` 和 token 化 `activate/release`。
- Produces: `PgColumnComments.resolve(Connection, ResultSetMetaData, SqlExecutionOptions)` 与 `OracleColumnComments.resolve(Connection, ResultSetMetaData, SqlExecutionOptions)`；每个辅助 PreparedStatement 都有自己的 activation。

- [x] **Step 1: 写阻塞列注释查询的失败测试**

为两个 runner 的动态代理测试增加同一时序断言：主 Statement 已执行并释放；列注释 PreparedStatement 成为 active owner；取消只命中辅助 Statement。

```java
@Test
void cancelDuringColumnCommentsTargetsTheCommentStatement() throws Exception {
    BlockingCommentJdbc jdbc = new BlockingCommentJdbc();
    SqlExecutionControl control = new SqlExecutionControl();
    SqlExecutionOptions options = new SqlExecutionOptions(100, 7, control);
    AtomicReference<QueryResult> result = new AtomicReference<>();

    Thread worker = Thread.startVirtualThread(
            () -> result.set(runner.execute(jdbc.connection(), "select id from t", null, options)));
    assertTrue(jdbc.commentQueryStarted.await(2, TimeUnit.SECONDS));
    assertTrue(control.cancel());
    worker.join(2_000);

    assertFalse(worker.isAlive());
    assertEquals(0, jdbc.mainStatementCancels.get());
    assertEquals(1, jdbc.commentStatementCancels.get());
    assertEquals(7, jdbc.commentQueryTimeout.get());
}
```

再覆盖 comment PreparedStatement 的 `executeQuery()` 抛 `SQLTimeoutException` 时 runner 有界返回且 activation 被释放；列注释仍是 best-effort，可返回无注释的主结果。

- [x] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat test --tests com.datacube.provider.postgres.PgSqlRunnerExecutionControlTest --tests com.datacube.provider.oracle.OracleSqlRunnerExecutionControlTest --rerun-tasks --no-daemon --console=plain
```

Expected: comment PreparedStatement 没有 timeout/activation，取消命中错误 Statement 或 worker 不结束。

- [x] **Step 3: 扩展列注释 resolver 接口**

两个 helper 都接收 `SqlExecutionOptions`，并在每个 PreparedStatement 周围使用独立 activation：

```java
static List<String> resolve(
        Connection connection,
        ResultSetMetaData metadata,
        SqlExecutionOptions options) {
    // 保留现有 metadata 映射和 best-effort null 语义。
}

private static Map<String, String> queryComments(
        Connection connection,
        Set<String> keys,
        SqlExecutionOptions options) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        int parameter = 1;
        for (String key : keys) {
            int separator = key.indexOf('\u0000');
            statement.setString(parameter++, key.substring(0, separator));
            statement.setString(parameter++, key.substring(separator + 1));
        }
        var activation = options.control().activate(
                statement, options.queryTimeoutSeconds());
        try {
            options.control().ensureNotCancelled(activation);
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, String> comments = new HashMap<>();
                while (resultSet.next()) {
                    String description = resultSet.getString("descr");
                    if (description == null || description.isEmpty()) continue;
                    comments.put(resultSet.getString("s") + '\u0000'
                            + resultSet.getString("t") + '\u0000'
                            + resultSet.getString("col"), description);
                }
                return comments;
            }
        } finally {
            options.control().release(activation);
        }
    }
}
```

上面是 PostgreSQL 的完整查询循环。Oracle helper 保留现有参数绑定和结果读取，但其两处 `executeQuery()` 都必须由同样的 `activate -> ensureNotCancelled -> executeQuery -> release` try/finally 包裹，不能只修其中一条。

- [x] **Step 4: 在 runner 中先释放主 activation，再解析注释**

读取主 `ResultSet` 和 metadata 后保存 `QueryResult`，释放主 activation，再调用新 resolver：

```java
QueryResult result = QueryResult.fromResultSet(resultSet, elapsed, options.maxRows());
options.control().release(mainActivation);
mainActivation = null;
List<String> comments = PgColumnComments.resolve(connection, metadata, options);
return comments == null ? result : result.withColumnComments(comments);
```

外层 `finally` 只在 `mainActivation != null` 时释放。Oracle runner 做同样调整；任何异常和提前返回都仍释放当前 token。

- [x] **Step 5: 运行聚焦与全量测试**

```powershell
.\gradlew.bat test --tests com.datacube.provider.postgres.PgSqlRunnerExecutionControlTest --tests com.datacube.provider.oracle.OracleSqlRunnerExecutionControlTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
```

Expected: 两个 provider 的取消、超时、schema、execute/explain/script 现有矩阵和新增注释查询矩阵全部通过。

- [x] **Step 6: 提交**

```powershell
git add -- src/com/datacube/provider/postgres/PgSqlRunner.java src/com/datacube/provider/postgres/PgColumnComments.java src/com/datacube/provider/oracle/OracleSqlRunner.java src/com/datacube/provider/oracle/OracleColumnComments.java test/com/datacube/provider/postgres/PgSqlRunnerExecutionControlTest.java test/com/datacube/provider/oracle/OracleSqlRunnerExecutionControlTest.java
git diff --cached --check
git commit -m "fix: 接管结果注释查询执行控制"
```

---

### Task 3: 建立事务关闭门禁和 mandatory 退出守卫

**Files:**

- Create: `src/com/datacube/fx/ManagedCloseMode.java`
- Create: `src/com/datacube/fx/SqlEditorCloseSequence.java`
- Modify: `src/com/datacube/fx/AsyncTabCloseCoordinator.java`
- Modify: `src/com/datacube/fx/AsyncManagedTabRegistry.java`
- Modify: `src/com/datacube/fx/ContentTabPane.java`
- Modify: `src/com/datacube/fx/SqlEditorPane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Test: `test/com/datacube/fx/AsyncTabCloseCoordinatorTest.java`
- Test: `test/com/datacube/fx/AsyncManagedTabRegistryTest.java`
- Test: `test/com/datacube/fx/ContentTabPaneLifecycleContractTest.java`
- Test: `test/com/datacube/fx/SqlEditorSessionContractTest.java`
- Test: `test/com/datacube/fx/SqlEditorCloseSequenceTest.java`
- Test: `test/com/datacube/fx/AppShellTest.java`

**Interfaces:**

- Produces: package-private `enum ManagedCloseMode { INTERACTIVE, MANDATORY }`。
- Produces: `AsyncTabCloseCoordinator.requestMandatoryClose()`，与普通 `requestClose()` 共享唯一 attempt/settlement。
- Produces: `AsyncManagedTabRegistry.closeAll(ManagedCloseMode)`；旧 `closeAll()` 委托 `INTERACTIVE`。
- Produces: `ContentTabPane.closeAllManagedTabsMandatory()`。
- Produces: `SqlEditorPane.requestMandatoryClose(): CompletionStage<CloseGuardOutcome>`，不显示对话且只回滚。
- Produces: `SqlEditorCloseSequence.run(Runnable, Runnable)`，事务 gate 成功后才调用 destructive cleanup。

- [x] **Step 1: 写事务决策失败必须阻止破坏性清理的 RED**

在 `SqlEditorSessionContractTest` 用源码合同加纯 Java seam 验证顺序：

```java
@Test
void failedCommitDoesNotRunHistoryScopeOrStrictCleanup() {
    List<String> events = new ArrayList<>();
    RuntimeException commitFailure = new RuntimeException("commit failed");

    RuntimeException actual = assertThrows(RuntimeException.class, () ->
            SqlEditorCloseSequence.run(
                    () -> { events.add("commit"); throw commitFailure; },
                    () -> events.add("destructive")));

    assertSame(commitFailure, actual);
    assertEquals(List.of("commit"), events);
}
```

新增 production seam，完整实现固定为：

```java
package com.datacube.fx;

import java.util.Objects;

final class SqlEditorCloseSequence {
    private SqlEditorCloseSequence() {}

    static void run(Runnable transactionGate, Runnable destructiveCleanup) {
        Objects.requireNonNull(transactionGate, "transactionGate").run();
        Objects.requireNonNull(destructiveCleanup, "destructiveCleanup").run();
    }
}
```

`SqlEditorPane` 必须实际调用该 seam；源码合同只补充断言 wiring 存在，不代替可执行顺序测试。

- [x] **Step 2: 运行并确认 RED**

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorSessionContractTest --rerun-tasks --no-daemon --console=plain
```

Expected: 当前 `BestEffortCloseSequence` 在 commit failure 后仍运行 strict cleanup。

- [x] **Step 3: 将普通关闭拆成事务门禁与破坏性清理两阶段**

`SqlEditorPane` 的后台流程必须等价于：

```java
private void closeInBackground(ClosePlan plan) {
    cancelCancellableCurrentSession();
    awaitSessionOperationsIdle();
    SqlEditorCloseSequence.run(
            () -> resolveCloseTransaction(currentEditorSession(), plan.decision()),
            () -> {
                sessionOperations.suppressCallbacks();
                BestEffortCloseSequence.run(
                        () -> persistCloseSnapshot(plan),
                        metadataTasks::close,
                        sessionOperations::close,
                        tasks::close,
                        this::awaitStrictSessionCleanup);
                resourcesClosed.set(true);
            });
}
```

交互式 `COMMIT`/`ROLLBACK` 门禁失败时：不设置 `resourcesClosed`，不持久化关闭快照，不关闭 scope/JDBC；重新打开 admission/queue，由 coordinator 以 retryable failure 结算并重新启用标签。门禁成功后发生的任何清理失败继续映射 `FAILED_PARTIAL`。

- [x] **Step 4: 写 mandatory guard 选择和唯一 settlement 的 RED**

```java
@Test
void mandatoryCloseUsesMandatoryGuardWithoutCallingInteractiveGuard() {
    AtomicInteger interactive = new AtomicInteger();
    AtomicInteger mandatory = new AtomicInteger();
    AsyncTabCloseCoordinator coordinator = new AsyncTabCloseCoordinator(
            () -> {
                interactive.incrementAndGet();
                return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
            },
            () -> {
                mandatory.incrementAndGet();
                return CompletableFuture.completedFuture(CloseGuardOutcome.APPROVED);
            },
            Duration.ofSeconds(5), new ManualTimeoutScheduler(), Runnable::run,
            () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, ignored -> {});

    assertEquals(TabCloseOutcome.COMPLETED,
            coordinator.requestMandatoryClose().settlement().toCompletableFuture().join());
    assertEquals(0, interactive.get());
    assertEquals(1, mandatory.get());
}
```

Registry 测试覆盖 `closeAll(MANDATORY)` 对所有 entry 只调用 mandatory guard；非 SQL entry 的 mandatory guard 默认与普通 guard 相同；reservation seal、timeout 和 FAILED_PARTIAL 聚合语义不变。

- [x] **Step 5: 实现 close mode 管道**

新增：

```java
enum ManagedCloseMode { INTERACTIVE, MANDATORY }
```

`AsyncTabCloseCoordinator` 保存 `interactiveGuard` 与 `mandatoryGuard`；兼容构造器令二者相同。新 attempt 创建时固定选中的 guard：

```java
CloseAttempt requestClose() {
    return requestClose(ManagedCloseMode.INTERACTIVE, null);
}

CloseAttempt requestMandatoryClose() {
    return requestClose(ManagedCloseMode.MANDATORY, null);
}

private AsyncTabCloseGuard guardFor(ManagedCloseMode mode) {
    return mode == ManagedCloseMode.MANDATORY ? mandatoryGuard : interactiveGuard;
}
```

`Attempt` 持有该 guard，`start(attempt)` 调用 `attempt.guard.requestClose()`；同一 coordinator 已有 attempt 时仍返回同一 settlement，不能并发运行两个 cleanup。

`AsyncManagedTabRegistry.closeAll(mode)` 在 snapshot 中分别调用 `requestClose()` 或 `requestMandatoryClose()`；旧 `closeAll()` 委托 interactive。

- [x] **Step 6: 扩展 ManagedTabSpec 与 ContentTabPane**

record 新增 mandatory guard，同时保留四参数兼容构造器：

```java
public record ManagedTabSpec(
        Node content,
        AsyncTabCloseGuard guard,
        AsyncTabCloseGuard mandatoryGuard,
        Runnable uiFinalizer,
        Runnable mandatoryAbortCleanup) {
    public ManagedTabSpec(Node content, AsyncTabCloseGuard guard,
            Runnable uiFinalizer, Runnable mandatoryAbortCleanup) {
        this(content, guard, guard, uiFinalizer, mandatoryAbortCleanup);
    }
}
```

`openReservedManagedTab` 把两个 guard 交给 coordinator；新增：

```java
public CompletionStage<TabCloseOutcome> closeAllManagedTabsMandatory() {
    return closeAllManagedTabs(ManagedCloseMode.MANDATORY);
}
```

原 `closeAllManagedTabs()` 保持 interactive 兼容；内部 barrier、MandatoryAbortTracker hard-seal、CANCELLED reopen 和 reservation 所有权逻辑不得复制或绕过。

- [x] **Step 7: 写 SqlEditorPane mandatory close RED**

覆盖以下行为：

```java
@Test
void mandatoryCloseNeverUsesTransactionDialogAndAlwaysRequestsRollback() {
    String source = sourceOf(SqlEditorPane.class);
    assertTrue(source.contains("requestMandatoryClose"));
    assertTrue(source.contains("CloseDecision.CANCEL_ROLLBACK"));
    assertFalse(methodBody(source, "startMandatoryCloseAttempt").contains("showAndWait"));
}
```

再用可执行 close seam 验证 mandatory rollback failure 返回 `FAILED_PARTIAL`、不执行 strict cleanup、不会变成 `CANCELLED` 或重新启用；成功 rollback 才进入 destructive cleanup。

- [x] **Step 8: 实现 SqlEditorPane mandatory close**

新增独立 guard 字段与公开入口：

```java
private final AsyncTabCloseGuard mandatoryCloseGuard;

public CompletionStage<CloseGuardOutcome> requestMandatoryClose() {
    if (!Platform.isFxApplicationThread()) {
        return CompletableFuture.failedFuture(new IllegalStateException(
                "SqlEditorPane.requestMandatoryClose must start on the FX Application Thread"));
    }
    return mandatoryCloseGuard.requestClose();
}
```

构造器用 retry-aware guard 包装 `startMandatoryCloseAttempt`。mandatory 路径与普通路径共享 admission、FIFO idle、close plan、strict cleanup 和 finalizer，但 close plan 不调用任何 Alert：有 pending transaction 或取消中的 SQL 时固定 `CANCEL_ROLLBACK`，否则 `CLOSE`。mandatory rollback failure 不继续 strict cleanup，并返回 `FAILED_PARTIAL` 让 registry 保持隔离；普通 commit/rollback failure 则恢复 admission/queue 并允许用户重试。

- [x] **Step 9: 将 AppShell 退出接到 mandatory close-all**

SQL 标签 spec 使用五参数 record：

```java
new ContentTabPane.ManagedTabSpec(
        pane.getNode(),
        pane::requestClose,
        pane::requestMandatoryClose,
        pane::finalizeCloseOnFx,
        pane::closeResources)
```

`AsyncShutdownCoordinator` 的第一阶段 supplier 改为 `contentTabs::closeAllManagedTabsMandatory`。其他标签继续使用四参数构造器，mandatory 与普通 blocking guard 相同。

- [x] **Step 10: 运行关闭生命周期聚焦测试**

```powershell
.\gradlew.bat test --tests com.datacube.fx.AsyncTabCloseCoordinatorTest --tests com.datacube.fx.AsyncManagedTabRegistryTest --tests com.datacube.fx.ContentTabPaneLifecycleContractTest --tests com.datacube.fx.SqlEditorSessionContractTest --tests com.datacube.fx.SqlEditorCloseSequenceTest --tests com.datacube.fx.AppShellTest --tests com.datacube.fx.AsyncShutdownCoordinatorTest --rerun-tasks --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL；交互关闭仍可取消，mandatory 退出不调用交互 guard，事务门禁失败不运行 destructive cleanup。

- [x] **Step 11: 运行全量测试并提交**

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add -- src/com/datacube/fx/ManagedCloseMode.java src/com/datacube/fx/SqlEditorCloseSequence.java src/com/datacube/fx/AsyncTabCloseCoordinator.java src/com/datacube/fx/AsyncManagedTabRegistry.java src/com/datacube/fx/ContentTabPane.java src/com/datacube/fx/SqlEditorPane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/AsyncTabCloseCoordinatorTest.java test/com/datacube/fx/AsyncManagedTabRegistryTest.java test/com/datacube/fx/ContentTabPaneLifecycleContractTest.java test/com/datacube/fx/SqlEditorSessionContractTest.java test/com/datacube/fx/SqlEditorCloseSequenceTest.java test/com/datacube/fx/AppShellTest.java
git diff --cached --check
git commit -m "fix: 分离交互关闭与退出回滚"
```

---

### Task 4: 同步文档并完成发布前验证

**Files:**

- Modify: `README.md`（仅在实现语义需要更精确措辞时）
- Modify: `docs/superpowers/plans/2026-08-09-safe-sql-session.md`
- Modify: `docs/superpowers/plans/2026-08-10-safe-sql-final-review-fixes.md`
- Report: `.superpowers/sdd/final-fixes-report.md`（ignored，不提交）

**Interfaces:**

- Consumes: Tasks 1-3 的最终 API 和测试证据。
- Produces: 与实际实现一致的用户说明、已勾选的修复计划、本地发布验证证据；不负责 push，push 由主控在最终 review 后执行。

- [x] **Step 1: 修正规格与旧实施计划冲突**

在旧计划的 Task 6 关闭步骤中明确：transaction resolve 是 pre-cleanup gate，失败不得继续 `BestEffortCloseSequence`；应用退出调用 mandatory close-all。不得把未完成的 PostgreSQL/Oracle live smoke 勾选为完成。

- [x] **Step 2: 运行完整验证**

```powershell
.\gradlew.bat clean test --no-daemon --console=plain
.\gradlew.bat jlink --no-daemon --console=plain
git diff --check
codegraph sync
codegraph status
Test-Path 'build/image/bin/DataCube.bat'
git status --short
```

Expected:

- clean test、jlink 均 `BUILD SUCCESSFUL`，零 failed test；
- Windows linked image launcher 存在，并继续包含 G1、`-Xms16m`、`-Xmx256m`；
- CodeGraph 为最新；
- `git diff --check` 无错误；
- status 只允许本任务文档改动和既有 `?? .testagent/`。

- [x] **Step 3: 运行安全与兼容聚焦矩阵**

```powershell
.\gradlew.bat test --tests com.datacube.sqleditor.SqlSafetyAnalyzerTest --tests com.datacube.sqleditor.SqlSafetyPolicyTest --tests com.datacube.service.JdbcEditorSessionTest --tests com.datacube.provider.postgres.PgSqlRunnerExecutionControlTest --tests com.datacube.provider.oracle.OracleSqlRunnerExecutionControlTest --tests com.datacube.fx.SqlEditorSessionContractTest --tests com.datacube.fx.AsyncManagedTabRegistryTest --tests com.datacube.redis.RedisLiveIntegrationTest --rerun-tasks --no-daemon --console=plain
```

Redis live test 只在主控提供环境变量时运行；不得把凭据写进命令报告、文件或 git。无 PostgreSQL/Oracle 非生产端点时继续记录 live residual，不猜测凭据。

- [x] **Step 4: 更新 checkbox、报告并提交文档**

只有上述命令实际成功后才勾选本计划对应步骤。记录精确测试数量、jlink、CodeGraph、remaining relational live residual 和 `.testagent/` 边界。

```powershell
git add -- README.md docs/superpowers/plans/2026-08-09-safe-sql-session.md docs/superpowers/plans/2026-08-10-safe-sql-final-review-fixes.md
git diff --cached --check
git commit -m "docs: 完成安全 SQL 终审修复说明"
```

若 README 无需修改，不得为制造 diff 而改写；只暂存实际变更文件。

- [x] **Step 5: 独立终审交接**

生成从 `d786207` 到新 HEAD 的 review package，fresh reviewer 必须复验原 1 Critical、3 Important、1 Minor，且最终报告 Critical/Important/Minor 均为零后才允许主控 push `main` 并观察 GitHub Verify。

---

## Plan Self-Review

- Spec coverage: Task 1 覆盖扩展事务语法和空脚本；Task 2 覆盖辅助查询 timeout/cancel；Task 3 覆盖事务关闭门禁和 mandatory 退出；Task 4 覆盖文档、完整构建与发布交接。
- Scope: 不增加方言级 `COMMIT WORK` 支持，不重写整个 lifecycle 状态机，不引入依赖或新数据库类型。
- Type consistency: `ManagedCloseMode`、双 guard `ManagedTabSpec`、`requestMandatoryClose()`、`closeAllManagedTabsMandatory()` 在 Task 3 中一次定义并贯穿调用方。
- Placeholder scan: 无占位步骤、模糊错误处理或未定义的后续接口。
- Safety: 事务 gate failure 不进入 strict cleanup；mandatory exit 不提供提交选项；辅助 Statement 始终有当前 activation。
