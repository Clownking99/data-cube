# Task 6 报告：安全 SQL 会话 UI 集成

## 摘要

- `SqlEditorPane` 为每个关系型编辑标签持有一个 `JdbcEditorSession`；已有绑定连接在构造期
  创建会话，未绑定标签在第一次关系型执行/事务操作时 pin，之后不再跟随树选择。
- `openEditorSession(editorConnection)` 直接消费不可变 pinned `ConnConfig` 快照，且调用后立即登记
  `construction.ownBlocking(jdbcSession::close)`；构造失败的阻塞补偿由 Task 5 的 tracked
  mandatory-abort 虚拟线程承接，正常关闭由 Pane 后台关闭序列承接。
- 执行与 Explain 在 FX 线程完成 `SqlSafetyAnalyzer`/`SqlSafetyPolicy` 分析和确认后，才把
  历史文件写入与 JDBC 工作提交给虚拟线程。生产环境确认按钮为“确认在生产环境执行”。
- 新增环境、只读、连接、事务模式、提交、回滚、取消和超时状态；事务状态与结果状态分区
  渲染。`CANCELLED` 显示“已取消”，`TIMEOUT` 显示“执行超时”。
- 关闭请求在 FX 线程原子停止 admission。可取消的 execute/Explain 才提供“取消执行、
  回滚并关闭 / 取消关闭”；当前 COMMIT/ROLLBACK/SET_MODE 先异步等待结束，再回 FX 基于
  fresh session snapshot 决定是否展示“提交并关闭 / 回滚并关闭 / 取消”。用户拒绝为可重试
  `REJECTED`；清理开始后的不可重试失败为缓存的 `FAILED_PARTIAL`，JDBC strict close
  失败则保持未结算并在受追踪通道中继续重试。
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
  绕过队列。Queue snapshot 显式携带 current kind/cancellable；操作 pending 或 close admission
  期间执行和事务控件均禁用。
- 关闭 admission 在 FX 线程先停止接收，取消未开始的事务命令，将 current/queued 纳入
  关闭对话，后台等待 queue idle，最终从当前真实 session 做事务决策和清理。
- `SqlHistoryStore.recordStrict` 对写入失败可观测并回滚内存快照；
  `JdbcEditorSession.closeStrict` 会上报 JDBC close 失败且保留 cleanup reference 供重试。
  正常关闭与 mandatory abort 共享一个单飞 `StrictCleanupRetryChannel` settlement；
  close 失败会在虚拟线程中继续重试，CloseAttempt 在成功前保持 closing/
  `STILL_CLOSING`，不会假 `APPROVED`。

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

第二轮复审 RED：

```powershell
.\gradlew.bat test --tests com.datacube.service.ConnectionManagerDedicatedSessionTest --tests com.datacube.fx.task.SerialSessionOperationQueueTest --tests com.datacube.fx.StrictCleanupRetryChannelTest --tests com.datacube.fx.SqlEditorSessionContractTest --rerun-tasks --no-daemon --console=plain
```

结果：测试编译按预期产生 19 个缺失符号/签名错误，覆盖 `ConnConfig` snapshot overload、
typed queue snapshot 与 strict retry channel。核心纯 Java 层 GREEN 后，Pane close 合同单独运行
9 tests 仍有 2 failures，证明尚未等待不可取消操作/fresh FX 决策且未共享 strict
settlement。迁移后两项 GREEN。关闭等待期 terminal callback 重启控件的内部审计合同先
1 failure，将 `!accepting` 纳入 busy 后 GREEN。

## GREEN

最终聚焦强制重跑：

```powershell
.\gradlew.bat test --tests com.datacube.service.ConnectionManagerDedicatedSessionTest --tests com.datacube.fx.task.SerialSessionOperationQueueTest --tests com.datacube.fx.StrictCleanupRetryChannelTest --tests com.datacube.fx.SqlEditorClosePolicyTest --tests com.datacube.fx.SqlEditorSessionContractTest --tests com.datacube.fx.SqlEditorPaneLifecycleTest --tests com.datacube.fx.Task6ConstructionPlanContractTest --tests com.datacube.fx.AsyncCloseGateTest --tests com.datacube.fx.AsyncTabCloseCoordinatorTest --tests com.datacube.fx.AsyncManagedTabRegistryTest --tests com.datacube.service.JdbcEditorSessionTest --tests com.datacube.config.SqlHistoryStoreStrictTest --tests com.datacube.service.SqlSafetyPolicyTest --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，8/8 Gradle tasks 实际执行；XML 汇总 90 tests、0 failures、
0 errors、0 skipped。聚焦包名为实际的 `com.datacube.service.SqlSafetyPolicyTest`。

最终全量强制重跑：

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，8/8 Gradle tasks 实际执行；测试 XML 汇总 391 tests、0 failures、
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
- Task 6 第一轮审查修复：`968d43d25b113a3d818c2512fd8ef8ebd3241826`
- Task 6 第二轮审查修复：本报告所在的 `fix: 固化 SQL 会话配置与关闭结算` 提交；完整 SHA 由
  提交后的 `git rev-parse HEAD` 记录在交付简报中。
- 未 push。

## 风险

- 驱动若永久忽略 cancel，关闭 attempt 会保持 `STILL_CLOSING`，不会误批准或提前移除标签；
  需要等待底层 JDBC 终止或应用退出清理完成。
- strict 历史写入失败仍使当次关闭为 `FAILED_PARTIAL`；JDBC close 失败则保留 cleanup
  reference 并由单飞 channel 重试。驱动若永久失败，settlement 保持未完成且标签继续
  closing/由注册表追踪；不会误报 `APPROVED`，但需外部修复驱动/网络状态才能结算。
- closing 只取消未开始的会话命令；当前 JDBC 操作需先响应 cancel 并进入 idle，才能执行
  关闭事务决策。这是保持 commit/rollback 与执行严格顺序所必需的等待点。
- 安全分析器按既有保守词法策略工作，不是完整 SQL parser；未知语句和生产写入仍会要求确认，
  会话状态冲突与只读违规仍会直接阻止。
- 源码合同测试用于锁定所有权紧邻顺序和 UI 编排；JDBC 事务/取消的运行时语义继续由
  `JdbcEditorSessionTest` 与 provider execution-control 测试覆盖。
