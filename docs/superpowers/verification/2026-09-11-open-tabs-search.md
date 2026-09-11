# 查找已打开标签验收

基线 main `25ba15f`，独立 worktree / 分支 `codex/open-tabs-search`；[设计与范围](../specs/2026-09-11-open-tabs-search.md)。
按 code-testing-agent 技能针对本次导航功能补齐行为测试，沿用 JUnit 5、真实 JavaFX 控件、FxUiTestSupport、独占临时目录及 DraftConnectionProbe。不添加依赖、覆盖率工具或 CI 配置，不读取/更改 `.testagent/`。

## 行为与精确测试

| 要求 | 测试 |
| --- | --- |
| 标题字面匹配、大小写/空白/Unicode、无匹配，不搜索正文；过滤不导航 | `OpenTabsPaneTest.literalTitleFilteringNeverNavigatesOrSearchesContents`（5 组） |
| 同名对象按身份切换，方向键仅选候选；保留标签顺序及正文/反向选区 | `explicitSwitchUsesIdentityForDuplicateTitlesAndKeepsTextSelectionAndOrder` |
| 新增、移除、重命名实时更新；旧目标消失不能改选同名或相邻项 | `liveChangesPreserveIdentityButNeverRetargetRemovedOrRenamedCandidate` |
| 禁用标签/标签容器/应用导航守卫拒绝，解除后恢复；关闭清缓存、旧动作拒绝 | `disabledTargetOwnerAndClosedPickerRejectOldActionsAndRecoverAfterCancellation` |
| 空列表、空白查询、256/257 边界及超长反馈，不截断目标 | `emptyAndOverlongQueriesGiveFeedbackWithoutTruncatingToAnotherTarget` |
| 真实受管标签关闭期间不可切换；取消关闭后恢复，不多次调用守卫/提前清理 | `realManagedCloseGuardCannotBeBypassedByPicker` |
| 对话框 Enter 确认只选确切对象，完成后关闭/释放 | `OpenTabsDialogTest.enterSwitchesExactCandidateAndClosesDialog` |
| 确认时二次检查应用守卫，失败保持对话框，取消不切页 | `deniedSwitchStaysOpenAndCancelDoesNotNavigate` |
| 真实已显示控件收到 Enter/Esc 事件时，输入框和列表都确认/取消，不预览切页 | `keyboardEventsOnShownControlsConfirmOrCancelWithoutPreviewNavigation`（4 组） |
| 明暗主题下实际 Dialog 的两行说明不被列表挤压为省略文本，空查询提示可见 | `actualDialogKeepsBothInstructionLinesAndVisiblePrompt`（2 组） |
| 真实 SQL 编辑器保留未保存内容、物理文件、反向选区、撤销和被动连接；provider/session/metadata/network 均为 0，不写 SQL 历史 | `OpenTabsSqlEditorIntegrationTest.selectingExistingSqlTabKeepsDirtyFileUndoAndPassiveConnectionWithoutDatabaseWork` |
| 既有标签关闭契约与异步终结间隙 | `ContentTabPaneContractTest`、`ContentTabPaneCloseAttemptTest` |

## 自动化

首轮定向 exit 0，9s，21 项全部通过。桌面发现说明被挤压后，补充真实 Dialog 布局断言，明暗两组均在“完整说明被替换为省略文本”处失败；设置说明/状态的最小首选高度后通过，并为新输入复用主题提示色。另补真实已显示控件的键盘事件断言，列表焦点下 Enter/Esc 两组失败（ListView 自身处理吞掉事件），增加对话框内容区事件过滤后通过。没有调整断言来掩盖这些缺陷。

最终定向 JUnit XML 为 27 项全部通过、0 跳过/失败/错误；其中新增 19 项。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests 'com.datacube.fx.OpenTabs*Test' --tests com.datacube.fx.ContentTabPaneContractTest --tests com.datacube.fx.ContentTabPaneCloseAttemptTest --no-daemon --console=plain
```

全量与开发镜像：在本轮独占 ASCII 短路径临时目录下设置 `java.io.tmpdir`，保持 `java.awt.headless=false`，执行：

```powershell
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

