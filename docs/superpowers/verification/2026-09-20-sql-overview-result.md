# 执行概览查看结果验收

基线 main `2658a5d`，分支 `codex/sql-overview-result`；[设计](../specs/2026-09-20-sql-overview-result.md)。仅合成数据与一次性配置，不操作真实数据库、用户 SQL/历史、系统剪贴板或 `.testagent/`。

## 行为证据

均位于 `SqlOverviewResultNavigationTest`：

| 需求 | 回归证据 |
| --- | --- |
| 排序后打开正确数据、影响行数或异常 | `sortedOverviewOpensExactResultAndPreservesEditorAndSource`：查询/空查询/更新/未知行数/错误/超时/取消，非连续编号和重复 SQL；检查导出 SQL、Schema、筛选重置、编辑器文本/选区/撤销/源文件与零网络探针 |
| 无选择和伪造等值行不能导航；Enter 保留详情语义 | `noSelectionAndForeignRowCannotNavigateAndEnterStillShowsDetails` |
| 动作执行时再次检查忙碌/关闭守卫 | `actionTimeGuardsPreventNavigationWhileBlocked`：八种状态；忙碌结束后按钮恢复且可导航 |
| 旧动作不恢复旧批次，仅当前新选择可导航 | `oldActionCannotRestoreReplacedResult`：单查询/空结果/错误/计划/清空/新批次/关闭 |
| 480/880 明暗布局中两个入口完整且表格可用 | `overviewActionsFitNarrowAndWideThemes` |
| 按快照对象身份，不按 SQL/编号/值相等定位 | `identicalEntriesAreMatchedByIdentityAndStaleOrForeignEntriesCannotNavigate`；同摘要不同数据、外来等值快照、旧快照、禁用、清空、关闭均覆盖 |
| 完全相等的 Choice 仍定位所选条目 | `equalChoicesStillNavigateToTheSelectedEntryIdentity`：同一个 outcome 重复两次，核对第二条 entry 身份和选择索引 |
| 沿用前 1,000 条保留上限 | `navigationUsesOnlyRetainedEntries`：999/1,000/1,001，省略项无法导航 |

## 自动验证过程

- RED：初始 27 项全部因缺少“查看结果”按钮失败，40 秒 exit 1，无编译错误。
- 第一轮实现后 77 项中 6 项失败：两处预期未包含既有“影响 N 行”文案，四处独立 ComboBox 夹具未安装 CSS/皮肤。调整夹具和预期后 77 项通过，16 秒 exit 0。
- 自审扩展“完全相等的 Choice”用例，发现 JavaFX 按对象选择会匹配第一个等值记录（10 秒 exit 1）。改为快照身份匹配后选择准确索引，新测试类 32 项全部通过，13 秒 exit 0。
- 全量命令首试未正确引用 PowerShell `-PappVersion=0.0.0`，在任务选择阶段退出；改为引号参数后重跑，不将首次命令计入验证。
- 首次完整测试 3,010 项中 1 项失败、3 项既有 live 跳过，2 分 30 秒 exit 1。失败为既有 `TableSelectSqlTabsTest` 在草稿初始化仍为 INITIALIZING 时 flush；其 metadata barrier 与草稿初始化属于不同队列。参照现有草稿生命周期测试增加完成观察及 ENABLED 断言，不加 sleep、不改变生产草稿逻辑，然后重新完整验证。

## 最终自动验证

`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，`gradlew.bat clean :buildSrc:test test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain`：3 分 19 秒 exit 0。主测试 XML 共 3,010 项，3,007 通过、3 项既有 live 跳过、0 failures/errors；buildSrc 8 项本轮已有通过报告，本次 up-to-date。沿用既有 unchecked、JAVA_TOOL_OPTIONS/JEP 493 打包提示，最终 BUILD SUCCESSFUL。

## 隔离桌面验收

最终生产镜像复制为 `build/overview-result-desktop-image`，仅在副本 patch 已有 `SqlScriptDetailsDesktopFixture`，独立 profile 为 `build/script-details-desktop-profile`。首次副本启动失败；补齐测试启动配置的绝对 patch 路径及 JavaFX 模块访问参数后正常启动，不修改正式镜像配置。

- 暗色宽窗：执行概览无选择时两个逐行按钮禁用；编号倒序后选择 #3，点击“查看结果”显示 READY / WAITING 两行数据，选择器同步到语句 #3，没有重新执行或自动弹详情。
- 明暗窄窗：概览入口、说明和批次汇总可见，结果表保留滚动。亮色窄窗选择 #2 后，从结果选择器用原生 Tab 依次聚焦“下一异常”“查看结果”，空格显示“影响 3 行”；未把未验证的 Shift+Tab 路径写成通过。
- 亮色宽窗：从 #4 摘要“查看结果”显示对应 missing_column 错误及 SQLState=42703；红色汇总保持正常 3 / 失败 1 / 超时 1 / 取消 1。
- “替换为查询”移除旧批次选择器与概览入口，只显示新单条 id=1。Alt+F4 正常退出，窗口列表与进程检查不再包含验收实例。
- 合成源文件逐字符不变，profile 仅合成 SQL、主题设置及 JavaFX 缓存；未注册数据库连接、读取真实配置或操作剪贴板。
- Enter 保留执行详情语义、八种忙碌/关闭守卫和前 1,000 条边界由自动回归覆盖，本轮桌面不冒充 live 数据库或完整 AppShell 验收。

生产镜像经 `jimage extract` 检查，应用模块 824 文件、809 class，零 Fixture 类。正式 `DataCube.cfg` 保持 `DataCubeFx` 入口，无 patch/profile 参数；SHA-256 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`。桌面副本不交付。

## 本地集成

- 实现提交 `e15c432` 从 `2658a5d` 快进本地 main，合并前核对分支、完整基线 hash 与 tracked 工作区；未覆盖用户改动。
- main 九类定向回归：`SqlOverviewResultNavigationTest`、`SqlScriptDetailsIntegrationTest`、`SqlBatchResultsIntegrationTest`、`SqlBatchResultsTest`、`SqlBatchDetailsIntegrationTest`、`SqlBatchFailureNavigationTest`、`SqlScriptDetailFindTest`、`TableSelectSqlTabsTest`、`SqlScriptExecutionReportTest`。XML 165 项全部通过，0 failures/errors/skipped；与 `jpackageImage '-PappVersion=0.0.0'` 同次执行，1 分 33 秒 exit 0。
- main 与 worktree 应用模块逐文件 SHA-256 比较：824/824 完全相同（809 class），零差异、零 Fixture；正式 `DataCube.cfg` 哈希均与上文相同，正常入口，无测试 patch/profile 参数。
- `git diff --check` 通过。本段记录另作 docs 提交，不修改生产代码；本轮不推送、不打 tag。
