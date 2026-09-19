# 多语句结果与执行反馈修复验收

基线 main `07be400`，分支 `codex/sql-batch-results`；[设计](../specs/2026-09-19-sql-batch-results.md)。测试均使用合成结果、JDBC 替身或独立临时文件，不访问真实连接、业务数据或 `.testagent/`。

## 需求与证据

| 用户报告 / 修复边界 | 回归证据 |
| --- | --- |
| “一次选中多条 SQL 语句执行，就不会展示执行的具体结果” | `SqlEditorResultFilterContractTest.selectedMultipleStatementsReachTheResultSwitcherWithoutExecutingExcludedSql`：按钮/F5、反向选择两条、排除未选中语句；执行仅一次，切换真实返回的两个查询数据 |
| 多结果分别保留数据/SQL/Schema；筛选和导出不串结果 | `SqlBatchResultsIntegrationTest.batchQueriesExposeTheirOwnRowsSqlSchemaAndExportsWithoutReexecuting`：查询/更新/查询/失败混合、逐条切换及概览、过滤后导出、文件/SQL/撤销及零网络探针 |
| 空结果仍有列，未知影响行数不显示负数 | `SqlBatchResultsIntegrationTest.emptyQueryKeepsItsColumnsAndUpdateWithoutCountIsNotNegativeRows` |
| 部分失败不能伪装为全成功，详情和排序保持语句身份 | 既有 `SqlScriptDetailsIntegrationTest` 全类；原摘要专用测试显式切到“执行概览”后继续验证旧行为 |
| 执行/关闭/禁用期间旧切换动作无效 | `SqlBatchResultsIntegrationTest.blockedSelectionRestoresThePreviousChoiceAndNeverChangesData`：8 种守卫 |
| 单结果/清空/错误/计划/关闭释放旧批次 | `SqlBatchResultsIntegrationTest.newSingleResultClearsBatchAndDetachedChoicesCannotResurrectOldData`、`replacingBatchReleasesChoicesAndOldSummary` |
| 明暗 480/880 窗口中切换器和失败汇总不溢出 | `SqlBatchResultsIntegrationTest.resultSwitcherAndFailureSummaryStayInsideNarrowAndWidePanels` |
| 前 1,000 项保留边界、未展示的失败仍计入汇总 | `SqlBatchResultsTest.retentionBoundIsExplicitAndSummaryCountsEvenOmittedFailures`：999/1,000/1,001，关闭清除引用 |
| “一行语句执行失败…最后一条执行失败也提示” | `ScriptContinuationTest.terminalFailureNeverAsksToContinue`：Oracle/PostgreSQL，单条、末条、尾随注释 |
| PL/SQL 块中的分号和尾随 `/` 不算后续语句 | `ScriptContinuationTest.oracleBlockAndTrailingSlashAreOneStatementForContinuation` |
| 末条超时也不询问；中间失败仍保留三种决策 | `ScriptContinuationTest.terminalTimeoutIsReturnedWithoutContinuationPrompt`、`intermediateFailureHonorsDecisionAndNeverAsksForLastFailure`、`absentPolicyStillStopsAtFirstFailure` |
| 取消和手动事务遇错停止不回归 | `OracleSqlRunnerExecutionControlTest`、`PgSqlRunnerExecutionControlTest`、`JdbcEditorSessionTest` 既有测试 |
| “oracle 的 cascade 没有识别为关键字” | `SqlHighlighterTest.oracleDdlKeywordsAreCaseInsensitiveAndAvailableForCompletion`、`keywordsDoNotLeakIntoCommentsStringsQuotedOrExtendedIdentifiers`：共享高亮/补全词表、大小写、标识符 $/#、普通字符串/注释/双引号 |

## 自动测试过程

Gradle 使用 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，附 `--no-daemon --console=plain`。

- 首轮 RED：三个新测试类共 18 项，12 项失败；具体为缺少结果切换器、CASCADE 未着色及末条错误仍回调继续策略。29 秒 exit 1。
- 实现后新增三类 + 既有摘要集成、Oracle/PostgreSQL 执行控制，共六类定向运行，18 秒 exit 0。
- 扩展入口、生命周期、布局、保留上限及会话回归后，149 项中 3 项保留上限夹具失败：独立 ComboBox 未安装 Scene/皮肤，选择未触发动作。为夹具安装实际 Scene 后，不改生产行为，四类回归 14 秒 exit 0。
- 最终完整 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`：2 分 56 秒，exit 0；主测试 XML 共 2,906 项，2,903 通过、3 项既有 live 跳过，0 failures/errors。buildSrc 8 项已有成功报告，本次任务为 UP-TO-DATE。前一次全量运行被用户消息中断，不计为成功。
- 与基线相比新增 43 项回归。打包保留既有 JAVA_TOOL_OPTIONS/JEP 493 提示，最终 `BUILD SUCCESSFUL`；开发镜像版本固定 `0.0.0`，不是发布版本。

## 隔离桌面验收

从最终 `build/jpackage/DataCube` 复制可丢弃镜像，在副本中 patch 仅测试用的 `SqlScriptDetailsDesktopFixture`；独立 `user.home` 为 `build/script-details-desktop-profile`。使用真实 `SqlEditorPane`、合成 SQL 文件和六条预置结果；没有注册连接，执行按钮禁用。未执行示例 SQL、读取真实配置、访问数据库或使用系统剪贴板。

- 暗色宽窗启动默认显示第一条查询的 `id=1`；结果列表包含执行概览和六条查询/更新/失败/超时/取消记录。
- 鼠标选择第三条查询，表格显示 `READY / second query result`、`WAITING / another row`；原生 ↑ 切到第二条更新，显示“影响 3 行”，查询导出入口禁用。
- 切到第四条错误，显示合成错误而非上次查询数据；全程保留红色批次汇总“正常 3 · 失败 1 · 超时 1 · 取消 1”。
- 回到执行概览，六条摘要仍可见；选中第四条后 Enter 打开只读执行详情，显示对应 SQL、错误正文和 SQLState，点击“关闭”返回。仅确认 Enter/鼠标关闭，未把主窗口投递的 Escape 当作详情关闭验收。
- 暗色宽窗及明暗窄窗下结果选择器、失败汇总保持可见，工具栏换行，表格保留滚动；亮色窄窗再次切换第三条查询，两列两行数据正确。
- 使用夹具“替换为查询”后旧选择器/失败汇总消失，只有新单条查询；“恢复批量结果”重新默认第一条查询。
- 两种主题中小写 `cascade constraints` 均呈关键字样式。关键字覆盖不等于完整 SQL/PLSQL 词法或语法解析。
- Alt+F4 正常退出后无 DataCube 进程；隔离 profile 仅产生合成 SQL、主题设置和 JavaFX 缓存。没有更改真实用户数据。

生产镜像保持 `com.datacube/com.datacube.DataCubeFx` 入口，不含 patch/profile 参数。`jimage extract` 核对应用模块为 823 个文件（808 个 class），没有 Fixture 类；修改过入口的副本不作为交付镜像。

## 本地集成

本地 main 集成及合并后定向验证结果待追加。未运行真实 Oracle/PostgreSQL，不把 JDBC 替身测试当作 live 数据库或远端 CI/发布验收；本轮不推送、不打 tag。
