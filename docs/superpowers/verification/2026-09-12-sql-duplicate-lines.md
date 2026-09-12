# SQL 重复行验收

基线 `6eac2d4`（v3.2.5），分支 `codex/sql-duplicate-lines`。[设计与实施顺序](../specs/2026-09-12-sql-duplicate-lines.md)。本轮开始时核实 [v3.2.5 发布流水线](https://github.com/Clownking99/data-cube/actions/runs/34666161106) completed/success；该结果属于上一版本，不等同于本增量远端验证。

## 行为与证据

沿用 Java 25 / JavaFX 25 / RichTextFX 0.11.6 / JUnit 5 的项目约定。测试技能用于行为矩阵、精确断言和 Java 测试运行；按本机协作约定内联实施，不另建中间状态文件或多代理流水线。不声明覆盖率百分比。

| 要求 | 自动化证据 |
| --- | --- |
| 当前首/中/末行、部分/反向多行、末端行首排除、整篇含/不含终止换行、空文/中间空行/末尾空行、Unicode/Tab、SQL 字符串与危险文本不解释 | `SqlDuplicateLinesTest.oneInsertionCopiesWholeLinesAndMovesExactSelection`（16 组），精确输出、anchor/caret/行数及原选区内容 |
| 9,999 / 10,000 / 10,001 行界限，不误包含末尾空行 | `lineLimitHasExactBoundaryAndTrailingEmptyLineIsNotAccidentallyCopied`（3 组） |
| 结果 8 Mi 前/等/后界限，在构造副本前拒绝超大输出 | `outputLengthLimitIsCheckedBeforeBuildingOversizedCopy`（3 组） |
| null、负/越界 anchor 和 caret、非规范化 CR/CRLF、超长输入拒绝 | `invalidInputsNeverProducePartialPlans` |
| 一次撤销，与前后键入分离；重做、反向选区、补全收起与反馈 | `SqlDuplicateLinesActionTest.duplicationIsOneUndoStepBetweenTypingAndPreservesReverseSelection` |
| 新快捷键即时改绑、持久化读回、旧键不再重复、其他输入框和 Tab 不受影响 | `shortcutRebindPersistsAndOnlyHandlesEditorWithExactModifiers` |
| guard/不可编辑/禁用/父禁用/关闭拒绝旧按钮和快捷键；关闭解除设置监听 | `unavailableOrClosedActionCannotChangeTextSelectionOrUndo`（5 组） |
| 超行数不编辑/改选区/记撤销，明确限额反馈 | `limitFailureKeepsOriginalTextSelectionUndoAndReportsBound` |
| 计划后 admission 关闭、beforeEdit 改文本/选区/关闭时不套用旧计划 | `preparedPlanIsRejectedIfAdmissionOrEditorChanges`（4 组） |
| 实际 Pane 工具栏可见、当前行定位、文件 dirty 与撤销恢复，零数据库请求 | `SqlEditorDuplicateIntegrationTest.toolbarDuplicatesCurrentLineWithoutWritingFileOrOpeningConnection` |
| 混合物理换行原文保留，新增换行使用文件分隔符，反向选区/软换行/执行选区、文件身份、只读生产连接离线意图和撤销重做 | `mixedSeparatorsReverseSelectionFileIdentityAndPassiveReadOnlyTargetSurviveUndoRedo` |
| Schema 输入框不触发，Pane 改绑生效，原行注释入口仍正常 | `newShortcutIsEditorScopedAndRebindingKeepsExistingCommentAction` |
| admission/resources/tasks/file-busy/finalized/readonly/disabled 守卫拒绝旧按钮和键盘 | `paneGuardsRejectOldButtonAndKeyboardWithoutEditing`（7 组） |
| 进入真实草稿检查点，冻结拒绝继续重复，源文件仍不变 | `duplicateEntersDraftCheckpointButFreezeStopsFurtherCopiesAndSourceRemainsUntouched` |
| 明暗 600/880 宽实际按钮文字、布局与行为 | `actualButtonAndTextFitBothThemesAndWrappedToolbar`（4 组） |

Pane 测试使用 @TempDir 文件/配置与 DraftConnectionProbe，断言 provider/session/metadata/network 全零；新增动作本身不依赖剪贴板、文件或网络。隔离集成测试不代表真实用户效率实验。

## 运行记录

- 红灯：实际 Pane 工具栏没有入口，1 项失败（39s、exit 1），未修改既有断言。
- 第一轮纯计划/动作/入口测试通过（14s、exit 0）。
- 补齐 Pane 集成后 50 项定向全部通过（13s、exit 0）：纯计划 23、动作 12、Pane 15。
- 首次全量 2,346 项中 6 项失败、3 既有跳过（1m53s、exit 1），打包未执行。失败全部来自 `SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor` 的既有动作数 13 断言；新增入口后为 14。更新数量并明确断言新按钮存在，保留所有标签可读、边界布局与未绑定执行禁用断言。
- 补正集成契约后，以上 50 项加原有明暗 480/640/880 宽的 6 项布局回归全部通过（12s、exit 0）。定向命令追加 `--tests '*SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor'`。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*SqlDuplicateLinesTest' --tests '*SqlDuplicateLinesActionTest' --tests '*SqlEditorDuplicateIntegrationTest' --no-daemon --console=plain
```

最终全量与镜像使用独占临时目录的 Windows ShortPath 设置 java.io.tmpdir、非 headless，沿用既有路径别名测试要求；不修改 Gradle 配置或依赖。

## 最终全量与桌面

- 最终 `clean test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain` 成功，2m09s、exit 0。XML 汇总 2,346 项：2,343 通过、0 失败/错误、3 既有 live 跳过（Redis 1、Schema Diff 2）；jlink / jpackageImage 实际执行。保留既有 unchecked、JAVA_TOOL_OPTIONS 工具探测及 JEP 493 输出，不称为无警告构建。
- `jimage list` 确认生产开发镜像包含 SqlDuplicateLines / Plan / Action，没有 DesktopFixture 或 DraftConnectionProbe；正式 cfg 入口仍为 `com.datacube/com.datacube.DataCubeFx`。
- 桌面采用开发镜像副本 `build/duplicate-fixture-image/DataCube`，只在副本 patch 单个 `SqlDuplicateDesktopFixture.class` 并切换入口，强制独立 `build/duplicate-desktop-profile`。夹具包含真实 SqlEditorPane，不注册连接、不绑定业务文件；不启动自动更新、历史或用户工作区。
- Computer Use 实际观察：初始前两行反向选择 anchor 27 / caret 2；点击工具栏“重复行”后原两行保留，副本在下方，反向选择变为 54 / 29。底栏仍显示选中 25，未绑定的执行按钮保持禁用；Ctrl+Z 一次恢复原 SQL（撤销不承诺恢复原选区）。
- 默认 Ctrl+Shift+D 对当前 `-- keep` 行成功重复，光标移到副本；暗色宽窗和明暗 640 窄窗按钮/反馈可读。窗口宽度改变不导致文本编辑。
- 亮色窄窗下 Ctrl+End 到无终止换行的末行，Ctrl+Shift+D 生成独立下一行，caret 从 52 到 62，均为行内第 10 列；Ctrl+Z 一次还原。最后通过标题栏正常关闭隔离实例。
- 桌面覆盖生产 Pane 的实际按钮、默认键、选择、撤销、明暗窄窗及末行分隔；文件混合换行、草稿、改绑和失效守卫由上面的隔离自动测试覆盖，不混称手动完成真实数据库工作流。

## 自审与本地交付

复查纯计划边界及选区映射、单次插入与撤销隔离、前后守卫、设置监听/构造失败/最终关闭清理、文件分隔符契约和镜像测试入口隔离。已有动作数量断言随新入口更新，原布局检查没有删减。README 与路线图同步；本轮本地提交并快进 main，合并后再跑 56 项定向回归。不推送、打 tag 或将此增量称为已发布；`.testagent/` 未读取或修改。
