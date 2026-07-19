# SQL 执行计划（EXPLAIN / EXPLAIN ANALYZE）

日期：2026-07-08
状态：已确认，待实现

## 目标

在 SQL 编辑器中，对当前 SQL 一键查看 PostgreSQL 执行计划：默认 `EXPLAIN`（仅估算、
不执行），可选 `EXPLAIN ANALYZE`（实际执行、真实耗时/行数）。计划以等宽只读文本展示，
保留缩进树形。

## 背景与约束

- `SqlEditorPane` 现有“执行 (F5)/美化/清空”按钮，执行走
  `runner.executeScript(conn, sql, schema)`；单条走 `runner.execute(...)`。
- EXPLAIN 语法是方言相关；SPI 中 `SqlDialect` 收敛方言片段，`DatabaseProvider`
  暴露 `dialect()` 与 `sqlRunner()`，`SqlEditorPane` 可经
  `connections.provider(connId)` 取到。
- `EXPLAIN` 只接受单条语句；`EXPLAIN ANALYZE` 会真正执行 SQL（对 DML 有写入副作用）。
- 目前仅 PostgreSQL provider。

## 设计

### 1. 方言层（`spi.SqlDialect`）
- 新增 **default 方法** `String explainSql(String sql, boolean analyze)`：
  返回 `EXPLAIN <sql>`（analyze=false）或 `EXPLAIN ANALYZE <sql>`（analyze=true）。
- 作为 default 方法：PG 直接适用，不破坏其它/未来 provider（可各自 override）。
- 不改 `SqlRunner`：复用现有 `execute()` 执行这条 EXPLAIN 语句。

### 2. UI 入口（`fx.SqlEditorPane` 工具栏）
- 新增“执行计划”按钮 + “ANALYZE（实际执行）”复选框，置于“执行 (F5)”旁。
- `onExplain()` 流程：
  1. 校验非空 SQL、存在活动连接（复用现有校验）；
  2. 用现有 `SqlScriptSplitter.split` 取**第 1 条语句**；多于 1 条时状态栏提示
     “已对第 1 条语句生成执行计划（共 N 条）”；
  3. 若勾选 ANALYZE **且语句为写类**（首关键字非 `SELECT`/`WITH`/`VALUES`/`SHOW`/
     `TABLE` 的启发式判断）→ 弹确认框；用户取消则中止；
  4. 与 `onExecute` 相同的运行态处理（禁用按钮、状态“执行中...”），后台线程执行。

### 3. 执行与展示
- `doExplain(connId, sql, schema, analyze)`（后台线程）：
  `dialect = connections.provider(connId).dialect()`；
  `explainSql = dialect.explainSql(sql, analyze)`；
  `QueryResult r = runner.execute(conn, explainSql, schema.isEmpty()?null:schema)`。
- 结果为单列 `QUERY PLAN` 的多行文本：把各行按换行拼成整段纯文本。
  `Platform.runLater` 后：QUERY → `showPlan(text)`；ERROR → `showError(...)`。
- 结果区内容可切换：
  - 保留现有 `TableView resultTable`（正常结果）。
  - 新增 `TextArea planArea`（只读、等宽、不换行、可滚动）。
  - `resultPane`（承载结果的 `TitledPane`）在两者间切换 content：`showPlan` 切到
    `planArea` 并设标题“结果（执行计划）”；正常查询/错误/清空路径先经
    `useTable()` 切回 `resultTable` 并复位标题“结果”。

### 4. 安全与边界
- 默认 EXPLAIN 不执行，安全；ANALYZE 实际执行，写类语句已二次确认（启发式，best-effort）。
- 仅对第 1 条语句生成计划，避免多语句 EXPLAIN 报错。
- 语法错误等失败沿用 `showError`。
- 写类判断为启发式：`WITH ... 数据修改 CTE` 等边界可能漏判，故 ANALYZE 的实际执行
  风险由“默认不勾 + 二次确认”兜底。

## 影响面

- 改动：`spi.SqlDialect`（加 default 方法）、`fx.SqlEditorPane`（按钮/复选框 +
  `onExplain`/`doExplain`/`showPlan`/`useTable` + 结果区内容切换）。
- 不影响：CLI、迁移、字段注释功能、其它 SPI 与 provider（无接口破坏）。
- 构建：无新增文件，`build.sh` 与 Gradle 均无需改源清单。

## 测试计划

- 编译验证：`gradlew compileJava` 通过。
- 手动验证（PostgreSQL）：
  - 对 `SELECT ...` 点“执行计划”：等宽文本区显示缩进计划树；不勾 ANALYZE 不执行。
  - 勾 ANALYZE 对 `SELECT` 生成含真实耗时/行数的计划；对 `UPDATE/DELETE` 先弹确认，
    取消则不执行、确定才执行。
  - 多语句时仅对第 1 条出计划并有状态提示。
  - 语法错误显示错误视图；随后普通“执行”能正确切回表格视图。

## 假设

- PG 接受 `EXPLAIN <sql>` 与 `EXPLAIN ANALYZE <sql>`，结果集单列 `QUERY PLAN`
  逐行返回。
- 写类语句启发式（首关键字白名单）足以覆盖常见用法；边界情况以二次确认兜底。
