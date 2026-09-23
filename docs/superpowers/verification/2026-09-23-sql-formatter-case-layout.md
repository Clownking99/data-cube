# SQL 美化 CASE 层次验收

## 基线与复现

基线 `144e927`。在生产代码未修改时先加入 8 个精确输出测试；SqlFormatterTest 共 127 项，新增 8 项全部失败。复现嵌套 CASE 无独立层级、WHERE 分支 AND/OR 错用外层布局、函数内 END 对齐错误、子查询/窗口组合缩进和行注释后多余空行。

修复后 127 项通过。进一步加强外层 BETWEEN 与分支 AND 的交叉测试，另加窗口包含 CASE 的反向嵌套、条件中行注释、无 ELSE 的相邻/跨语句 CASE 和 MERGE 后续动作，累计新增 12 项。

## 行为证据

| 行为 | 精确测试名 |
| --- | --- |
| 简单 CASE 的分支、END、别名与后续列 | `simpleCaseBranchesIndentAndEndAlignsWithCase` |
| 嵌套 CASE 的独立分支和 END 层级 | `nestedCasesHaveIndependentBranchAndEndIndentation` |
| CASE 内 AND/OR/BETWEEN 不冒充外层 WHERE 条件 | `caseConditionsDoNotBecomeOuterWhereClauses` |
| 外层 BETWEEN 的 AND 不被 CASE 分支中的 AND 消耗 | `casePreservesOuterBetweenPairing` |
| 函数内 CASE 结束后恢复参数和 SELECT 列表 | `caseInsideFunctionRestoresArgumentsAndFollowingColumns` |
| CASE → 子查询 → CASE 各自独立，返回后恢复外层 ELSE | `caseSubqueryHasItsOwnCaseStackAndRestoresOuterBranches` |
| 窗口排序中的 CASE 结束后恢复排序列表 | `caseInsideWindowRestoresFollowingOrderColumns` |
| CASE → 窗口 → CASE 的反向嵌套和恢复 | `windowInsideCaseIsIndentedAndRestoresOuterElse` |
| 行注释后不多添空行 | `lineCommentsBetweenCaseBranchesDoNotAddBlankLines` |
| SELECT 后注释、条件中注释及后续语句布局 | `commentsBeforeCaseAndInsideConditionKeepContinuationIndented` |
| 无 ELSE、同级 CASE、多语句缩进不泄漏 | `siblingCasesWithoutElseDoNotLeakIndentation` |
| MERGE 的下一个 WHEN 不冒充 CASE 分支 | `mergeActionAfterCaseIsNotTreatedAsCaseBranch` |

所有新增样例均断言完整期望输出，并再次格式化验证幂等性；不以“含有 WHEN”或去空白比较替代缩进断言。期望输出不由被测函数生成。参数/列列表、外层谓词、ELSE 与 MERGE 后续动作分别验证进出表达式的状态恢复。

## 通过的命令

最终定向回归：

```text
gradlew.bat test --tests '*SqlFormat*' --no-daemon --console=plain
```

exit 0，12s；168 项通过（SqlFormatterTest 131、SqlFormatScopeTest 21、SqlFormatActionTest 16）。

完整回归：当前 PowerShell 进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```text
gradlew.bat clean test --no-daemon --console=plain
```

exit 0，4m 23s。XML 汇总：262 suites / 3412 tests / 3409 passed / 0 failures / 0 errors / 3 既有 live skips。SqlEditorFormatIntegrationTest 15 项通过，保留选区范围外文本、换行和一次撤销的既有集成回归。没有为本轮新增跳过或降低断言。仍有既有 unchecked 测试编译提示。

免安装镜像使用不带测试用 JAVA_TOOL_OPTIONS 的独立命令进程：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

exit 0，59s。保留 jlink 的 JEP 493 模块假设提示，不宣称零警告。`git diff --check` 通过。

## 验收边界

仅改词法美化器和测试，不新增依赖、不改编辑器操作、连接、执行或保存路径；未连接真实数据库、读取真实 SQL 历史/凭据或执行示例 SQL。本轮不做真人桌面、真实数据库、安装升级或远端 CI 验收。嵌套布局是产品选型，不是完整 SQL/PLSQL 语法或语义等价保证。

使用聚焦测试流程完成反例、精确回归及自检，未创建测试过程状态文件；根工作区的 `.testagent/` 未读取、修改或暂存。只本地提交与合并，不推送、打 tag 或发布。
