# SQL 美化数值表达式验收

## 基线与复现

基线 `0a21bfa`；在未修改生产代码时先加入 34 个测试实例，SqlFormatterTest 共 117 项，22 项失败。实际输出包含 `1e - 3`、`1E + 03` 和 `SELECT.5`，另有残缺数值仍被部分美化的问题。失败来自完整文本断言，不是编译错误。

修复后 117 项通过。随后增加相邻运算符/数值样字段和长分组数字两个边界，共新增 36 个实例。

## 行为证据

| 行为 | 精确测试名 |
| --- | --- |
| 完整数值词法、前导/尾随小数点、指数、尾零、f/d 后缀、分组及进制；后续子句/语句继续美化 | `numericLiteralsStayWholeAndFollowingSqlIsFormatted`（20 实例） |
| 指数内正负号与外部加减乘运算、BETWEEN 边界分离 | `exponentSignsDoNotConsumeAdjacentArithmeticOperators` |
| 函数、类型转换和内嵌子查询中的数值 | `numericTokensComposeWithFunctionsCastsAndSubqueries` |
| 带数字字段、e/f/d 名称、限定名、绑定变量和引用正文不误识别为数值 | `numericLookingIdentifiersParametersAndQuotedTextStayUnchanged` |
| 前置负号、减法和 e/f 字段、十六进制末尾 e 不冒充指数 | `leadingSignsAndNumericLookingNamesRemainSeparateOperators` |
| 长分组数值逐字保留，无浮点转换或递归扫描 | `longGroupedNumberRetainsEveryDigitWithoutNumericConversion` |
| 残缺指数/进制、错误分组和粘连未知标识符，整段原文回退 | `incompleteOrAmbiguousNumericTokenLeavesWholeInputUntouched`（11 实例） |

自检：成功场景均精确比较完整输出及再次格式化的输出，检查数值原文、相邻 SQL 的布局和幂等性。没有通过去空白来掩盖数值内部插入空格；期望值不由被测方法生成。原文回退案例包含前一条完整语句及前后空白，检查的是整次操作，不是仅保留残缺尾部。不以“SQL 可执行”代替词法保真，也不以这些断言声称完整方言校验。

## 验证命令

最终定向回归：

```text
gradlew.bat test --tests '*SqlFormat*' --no-daemon --console=plain
```

exit 0，13s；156 项通过（SqlFormatterTest 119、SqlFormatScopeTest 21、SqlFormatActionTest 16）。

完整回归：在当前 PowerShell 进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```text
gradlew.bat clean test --no-daemon --console=plain
```

exit 0，3m 9s。XML 汇总：262 suites / 3400 tests / 3397 passed / 0 failures / 0 errors / 3 既有 live skips。SqlEditorFormatIntegrationTest 15 项通过，包含选区外文本及一次撤销恢复的既有回归。保留既有 unchecked 测试编译提示。

免安装镜像：独立命令进程不带测试用 JAVA_TOOL_OPTIONS：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

exit 0，52s；保留 jlink 的 JEP 493 模块假设提示，不宣称零警告。`git diff --check` 通过。

## 边界

仅修改美化器及回归，不新增依赖，不改变编辑器 UI、连接或执行路径；未连接真实数据库、访问真实凭据/SQL 历史或执行示例语句。本轮不做真人桌面、安装升级或远端 CI 验收。按聚焦测试流程完成精确回归，不创建测试过程状态文件，用户所有的 `.testagent/` 保持不动；只本地提交/合并，不推送、打 tag 或发布。
