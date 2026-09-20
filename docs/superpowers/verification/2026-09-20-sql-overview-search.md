# 执行概览 SQL 摘要筛选验收

基线 main `0a419a0`，分支 `codex/sql-overview-search`；[设计](../specs/2026-09-20-sql-overview-search.md)。生产改动仅限 `SqlScriptDetails`；不触及数据库执行、事务、保存、依赖或版本规则。合成结果使用已有离线探针及临时 SQL 文件，不读取真实用户连接、SQL、历史、剪贴板或 `.testagent/`。

## 行为证据

按 `code-testing-agent` 聚焦流程新增 `SqlOverviewSearchTest`，40 项测试，不另建测试状态目录。沿用既有异常过滤、结果导航与耗时排序测试校验兼容性。

| 需求 | 证据 |
| --- | --- |
| 忽略大小写及首尾空白、字面匹配可见摘要，保持编辑器/文件及汇总不变 | `matchesDisplayedPreviewLiterallyAndCaseInsensitively`：7 组，含换行展平、中文、正则/通配符外观字符、空白及零匹配；断言具体语句、计数、批次选择、撤销和零网络探针 |
| 与异常条件取交集、保留数值排序及可见选择，隐藏选择不代选/复活 | `combinesWithFailureFilterAndPreservesSortAndOnlyVisibleSelection`：两类选择；清除关键词保留异常条件，取消异常条件保留关键词 |
| 筛选后打开正确的原始结果或详情，修改词关闭旧详情 | `filteredRowsOpenOriginalResultOrDetailsAndChangingQueryClosesDialog`：两个动作，核对原始 SQL/错误、选择编号、旧窗口释放和离开概览重置 |
| 不搜索完整 SQL、错误、结果数据和编辑器后改内容 | `doesNotSearchBeyondDisplayedSqlPreview`：四个范围；只在 120 单元后出现的词不匹配，而可见省略标记及可见词可匹配 |
| 255/256/257 边界、emoji 超限整体拒绝，保留视图并可恢复 | `rejectsOverlongInputWithoutLosingQueryRowsSelectionOrRecovery`：断言输入、行列表引用、选择引用、提示、继续输入及清除提示；空条件的拒绝提示也可清除 |
| 缺失 SQL 的可见提示可匹配，空值重置后仍可继续输入 | `displayedMissingSqlNoticeIsSearchableAndNullQueryRestoresAllRows` |
| 只搜索已保留的前 1,000 条，总汇不缩小 | `searchOnlySeesRetainedEntriesAndCountsOmittedOutcomesSeparately`：999/1000/1001 条，分别检查关键词、可见/保留计数、总返回量与省略通知 |
| 忙碌、关闭及禁用时拒绝输入与旧清除动作，恢复忙碌后可操作 | `blockedPaneRejectsTextChangesAndStaleClearActions`：8 种状态，保留原关键词、列表引用和提示 |
| 新结果/清空/关闭不恢复旧条件或数据，等值行按身份定位 | `replacementClearsQueryAndOldActionsCannotRestoreOldRows`：7 种替换；`equalVisibleRowsKeepOriginalIdentityAfterKeywordChanges` |
| 明暗主题 480/640 宽下控件及长提示在边界内、结果区保留空间 | `queryClearCountsAndActionsFitBothThemesAndNarrowPanels`：四组，检查输入框至少 140 宽、按钮完整、提示高度与结果表至少 100 高；仅自动布局，不是原生验收 |

## 复现与修复

- 先运行新增类：39 项全部因缺少筛选入口失败，27 秒 exit 1。
- 首次实现后四类 111 项中仅 4 个新布局用例失败（17 秒 exit 1）；单独复验同样失败（9 秒）：长零匹配/拒绝提示把 FlowPane 最小宽度撑至 646，导致 480/640 编辑器越界。允许工具栏及计数标签缩小后，111 项通过（16 秒 exit 0）。
- 自审补充空 SQL/空值重置用例后，112 项中 1 项失败（27 秒 exit 1）。已启动的首次全量同样在该项失败：3,152 项、1 失败、3 既有 live 跳过，2 分 37 秒 exit 1；不作为通过证据，也未进行其后的打包。
- `TextFormatter` 对 `setText(null)` 重置时组装替换文本会触发范围异常；仅在有新增文本时检查新长度，纯删除不增加长度，避免该不必要操作。保留所有输入上限与禁用守卫。
- 最终定向四类 112 项全部通过，18 秒 exit 0（新增类 40、原异常筛选 32、结果导航 32、耗时排序 8），`git diff --check` 通过。

定向命令（PowerShell 设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`）：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlOverviewSearchTest --tests com.datacube.fx.SqlOverviewFailureFilterTest --tests com.datacube.fx.SqlOverviewResultNavigationTest --tests com.datacube.fx.SqlOverviewDurationSortTest --no-daemon --console=plain
```

## 最终全量与本地集成

同样设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，修复后重新运行：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- 2 分 40 秒 exit 0：主测试 3,152 项，其中 3,149 通过、3 项既有 live 跳过、0 failures/errors。新增 40 项全部纳入。buildSrc 8 项本轮已通过，本次无源码变化为 UP-TO-DATE，报告无失败/错误/跳过。
- 开发镜像成功；保留既有 unchecked 编译、`JAVA_TOOL_OPTIONS` 与 JEP 493 辅助提示，最终构建成功。自审已逐项检查上表断言与生产差异，之后未再改生产代码。
- 本地 main 集成及生产模块一致性在实际完成后补记。先前失败全量没有被计为成功证据。

## 原生桌面待验

本轮未重试此前 `GetCursorPos` 访问拒绝的通道。桌面恢复后补验新镜像的实际输入、清除按钮、Tab 焦点、筛选后鼠标/Enter 查看及明暗窄窗；此前 SQL 摘要、耗时排序和双向异常按钮的待验项继续保留。自动 JavaFX Scene/Stage 测试不是原生桌面证据，不宣称真实用户效率、live 数据库兼容性或远端发布已验收。不推送、不打 tag；`0.0.0` 仅为开发镜像版本。
