# 可编辑表数据网格设计

## 目标

让双击表打开的「表数据」网格（`DataGridPane`）像 Navicat 一样**可直接编辑并提交**：单元格内联编辑、新增行、删除行；改动**逐行离开即提交**到数据库；删除需**二次确认**。SQL 编辑器的查询结果网格本期**保持只读**。

## 背景与约束

- **现有分层**：`SPI 能力接口`（各 provider 实现）→ `Service`（编排、零 JavaFX）→ `FX 面板`。只读数据浏览已有对称三件：`DataAccessor`（SPI）/ `DataBrowseService` / `DataGridPane`。
- **只读现状**：`DataGridPane` 经 `DataBrowseService.page()` → `DataAccessor.page()` 分页读取单表（`SELECT *`，多取 1 行判断 `hasMore`），`PAGE_SIZE=200`。渲染时把每个单元格 `toString()` 拍平成字符串、`null` 变成 `""`——**丢失了「NULL vs 空串」区分与原始类型**。
- **连接模型**：`ConnectionManager.acquire(connId)` 惰性建连并**按 connId 缓存单个共享 `Connection`**（`synchronized`，默认 autoCommit=true）。共享连接**非线程安全**，读写必须串行。
- **值裁剪**：`QueryResult.readCell` 对 CLOB 截断到 500 字符、BLOB 截断到 64 字节 hex、Timestamp 截断到 19 字符——**这些列的显示值不是真实完整值**，不可用于写回或作为匹配条件。
- **元数据可得**：`MetadataReader.columns(TableRef)` 返回 `ColumnInfo(name, typeName, nullable, defaultValue, ordinal, primaryKey, comment)`；`constraints()` 含 `PRIMARY_KEY`。但 `ColumnInfo` **不含 `java.sql.Types` 整型**。
- **方言收敛**：`SqlDialect.quoteIdentifier(ident)` 负责标识符引用（PG 双引号、Oracle 大写等），是拼 DML 时唯一的库差异点。
- **模块化**：单模块 `com.datacube`（`src/module-info.java`）；新增包均属同模块，无需改模块声明。

### 澄清结论（brainstorming）

| 议题 | 决定 |
|---|---|
| 编辑范围 | 仅「表数据」网格 `DataGridPane`；SQL 查询结果网格保持只读 |
| 提交模型 | **逐行离开即提交**（Navicat 默认）：改完某行、焦点/选中移开该行即提交该行 |
| 操作集 | 编辑已有单元格（UPDATE）+ 新增行（INSERT）+ 删除行（DELETE，二次确认） |
| 无主键处理 | 用该行**全列旧值**拼 `WHERE`；提交前事务内校验「仅影响 1 行」，否则回滚并告警 |
| 写能力落点 | **方案 A**：新增 `DataEditor` SPI 能力 + `DataEditService`，`DataGridPane` 就地改可编辑 |

### 采用方案

**方案 A：对称扩展只读分层。** 新增 `DataEditor`（SPI 写能力，各 provider 实现）+ `DataEditService`（编排），`DataGridPane` 就地改为可编辑。所有写操作用 `PreparedStatement` **参数化**执行（杜绝注入），类型/NULL/二进制处理集中在写层，UI 不出现任何 SQL。

已否决：
- **在 `DataGridPane` 里直接用原生 JDBC 写共享连接**——SQL 与库差异泄漏进 UI，不可复用、难测试，违背现有架构。
- **复用 `SqlRunner` 执行生成的 DML 文本**——需把值拼进 SQL 字符串，类型/NULL/二进制易错且有注入风险，失去参数化。

## 架构与新增件