首轮全量 exit 0，2m，2,003 项：2,000 通过，3 项既有 live 跳过，0 失败/错误。

修复后最终完整重建 exit 0，2m 1s，194 个测试类、2,009 项：2,006 通过、0 失败/错误，3 项既有 live 跳过（Redis 1、Schema Diff 2）。本轮 19 项新增测试全部实际执行，无 headless 跳过。`jlink` / `jpackageImage` 实际执行；保留原有 unchecked 测试编译提示、JAVA_TOOL_OPTIONS 的工具探测输出及 JEP 493 提示。

`jimage list` 确认正常镜像包含 OpenTabsDialog / OpenTabsPane，没有 DesktopFixture / DraftConnectionProbe；正式 cfg 保持 DataCubeFx 入口。桌面使用 `build/desktop-fixture-image` 副本与 `build/select-desktop-profile` 独占 profile，patch-module 复用既有 TableSelectSqlDesktopFixture，不修改 fixture 源码或正式镜像配置。

## 实际桌面与明确限制

- Computer Use 只操作本 worktree 的合成验收镜像，使用未绑定连接的空 SQL 标签。合成树为已加载的 example.invalid 节点，未点击执行、执行计划、事务、刷新或真实数据库操作；未打开业务文件或真实 profile。
- 无标签时顶部“查找标签”禁用；正常新建两个 SQL 后入口可用。1200×800 主窗内顶部其他入口仍完整可见。
- 两个同名 SQL 在选择器中分别显示为 `1 · SQL` / `2 · SQL`，初始高亮当前第二个标签；单击第一个候选时背景仍在第二个标签。明确点击“切换”后才激活第一个，标签数仍为两个。
- 最终镜像暗色下重新高亮第一个并点击“取消”，保留第二个；切至亮色后重新打开选择器，默认仍为第二个，明确确认再激活第一个。取消不保存候选选择，重新打开不会复用已销毁控件。
- 首次桌面发现两行说明被压成省略文本、空输入提示在暗色下不明显，已通过失败回归驱动修正。最终镜像明暗主题下提示及两行说明均完整显示，不遮挡候选和按钮。
- 键盘实测限制：当前 Computer Use 对所属模态窗的 `type_text` 未产生可见文字；显式聚焦后重试仍无输入，`set_value` 返回 `read UIA value read-only state: 所需属性不在 CacheRequest 中 (0x80070057)`。同时所属窗口元素点击曾返回 cached app state 中不可用，改用刷新截图坐标完成鼠标路径。没有通过其他原生自动化接口绕过，也没有把这些输入尝试计为通过。
- 字面筛选、方向键及 Enter/Esc 由真实 JavaFX 组件/已显示 Dialog 的自动测试覆盖；本轮没有完成该模态窗的 Windows 物理键盘端到端验收，也不是数据库执行、安装升级或远端 CI 验收。
- 额外打开过独占 profile 的空“SQL 草稿”窗口并正常关闭，无真实记录、无清空/删除/恢复操作。未保存截图文件，桌面证据来自本轮工具返回截图。
- 最后通过主窗口正常退出流程关闭所有空 SQL 标签与隔离实例，窗口列表确认退出；未强杀进程。

## 审查范围

- 生产变更仅两个导航组件、AppShell 顶部入口及新输入框的提示色选择器；未修改 ContentTabPane 关闭所有权或数据库、执行、事务代码。
- 仅读取现有 Tab 的标题、顺序、禁用状态并显式调用选择模型，不接收页面工厂、连接或文件服务。正常标签选择仍沿用已有工作区记录/页面生命周期，不承诺取消这些既有行为。
- 对话框关闭解除标签列表及逐标签提取监听，不新增线程、计时器或持久化字段；旧候选不能按同名替换。
- 本轮只本地提交、快进 main，不推送/tag/发布；不访问真实连接、SQL 历史、凭据或业务文件。
- 差异自审未发现本轮范围内的阻断问题，`git diff --check` 通过。最终桌面镜像与上述完整构建的生产源码/资源一致；后续只补充验收文档。
