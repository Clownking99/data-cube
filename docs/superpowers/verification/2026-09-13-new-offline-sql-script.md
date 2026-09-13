# 新建离线 SQL 脚本验收

基线 `6ee55aa`，分支 `codex/sql-new-script`，见[设计与实施清单](../specs/2026-09-13-new-offline-sql-script.md)。本轮仅增加 SQL 文件菜单入口和共享安装适配，不改 SqlEditorPane 的执行、保存或关闭实现。

## 行为证据

测试技能用于菜单与新脚本的状态矩阵，按本机约定直接实施，不启用通用多代理流程或新增 `.testagent/` 报告。

| 要求 | 自动化证据 |
| --- | --- |
| 菜单首项提供无需文件的新建入口 | `SqlScriptFileEntryTest.sqlFilesMenuOffersNewOfflineScriptBeforeOpen` |
| 新建回调不打开文件；清除最近索引重建后仍可新建，原打开/最近回调保留 | `SqlScriptFileEntryTest.newScriptCallbackSurvivesRecentMenuRebuildWithoutOpeningFiles` |
| 无连接及 PG/Oracle/Redis 活动上下文下均为空白、空 Schema、干净无路径、无隐式目标；安装选择器/文件控制器先于草稿绑定 | `SqlNewScriptTabsTest.newScriptIsBlankCleanAndNeverUsesTheAmbientConnection`（4 组） |
| PG/Oracle 搜索确认与取消更换保持文本/Schema/未保存状态、原目标及零数据库调用 | `SqlNewScriptTabsTest.explicitSelectionAndCancelledReplacementKeepTheNewScriptOffline`（2 组） |
| 多个新脚本独立，原文本和草稿身份保留，空白标签不生成非空草稿 | `SqlNewScriptTabsTest.anotherNewScriptKeepsExistingEditsAndTheirDraftIdentity` |
| 取消未保存关闭保留标签和文本，仍能编辑 | `SqlNewScriptTabsTest.cancelCloseKeepsUnsavedTextAndAllowsFurtherEditing` |
| 工作区关闭后不创建或绑定编辑器 | `SqlNewScriptTabsTest.closedWorkspaceRejectsNewScriptBeforeConstructingOrBindingAnEditor` |
| 首次保存走另存为，后续保存和另存为精确发布及改绑 | 既有 `SqlScriptFileControllerTest.firstSaveNormalSaveAndSaveAsPublishExactSnapshotsAndRebind` |
| 保存关闭只在成功后继续；取消关闭不进入后续资源关闭 | 既有 `SqlScriptFileControllerTest.saveCloseProceedsOnlyAfterSuccessfulStillCurrentBaseline`、`cleanDiscardAndCancelCloseInvokeExistingGuardAtMostOnce` |

使用 `DraftConnectionProbe` 拒绝网络和元数据路径，并在后台队列屏障后断言 provider/session/metadata/network 均为 0。没有因合成地址或测试名称而访问真实数据库。既有 `SqlFileConnectionTest` 和 `SqlTabFileLifecycleTest` 一起回归，覆盖共用准入/失效复核和文件/草稿绑定；不声称本轮修改了这些实现。

## 运行记录

- 原菜单红灯：期望首项 `sql-file-new`，实际为 `sql-file-open`，1 项失败，26s，exit 1；不是编译失败冒充红灯。
- 实现后修正测试适配中的关闭结果类型引用，以及自定义取消按钮定位；未放宽业务断言。
- 定向命令通过：51 项、0 failures/errors/skipped，19s，exit 0。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew.bat test --tests '*SqlNewScriptTabsTest' --tests '*SqlScriptFileEntryTest' --tests '*SqlFileConnectionTest' --tests '*SqlTabFileLifecycleTest' --tests '*SqlScriptFileControllerTest' --no-daemon --console=plain
```

## 完整测试与开发镜像

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false -Djava.io.tmpdir=C:\Users\hetia\AppData\Local\Temp\DA65AC~1'
.\gradlew.bat clean test jpackageImage '-PappVersion=0.0.0' --no-daemon --console=plain
```

成功，2m47s，exit 0。完整 XML 汇总 2,435 项：2,432 通过、3 既有 live 跳过、0 failures/errors，包含最终补充的另存为默认文件名断言。`jlink` 和 `jpackageImage` 实际执行；仍有既有 unchecked、JAVA_TOOL_OPTIONS 探测输出和 JEP493 提示，不声明无警告。`0.0.0` 仅为开发镜像版本，不是正式发布。

生产镜像入口仍是 `com.datacube.DataCubeFx`，jimage 列表包含 `SqlNewScriptTabs`，不包含 DesktopFixture。另行复制至 `build/new-script-fixture-image/DataCube` 后，仅对副本添加测试 class patch jar 并替换入口；隔离 `user.home` 强制为 `new-script-desktop-profile`。夹具组合真实菜单构建器、新建入口和 managed SQL Pane，使用合成 PG/Oracle 配置及拒绝数据库调用的探针，不读取用户配置或历史；未用它冒充完整 AppShell 的启动/更新/退出验收。

## 合成桌面实测

- 暗色窗口的“SQL 文件”菜单首项为“新建 SQL 脚本（离线）”；点击后空白编辑器、空 Schema、无文件未保存标记，显示未绑定连接、执行禁用。
- 在编辑区输入 `select 'offline draft';`，标签出现 `*`，草稿保护随后显示已保存检查点；尚未选择目标时仍不能执行。
- 点击“保存 SQL”进入原生“保存 SQL 文件”窗口，默认文件名为 `query.sql`。Esc 取消后文本及 `*` 保留，未创建 SQL 文件。原生弹窗元素索引无法用于取消，重新观察后使用实际 Esc 操作完成，未修改产品来规避工具限制。
- 点击标签关闭，出现“保存并关闭 / 不保存 / 取消”提示；选择取消后标签、正文及操作入口保留。
- 通过真实脚本连接弹窗点击 Oracle 候选并明确确定，页面显示“目标已选择，尚未连接”。刷新探针后 provider/session/metadata/network 均为 0，没有执行 SQL 或事务操作。
- 再从菜单新建第二个脚本：新标签为空白、未绑定且执行禁用；切回第一标签，原 SQL、未保存标记及已选目标仍在。
- 640 宽的暗色/亮色窗口中，脚本保存、选择连接入口及编辑正文可见；亮色窄窗的新建菜单文字完整。这里只验本轮入口，不宣称所有既有长提示在窄窗下无截断。
- 通过标题栏正常退出夹具，窗口重新枚举为空，按完整可执行路径检查残留进程数为 0。

## 本地集成

实现与测试差异已复核，`git diff --check` 通过。功能先提交到独立分支，本地 main 快进及合并后定向结果待追加。根仓库用户 `.testagent/` 未读取、修改或暂存。

未新增持久化格式、数据库行为、依赖或网络请求。未进行真实数据库、远端 CI、安装升级或用户效率测量；不推送、不打 tag。
