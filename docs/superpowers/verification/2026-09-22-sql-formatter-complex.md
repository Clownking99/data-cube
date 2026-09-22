# 复杂 SQL 美化增强验收记录

## 自动化

定向命令：

```text
gradlew.bat test --tests com.datacube.sqleditor.SqlFormatterTest --no-daemon --console=plain
```

结果：通过，14 项格式化测试，0 失败。覆盖原有嵌套子查询、CTE、IN 子查询、幂等和语义保全，并新增：

- `complexDmlUsesReadableParenthesisSpacingAndClauseBoundaries`
- `caseAndWindowExpressionsHaveVisibleStructure`
- `cteListAndSetOperatorsBreakAtStatementBoundaries`
- `postgresDollarQuotedBodiesRemainOpaque`
- `oracleQQuotedLiteralRemainsOpaque`

这些用例分别锁定复杂 DML、CASE/窗口、CTE/集合运算、PostgreSQL 特殊字面量、Oracle 特殊字面量与绑定变量边界。

完整回归命令：

```text
gradlew.bat clean test --no-daemon --console=plain
```

结果：exit 0，262 suites / 3295 tests / 3292 passed / 0 failures / 0 errors / 3 既有 live skips。保留项目原有 unchecked 编译提示，不宣称零警告构建。

打包命令：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

结果：exit 0，免安装镜像生成成功。

## 结果样例边界

当前筛选的复杂查询会把 CASE、窗口定义、CTE 列表和集合运算拆成可定位的逻辑行，并修复常见的括号粘连。格式化后再次格式化保持相同输出。

## 未宣称事项

本轮未引入真实数据库或完整 SQL AST parser；未将词法格式化通过扩大为所有 Oracle/PostgreSQL 方言、PL/SQL/PLpgSQL 过程体、远端 CI 或正式发布验收。编辑器原有范围限制、撤销与执行前人工检查规则保持不变。
