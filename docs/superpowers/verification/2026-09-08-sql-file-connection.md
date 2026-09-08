# SQL 文件选择连接验收

## 基线及实现边界

基线 `846618a` 的文件入口使用独立空 SessionContext，但缺少显式选择执行目标的入口，
普通编辑器“在左侧选择连接”的提示在文件标签中不适用。新增
`openedFileHasAnExplicitOfflineConnectionEntry` 通过真实 AppShell 文件安装路径复现缺少按钮，
先失败后实现。开发中修正一处 AppShell 缺失 List import；没有跳过或放宽失败断言。

新文件构造入口在构建编辑器前启用已有凭据无关的被动连接意图和离线门禁。
真实 AppShell 在文件安装事务中提供当前保存配置快照和选择入口，不改动全局 SessionContext。
选择框复用现有草稿组件，增加脚本标题和空列表提示；普通 SQL 与草稿恢复保持各自原有入口。

## 行为证据

| 行为 | `SqlFileConnectionTest` 中的证据 |
| --- | --- |
| 真实文件入口可见且默认离线 | `openedFileHasAnExplicitOfflineConnectionEntry` |
| 确认正确目标、名称冲突可区分、Redis/凭据不进入显示、编辑器被动路径零数据库访问、文件保持 clean | `confirmedChoiceAndAllPassiveEditorPathsStayOfflineAndLeaveTheFileClean` |
| 无默认目标、空列表、取消保留 Schema/正文/原目标，准入前可显式重选 | `emptyChoiceAndCancellationNeverSelectOrChangeTheExistingTarget` |
| 删除/改类型后拒绝旧选择及执行，不按同名或上下文连接替代 | `deletedOrTypeChangedIntentCannotFallBackToSameNameOrGlobalConnection`（两种情况） |
| 选择后删除目标再切换事务模式，提示回到脚本选择入口，保持离线 | `removedTargetDuringTransactionModeSelectionPointsBackToScriptPicker` |
| 同 ID/类型采用最新配置和安全策略，准入后锁定，专用会话使用该快照 | `freshMatchingConfigIsUsedAtAdmissionAndThePinnedTargetCannotBeSwitched` |
| 模态框已打开但编辑器关闭后的确认无效 | `closedEditorRejectsAChoiceAlreadyOpenInTheDialog` |
| null、未注册、Redis 选择拒绝 | `invalidAndRedisChoicesAreRejectedWithoutCreatingAnIntent` |
| 选择意图沿用原草稿检查点，目标删除后仍保留稳定身份和原 Schema；SQL 文件不改变 | `selectedIdentitySurvivesInDraftCheckpointWithoutAddingFilePathsOrCredentials` |

定向命令通过（最终补充事务提示回归后 `BUILD SUCCESSFUL in 13s`）：

```powershell
.\gradlew.bat test --tests com.datacube.fx.SqlFileConnectionTest --tests com.datacube.fx.SqlEditorDraftRecoveryTest --tests com.datacube.fx.SqlScriptFileEntryTest --tests com.datacube.fx.SqlEditorSessionContractTest --tests com.datacube.fx.SqlEditorConnectionAdmissionTest --tests com.datacube.fx.SqlTabFileLifecycleTest --no-daemon --console=plain
```

`DraftConnectionProbe` 拦截网络/SQL 路径；被动操作后等待元数据队列屏障，并断言 provider、session、
metadata、network 计数全部为零。准入/专用会话测试只创建探针会话，不执行真实 SQL。
本轮没有修改草稿/工作区记录类型，也不向 SQL 文件写入连接信息。
复核新增事务目标失效的真实控件事件测试，先在旧“左侧选择”提示上失败，再修改三个准入失败出口
按文件/草稿/普通标签提示正确入口；没有放宽断言。

## 全量及桌面

第一轮 `clean test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain`
通过（1m 58s，1,772 项，0 失败、0 错误、3 跳过）。事务提示补充后的最终全量结果见下。

使用第一轮构建的 0.0.0 桌面镜像，配置独立 user.home，准备虚构 PostgreSQL、Oracle、Redis
配置（synthetic.invalid，无凭据）和只读验收用 SQL 文件，没有展开连接树、执行 SQL 或触发事务操作。
首次系统返回 GetCursorPos 拒绝访问后停止桌面输入，用户确认解锁后恢复。

- Ctrl+O 打开含中文及空格文件名的合成脚本；正文正确，默认未绑定，执行及事务模式禁用。
- “选择脚本连接”入口和离线提示可见；模态框无默认值，确定按钮禁用。
- 展开列表只显示 PostgreSQL / Oracle 的名称、类型及稳定 ID，Redis 未列入。
- 取消回到未绑定状态，SQL 正文和干净文件标题保留。
- 浅色主题与约 480px 宽编辑区域中，按钮可见、提示换行、不覆盖 SQL；正常退出实例。
- 本次桌面自动化不能可靠确认下拉项：工具将焦点转回主窗口导致弹层关闭，选择未落入控件。
  因此不声称实际窗口中的“确认目标后展示环境/只读、更换后取消保留原目标”已验收；这些行为由
  上述真实 JavaFX ChoiceDialog 事件测试覆盖，后续仍可补实际窗口确认。

验收前后 `脚本连接 验收.sql` SHA-256 相同：
`ED0EB67F93C92387E0F2B6F56B1A335B4339CEC4BB8E32B060EA835FB964CBF6`。
桌面配置与 SQL 均位于忽略的 build 下；最终 clean 重建未将它们纳入提交。

最终 `clean test jpackageImage -PappVersion=0.0.0 --no-daemon --console=plain` 通过：
`BUILD SUCCESSFUL in 2m 4s`，XML 汇总 1,773 项，0 失败、0 错误、3 跳过（1,770 通过）。
本轮新增 10 项执行；`git diff --check` 通过。
构建仅为开发验收镜像，不修改项目发布版本或生成 tag。

## 限制

- 只为显式打开的 SQL 文件提供新入口；普通新建标签仍遵循原有全局候选/准入绑定规则。
- 本轮不允许对已准入标签切换连接；需要其他目标时使用独立 SQL 标签。
- 不自动联网、迁移文件、执行脚本；不新增文件格式、凭据存储、数据库支持或发布版本。
- 真实数据库执行、安装器升级、用户试用耗时未在本轮验证。
