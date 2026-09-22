# MERGE SQL 美化增强

状态：已实现，等待本地整合。

## 用户问题

Oracle 常见 `MERGE INTO ... USING ... WHEN MATCHED ... WHEN NOT MATCHED ...` 在原有词法排版器中会被误当作普通文本：动作边界挤在同一行，`INSERT (列列表)` 也可能出现函数调用式的粘连，复杂 upsert 难以审阅。

## 本轮行为

- 识别 `MERGE` 语句并将 `USING`、`ON`、`WHEN MATCHED`、`WHEN NOT MATCHED` 作为可定位的逻辑边界。
- 保持 `MERGE INTO target`、`WHEN ... THEN` 等短语同一行；动作体中的 `UPDATE SET`、`INSERT (...) VALUES (...)` 继续复用现有 DML 与列表排版。
- 子查询源在进入/退出时保存并恢复 MERGE 上下文，避免内部 `SELECT` 的状态泄漏到外层动作。
- 仍只做关键字大小写和空白排版，不推断 MERGE 语义、不连接数据库、不执行脚本。

## 明确不做

- 不校验 Oracle 的 `MERGE` 语法、触发器副作用或目标表约束。
- 不将通用词法格式化器扩展为完整 Oracle/PostgreSQL 方言解析器。
