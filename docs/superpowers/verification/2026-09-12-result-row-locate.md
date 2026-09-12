# 当前显示结果行定位验收

基线 `136092f`，独立 `codex/result-row-locate`。范围见[设计](../specs/2026-09-12-result-row-locate.md)。本轮没有真实数据库请求、用户凭证/SQL 历史访问、业务文件修改或 `.testagent/` 操作。

## 行为矩阵

沿用 Java 25、JavaFX 25、JUnit 5 和 FxUiTestSupport；实际 TableView/Dialog 行为测试与 Pane 隔离集成互补，不以源码字符串匹配代替 UI 断言。测试技能用于边界矩阵和断言核对，未套用额外多代理或中间状态文件流程。

| 要求 | 证据 |
| --- | --- |
| 首行/中间/末行、空白与前导零、默认全选、同名重排列身份；输入不导航、确认只选目标一格 | `ResultRowLocateDialogTest.confirmingSelectsExactDisplayRowAndCapturedDuplicateColumn`（3 组） |
| 空/空白、零、负号、正号、小数、指数、全角数字、超行数、10 位大数及 11 位拒绝；有效输入可恢复 | `invalidNumbersDoNotNavigateOrCloseAndCanRecover`（11 组） |
| 原输入 32/33 边界，不能裁掉空白后偷偷接受超长输入 | `rawLengthLimitRejectsRatherThanSilentlyTrimmingExcess`（2 组） |
| 无焦点/序号列/隐藏列焦点退到第一可见数据列，不显示隐藏列 | `invalidColumnFocusFallsBackToFirstVisibleDataColumn`（3 组） |
| 行列表替换/删除/重排、隐藏/列重排、guard 拒绝/禁用/关闭均不使用旧编号 | `changedProjectionOrDeniedContextNeverUsesOldRowNumbers`（8 组），直接派发旧按钮事件仍拒绝 |
| Esc 取消不改变选择，owner onHidden 不覆盖清理，关闭后解绑 | `cancelAndHiddenCallbackReleaseListenersWithoutMovingSelection` |
| 零行/null items/所有数据列隐藏不导航 | `emptyRowsOrNoDataColumnCannotNavigate`（3 组） |
| 明暗 480/720 宽布局、聚焦提示色、实际滚动条和目标 TableRow 可见 | `dialogLayoutAndRealScrollWorkInBothThemes`（4 组），150 行的真实 skin |
| 右键入口可发现，空结果不能打开，重复打开复用当前窗口 | `SqlResultCellIntegrationTest.rowLocationMenuIsPresentButEmptyResultsDoNotOpenIt` 与 `rowLocationUsesSortedFilteredDisplayOrderAndOnlyChangesTheExplicitTargetSelection` |
| 筛选排序后的显示行号准确；原行对象/列顺序/宽度/隐藏/导出与 SQL 反向选区/撤销/文件保留；后续复制读取新目标 | `rowLocationUsesSortedFilteredDisplayOrderAndOnlyChangesTheExplicitTargetSelection` |
| 新结果/过滤后旧弹窗失效，activeResult 不匹配/行映射缺失/列索引失效均拒绝，标签关闭清理 | `resultChangesOrInvalidIdentityRejectAnAlreadyOpenRowLocator`（6 组） |
| 打开不提交防抖文字；随后明确应用筛选使旧定位失效 | `openingRowLocatorDoesNotFlushPendingFilterAndLaterFilteringInvalidatesIt` |
| admission/resources/tasks/running/queue/disabled/plan/finalized 等守卫不允许新开定位 | 扩展既有 `closedBusyOrNonTableStatesRejectOldViewActions`（9 组） |

Pane 夹具沿用 @TempDir 与 DraftConnectionProbe，断言 provider/session/metadata/network 全零；注入剪贴板 writer 验证导航不触发复制，后续明确复制仍取当前目标。无数据写入和新增持久化，不声明覆盖率百分比。

## 构建过程

