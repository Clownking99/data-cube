# DataCube 专业数据库管理工具 — 设计文档

## 背景与目标

DataCube 当前是一个 Oracle→PostgreSQL 数据迁移工具，已具备：数据迁移（DDL/数据导出导入）、
SQL 窗口（编辑/美化/多语句执行）、JavaFX 三栏尚未成型的 TabPane GUI。

本设计将其演进为**标准 DBA 工具**（对标 DBeaver/Navicat 的核心能力子集），并在架构层面**彻底解耦**：
业务层不依赖任何具体数据库实现，运行时按连接类型分派到对应实现。**一期仅实现 PostgreSQL**，
但接口设计规避 PG 专属假设，未来接入 MySQL/Oracle 等为纯新增、零改动现有代码。

### 一期功能范围（明确边界）

| 功能 | 说明 | 状态 |
|------|------|------|
| 连接管理 | 新建/编辑/保存多个连接，作为连接树根节点；密码 AES 加密持久化 | 新增 |
| 对象树浏览 | schema→表/视图/索引/约束/函数/序列，懒加载展开 | 新增 |
| SQL 编辑器升级 | 现有 SQL 窗口接入连接树、绑定活动连接、结果面板化 | 升级 |
| 表数据浏览（只读）| 双击表→分页数据网格 + 排序 + 过滤 | 新增 |
| 对象 DDL 查看 | 选中对象→展示 CREATE 语句（只读 + 复制）| 新增 |
| 数据迁移 | 现有 Oracle→PG 功能，移包后作为一个功能标签挂载 | 保留（不重构）|

**明确排除（二期及以后）**：表数据编辑（写）、慢 SQL/锁/会话监控、用户权限管理、
ER 图、存储过程调试、Schema Diff、数据同步。

## 整体设计

### 分层架构（严格单向依赖）

```
┌─────────────────────────────────────────────────────────────┐
│  UI 层  com.datacube.fx.*                                     │
│  三栏式 Shell + 各功能面板（连接树/SQL/数据网格/DDL/迁移）      │
│  —— 只依赖 service 层与 spi 接口，不含任何 PG 专属 SQL         │
├─────────────────────────────────────────────────────────────┤
│  服务层  com.datacube.service.*                               │
│  ConnectionManager（连接+凭据）、SessionContext（当前连接）    │
│  ObjectTreeService、DataBrowseService —— 编排 SPI 完成用例    │
├─────────────────────────────────────────────────────────────┤
│  抽象层(SPI)  com.datacube.spi.*                              │
│  DatabaseProvider + 6 能力接口 + ProviderRegistry            │
│  —— 纯接口 + 模型对象(record)，零 JDBC 方言                    │
├─────────────────────────────────────────────────────────────┤
│  实现层  com.datacube.provider.postgres.*                     │
│  PostgresProvider + 6 个能力实现 —— 一期唯一实现              │
│  （未来 provider.mysql.* / provider.oracle.* 平行新增）       │
├─────────────────────────────────────────────────────────────┤
│  遗留迁移  com.datacube.migration.*（现 source/target 迁入）  │
│  OracleExporter/PgImporter 一期不重构，作为迁移功能挂载        │
└─────────────────────────────────────────────────────────────┘
```

**依赖方向**：UI → service → spi ← provider。`provider.postgres` 仅被 `ProviderRegistry`
在启动时注册，UI/service 编译期完全看不到它。任何 PG 专属 SQL 只允许出现在 `provider.postgres`
与 `migration`（遗留）中。

## SPI 抽象层（`com.datacube.spi`）

### 顶层入口

```java
public interface DatabaseProvider {
    DbType type();                                    // POSTGRESQL / (未来)MYSQL...
    boolean supports(String jdbcUrl);                 // URL 前缀识别
    ConnectionFactory connectionFactory();
    SqlDialect dialect();
    MetadataReader metadataReader(Connection c);      // 传入连接，返回该连接的读取器
    DdlGenerator ddlGenerator(Connection c);
    DataAccessor dataAccessor(Connection c);
    SqlRunner sqlRunner();
}
```

### 6 能力接口

