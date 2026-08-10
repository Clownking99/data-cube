# DataCube Schema Diff 全对象同库对比设计

- 状态：待用户书面规格审核
- 日期：2026-08-10
- 交付方式：直接在 `main` 分阶段提交，每个阶段独立测试、审查和回退

## 1. 背景

DataCube 已具备 Oracle、PostgreSQL 的连接管理、完整对象树、元数据读取、DDL 生成、安全 SQL 会话和受管虚拟线程生命周期。下一阶段把这些能力组合为面向开发、测试和 DBA 的 Schema Diff 工作流：比较两个同类型数据库的单个 Schema，审阅全对象差异，生成依赖有序的变更脚本，并在安全确认后部署到目标 Schema。

本阶段优先解决“环境之间结构是否一致、如何安全同步”问题，不把 Schema Diff 退化为字符串比较器，也不自动猜测不可逆变更。

## 2. 已确认产品决策

- 对比范围为全对象。
- 首期只支持同数据库类型：PostgreSQL ↔ PostgreSQL、Oracle ↔ Oracle。
- 一次只比较一个源 Schema 与一个目标 Schema。
- 源 Schema 是期望状态，目标 Schema 是待同步状态。
- 结果支持生成有序 SQL，并通过安全确认在工具内执行。
- 破坏性变更默认不生成；用户必须逐项启用并再次确认。
- 首期只支持在线连接 ↔ 在线连接。
- 使用规范化语义模型和 provider adapter，不采用纯 DDL 文本 diff。

## 3. 目标

### 3.1 用户目标

- 快速确认两个环境的 Schema 是否一致。
- 按对象类型、风险和依赖关系理解差异。
- 查看源定义、目标定义、结构化属性变化和拟生成 SQL。
- 选择需要同步的变更，安全导出或执行脚本。
- 明确知道哪些差异无法自动处理，以及原因和人工建议。

### 3.2 工程目标

- UI 和通用 diff engine 不包含 Oracle/PostgreSQL 专属 SQL。
- 同一输入产生稳定、可重复审查的差异和脚本顺序。
- 元数据读取、脚本生成和执行均可取消，不阻塞 JavaFX Application Thread。
- 复用安全 SQL 会话、受管标签、JDK 25 虚拟线程和现有 provider 分层。
- 不增加不必要的数据库连接压力。

## 4. 明确不在本阶段

- Oracle ↔ PostgreSQL 跨类型 Schema 对比或迁移映射。
- 在线 Schema 与快照文件、DDL 文件之间的对比。
- 整库多 Schema 对比或任意对象集合对比。
- 自动执行推断出的重命名。
- 默认启用删除、类型收窄等破坏性变更。
- 数据内容对比、数据同步或增量数据复制。
- 权限、用户、角色、表空间、存储参数和数据库实例级配置对比。
- 自动解决无法可靠排序的循环依赖。
- 保证整个部署批次可事务回滚。

## 5. 总体架构

采用“provider 提取与渲染 + 通用语义 diff + JavaFX 工作流”的分层结构：

```text
SchemaDiffPane
  -> SchemaDiffService
       -> SchemaSnapshotReader (SPI/provider)
       -> SchemaDiffEngine (通用纯 Java)
       -> SchemaChangePlanner (通用依赖图与风险)
       -> SchemaChangeRenderer (SPI/provider)
       -> SchemaDeploymentService
            -> 目标独立安全 JDBC 会话
```

职责边界：

- `SchemaSnapshotReader`：读取 provider 元数据并生成不可变规范模型。
- `SchemaDiffEngine`：只比较规范模型，不访问 JDBC，不生成方言 SQL。
- `SchemaChangePlanner`：建立依赖图、风险、默认选择状态和稳定顺序。
- `SchemaChangeRenderer`：把已选择 change 转为同数据库类型的方言 SQL。
- `SchemaDeploymentService`：执行目标漂移检查并串行部署，记录逐项结果。
- `SchemaDiffPane`：选择连接、展示差异、控制选择、预览、导出和执行。

## 6. SchemaSnapshot 规范模型

### 6.1 顶层模型

```java
public record SchemaSnapshot(
        DbType databaseType,
        String connectionId,
        QualifiedName schema,
        Instant capturedAt,
        SnapshotCompleteness completeness,
        Map<ObjectKey, SchemaObject> objects,
        String fingerprint) {}
```

- snapshot 不保存密码、完整 JDBC URL 或可还原凭据。
- `fingerprint` 由规范对象内容按稳定顺序计算，用于执行前漂移检查。
- `SnapshotCompleteness` 记录成功、部分失败以及失败的对象范围。
- snapshot 内集合全部不可变并保持确定性排序。

