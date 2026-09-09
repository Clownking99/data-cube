# 连接树显式查找验证

基线 main `7e1ab3b`；独立 `codex/connection-tree-find`。设计见[连接树显式查找](../specs/2026-09-09-connection-tree-find.md)。
本轮仅定位当前已展开树中的名称，不是全库对象索引或远端元数据检索。

## 要求与测试证据

按 code-testing-agent 技能组织本增量回归；沿用 JUnit 5、FX 调度辅助类和拒绝网络的合成 provider。

| 要求 | 精确测试 |
| --- | --- |
| 展开链深度优先、可见行号、状态行排除；不读取折叠子树 | `ConnectionTreeFindBarTest.scanUsesExpandedDepthFirstRowsAndSkipsStatusWithoutOpeningBranches` |
| 显示/隐藏根节点、null、字面匹配、Unicode、土耳其默认 Locale 下大小写 | `shownHiddenNullRootsAndLiteralUnicodeUseLocaleIndependentMatching` |
| 空白/无匹配 | `emptyOrAbsentQueryHasNoMatch` |
| 9,999 / 10,000 / 10,001 节点上限、状态行计入扫描预算、256 / 257 输入边界 | `scanLimitReportsTruncationOnlyWhenNodesRemain` / `queryLengthBoundaryAndStatusNodesCountTowardWorkLimit` |
| 输入不选中；按钮、Enter、Shift+Enter、F3、Shift+F3 相对当前行循环；重名节点按身份区分 | `typingCountsWithoutSelectionAndNavigationCyclesRelativeToCurrentRow` |
| Ctrl+F 选中查找词；清除和 Esc 保留选择及展开，Esc 返回树 | `keyboardClearAndEscapeKeepSelectionAndExpansionAndReturnFocus` |
| 折叠、改名、加载结果替换、清空和换根后立即 Enter 不使用旧节点；旧根监听移除 | `mutationCollapseRenameAndReplacementCannotNavigateStaleMatches` |
| 超长拒绝、截断结果明确标注且不导航超预算节点 | `longQueryAndTruncationHaveHonestDisabledOrPartialFeedback` |
| 实际防抖计时完成，不借助睡眠猜测、也不改变选择 | `actualDebounceUpdatesCountWithoutSelection` |
| 非 FX 线程关闭、重复关闭、排队动作无效、关闭后树监听不更新 UI | `offThreadCloseDisablesQueuedNavigationAndDetachesExternalListeners` |
| 明暗 240 / 280 / 400px，输入和操作不出界，树保留可用高度；聚焦时提示文字使用实际主题可读色 | `narrowLayoutsKeepControlsInsideAndLeaveSpaceForTree` |
| 真正 ConnectionTreePane + 合成 PostgreSQL / Oracle / Redis：输入不切换连接、显式定位才切换；刷新无旧选择、保留全部连接快照与旧键入检索；provider / 会话 / 元数据 / 网络计数均为 0、对象动作禁止、配置字节不变 | `ConnectionTreePaneFindTest.savedConnectionFindRefreshAndLegacyTypingNeverConnectExecuteOrWriteConfiguration` |
| 原“选择已有连接”只聚焦，不选择/展开/连接；生命周期和 Schema Diff 入口保留 | `WorkspaceStartPaneTest` / `ConnectionTreePaneLifecycleTest` / `ConnectionTreeSchemaDiffContractTest` |

定向 36 项全部通过，11s；桌面反馈的提示文字修正后再次 36 项全部通过，10s，均 exit 0：

```powershell
.\gradlew.bat test --tests com.datacube.fx.ConnectionTreeFindBarTest --tests com.datacube.fx.ConnectionTreePaneFindTest --tests com.datacube.fx.WorkspaceStartPaneTest --tests com.datacube.fx.ConnectionTreePaneLifecycleTest --tests com.datacube.fx.ConnectionTreeSchemaDiffContractTest --no-daemon --console=plain
```

## 开发与审查记录

