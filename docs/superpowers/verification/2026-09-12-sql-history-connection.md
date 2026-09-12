# 历史 SQL 连接选择验收

基线 `980384c`，分支 `codex/sql-history-connection`。[设计](../specs/2026-09-12-sql-history-connection.md)。本轮解决历史标签独立空会话没有选择入口的断点，沿用现有脚本选择器及受管文件/草稿生命周期，不修改执行管线。

## 行为证据

测试技能用于聚焦载入流程的 Java/JUnit 状态矩阵与精确断言；按本机约定内联实施，不建立通用中间状态文件或多代理流水线。不声明覆盖率百分比。

| 要求 | 证据 |
| --- | --- |
| 历史来源标题、完整 SQL/schema、选择入口、初始无目标、执行禁用，同名/全局选择不自动绑定 | `SqlHistoryTabsTest.historyTabOffersAnExplicitConnectionEntry` |
| PG/Oracle 明确选择稳定身份、排除 Redis、区分同名、生产/只读提示；未连接、文件干净、历史字节不变 | `explicitChoiceUsesStableIdentityAndStaysOfflineWithoutChangingHistory`（2 组） |
| 空选择提示、取消更换保留已有目标/SQL/schema/文件状态 | `emptyChoicesAndCancelledReplacementKeepTextSchemaAndTarget` |
| 目标删除/类型变更不按同名或全局目标回退，必须新确认 | `missingOrTypeChangedTargetNeverFallsBackToHistoryNameOrGlobalSelection`（2 组） |
| 模态选择期间关闭资源，迟到确认无效 | `closingDuringSelectionRejectsTheLateConfirmedTarget` |
| 初始干净未绑定、编辑 dirty、多个历史标签不互相覆盖，草稿包含选定 ID/type、SQL/schema | `editingAndChoiceReachDraftWithoutBindingAFileOrOverwritingAnotherHistoryTab` |
| null/空白/带引号 schema 沿用原有处理，不选择目标 | `schemaHintKeepsExistingNormalizationWithoutChoosingAConnection`（4 组） |
| 受管标签关闭后不再构造历史编辑器 | `closedTabsRejectHistoryBeforeConstructingAnEditor` |
| 明暗 600/900 宽的入口与指导文字布局、执行禁用 | `historyConnectionEntryAndGuidanceFitBothThemes`（4 组） |
| 真正历史路径保留未绑定文件基线（包括 CRLF）、文件控制器先于草稿绑定 | `SqlTabFileLifecycleTest.ordinaryAndHistoryTabsInstallUnboundFileControllersBeforeDraftBinding`，已改为调用新的历史边界 |
| 首次会话准入刷新配置并锁定目标，原文件选择/取消/迟到守卫仍正常 | 既有 `SqlFileConnectionTest`，含 `freshMatchingConfigIsUsedAtAdmissionAndThePinnedTargetCannotBeSwitched` |
| 生成 SELECT 的被动连接及文件/草稿组合不回归 | 既有 `TableSelectSqlTabsTest` |

新流程测试使用独占临时文件和 DraftConnectionProbe；在元数据队列屏障后断言 provider/session/metadata/network 全零。关闭期间场景不向已经关闭的队列添加屏障。草稿测试等待初始化完成后 flush，不以睡眠或构造耗时推断已准备好。

## 运行记录

