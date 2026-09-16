# SQL 草稿摘要筛选验收

基线 `04a9600`，分支 `codex/sql-draft-filter`；[设计](../specs/2026-09-16-sql-draft-filter.md)。测试技能用于管理 Pane 的直接行为回归，不新增通用测试流水线工件或子代理；保留 `.testagent/`，不读取或修改真实草稿、保存连接、业务 SQL 或系统剪贴板。

## 自动验证范围

新增 `SqlDraftFilterTest` 使用真实 `SqlDraftCoordinator` 和既有内存 `DraftManagementProbe`，不创建生产草稿存储。

| 行为 | 直接测试 |
| --- | --- |
| 入口及零存储/恢复副作用 | `filterEntryExistsWithoutRestoringOrWriting` |
| 连接、Schema、SQL 摘要、大小写、字面符号、未绑定及空草稿 | `searchesDisplayedFieldsLiterallyWithoutStorageWork`（6 种） |
| 80 / 120 UTF-16 边界，不搜索截断尾部 | `onlySummaryPrefixIsSearchable`（6 种） |
| 不搜索 ID、隐藏连接名；无匹配与真正空快照区分 | `hiddenMetadataAndIdentifiersAreNotSearchableAndNoMatchDiffersFromEmpty` |
| 保留同一选择及预览选区；排除后不代选 | `retainedSelectionKeepsPreviewRangeAndExcludedSelectionCannotRestoreAnotherDraft` |
| 刷新按 UUID 保留最新正文，不按同名替换 | `refreshRetainsUuidWithLatestTextButDoesNotReplaceExcludedRecordByMatchingName` |
| 初始化/忙碌期间最新筛选与恢复准入 | `initializingAndPendingRefreshUseLatestFilterWithoutRestoreAdmission` |
| 存储不可用仍可筛选、恢复已读内容 | `unavailableStorageStillFiltersAndRestoresReadSnapshot` |
| 关闭、禁用后拒绝输入及恢复 | `closedOrDisabledPaneRejectsFilterAndKeyboardActions`（2 种） |
| 256 / 257 整体拒绝、提示及清除恢复 | `queryLimitRejectsWholeEditAndClearRestoresSnapshot` |
| Ctrl+F、↓、显式 Enter；预览与修饰 Enter 不恢复 | `keyboardRequiresExplicitSelectionAndPreviewEnterNeverRestores` |
| 删除所选只作用于 UUID | `deleteWithFilterOnlyRemovesExplicitUuid` |
| 清空全部披露隐藏记录且默认取消 | `clearAllDisclosesHiddenRecordsAndKeepsCancelDefault`（取消/确认） |
| 部分清理失败提示不被筛选覆盖 | `partialFailureNoticeSurvivesLocalFiltering` |
| 明暗 680×600 范围提示与控件边界 | `narrowThemesKeepFocusedSearchPromptAndScopeReadable`（2 主题） |

所有 Gradle 命令使用 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`、`--no-daemon --console=plain`。

- 入口 RED：单个入口用例 28 秒 exit 1，因缺少筛选控件断言失败，不是编译错误。
- 实现后定向 `SqlDraftFilterTest`、`SqlDraftManagerTest`、`SqlDraftUiTest`，22 秒 exit 0。
- 首轮 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`，3 分 21 秒 exit 0；XML 2,785 项，2,782 通过、3 既有 live 跳过，0 failures/errors；新增筛选 28 项通过，buildSrc 8 项通过。
- 桌面发现空输入框聚焦后提示消失。原离屏 Scene 测试没有取得原生窗口焦点，已加强为显式 focused CSS 状态；两个主题均在 20 秒定向回归中 RED。仅将新控件加入现有 `theme-base.css` 有界提示色选择器，不改变通用输入框样式。
- 修正后 `:buildSrc:test test jpackageImage -PappVersion=0.0.0`，3 分 7 秒 exit 0。最终 XML 仍为 2,782 通过、3 既有 live 跳过、0 failures/errors；28 项筛选回归全部通过，buildSrc 8 项通过（本次 up-to-date，首次 clean 轮已实际运行）。既有 unchecked、JAVA_TOOL_OPTIONS/toolchain、JEP 493 提示仍在，不宣称构建无警告。

## 合成桌面