```java
public interface ConnectionFactory {
    void ensureDriverLoaded();
    Connection open(ConnConfig cfg) throws SQLException;
    String test(ConnConfig cfg);                      // null=成功，否则错误消息
}

public interface SqlDialect {
    String quoteIdentifier(String ident);
    String pageClause(long offset, int limit);        // PG: LIMIT ? OFFSET ?
    String currentSchemaSql(String schema);           // PG: SET search_path TO ?
    boolean hasSchemaLevel();                          // PG=true; 未来 MySQL=false
}

public interface MetadataReader {
    List<CatalogInfo> catalogs();                     // PG=databases
    List<SchemaInfo> schemas(String catalog);
    List<TableInfo> tables(String schema);
    List<ColumnInfo> columns(TableRef t);
    List<IndexInfo> indexes(TableRef t);
    List<ConstraintInfo> constraints(TableRef t);
    List<RoutineInfo> routines(String schema);        // 函数/过程
    List<SequenceInfo> sequences(String schema);
    List<ViewInfo> views(String schema);
}

public interface DdlGenerator {
    String tableDdl(TableRef t);
    String viewDdl(TableRef t);
    String routineDdl(RoutineRef r);
    String sequenceDdl(String schema, String name);
}

public interface DataAccessor {
    PagedResult page(TableRef t, long offset, int limit, List<SortKey> sorts, String filter);
    long count(TableRef t, String filter);
}

// SQL 执行：现有 sqleditor.SqlExecutor 收编为 spi 能力，方言部分委托 SqlDialect
public interface SqlRunner {
    QueryResult execute(Connection c, String sql, String schema);
    List<ScriptOutcome> executeScript(Connection c, String script, String schema);
}
```

### 模型对象（`com.datacube.spi.model`）

全部用 `record` 定义，不可变、无行为，跨层传递：
`ConnConfig`、`DbType`、`TableRef`、`RoutineRef`、`CatalogInfo`、`SchemaInfo`、`TableInfo`、
`ColumnInfo`、`IndexInfo`、`ConstraintInfo`、`RoutineInfo`、`SequenceInfo`、`ViewInfo`、
`PagedResult`、`SortKey`、`QueryResult`、`ScriptOutcome`。

**设计约束**：`MetadataReader` 使用通用的 catalog/schema 模型，不假设"user 即 schema"（Oracle）
或"无 schema 层级"（MySQL）。`SqlDialect.hasSchemaLevel()` 供 UI 决定树层级展现。

### 运行时分派

```java
public final class ProviderRegistry {
    private static final List<DatabaseProvider> PROVIDERS = new ArrayList<>();
    static { register(new PostgresProvider()); }      // 一期唯一；未来加一行
    public static void register(DatabaseProvider p) { PROVIDERS.add(p); }
    public static DatabaseProvider forType(DbType t) { ... }
    public static DatabaseProvider forUrl(String jdbcUrl) { ... }  // 遍历 supports()
}
```

未来接入新库：新增 `provider.mysql` 包 + `static` 块加一行注册，service/UI 层零改动。
后续可无缝升级为 `ServiceLoader` 自动发现；一期用显式注册更简单可控。

## 服务层（`com.datacube.service` + `com.datacube.config`）

### 连接管理与凭据加密

```java
// 连接配置持久化：~/.datacube/connections.json
record ConnConfig(String id, String name, DbType type, String host,
                  int port, String database, String username,
                  String encryptedPassword, Map<String,String> props) {}

class ConnectionStore {                       // 读写 JSON（手写极简 JSON，无新依赖）
    List<ConnConfig> loadAll();
    void save(ConnConfig c);  void delete(String id);
}

class CredentialCipher {                       // AES-GCM 对称加密
    // 密钥从本机固定信息(用户名+主目录)派生 + 固定 salt，SHA-256 → AES-128
    String encrypt(String plain);  String decrypt(String cipher);
}

class ConnectionManager {                      // 连接生命周期
    Connection acquire(String connId);         // 惰性建连，按 connId 缓存
    void release(Connection c);  void closeAll();
    String test(ConnConfig cfg);
}

```

