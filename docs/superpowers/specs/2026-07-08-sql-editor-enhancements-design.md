# SQL 编辑器增强设计（A 组）

## 概述

针对 SQL 编辑器的三项相互独立的增强，共用 `SqlEditorPane`：

1. **SQL 美化支持多层嵌套** —— 子查询 / 派生表 / CTE 体正确换行缩进。
2. **批量执行遇错弹窗** —— 一批多语句遇到失败时弹窗询问「继续 / 全部继续 / 取消」，而非静默停止。
3. **注释快捷键** —— `Ctrl+/` 行注释切换、`Ctrl+Shift+/` 块注释切换。

三项均不引入第三方依赖，保持现有分层：SPI（provider 无 JavaFX）→ Service → FX 面板。

---

## ① SQL 美化：支持多层嵌套

### 问题

`SqlFormatter.Renderer`（`src/com/datacube/sqleditor/SqlFormatter.java`）仅在括号深度 `paren==0` 时跟踪顶层子句并换行；一旦进入括号即整段行内输出。因此括号内的子查询、`FROM (SELECT ...)` 派生表、`WITH x AS (SELECT ...)` CTE 体不做河道对齐与换行 —— 即「多层嵌套失效」。

### 方案

在 `Renderer` 内引入**缩进基准 `indent`**（当前块河道的前导空格数，顶层为 0），并把括号分为两类：

- **子查询括号**：开括号 `(` 之后（跳过空白与注释 token）紧跟 `SELECT` 或 `WITH` 时，视为嵌套查询块：
  - 开括号后换行，`indent` 增加一档 `indent += RIVER + 2`（即每层缩进 8 空格）；
  - 块内照常做河道对齐，`startClause` / `contLine` 的填充由绝对列 `RIVER` 改为 `indent + RIVER`；
  - 遇到配对的 `)` 时先换行、`indent` 回退到外层，`)` 对齐到外层块。
- **普通括号**：函数参数、`IN (...)`、`VALUES (...)` 元组等非子查询括号，保持现有**行内**排版不变。

判定「子查询括号」需要向前看一个 token，故将 `render()` 的 for-each 遍历改为**索引遍历**，新增：
- lookahead 辅助：从当前 `(` 之后找到首个非空白、非注释 token，判断是否为 `SELECT`/`WITH`；
- 缩进栈：进入子查询括号时压入外层 `(indent, clause, joinLineOpen)` 等上下文，出括号时恢复。

`format(String)` 入口签名与「纯词法、不解析语义、永不改变 SQL 语义、只调整空白与关键字大小写」的契约保持不变；字符串字面量 / 双引号标识符 / 行注释 / 块注释仍作为整体 token 保留。

### 组件

- `SqlFormatter.Renderer`（改）：新增 `int indent` 字段、缩进栈、lookahead；`startClause`/`contLine` 按 `indent + RIVER` 填充；`(` / `)` 分支区分子查询与普通括号。
- 其余方法（`tokenize`、`readQuoted`、`handleClause`、`emit`、`needSpaceBefore`）逻辑不变。

### 测试

新增 `test/com/datacube/sqleditor/SqlFormatterTest.java`（JUnit5，复用现有 test sourceSet 与 `modularity.inferModulePath=false` 隔离配置）：

- 单层子查询：`SELECT * FROM (SELECT id FROM t WHERE x=1) a` 内层应换行缩进；
- `FROM (SELECT ...)` 派生表；
- `WITH x AS (SELECT ...) SELECT ...` CTE 体；
- `WHERE col IN (SELECT ...)` 子查询换行；
- 函数参数不被误拆：`SELECT COALESCE(a, b, c) FROM t` 保持行内；
- 幂等性：对已格式化文本再次 `format` 结果稳定（同一输出）；
- 语义保全（弱校验）：格式化前后「去除所有空白后的 token 序列」一致。

---

## ② 批量执行：遇错弹窗（继续 / 全部继续 / 取消）

### 问题

`OracleSqlRunner.executeScript` 与 `PgSqlRunner.executeScript` 在 `QueryResult.Kind.ERROR` 时 `break`，静默停止后续语句。用户希望遇错时可选择跳过当前失败语句继续执行。

### 分层约束

执行发生在 worker 线程且 runner 位于 provider 层（无 JavaFX 依赖），runner 不能直接弹窗。通过**回调接口**把「是否继续」的决策上交给 UI 层，runner 只依赖接口，不依赖 JavaFX。

### 方案

- SPI 新增回调接口 `com.datacube.spi.ScriptErrorPolicy`：

  ```java
  public interface ScriptErrorPolicy {
      enum Decision { CONTINUE, CONTINUE_ALL, ABORT }
      Decision onError(int index, String sql, String message);
  }
  ```

