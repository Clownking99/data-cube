# DataCube v3.1 安全 SQL 会话设计

- 日期：2026-08-09
- 状态：待用户书面规格审核
- 目标平台：Windows 为主要正式发行平台，同时保留跨平台运行能力
- 交付方式：直接在 `main` 分阶段提交，每个阶段独立测试、验证和回退

## 1. 背景

DataCube 当前的 SQL 编辑器通过 `ConnectionManager.acquire(connId)` 获取按连接 ID 缓存的 JDBC 连接。多个 SQL 标签可能复用同一连接，因此会共享 `autoCommit`、当前 schema、事务和正在执行的语句。现状适合自动提交的短查询，但不具备安全实现手动事务、单标签取消和关闭确认的隔离边界。

本阶段把 SQL 编辑器从“共享连接上的执行界面”升级为“拥有独立 JDBC 会话的安全工作台”。连接树、数据网格、设计器、迁移和 Redis 继续使用现有连接管理方式，不在本阶段整体重写。

## 2. 已确认的产品与架构决策

曾评估三种方案：

1. 每个 SQL 标签独占 JDBC 会话：事务和取消完全隔离，但多标签会增加连接数。
2. 每个数据库连接共享 JDBC 会话：连接数少，但不同标签会互相污染事务和 schema。
3. 每次执行创建临时连接：执行隔离，但无法跨多次执行保留手动事务。

采用方案 1。用户已确认每个 SQL 标签独占 JDBC 会话。

其他约束：

- 阻塞 JDBC 操作继续使用 JDK 25 虚拟线程。
- JavaFX 控件只在 JavaFX Application Thread 访问。
- 不引入连接池、响应式数据库栈或第三方 SQL 解析器。
- 不降低数据库原生权限控制的重要性；客户端风险分析是防误操作能力，不是安全沙箱。
- 旧连接配置必须无损加载，Redis 配置和行为不得回归。

## 3. 本阶段目标

### 3.1 独立 SQL 会话

- 一个 SQL 标签最多持有一个惰性创建的专用 JDBC 连接。
- 标签创建后固定到一个连接配置；同一数据库的不同标签拥有不同 JDBC 连接。
- 关闭标签、断线或应用退出时显式释放专用连接。
- 连接树和其他功能仍可使用 `ConnectionManager.acquire` 的共享连接。

### 3.2 可见、可控的事务

- 支持自动提交与手动事务模式。
- 手动模式提供提交和回滚。
- UI 始终显示连接、环境、事务模式、事务状态和执行状态。
- 关闭存在未提交事务的标签时，用户可选择提交并关闭、回滚并关闭或取消关闭。
- 应用退出的无交互资源回收路径一律回滚未提交事务，不做隐式提交。

### 3.3 执行控制

- 正在执行的 SQL 支持取消。
- 每个关系型连接可配置查询超时。
- 取消和超时仅影响所属 SQL 标签，不影响其他标签或连接树。
- 驱动不支持或不响应 `Statement.cancel()` 时，关闭该标签的专用连接作为兜底，并将会话标记为需要重连。

### 3.4 防误操作

- 连接可标记为开发、测试或生产环境。
- 连接可设为只读。
- 检测无顶层 `WHERE` 的 `UPDATE`、`DELETE`。
- 检测 `DROP`、`TRUNCATE` 等破坏性 DDL。
- 生产环境的写入、DDL 和无法可靠分类的语句执行前必须确认。
- 只读连接阻止写入、DDL 和无法可靠分类的语句。

## 4. 明确不在本阶段的内容

- SSL/TLS、SSH 隧道、代理和云数据库发现。
- SQL 工作区恢复、文件项目、收藏和代码片段。
- Schema Compare、Data Compare、ER 图和会话监控。
- 保存点 UI、两阶段提交和分布式事务。
- SQL 自动重试、断线后自动恢复未提交事务。
- 修改 Redis 控制台的命令安全策略。
- 为 Oracle/PostgreSQL 实现完整语法树解析器。

## 5. 总体架构

新增四个清晰边界：