> **分层约束**：`SessionContext`（持有活动连接、用 JavaFX `ObjectProperty` 供 UI 绑定）
> 归入 **`com.datacube.fx` 层**，不放 service 层——避免 service 依赖 JavaFX。
> `service`/`spi`/`provider` 层方法一律显式接收 `connId`，全层零 JavaFX 依赖。
> `provider = ProviderRegistry.forType(cfg.type())` 由 UI 在需要时解析。

- 元信息（名称/host/端口/库/用户名）**明文** JSON，便于查看/手改
- 密码 **AES-GCM 加密**，密钥本机派生（不引入系统密钥库依赖）
- **JSON 处理**：一期不引入新依赖，手写极简 JSON 读写（配置结构简单固定），保持 fat-jar 精简

### 用例服务

- `ObjectTreeService` — 为连接树提供懒加载数据，编排 `MetadataReader`
- `DataBrowseService` — 表数据分页，编排 `DataAccessor`
- DDL 查看直接经 `SessionContext.provider().ddlGenerator(conn)`

## UI 层（`com.datacube.fx`）

### 三栏式结构

```
┌─ AppShell (BorderPane) ───────────────────────────────────────┐
│ 顶部工具栏: [新建连接] [执行] [刷新] ...                        │
├────────────┬──────────────────────────────────────────────────┤
│ 左:连接树   │ 中: 内容标签区 (TabPane, 每个对象/编辑器一个标签)  │
│ (TreeView) │   [SQL: query1] [表: users] [DDL: orders] ...     │
│            │   ← SqlEditorPane / DataGridPane / DdlViewPane    │
│            ├──────────────────────────────────────────────────┤
│            │ 底部: 结果/消息面板 (结果网格 + 日志 Tab)          │
└────────────┴──────────────────────────────────────────────────┘
```

### UI 组件（各为独立类，单一职责）

- `AppShell` — BorderPane 骨架，组装三栏，持有 `SessionContext`（活动连接 UI 状态）
- `SessionContext` — 归属 fx 层，`ObjectProperty<ConnConfig>` 供各面板绑定活动连接
- `ConnectionTreePane` — 左侧 TreeView；懒加载；右键菜单（打开 SQL/查看数据/查看 DDL/刷新）；数据来自 `ObjectTreeService`
- `ContentTabPane` — 中部多标签容器，管理打开的编辑器/查看器
- `SqlEditorPane` — 现有 `SqlEditorController` 升级：绑定树选中连接、结果展示于底部面板
- `DataGridPane` — 双击表打开，调 `DataBrowseService` 分页展示（一期只读）
- `DdlViewPane` — 展示 `DdlGenerator` 生成的 CREATE 语句（只读 + 复制）
- `MigrationPane` — 现有 `MainController` 迁移 UI 迁入，作为一个功能标签
- `ConnectionDialog` — 新建/编辑连接表单

### 数据流示例（双击表看数据）

```
用户双击树节点 users
 → ConnectionTreePane 发出 OpenTableData(connId, schema, "users")
 → ContentTabPane 新建 DataGridPane
 → DataBrowseService.firstPage(connId, tableRef)
     → conn = ConnectionManager.acquire(connId)
     → provider = ProviderRegistry.forType(cfg.type())   ← 运行时分派
     → provider.dataAccessor(conn).page(tableRef, 0, 100, ...)
         → PgDataAccessor 用 dialect.quoteIdentifier + pageClause 拼 SQL
 → PagedResult 返回 → DataGridPane 渲染 TableView
```

UI 与 service 全程不出现任何 PG 专属 SQL。

## 现有代码迁移路径（低风险、渐进）

| 现有 | 去向 | 处理 |
|------|------|------|
| `sqleditor.SqlExecutor/QueryResult/ScriptOutcome` | `spi.SqlRunner` + `spi.model.QueryResult` | 收编为能力接口，PG 实现委托 `SqlDialect` |
| `sqleditor.SqlFormatter/SqlScriptSplitter` | 保留（通用工具，不含方言） | 原样复用 |
| `core.ConnectionHelper` | `provider.postgres.PgConnectionFactory` + `service.ConnectionManager` | 硬编码驱动/`ensureSchema` 拆入实现层 |
| `core.ColumnInfo/TypeConverter` | `spi.model.ColumnInfo` + `provider.postgres` | 类型转换归 PG 实现 |
| `source.OracleExporter`/`target.Pg*` | `migration.*` 包 | 一期不重构，仅移包 + 挂到 MigrationPane |
| `fx.MainController` | `fx.MigrationPane` | 迁移 UI 内容迁入，成为一个功能标签 |
| `fx.FxLogger/cli.*` | 保留 | 日志复用 |