### 6.2 对象标识

```java
public record ObjectKey(ObjectType type, QualifiedName name, String signature) {}

public record QualifiedName(String original, String comparisonKey, boolean quoted) {}
```

- `original` 用于 UI 和生成 SQL。
- `comparisonKey` 由 provider 按数据库自身的未引用/引用标识符规则生成。
- 不用统一 `toLowerCase()` 或 `equalsIgnoreCase()` 代替数据库规则。
- 函数、过程和重载对象的 key 包含规范参数签名。

### 6.3 对象范围

`ObjectType` 包含：

- TABLE
- COLUMN（作为 table child，不单独出现在顶层对象树）
- PRIMARY_KEY
- UNIQUE_CONSTRAINT
- FOREIGN_KEY
- CHECK_CONSTRAINT
- INDEX
- SEQUENCE
- VIEW
- MATERIALIZED_VIEW（provider 支持时）
- FUNCTION
- PROCEDURE
- TRIGGER
- PACKAGE_SPEC
- PACKAGE_BODY
- TYPE

PostgreSQL 不产生 Oracle 专属对象；Oracle 不产生 PostgreSQL 专属对象。UI 只展示 provider 声明支持的类别。

### 6.4 结构化对象

表、列、约束、索引、序列使用结构化属性：

```java
public record TableDefinition(
        ObjectKey key,
        List<ColumnDefinition> columns,
        List<ConstraintDefinition> constraints,
        List<IndexDefinition> indexes,
        Set<ObjectKey> dependencies) implements SchemaObject {}

public record ColumnDefinition(
        QualifiedName name,
        CanonicalDataType dataType,
        boolean nullable,
        String normalizedDefault,
        Integer ordinal,
        String comment) {}
```

`CanonicalDataType` 保留同类型数据库内进行等价判断所需的 base type、长度、精度、scale、时区、数组维度和 provider extension；不承担 Oracle↔PostgreSQL 类型映射。

约束和索引比较列顺序、唯一性、表达式、条件、引用目标和动作。仅因系统自动生成名称不同但语义相同的对象可由 provider 标记为语义等价；显式用户名称仍参与比较。

### 6.5 可编程对象与定义型对象

视图、函数、过程、触发器、Oracle 包和类型采用：

```java
public record DefinitionObject(
        ObjectKey key,
        String normalizedDefinition,
        String originalDefinition,
        Set<ObjectKey> dependencies,
        DefinitionConfidence confidence) implements SchemaObject {}
```

- `normalizedDefinition` 只消除不影响语义的换行、尾部分号和 provider 已确认的格式噪声。
- 不重排表达式、不改写字符串、不删除 optimizer hint。
- provider 无法高置信规范化时使用 `LOW` confidence；差异可展示，但不自动生成 replace SQL。
- `originalDefinition` 用于审阅和 provider SQL 渲染，不写入日志。

## 7. 匹配与重命名

- 先按 `ObjectKey` 精确匹配。
- 精确匹配失败后，可根据结构相似度产生“疑似重命名”建议。
- 疑似重命名不改变 diff 结果，也不自动生成 rename SQL。
- 用户明确配对后，provider 必须声明支持该对象类型的 rename，才允许生成 rename change。
- 未配对对象保持 `MISSING_IN_TARGET` 与 `EXTRA_IN_TARGET` 两项。

## 8. Diff 模型

```java
public record SchemaDifference(
        DifferenceKind kind,
        ObjectKey object,
        SchemaObject source,
        SchemaObject target,
        List<PropertyDifference> properties,
        RiskLevel risk,
        AutomationLevel automation,
        Set<ObjectKey> dependencies,
        String explanation) {}
```

`DifferenceKind`：

- MISSING_IN_TARGET
- EXTRA_IN_TARGET
- MODIFIED
- EQUIVALENT
- UNSUPPORTED

`AutomationLevel`：

- SAFE_AUTOMATIC
- DESTRUCTIVE_OPT_IN
- MANUAL_ONLY

若 snapshot 对相关对象不完整，则该对象只能产生 `UNSUPPORTED/MANUAL_ONLY`，不得生成部署 SQL。

## 9. 风险规则

默认安全、自动选中的变更包括：

- 创建缺失对象。
- 添加 nullable 列且无破坏性默认值副作用。
- 添加不改变现有数据语义的索引、约束或可编程对象。
- 扩大兼容字段长度或精度（provider 高置信确认时）。

默认禁用、必须逐项启用的变更包括：

- 删除表、列、序列、视图、函数、过程、触发器、包或类型。
- 字段类型变更、长度/精度收窄。
- nullable → NOT NULL。
- 修改或删除主键、唯一约束、外键、check constraint。
- 可能重建表或长时间锁表的变更。
- replace 可编程对象且 provider 判断存在依赖风险。

