# SQL 行缩进验收

基线 `c204396`，独立 worktree `codex/sql-line-indent`。设计见[行缩进](../specs/2026-09-11-sql-line-indent.md)。本轮无真实数据库、用户 SQL、凭据或业务文件访问；未新增依赖/持久化格式。

## 行为证据

| 要求 | 实际测试 |
|---|---|
| 整行范围、末端下一行排除、Unicode、混合分隔符、反向选区、缩进往返 | `SqlLineIndentTest.selectedLinesExcludeEndpointAtNextLineStartAndPreserveDirection`（2 组） |
| 光标单行、末尾空行、空文件、内部空行 | `collapsedCaretOnlyIndentsItsLogicalLine`（5 组）、`emptyAndBlankLinesCanBeIndentedButWholeSelectionExcludesTrailingEmptyLine` |
| 一个 Tab 或最多 4 空格，不移除其他空白，删除区内偏移收敛 | `outdentRemovesOnlyOneTabOrUpToFourSpacesAndClampsInsideRemovedPrefix` |
| 无变化不建补丁；文本/结果/行数限额，准确边界和非法选区 | `unchangedOutdentRetainsSelectionAndProducesNoEdits`、`lineAndTextLimitsAreAllOrNothingAndExactBoundaryIsAccepted` |
| 真实 CodeArea 多行一次撤销、与前后操作隔离、重做和反向选区 | `SqlIndentActionsTest.multiLineEditIsOneUndoStepSeparateFromTypingAndPreservesBackwardSelection` |
| 默认按键、立即改绑、持久化、提示更新；不捕获其他输入或 Tab | `rebindPersistsRefreshesHintsAndDoesNotHandleOtherFieldsOrTab` |
| 禁止编辑/自身及父级禁用/关闭旧按钮/设置监听释放 | `unavailableAndStaleActionsCannotMutateTextOrUndo`（5 组） |
| 超限整体拒绝、无变化不污染撤销与选区、明确反馈 | `noOpAndOverLimitLeaveUndoAndSelectionUntouchedWithFeedback` |
| 计划生成后再次检查关闭准入 | `admissionIsRecheckedBeforeApplyingPreparedEdits` |
| 真实文件 CRLF/LF/CR、路径身份、dirty/title、撤销重做、被动只读连接 | `SqlEditorIndentIntegrationTest.mixedPhysicalSeparatorsFileIdentityAndPassiveReadOnlyTargetSurviveIndentUndoRedo` |
| Pane 关闭准入/资源/任务/文件忙碌/FX 终结拒绝旧动作 | `paneLifecycleGuardsRejectOldIndentActions`（5 组；资源/文件状态采用白盒状态注入，不宣称并发 I/O 压测） |
| 草稿检查点记录缩进、不保存源 SQL 文件、冻结拒绝新修改 | `indentationUsesDraftCheckpointWithoutSavingFileAndFreezeBlocksFurtherEdits` |
| 明暗主题 880/640/480 宽度下全部 12 个工具栏动作完整且在边界内 | `SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor`（6 组） |

三个新测试类共 27 项。集成测试统一断言 provider/session/metadata/network 均为 0；外部效果通过已有 DraftConnectionProbe 和独占临时目录隔离。测试仅声明表中行为覆盖，没有测量/宣称覆盖率百分比。

## 自动化

首轮纯逻辑/控制器 exit 0，25s。接入文件和现有布局回归后定向 exit 0，12s，JUnit XML 57 项全部通过、无失败/错误/跳过。随后自审补充“生成计划后再次检查准入”和第 27 项新测试。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.sqleditor.SqlLineIndentTest --tests com.datacube.fx.SqlIndentActionsTest --tests com.datacube.fx.SqlEditorIndentIntegrationTest --tests com.datacube.fx.SqlEditorUsabilityTest --no-daemon --console=plain
```

最终全量重建与打包：独占临时目录通过 Windows ShortPath 提供 ASCII `java.io.tmpdir`，并保持 `java.awt.headless=false`，执行：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

最终 exit 0，2m 12s。197 个测试类、2,036 项：2,033 通过、0 失败/错误、3 项既有 live 跳过（Redis 1、Schema Diff 2）。27 项新测试全部实际执行、无 headless 跳过；布局回归通过。`jlink` / `jpackageImage` 实际执行；原有 SqlEditorResultFilterContractTest unchecked 编译提示、JAVA_TOOL_OPTIONS 工具探测输出和 JEP 493 提示未更改，最终构建成功。

`jimage list` 检查正式镜像包含 SqlIndentActions / SqlLineIndent，无 DesktopFixture / DraftConnectionProbe；正式 DataCube.cfg 仍使用 DataCubeFx 入口。

## 桌面与审阅

使用已有 `TableSelectSqlDesktopFixture` 的副本镜像及本轮独占 `build/select-desktop-profile`，不改正式入口或 fixture 源码。2026-09-11 实际 Windows 窗口 `DataCube - SELECT 合成验收` 验证：

- 通过合成 PostgreSQL 表右键“生成 SELECT 到新 SQL（不执行）”打开两行基线，保持待绑定/尚未连接；未点击执行或读取数据库。
- 点击 SQL 工作面后 Ctrl+A 选中两行，物理 Ctrl+] 均增加 4 空格；选区仍覆盖原内容，行 2 列数由 30 变为 34，文件标题出现星号，底部明确显示“已缩进 2 行，可撤销；未自动保存文件或执行 SQL”。
- 物理 Ctrl+Z 一次恢复两行基线和干净标题。撤销使用编辑器原有光标定位规则，不宣称撤销会恢复原选区。
- 无选区时点击“缩进”只改变第 2 行，物理 Ctrl+[ 恢复该行及干净标题。
- 切换亮色主题，把左栏分隔条移到约 x=630，使 SQL 区约 560px 宽；按钮组正常换行，缩进/反缩进全文字及反馈可见，鼠标分别点击两按钮完成往返，原 SQL 未执行。
- 合成草稿由既有机制出现“待保存/已保存”；不把它误称为源 SQL 文件自动保存。最后在干净状态正常关闭窗口，重新列出窗口确认该进程窗口已消失。

审阅覆盖范围选择/偏移、只改前缀、单次撤销、设置监听释放、重新检查关闭准入、异步生命周期守卫、原文件和草稿语义、实际打包入口及两主题布局。没有遗留本轮阻塞问题；大文本上限是资源保护值，不是已测得的 10,000 行交互性能承诺。没有用户使用耗时数据或 Linux/远端 CI 本轮结果。

技能使用：`code-testing-agent` 及 Java 扩展用于边界/状态交叉测试与逐项证据；`computer-use` 用于独占合成窗口的可见交互检查。依照本机约定不引入通用多代理编排、`.testagent/` 或覆盖率工具。

交付边界：仅本地提交与快进 main，不推送、tag 或发布；不把合成验收称为真实用户效率验证。
