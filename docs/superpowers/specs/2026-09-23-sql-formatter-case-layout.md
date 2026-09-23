# SQL 美化：CASE 表达式层次

## 范围

基线 144e927。继续改善复杂 SQL 可读性，只改 SqlFormatter 与聚焦回归，不改变编辑器入口、SQL 执行、连接或文件保存。

当前 CASE 使用一个深度计数，所有 WHEN/ELSE/END 都在同一列；WHERE 中的 CASE 分支 AND/OR 被外层条件布局处理，BETWEEN 状态也可能越过表达式边界。函数及子查询组合缺乏清晰层次。

## 排版规则

1. 顶层 CASE 沿用当前表达式位置；WHEN/ELSE 比所属 CASE 缩进 4 格，END 与所属 CASE 对齐，保留后续别名和函数参数。
2. 嵌套 CASE 独立起行，比父 CASE 缩进 8 格（父分支 4 格 + 子表达式 4 格）。不改条件/返回值顺序。
3. CASE 内普通条件和结果行不冒充查询级子句；分支 AND/OR/BETWEEN 保持表达式内部布局，不读写外层 BETWEEN 待配对状态。
4. 子查询/窗口保存并隔离 CASE 缩进栈；CASE 内的结构化括号增加适当缩进，退出后恢复表达式，END 后恢复外层列表/条件。
5. 行注释后的分支不插入多余空行；后续表达式仍保持缩进。多语句状态相互独立。

规则是 DataCube 的可读性选型，不宣称某一种布局是数据库标准。[PostgreSQL CASE](https://www.postgresql.org/docs/current/functions-conditional.html#FUNCTIONS-CASE) 与 [Oracle CASE Expressions](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CASE-Expressions.html) 用于核对简单/搜索式 CASE、嵌套和表达式组合的语法范围。

## 验收

完整输出和二次美化断言覆盖简单/嵌套 CASE、WHERE 条件、外层 BETWEEN、函数、子查询、窗口与注释。先运行反例，再定向与完整回归、免安装镜像打包；不使用真实连接、不执行 SQL、不新增第三方解析器。仍是词法排版，不是完整方言或语义校验。
