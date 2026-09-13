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

首次桌面启动被 `GetCursorPos: 拒绝访问 (0x80070005)` 阻止；2026-09-13 用户解锁后，使用现有隔离副本完成以下 Computer Use 实测。

## 2026-09-13 桌面补验

- 暗色宽窗初始显示 `63 / 63`，搜索框聚焦时提示可见，没有默认候选，确定禁用。
- 输入 `demo-oracle` 后显示 `1 / 63`，同名连接通过 `ORACLE / demo-oracle` 区分；直接 Enter 不关闭弹窗，↓ 显式选中后 Enter 返回精确的 `demo-oracle / ORACLE`。
- 重新打开后搜索中文 `演示` 得到 2 条候选；显式选择 Oracle，再以 Ctrl+F 返回搜索并输入 `demo-needle`，原选择清除，唯一新候选没有被代选，确定重新禁用。
- 搜索 `no-match` 显示 `0 / 63` 及“没有匹配的连接，请修改或清除筛选”；清除筛选恢复 `63 / 63`，原选择没有复活。Esc 关闭后夹具显示“已取消；没有返回新目标”。
- 暗色、亮色的 480 宽窄窗均实测：搜索提示、计数、列表、键盘指引和确定/取消按钮可见，较长头部说明正常换行。
- 亮色窄窗空列表显示 `0 / 0`，提示先新建 PostgreSQL / Oracle 连接，确定禁用；Esc 正常关闭。
- 完成后通过标题栏正常退出夹具，重新枚举窗口及按完整可执行路径检查进程均为 0。

以上是生产选择器在合成夹具中的真实桌面交互，不是对真实数据库的连接或 SQL 执行验证。夹具没有会话服务；三个生产入口的 owner、只记录离线意图和迟到确认守卫仍以集成测试为证。

## 当前交付状态

2026-09-13 桌面补验及正常退出完成后，将实现提交 `7e48e1e` 从 `codex/connection-picker-search` 快进合并到本地 main。main 重跑上述命令成功（50s，exit 0），XML 汇总为 93 项通过、0 failures/errors/skipped：选择器 30、草稿管理 26、草稿恢复 10、文件连接 10、历史入口 17。工作树中的全量 XML 另行复核仍为 2,424 总计、2,421 通过、3 跳过，未用本次定向运行替代全量证据。

复核新增选择器用例及三个入口改动后，无新增待修代码问题；`git diff --check` 通过。独立工作树保留，用户 `.testagent/` 未读取、修改或加入提交；本地合并与验收记录完成，不推送、不打 tag。

## 边界

未更改连接持久化或 SQL 执行管线；搜索不检索私有连接字段，展示名称/ID 仍沿用 80 UTF-16 单元的有界预览。候选是打开时快照，失效检查仍由调用方确认后完成。不宣称用户效率数据、远端 CI、真实数据库或正式发布验收完成。本轮仅本地提交/合并，不推送或打 tag。
