# 查找结果列验收

基线 `aa5d08d`，独立 `codex/result-column-find`。[设计与范围](../specs/2026-09-11-result-column-find.md)。只用合成数据和独占临时目录，不访问真实数据库、凭据、SQL 历史；不触碰 `.testagent/`。

## 行为与证据

| 要求 | 精确测试 |
|---|---|
| 大小写不敏感、隐藏/同名列按对象身份列出，只有明确确认才显示 | `ResultColumnFindDialogTest.filteringHiddenDuplicateColumnsOnlyNavigatesOnExplicitConfirmation` |
| 实际窄表明/暗主题恢复远端隐藏列并真正横向滚动，行对象、选择/焦点、顺序、列宽不变 | `confirmingHiddenFarColumnActuallyScrollsWithoutChangingRowsOrSelection`（2 组） |
| 输入框及列表 Enter/Esc 分别确认/取消，不预览导航 | `keyboardConfirmsOrCancelsWithoutPreviewNavigation`（4 组） |
| 换同名新列、移除、重排、准入拒绝、禁用、关闭拒绝旧候选且不发滚动事件 | `staleOrDeniedCandidatesNeverNavigateToASameNamedReplacement`（6 组） |
| 可见状态改变后先更新按钮，再次明确确认才显示 | `visibilityChangeRequiresASecondExplicitConfirmation` |
| 查询词 255/256/257 边界、无匹配禁用和恢复 | `queryLengthBoundaryRejectsOverlongInputAndCanRecover`（3 组） |
| 空列、199/200/201 列边界，200 项上限明确且可缩小到第 201 列 | `candidateLimitIsExplicitAndCanFindAColumnBeyondTheInitialList`（4 组） |
| 查询框上下键选同名列，关闭清除候选并释放集合监听 | `queryArrowsChooseDuplicateIdentityAndClosedDialogReleasesColumnListener` |
| 480/720px 明暗窗口、超长/换行/代理对列名有界，中文/下划线/HTML 按字面搜索完整名称，说明和按钮不出界，切换隐藏候选后实际渲染按钮文案完整 | `boundedMetadataAndInstructionsStayReadable`（4 组） |
| 实际 Pane 在筛选/倒序/隐藏/重排/同名下查找不搜索数据，不改变行选择、焦点、SQL 选区、文件、撤销；确认显示才扩大可见导出投影 | `SqlResultCellIntegrationTest.resultColumnFindPreservesFilteredSortedRowsAndOnlyExplicitlyRevealsTheChosenColumn` |
| 新结果拒绝旧入口和旧候选，单窗口保护，终结时关闭窗口 | `newResultsRejectOldFindMenuAndOpenCandidatesAndFinalizationClosesFinder` 及上项 |
| 查找不提交待防抖的行搜索 | `pendingSearchDoesNotChangeTheCellCapturedAtClickTime`（扩展原用例） |
| 关闭准入、资源/任务关闭、运行、队列关闭/待处理、禁用、计划、终结拒绝旧入口 | `closedBusyOrNonTableStatesRejectOldViewActions`（9 组，扩展原用例） |
| 查找位于菜单首项，原显示/隐藏动作继续有效；工具栏 480/640/880px 明暗不截字/重叠 | `SqlResultToolbarLayoutTest.actionsAndColumnMenuStillWorkAfterNarrowingAndWidening` 和 `resultActionsKeepFullLabelsAndWrapWithoutDispatching`（6 组） |

Pane 的 DraftConnectionProbe 断言 provider/session/metadata/network 均为 0。文件使用 @TempDir；导出证据是实际可见列快照，不宣称业务文件导出。未收集覆盖率百分比。

## 构建与修正

