# SQL 文件重新加载验收

基线 main `8b689d3`，独立 `codex/sql-file-reload`。需求见[重新加载设计](../specs/2026-09-15-sql-file-reload.md)。
新增显式确认、同一文件控制器的后台读取、修订号/生命周期守卫和 Pane 按钮；不改变文件存储器及原有外部冲突规则，不新增依赖或网络。
按项目约定未读取、修改或暂存根目录 `.testagent/`；不使用通用测试流水线工件。本轮不推送、不打 tag。

## 行为证据

测试技能用于取消、失败、并发边界及状态不变量检查。

| 需求 | 证据 |
| --- | --- |
| 真实文件标签可用入口、无数据库调用 | `SqlFileReloadIntegrationTest.boundFileHasReloadEntryWithoutAnyDatabaseAccess` |
| 确认默认取消、明确丢弃撤销和未保存修改 | `confirmationIsExplicitAboutDiscardingAndDefaultsToCancel` |
| 磁盘新版本/精确换行、标题/干净基线、选择夹紧/撤销清空；不写源文件或最近列表 | `SqlScriptFileControllerTest.reloadAdoptsExternalTextAndBaselineButDoesNotWriteDiskOrRecentIndex` |
| 成功后保存用新基线，再次外改仍拒绝覆盖 | 同上，真实临时文件与原保存器 |
| 取消无读取，保留正文/选区/脏状态/撤销 | `reloadCancelPreservesDirtyTextSelectionUndoAndSubmitsNothing`、`actualButtonCancelPreservesTextAndUndo` |
| 缺失/非法 UTF-8/超限失败保留基线与撤销且可重试 | `reloadReadFailureKeepsWorkingTextBaselineAndUndoAndCanRetry`（3 种） |
| 空文件、UTF-8 BOM、短文件选择夹紧 | `reloadAcceptsEmptyAndUtf8BomFilesAndClampsSelection`（2 种） |
| 读取后编辑/编辑撤销/关闭/守卫变化拒绝迟到应用 | `lateReloadCannotReplaceNewerOrClosedEditor`（4 种，手动控制 worker 与 FX 回调） |
| 确认期间编辑拒绝读取、确认异常固定提示且释放 busy | `editingDuringConfirmationRejectsReloadBeforeReading`、`reloadConfirmationExceptionReleasesBusyWithoutLeakingDiagnostic` |
| 无文件/被阻止无确认；busy 拒绝重复保存/重读 | `unboundOrBlockedReloadDoesNotConfirmOrReadAndBusyDoesNotAdmitAnotherOperation` |
| 运行/关闭/任务域/禁用/准入/队列阻止旧入口 | `staleReloadButtonCannotConfirmOrChangeBlockedPane`（8 种） |
| 不改变已有结果列表、Schema、布局、连接探针 | `actualReloadChangesOnlySqlFileStateNotResultsSchemaLayoutOrNetwork` |
| 按钮在 480/640/880 × 明暗主题不裁切 | 既有 `SqlEditorUsabilityTest.primaryActionsRemainReadableAndInsideTheEditor` 扩展到 16 项动作 |

## 自动验证

环境 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，Gradle `--no-daemon --console=plain`。

1. 实际入口 RED：按钮不存在，1 项失败，23 秒 exit 1。
2. 接入后原保存控制器与入口测试通过，15 秒 exit 0。
3. 第一批新增用例 34 项中 4 项失败：测试安装初始文本的 undo 与下一次 edit 合并，undo 回到最初空编辑器。新测试先清除安装阶段 undo，再验证用户编辑撤销；不修改生产撤销语义来迁就夹具。
4. 联跑 reload/文件控制器/现有编辑器易用性：76 项通过，无失败/错误/跳过，14 秒 exit 0。
5. `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`：3 分 10 秒 exit 0。应用 2,612 项：2,609 通过、3 项既有 live 测试跳过，无失败/错误；buildSrc 8 项全过。本轮净新增 26 项用例（含后补的空文件/BOM 两种）。

现有 unchecked 编译提示、JAVA_TOOL_OPTIONS 工具链探测提示和 JEP 493 缺少 jmods 提示仍存在；最终任务均成功，不宣称无警告。未运行真实数据库、远端 CI 或安装器升级验证。

## 隔离桌面验收

采用 computer-use 技能操作打包程序副本，仅通过 `--patch-module` 装入测试夹具，独立 `build/sql-reload-desktop-profile`；创建合成文件，不读取业务文件或保存的连接，不启动 AppShell。夹具先载入 `VERSION_1`，再将磁盘更新为 `VERSION_2_DISK`。

- 暗色、980 宽窗口：实际「重新加载」按钮与保存/另存为并列；确认框完整提示丢弃未保存修改和撤销记录，「取消」默认高亮。点击取消后编辑器仍为 `VERSION_1`。
- 切换亮色、640 宽窗口：文件动作完整可见、工具栏正常换行。再次打开实际确认框，点击「从磁盘重新加载」后正文为 `VERSION_2_DISK`，标题无脏标记，状态明确显示撤销已清空、未执行 SQL。
- 验收全程无连接、执行按钮禁用、无结果；正常关闭后副本精确 exe 路径对应的进程数量为 0。源文件仍精确等于夹具写入的磁盘新版（含混合 CRLF/LF），重载未写源文件。
- 本轮桌面仅使用鼠标确认/取消，未验证原生 Enter/Esc 派发；默认按钮属性已由自动测试验证。脏文本取消、读取失败及并发状态保护由自动测试覆盖，不等同于这些路径均已手工复现。

生产镜像入口仍为 `com.datacube/com.datacube.DataCubeFx`，无测试 patch/profile 参数。运行时包含 `SqlFileReloadDialog`，不包含 `DesktopFixture` 或 `DraftConnectionProbe`。生产 modules SHA-256：`B8FE6D79FCE3B460F325A099AC4971E00A7405C8D7CD427ADB00AB4778A74923`。
