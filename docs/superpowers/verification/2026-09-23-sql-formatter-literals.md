# SQL 美化引用文本与注释边界验收

## 基线与失败证据

基线 `a24388d`。生产代码未修改时先加入 34 个测试实例：`SqlFormatterTest` 共 66 项，28 项失败。失败涵盖 Oracle q/nq 吞掉后续 SQL、字符串前缀分离、CR/CRLF 行注释边界、嵌套块注释及未闭合输入被改写，不是编译或环境错误。

首轮修复与跨行字符串测试通过后，继续加入空引用、普通字符串末尾反斜杠、未闭合续接、大小写不匹配 dollar 标签、参数/标识符和带行注释的续接边界。83 项中带行注释续接的 3 项失败，证明续接丢失 E 转义模式及原有空白；修复该路径后全部通过。累计新增 51 个测试实例。

## 行为证据

| 行为 | 精确测试名 |
| --- | --- |
| Oracle 成对/自定义/单引号分隔符、q/nq、空引用，引用后 SQL 继续排版 | `oracleQuotedTokensDoNotSwallowFollowingSql`（9 实例） |
| E/N/B/X/U& 前缀连接、E 转义和空字符串 | `literalPrefixesAndEscapesStayAttached`（9 实例） |
| 普通/双引号/反引号/dollar 文本，普通字符串反斜杠及空文本 | `existingQuotedFormsRetainExactContents`（7 实例） |
| 跨行续接、继承 E 转义模式，保留间隔行注释与换行 | `continuedStringsRetainRequiredLineBreakAndEscapeMode`（5 实例） |
| LF、CRLF、CR 行注释终止后 SQL 继续排版 | `lineCommentEndsAtEveryPhysicalLineSeparator`（3 实例） |
| 嵌套块注释内部 SQL、引号和分号保持原文 | `nestedBlockCommentsRemainOneOpaqueToken` |
| 未闭合引用/块注释、标签不匹配和未闭合续接，整段输入原样返回 | `unfinishedQuotedTextOrCommentLeavesWholeInputUntouched`（15 实例） |
| 参数与含前缀的标识符不会被误作引用 | `dollarParametersAndIdentifierPrefixesAreNotQuotedLiterals` |
| 文件末尾行注释不需要闭合，内部引号不误触发保护 | `trailingLineCommentDoesNotRequireAClosingDelimiter` |

自检：期望值为明确的完整排版字符串，不从被测函数生成；引用参数逐字嵌入期望值以检查内容保真，同时断言引用后两条 SQL 的布局，避免“整段原样返回”假通过。成功引用、续接及注释边界另检验二次格式化不变。未闭合样例前放完整语句，并保留前后空白，精确比较整段输入，验证原子回退而非只保留尾部。

## 通过的命令

最终格式化定向回归：

```text
gradlew.bat test --tests '*SqlFormat*' --no-daemon --console=plain
```

exit 0，10s；120 项通过（SqlFormatterTest 83、SqlFormatScopeTest 21、SqlFormatActionTest 16）。

完整回归：当前 PowerShell 进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```text
gradlew.bat clean test --no-daemon --console=plain
```

exit 0，2m 45s。XML 汇总：262 suites / 3364 tests / 3361 passed / 0 failures / 0 errors / 3 既有 live skips。SqlEditorFormatIntegrationTest 15 项通过，包含 `selectedSqlLeavesOtherStatementsAndPhysicalSeparatorsUntouched` 的选区外文本、物理换行和一次撤销恢复断言。保留既有 unchecked 测试编译提示。

免安装镜像：使用不含测试用 JAVA_TOOL_OPTIONS 的独立命令进程：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

exit 0，41s；保留 jlink 的 JEP 493 模块假设提示，不宣称零警告。`git diff --check` 通过。

## 验收边界

没有新增依赖、改动编辑器 UI、访问真实连接配置、连接数据库或执行样例 SQL。单元/合成 JavaFX 测试不替代真人桌面点击、真实 Oracle/PostgreSQL、远端 CI 或安装升级；这些本轮未执行。词法排版不等于完整方言语法解析或语义等价证明。

按聚焦测试流程完成反例、完整输出断言及自检，未创建测试过程状态文件。根工作区用户所有的 `.testagent/` 未读取、修改或暂存。本轮只本地提交/合并，不推送、打 tag 或发布。
