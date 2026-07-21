# 表设计器与 Ctrl+点击跳转设计（B 组）

## 概述

两项相关功能，围绕「查看并设计表结构」：

1. **表设计器** —— 一个可视化面板，既能**从零新建表**（生成 `CREATE TABLE`），也能**编辑现有表结构**（diff 出 `ALTER`）。所有结构改动统一走「生成 DDL → 预览确认 → 执行」，复用现有 `SqlRunner` 与 A 组 `ScriptErrorPolicy`。
2. **SQL 编辑器 Ctrl+点击表名跳转** —— 在 `SqlEditorPane` 里按住 `Ctrl` 点击表名，解析并打开该表的设计器（类 PL/SQL Developer「查看表定义」）。

保持现有分层护栏：SPI（provider 无 JavaFX）→ Service（零 JavaFX）→ FX 面板。DDL 文本生成为**纯函数**，方言差异全部封闭在 provider 内，可脱离数据库单测。

### v1 范围（经用户确认）

- **支持要素**：列（名 / 类型文本 / 可空 / 默认值）、注释（表 / 列）、主键（含复合）、索引（普通 / 唯一）。
- **暂不做**：外键、唯一约束（表级 `UNIQUE` 约束，区别于唯一索引）、检查约束 —— 留后续版本。
- **编辑现有表**：完整 diff，含**删列 / 删索引**（破坏性操作由预览确认门控）。
- **落库方式**：生成 DDL → 预览弹窗 → 执行；不直接执行、不仅仅塞进 SQL 编辑器。

---

## 架构：方案 A（Provider 层 DDL 构建 SPI）

DDL 生成逻辑放在 provider 层，方言差异（标识符引用、类型渲染、`MODIFY` vs `ALTER COLUMN`、`COMMENT ON` 语法、`CREATE INDEX`）全部封闭在各 provider 内。Service 只负责「加载当前结构 → 组装草稿」「调 builder 生成 DDL」「执行」，UI 只负责收集草稿模型。DDL 生成是纯文本函数，像 A 组 `SqlFormatterTest` 一样可单测。

---

## 数据模型（新增 `spi/model`）

跨方言不建模每种类型系统，列类型用**类型文本**（`typeText`，如 `VARCHAR2(50)` / `numeric(10,2)`），由用户在可编辑下拉框里填/选。

- `TableDraft`（record）：`schema`、`name`、`tableComment`、`List<ColumnDraft> columns`、`List<String> primaryKey`（有序列名，空=无主键）、`List<IndexDraft> indexes`。
- `ColumnDraft`（record）：`name`、`typeText`、`nullable`、`defaultValue`（可空）、`comment`（可空）。
- `IndexDraft`（record）：`name`、`unique`、`List<String> columns`。

草稿为不可变 record；UI 编辑态用可变包装（JavaFX property 承载），应用时快照成 `TableDraft`。

---

## SPI 变更

### 新增 `spi/TableDdlBuilder`

无状态（不绑定 `Connection`），由 `DatabaseProvider` 提供，各 provider 实现方言文本：

```java
public interface TableDdlBuilder {
    /** 从草稿生成建表脚本：CREATE TABLE + 表/列 COMMENT + CREATE INDEX。 */
    String createTable(TableDraft draft);

    /**
     * diff 原始态与编辑态，生成变更脚本（多语句，以分号分隔）：
     * 增列 / 改列(类型/可空/默认) / 删列 / 主键增删改 / 增删索引 / 表/列注释变更。
     * 无差异时返回空串。
     */
    String alterScript(TableDraft original, TableDraft edited);
}
```

- **列匹配**：`original` 与 `edited` 的列按 `name` 匹配（v1 不支持改列名，改名等价删+增；后续如需 rename 再引入稳定 id）。
- **Oracle vs PG 差异样例**：改列类型 Oracle `ALTER TABLE t MODIFY (c <type>)`、PG `ALTER TABLE t ALTER COLUMN c TYPE <type>`；可空 Oracle 用 `MODIFY (c [NOT] NULL)`、PG 用 `ALTER COLUMN c SET/DROP NOT NULL`；默认值同理；注释两者都用独立 `COMMENT ON TABLE/COLUMN`。