```
spi/DataEditor.java                 写能力接口：columns / insert / update / delete
spi/model/EditableColumn.java       列可编辑元数据（record）
spi/model/RowKey.java               行定位符（record）：columns + 旧值
provider/jdbc/JdbcDataEditor.java   共享实现（DML 标准，仅引用走 dialect）
service/DataEditService.java        编排（对称 DataBrowseService，零 JavaFX）
fx/EditableGridModel.java           行/单元格状态与脏标记（从面板抽出）
fx/DataGridPane.java                就地改造为可编辑（既有文件）
spi/DatabaseProvider.java           增 dataEditor(Connection) 方法（既有文件）
provider/oracle/OracleProvider.java 实现 dataEditor（既有文件）
provider/postgres/PostgresProvider.java 实现 dataEditor（既有文件）
```

- `DatabaseProvider` 增 `DataEditor dataEditor(Connection c)`；Oracle 与 PG 两个 provider 均 `return new JdbcDataEditor(c, dialect())`。DML 为标准 SQL，唯一库差异（标识符引用）由 `SqlDialect.quoteIdentifier` 收敛，故**共享一个 `JdbcDataEditor`**，不每库一份。
- 分页限定单表 `SELECT *`，与 `DataEditor.columns()` 的列顺序一致，保证网格列 ↔ 表列一一对应。

## 数据模型

```java
// 列可编辑元数据（打开网格时读取一次）
record EditableColumn(
    String  name,
    int     jdbcType,      // java.sql.Types
    String  typeName,
    boolean nullable,
    boolean primaryKey,
    boolean autoIncrement,
    boolean editable)      // LOB/二进制/ROWID/只读列 = false
{}

// 行定位符：PK 优先，否则全列可匹配列
record RowKey(List<String> columns, List<Object> values) {}
```

`EditableColumn.columns(TableRef)` 的获取：用 `SELECT * FROM t WHERE 1=0` 取 `ResultSetMetaData`（得 `getColumnType`=jdbcType、`isAutoIncrement`、`isReadOnly`、`isWritable`），叠加 `DatabaseMetaData.getPrimaryKeys` 得主键列。`editable=false` 判定：LOB（`CLOB/BLOB/LONGVARCHAR/LONGVARBINARY/BINARY/VARBINARY`）、`ROWID`、`isReadOnly==true`。

## 写能力接口语义（DataEditor）

```java
List<EditableColumn> columns(TableRef t) throws SQLException;
int insert(TableRef t, LinkedHashMap<String,String> values) throws SQLException;
int update(TableRef t, LinkedHashMap<String,String> newValues, RowKey key) throws SQLException;
int delete(TableRef t, RowKey key) throws SQLException;
```

- **值传参约定**：`String` 为文本值；`null` 键值表示 SQL NULL。写层按列 `jdbcType` 把文本**强转**为目标 Java 值再 `ps.setObject(i, val, jdbcType)`；NULL 走 `ps.setNull(i, jdbcType)`。转换失败抛携带列名与目标类型的可读异常。
- **INSERT**：`values` 仅含用户实际填写过的列，**未触碰的列一律省略**，让 DB 默认值/序列/identity 生效。
- **RowKey 的 WHERE 拼装**：值非 NULL → `quoteId(col) = ?`（绑定旧值）；值为 NULL → `quoteId(col) IS NULL`（不占位）。
- 写层内部缓存 `columns(t)` 结果（按 TableRef），避免每次操作都探测元数据。

### 类型强转规则（文本 → 目标类型）

| jdbcType 类别 | 规则 |
|---|---|
| 数值（INTEGER/BIGINT/DECIMAL/NUMERIC/REAL/DOUBLE…） | `new BigDecimal(text)`；空白文本 → NULL |
| 布尔（BOOLEAN/BIT） | `true/false/1/0/t/f`（忽略大小写）；空白 → NULL |
| 时间（DATE/TIME/TIMESTAMP） | 解析为 `java.sql.Date/Time/Timestamp`；空白 → NULL |
| 字符（VARCHAR/CHAR/NVARCHAR…） | 原文本（空串 `""` 即空串，非 NULL） |
| 其它 | 原文本交由 `setObject(i, text, jdbcType)` 让驱动强转 |