先写查找行为测试，类不存在时编译失败（14s、exit 1）。初始实现定向回归通过（26s）。补齐矩阵后 94 项中 2 项失败：新显示的远列没有实际滚动。核对 [OpenJFX 25 TableViewSkinBase](https://raw.githubusercontent.com/openjdk/jfx/jfx25/modules/javafx.controls/src/main/java/javafx/scene/control/skin/TableViewSkinBase.java) 的横向滚动实现后，在显示后先应用 CSS/布局以实现表头，再调用 scrollToColumn；原实际滚动断言保留，26 项窗口测试通过（10s）。

追加集成验证时，96 项中终结场景的测试自身取不到已清空的菜单项；改为在状态变化前捕获旧入口，再派发旧动作，96 项全通过（13s）。自审将“查找列…”置于菜单首项；原布局测试按稳定 ID 操作列项，并新增首项断言，未削弱显示/隐藏和事件计数断言。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.fx.ResultColumnFindDialogTest --tests com.datacube.fx.SqlResultCellIntegrationTest --tests com.datacube.fx.SqlEditorResultFilterContractTest --tests com.datacube.fx.SqlResultToolbarLayoutTest --no-daemon --console=plain
```

首轮全量 clean test + jpackageImage 完成（2m11s、exit 0），JUnit XML 共 2,133 项：2,130 通过、0 失败/错误、3 既有外部 live 跳过。桌面检查随后发现动态按钮文本被省略。只比较控件宽度与 prefWidth 的新增检查仍未捕获，进一步断言皮肤内实际 Text 内容后，原实现 480/720px 明暗四项全失败（13s），复现“显示并…”而非完整动作。修正动态按钮不参与初始统一宽度、最小宽度随首选尺寸，保留实际渲染断言；最终定向 96 项通过（27s）。

完整构建使用本轮独占 ASCII ShortPath 临时目录设置 `java.io.tmpdir`，保持非 headless：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

修正后的完整构建 2m17s、exit 0，jlink 和 jpackageImage 成功；JUnit XML 共 2,133 项，2,130 通过、0 失败/错误、3 既有外部 live 跳过（Redis 1、SchemaDiff 2）。新增 28 项均实际执行；定向四类分别 26/23/40/7 项。既有 unchecked、JAVA_TOOL_OPTIONS 工具链探测输出及 JEP 493 提示未阻止构建，没有禁用检查。正式 runtime 确认含 ResultColumnFindDialog，不含 ResultColumnDesktopFixture/DraftConnectionProbe；正式 cfg 保持 DataCubeFx 入口。

## 桌面

使用测试源码中的 ResultColumnDesktopFixture，实际 SqlEditorPane、12 列 3 行内存合成值，无注册连接；末两列同名、原列 11 初始隐藏、列宽 180、初选第 2 行原列 1。只在开发镜像副本 patch-module，使用独占 `build/result-column-desktop-profile`，不进入正式包或用户配置目录。

- 暗色“列（11/12）”菜单首项可直接打开查找，末尾两同名候选分别标注原列 11/12，隐藏项带“隐藏”；选择候选不改变表格。首轮鼠标取消后列数和横向位置保持，原选中格保留。
- 首轮发现动态按钮截字，修正后重新 clean 构建/重建隔离镜像。复验完整“显示并定位”按钮；鼠标确认原列 11，窗口关闭，列数更新为 12/12，横向滚动到 customer_note，行值确为 `original-column-11`，不是同名的原列 12。
- 切换亮色、重新打开查找，提示和按钮可读；鼠标确认可见首列，横向返回 field_1，原第 2 行原列 1 选中格仍保留。正常关闭合成窗口。
- 桌面输入通道受限：向主窗口发送 type_text 会使弹窗失焦而没有输入；UIA set_value 返回 CacheRequest `0x80070057`，按索引点击该弹窗输入框也出现缓存项不可用。停止重试该路径，以截图坐标完成上述鼠标验收。**手工按名称输入、Enter/Esc 和方向键未验收**；其正确性证据仅来自实际 JavaFX 控件自动化，不混称为物理键盘通过。
- 480/720px 明暗窗口和动态文字完整性来自实际 JavaFX 布局/渲染断言；未进行桌面窗口尺寸拖动验证。没有执行 SQL、操作真实数据库、写系统剪贴板或业务导出文件。

## 验证边界

本轮使用 code-testing-agent 的 Java 指南和行为矩阵补齐边界/集成回归，Computer Use 用于隔离合成窗口的可见交互；不引入通用多代理/状态文件模板。交付仅本地提交并快进 main，不推送/tag/发布，不宣称真实用户效率、Linux 或远端 CI 已验收。