### `DatabaseProvider` 增方法

```java
/** 表 DDL 构建器（无状态，可复用）。 */
TableDdlBuilder tableDdlBuilder();
```

`OracleProvider`、`PostgresProvider` 各返回自己的实现（`OracleTableDdlBuilder`、`PgTableDdlBuilder`，置于对应 `provider/*` 包）。

---

## 服务层 `service/TableDesignService`（零 JavaFX）

显式接收 `connId`，与 `DdlService`/`ObjectTreeService` 同风格：

- `TableDraft load(String connId, TableRef t)` —— 复用 `ObjectTreeService`/`MetadataReader` 的 `columns`/`indexes`/`constraints` 组装当前结构；主键列由 `ColumnInfo.primaryKey` 或 `ConstraintInfo(PRIMARY_KEY)` 推导（保序）。
- `String previewCreate(String connId, TableDraft draft)` —— 取 `provider(connId).tableDdlBuilder().createTable(draft)`。
- `String previewAlter(String connId, TableDraft original, TableDraft edited)` —— 同上调 `alterScript`。
- `List<ScriptOutcome> execute(String connId, String ddl, ScriptErrorPolicy policy)` —— 复用 `SqlRunner.executeScript`（含 A 组遇错策略）。

类型下拉的候选值可由 service 暴露（`List<String> commonTypes(connId)`，从 `dialect`/provider 取该方言常用类型），或先在 UI 侧内置常量表，v1 采用后者从简。

---

## UI：`fx/TableDesignerPane`

参照 `DataGridPane` / `DdlViewPane` 风格，构造入参 `(TableDesignService svc, String connId, TableRef table 或 null 表示新建, String schema)`。

- **顶部**：schema + 表名（新建时可编辑，编辑现有表时只读）+ 工具栏「应用」「刷新（丢弃改动重载）」。
- **主体 `TabPane`**：
  - **列**：可编辑 `TableView<ColumnRow>`，列 = 列名 / 类型(可编辑下拉，预置该方言常用类型) / 可空(勾选) / 默认值 / 注释；行操作 = 增行、删行、上移、下移；主键用一个「PK」勾选列（或单独多选控件）标记，复合主键按勾选顺序。
  - **索引**：可编辑 `TableView<IndexRow>`，名 / 唯一(勾选) / 列(多选)；增删行。
  - **表注释**：单行文本框。
  - **DDL 预览**：只读 `CodeArea`（复用 `SqlHighlighter`），切到该页或点「应用」时刷新。
- **应用流程**：快照编辑态成 `TableDraft` → 新建走 `previewCreate`、编辑走 `previewAlter(original, edited)` → 弹**预览确认对话框** → 执行 → 回填结果；成功后对编辑态表重新 `load` 使编辑态成为新的「原始态」。
- **生成校验**：列名/类型为空、主键引用不存在的列等，在生成阶段拦截并提示，不进入执行。

### 预览确认对话框（安全门）

- 展示完整生成 DDL（多语句），按钮「执行 / 取消」。
- 执行走 `TableDesignService.execute` + A 组 `ScriptErrorPolicy`（逐条遇错 → 继续 / 全部继续 / 取消）。
- 破坏性语句（`DROP COLUMN` / `DROP INDEX`）在预览文本中原样可见（可选：加醒目样式，非 v1 必须）。

---

## 入口接线

扩展 `ConnectionTreePane.Actions` 并由 `AppShell.TreeActions` 实现：

- 新增 `openTableDesigner(String connId, TableRef table)`：`TABLE` 右键菜单加「设计表」（双击维持打开数据网格不变）。
- 新增 `newTable(String connId, String schema)`：给 `TABLES` 节点补右键菜单「新建表」（当前 `buildMenu` 的 `default` 分支返回 null，需补 `TABLES` case）。
- 打开方式：`contentTabs.openTab("设计: " + name, pane.getNode())`；新建表标题用「新建表」。