- 首轮 36 项中 7 项失败：空输入 Esc 的焦点判断依赖窗口激活，改为使用 Scene 的实际 focusOwner；6 组主题测试引用了不存在的样式路径，改为真实 `theme-base.css` + 明暗主题文件。修正后全部通过。
- 首次桌面验收发现 Modena 默认提示文字在暗色背景不易辨认；仅对 `#connection-tree-find-query` 使用主题的 `-brand-fg-dim`，不全局修改其他输入框；明暗窄栏测试增加聚焦提示文字颜色断言。
- 输入与树变更只使结果失效并触发 180ms 防抖；显式定位同步扫描最新展开链，不缓存跨刷新节点。防抖等待期间禁用按钮并保留“正在查找”反馈。
- 使用迭代遍历，10,000 节点预算包括未匹配项和状态行；不调用 loader，不改 children 或 expanded。匹配列表为不可变快照；每次定位按节点身份和当前可见行处理。
- 关闭先原子封住回调，再在 FX 线程停止计时并移除对外监听。新组件无 provider / SQL / 文件写入依赖。
- 本轮只扩展左栏，本来依赖“第一个子节点是树”的焦点测试改为稳定 ID 定位；连接配置快照、右键菜单和懒加载逻辑不变。
- 原有未跟踪 `.testagent/` 未读取、修改或暂存。不把本地回归当作真实用户任务耗时改善或远端 CI 证据。

## 全量与开发镜像

首次执行 `clean test jpackageImage -PappVersion=0.0.0`，2m12s，exit 0。184 个测试类 XML 汇总：
1,894 项，1,891 通过、3 原有 live 测试跳过（Redis 1、Schema Diff 2），0 failures/errors。
提示文字修正后再次执行 `test jpackageImage -PappVersion=0.0.0`，1m52s，exit 0；全量仍为
1,894 项、1,891 通过、3 原有跳过、0 failures/errors，`jlink` / `jpackageImage` 实际重新执行。

构建使用本轮独占临时目录的 Windows 短路径，通过 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=...` 传入。
保留既有 unchecked 测试提示；打包工具将 `Picked up JAVA_TOOL_OPTIONS` stderr 标为 javac/java failed 的噪声不代表最终任务失败。
`0.0.0` 只用于隔离开发镜像，现有 `AppVersion.isDev()` 使其跳过自动更新检查，不是新发布版本。

## Windows 实际窗口

Computer Use 控制 `build/jpackage/DataCube/DataCube.exe`，只在忽略目录中的 cfg 添加
`-Duser.home=.../build/desktop-profile`。合成配置含 Demo PostgreSQL East / Demo Oracle East / Demo Redis West，
地址均为 example.invalid、无密码，不使用真实用户配置。

首次镜像已实际操作：

1. 输入 `east` 后显示“共 2 处”，全部连接仍折叠且未选中。
2. Enter 选中 PostgreSQL（1 / 2）；Shift+Enter 反向循环到 Oracle（2 / 2、已回到末尾）；F3 正向循环回 PostgreSQL（已回到开头）。
3. 点击“下一处”选中 Oracle；Esc 清空查找、收起前后按钮、焦点返回树，Oracle 选择与全部折叠状态保留。
4. 从树内 Ctrl+F 将焦点返回查找框；全过程仍显示欢迎页，没有数据标签、加载行或连接错误弹窗。
5. Alt+F4 正常退出，刷新窗口列表确认本轮镜像窗口为空。

提示文字修正后的最终镜像复验：

1. 暗色默认约 280px 左栏中的提示文字已可读；拖动分隔条到约 240px，聚焦后仍可读，输入、清除按钮和树没有重叠。
2. 窄栏输入 `east` 后前后按钮正常出现；Enter 选中 PostgreSQL、显示 1 / 2，全部连接仍折叠。
3. 切换亮色后输入、按钮、匹配状态和选择保持；点击“清除”后输入为空、前后按钮收起，PostgreSQL 选择保留，亮色聚焦提示文字可读。
4. Alt+F4 正常退出。两次镜像都只使用隔离合成连接，未打开真实历史、访问数据库或执行 SQL。

真实数据库对象的展开/异步加载、上限和关闭回调由自动测试覆盖，不宣称进行过真实数据库桌面验收。

合成连接配置两次桌面验收前后 SHA-256 一致：
`E84C5C9E3603EC97E108D6D268A1CACCF6596ED69A1168470583D931FEA9EBF0`。
没有验证真实数据库执行、安装器升级、远端 CI 或用户耗时改善。

## 本地交付边界

交付前核对根目录仍为 main `7e1ab3b`，仅有原有未跟踪 `.testagent/`。
本轮源码、样式、测试和文档使用显式路径提交并快进合并本地 main，保留 worktree；不推送、不打 tag、不发布。
