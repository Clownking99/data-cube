# 当前批量结果执行详情验收

基线 main `5b21ec3`，分支 `codex/sql-batch-detail-entry`；[设计与范围](../specs/2026-09-19-sql-batch-detail-entry.md)。只使用合成结果、零网络探针和独立文件，不读取 `.testagent/` 或真实用户数据。

## 行为证据

| 需求 | 自动回归 |
| --- | --- |
| 直接核对当前查询、更新、错误、超时或取消；SQL 与原返回语句相符 | `SqlBatchDetailsIntegrationTest.directDetailsUseReturnedOutcomeAndPreserveEditorAndResult`：非连续语句序号、五种结果类型、编辑后再打开；只读正文、状态与错误逐项匹配 |
| 不修改结果筛选、表格/列身份、导出来源或 SQL/选区/撤销 | 同一 `directDetailsUseReturnedOutcomeAndPreserveEditorAndResult`：查询已过滤为 beta，详情仍准确说明原已加载 2 行，关闭后过滤数据/SQL 来源保持 |
| 一次一个窗口，ESC 关闭，可重新打开 | 同一测试的重复按钮回调、实际 Dialog 的 ESC 及 `changingResultOrLifecycleClosesOldDetails` 的重新打开 |
| 切换/概览/单结果/清空/错误/计划/新批次/忙碌/禁用/关闭清理旧详情 | `changingResultOrLifecycleClosesOldDetails` 十种转换；概览旧入口仍可见，忙碌恢复后按钮重新可用 |
| 过期按钮不能绕过关闭与执行守卫 | `staleButtonActionCannotBypassPaneGuards`：resources/tasks/finalized/root/table/running/admission/queue/overview 九种状态 |
| 使用现有有界快照，旧候选不能串入新批次 | `boundedSnapshotIsReusedAndOldChoicesCannotSupplyNewDetails`：20,000 单元 SQL/错误截为 16,384 且有提示；新批次拒绝旧候选；`SqlBatchResultsTest.retentionBoundIsExplicitAndSummaryCountsEvenOmittedFailures` 验证 detail 与 report entry 是同一对象 |
| 新按钮不挤出窄窗；沿用明暗主题 | `SqlBatchResultsIntegrationTest.resultSwitcherAndFailureSummaryStayInsideNarrowAndWidePanels` 扩展检查按钮的横向边界及完整首选宽度，480/880、明暗四组合 |

## 自动测试

- RED：新增详情入口类 25 项全部在“缺少直接详情按钮”处失败，26 秒 exit 1。
- 最小实现仅修改 `SqlBatchResults`，不改执行器和 `SqlEditorPane`。新增详情类及批次/概览三个既有类定向运行，18 秒 exit 0。
- 最终 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，运行 `gradlew.bat clean :buildSrc:test test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`：2 分 54 秒 exit 0；主测试 XML 2,931 项，2,928 通过、3 既有 live 跳过、0 failures/errors；buildSrc 8 项通过。沿用既有 unchecked 和打包 JAVA_TOOL_OPTIONS/JEP 493 提示，最终 BUILD SUCCESSFUL。

## 隔离桌面与镜像

从最终生产镜像复制 `build/batch-detail-desktop-image`，只在副本 patch 既有 `SqlScriptDetailsDesktopFixture`。独立 `build/script-details-desktop-profile`，无注册连接，执行按钮禁用，不执行 SQL、不接触系统剪贴板。

- 暗色宽窗：默认首查询旁的新按钮完整可见；点击直接显示 `select id from sample;`、语句 #1、12ms、已加载 1 行；鼠标关闭后结果仍在。
- 切到 #4 失败后，原生 Tab 聚焦按钮、空格打开详情；展示该语句的 SQL、合成长错误全文及 SQLState，仍为只读、带事务边界说明。没有先切执行概览。
- 暗色和亮色窄窗：新按钮与选择器保持完整；亮色窄窗直接打开对应错误详情，正文换行可读。详情是可调整尺寸的独立窗口，不要求限制在主窗口边界内。
- 回到执行概览后，新按钮禁用、原逐行详情入口保留；替换为单条查询后整条批次区域及新按钮消失，显示新查询。
- Alt+F4 正常关闭；后续进程检查无 DataCube，独立 profile 仅合成 SQL、主题设置和 JavaFX 缓存。
- 本轮原生键盘验证为 Tab/空格打开；ESC 的证据来自真实 JavaFX Dialog 事件回归，不将其扩大为桌面原生 ESC 验收。忙碌/关闭等程序化生命周期由集成测试覆盖。

`jimage extract` 验证正式开发镜像应用模块 823 文件、808 class，零 Fixture 类。正式 `DataCube.cfg` 保持 `DataCubeFx` 入口，无 patch/profile 参数；测试副本不交付。

## 本地 main 集成

- 实现提交 `d748a78`，从 `5b21ec3` 快进 main，合并前后 tracked 工作区干净，没有覆盖用户改动。
- main 六类定向回归：`SqlBatchDetailsIntegrationTest`、`SqlBatchResultsTest`、`SqlBatchResultsIntegrationTest`、`SqlScriptDetailsIntegrationTest`、`SqlEditorResultFilterContractTest`、`SqlScriptExecutionReportTest`；120 项全通过、0 failures/errors/skipped。与 `jpackageImage -PappVersion=0.0.0` 同次运行，55 秒 exit 0。
- main 新生产镜像与 worktree 已验收生产镜像逐文件 SHA-256 核对：应用模块 823/823 一致（808 class），零差异、零 Fixture 类。两份 `DataCube.cfg` 哈希均为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`，正常 `DataCubeFx` 入口，无 patch/profile 参数。
- `git diff --check` 通过；本段验收记录另作 docs 提交，不改生产代码。

本地合成验收不代表 live 数据库、真实用户耗时实验或远端 CI/发布；本轮不推送、不打 tag。
