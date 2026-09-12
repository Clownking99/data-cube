# 连接快速选择验收

基线 `fb08f8a`，分支 `codex/connection-picker-search`。[设计](../specs/2026-09-12-connection-picker-search.md)。仅修改共用选择器与新输入框局部主题规则，调用方的会话、风险确认、关闭和文件/草稿逻辑不改。

## 行为证据

测试技能用于单一选择器的状态矩阵及实际入口回归；按本机约定内联实施，不创建通用多代理流水线或覆盖率报告，不读取用户 `.testagent/`。

| 要求 | 证据（SqlConnectionPickerTest，除另注） |
| --- | --- |
| 共享入口有筛选框和可见列表 | `sharedChooserExposesSearchInsteadOfAnUnsearchableDropdown` |
| 配置快照、原顺序、排除 Redis/无效 ID/type、初始不选择、同名区分及不显示凭据 | `snapshotExcludesUnsupportedTargetsAndHasNoImplicitSelection` |
| 名称/类型/ID 字面匹配，单条结果也不代选 | `literalSearchFindsNameTypeOrIdWithoutChoosing`（6 组） |
| 不搜索地址、database、用户名、凭据或 props | `searchNeverUsesPrivateConnectionFields`（5 组） |
| Turkish Locale、空名称、完整 ID 超出显示预览、Unicode 空白 | `localeNullNameAndFullUntruncatedIdRemainSearchable` |
| 按候选保留而非索引，被排除后无回退，清除不复活隐藏选择 | `filterKeepsTheCandidateNotItsIndexAndNeverFallsBackOrResurrects` |
| 200 个同名连接定位最后 ID、恢复原顺序及选中身份 | `manyConnectionsCanFindLastIdWithoutChangingOrderOrTarget` |
| 空列表/无匹配区分，无选择不能确认 | `emptyAndNoMatchesHaveDifferentGuidanceAndCannotConfirm` |
| query/list/button 明确确认返回原对象 | `explicitConfirmationReturnsExactFilteredObject`（3 组） |
| query/list/button/标题栏关闭取消不返回候选 | `cancellationDiscardsEvenAnExplicitCandidate`（4 组） |
| ↓ 明确进入列表、Ctrl+F、组合键和双击不误确认 | `keyboardRequiresNavigationAndSupportsReturningToSearch` |
| 255/256/257 与 emoji UTF-16 边界，超限整次拒绝并保留先前选择 | `searchLimitRejectsWholeEditAndKeepsPriorCandidate` |
| 明暗 480/680 宽，入口/指导文字完整、聚焦提示不透明 | `themesAndNarrowLayoutKeepSearchAndGuidanceVisible`（4 组） |
| 文件入口筛选确认只改变离线意图，源文件与草稿/Schema 保留 | `SqlFileConnectionTest.confirmedChoiceAndAllPassiveEditorPathsStayOfflineAndLeaveTheFileClean` |
| 历史入口筛选确认保持离线、不改历史 | `SqlHistoryTabsTest.explicitChoiceUsesStableIdentityAndStaysOfflineWithoutChangingHistory`（PG/Oracle） |
| 恢复草稿筛选确认保留原 SQL/CRLF、稳定身份与离线状态 | `SqlDraftRecoveryTabsTest.explicitChooserIsUnselectedSafeAndChangesOnlyRecoveryIntent` |

三个入口的原业务断言保留，仅把 ChoiceDialog 的 ComboBox 控件定位更新为 ListView，并追加搜索断言。目标删除、类型变化、取消、迟到确认与首次准入锁定继续由原入口测试覆盖。选择器只接收不可变配置记录，不接收 ConnectionManager 或执行服务；真实入口回归使用离线探针，不连接真实数据库。

## 运行记录

- 原选择器红灯：缺少 `sql-connection-query`，1 项失败（21s，exit 1），没有将编译错误作为红灯。
- 第一轮实现后 92 项定向通过（22s，exit 0）；补充 200 连接场景及桌面夹具后，93 项定向通过（17s，exit 0；0 failures/errors/skipped）。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*SqlConnectionPickerTest' --tests '*SqlHistoryTabsTest' --tests '*SqlFileConnectionTest' --tests '*SqlDraftRecoveryTabsTest' --tests '*SqlDraftManagerTest' --no-daemon --console=plain
```

## 全量与镜像

`clean test jpackageImage` 成功（2m28s，exit 0）：2,424 项总计，2,421 通过、3 既有 live 跳过、0 failures/errors。`jlink` 和 `jpackageImage` 实际执行，开发版本 `0.0.0`，不代表正式发布。仍有既有 unchecked、JAVA_TOOL_OPTIONS 探测输出和 JEP493 提示，不声明无警告。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false -Djava.io.tmpdir=C:\Users\hetia\AppData\Local\Temp\DA65AC~1'
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

原生产镜像入口为 `com.datacube.DataCubeFx`，jimage 列表含新选择器、不含 DesktopFixture。复制至 `build/connection-picker-fixture-image/DataCube` 后才为副本添加单一测试 class 的 patch jar 并切换入口，强制独立 `connection-picker-desktop-profile`。夹具只构造 63 个合成配置与空列表，没有 ConnectionManager 或执行服务，不使用真实连接或凭据。为让桌面工具能直接定位键盘目标，夹具以无 owner 的真实生产 Dialog 验收；生产入口的 owner、被动意图与失效复核由已有集成测试覆盖，不混淆验证层次。

首次桌面启动被 `GetCursorPos: 拒绝访问 (0x80070005)` 阻止，已请用户解锁；真实交互和本地集成结果待追加。

## 当前交付状态

复核新增 30 项选择器用例的断言与三个入口改动后，无新增待修代码问题；`git diff --check` 通过。已完成代码先提交在独立分支，暂不合并 main；根仓库仍为 `fb08f8a`，用户 `.testagent/` 未读取、修改或加入提交。桌面夹具进程未启动，后续解锁后用现有隔离副本补验搜索、键盘确认、取消和明暗窄窗，正常关闭后再合并并跑 main 定向回归。

## 边界

未更改连接持久化或 SQL 执行管线；搜索不检索私有连接字段，展示名称/ID 仍沿用 80 UTF-16 单元的有界预览。候选是打开时快照，失效检查仍由调用方确认后完成。不宣称用户效率数据、远端 CI、真实数据库或正式发布验收完成。本轮仅本地提交/合并，不推送或打 tag。
