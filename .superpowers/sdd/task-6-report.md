# Task 6 报告：安全 SQL 会话 UI 集成

## 摘要

- `SqlEditorPane` 为每个关系型编辑标签持有一个 `JdbcEditorSession`；已有绑定连接在构造期
  创建会话，未绑定标签在第一次关系型执行/事务操作时 pin，之后不再跟随树选择。
- `openEditorSession(editorConnection.id())` 后立即登记
  `construction.ownBlocking(jdbcSession::close)`；构造失败的阻塞补偿由 Task 5 的 tracked
  mandatory-abort 虚拟线程承接，正常关闭由 Pane 后台关闭序列承接。
- 执行与 Explain 在 FX 线程完成 `SqlSafetyAnalyzer`/`SqlSafetyPolicy` 分析和确认后，才把
  历史文件写入与 JDBC 工作提交给虚拟线程。生产环境确认按钮为“确认在生产环境执行”。
- 新增环境、只读、连接、事务模式、提交、回滚、取消和超时状态；事务状态与结果状态分区
  渲染。`CANCELLED` 显示“已取消”，`TIMEOUT` 显示“执行超时”。
- 关闭请求在 FX 线程即时捕获草稿、会话快照和用户决定；running 会话提供“取消执行、回滚并
  关闭 / 取消关闭”，dirty 手动事务提供“提交并关闭 / 回滚并关闭 / 取消”。用户拒绝为可重试
  `REJECTED`；清理开始后的任一失败为缓存的 `FAILED_PARTIAL`。
- 后台关闭用 `BestEffortCloseSequence` 依次尝试 cancel、等待执行终止、选定的事务处理、历史
  持久化、metadata/task 两个 scope 和 JDBC close。FX finalizer 只移除监听器并隐藏补全 UI。
- `AppShell` 保持 reservation factory → abort binding → 初始化 → `ManagedTabSpec` 的发布顺序；
  通用“新建 SQL”遇活动 Redis 时打开未绑定标签，树上的 Redis 动作仍保持原提示/入口。
- Explain 保留首语句行为，并使用已选关系型连接的 Oracle 分句模式；执行、策略、schema 与
  `oracleMode` 都来自同一个被 pin 的连接配置。
- 审查修复将首次关系型操作收口到 FX admission 临界区：在安全分析和后台提交之前原子
  pin `ConnConfig`/connectionId，后续 safety、schema、Oracle 模式与专用会话只从 pinned
  配置派生；closing 阶段不得新建或发布会话。
- 新增每编辑器 `SerialSessionOperationQueue`：execute、Explain、事务模式、commit 和
  rollback 严格 FIFO 单飞，每项在 JDK 25 虚拟线程上运行；cancel 通过独立 task scope
  绕过队列。操作 pending 期间执行和事务控件均禁用。
- 关闭 admission 在 FX 线程先停止接收，取消未开始的事务命令，将 current/queued 纳入
  关闭对话，后台等待 queue idle，最终从当前真实 session 做事务决策和清理。
- `SqlHistoryStore.recordStrict` 对写入失败可观测并回滚内存快照；
  `JdbcEditorSession.closeStrict` 会上报 JDBC close 失败且保留 cleanup reference 供重试。
  正常关闭与 mandatory abort 都通过 strict best-effort 序列，任一失败都为
  `FAILED_PARTIAL`。

## RED