无法可靠自动化的差异只显示人工建议，不提供勾选框。

## 10. 依赖图与稳定排序

planner 根据显式元数据依赖和对象类别建立有向图。

默认创建顺序：

1. TYPE
2. SEQUENCE
3. TABLE 与 COLUMN
4. PRIMARY KEY、UNIQUE、CHECK
5. INDEX
6. FOREIGN KEY
7. VIEW、MATERIALIZED VIEW
8. FUNCTION、PROCEDURE、PACKAGE SPEC/BODY
9. TRIGGER

删除采用反向顺序。对象类型顺序相同的节点按规范 schema/name/signature 排序，保证稳定输出。

循环依赖处理：

- provider 可安全拆分声明/实现时，生成分阶段 change。
- 无法安全拆分时，循环节点标记 `MANUAL_ONLY`。
- 不通过关闭约束、删除依赖或猜测性 stub 自动打破循环。

## 11. SQL 渲染

`SchemaChangeRenderer` 只接收同类型、已选择、已排序的 change：

```java
public interface SchemaChangeRenderer {
    DbType databaseType();
    RenderedChange render(SchemaDifference difference, RenderContext context);
}

public record RenderedChange(
        ObjectKey object,
        List<String> statements,
        RiskLevel risk,
        String expectedTargetFingerprint,
        Set<ObjectKey> dependencies) {}
```

- SQL 使用 provider 的标识符引用规则。
- 不拼接密码或连接参数。
- 每个 change 保持独立 statement 列表，便于逐项记录结果。
- script export 包含生成时间、源/目标对象名、风险注释和目标 fingerprint，但不包含连接秘密。
- `MANUAL_ONLY` 不进入 renderer。

## 12. 目标漂移检查

生成计划时记录目标 snapshot fingerprint 和每个相关对象 fingerprint。

执行前：

1. 重新读取目标 Schema 的相关对象摘要。
2. 对比计划中的 fingerprint。
3. 任一相关对象变化则阻止整个执行，提示重新比较。
4. 不允许用户通过普通确认跳过漂移检查。

执行过程中每个 change 完成后记录实际结果；取消或异常后重新读取受影响对象并显示“已应用 / 未应用 / 状态未知”。

## 13. 部署执行

- 使用目标连接的独立 `JdbcEditorSession`，不占用连接树共享连接。
- 执行在受管 JDK 25 虚拟线程中串行进行。
- 默认遇到第一个失败即停止。
- 依赖失败对象的后续 change 标记 `SKIPPED_DEPENDENCY`。
- 用户取消后不启动新 statement；当前 statement 使用既有 timeout/cancel 机制。
- 不承诺整体回滚：Oracle DDL 存在隐式提交，PostgreSQL 也统一按逐项实际结果记录。
- 生产目标和破坏性 change 继续经过安全策略与最终确认。
- 执行日志只保存对象、statement 摘要、时间和结果，不保存完整敏感 SQL 内容。

## 14. 元数据读取与并发

- 源和目标各使用一个专用只读元数据会话。
- 两侧 snapshot 可在两个虚拟线程中并行采集。
- 同一连接内按稳定顺序串行查询，不为每个对象创建新连接或虚拟线程。
- snapshot reader 接收取消令牌，并在每类对象和每个分页/批次之间检查。
- 关闭标签或应用退出时取消未开始工作、关闭两个专用会话并屏蔽延迟 UI 回调。
- 读取失败必须带对象范围写入 `SnapshotCompleteness`，不能静默返回空集合冒充“对象不存在”。

## 15. 用户界面

### 15.1 入口与选择

连接树和主工具栏提供“Schema 对比”。打开受管 `SchemaDiffPane`：

- 源连接、源 Schema。
- 目标连接、目标 Schema。
- 只允许选择相同 `DbType`。
- 源目标连接或 Schema 相同则阻止开始。

### 15.2 采集状态

- 分别显示源/目标连接状态、当前对象类别、进度和耗时。
- 支持取消。
- 一侧失败不把另一侧结果当成完整 diff；页面显示失败原因并允许重试。

### 15.3 差异页面

左侧按对象类别和风险分组的树/表；支持：

- 仅显示差异。
- 仅显示破坏性变更。
- 仅显示 manual-only。
- 按对象名称搜索。

右侧显示：

- 源定义。
- 目标定义。
- 结构化属性变化。
- 拟生成 SQL。
- 风险、自动化等级、依赖与人工建议。

### 15.4 选择与确认

