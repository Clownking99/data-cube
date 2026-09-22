# 复杂 SQL 美化增强

状态：已实现，等待本地整合。

## 用户问题

原有词法排版器能处理基础 SELECT 和简单子查询，但复杂 SQL 中存在明显可读性问题：`WHERE(`、`IN(`、`OVER(` 和 `INSERT INTO t(` 粘连；CTE 列表挤在一行；CASE、窗口定义和集合运算符缺少可扫描边界；PostgreSQL dollar quote、Oracle q quote、绑定变量与 JSON/类型转换运算符可能被误拆。

## 本轮行为

- 普通括号按上下文决定是否留空格：函数调用保持 `COUNT(*)`，谓词和列表使用 `IN (`、`WHERE (`、`INSERT INTO t (`、`OVER (`。
- CTE 逗号换行，集合运算符 `UNION` / `INTERSECT` / `EXCEPT` / `MINUS` 独立成行。
- CASE 的 `WHEN`、`ELSE`、`END` 形成独立逻辑行；窗口定义中的 `PARTITION`、`ORDER`、`ROWS`、`RANGE`、`GROUPS`、`EXCLUDE` 展开成可扫描行。
- PostgreSQL dollar-quoted body、Oracle q-quoted literal、反引号标识符作为不透明 token；绑定变量保持为 `:name` / `:1`；`::`、`->`、`->>`、`#>`、`#>>` 等复合运算符不被拆开。
- 保持无数据库、无网络的纯函数实现；只调整空白和关键字大小写，不声称完成完整方言语法解析。

## 明确不做

- 不引入第三方 SQL parser 或方言依赖。
- 不自动修正语法错误、不推断表关系、不改写 SQL 语义。
- PL/SQL/PLpgSQL 过程体仍以词法安全为边界；需要执行前人工检查，尤其是未被字符串或 dollar quote 包裹的过程分号。