第一轮规格测试：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorPaneLifecycleTest --tests com.datacube.fx.SqlEditorSessionContractTest --no-daemon --console=plain
```

结果：6 tests 中 2 个按预期失败：编辑器未使用 `JdbcEditorSession`/安全策略且仍存在共享
`connections.acquire(connId)`；`openEditorSession` 与紧邻 `ownBlocking` 所有权登记缺失。

后续逐项 RED：

- pin 后停止跟随树选择元数据：1 test，1 failure；加 `editorConnection == null` guard 后 GREEN。
- running + AUTO_COMMIT 关闭不得误 rollback：1 test，1 failure；仅对手动 dirty 事务回滚后 GREEN。
- Explain 必须使用连接的 Oracle 分句模式：1 test，1 failure；传递 `DbType.ORACLE` 后 GREEN。

审查修复第一轮 RED：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorConnectionAdmissionTest --tests com.datacube.fx.task.SerialSessionOperationQueueTest --tests com.datacube.config.SqlHistoryStoreStrictTest --tests com.datacube.service.JdbcEditorSessionTest --tests com.datacube.fx.SqlEditorSessionContractTest --no-daemon --console=plain
```

结果：测试编译按预期 RED，共报告 15 处缺失 API/合同：atomic admission、FIFO queue、
strict history/JDBC cleanup 与 Pane 编排尚未存在。纯 Java 层转 GREEN 后，Pane 源码合同仍有
2 个预期 failure；迁移完成后全部 GREEN。内部审计又先加 RED 锁定关闭拒绝后的
pending/running 重算与 cancel 绕过 FIFO，然后完成最小实现转 GREEN。

## GREEN

最终聚焦强制重跑：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorPaneLifecycleTest --tests com.datacube.fx.SqlEditorSessionContractTest --tests com.datacube.fx.SqlEditorConnectionAdmissionTest --tests com.datacube.fx.task.SerialSessionOperationQueueTest --tests com.datacube.config.SqlHistoryStoreStrictTest --tests com.datacube.fx.task.AsyncCloseGateTest --tests com.datacube.service.JdbcEditorSessionTest --tests com.datacube.service.SqlSafetyPolicyTest --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，8/8 Gradle tasks 实际执行；所有聚焦类全部通过。

最终全量强制重跑：

```powershell
.\gradlew.bat test --no-daemon --console=plain --rerun-tasks
```

结果：`BUILD SUCCESSFUL`，8/8 Gradle tasks 实际执行；测试 XML 汇总 382 tests、0 failures、
0 errors、1 skipped。

静态检查：

```powershell
rg -n "connections\.acquire\(connId\)" src/com/datacube/fx/SqlEditorPane.java
git diff --check
git status --short
```

结果：共享 acquire 无匹配；`git diff --check` 退出 0（仅 Git 的 LF→CRLF 工作树提示）；
`.testagent/` 仍是任务开始前已有的未跟踪目录，未读取、修改、暂存或提交。

## SHA

- 基线：`1485ca9545af8136c3596551b701a888cb8b3eaa`
- Task 6 初始集成：`0d428f8f7157d0715c46419106c5fdb532f7d127`
- Task 6 审查修复：本报告所在的 `fix: 修复 SQL 会话并发与关闭` 提交；完整 SHA 由
  提交后的 `git rev-parse HEAD` 记录在交付简报中。
- 未 push。

## 风险

- 驱动若永久忽略 cancel，关闭 attempt 会保持 `STILL_CLOSING`，不会误批准或提前移除标签；
  需要等待底层 JDBC 终止或应用退出清理完成。
- strict 历史写入或 JDBC close 首次失败会使当次关闭为 `FAILED_PARTIAL`，标签注册表保留
  清理责任；JDBC cleanup reference 保留供后续 strict close 重试。当前 UI 不展示驱动私有
  cleanup reference 的细节。
- closing 只取消未开始的会话命令；当前 JDBC 操作需先响应 cancel 并进入 idle，才能执行
  关闭事务决策。这是保持 commit/rollback 与执行严格顺序所必需的等待点。
- 安全分析器按既有保守词法策略工作，不是完整 SQL parser；未知语句和生产写入仍会要求确认，
  会话状态冲突与只读违规仍会直接阻止。
- 源码合同测试用于锁定所有权紧邻顺序和 UI 编排；JDBC 事务/取消的运行时语义继续由
  `JdbcEditorSessionTest` 与 provider execution-control 测试覆盖。
