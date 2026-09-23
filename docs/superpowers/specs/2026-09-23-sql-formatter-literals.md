# SQL 美化：引用文本与注释边界

## 产品范围

继续处理用户提出的复杂 SQL 美化问题。本轮聚焦 `SqlFormatter` 的词法边界；不新增编辑器按钮、不改变选区/撤销规则、SQL 执行或连接行为。基线为 `a24388d`。

已有分词器对 Oracle q 引用的结束符多要求一个单引号，导致正常结束的引用吞掉后续 SQL，后面的子句和语句不再排版。带前缀的字符串被拆开，E 字符串转义引号也会被错误视作结束；未闭合文本仍然进入排版，边界空白被裁剪。

## 实现约定

1. Oracle q/nq 作为一个 token 保留。成对分隔符使用对应闭符号，其余使用相同分隔符，闭符号后紧接单引号才结束；前缀大小写与内容不改。
2. E/N/B/X 单引号文本及 U& 单/双引号文本保留前缀连接。仅显式 E 字符串启用反斜杠转义，普通字符串使用双单引号规则；不读取或猜测 `standard_conforming_strings` 等会话设置。
3. PostgreSQL 跨行续接字符串作为整体保留，包含必要的换行、缩进和中间行注释；后续片段继承第一段的 E 转义模式。块注释不作为该续接规则的连接部分。
4. 行注释以 LF 或 CR 结束（同时覆盖 CRLF）；嵌套块注释整体保留，内部引号/分号/SQL 关键字不参与排版。
5. 引号、dollar quote 或块注释未闭合时，分词报告失败，整个本次输入原样返回，包括之前完整的语句及边界空白。不把“未闭合”伪装为成功格式化。
6. dollar 参数和普通标识符不误识别为引用；沿用 dollar 标签大小写敏感匹配。无完整 dollar 起始分隔符时按普通 token 处理。

## 依据

- [Oracle Literals](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/Literals.html)：q/nq 分隔符及 national 字符串。
- [PostgreSQL Lexical Structure](https://www.postgresql.org/docs/current/sql-syntax-lexical.html)：显式转义、Unicode/bit 字符串、dollar quote、跨行续接和嵌套块注释。
- [PostgreSQL scanner](https://github.com/postgres/postgres/blob/master/src/backend/parser/scan.l)：`quotecontinue` 接受包含换行的空白和行注释；只在续接成功时继承前段的字符串状态。

## 验收与限制

先加入反例复现，再使用完整期望输出检查引用内容不变、后续 SQL 确实继续排版以及二次美化不变；未完成输入精确比较整段原文。运行格式化定向测试、完整 clean test 和 jpackageImage。

这是离线词法排版，不是完整方言解析或 SQL 语义等价证明。未知方言与非默认会话语义仍需执行前检查；本轮不连接真实 Oracle/PostgreSQL、不执行样例 SQL、不上传用户文本。原有选区范围和一次撤销语义由既有集成回归保留。
