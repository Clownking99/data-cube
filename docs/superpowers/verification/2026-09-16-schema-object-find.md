# Schema 内查找表/视图验收

基线 main `afae456`，独立分支 `codex/schema-object-find`。设计见[Schema 表/视图检索](../specs/2026-09-15-schema-object-find.md)。不访问真实数据库、保存的连接、业务 SQL 或用户 `.testagent/`，不推送、不打 tag。

## 行为与证据

测试技能用于本增量的边界与生命周期回归，按项目约定不另建测试流水线工件。下列测试使用合成对象、JDBC 代理或隔离临时目录，不能代替真实数据库兼容性测试。

| 需求 | 直接证据 |
| --- | --- |
| Schema 菜单存在，渲染不读数据库，确认只调用原 SELECT 草稿入口 | `SchemaObjectFindEntryTest.schemaMenuOffersSearchWithoutReadingMetadataOnRender`、`confirmationUsesClickedSchemaAndExactRefWithoutChangingTreeSelection`（PG/Oracle） |
| 节点移除/替换、连接变化、根替换、关闭前后不接受旧操作 | `staleMenuAndChangedSourceDuringPickerCannotGenerate`（10 种）、`ineligibleSourceOrPickerResultCannotGenerate`（5 种） |
| PG/Oracle 仅取名称/类型，Schema 绑定、限额、超时、资源关闭 | `SchemaObjectNamesTest.namesUseBoundSchemaTimeoutAndRowCapWithoutDefinitions`、`unknownTypesAndInterruptedReadsNeverReturnPartialResults`、`invalidScopeAndLimitsDoNotPrepareSql`（各 2 种） |
| 旧提供者明确不支持，不回退到无界定义读取 | `legacyProviderDefaultDoesNotFallBackToUnboundedDefinitionReads` |
| 原连接快照、独立连接，不占用或关闭共享连接 | `SchemaObjectCatalogTest.immutableTargetUsesAndClosesDedicatedConnectionWithoutTouchingSharedConnection`、`catalogLoadLeavesExistingSharedConnectionAlive` |
| 失败/取消回收，空列表/精确 10,000/10,001 边界、去重及坏身份拒绝 | `failureAndCancellationCloseOnlyAcquiredResources`（3 种）、`exactCapacityEmptyAndDeduplicationKeepOnlyObjectIdentity`、`malformedSnapshotsFailAsAWhole`（5 种）、`invalidTargetsNeverResolveOrOpenAProvider` |
| 加载期间可输入，本地字面筛选，不自动选择或追加请求 | `SchemaObjectSearchDialogTest.loadingFiltersTypedQueryWithoutAnotherRequestOrImplicitSelection`、`filteringPreservesExactTypeAndClearsExcludedSelectionInsteadOfReplacingIt` |
| 重读清除候选、旧成功/失败不覆盖新结果，关闭/来源变化/禁用封住确认 | `reloadClearsOldCandidatesAndLateSuccessOrFailureCannotOverwriteNewSnapshot`、`staleCandidateCannotConfirmOrPublish`（3 种）、`closingBeforeQueuedWorkRunsCancelsWithoutCallingLoader` |
| 失败重试、提交拒绝恢复、空与无匹配区别、256/257 及 200/201 边界 | `failuresAreSafeAndRetryCanRecoverWithoutPartialResults`（2 种）、`rejectedSubmissionIsNotStuckLoadingAndCanRetry`、`emptySchemaAndNoMatchHaveDistinctFeedback`、`queryBoundaryAndCandidateCapAreExplicitWithoutLosingTheSnapshot` |
| 预览 Enter 不确认、Ctrl+F/Esc、480 窄窗明暗主题和提示 | `previewEnterDoesNotConfirmAndKeyboardCancelReturnsNoObject`、`narrowThemesKeepTargetSearchPreviewAndConfirmationVisible`（2 种） |
| 真 Dialog 显示启动真 runner 后台读取；关闭中断本作用域，runner 继续可用 | `SchemaObjectSearchLifecycleTest.actualShownDialogLoadsOffFxAndClosingCancelsOnlyItsOwnScope`（完成/取消） |
| 原生成 SELECT 的离线目标、引用及未保存规则不回退 | 既有 `ConnectionTreeSelectSqlTest`、`TableSelectSqlTabsTest` 联跑 |

## 自动验证过程

设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，使用仓库 Gradle wrapper，均加 `--no-daemon --console=plain`。