```text
SqlEditorPane
  ├─ SqlSafetyAnalyzer        纯函数，分析脚本风险
  ├─ JdbcEditorSession        专用连接、事务与执行生命周期
  ├─ SqlExecutionControl      当前 Statement、超时与取消
  └─ FxTaskScope              虚拟线程任务和关闭后回调抑制

ConnectionManager
  ├─ acquire(connId)          保留：共享连接，供现有功能使用
  └─ openDedicated(connId)    新增：返回调用方拥有的 JDBC 连接

SqlRunner implementations
  └─ 执行每个 Statement 时向 SqlExecutionControl 注册和解除注册
```

`SqlEditorPane` 只负责读取 UI 输入、显示状态和弹出确认框。连接、事务和取消行为放入不依赖 JavaFX 的 `JdbcEditorSession`，SQL 风险分析放入纯 Java 的 `SqlSafetyAnalyzer`，避免继续扩大 Pane 的业务职责。

## 6. 专用会话生命周期

### 6.1 绑定规则

- 从连接树打开 SQL 标签时，创建时即绑定该 `ConnConfig`。
- 从通用入口打开 SQL 标签时：
  - 若当前已有活动关系型连接，创建时立即绑定；
  - 若没有连接，标签以“未连接”状态打开，在第一次执行前绑定当时选中的关系型连接；
  - 一旦专用连接已经创建，该标签不再随左侧活动连接自动切换。
- Redis 连接不能绑定到 SQL 编辑器，继续使用 Redis 控制台。
- 编辑连接配置不会偷偷替换已打开标签的会话；新配置对新标签生效，已有标签关闭后重新打开才生效。

### 6.2 创建与重连

专用连接按第一次执行、切换事务模式或显式重连时惰性创建。创建步骤为：

1. 从 `ConnectionManager` 获取已注册配置并解密临时密码。
2. 通过对应 provider 的 `ConnectionFactory` 打开新 JDBC 连接，但不放入共享 `live` 映射。
3. 应用只读标记和自动提交模式。
4. 创建会话快照并通知 UI 更新。

连接失效或因取消兜底被关闭后，会话进入“已断开”状态。下一次执行可显式重连；未提交事务视为已丢失，必须显示明确提示，不能宣称自动恢复。

### 6.3 单飞行约束

同一 SQL 标签一次只允许一个执行、执行计划、提交或回滚操作。取消可以与当前操作并发发起。不同 SQL 标签可并发执行，其阻塞任务由共享 `FxTaskRunner` 的虚拟线程承载。

## 7. 事务模型

### 7.1 状态

会话对 UI 暴露不可变快照，至少包含：

- `connectionState`：`DISCONNECTED`、`CONNECTED`、`BROKEN`、`CLOSED`。
- `transactionMode`：`AUTO_COMMIT`、`MANUAL`。
- `transactionState`：`IDLE`、`ACTIVE`、`ERROR_PENDING`。
- `running` 和 `cancelling`。
- 当前连接 ID、环境、只读和查询超时。

执行状态与事务状态分开，避免用一个枚举组合出大量不可维护状态。

### 7.2 状态转换

- 新会话默认自动提交、事务 `IDLE`。
- 切换到手动模式时调用 `Connection.setAutoCommit(false)`，事务保持 `IDLE`。
- 手动模式执行任何非事务控制语句后进入 `ACTIVE`；即使返回错误，也保守地保留待处理事务。
- 手动模式执行失败后进入 `ERROR_PENDING`，允许回滚；提交仍交给数据库决定，UI 显示“建议回滚”。
- 提交或回滚成功后回到手动模式的 `IDLE`。
- 用户直接执行 `COMMIT` 或 `ROLLBACK` 时同步更新为 `IDLE`。
- 从手动切回自动提交：
  - `IDLE` 可直接切换；
  - `ACTIVE` 或 `ERROR_PENDING` 必须先选择提交、回滚或取消切换。
- Oracle DDL 可能隐式提交。客户端无法从 JDBC 可靠获知全部隐式提交场景，因此仍保守显示待处理状态；额外提交或回滚无副作用，但不能错误显示“已自动安全提交”。