**关键：现有迁移功能一期零逻辑改动**，只移动 + 挂载，将风险降到最低。

## 错误处理

- **连接失败**：`ConnectionFactory.test()` 返回错误消息字符串（不抛到 UI），弹 Alert
- **SQL/查询异常**：`QueryResult.error(msg)` 承载（沿用现有模式），结果面板红字展示
- **元数据读取失败**：树节点显示"加载失败"占位，不崩溃整棵树
- **加密/配置文件损坏**：`ConnectionStore` 容错——损坏条目跳过并记日志，不阻断启动

## 测试策略

- **SPI 契约测试**：针对接口写测试，对 mock `Connection` 验证 service 编排逻辑（不连真库）
- **PG 集成测试**：可选，用当前环境的 postgres 连接跑 `MetadataReader`/`DataAccessor` 冒烟
- **纯逻辑单测**：`SqlDialect`（引用/分页拼接）、`CredentialCipher`（加解密往返）、
  `ConnectionStore`（JSON 读写往返）无需数据库
- **GUI 冒烟**：沿用现有方式——`build.sh` 构建后启动 GUI 验证不崩溃、连接树可展开

## 构建与打包

- 无新增第三方依赖（手写 JSON + JDK 自带 `javax.crypto`），fat-jar 体积基本不变
- **build.sh 需显式新增源集**：现脚本逐包列出（非 `src/**` 通配），必须在 `javac` 源列表
  追加新包：
  ```
  src/com/datacube/spi/*.java \
  src/com/datacube/spi/model/*.java \
  src/com/datacube/service/*.java \
  src/com/datacube/config/*.java \
  src/com/datacube/provider/postgres/*.java \
  src/com/datacube/migration/*.java     # 由 source/target 迁入
  ```
  同时移除已迁空的 `source/target` 通配（或保留至迁移完成）。`spi`/`service` 不需要
  JavaFX classpath；`fx` 包（含 AppShell/SessionContext）继续依赖 `$JAVAFX_CP`。

## GUI 入口改造（`DataCubeFx`）

现 `DataCubeFx.start()` 将 `MainController.createUI()` 的 `VBox` 包进 `ScrollPane`。三栏式改造：

- 根节点改为 `new AppShell()`（`BorderPane`），**去掉 `ScrollPane`**（三栏需铺满窗口自适应，
  内部各面板各自滚动）
- `Scene` 尺寸调大（如 1200×800），标题改为「DataCube 数据库管理工具」
- 关闭确认/`shutdown()`：原 `MainController.isRunning()`/`shutdown()` 语义迁移到 `MigrationPane`，
  `AppShell` 关闭时聚合检查（迁移运行中才提示）并 `ConnectionManager.closeAll()` 释放连接
- 反射启动链（`DataCube.java` 中 `--gui` 分支）不变，仍加载 `DataCubeFx`

## 验证标准

- `bash build.sh` 成功生成 fat-jar，GUI 启动不崩溃
- 连接管理：可新建 PG 连接、密码加密存盘、重启后可解密连接成功
- 连接树：展开 schema 懒加载出表/视图/索引/函数/序列
- SQL 编辑器：绑定活动连接执行查询，结果显示于底部面板
- 表数据浏览：双击表分页展示数据，支持排序/过滤（只读）
- DDL 查看：选中对象展示可复制的 CREATE 语句
- 数据迁移：原 Oracle→PG 功能作为标签可正常使用（逻辑不变）
- 架构校验：`grep` 确认 UI/service/spi 包中无 PG 专属 SQL（仅 provider.postgres/migration 允许）

## 假设

- 目标运行环境为个人本机（凭据本机派生密钥可接受，非多用户共享服务器）
- 一期仅 PostgreSQL；接口设计已规避 PG 专属假设，但实际多库验证留待后续期
- 现有迁移功能的 Oracle 依赖（ojdbc）继续随 fat-jar 打包，作为迁移标签的运行时依赖