「空白 → NULL」仅对非字符类型生效；字符类型的空串是合法空串。显式 NULL 由 UI 传 `null`。

## 事务护栏（安全核心）

每个 `update` / `delete` **各自独立事务**：

```
prevAutoCommit = conn.getAutoCommit();
conn.setAutoCommit(false);
try {
    int n = ps.executeUpdate();
    if (n == 1) { conn.commit(); return n; }
    conn.rollback();
    throw new RowGuardException(n);   // n==0 或 n>1
} catch (SQLException e) {
    conn.rollback(); throw e;
} finally {
    conn.setAutoCommit(prevAutoCommit);
}
```

- 影响 **1 行** → 提交。
- 影响 **0 行**（他人已改/删、旧值不匹配）或 **>1 行**（无主键全列匹配撞到重复行）→ 回滚 + 抛 `RowGuardException(n)`，UI 提示「该行可能已被他人修改/删除，或匹配到多行（{n}），已回滚」。

`insert` 单语句执行（autoCommit=true 即可原子），期望影响 1 行；异常直接上抛。护栏是「无主键全列匹配」的安全兜底。

## Service 层（DataEditService）

对称 `DataBrowseService`，构造注入 `ConnectionManager`，零 JavaFX：

```java
private DataEditor editor(String connId) throws SQLException {
    Connection c = connections.acquire(connId);
    return connections.provider(connId).dataEditor(c);
}
List<EditableColumn> columns(String connId, TableRef t) throws SQLException;
int insert(String connId, TableRef t, LinkedHashMap<String,String> values) throws SQLException;
int update(String connId, TableRef t, LinkedHashMap<String,String> newValues, RowKey key) throws SQLException;
int delete(String connId, TableRef t, RowKey key) throws SQLException;
```

`AppShell` 已持有 `connMgr`；新建 `DataEditService(connMgr)` 传入 `DataGridPane`（`openDataGrid` 处多传一个参数）。

## UI 层：DataGridPane 改造

### 行/单元格状态（EditableGridModel）

从面板抽出，保持面板聚焦。每行持有一组单元格；每个单元格保留：`original`（typed Object，来自加载）、`edited`（String 或 NULL 标记）、`touched`（是否被用户改过）。行状态：

- `CLEAN`：未改。
- `MODIFIED`：≥1 单元格被改，保留全部 `original` 供 WHERE。
- `NEW`：新增行，未 INSERT。

（不设 `DELETED` 缓冲态——删除即时执行 + 二次确认。）

### 加载路径调整

`DataGridPane` 打开时：

1. `columns(connId, t)` 取 `List<EditableColumn>`（PK / jdbcType / editable）。
2. 分页 `page(...)` 取数据，构建 `EditableGridModel`，**保留 null 与原始类型**（不再拍平为 `""`）。
3. 依 `EditableColumn.editable` 与 PK 情况配置列的可编辑性与提示栏。

### 单元格与呈现

- `TableView.setEditable(true)`；自定义单元格（基于 `TextFieldTableCell` 思路）：
  - NULL 显示为斜体灰 `(NULL)`；空串显示为空。
  - `editable=false` 的列双击不进入编辑，悬浮提示「该列不可在网格内编辑」。
  - 提交编辑 → 写回该单元格 `edited`+`touched`，行标 `MODIFIED`。
- 行底纹（`rowFactory`）：`MODIFIED` 浅黄、`NEW` 浅绿。
- NULL 录入：单元格右键「设为 NULL」；**非字符类型**列清空文本亦视为 NULL（字符类型清空为 `""`）。

### 提交时机（逐行离开即提交）

跟踪「当前编辑行」。以下任一发生即 `flushRow(上一行)`：选中行移到**不同行**、网格失焦、翻页（上一页/下一页）、刷新/重查、按 **Enter**。

