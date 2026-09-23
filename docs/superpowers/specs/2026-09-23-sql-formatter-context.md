# 复杂 SQL 排版上下文隔离

## 范围与复现

本轮沿用复杂 SQL 美化方向，只修改 `SqlFormatter` 及其回归用例，不改编辑器操作、连接、SQL 执行或文件存储。

基线 `f72818e` 存在组合场景遗漏：

- `COALESCE((SELECT ...), 0)` 的子查询继承了函数的普通括号深度，内部 FROM/WHERE 不换行。
- 函数包裹 `OVER (...)` 时，外层括号深度抑制窗口列表换行；窗口状态也没有完整保存/恢复。
- 已有窗口排版分支依赖 KEYWORDS，但 ROWS/RANGE/GROUPS/EXCLUDE 未入集合，窗口边界排版实际未启用。
- 子查询里的 UNION 顶格输出，视觉上脱离所属块。
- MERGE 的模式未在分号结束时清理，随后 JOIN USING 的排版受前一条语句影响。

## 实现约定

1. 单一括号栈显式区分普通括号、子查询、窗口；结构化括号保存包括普通括号深度在内的完整外层状态。
2. 进入子查询/窗口时建立局部排版上下文，退出时恢复；普通函数参数及条件分组继续采用行内排版。
3. 窗口定义只触发窗口子句规则。内部函数/CASE 中的关键字不冒充窗口顶层子句，EXCLUDE GROUP 不冒充查询 GROUP BY。
4. 窗口 ROWS/RANGE/GROUPS 与 EXCLUDE 独立成行；BETWEEN ... AND ... 边界保持同一行。词法样例参照 [PostgreSQL Window Function Calls](https://www.postgresql.org/docs/current/sql-expressions.html#SYNTAX-WINDOW-FUNCTIONS)。
5. 集合运算沿用当前查询块缩进，语句结束清理 MERGE/CASE 状态。

## 验收要求

- 精确断言完整输出，而非仅断言存在关键字；覆盖内层布局及退出后的参数、SELECT 列表和条件恢复。
- 每个嵌套/窗口样例验证二次格式化输出不变，另以组合格式化等于逐句格式化检查语句隔离。
- 定向测试、完整 clean test 及 jpackageImage；保留选区和撤销集成测试。
- 不引入第三方解析器，不宣称完整 SQL 方言或语义等价校验，不连接真实数据库。