### 7.3 脚本遇错

- 自动提交模式保留现有“继续 / 全部继续 / 取消”行为。
- 手动事务模式下，脚本任一语句失败后立即中止剩余语句，保留当前事务供用户检查、提交或回滚。
- 这样可避免 PostgreSQL 已进入 aborted transaction 后继续执行大量必然失败的语句，也让 Oracle/PostgreSQL 的 UI 行为一致。

## 8. SQL 风险分析

### 8.1 输出模型

`SqlSafetyAnalyzer` 对 `SqlScriptSplitter` 拆出的每条语句返回：

- 类型：`READ`、`WRITE`、`DDL`、`TRANSACTION_CONTROL`、`UNKNOWN`。
- 风险：`MISSING_WHERE`、`DESTRUCTIVE_DDL`、`PRODUCTION_WRITE`、`UNKNOWN_STATEMENT`、`SESSION_STATE_CONFLICT`。
- 用于确认框的语句序号、首个关键字和截断摘要。

### 8.2 词法规则

扫描器必须忽略字符串、引用标识符、行注释、块注释、PostgreSQL dollar-quoted 内容和 Oracle q-quoted 内容，并跟踪括号深度。`UPDATE`、`DELETE` 只有缺少顶层 `WHERE` 时才标记，子查询内部的 `WHERE` 不算外层条件。

首版识别：

- 读取：`SELECT`、`SHOW`、`DESCRIBE`、`DESC`、`VALUES` 和只读 `EXPLAIN`。
- 写入：`INSERT`、`UPDATE`、`DELETE`、`MERGE`、`CALL`、`DO`、`EXEC`、`EXECUTE`。
- DDL/权限：`CREATE`、`ALTER`、`DROP`、`TRUNCATE`、`RENAME`、`COMMENT`、`GRANT`、`REVOKE`。
- 事务控制：`BEGIN`、`START TRANSACTION`、`COMMIT`、`ROLLBACK`、`SET TRANSACTION`、`SAVEPOINT`、`RELEASE SAVEPOINT`。
- `WITH` 继续扫描到 CTE 之后的顶层主语句；不能可靠识别时返回 `UNKNOWN`。
- `EXPLAIN ANALYZE` 继承被分析语句的写入风险，因为它可能真实执行 DML。
- Oracle PL/SQL 块和无法确定副作用的调用统一按 `UNKNOWN` 或 `WRITE` 保守处理。

### 8.3 决策矩阵

| 场景 | 行为 |
|---|---|
| 只读连接 + READ | 允许 |
| 任意环境 + `COMMIT`/`ROLLBACK` | 允许，并同步会话状态 |
| 任意环境 + `BEGIN`/`START TRANSACTION`/`SET TRANSACTION`/保存点语句 | 阻止，要求使用会话事务控件 |
| 只读连接 + WRITE/DDL/UNKNOWN | 阻止 |
| 开发/测试 + 普通 WRITE | 允许 |
| 任意环境 + MISSING_WHERE | 必须确认 |
| 任意环境 + DESTRUCTIVE_DDL | 必须确认 |
| 生产环境 + WRITE/DDL/UNKNOWN | 必须确认 |
| 生产环境 + READ | 允许 |

确认只对本次执行有效，不提供“永久忽略”选项。多语句脚本一次性汇总全部风险，用户确认后才开始执行，避免执行到一半再发现后续危险语句。

事务模式由会话控件统一管理。除 `COMMIT`、`ROLLBACK` 外，用户直接输入的事务启动、事务属性和保存点语句标记为 `SESSION_STATE_CONFLICT` 并阻止执行，避免 JDBC `autoCommit` 与数据库真实事务状态分叉。保存点 UI 不属于本阶段。

风险分析不是数据库权限边界。只读仍同时调用 `Connection.setReadOnly(true)`；数据库账户权限是最终防线。

## 9. Statement 注册、超时和取消

### 9.1 执行选项

