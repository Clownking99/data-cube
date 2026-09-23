# SQL 美化：数值表达式保真

## 范围与决策

基线 0a21bfa。继续解决复杂 SQL 美化的实际缺陷，本轮只改 SqlFormatter 的数值分词及对应回归，不改 SQL 执行、连接或编辑器操作。

现有分词把科学计数法指数中的加减号当作独立运算符；以小数点开头的数值被拆成标点和数字，可能与 SELECT 等关键字粘连。收益是金额、比例、阈值与科学计数值的查询在美化后仍保持完整数值文本。

1. 数值独立分词：整数、小数（含 .5、1.）、e/E 指数及其正负号、Oracle f/F/d/D 后缀。
2. 沿用 PostgreSQL 下划线分组和十六/八/二进制整数原文。不解析为浮点值，不舍入、不改写精度或指数大小写。
3. 普通加减号仍是独立运算符；不将字段 e/f/d、绑定变量、带数字标识符或引用中的数值文本误识别为数字。
4. 数值扫描采用有界前进的迭代逻辑。发现残缺指数、进制前缀或非法分组等无法确定边界的数值 token 时，本次美化整体保留原文，不自动修复输入。
5. 保留后续子句、多语句分段及二次美化稳定性。已有选区范围、撤销和未闭合引用保护不变。

## 依据与限制

- [PostgreSQL Numeric Constants](https://www.postgresql.org/docs/current/sql-syntax-lexical.html#SQL-SYNTAX-CONSTANTS-NUMERIC)：小数/指数、分组下划线及非十进制整数词法形式。
- [Oracle Numeric Literals](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/Literals.html)：e/E 指数以及 f/F/d/D 浮点后缀。

上述规则仅用于完整保留文本，不判断数据库版本支持、数值范围或 SQL 语义。本轮不宣称完整方言解析，不执行示例 SQL、不连接真实数据库。按先复现、精确输出与幂等断言、定向/完整测试及免安装镜像打包验收。
