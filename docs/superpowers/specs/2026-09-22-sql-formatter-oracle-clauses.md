# Oracle 层级查询与 DDL 动作美化增强

状态：已实现，等待本地整合。

## 用户问题

Oracle SQL 中 `START WITH`、`CONNECT BY`、`ORDER SIBLINGS BY` 是完整短语；若格式化器把 `START`/`WITH` 或 `CONNECT`/`BY` 拆散，复杂层级查询难以审阅。外键 DDL 的 `ON DELETE CASCADE` 也不应被拆成 `ON` 与动作两行，`CASCADE` 需要统一关键字大小写。

## 本轮行为

- 将 `START WITH`、`CONNECT BY` 和 `ORDER SIBLINGS BY` 保持为可扫描的层级查询边界。
- 补齐 `CASCADE`、`RESTRICT`、约束延迟/启停等常见 Oracle DDL 关键字，并保持 `ON DELETE/UPDATE ...` 动作同一逻辑行。
- 外键 `KEY (...)` 与 `REFERENCES table (...)` 列表使用声明式空格；普通函数调用规则不变。
- 仅调整词法排版与关键字大小写，不连接 Oracle、不验证层级查询语义。

## 明确不做

- 不推断 `CONNECT BY` 的循环、优先级或数据层级结果。
- 不将关键字补齐误认为完整 Oracle 方言语法解析。