SPI 增加一次执行使用的选项对象，包含最大结果行数、查询超时秒数和 `SqlExecutionControl`。Oracle 与 PostgreSQL runner 创建的 schema 切换、用户 SQL、执行计划等 Statement 都必须：

1. 在执行前设置 `Statement.setQueryTimeout(seconds)`；`0` 表示不限制。
2. 向控制器注册为当前活动 Statement。
3. 在 `finally` 中解除注册。
4. 保持 try-with-resources 关闭规则。

驱动明确报告不支持查询超时时，记录脱敏警告并在会话状态显示“驱动不支持超时”，但不把普通查询直接判为失败。

### 9.2 取消

- 取消按钮仅在 `running=true` 时启用。
- UI 通过虚拟线程调用 `SqlExecutionControl.cancel()`，不在 JavaFX 线程直接执行 JDBC 方法。
- 首选调用当前 `Statement.cancel()`。
- `cancel()` 抛错或 Statement 已丢失时，关闭该标签专用连接作为兜底。
- 取消成功后展示“已取消”，不把正常取消渲染成红色数据库错误。
- 取消导致连接关闭时，会话进入 `BROKEN`，再次执行前重连。
- 取消、关闭和 Statement 正常完成之间使用原子引用保证幂等，不重复关闭资源。

## 10. 连接安全配置与兼容性

继续使用 `ConnConfig.props` 承载关系型连接安全设置，但 `ConnectionStore` 只持久化三个白名单字段：

- `environment`：`DEVELOPMENT`、`TEST`、`PRODUCTION`，默认 `DEVELOPMENT`。
- `readOnly`：布尔值，默认 `false`。
- `queryTimeoutSeconds`：`0..3600`，默认 `60`；`0` 表示不限制。

序列化仍使用平坦 JSON 字段，兼容现有极简解析器。不得序列化任意 props，更不能持久化临时的 `__plainPassword`。旧配置缺少新字段时采用默认值；下一次正常保存时写入新字段。未知环境值回退到 `DEVELOPMENT` 并记录警告，非法超时回退到 `60`。

连接对话框只在 Oracle/PostgreSQL 显示：

- 环境下拉框。
- 只读复选框。
- 查询超时秒数输入。

Redis 暂不显示这些字段，也不改变 Redis 配置序列化结果。

## 11. SQL 编辑器交互

工具栏新增紧凑的会话区：

```text
[环境标识] [连接名称] [自动提交/手动事务] [提交] [回滚] [取消] [超时 60s]
```

- 开发环境使用中性色，测试环境使用黄色，生产环境使用红色。
- 只读连接显示明显的“只读”徽标。
- 提交、回滚仅在手动模式且存在待处理事务时启用。
- 取消仅在执行中启用；执行按钮在取消完成前保持禁用。
- 状态栏同时展示耗时、结果摘要和事务状态，不再用结果消息覆盖关键事务信息。
- 执行危险脚本时，确认框展示环境、连接、风险类型和涉及语句；生产环境按钮文案使用“确认在生产环境执行”。

不在本阶段重排整个 SQL 编辑器或制作新的视觉主题。

## 12. 标签关闭与应用退出

现有 `ContentTabPane.openManagedTab` 只有关闭后的 disposer，无法在阻塞 JDBC 提交前异步确认。新增通用异步关闭守卫：

- 用户请求关闭时先消费默认关闭事件。
- SQL 编辑器无待处理事务时立即批准关闭。
- 有待处理事务时弹出“提交并关闭 / 回滚并关闭 / 取消”。
- 若语句仍在执行，关闭提示只提供“取消执行、回滚并关闭 / 取消关闭”；未知执行结果不得直接提交。
- 提交或回滚通过 `FxTaskScope` 的虚拟线程执行。
- 操作成功后由守卫回到 FX 线程移除标签；失败则保留标签并展示错误。
- 关闭批准、用户重复点击关闭和应用清理之间保证 disposer 最多执行一次。

`SqlEditorPane.close()` 是无交互的最终资源清理：取消活动语句、回滚未提交事务、关闭专用连接、关闭任务作用域并移除监听器。应用退出调用 `disposeAll()` 时走该保守回滚路径，绝不隐式提交。

