# SQL 行注释验收

基线 `803bfb1`，独立 `codex/sql-line-comment`。[设计](../specs/2026-09-11-sql-line-comment.md)。使用现有 Java 25 / JavaFX 25 / JUnit Jupiter 5.11.3 / Gradle wrapper 9.2.0，无新增依赖或 CI 变更，不读取/更改用户 `.testagent/`。

## 行为与精确证据

| 要求 | 测试 |
|---|---|
| 反向/正向选区、混合 CR/LF/CRLF、Unicode、末端下一行排除，增删恢复 | `SqlLineCommentTest.selectedMixedSeparatorLinesPreserveDirectionAndExcludeNextLineStart`（2 组） |
| 非空选区的首行标记不被后续执行范围绕过；字符中间起选也包含新标记 | `selectedExecutionCannotBypassFirstInsertedCommentMarker`（4 组） |
| 只有缩进的选区添加后至少含标记，不回退全文 | `selectedIndentationIncludesMarkerInsteadOfFallingBackToAllSql`（2 组） |
| 折叠光标、换行处、末尾空行只改当前行 | `caretStaysCollapsedAndOnlyCurrentLineChanges`（5 组）、`caretWithinOrAfterCrLfHasUnambiguousPhysicalLine`（3 组） |
| 混合注释加一层、缩进/空白/Unicode 留存；去一层且最多移除一个普通空格，标记内部端点夹紧 | `mixedCommentsAddOneLayerAndBlankLinesAndIndentationStayUntouched`、`removingOneMarkerRetainsTrailingWhitespaceAndClampsInsideRemovedMarker` |
| 空内容/空白范围无编辑且端点不变 | `emptyAndWhitespaceOnlyRangesProduceNoEdit`（3 组） |
| 9,999/10,000/10,001 行与结果文本限额前/等/后；可仅改大脚本一行 | `lineLimitRejectsWholeOperationButStillAllowsEditingOneLine`、`resultingTextLimitIsExact`（各 3 组） |
| 输入 8 Mi 等值/超限、null、各端点负数/越界及编辑集合不可变 | `inputLimitInvalidSelectionAndImmutableEditsAreEnforced` |
| 按钮批量修改是独立撤销步，隔开前后输入，反选及重做准确 | `SqlLineCommentActionTest.buttonPreservesBackwardRangeAndSeparatesToggleFromTypingInUndoRedo` |
| 禁止编辑/只读/禁用/父禁用/关闭后的旧按钮不能改文本、选区、撤销 | `unavailableAndStaleButtonCannotChangeTextSelectionOrUndo`（5 组） |
| 空白和超限提示且完全不编辑；准备后第二次准入检查阻止应用 | `blankAndOverLimitHaveFeedbackButNeverEditOrCreateUndo`、`rechecksAdmissionAfterPlanAndBeforeAnyMutation` |
| 后台改绑在 FX 更新提示，持久化往返及关闭监听解除 | `backgroundShortcutChangeRefreshesHintOnFxAndCloseDetachesListener` |
| 实际 Pane 混合物理分隔符、反选、只读生产连接的被动意图、文件身份/字节、dirty/标题星号、撤销/重做及选中执行提取组合 | `SqlEditorLineCommentIntegrationTest.mixedPhysicalSeparatorsFileIdentityPassiveConnectionAndSelectionSurviveToggleUndoRedo` |
| 改绑即时生效、旧键不响应、Schema 输入不触发行注释，既有块注释组合键独立 | `shortcutRebindingIsLiveScopedToEditorAndBlockShortcutStaysSeparate` |
| 实际 Pane 准入/资源/任务/文件忙碌/终结/只读/父禁用拒绝旧按钮和快捷键 | `paneLifecycleGuardsRejectBothOldButtonAndShortcut`（7 组） |
| 草稿确实写入新文本但源文件未保存；freeze 后按钮/按键均不再编辑 | `toggleUsesDraftCheckpointWithoutSavingSourceAndFreezeBlocksFurtherEdits` |
| 明暗 600/880 按钮的实际文字完整并可用 | `visibleButtonAndActualTextStayInsideToolbarForBothThemes`（4 组） |
| 原有 480/640/880 明暗工具栏全体控件的完整标签、边界、未绑定执行/事务禁用不退化 | `SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor`（6 组，计数更新 12→13，额外断言新按钮身份，保留所有原布局断言） |

实际 Pane 均使用 @TempDir 和 DraftConnectionProbe，provider/session/metadata/network 全部断言为 0；文件和草稿测试只使用合成内容。没有调用真实数据库或为验证注释而执行 SQL。未收集覆盖率百分比。

## 修复过程与命令