- 抽取原历史载入边界后红灯：缺少显式连接入口，1 项失败（20s，exit 1）。
- 扩展定向测试第一轮 36 项中 8 项失败（26s，exit 1）：未安装 skin 的编辑区无法通过 CSS lookup 访问。测试夹具改为读取实际 editorArea 字段，保留所有行为断言；没有为测试改变生产布局。
- 最终定向 36 项全通过（14s，exit 0）：新历史流程 17 项，原脚本连接 10 项、文件生命周期 1 项、生成查询 8 项。
- 首次全量 2,394 项中 1 项失败、3 项既有跳过（1m42s，exit 1），没有打包：`SqlEditorPaneLifecycleTest.appShellUsesThePaneAsyncGuardInsteadOfAnFxBlockingFinalizer` 仍要求旧历史 lambda 直接位于 AppShell。更新为验证 AppShell → SqlHistoryTabs → 受管 openSqlTab 和文本载入调用，原异步关闭/资源绑定断言保留；真实历史文件/草稿行为另有集成测试。
- 加入上述生命周期类后 41 项定向通过（13s，exit 0），命令在下面基础上追加 `--tests '*SqlEditorPaneLifecycleTest'`。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*SqlHistoryTabsTest' --tests '*SqlFileConnectionTest' --tests '*SqlTabFileLifecycleTest' --tests '*TableSelectSqlTabsTest' --no-daemon --console=plain
```

## 全量与桌面

最终 `clean test jpackageImage` 通过（2m09s，exit 0）：2,394 项总计，2,391 通过、3 项既有 live 跳过、0 failures/errors。`jlink` 与 `jpackageImage` 实际执行，开发版本 `0.0.0`，不生成发布 tag。仍有既有 unchecked、JAVA_TOOL_OPTIONS 探测输出和 JEP493 提示，不声明无警告。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false -Djava.io.tmpdir=C:\Users\hetia\AppData\Local\Temp\DA65AC~1'
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

生产镜像入口仍为 `com.datacube.DataCubeFx`。`jimage list` 确认含 `SqlHistoryTabs`，不含 DesktopFixture、DraftConnectionProbe 或 DraftTestCipher。复制至独立 `build/history-connection-fixture-image/DataCube` 后，才加入 5 个测试 class 的 patch jar 并改副本入口；原镜像不改动。副本以强制独立的 `history-connection-desktop-profile` 运行，只注册 `example.invalid` 合成 PG/Oracle，所有 provider/session/metadata/network 调用由探针截获。

桌面技能用于实际截图与交互验证。首次启动被 Windows `GetCursorPos: 拒绝访问 (0x80070005)` 阻止，没有验收窗口；用户解锁后正常启动。已从真实历史对话框载入合成条目，确认标题标记历史、SQL/SALES 保留、初始未绑定且执行禁用；首次选择框无默认值、确定禁用，取消保持原状态。同名下拉候选显示类型及 `demo-pg` / `demo-oracle`。控制工具对下拉弹窗的焦点与候选索引不稳定，未将失败的自动化点击算作确认成功；后续桌面步骤另记。

### 桌面收尾补验

用户继续收尾后，重新取得同一隔离窗口，观察到目标选择已完成；没有重复点击执行或尝试连接数据库。首次截图与窗口树不一致，激活已确认的验收窗口并重新截图后再操作，不依据被遮挡的截图点击。

- 目标显示“待绑定: 演示连接”，提示“目标已选择，尚未连接”；执行入口可用，仍“尚未创建专用会话”。SQL（含中文/emoji）及 `SALES` 保留，标签无未保存星号。
- 点击“检查离线计数（验收）”刷新后，provider/session/metadata/network 均为 0；结束前再次刷新仍全为 0。
- 使用验收按钮切换到 640 逻辑宽度，暗色和亮色下历史连接按钮、目标状态与指导文字均可见；回到 1000 宽度后亮色布局正常。此结论只针对本轮入口和状态，不宣称所有底部说明都不截断。
- 亮色窄窗打开“更换脚本连接”，不选择新候选而直接取消；回到原标签后目标、SQL、Schema、文件干净状态保持。随后只读检查隔离的合成 `.draft`，仍含 `demo-oracle` 与 `ORACLE`。另选不同候选再取消的场景由上述自动测试覆盖，不冒称本次桌面已操作该分支。
- 点击验收窗口标题栏关闭，重新列出窗口和检查指定副本进程，均确认已退出。未关闭用户正常 DataCube 实例，未改真实连接或历史。

代码没有因补验而改变；复核生产差异及 `git diff --check` 通过。

## 交付范围

历史名称不用于查找或匹配连接；初始 schema 仍只是预填的执行上下文，用户执行前须检查，未新增自动 schema 请求。载入和选择不写历史，但正常执行/关闭仍按既有逻辑记录，不宣称关闭了历史功能。选择及编辑可形成草稿检查点；没有持久化格式变更。

实现提交 `736548f` 已在桌面补验后快进合并到本地 main。合并后 41 项定向回归全部通过（19s，exit 0；0 failures/errors/skipped），XML 分别为历史流程 17、文件连接 10、文件生命周期 1、生成查询 8、编辑器生命周期 5 项；本次仅追加验收文档，不改生产代码。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*SqlHistoryTabsTest' --tests '*SqlFileConnectionTest' --tests '*SqlTabFileLifecycleTest' --tests '*TableSelectSqlTabsTest' --tests '*SqlEditorPaneLifecycleTest' --no-daemon --console=plain
```

未推送或打 tag，用户 `.testagent/` 保持原样，未读取或加入提交。不以合成验收代替真实数据库/用户效率研究。
