# 紧凑结果行验收

基线 `010a97c`，独立 `codex/result-compact-rows`。[设计与范围](../specs/2026-09-11-result-compact-rows.md)。合成数据、临时文件、无真实连接，未触碰 `.testagent/`。

## 要求与证据

| 要求 | 精确测试 |
|---|---|
| 单行、空字符串、文字 NULL、Unicode/Tab/HTML 保持普通文字；首行、CRLF、空首行明确省略 | `CompactResultTextTest.rendersOnlyAnExplicitSingleLinePreview`（9 组） |
| CR/LF/NEL/Unicode 行段分隔/VT/FF | `treatsEveryVisualLineBreakAsAPreviewBoundary`（7 组） |
| 256 单元前/等于/超过限额和长文本；临界代理对完整、边界换行明确 | `onlyAddsLengthMarkerWhenContentIsActuallyOmitted`（4 组）及上面的 `rendersOnlyAnExplicitSingleLinePreview` |
| 真实虚拟化表格在明/暗下行高缩小后恢复，选择/焦点/原行对象保留 | `SqlResultRowDisplayTest.actualRowsShrinkAndRestoreWithoutChangingSelectionOrData`（2 组） |
| 同一个 TableCell 从多行到 null/空串/文字 NULL/数字/空项，不泄漏旧文本 | `reusedCellsClearOldTextAndOnlyCompactDisplayNotTheirItems` |
| 可用性、外部守卫、表格/祖先禁用、close 后拒绝旧动作；其他显示控制器独立 | `staleActionsKeepActualModeAndOtherEditorsUnchanged`（5 组） |
| 实际 Pane：筛选、倒序、同名列、重排和隐藏组合下切换，复制/导出/查看仍是原值，SQL/文件/撤销不变；新结果沿用、清空仅禁用 | `SqlResultCellIntegrationTest.compactRowsKeepSortedFilteredCellCopyExportAndSqlOnOriginalValues` |
| 显示切换不提交待防抖搜索 | `pendingSearchDoesNotChangeTheCellCapturedAtClickTime`（扩展原用例） |
| 关闭准入、资源/任务关闭、运行、队列关闭/有待处理任务、禁用、计划、终结后拒绝旧入口 | `closedBusyOrNonTableStatesRejectOldViewActions`（9 组，扩展原用例） |
| 880/640/480px 明暗工具栏换行不截字/重叠；布局不派发查询或复制 | `SqlResultToolbarLayoutTest.resultActionsKeepFullLabelsAndWrapWithoutDispatching`（6 组，扩展原用例） |

Pane 测试使用 DraftConnectionProbe 断言 provider/session/metadata/network 均为 0，复制写入注入捕获器，不操作系统剪贴板；导出证据为实际原值快照，不宣称真实业务文件导出验收。排队测试是由 latch 控制的空操作，没有 JDBC。未配置覆盖率工具，不宣称百分比。

## 构建

先写文本规则测试，缺失 CompactResultText 时编译失败（33s，exit 1），再实现。首次集成回归 54 项中 1 失败：新断言没有考虑原复制路径对多行值的 TSV 引号规则；核对 TsvClipboardFormatter 后修正精确期望，保留原复制实现。补齐排队/终结和防抖交叉后，以下命令 21s、exit 0。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests com.datacube.sqleditor.result.CompactResultTextTest --tests com.datacube.fx.SqlResultRowDisplayTest --tests com.datacube.fx.SqlResultCellIntegrationTest --tests com.datacube.fx.SqlResultToolbarLayoutTest --no-daemon --console=plain
```

完整构建使用本轮独占 ASCII ShortPath 临时目录设置 `java.io.tmpdir`、保持非 headless：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

完整构建 2m37s、exit 0，jlink 和 jpackageImage 均执行成功。JUnit XML 共 2,105 项：2,102 通过、0 失败、0 错误、3 项既有外部 live 跳过；本轮新增 31 项均实际执行。定向最终共 56 项、0 跳过。既有 unchecked 提示、工具链探测的 JAVA_TOOL_OPTIONS 输出及 JEP 493 提示未阻止构建，未禁用检查。正式 runtime 中确认有 SqlResultRowDisplay / CompactResultText，没有 ResultCellDesktopFixture 或 DraftConnectionProbe；正式 cfg 保持 DataCubeFx 入口。

## 桌面

复用 ResultCellDesktopFixture：无注册连接的实际 SqlEditorPane，只有五行内存合成值；开发镜像副本和独占 `build/result-cell-desktop-profile`，不使用用户目录和业务数据。手工 fixture 仅在镜像副本 patch-module，不进入生产包。

Windows 真实桌面验收：

- 暗色默认未选中“紧凑行”；先选择第 4 行 value，多行值占多行高度。鼠标开启后变为 `{ ↵ …` 单行，所选格保留，其他行连续排列。
- 工具栏“查看单元格”仍打开第 4 行/原列 3，正文保留 247 UTF-16 单元的多行合成文本；没有把紧凑标记带入查看窗口。关闭按钮正常。
- 切换亮色主题仍保持紧凑；鼠标关闭后恢复原多行高度，焦点在复选框时物理空格键可重新开启。
- 通过窗口边框拖动尝试缩窄两次，截图尺寸未改变，未宣称手工窄窗通过；工具栏 480/640/880px 明暗布局证据来自上表实际 JavaFX 测试。系统窗口菜单打开后已用 Esc 取消。
- 正常关闭合成窗口。没有执行 SQL、操作真实数据库或写系统剪贴板。复制/导出、生命周期和列状态交叉的证明是自动化测试，不混称为手工业务验收。

本轮使用 code-testing-agent 的 Java 指南和行为矩阵完成边界回归，使用 Computer Use 验证真实可见交互；按本机约定不引入通用多代理/状态文件模板。交付仅本地提交、快进 main，不推送/tag/发布，不宣称用户耗时、Linux 或远端 CI 已验收。
