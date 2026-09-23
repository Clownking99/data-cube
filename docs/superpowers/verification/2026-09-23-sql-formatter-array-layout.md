# SQL 美化数组与下标验收

## 基线与复现

基线 `7089458`。合成文本复现：

- `ARRAY[1,2]` 在 SELECT 中被拆为外层列样式，数组闭合带多余空格。
- `matrix[1][2]` 输出为 `matrix [ 1 ] [ 2 ]`。
- 数组内窗口结束后的逗号错误触发外层 SELECT 换行。

先新增 21 项用例，SqlFormatterTest 共 199 项，19 项未达到预期，既有 178 项通过。实现后剩一项失败是测试期望遗漏了既有 ORDER 河道前导的一格空白；核对 startClause 的 RIVER=6 与 ORDER 长度后校正期望，未为该断言改变原有 ORDER 布局。

随后将不匹配定界符检查加强为完整输出断言，并补齐数组位于函数/CASE 内、子查询构造数组后取下标、负数元素和切片注释，共新增 25 项。

## 行为证据

| 行为 | 精确测试名 |
| --- | --- |
| 元素逗号行内，后续列、WHERE 和 AND 恢复外层规则 | `arrayElementsStayInlineAndOuterSelectListResumes` |
| 多维/空数组、类型后缀、括号表达式下标和切片 | `nestedAndEmptyArraysKeepBracketAndTypeSuffixSpacing` |
| 13 种切片、空边界、冒号隔离、绑定/命名参数、转换、括号和负数组合 | `slicesKeepColonTokenBoundariesAndBindings` |
| 数组内部的子查询独立排版，返回后恢复数组与外层列 | `subqueryInsideArrayRestoresArrayElementsAndOuterColumns` |
| 子查询中的数组不影响 WHERE、外层条件和 ORDER 列表 | `arrayInsideSubqueryDoesNotSuppressOuterWhereAndOrderLists` |
| 数组 → 窗口与窗口 → 数组，两向嵌套返回后恢复列表 | `windowInsideArrayRestoresArrayAndOuterWindowOrderList` |
| CASE 内绑定保留间距，CASE/数组结束后恢复列及后续语句 | `caseInsideArrayKeepsElementsAndFollowingStatementSeparate` |
| 引用/注释中的方括号不改变栈，保留行注释边界 | `bracketsInQuotesAndCommentsDoNotChangeArrayNesting` |
| 切片冒号后的块/行注释保留文本和行边界 | `commentsAfterSliceColonKeepTextAndLineBoundaries` |
| 数组位于函数/CASE 中时不影响参数和表达式结束 | `arraysInsideFunctionAndCaseRestoreTheirContainingExpressions` |
| 子查询构造的数组可取下标，并继续排列外层列 | `arrayFromSubqueryCanBeSubscriptedWithoutLosingOuterColumns` |
| 不匹配闭合符号不误弹出另一类型的栈帧，保留符号及后续列 | `mismatchedClosingDelimitersDoNotPopAnotherFrameOrLoseText` |

所有新增用例最终均使用完整期望文本和二次美化断言。测试不将容错输出宣称为语法有效；含命名绑定的数组用例用于文本边界验证，不宣称其无需客户端处理即可在数据库执行。

自检：方括号帧只由对应闭合符号弹出；普通深度计入方括号，并沿既有子查询/窗口上下文保存恢复。冒号压紧仅限栈顶方括号层，函数参数不使用该规则。连续冒号在独立 token 情况下保留空格，不能把 `: :upper` 拼成 `::upper`。引用与注释仍由词法层整体保留。

## 定向回归

```text
gradlew.bat test --tests '*SqlFormat*' --no-daemon --console=plain
```

exit 0，12s。XML：SqlFormatterTest 203、SqlFormatScopeTest 21、SqlFormatActionTest 16，共 240 项全部通过。

## 全量首轮异常与复查

第一次 `clean test` 在 2m 33s 结束：3484 项，1 失败、3 既有 live 跳过。失败为 `SchemaDiffServiceTest.providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview`，异常为脱敏后的 `Schema snapshot failed`；格式化相关测试全部通过。

检查调用路径，SchemaDiffService 不调用 SqlFormatter，生产中 SqlFormatter 的入口仍为 SqlFormatScope。未更改 SchemaDiff 生产代码或测试，也未新增跳过、放宽断言或修改超时。

```text
gradlew.bat test --tests com.datacube.service.SchemaDiffServiceTest --no-daemon --console=plain
```

相同代码和测试环境下，单独复跑 exit 0，12s，3 项全部通过。首次异常未给出底层原因，不将这次失败宣称为已定位或已修复。

## 最终完整回归

当前命令进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，再次运行：

```text
gradlew.bat clean test --no-daemon --console=plain
```

exit 0，2m 59s。XML：262 suites / 3484 tests / 3481 passed / 0 failures / 0 errors / 3 既有 live skips（Redis 1、SchemaDiff 2）。SchemaDiffServiceTest 的 3 项和 SqlEditorFormatIntegrationTest 的 15 项全部通过；首次失败未在此次全量复跑中重现。仍有既有 SqlEditorResultFilterContractTest unchecked 编译提示。

免安装镜像在不携带测试用 JAVA_TOOL_OPTIONS 的独立命令进程构建：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

exit 0，38s。保留既有 jlink 的 JEP 493 模块假设提示，不宣称零警告。`git diff --check` 通过。

## 依据与验收边界

布局参照 [PostgreSQL 18 数组说明](https://www.postgresql.org/docs/18/arrays.html)和[数组表达式](https://www.postgresql.org/docs/18/sql-expressions.html#SQL-SYNTAX-ARRAY-CONSTRUCTORS)。不验证维度、类型或真实执行结果，不新增 SQL Server 方括号引用标识符支持。

生产改动仅 SqlFormatter；未新增依赖、设置或 UI 操作，未更改连接、执行和文件保存路径。所有 SQL 为合成文本，没有读取真实历史/凭据或执行数据库查询；无真实数据库、原生桌面、安装升级或远端 CI 验收。

使用聚焦测试技能先复现后修复并检查断言，不创建测试过程状态文件；用户根目录 `.testagent/` 未读取、修改或暂存。本轮只本地提交并合并 main，不推送、不打 tag、不发布。
