# 复杂 SQL 排版上下文隔离验收

## 基线与失败证据

基线 `f72818e`。在生产代码未改动时先增加 8 个测试实例，格式化定向测试共 29 项，其中 7 项失败：函数内子查询、函数包裹窗口、3 个窗口帧样例、嵌套 UNION 和 MERGE 跨语句状态。失败来自完整输出差异，不是编译错误。另一个嵌套函数样例在基线上通过，用于保留兼容行为。

修复后增加 EXCLUDE GROUP、窗口内子查询及条件分组内子查询三个组合边界；累计新增 11 个测试实例。

## 行为证据

| 行为 | 精确测试名 |
| --- | --- |
| 函数内子查询展开及外层列表恢复 | `scalarSubqueryInsideFunctionRestoresOuterLayout` |
| 函数包裹窗口，保留函数参数并恢复 SELECT 列表 | `windowInsideFunctionRestoresArgumentsAndSelectList` |
| 窗口内普通函数的 FROM 等关键字保持行内 | `nestedWindowFunctionsKeepTheirOwnKeywordsInline` |
| ROWS/RANGE/GROUPS、BETWEEN 边界及四种 EXCLUDE | `windowFrameBoundariesHaveTheirOwnLines`（4 实例） |
| 子查询内 UNION 保持当前块缩进 | `nestedSetOperatorsStayInsideTheirQueryBlock` |
| 子查询 → 窗口 → 函数的逐层恢复 | `subqueryRestoresWindowListAndEnclosingFunction` |
| 子查询退出后保留条件分组与外层 AND | `subqueryInsideGroupedPredicateRestoresOuterBooleanClauses` |
| MERGE 结束后不污染 JOIN USING | `mergeContextEndsAtStatementBoundary` |

上述嵌套/窗口样例断言完整输出与幂等性；多语句用例断言组合格式化等于逐句格式化结果，明确检测状态泄漏。

## 通过的命令

最终定向回归：

```text
gradlew.bat test --tests '*SqlFormat*' --no-daemon --console=plain
```

exit 0，69 项通过（SqlFormatterTest 32、SqlFormatScopeTest 21、SqlFormatActionTest 16）。先前单类定向命令亦通过；新增全部边界以后以本条命令及下述全量结果为准。

完整回归：当前 PowerShell 进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```text
gradlew.bat clean test --no-daemon --console=plain
```

exit 0，2m 37s。XML 汇总：262 suites / 3313 tests / 3310 passed / 0 failures / 0 errors / 3 既有 live skips。SqlEditorFormatIntegrationTest 15 项也全部通过，含选区范围外文本不变、撤销恢复、快捷键准入等合成 JavaFX 行为。仍有既有 unchecked 测试编译提示。

免安装镜像（独立命令进程，不带测试用 JAVA_TOOL_OPTIONS）：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

exit 0，51s；保留 jlink 的 JEP 493 模块假设提示，不宣称零警告。另执行 `git diff --check` 通过。

## 边界

没有新增依赖、连接数据库、执行样例 SQL、改动文件存储或编辑器 UI。本轮未做真人桌面点击、真实 Oracle/PostgreSQL、远端 CI 或发布验收。词法排版不等于语法解析或语义等价证明；执行前仍须检查 SQL。

本轮只使用单一类的聚焦测试流程，未创建测试过程状态文件；根工作区 `.testagent/` 保持不动。