- `SAFE_AUTOMATIC` 默认选中。
- `DESTRUCTIVE_OPT_IN` 默认未选中，首次勾选显示逐项风险说明。
- `MANUAL_ONLY` 无勾选框。
- 执行前显示最终有序计划、生产环境标识、破坏性项目数量和目标漂移检查状态。
- 用户可以导出完整脚本而不执行。

### 15.5 执行结果

逐项状态：

- PENDING
- RUNNING
- SUCCEEDED
- FAILED
- SKIPPED_DEPENDENCY
- CANCELLED
- UNKNOWN_AFTER_CANCEL

失败后保留标签、差异和日志；用户可重新读取目标并生成新的计划，不复用过期计划直接重试。

## 16. 错误处理与安全

- 错误消息脱敏，不显示密码、完整 URL、密文或未裁剪的敏感 SQL。
- 元数据权限不足与对象不存在必须区分。
- provider 不支持的对象类别显式标记，不以空集合表示支持。
- 普通元数据失败不会产生删除建议。
- 目标漂移、snapshot 不完整和数据库类型不一致属于硬阻止条件。
- 破坏性 change 需要逐项 opt-in 与执行前二次确认。
- 取消不等价于回滚；UI 必须展示当前对象的实际或未知状态。

## 17. Provider 能力

`DatabaseProvider` 增加可选 Schema Diff capability，不支持时 UI 隐藏入口：

```java
public interface SchemaDiffCapability {
    SchemaSnapshotReader snapshotReader(Connection connection);
    SchemaChangeRenderer changeRenderer();
    Set<ObjectType> supportedObjectTypes();
}
```

PostgreSQL 与 Oracle 分别实现：

- identifier normalization。
- canonical data type。
- 全对象 snapshot extraction。
- definition normalization 与 confidence。
- dependency extraction。
- same-type change rendering。

通用 service/UI 不通过 `instanceof` 判断具体 provider。

## 18. 测试策略

### 18.1 纯模型与 diff engine

- 相同 snapshot 无差异。
- 缺失、多余、修改、等价和 unsupported 全矩阵。
- quoted/unquoted identifier 和大小写规则。
- 函数/过程重载 signature。
- 默认值、数据类型、约束、索引、序列属性。
- 可编程对象 definition normalization confidence。
- 疑似重命名只建议、不自动转换。

### 18.2 planner

- 创建和删除顺序。
- 同级稳定排序。
- 跨对象依赖。
- 循环依赖 manual-only。
- 破坏性变更默认禁用。
- 未选择 change 不进入计划。

### 18.3 provider contract

- PostgreSQL/Oracle 全对象 metadata 映射。
- 元数据权限错误不会变成空 snapshot。
- provider SQL 引用、类型和 replace/drop 语法。
- unsupported object type 明确暴露。
- 渲染结果不包含凭据或连接 URL。

### 18.4 部署与生命周期

- 目标 fingerprint 漂移阻止执行。
- 逐项成功、失败停止、依赖跳过。
- cancel/timeout/unknown-after-cancel。
- 生产和破坏性二次确认。
- 标签关闭、应用退出、虚拟线程和两个专用会话释放。
- 延迟 FX callback 在关闭后不访问 UI。

### 18.5 live integration

- 只使用明确授权的非生产 PostgreSQL/Oracle 端点。
- 建立一次性测试 Schema，覆盖创建、修改、删除 opt-in 与重新比较归零。
- 没有端点时保留 provider JDBC proxy/fixture 测试，并在交付报告中记录 residual。

## 19. 分阶段交付

完整功能拆成四个可独立审查阶段：

1. 规范模型、identifier/type normalization、diff engine 与 planner。
2. PostgreSQL 全对象 snapshot/renderer 与 provider contract。
3. Oracle 全对象 snapshot/renderer 与 provider contract。
4. JavaFX 工作流、脚本导出、安全部署、漂移检查、生命周期和文档。

每个阶段严格 RED → GREEN、全量回归、独立 reviewer；最终再做累计安全与产品审查。

## 20. 完成条件

- PostgreSQL/Oracle 同类型单 Schema 全对象对比可用。
- 相同输入稳定生成相同差异和脚本顺序。
- 破坏性变更默认不进入计划，启用与执行均有明确确认。
- snapshot 不完整、目标漂移或类型不一致时不得执行。
- 普通关闭、取消和应用退出不泄漏 JDBC 会话、虚拟线程或 FX callback。
- 执行结果逐项可追溯，失败不会继续运行依赖项。
- 现有 Redis、安全 SQL、迁移、对象树、数据网格、jlink 和 G1 256MB 配置无回归。
- Windows 与 Ubuntu CI、Windows linked image、CodeGraph 和无秘密检查通过。