- `MODIFIED` → `update(newValues=被改列, key=RowKey)`；`key` 取 PK 列（有 PK 时）或全部可匹配列（无 PK 时；排除 `editable=false` 的 LOB/二进制列，因其显示值已被裁剪不可匹配）的 `original` 值。
- `NEW` → `insert(仅 touched 列)`。
- 成功 → 行转 `CLEAN`，用库中回读值刷新该行（重查该行或按返回值更新）。
- 失败 → **保留脏状态**、弹 Alert、焦点停在该行，不丢用户输入。

### 工具栏与提示栏

- 现有「查询 / 上一页 / 下一页」保留；新增 **＋新增行**、**🗑删除行**（红色）。逐行提交模型下**无提交按钮**。
- 顶部提示栏：
  - 无主键但可全列匹配 → 琥珀色「无主键：更新/删除按全列旧值匹配，提交前校验仅影响 1 行」。
  - 全部列均不可匹配（皆 LOB/二进制）→ 整表只读并说明原因。

### 新增行

「＋新增行」在末尾追加一个 `NEW` 行（所有单元格 `touched=false` 即「默认」）。未触碰的单元格**不进 INSERT 列表**（DB 默认/序列生效）。离开该行提交；若非空且无默认值的列被留空 → DB 报错，经 Alert 呈现，保留该行继续编辑。

### 删除（二次确认）

选中 1+ 行 → 点「删除行」或按 Delete → 确认弹窗「确认删除选中的 N 行？此操作不可撤销。」→ 逐行 `delete(RowKey)`（各自事务 + 影响 1 行护栏）；`NEW` 未提交行直接从网格移除。任一行失败即停止并报告已删/未删情况。

## 并发与线程

- 所有写操作在 worker 线程执行（同现有 `load()` 模式），完成后回 FX 线程更新行状态。
- 与分页加载**共用一个 `busy` 串行开关**（现有 `loading` 字段升级为覆盖读+写的 `busy`），保护共享 `Connection` 不被并发使用；`busy` 期间禁用工具栏与网格编辑。

## 错误处理

- 三类错误各有可读文案：类型转换失败（列名+目标类型）、护栏违规（影响行数）、其它 `SQLException`（原始消息）。
- 统一经**状态栏 + Alert** 呈现；出错保留脏状态便于修正。

## 测试

- **纯函数单测（不依赖真实库）**：把 SQL 片段拼接（INSERT/UPDATE/DELETE 文本、`SET`/`WHERE` 拼装、`IS NULL`、标识符引用）与文本→类型强转抽为 `JdbcDataEditor` 的静态/包级函数，覆盖：数值/布尔/时间/字符/NULL 各分支、无主键全列 WHERE（含 NULL 列走 `IS NULL`）、引用转义。
- 项目当前无测试框架；本期以最小方式引入 JUnit 5（Gradle `test` 配置）仅测上述纯函数。若不希望引入测试依赖，则保留纯函数供后续接入，本期仅手工验证。
- **集成验证（手工回归）**：真实 PG 与 Oracle 各跑一遍——有 PK 表编辑/新增/删除；无 PK 表（含重复行触发护栏）；NULL 与空串区分；LOB 列只读；他人并发改动触发「影响 0 行」回滚。

## 明确不做（YAGNI）

- SQL 编辑器结果网格保持只读（后续单独立项：需从 `ResultSetMetaData` 推断单表+PK）。
- 不做 BLOB/CLOB 值编辑器（这些列网格内只读）。
- 不做批量/多行单事务（已选逐行模型）。
- 不做网格粘贴导入、不改导出。

## 假设

- 编辑目标为**表**（非视图/物化视图/同义词）；视图等即便可打开数据网格也按只读处理（`isWritable`/无 PK 会自然落到只读或全列匹配路径）。
- 单页 200 行内编辑；跨页编辑通过翻页前 `flushRow` 保证不丢改动。
- Oracle 与 PG 的 JDBC 驱动 `setObject(i, val, jdbcType)` 对上述类型分类的绑定行为符合标准。