1. 入口 RED：29 秒 exit 1，缺 Schema 右键查找入口；接入后入口及既有 SELECT 测试 18 秒 exit 0。
2. PG/Oracle 名称读取与服务层定向验证 10 秒 exit 0。
3. 扩展联跑暴露测试夹具错误：重复将 DialogPane 设为新 Scene 根、JDBC 代理遗漏 isClosed。修正夹具，保留行为断言；70 项联跑剩 1 项超长输入提示失败。
4. 超长词被拒绝但匹配计数覆盖提示：生产代码保留拒绝状态，下一次合法内容输入清除；追加真实 Dialog/runner 生命周期测试，定向联跑 55 秒 exit 0。随后补充提交拒绝的恢复断言及关闭时 volatile 快照读取。
5. 首次全量命令在会话继续期间失去进程，未取得完整产物/完成证据，不计成功；确认没有残留构建进程后重新执行。

6. 最终全量 `clean :buildSrc:test test jpackageImage -PappVersion=0.0.0`：2 分 31 秒 exit 0。XML 汇总 2,710 项，2,707 通过、3 项既有 live 跳过、0 失败/错误；buildSrc 8 项通过（最终命令 up-to-date，已有成功报告）。净新增 54 项直接回归。既有 unchecked、JAVA_TOOL_OPTIONS 工具链探测和 JEP 493 提示仍存在，不宣称无警告。

定向复核命令：

```text
test --tests com.datacube.fx.SchemaObject*Test
     --tests com.datacube.provider.SchemaObjectNamesTest
     --tests com.datacube.service.SchemaObjectCatalogTest
     --tests com.datacube.fx.ConnectionTreeSelectSqlTest
     --tests com.datacube.fx.TableSelectSqlTabsTest
     --no-daemon --console=plain
```

## 桌面与本地集成

computer-use 操作最终生产镜像的独立副本 `build/schema-find-desktop-image/DataCube`，只对副本入口和参数进行 fixture patch。`SchemaObjectFindDesktopFixture` 使用真实查找 Dialog 与 FxTaskRunner，名称由常量和可释放等待控制，显式使用隔离 `build/schema-object-find-desktop-profile`；不创建数据库/执行服务。为直接验收原生键盘使用无 owner 的独立 Dialog，不把它称为 AppShell 右键到真实数据库的全链路实测。

- 暗色加载中输入 `REPORT`，释放合成读取后显示 1/4 个视图匹配，没有默认选中，确认禁用。Down 明确选择后预览显示完整 Schema、名称和类型。
- 暗色及亮色 480 窄窗中目标、输入、清除、候选、预览、重新读取、说明与确认/取消均可见；聚焦输入提示可读。
- 预览获得焦点后 Enter 不确认；Ctrl+F 返回查询并选中原词，输入 `missing_marker` 后显示 0/4 和无匹配说明，清空旧选择与预览，禁用确认。Esc 取消，夹具结果窗口明确未返回对象。
- 重新打开后释放一次合成 SQLException：只显示检查连接/权限的安全提示，不展示原诊断；点击“重新读取”恢复 4/4 候选，不默认选中。
- 鼠标选中 `Order "Quoted" 中😀`，预览完整身份；原生 Enter 返回完全相同的 `Exact Schema` 和名称。夹具只展示 TableRef 返回值，生成 SQL 的生产接入和不执行约束由自动测试验证，未执行任何业务 SQL。
- JavaFX 的辅助功能 focused_element/selected_text 存在陈旧文本，输入焦点以新截图中的实际光标/高亮与结果核对；未凭陈旧字段输入到其他应用。
- 正常退出后精确 exe 路径进程数为 0。隔离 profile 仅含主题设置和 JavaFX 自动解压的 `.openjfx` 原生库缓存，没有连接或 SQL 文件。

生产入口仍为 `com.datacube/com.datacube.DataCubeFx`，没有 fixture patch/profile 参数；runtime 含新对话框和服务，不含 DesktopFixture 或 DraftConnectionProbe。modules SHA-256：`BCC5FFB186543825A4D603E8208F691F87C9ACFB2DD5534DB911709A66E5176F`。开发版本 0.0.0，不代表正式发布、安装升级、真实数据库、用户效率实验或远端 CI 验收。

## 本地 main 集成

实现提交 `f2f7df6` 已快进本地 main；合并前核对 main 仍为 `afae456` 且跟踪文件/暂存区干净，保留用户 `.testagent/`，未读取、修改或暂存。未推送、未打 tag。

合并后在根目录运行上方定向命令，追加 `jpackageImage -PappVersion=0.0.0`：43 秒 exit 0，73 项全部通过、0 失败/错误/跳过。全量 2,710 项报告保留在独立 worktree，main 的报告是这次定向复核，不混为全量。

main 开发镜像：`build/jpackage/DataCube/DataCube.exe`。生产入口与无测试夹具检查再次通过；modules SHA-256：`B5DB2F23EFFF51390ACAF4095F5CA17882860A753276EC68EE7C0287A1CF2FD4`。两个 runtime 的 com.datacube 模块均提取 811 项，唯一字节差异为 theme-base.css 的 main CRLF/worktree LF；换行规范化后相同，不声称镜像逐字节一致。
