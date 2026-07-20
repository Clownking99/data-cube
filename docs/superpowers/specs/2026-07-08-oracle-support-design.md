# Oracle 数据库支持设计

## 目标

为 DataCube 增加 Oracle 数据库支持，功能与现有 PostgreSQL **完全对等**：连接管理、对象树（表/视图/列/索引/约束/序列/函数）、数据网格分页浏览、DDL 查看、SQL 编辑器（执行 + 执行计划）、导出（SQL 脚本 / Excel）。

新增 Oracle 支持遵循现有 SPI 开闭原则：新建一个 `provider.oracle` 实现 + 在 `ProviderRegistry` 注册，业务/UI 层零改动。

## 背景与约束

- **SPI 架构现状**：`DatabaseProvider` 暴露 6 个能力（connectionFactory / dialect / sqlRunner / metadataReader(conn) / ddlGenerator(conn) / dataAccessor(conn)）。`ProviderRegistry` 显式注册 provider，按 `DbType` / JDBC URL 分派。业务层只依赖接口。
- **驱动已就位**：`drivers/ojdbc17-23.26.1.0.0.jar` 已在 `build.gradle`、`build.sh` 引用，无需新增依赖。
- **可复用资产**：`migration/OracleExporter.java`（581 行）已含验证过的 Oracle 元数据 SQL（`ALL_SEQUENCES` / `ALL_TABLES` / `ALL_TAB_COLUMNS` / `ALL_INDEXES` / `ALL_CONSTRAINTS` / `DBMS_METADATA` 等），可作为元数据/DDL 实现的参考底本。
- **模块化**：`src/module-info.java` 中 JDBC 驱动经 `DriverManager` 的 ServiceLoader 加载，未直接 `requires`；`provider.oracle` 属同一模块 `com.datacube`，无需改 `module-info`。

### 澄清结论（brainstorming）

| 议题 | 决定 |
|---|---|
| v1 范围 | 与 PostgreSQL 完全对等 |
| 单表导出的"数据库备份"选项 | 不做备份，Oracle 仅留 SQL / Excel（隐藏 pg_dump 备份单选） |
| 执行计划深度 | 估算 + 实际都支持（EXPLAIN PLAN + DBMS_XPLAN.DISPLAY；ANALYZE 用 GATHER_PLAN_STATISTICS + DISPLAY_CURSOR） |
| 连接标识 | Service Name → `jdbc:oracle:thin:@//host:port/service` |

### 采用方案

**方案 A：镜像 `provider.postgres`，新建 `provider.oracle` 全套实现。** 与现有架构一致、Oracle 逻辑内聚隔离、业务/UI 零改动，DDL 用 `DBMS_METADATA.GET_DDL` 保真度最高。（已否决：B 通用 `DatabaseMetaData`——拿不到 DDL、保真度差；C 抽公共基类——把两库耦合进继承体系，改 PG 有连带风险。）

## 设计

### 1. 类型与配置层

- `DbType` 增枚举值 `ORACLE("Oracle", "jdbc:oracle:thin:@", 1521)`。
- `ConnConfig.jdbcUrl()` 的 `switch` 增 Oracle 分支：`jdbc:oracle:thin:@//<host>:<port>/<database>`，其中 `database` 字段承载 Service Name。（switch 对 `DbType` 穷举，新增枚举会被编译强制补分支。）
- `ConnectionDialog` 顶部新增**数据库类型下拉框**（PostgreSQL / Oracle）：
  - 切换时联动默认端口（5432 / 1521）、"数据库"字段标签（数据库 / Service Name）、`headerText` 文案。
  - `build()` 用当前所选 `DbType` 构造 `ConnConfig`（替换现在硬编码的 `DbType.POSTGRESQL`）。
- `ConnectionStore` 持久化验证：`DbType` 以枚举名读写，确认 `ORACLE` 可正常 round-trip。

### 2. `provider.oracle` 新包（8 个类）

- **`OracleProvider`** — `implements DatabaseProvider`。无状态能力（工厂/方言/执行器）单例复用；绑定连接的能力按 `Connection` 现建。`type()=ORACLE`；`supports(url)` 认 `jdbc:oracle:` 前缀。
- **`OracleConnectionFactory`** — `Class.forName("oracle.jdbc.OracleDriver")`（幂等）；`open()` 用 `DriverManager.getConnection`；`test()` 执行 `SELECT 1 FROM DUAL`，成功返回 `null`，失败返回错误消息不抛异常。密码取值沿用 PG 的 `__plainPassword` props 约定。
- **`OracleSqlDialect`** — `implements SqlDialect`：
  - `quoteIdentifier`：双引号，内部双引号转义为两个（同 PG）。
  - `pageClause(offset, limit)`：`OFFSET <offset> ROWS FETCH NEXT <limit> ROWS ONLY`（12c+）。
  - `currentSchemaSql(schema)`：`ALTER SESSION SET CURRENT_SCHEMA = <schema>`（schema 为空返回 null）。
  - `hasSchemaLevel()`：`true`（Oracle user=schema，owner 大写）。
  - `sqlLiteral(Object)`：见 §3。
- **`OracleMetadataReader`** — 按 `OWNER` 过滤 `ALL_*` 视图：
  - `schemas`：拥有可见对象的 owner（`SELECT DISTINCT OWNER FROM ALL_OBJECTS ORDER BY OWNER`）。
  - `tables` / `views`：`ALL_TABLES` / `ALL_VIEWS`。
  - `columns`：`ALL_TAB_COLUMNS`（含类型/长度/精度/可空）。
  - `indexes` / `constraints`：`ALL_INDEXES` / `ALL_CONSTRAINTS`（+ `ALL_CONS_COLUMNS`）。
  - `sequences`：`ALL_SEQUENCES`。
  - `routines`：`ALL_PROCEDURES`（过程/函数/包）。
  - `catalogs`：Oracle 无 catalog 概念，返回空列表 / 单个隐式 catalog（与 PG 行为对齐，交由对象树通用渲染）。
