# 批量结果“下一异常”验收

基线 main `b5510eb`，分支 `codex/sql-batch-next-failure`；[设计与范围](../specs/2026-09-19-sql-batch-next-failure.md)。只使用合成结果、零网络探针和独立文件，不读取 `.testagent/` 或真实用户数据。

## 行为证据

| 需求 | 自动回归 |
| --- | --- |
| 从概览、查询、更新或异常定位下一异常，失败/超时/取消按执行顺序循环 | `SqlBatchFailureNavigationTest.nextFailureUsesExecutionOrderAndWrapsWithoutExecuting`：七个起点，非连续语句序号及相同 SQL 文本，逐项核对选择、错误正文与详情；不自动弹窗 |
| 当前唯一异常不重复重置；回正常项后可重新定位 | `uniqueFailureIsReachableOnceAndDoesNotResetCurrentDetails`：按钮禁用，过期回调保留同一详情和结果 |
| 替换结果、清空或关闭后旧动作不能恢复旧批次 | `replacingBatchDisablesOldNavigationAndPreservesNewResult`：empty/single/normal/error/plan/clear/close 七类替换 |
| 关闭、忙碌等守卫不可绕过 | `staleActionsCannotNavigateBlockedPane`：resources/tasks/finalized/root/table/running/admission/queue/busy 九类状态，忙碌结束恢复导航 |
| 目标按当前批次重算，不捕获旧索引 | `actionRecomputesTargetFromNewBatchInsteadOfCapturedIndex`：旧动作回调只定位新批次的异常 |
| 不执行 SQL、不改文本、选区、撤销或源文件 | 顺序测试核对编辑器状态，既有隔离 fixture 的零网络探针和源文件内容在各用例后验证 |
| 只定位已保留结果，不漏报已省略异常 | `SqlBatchResultsTest.retentionBoundIsExplicitAndSummaryCountsEvenOmittedFailures`：999/1,000/1,001 条，最后一条才异常；超限不能导航，但汇总仍计入失败 |
| 明暗窄窗保持完整按钮及可用选择器 | `SqlBatchResultsIntegrationTest.resultSwitcherAndFailureSummaryStayInsideNarrowAndWidePanels`：480/880、明暗四组合，含两个按钮完整首选宽度 |

## 自动测试

- RED：新增导航类 25 项均因缺少按钮失败，25 秒 exit 1；无编译错误。
- 最小实现仅修改 `SqlBatchResults`，不改执行器、SPI 或 SQL 解析。导航、直接详情、批量结果、报告保留边界和概览详情五类定向回归运行成功，19 秒 exit 0。

- 最终 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，运行 `gradlew.bat clean :buildSrc:test test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`：2 分 40 秒 exit 0；主测试 XML 2,956 项，2,953 通过、3 既有 live 跳过、0 failures/errors；buildSrc 8 项通过。沿用既有 unchecked 和打包 JAVA_TOOL_OPTIONS/JEP 493 提示，最终 BUILD SUCCESSFUL。

## 隔离桌面与镜像

从最终生产镜像复制 `build/next-failure-desktop-image`，只在副本 patch 既有 `SqlScriptDetailsDesktopFixture`。独立 `build/script-details-desktop-profile`，无注册连接，执行按钮禁用，不执行 SQL、不接触系统剪贴板。

- 暗色宽窗：新按钮与“执行详情”完整可见；从默认首查询鼠标点击“下一异常”到 #4 失败，直接展示对应错误，不弹详情。
- 按原生空格依次到 #5 超时、#6 取消、回到 #4 失败；批次汇总始终显示正常 3、失败 1、超时 1、取消 1。
- 从“下一异常”按 Tab 到“执行详情”，空格打开，确认为 #4、5ms、对应 SQL 与长错误/SQLState；只读说明和事务边界提示可见，鼠标关闭。
- 暗色和亮色窄窗：选择器、两个按钮完整可见，结果区保留；亮色窄窗点击到 #5 超时；Shift+Tab 回选择器、Tab 回新按钮，焦点可见。
- 替换为单条查询后批次选择器、汇总及两个按钮消失，显示新查询 id=1；源 SQL 内容未变。Alt+F4 正常关闭，后续进程检查无 DataCube，profile 仅合成 SQL、主题设置及 JavaFX 缓存。
- 唯一异常、超限、忙碌/关闭和过期动作由自动回归覆盖；本轮不追加原生 ESC 或 live 数据库验证结论。

`jimage extract` 验证正式开发镜像应用模块 823 文件、808 class，零 Fixture 类。正式 `DataCube.cfg` 为 `DataCubeFx` 入口，无 patch/profile 参数，SHA-256 为 `AD4F0A4A8AA7F06FEB3072B67FA10966ECFFE3D2040951D5C701A4EF41E4BFF9`；测试副本不交付。

本地 main 集成结果将在实际完成后追加；不将合成验证等同于 live 数据库、真实用户耗时研究或远端发布。本轮不推送、不打 tag。