---

## Ctrl+点击跳转（`fx/SqlEditorPane`）

- 给 `editorArea`（RichTextFX `CodeArea`）加鼠标处理：`Ctrl` 按下时的点击，用 `hit(x, y)` / 光标位置取该处的**标识符**（含 `.`），解析为 `schema.table` 或裸表名。
- **schema 解析**：限定名用其 schema；裸名用编辑器当前 schema（`doExecute` 已使用的 schema 变量），并用 `dialect.foldUnquotedIdentifier` 归一大小写。
- 后台线程用 `MetadataReader.tables(schema)` 校验表存在 → 存在则 FX 线程打开设计器；不存在则忽略（可选：状态栏轻提示）。
- **注入方式**：给 `SqlEditorPane` 构造器加一个回调 `java.util.function.BiConsumer<String, TableRef> openDesigner`，由 `AppShell.TreeActions.openTableDesigner` 实现，保持 fx 层内部低耦合。`AppShell.TreeActions.openSqlEditor` 构造 `SqlEditorPane` 时传入。
- 可选打磨：`Ctrl` 悬停时表名加下划线 + 手型光标 —— 视工作量，非 v1 必须。

---

## 测试

纯文本、无需真实 DB，仿 A 组 `SqlFormatterTest` 风格（`test` sourceSet，`modularity.inferModulePath=false`）：

- `OracleTableDdlBuilderTest` / `PgTableDdlBuilderTest`：
  - `createTable`：列定义、内联主键、表/列 `COMMENT`、`CREATE INDEX` 的结构化断言（正则/包含，非精确整串）。
  - `alterScript` 各场景：加列、删列、改类型（Oracle `MODIFY` / PG `ALTER COLUMN ... TYPE`）、可空变更、默认值变更、主键增删改、增删索引、表/列注释变更。
  - 无差异 → 空串。
  - 破坏性：删列生成 `DROP COLUMN`、删索引生成 `DROP INDEX`。
- `TableDesignService` 的 `load`→draft 组装可选轻量测试（若可脱离连接，否则靠手工回归）。
- UI 编辑态、预览弹窗、Ctrl+点击解析靠手工回归。

---

## 影响面与回归

- 新增：`spi/TableDdlBuilder`、`spi/model/{TableDraft,ColumnDraft,IndexDraft}`、`provider/oracle/OracleTableDdlBuilder`、`provider/postgres/PgTableDdlBuilder`、`service/TableDesignService`、`fx/TableDesignerPane`、对应测试。
- 改动：`spi/DatabaseProvider`（加 `tableDdlBuilder()`）、`OracleProvider`/`PostgresProvider`（实现）、`ConnectionTreePane`（`Actions` 加两方法 + TABLE/TABLES 菜单）、`AppShell`（实现两 action + 构造 `SqlEditorPane` 传回调）、`SqlEditorPane`（构造器加回调 + Ctrl+点击处理）。
- `DatabaseProvider` 接口新增方法会在编译期暴露所有未实现的 provider（当前仅 Oracle/PG 两个）。
- 手工回归：
  - 新建表：填列/类型/主键/索引/注释 → 应用 → 预览 CREATE → 执行 → 树刷新可见新表。
  - 编辑现有表：加列 / 改类型 / 删列 / 改主键 / 增删索引 / 改注释 → 预览 ALTER → 执行 → 重载。
  - Ctrl+点击：裸名（当前 schema）/ `schema.table` 限定名 / 不存在的名（忽略）。
  - Oracle 与 PG 各跑一遍上述。

## 假设

- 列类型以「类型文本」承载，不校验类型合法性（由数据库执行时报错，经预览可见）。
- v1 不支持改列名（改名 = 删+增）；如需 rename 后续引入列稳定 id。
- 类型下拉候选 v1 用 UI 内置常量表，不从数据库动态取。
- 复合主键顺序 = 列表中勾选 PK 的行序。
