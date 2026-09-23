# SQL 美化运算符边界验收

## 复现

基线 `0c71219`。先新增 36 项参数化/普通用例，未改生产代码时 SqlFormatterTest 共 176 项，22 项失败；既有 140 项仍通过。

完整输出断言确认 `#-` 被拆成 `# -`、`@?` 变成 `@ ?`、`<->` 变成 `< ->`、`<=>` 变成 `<= >`、`=>` 变成 `= >`。另外已有枚举中的 `!~~*` 在括号前被当成函数名而无空格。

实现一次符号段扫描后，176 项全部通过。随后增加真实语法形状的 JSON/point 距离组合，以及前缀根号与普通算术紧邻注释的完整输出用例，共新增 38 项。

## 行为证据

| 行为 | 精确测试名 |
| --- | --- |
| 命名参数 => / :=、绑定变量、负数括号及 q 引用共同保留 | `namedArgumentsKeepArrowBindingsAndExpressionSpacing` |
| 16 种组合符号不被拆分，括号和后续列/子句间距一致 | `compoundOperatorsStayWholeAndSeparateFromParentheses` |
| 一元正负号、JSON 负索引、命名负参数、已有空白边界 | `operatorRunsRespectUnarySignsAndExistingWhitespace` |
| 注释起始中断运算符扫描，不吞掉行注释后的 SQL | `operatorScanStopsAtBlockAndLineComments` |
| JSON 路径/字段删除与 point 距离组成的完整 SELECT | `jsonPathDeletionAndGeometricDistanceKeepTheirSqlStructure` |
| 前缀根号与算术运算符紧邻块/行注释 | `prefixOperatorsAndArithmeticBeforeCommentsRemainSeparate` |
| 六种引用保持原文，同时正确处理后续 JSON 谓词、绑定与类型转换 | `quotedOperatorsStayOpaqueWhileFollowingJsonPredicateIsFormatted` |
| 8,192 个连续一元正号完整输出，二次美化一致 | `longUnarySignRunRetainsEverySignAndRemainsIdempotent` |

所有新增用例均断言完整期望文本和二次格式化结果；没有以去空白比较代替运算符边界验证。符号枚举中有仅用于测试词法边界的抽象操作数，不宣称每种组合都构成可执行或类型正确的表达式。

自检：每个连续符号段先扫描一次，再分离尾部正负号并一次输出；不反复扫描长符号段的剩余后缀。冒号组合走独立分支，绑定变量和引用先于运算符识别；注释在扫描中保留起始位置交回原有分词路径。反引号沿用引用名称解释，不支持其作为自定义运算符字符。未做耗时阈值测试或数据库语义验证。

## 定向验证

```text
gradlew.bat test --tests '*SqlFormat*' --no-daemon --console=plain
```

exit 0，11s。XML 汇总：SqlFormatterTest 178、SqlFormatScopeTest 21、SqlFormatActionTest 16，共 215 项全部通过。修复初轮单独的 SqlFormatterTest 命令也 exit 0（176 项，8s）。

## 完整回归

当前命令进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```text
gradlew.bat clean test --no-daemon --console=plain
```

exit 0，2m 29s。XML：262 suites / 3459 tests / 3456 passed / 0 failures / 0 errors / 3 既有 live skips（Redis 1、SchemaDiff 2）。SqlEditorFormatIntegrationTest 15 项全部通过。本轮没有新增跳过或修改其他测试的断言。仍有既有 SqlEditorResultFilterContractTest unchecked 编译提示。

免安装镜像使用不携带测试用 JAVA_TOOL_OPTIONS 的独立命令进程：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

exit 0，46s。保留既有 jlink 的 JEP 493 模块假设提示，不宣称零警告。`git diff --check` 通过。

## 依据与验收边界

词法规则来自 [PostgreSQL 18 运算符说明](https://www.postgresql.org/docs/18/sql-syntax-lexical.html#SQL-SYNTAX-OPERATORS)，命名参数与 JSON 符号参照[函数调用说明](https://www.postgresql.org/docs/18/sql-syntax-calling-funcs.html)及 [JSON 运算符说明](https://www.postgresql.org/docs/18/functions-json.html)。不根据这些文档推断实际数据库中的扩展安装、运算符定义或操作数类型。

仅 SqlFormatter 有生产改动，不增加依赖，不改 UI、连接、执行、文件保存或设置。测试均用合成文本；未读取真实 SQL 历史/凭据，未连接数据库，未执行 SQL。无原生桌面、真实数据库、安装升级或远端 CI 验收。

按聚焦测试技能先复现后修复并检查断言，不创建测试过程状态文件。用户根目录 `.testagent/` 未读取、修改或暂存。本轮仅本地提交与合并，不推送、不打 tag、不发布。