- 红灯：右键新入口用例先运行，因入口不存在而失败（18s，exit 1）。
- 首轮 61 项中 3 项失败，均为正向用例中的 ScrollToEvent 未发生：该测试的 TableView 没有 Scene/skin，JavaFX 将滚动推迟。补真实 Scene/skin 并用事件 filter 观察请求，没有更改生产滚动逻辑；实际 Stage 滚动条/目标行的 4 组测试原已通过。
- 扩展集成后 69 项全部通过（16s，exit 0）：Dialog 35、Pane 34。
- 首次全量 `clean test jpackageImage` 在测试阶段失败（2m19s，exit 1）：2296 总项、1 失败、3 既有跳过，打包未执行。唯一失败为既有 `SqlTabFileLifecycleTest.ordinaryAndHistoryTabsInstallUnboundFileControllersBeforeDraftBinding` 在 INITIALIZING 状态调用 flush；本轮行定位相关测试没有失败。
- 阅读 SqlDraftCoordinator 确认初始化走后台 writer、回到 FX 后才允许 flush；失败测试没有等待。采用相邻 SqlDraftRecoveryTabsTest 已有的 observe/CountDownLatch 模式，在 flush 前等待 managementPending 结束，再明确断言 ENABLED，finally 解除观察者。不增加固定 sleep，不重试吞错，不修改生产草稿保护或原断言。
- 稳定初始化等待后，70 项定向全部通过（18s，exit 0），增加的过滤类为原有 SqlTabFileLifecycleTest（1 项）。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*ResultRowLocateDialogTest' --tests '*SqlResultCellIntegrationTest' --tests '*SqlTabFileLifecycleTest' --no-daemon --console=plain
```

全量验证使用独占临时目录的 Windows ShortPath 设置 java.io.tmpdir，避免已有 Windows 路径别名测试受临时长路径影响；非 headless。保留既有 unchecked、JAVA_TOOL_OPTIONS 探测及 JEP 493 输出，不称为无警告构建。

## 最终验证

- 最终 `clean test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain` 成功，2m23s、exit 0。XML 汇总 2,296 项：2,293 通过、0 失败/错误、3 既有 live 跳过（Redis 1、Schema Diff 2）；jlink 和 jpackageImage 实际执行。
- `jimage list` 确认开发镜像包含 `ResultRowLocateDialog.class`，不含 DesktopFixture / DraftConnectionProbe；正式 cfg 入口仍为 `com.datacube/com.datacube.DataCubeFx`。
- 桌面使用开发镜像副本 `build/row-locate-fixture-image/DataCube`，只在副本 patch `ResultRowLocateDesktopFixture.class` 并切换测试入口，强制独立 `build/row-locate-desktop-profile`。夹具提供真实 TableView 和生产 Dialog，不使用真实连接或用户配置；验收按一个可见窗口切换，避免工具激活 owner 时键盘未到达弹窗。
- 实际键鼠：默认第 5 行全选输入；输入 145 后 Enter，结果表滚动到末段，选中第 145 行 note 格且仅一格。再次打开默认 145 全选；输入 0 显示范围错误、定位禁用，Enter 不关闭；清空时聚焦提示可见。Esc 返回后第 145 行选区不变。
- 明暗主题及 480 宽窄窗：范围、错误、提示和按钮可读。720 宽布局由前述四组真实 Stage 自动测试覆盖，不混称桌面手动覆盖。
- F8 合成重排：旧窗口即时显示重新定位提示，输入与定位禁用；Enter 不能确认，Esc 可取消。返回表格可见逆序，原记录因重排成为显示第 6 行，没有按旧 145 执行新跳转。最后通过标题栏关闭验收实例。
- Computer Use 技能用于选定隔离窗口、逐步操作并观察刷新；桌面验证的是生产 Dialog 与真实表格交互。SqlEditorPane 右键入口、筛选/排序映射、忙碌/关闭及文件/网络隔离由实际 Pane 自动测试覆盖，不宣称手动执行过完整数据库查询链路。

## 自审与交付边界

检查了数字解析边界、显示列表/列身份失效、确认前守卫、窗口清理、主题作用域与测试镜像隔离；没有新增网络、依赖或持久化。首次全量暴露的既有测试初始化竞态及修复单列在上文，不将失败隐藏为一次通过。本轮本地提交并快进 main，合并后再运行 70 项定向回归；不推送、打 tag、声明远端 CI 或正式发布通过。
