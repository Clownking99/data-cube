# 执行概览仅看异常验收

基线 main `8861fea`，分支 `codex/sql-overview-filter`；[设计](../specs/2026-09-20-sql-overview-filter.md)。不操作真实数据库/SQL/历史/剪贴板及 `.testagent/`，只使用合成结果与一次性配置目录。

## 行为证据

回归类 `SqlOverviewFailureFilterTest`：

| 需求 | 回归证据 |
| --- | --- |
| 过滤失败/超时/取消，保留排序、仅保留仍可见的选择 | `filterKeepsSortAndVisibleIdentityButNeverReassignsHiddenSelection`：异常与正常选择两种情况，双向切换；批次汇总/下拉框、编辑器/选区/撤销/文件/零网络探针不变 |
| 筛选排序后查看结果或 Enter 详情仍对应原始语句 | `filteredSortedRowOpensItsOriginalResultOrDetails`：查看原始错误，筛选变化关闭旧详情，离开概览重置 |
| 零匹配限定为已保留范围，不隐瞒省略的异常 | `zeroMatchesDescribeRetainedScopeEvenIfAnOmittedOutcomeFailed`：2/999/1,000/1,001；第 1,001 项异常仍计入总汇，恢复全部不会代选 |
| 全部异常、相同编号/SQL/摘要仍保留准确快照 | `allAbnormalEqualRowsKeepTheExactSelectedSnapshot`：同一 outcome 重复两次，选中第二条后筛选并查看，身份/索引均正确 |
| 忙碌/关闭/禁用期间迟到动作不能过滤 | `staleToggleCannotMutateBlockedOverview`：八种状态、复选框恢复旧值、忙碌解除后正常过滤 |
| 新结果等替换释放旧概览，条件不跨批次 | `replacementResetsFilterAndOldToggleCannotRestoreRows`：查询/空结果/错误/计划/清空/新批次/关闭 |
| 明暗宽窄窗、零匹配长文案均不溢出 | `filterActionsCountsAndNoticeFitBothThemes`：480/880 × dark/light × 有匹配/零匹配 |

## 自动验证过程

- RED：初始 27 项均因缺少筛选控件失败，27 秒 exit 1，无编译错误。
- 首轮实现的 83 项定向测试中 2 项失败：JavaFX 替换 items 列表会清空排序。加入等值摘要回归后 84 项仍为同两项排序失败；保存并恢复 sortOrder 后三类定向 84 项全部通过，17 秒 exit 0。
- 扩展零匹配明暗宽窄布局四项，新增类共 32 项。只改 `SqlScriptDetails` 概览控制器及纯测试桌面夹具，未改数据库执行和草稿逻辑。

## 最终验证

命令（PowerShell，`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`）：

```powershell
.\gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- 最终全量 2 分 45 秒 exit 0：主测试 3,042 项，其中 3,039 通过、3 项既有 live 跳过、0 failures/errors；buildSrc 8 项通过、无跳过或失败。新增筛选类 32 项均包含在此次结果中。
- 保留既有 unchecked 编译提示、`JAVA_TOOL_OPTIONS` 及打包辅助输出；没有将这些提示当作新增失败，最终构建成功。
- 开发镜像生产入口仍为 `com.datacube/com.datacube.DataCubeFx`，无测试 patch 或一次性 profile；配置 SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。
- `jimage extract` 提取生产 `com.datacube` 模块：824 文件 / 809 class，无 Fixture 类。验收 jar 仅添加到独立镜像副本，不进入生产镜像。

## 隔离桌面

Computer Use 控制本轮开发镜像副本，patch 纯测试 `SqlScriptDetailsDesktopFixture`；使用 `build/script-details-desktop-profile`，只呈现合成的查询、更新、错误、超时和取消，不建立数据库连接或提交 SQL。

- 暗色宽窗的概览默认显示 6 / 6 条；按编号降序排序后选择正常查询，勾选筛选仅保留 6（取消）、5（超时）、4（错误），计数 3 / 6。原选择被隐藏后清空，“查看结果 / 执行详情”禁用，批次汇总不变。
- 聚焦复选框后原生空格两次切换，恢复全部和重新筛选仍保持降序，不代选其他语句。
- 筛选内明确选择第 4 条，缩窄至 640、切为亮色后点击“查看结果”，显示第 4 条的完整合成诊断及 `SQLState=42703`。工具条和提示换行可见，结果区域可用。
- 切换为仅含查询/更新的两条正常批次，再进入概览时默认不带旧条件；亮色窄窗勾选后显示 0 / 2 及“已保留结果中没有异常”，两项查看动作禁用，但筛选仍可操作。
- 零匹配提示在亮色宽窗、暗色宽窗均可见；取消勾选恢复两条原始摘要，不自动选择。
- Alt+F4 正常退出，随后确认本轮镜像进程为 0。合成 SQL 的混合 CRLF/LF 文本逐字一致；profile 顶层仅 `.openjfx`、`settings.properties`、`synthetic-details.sql`。没有读取或修改用户连接、SQL、历史或剪贴板。

Enter 详情、全部异常、等值行、八种忙碌/关闭边界及保留上限由自动回归覆盖，不声称本轮另有原生 Enter 或 live 数据库验收。桌面证据不等同于完整 AppShell、真实用户效率或远端发布验证。

## 本地集成

- 实现提交 `7269e70` 已从 `codex/sql-overview-filter` 本地快进 main；合并前核对 main 仍为 `8861fea` 且跟踪文件/暂存区干净，未读取或改动既有未跟踪 `.testagent/`。
- main 上运行以下命令，1 分 1 秒 exit 0：9 类 189 项全部通过、无失败/错误/跳过，开发镜像重建成功（同样设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`）。

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlOverviewFailureFilterTest --tests com.datacube.fx.SqlOverviewResultNavigationTest --tests com.datacube.fx.SqlScriptDetailsIntegrationTest --tests com.datacube.fx.SqlBatchResultsIntegrationTest --tests com.datacube.fx.SqlBatchDetailsIntegrationTest --tests com.datacube.fx.SqlBatchFailureNavigationTest --tests com.datacube.fx.SqlScriptDetailFindTest --tests com.datacube.fx.SqlBatchResultsTest --tests com.datacube.sqleditor.SqlScriptExecutionReportTest jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

- main 镜像提取到 `build/overview-filter-main-module`，与 worktree 最终桌面验收所用生产模块逐路径、逐文件 SHA-256 比较：双方 824 文件（main 809 class）、0 差异、0 Fixture 类；生产启动配置哈希亦完全相同。
- `git diff --check` 通过。本轮不推送、不打 tag，不变更版本号；镜像 `0.0.0` 仅用于本地开发验收。