- `SqlRunner.executeScript` 增加参数：

  ```java
  List<ScriptOutcome> executeScript(Connection conn, String script, String schema,
                                    int maxRows, ScriptErrorPolicy policy);
  ```

  两个 provider 实现对称改：遇 ERROR 时——
  - 若已收到过 `CONTINUE_ALL`，直接继续，不再回调；
  - 否则调用 `policy.onError(index, sql, message)`：
    - `ABORT` → `break`（保留已执行 outcomes）；
    - `CONTINUE` → 继续下一条；
    - `CONTINUE_ALL` → 置内部标记，后续遇错不再回调，继续。
  - `policy` 为 `null` 时按 `ABORT`（等价现状），保证防御性。

- 仅一个调用点 `SqlEditorPane.doExecute`（`src/com/datacube/fx/SqlEditorPane.java`）需改。UI 侧实现 `ScriptErrorPolicy`：由于 `doExecute` 运行在 `SqlEditor-Worker` 线程（非 FX 线程），`onError` 内用 `Platform.runLater` 弹确认框 + `CountDownLatch` 同步等待用户选择后返回 `Decision`：
  - 弹窗文案：`第 N 条语句失败：<message>\n是否继续执行剩余语句？`；
  - 三个按钮：**继续**（`CONTINUE`）、**全部继续**（`CONTINUE_ALL`）、**取消**（`ABORT`）；
  - worker 线程阻塞在 latch 上等待，安全（不在 FX 线程，不会死锁 `showAndWait` 的嵌套事件循环）。

- 结果展示沿用现有多语句结果表（`#/类型/耗时/结果`，失败行 `结果` 列显示 `ERR: ...`）。取消时仅展示已执行部分。

### 组件

- `spi/ScriptErrorPolicy.java`（新）：回调接口 + `Decision` 枚举。
- `spi/SqlRunner.java`（改）：`executeScript` 签名加 `ScriptErrorPolicy policy` 参数并更新 javadoc。
- `provider/oracle/OracleSqlRunner.java`、`provider/postgres/PgSqlRunner.java`（改）：循环内以 policy 决策替换 `break`。
- `fx/SqlEditorPane.java`（改）：`doExecute` 传入 UI 实现的 policy（`Platform.runLater` + `CountDownLatch` + 三按钮 `Alert`）。

### 不做

- 不做整批事务包裹（沿用现状：各语句自身事务 / 自动提交）。
- 不做「记住选择」的持久化设置（`CONTINUE_ALL` 仅在当前这一批内有效）。

---

## ③ 注释快捷键

### 方案

在 `editorArea` 现有的 `KEY_PRESSED` 事件过滤器（当前处理 F5）中追加两个绑定：

- **`Ctrl+/`（行注释切换）**：
  - 计算选区跨越的整行范围（无选区则取光标所在行）；
  - 若这些**非空行全部**以 `--`（跳过行首空白后）开头 → 去掉每行的 `-- ` / `--` 前缀（取消注释）；
  - 否则给每行行首插入 `-- `（添加注释）；
  - 用 RichTextFX `CodeArea` 段落 API（`getParagraph`、段落起止偏移）计算行范围并 `replaceText`。
- **`Ctrl+Shift+/`（块注释切换）**：
  - 有选区且选区文本被 `/*` `*/` 包裹 → 去壳；
  - 否则用 `/* */` 包裹选区；
  - 无选区时在光标处插入 `/*  */` 并将光标置于中间两空格处。

改动后调用 `applyHighlighting(editorArea.getText())` 重算高亮。纯 UI 交互，不涉及 SPI / Service。

### 组件

- `fx/SqlEditorPane.java`（改）：`editor()` 中扩展 KEY_PRESSED 过滤器；新增私有方法 `toggleLineComment()`、`toggleBlockComment()`。

### 测试

行/块注释切换涉及 `CodeArea` UI 状态，纯函数部分（如「给定行文本列表判断是否全部已注释 / 生成切换后文本」）可抽为静态纯函数并单测；UI 绑定部分靠手工回归。

---

## 影响面与回归

- 改动集中在 SQL 编辑器与执行链：`SqlFormatter`、`SqlRunner` 接口及两实现、`SqlEditorPane`、新增 `ScriptErrorPolicy`。
- `executeScript` 签名变更仅影响一个调用点，编译期即可暴露遗漏。
- 手工回归：
  - 美化：多层嵌套子查询 / 派生表 / CTE / IN 子查询 / 函数参数；
  - 批量：多语句中间一条失败 → 弹窗「继续/全部继续/取消」三条路径；全部成功不弹窗；
  - 注释：单行 / 多行选区加与去行注释；块注释包裹与去壳；无选区插入。

## 假设

- 沿用 `SqlScriptSplitter` 现有分句能力，不在本组改进分句。
- 嵌套缩进档定为每层 `RIVER + 2 = 8` 空格（经用户确认）。
- 弹窗保留「全部继续」按钮（经用户确认）。