1. 模型测试先行，缺失 SqlLineComment 时编译失败（15s、exit 1）；实现后模型及原草稿/缩进回归通过（17s）。
2. 新 UI 测试首次误用 TextArea 的 positionCaret，编译失败（10s）；改用本项目 CodeArea 的 moveTo 后，六类定向 78 项通过（15s）。
3. 首轮全量 2,216 项，6 个原工具栏布局用例因新增按钮后旧计数为 12 失败，3 既有 live 跳过（1m42s）；保留布局/可执行性断言，仅更新计数并增加新按钮身份断言。
4. 自审发现原字符映射会使选区越过新 `--`。先补四组“执行范围不绕过标记”回归，实际 4/4 失败（6s），再修正非空选区映射；另补两组缩进选区防全文回退。更新后的模型、动作、Pane、原草稿/缩进及可用性七类定向 115 项通过（14s、exit 0），无跳过：模型 28、动作 9、Pane 14、原草稿 17、原缩进动作 9/Pane 7、可用性 31。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.sqleditor.SqlLineCommentTest --tests com.datacube.fx.SqlLineCommentActionTest --tests com.datacube.fx.SqlEditorLineCommentIntegrationTest --tests com.datacube.fx.SqlEditorDraftIntegrationTest --tests com.datacube.fx.SqlIndentActionsTest --tests com.datacube.fx.SqlEditorIndentIntegrationTest --tests com.datacube.fx.SqlEditorUsabilityTest --no-daemon --console=plain
```

最终全量使用独占 ASCII ShortPath 临时目录作为 java.io.tmpdir，非 headless：2m、exit 0；XML 共 2,222 项，2,219 通过、0 失败/错误、3 项既有外部集成测试跳过。本轮新增 51 项全部运行。开发镜像也构建成功。既有 unchecked、JAVA_TOOL_OPTIONS 的工具链探测输出及 JEP 493 提示仍有，不称为无警告构建。

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

`jimage list` 确认镜像包含 SqlLineComment / SqlLineCommentAction，不含 SqlLineCommentDesktopFixture / DraftConnectionProbe；生产 cfg 入口仍为 `com.datacube/com.datacube.DataCubeFx`，版本为本地验收用 0.0.0。

## 合成桌面

Computer Use 只操作生产镜像的独立副本 `build/desktop-fixture-image/DataCube`。副本 cfg 指向测试入口、只含该入口的 patch-module jar，以及本轮 `build/line-comment-desktop-profile`；不覆盖生产 cfg，不注册连接、不加载用户 SQL 或历史。

最初合成入口在建立 Scene 前用 CSS lookup 获取虚拟化 CodeArea，触发 NPE，没有可操作窗口。为该测试入口补充独占 profile 内的异常日志后定位原因，改从实际 Pane 字段取得编辑器，testClasses 重新编译通过（5s）；修正仅影响手动夹具，生产代码/自动测试未变。诊断 JVM 参数仅用于副本，随后移除，没有提交日志。修正后的应用正常启动。

- 暗色宽窗：实际“行注释”按钮可见，初始反选前两行长度 25。点击后仅前两行出现 `-- `，第三行原 `-- keep` 和第四行 `select 3;` 不变；选区长度 31，起点包括首行标记，底栏显示“选区已包含首行注释标记”。
- 在实际正文点击放置光标后按 Ctrl+Z，一次恢复两行原文本。随后 Ctrl+/ 仅为第二行添加注释，光标保持折叠；再次 Ctrl+/ 移除该层，其他行不变。
- 切换亮色，再将窗口宽度设为 640（截图可见宽度约 628）：工具栏分组自动换行，“行注释”文字完整。再次反选前两行并点击实际按钮，注释/选区范围和状态提示正确。
- 同一窄窗切回暗色，按钮、选区位置/范围及完整状态提示仍显示。未在桌面验证 480 宽度、改绑配置页、8 Mi/10,000 行边界或真实混合换行保存；这些由上方对应自动测试覆盖。
- 调整宽度后工具曾报告 `window bounds changed`，重新获取返回窗口对象后恢复；未继续使用旧坐标。验收结束通过关闭按钮正常退出。
- 全程没有点击执行、执行计划、保存/导出或连接入口。未触碰真实数据库及业务文件。

## 边界

按本机约定采用 code-testing-agent 的 Java 指南、行为矩阵和局部修复循环，不额外套通用多代理或临时状态文件流程。Computer Use 只操作独立合成应用；未完成、工具受限、自动与桌面验证分别记录。不宣称字符串/块注释内的 SQL 语义保护、真实用户效率、远端 CI 或安装升级已验证。本轮只本地提交和快进 main，不推送/tag/发布。
