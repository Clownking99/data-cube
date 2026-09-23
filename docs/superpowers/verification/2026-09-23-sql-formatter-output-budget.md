# SQL 美化输出预算验收

## 基线与复现

基线 `e21eb12`。在未修改生产代码时新增 6 项测试，SqlFormatterTest 共 137 项，其中 4 项失败：两种小输入深层膨胀、上限后一单元及多语句累计均没有被生成器拒绝。上限前一单元和恰好上限两项通过。

使用该基线编译产物与合成 SQL 只测量字符串长度，未执行 SQL：

| 构造 | 输入 UTF-16 单元 | 排版输出 UTF-16 单元 |
| --- | ---: | ---: |
| 800 层 CASE | 24,820 | 10,280,815 |
| 1,100 层子查询 | 9,908 | 9,698,708 |

两种输入都小于编辑器单次 256 Ki 限制，但输出超过 8 Mi。原有编辑器最终结果检查仍会拒绝替换；缺口是生成前没有预算控制，而不是原本会把超限结果写入编辑器。本轮没有测得或宣称实际 OOM、崩溃或固定耗时改进。

## 行为证据

| 行为 | 精确测试名 |
| --- | --- |
| 短输入深层 CASE/子查询超限拒绝；下次正常美化仍完整成功 | `smallDeepInputsRejectExcessiveOutputAndNextCallStillWorks` |
| 含代理对的文字按 UTF-16 计数；上限前一单元/恰好/后一单元 | `outputBudgetCountsUtf16UnitsAtTheExactBoundary` |
| 可接受的大结果保留所有 CASE/END、结尾及幂等性；跨语句不重置预算 | `statementsShareOutputBudgetWithoutTruncatingAcceptedResults` |
| 生成的尾部换行在 strip 前也计入预算，追加单字符同样受保护 | `generatedLineBreakCountsBeforeTrailingWhitespaceIsStripped` |
| 既有编辑器失败路径保留文本、反向选区、撤销及回调状态；不暴露异常文本 | `SqlFormatActionTest.planningFailureKeepsEditorAndDoesNotExposeExceptionText` |

最后一行为既有的注入失败回归，非本轮新增桌面验收。新增共 9 项参数化用例；边界大字面量直接测试无 UI 的格式化器，不表示编辑器允许超过 256 Ki 的单次输入范围。

自检所有输出追加点均走 BoundedOutput；整段缩进先检查剩余空间再生成，不先分配超限空格字符串。工作缓冲区按单次调用拥有，分号不重置，收尾空格删除只缩短缓冲区。异常消息固定，不含输入文本。8 Mi 是工作缓冲区内容长度上限，不是总堆占用上限，也不承诺格式化耗时上限。

## 通过的定向命令

```text
gradlew.bat test --tests '*SqlFormat*' --no-daemon --console=plain
```

exit 0，16s。XML：SqlFormatterTest 140、SqlFormatScopeTest 21、SqlFormatActionTest 16，共 177 项全部通过。修复初轮 SqlFormatterTest 的 137 项也全部通过；随后补齐了尾部换行的 3 个边界用例。

## 完整回归

当前命令进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后运行：

```text
gradlew.bat clean test --no-daemon --console=plain
```

exit 0，2m 57s。XML 汇总：262 suites / 3421 tests / 3418 passed / 0 failures / 0 errors / 3 既有 live skips（Redis 1、SchemaDiff 2）。SqlEditorFormatIntegrationTest 15 项通过。没有为本轮新增跳过或修改全量测试断言；仍有既有 SqlEditorResultFilterContractTest unchecked 编译提示。

免安装镜像使用独立命令进程，不携带测试用 JAVA_TOOL_OPTIONS：

```text
gradlew.bat jpackageImage --no-daemon --console=plain
```

exit 0，49s；保留既有 jlink 的 JEP 493 模块假设提示，不宣称零警告。`git diff --check` 通过。

## 验收范围

生产改动仅 SqlFormatter，沿用既有编辑器错误提示和原文保护，不改 SQL 执行、连接、文件保存或 UI 路径。本轮不访问真实数据库、SQL 历史或凭据；无真实数据库、原生窗口、安装升级及远端 CI 验收。

使用聚焦测试技能先复现再修复；不创建测试过程状态文件，用户根目录 `.testagent/` 不读取、不修改、不暂存。本轮只本地提交并合并回 main，不推送、打 tag 或发布。