## 13. 错误处理与恢复

- 所有 UI 错误信息脱敏，不包含密码、完整连接 URL 或完整密文。
- 提交/回滚失败时保留标签和会话，允许用户重试或关闭连接；不得错误显示成功。
- 连接已断开时提交/回滚明确提示事务可能已经丢失。
- 超时和用户取消分别记录为 `TIMEOUT`、`CANCELLED`，与数据库 SQL 错误区分。
- 专用连接创建失败不影响连接树缓存和其他标签。
- 会话关闭异常写日志，但继续释放监听器和任务作用域。

## 14. 测试策略

### 14.1 纯单元测试

`SqlSafetyAnalyzerTest` 覆盖：

- 普通查询、写入、DDL、事务语句和未知语句分类。
- `UPDATE/DELETE` 有无顶层 `WHERE`。
- 字符串、注释、子查询中的伪关键字。
- PostgreSQL dollar quote、Oracle q quote、CTE 和多语句脚本。
- `EXPLAIN` 与 `EXPLAIN ANALYZE`。
- 生产、测试、开发和只读决策矩阵。

`JdbcEditorSessionTest` 使用可控 JDBC stub/proxy 覆盖：

- 每个会话获取不同连接且只关闭自己的连接。
- 自动提交与手动模式转换。
- 提交、回滚、关闭回滚和失败状态。
- 单飞行拒绝、活动 Statement 注册、超时设置、取消及关闭连接兜底。
- 手动模式脚本遇错立即中止。

`ConnectionStoreTest` 覆盖旧 JSON 默认值、新字段往返、非法值回退、Redis 配置和 `__plainPassword` 永不落盘。

### 14.2 生命周期和 UI 合约测试

- 异步关闭守卫只有批准后才移除标签。
- 重复关闭和应用退出时 disposer 只执行一次。
- 关闭后的虚拟线程回调不访问 UI。
- `SqlEditorPane` 构造和关闭合约继续保持可测试。

### 14.3 Provider 合约测试

对 Oracle/PostgreSQL runner 使用 JDBC stub 验证每个执行路径都注册 Statement、应用查询超时并在异常后解除注册。

### 14.4 验收

- `clean test` 在 Windows 和 Ubuntu 通过。
- `jlink` 在 Windows 通过。
- GitHub Redis 集成测试不回归。
- 手工连接 PostgreSQL 和 Oracle 验证自动提交、手动提交/回滚、取消、超时、只读和生产确认。
- 两个指向同一数据库的 SQL 标签具有不同 JDBC 会话，一个标签回滚或取消不影响另一个。
- `git diff --check` 和 `codegraph sync` 通过。

## 15. 实施拆分

书面规格批准后，实施计划按以下独立交付拆分：

1. 连接安全设置模型与向后兼容持久化。
2. SQL 风险分析器和决策矩阵。
3. Statement 执行控制及 Oracle/PostgreSQL runner 合约。
4. `JdbcEditorSession` 专用连接、事务状态机和取消。
5. SQL 编辑器会话工具栏、风险确认和状态展示。
6. 异步标签关闭守卫与应用退出回滚。
7. README、完整测试、jlink、CodeGraph 和发布说明。

每一步遵循测试先行，先观察目标测试因缺少行为而失败，再完成最小实现并运行全量回归。

## 16. 完成条件

- SQL 标签不再使用共享 JDBC 连接执行 SQL 或执行计划。
- 自动提交、手动事务、提交、回滚、取消和超时均有清晰 UI 状态。
- 关闭未提交标签需要明确决策，应用退出默认回滚。
- 只读连接不能通过 SQL 编辑器执行写入、DDL 或未知语句。
- 生产写入和危险语句未经确认不得开始执行。
- Oracle/PostgreSQL 行为对称，Redis、迁移和其他数据库功能无回归。
- 正常运行路径中的执行、提交、回滚和取消使用受管 JDK 25 虚拟线程；应用最终退出可做有界同步清理，关闭后不得遗留 JDBC 连接或任务。