- `SqlDraftFilterDesktopFixture` 以真实 Pane / 协调器和三份内存合成草稿运行；测试 class 只注入开发镜像副本，正式镜像不注入。没有真实存储、连接服务或剪贴板依赖。
- 首轮窗口截图出现空白，重激活无效，切换主题后重新绘制；未把空白观察算作视觉通过。随后观察到 3/3、无默认选择，输入 `salary` 为 1/3 且恢复禁用。Enter 不恢复；↓ 明确选择 Payroll 后 Enter 使内存恢复计数为 1。
- 点击只读预览后 Enter 不增加恢复计数。Ctrl+F 全选筛选词，输入 `invoice` 后保留 PG 候选、清空 Payroll 选择和正文，恢复/删除所选禁用，不自动恢复 PG。
- 筛选 1/3 时打开“清空全部草稿”，可见包含筛选隐藏记录的完整说明，取消按钮为默认强调。通过可见取消按钮关闭，仍为 1/3、总数 3，未确认删除、未切换保护设置。原生自动化的 UIA 索引和焦点字段有迟滞，采用重新截图与可见坐标复核；未将未送达的 Esc 当作取消通过。
- 主窗口 Esc 正常退出，精确副本 exe 路径的进程已不存在。
- 最终镜像重新启动时可正常绘制；在 680 宽窄窗实测明暗两种主题，空查询获得焦点后提示仍可见，计数、两行范围说明、隐私提示和全部操作按钮可见。输入 `not_present` 得到 0/3 与“没有匹配”提示；清除筛选恢复 3/3，恢复/删除所选仍禁用，没有自动选择。
- 最终窗口通过可见“关闭”按钮退出；筛选框聚焦时 Esc 未关闭窗口，不宣称该焦点下 Esc 取消已验证。本轮没有重新定义外层 Dialog / 文本框的 Esc 行为。隔离 profile 顶层仅 `.openjfx/` 与 `settings.properties`。
- 最终生产镜像保持 DataCubeFx 入口、`0.0.0` 开发版本和原 JVM 参数，无 user.home / patch 注入。运行时含新增 `SqlDraftManagerPane$SearchEntry`，不含桌面夹具或内存探针；`runtime/lib/modules` SHA-256 为 `445442691D51F9AC43565BCE9D902A4F600E58CAE71647CC8ACB4B4729234D97`。

## 验证边界

合成桌面夹具只实例化真实管理 Pane / 协调器及内存后端，恢复回调仅记录内存，不代表完整 AppShell 创建标签验证。隔离 `user.home` 仅用于主题设置与 JavaFX 运行文件。桌面不确认删除/清空，不改变草稿保护开关；删除范围由内存自动测试验证。

本增量不涉及全文 SQL 搜索、真实数据库兼容性、用户效率实验、远端 CI 或正式发布。

## 本地 main 集成

- 功能提交 `7df3879`，8 个文件；暂存差异检查通过。合并前确认 main 仍为 `04a9600` 且已跟踪文件干净，使用 `git merge --ff-only codex/sql-draft-filter` 快进。用户 `.testagent/` 保留且未入库。
- main 执行 `test`，指定 `SqlDraftFilterTest`、`SqlDraftManagerTest`、`SqlDraftUiTest`、`SqlDraftRecoveryTabsTest`、`SqlDraftFailureFeedbackTest`、`SqlWorkspaceManagerTest`，并构建 `jpackageImage -PappVersion=0.0.0`；1 分 59 秒 exit 0。XML 共 117 项全部通过、0 failures/errors/skipped。
- main / 最终 worktree 镜像分别提取 `com.datacube`，均为 814 个文件，无新增或缺失。813 个逐字节 SHA-256 一致；唯一差异为 `theme-base.css` 的 Git 工作区 LF / CRLF 换行，统一换行后文本完全一致，包括新提示色选择器。未宣称整个 modules 文件逐字节相同。
- main 镜像保持 DataCubeFx 入口与原 JVM 参数，没有测试夹具、内存探针、profile 或 patch 注入。`runtime/lib/modules` SHA-256：`A32404E896211D75B69FD80D893271BED66E45C8FA8AFC291AA941C849AB9630`。
- 仅本地提交与合并，未推送、打 tag 或发布；`0.0.0` 仅作开发镜像验证。
