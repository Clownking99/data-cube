# 批量结果双向异常定位验收

基线 main `f124008`，分支 `codex/sql-batch-previous-failure`；[设计](../specs/2026-09-20-sql-batch-previous-failure.md)。仅一个结果选择控制器变更，复用现有离线 JavaFX 夹具和临时文件，不访问真实连接、SQL、历史或剪贴板，不操作 `.testagent/`。

## 行为与证据

依照 `code-testing-agent` 的聚焦用例方法，扩展既有两个测试类，净增 39 项；不生成额外测试状态目录。

| 需求 | 测试证据 |
| --- | --- |
| 双向按执行顺序跳过正常项，首尾循环，从概览进入首/末异常 | `SqlBatchFailureNavigationTest.failureNavigationUsesExecutionOrderAndWrapsWithoutExecuting`：14 个方向/起点组合，检查实际失败/超时/取消内容、批次总汇、无自动弹窗、详情切换关闭、编辑器/选区/撤销/文件和零网络探针 |
| 当前唯一异常不能重复重置详情 | `uniqueFailureIsReachableOnceAndDoesNotResetCurrentDetails`：两个方向同时禁用，过期动作不重置详情/结果，回概览后重新可达 |
| 新结果释放旧导航，动作实时计算新批次目标 | `replacingBatchDisablesOldNavigationAndPreservesNewResult`（双向 × 7 种替换），`actionRecomputesTargetFromNewBatchInsteadOfCapturedIndex` |
| 忙碌/关闭/禁用守卫不能被旧动作绕过 | `staleActionsCannotNavigateBlockedPane`：双向 × 9 种状态；忙碌解除后可正常导航 |
| 概览降序和仅看异常不改变批次导航顺序 | `overviewSortingAndFilteringDoNotChangeNavigationOrder`：先确认降序异常行，再从概览双向跳转并核对实际错误 |
| 两主题 480/640 宽结果栏可容纳全部按钮及选择器 | `bothDirectionsAndDetailsFitWithoutShrinkingResultSelector`：四组合，检查整行在编辑器内、控件不重叠、完整按钮宽度、选择器至少 100、焦点可达；不是原生桌面验收 |
| 导航限定已保留范围，省略异常仍计入总汇 | `SqlBatchResultsTest.retentionBoundIsExplicitAndSummaryCountsEvenOmittedFailures`：双向 × 999/1000/1001 项，检查截止点、快照、关闭和省略项不可达 |
| 等值记录保持独立身份且每次只呈现一次 | `equalFailuresRemainDistinctNavigationTargets`：两方向在重复失败之间往返，断言选中下标、值引用、渲染次数和原始 Entry 引用；`equalValuesCanBeSelectedManuallyWithoutRetainingPreviousOccurrence`：查询/更新/错误的手动下拉选择均检查同样身份约束 |

## RED / GREEN

- 修改前两类 62 项，35 项失败（25 秒 exit 1）：新增入口尚不存在；等值用例最初缺少 Scene/CSS 初始化，先修正测试夹具，再单独重跑等值用例（8 秒 exit 1），确切复现“下一异常”应选下标 3 却留在 1；另一方向仍因入口缺失失败。
- 首次实现将目标改为下标并加入逆向查找，五类 138 项中仍有 2 项失败（19 秒 exit 1）：下标已变化，但 JavaFX 对等值记录保留旧 ComboBox 值。这不能仅靠 `select(index)` 修复。
- 将内部 `Choice` 从值相等的 record 改为不可变身份对象，不改报告、展示内容或执行数据；两个方向共用带守卫的查找，按实际位置选择。补齐三种结果的手动切换用例后，五类 141 项通过（29 秒 exit 0）。
- 再补概览筛选/排序组合与整行布局边界，最终五类 143 项全部通过（23 秒 exit 0），`git diff --check` 通过。

定向命令（PowerShell 中设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`）：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlBatchFailureNavigationTest --tests com.datacube.fx.SqlBatchResultsTest --tests com.datacube.fx.SqlBatchDetailsIntegrationTest --tests com.datacube.fx.SqlBatchResultsIntegrationTest --tests com.datacube.fx.SqlOverviewResultNavigationTest --no-daemon --console=plain
```

## 最终验证

同样设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，运行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- 2 分 37 秒 exit 0：主测试 3,112 项，其中 3,109 通过、3 项既有 live 跳过、0 failures/errors；buildSrc 8 项全部通过。两个改动类 67 项纳入全量，相比原 28 项净增 39 项。
- 开发镜像构建成功；保留既有 unchecked 编译、`JAVA_TOOL_OPTIONS` 及 JEP 493 辅助提示，没有新增构建失败。
- 生产模块提取至 `build/previous-failure-module`，含 825 文件、810 class、0 Fixture 类。启动配置保持生产入口 `com.datacube/com.datacube.DataCubeFx`，无 fixture patch 或临时 profile；SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。
- 自审已逐项核对上表断言、边界与改动范围，未再改生产代码；`git diff --check` 通过。

## 本地集成

- 实现提交 `f3af71c`，功能分支跟踪文件干净。确认 root main 仍为基线 `f124008` 且跟踪文件、暂存区干净后，`git merge --ff-only codex/sql-batch-previous-failure` 本地快进成功；既有未跟踪 `.testagent/` 未读取、暂存或修改。
- main 设置相同 `JAVA_TOOL_OPTIONS` 后运行以下命令，51 秒 exit 0：12 类 259 项全部通过，0 failures/errors/skips，开发镜像重建成功。

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlOverviewDurationSortTest --tests com.datacube.fx.SqlOverviewPreviewTest --tests com.datacube.sqleditor.SqlScriptPreviewTest --tests com.datacube.fx.SqlOverviewFailureFilterTest --tests com.datacube.fx.SqlOverviewResultNavigationTest --tests com.datacube.fx.SqlScriptDetailsIntegrationTest --tests com.datacube.fx.SqlBatchResultsIntegrationTest --tests com.datacube.fx.SqlBatchDetailsIntegrationTest --tests com.datacube.fx.SqlBatchFailureNavigationTest --tests com.datacube.fx.SqlScriptDetailFindTest --tests com.datacube.fx.SqlBatchResultsTest --tests com.datacube.sqleditor.SqlScriptExecutionReportTest jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- main 生产模块提取至 `build/previous-failure-main-module`，与 worktree 全量验证版本按相对路径及 SHA-256 比较：各 825 文件、810 class，0 差异、0 Fixture 类；启动配置散列也一致。没有把测试夹具写入生产镜像。
- 此集成只证明自动验证与本地构建一致，不补记未执行的原生桌面或远端发布验收。

## 明确保留的待验项

- 本轮没有重试之前报 `GetCursorPos` 访问拒绝的原生桌面通道；自动 JavaFX Scene/Stage 布局及事件不等于原生鼠标/键盘验证。
- 桌面恢复后补验：新镜像中“上一异常”的鼠标及 Tab/空格、首尾循环、详情关闭和明暗窄栏。同时保留 [SQL 摘要待验](2026-09-20-sql-overview-preview.md)及 [耗时表头交互待验](2026-09-20-sql-overview-duration-sort.md)。
- 不宣称真实用户效率、live 数据库或发布验收完成；不推送、不打 tag。开发镜像版本 `0.0.0` 不是正式版本号。

## 后续原生补验

桌面恢复后的双向鼠标导航、上一异常 Shift+Tab/Space、首尾循环、窄窗及无异常时禁用，见 [批量结果桌面收尾](2026-09-20-sql-batch-desktop-acceptance.md)。未在该表记录的交互（例如保持详情打开时切换异常）仍不能视作原生验证通过。