- **`OracleDdlGenerator`** — `DBMS_METADATA.GET_DDL(<type>, <name>, <owner>)`：`TABLE` / `VIEW` / `PROCEDURE` / `FUNCTION` / `PACKAGE` / `SEQUENCE`。可设 `DBMS_METADATA.SET_TRANSFORM_PARAM` 提升可读性（去存储子句等，可选）。结果 CLOB 读为字符串。
- **`OracleDataAccessor`** — 分页/计数委托方言；**LOB 渲染**：CLOB → `getString`（截断预览），BLOB → `[BLOB n bytes]` 或十六进制摘要，避免网格显示 `oracle.sql.BLOB@...`。
- **`OracleSqlRunner`** — 复用脚本切分/逐条执行；实现执行计划能力（见 §4）。
- **`OracleColumnComments`**（best-effort）— `ALL_COL_COMMENTS`，对齐 PG 的"SQL 结果表头显示字段注释"；无法解析时返回 null 不影响结果展示。

### 3. 两处 SPI 小重构（服务于对等，PG 行为不变）

- **执行计划能力**：`SqlRunner` 接口新增
  `QueryResult explain(Connection conn, String sql, String schema, boolean analyze)`。
  - `PgSqlRunner`：委托现有 `dialect.explainSql(sql, analyze)` + `execute`，行为与现状一致。
  - `OracleSqlRunner`：实现两步流程（§4）。
  - `SqlEditorPane.doExplain` 改为调用 `provider.sqlRunner().explain(...)`，不再自行拼 `explainSql` 字符串。`planArea` 文本渲染（取结果首列逐行拼接）保持不变。
- **值字面量方言化**：`SqlDialect` 接口新增 `String sqlLiteral(Object v)`。
  - 把 `SqlScriptExporter.literal(...)` 里硬编码的 PG 字面量（null→NULL、Number 原样、Boolean→TRUE/FALSE、byte[]→`'\xHEX'`、字符串转义）下沉到 `PgSqlDialect.sqlLiteral`。
  - `OracleSqlDialect.sqlLiteral`：byte[] → `HEXTORAW('..')`；日期/时间 → `TO_DATE` / `TO_TIMESTAMP`；字符串同样单引号转义；Number 原样；null→NULL。
  - `SqlScriptExporter` 改为调用 `dialect.sqlLiteral(v)`，两库 SQL 脚本导出均正确。

### 4. Oracle 执行计划（估算 + 实际）

- **估算**（analyze=false）：
  1. `EXPLAIN PLAN SET STATEMENT_ID='dc_<rand>' FOR <sql>`（`<rand>` 避免并发 PLAN_TABLE 冲突）。
  2. `SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE','dc_<rand>'))`，逐行拼首列成文本。
- **实际**（analyze=true）：
  1. 对 SELECT 注入 `/*+ GATHER_PLAN_STATISTICS */` 后实跑（消费结果集）。
  2. `SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))` 取真实行数/耗时。
- 写类语句勾 ANALYZE 沿用 `SqlEditorPane` 现有二次确认；多语句只对第 1 条出计划（现状逻辑不变）。

### 5. 导出对接

- `ExportDialog` 按 `conns.config(connId).type()` 判断：**非 PostgreSQL 隐藏 pg_dump 备份单选**，Oracle 仅显示 SQL / Excel。
- Oracle SQL 脚本导出：DDL 走 `OracleDdlGenerator`，值走 `OracleSqlDialect.sqlLiteral`。
- `XlsxWriter` / `RowFeed` / `TableExporter` 的 XLSX 与分页路径 DB 无关，直接复用。

### 6. 打包 / 构建

- `build.sh` javac 源清单增 `src/com/datacube/provider/oracle/*.java`（`build.gradle` 的 `srcDirs=['src']` 自动覆盖）。
- `module-info` 无需改。jlink `mergedModule` 若报缺 Oracle 依赖 JDK 模块（如 `oracle.jdbc` 需额外 `requires`），按需在 `mergedModule` 增补——列为迭代项。

## 测试计划

连接一个 Oracle 实例，逐项验证与 PG 对等：

1. 新建 Oracle 连接（Service Name）→ 测试连接成功。
2. 对象树展开：schema → 表/视图/列/索引/约束/序列/函数正确显示。
3. 数据网格：分页、计数、含 CLOB/BLOB 列的表渲染正常（不出现对象地址串）。
4. 表节点 DDL 查看：`DBMS_METADATA` 输出完整 CREATE 语句。
5. SQL 编辑器：执行查询；执行计划（估算）；执行计划（ANALYZE 实跑）出真实统计。
6. 单表导出：SQL 脚本（结构/数据/两者）与 Excel；确认无 pg_dump 选项。
7. 连接持久化：重启后 Oracle 连接可复现。
8. `gradlew compileJava` 编译验证；PG 全路径回归（执行计划、SQL 导出）行为不变。

## 假设

- 目标 Oracle ≥ 12.1（`OFFSET..FETCH` 语法、ojdbc17 契合）。
- 连接用户对目标 schema 有 `ALL_*` 视图与 `DBMS_METADATA` 读取权限。
- 无 Oracle 实例的开发环境仅能做编译验证；功能行为需在有库环境实测。
