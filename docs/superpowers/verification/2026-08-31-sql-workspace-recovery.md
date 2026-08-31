# P2 SQL 工作区恢复验证记录

## 当前范围

最新正式入口续验：补全修复后的便携镜像已重新构建，真实DataCube.exe完成启动、跳过更新提示、恢复/重复恢复、正常退出及同profile重启。运行时确认使用合成user.home，未连接数据库；对话框完整键盘操作仍待人工确认，不因此标记P2全通过。见[正式入口验收](2026-08-31-workspace-formal-entry-acceptance.md)。下面未验收正式入口的描述为此前历史。

最新整合：维护者明确要求“合并并推送”后，main已快进到`3e793df`，重叠文件核实仅换行差异且原始字节已备份。合并后回归、授权边界和推送检查见[main整合记录](2026-08-31-sql-workspace-main-integration.md)。桌面仍未验收，不因合并而标记通过。以下“未合并/未推送”均保留为此前阶段历史，不代表最新整合状态。

后续核验：已确认远端提交`c8c53aa`的Verify四个作业全部成功。最新桌面续验实际完成恢复/重复恢复、两张标签顺序和选区、明暗主题、正常退出及同profile重启；两次复现的被动恢复补全浮层问题已在独立分支`a901811`修复，全量1564通过/3原有跳过、root独立7项及桌面复验通过。完整键盘及其他边界未冒充通过。见[最新桌面续验](2026-08-31-workspace-desktop-followup.md)；文末旧“桌面部分验收续跑”保留为历史。

P2.5代码侧验收已完成：并发回归`81fde83`、保存失败反馈/显式重试修复`e984c0c`均已审查，整分支无剩余Critical/Important。阶段全量1557通过/3既有live跳过/0失败，root独立74项、17个独立JVM矩阵及最新免安装镜像检查通过。真实桌面此前被控制通道故障阻塞；P2整体验收尚未完成，没有创建tag或发布。

P2.4本轮完成，源码`553e0621fb6735871480ce3dfa5c27e41aed09e0`，方案/计划基线`32360ea1aebb4c4bbc84b5d561191746246ce3a4`：[显式恢复界面设计](../specs/2026-08-31-sql-workspace-restore-ui-design.md)、[实施计划](../plans/2026-08-31-sql-workspace-restore-ui.md)。启动页入口打开同一草稿管理页，读取数量后由用户明确点击整组恢复；不启动就显示历史SQL。全量1539通过/3原有跳过，root独立107通过；任务审查Spec compliant/Approved，无Critical/Important，1项并发测试补强Minor列入P2.5。

P2.1基础模块、P2.2严格存储、P2.3a异步桥、P2.3b活动捕获/退出冻结及P2.4显式恢复入口均已完成各自任务验收。工作分支 `codex/sql-workspace-recovery`，起点 main `7710ecb526d10a22e3fbff65367c50b04e44ed9d`；P2.5尚未完成，main未合并本分支，无推送/tag/发布。

设计：[P2 工作区恢复](../specs/2026-08-31-sql-workspace-recovery-design.md)。已完成计划：[P2.1 基础模块](../plans/2026-08-31-sql-workspace-foundation.md)、[P2.2 共享锁持久化](../plans/2026-08-31-sql-workspace-persistence.md)、[P2.3a 异步存储桥](../plans/2026-08-31-sql-workspace-runtime-bridge.md)、[P2.3b 活动捕获/退出冻结](../plans/2026-08-31-sql-workspace-activity.md)、[P2.4 显式恢复界面](../plans/2026-08-31-sql-workspace-restore-ui.md)。恢复UI已实现并通过FX测试，不替代P2.5真实桌面/打包/跨进程与整分支验收。

P2.3a完成区间 `c3747e11fa8c54178851e561cd4b23e91536b1a6..2cb002d4de6103cfc07a690e82da8ef02ed486d2`，按[运行时设计](../specs/2026-08-31-sql-workspace-runtime-design.md)实施。本轮P2.3b完成区间`4c14aca620e1c673b618014d6a2d727436c76a93..2fa333c900a068036c9ef650bfc018ea6318a177`接入实际FX标签、变更合并与退出冻结，独立审查及修复后复审证据见下。主目录未提交SqlDraftStore改动仅核对状态，不读取或改动其内容；本轮仍只在独立worktree实施。

## 基线证据

P2.4实现前root基线：`./gradlew.bat test --no-daemon --console=plain`，JDK25.0.1+8、作用域内设置并恢复`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，session32637 terminal exit0/52秒，8 tasks中1executed/7up-to-date。root实际XML汇总158suites、1499total、1496passed、0failures/errors、3原有live skipped。main只核对name/status/HEAD：`7710ecb526d10a22e3fbff65367c50b04e44ed9d`，用户未提交SqlDraftStore和`.testagent/`名称存在，内容未访问。

P2.4真实RED起点：root读取XML `2026-08-31T10:33:16.739Z`，`SqlWorkspaceRecoveryTabsTest.orderedPartialRestoreClampsNewControlAndPreservesEditedReuseAndUnrelatedSlots` 1test/1failure/0errors/skips；未实现restore壳抛UOE，代理命令native exit1/14秒。root同时检查实际测试：临时store/writer保存三草稿及清单，已有B页修改正文/Schema/反向选择，穿插非SQL标签，并断言恢复计数、实际顺序、未覆盖B、A控件换行与夹取、重复无新工厂、离线四探针；不以单一状态断言冒充这些行为。确认后才允许GREEN。此项只证明实施前失败，不替代最终回归与审查。

- Worktree：`D:/Projects/朝花夕拾/.worktrees/sql-workspace-recovery`，基线源码 `7710ecb`。
- JDK：`D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8`。
- 命令：`./gradlew.bat test --no-daemon --console=plain`，仅该进程设置 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，退出后恢复原变量。
- session 65868：exit 0，46 秒，8 actionable tasks / 8 executed。
- 实际 `build/test-results/test/TEST-*.xml` 汇总：150 suites、1373 tests、1370 passed、0 failures、0 errors、3 skipped。
- 原有跳过：RedisLiveIntegrationTest 的 standaloneRedisSupportsFiveTypesScanTtlAndLifecycle；SchemaDiffLiveIntegrationTest 的 oracleSafeDeploymentConvergesInDisposableSchemas、postgresqlSafeDeploymentConvergesInDisposableSchemas。未启用真实数据库测试。
- 输出有原有 SqlEditorResultFilterContractTest unchecked 编译提示，以及 scoped JAVA_TOOL_OPTIONS 的 JVM 提示；不描述为无警告构建。

## P2.1 验证矩阵

首次 GREEN 尝试的实际 XML（2026-08-31T01:13:42Z）为 codec 26/0、recovery 6/1：`resolvesByIdInWorkspaceOrderWithoutChangingTextOrPositions` 第35行 `contains("select")` 错误匹配 record 的 `selectedDraftId` 字段名。源码 `Resolution` 使用 record 默认摘要，嵌套 SqlDraft 自身已隐藏正文/Schema/连接信息；该失败不能作为正文泄漏证据。计划/brief 将哨兵改为实际 SQL 片段 `select '😀'`，其余元数据断言保留；实现代理负责验证实际摘要、重跑和记录原始 RED，不为夹具错误修改生产代码。

| 要求 | 测试 | 当前证据 |
| --- | --- | --- |
| 精确格式、顺序、选中项、UUID 全位、时间 | SqlWorkspaceCodecTest.encodesExactBytesAndDecodesIndependentFixture；retainsEveryUuidBitAndMaximumTimestamp | GREEN，root复验 |
| 空工作区、非 SQL 选中、条目与位置边界 | preservesEmptyWorkspaceAndNoSelectedSqlTab；entryCountBoundaryIsEnforcedByValueAndDecoder；positionBoundariesApplyToAnchorAndCaret | GREEN，root复验 |
| 不可变值、身份校验 | rejectsNullsDuplicatesNegativeTimeAndForeignSelection；freezesCallerListAndDecodedList | GREEN，root复验 |
| 截断/超限/非法计数/选择/重复/未知版本 | rejectsEveryTruncationTrailingBytesAndNullPayload；rejectsInvalidCountBeforeAllocation；rejectsSelectionOutsideEntries；rejectsDuplicateWireIdsNegativeTimeAndInvalidMagic；distinguishesUnsupportedVersionsWithoutEchoingPayload | GREEN，root复验 |
| 严格 UUID 匹配，原始 SQL 与上下文保持 | SqlWorkspaceRecoveryTest.resolvesByIdInWorkspaceOrderWithoutChangingTextOrPositions | GREEN，root复验 |
| 缺失计数、选择回退、空状态 | missingSelectionFallsBackToFirstAvailableWithoutNameSubstitution；retainsNullSelectionWhenSelectedPageWasNotSql；allMissingAndEmptyWorkspacesProduceNoTabs | GREEN，root复验 |
| 解析输出隔离、输入失败诊断 | resultListsAreImmutableAndDetachedFromCallerList；rejectsInvalidCandidateSnapshotsWithFixedDiagnostics | GREEN，root复验 |

实现代理报告初次和夹具纠正后的骨架 RED 均为32项/24失败，详细命令/片段保存在本worktree `.superpowers/sdd/task-1-report.md`；root未独立运行这两次RED，不冒称有额外原始日志副本。root直接观察到了上述首次GREEN误报XML及后续32项全绿XML。

源码 `6b0bbe1b5d2ea9d5d8bb1f4a6917ee5274639647` 的最终证据：

- 实现代理全量命令：`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，仅该进程设 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`。报告exit0/48秒，root在后续定向复跑前直接汇总实际XML：152 suites、1405 tests、1402 passed、0 failures/errors、3 skipped；未新增实时集成环境。
- root独立定向复跑：`./gradlew.bat test --tests com.datacube.config.SqlWorkspaceCodecTest --tests com.datacube.config.SqlWorkspaceRecoveryTest --no-daemon --console=plain`，exit0/6秒，8 tasks中1 executed/7 up-to-date，`:test`实际执行。XML 2026-08-31T01:18:21Z：codec26/recovery6，32 passed、0 failures/errors/skips。
- root核对提交仅新增上述3个生产文件和2个测试文件，生产diff无文件/网络/FX/数据库服务调用；未改现有运行路径。工作区纯解析测试不代替未来UI“零数据库请求”的集成探针。

### 独立任务审查

`workspace_foundation_review`（terra）对冻结区间 `48faecc8a948e2521ef8145c6e7f535fc3bbabd2..6b0bbe1b5d2ea9d5d8bb1f4a6917ee5274639647` 的五文件diff、brief与报告完成审查：Spec compliant / Task quality Approved，0 Critical / 0 Important / 0 Minor。单独检查已有 SqlDraft 的摘要实现，确认输出record不会经它展开SQL/Schema/连接内容。

审查注明测试执行及工具版本不在五文件diff内；root已直接读取两次GREEN XML、独立执行定向复跑，并核对当前 `build.gradle` 与 wrapper/JDK（Java25/JavaFX25/JUnit5.11.3/Gradle9.2.0）。这是P2.1任务门槛，不是P2整分支合并批准；后续I/O、UI和退出流程仍按下列清单验收。

## P2.2 验证证据

冻结实现基线 `eaa8de0f7a746b175925294e562a9eccb965a3a3`，源码提交 `4611f54342621efa16de8232918c812d240ec91c`。新组件由 SqlDraftStore 持有，复用同一个目录、操作系统锁与 synchronized monitor；新增文件名仅 `workspace.bin`、`workspace-preferences.bin`。清空布局原子发布规范空清单，不删除 SQL 正文。详见[持久化设计](../specs/2026-08-31-sql-workspace-persistence-design.md)。测试、root复跑与独立任务审查通过。

本轮改动前基线：session4833，`./gradlew.bat test --no-daemon --console=plain`，scoped `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后恢复原值，exit0/32秒，8 tasks中1 executed/7 up-to-date。root实际XML：152 suites、1405 total、1402 passed、0 failures/errors、3原有live跳过。

| 要求 | 确切测试名 | 当前证据 |
| --- | --- | --- |
| 缺省读取无新文件、精确格式与重开 | SqlWorkspaceStoreTest.absentReadHasNoWorkspaceFileSideEffects；writesExactManifestAndReopensWithoutDuplicatingDraftData | GREEN，root复验 |
| 两个开关独立且保存准入严格 | ownDisablePersistsAndDoesNotDisableDraftProtection；draftSwitchAndInvalidDraftPreferencePreventNewWorkspaceWrites | GREEN，root复验 |
| 清空只清布局、禁用时可清、幂等 | clearPublishesCanonicalEmptyEvenWhenDisabledAndIsIdempotent | GREEN，root复验；原正文及两个偏好不变 |
| 损坏/未知/超限内容保护 | corruptUnknownAndOversizedManifestAreProtectedWithoutHidingDrafts；corruptPreferencesNeverDefaultOnAndMayNotBeOverwritten | GREEN，root复验；拒绝覆盖，邻近草稿仍可读 |
| 无效输入与已关闭owner | nullAndClosedOperationsNeverCreateOrChangeWorkspace | GREEN，root复验 |
| 保存/偏好/清空发布失败 | SqlWorkspaceStoreFaultTest.publicationFailuresPreserveOldFilesAndExposeExactStage | GREEN，root复验；3操作×WRITE/PUBLISH/CLEANUP共9种，旧文件字节、阶段、临时文件数量 |
| 身份与路径边界 | externalTargetChangeDuringWriteIsNotOverwritten；caseAliasIsNotTreatedAsMissingOrReplaced；directoryTargetIsNotFollowedOrOverwritten；symbolicLinkTargetCannotRedirectWorkspaceWrites | GREEN，root复验；本机两个符号链接用例均实际通过，0新增跳过 |
| 一个共享writer锁 | SqlWorkspaceStoreTest.sameJvmAndNewJvmShareDraftWriterLockAndReadAfterRelease | GREEN，root复验；同JVM/新JVM拒绝，释放后子进程重开读回 |

独立任务brief/report使用 `.superpowers/sdd/workspace-persistence-task-1-brief.md` 和 `workspace-persistence-task-1-report.md`，不覆盖P2.1报告。审查冻结基线至源码提交的整个diff，不以最后一个提交替代完整任务范围。

执行记录（均使用上述JDK）：

- 实现代理报告骨架RED：`./gradlew.bat test --tests com.datacube.config.SqlWorkspaceStoreTest --tests com.datacube.config.SqlWorkspaceStoreFaultTest --no-daemon --console=plain`，exit1、工具约10秒，25项中24失败。报告保留缺少持久化文件的NoSuchFileException片段；无独立原始日志，root未观察该RED，不另称独立复现。
- 实现代理同命令GREEN exit0/12秒；root直接看到XML25通过/0失败/0跳过。
- 实现代理相邻6套回归：`./gradlew.bat test --tests com.datacube.config.SqlDraftDirectoryTest --tests com.datacube.config.SqlDraftStoreTest --tests com.datacube.config.SqlWorkspaceStoreTest --tests com.datacube.config.SqlWorkspaceStoreFaultTest --tests com.datacube.config.SqlWorkspaceCodecTest --tests com.datacube.config.SqlWorkspaceRecoveryTest --no-daemon --console=plain`，报告exit0/14秒、85通过。
- 实现代理全量：`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，scoped `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 并恢复，报告exit0/42秒。root在后续定向复跑前实际汇总XML：154 suites、1430 total、1427 passed、0 failures/errors、3原有live跳过（名称同基线）；本机符号链接用例未跳过。仍有原有SqlEditorResultFilterContractTest unchecked编译提示，不声称无警告构建。
- root独立定向：`./gradlew.bat test --tests com.datacube.config.SqlWorkspaceStoreTest --tests com.datacube.config.SqlWorkspaceStoreFaultTest --no-daemon --console=plain`，exit0/7秒，8 tasks中1 executed/7 up-to-date。XML时间2026-08-31T01:44:09.310Z与01:44:09.952Z，fault16/store9，共25 passed、0 failures/errors/skips。

### P2.2 独立任务审查

`workspace_persistence_review`（terra）审查冻结区间 `eaa8de0..4611f54` 的五文件diff、brief与实现/控制器报告：Spec compliant / Task quality Approved，0 Critical / 0 Important / 0 Minor。

审查将历史RED/全量结果/原有编译提示标注为非diff可证事实。root保留上述证据来源区分，以实际全量XML和独立25项复跑确认最终通过，不冒称独立观察过RED。针对CodeGraph根main索引所提示的目录上限，root又直接核对本worktree `SqlDraftDirectory.read` 第102–124行及两个codec常量：新2424字节清单限制可通过既有更大的草稿上限，且该read实现未改。root另核对当前build/wrapper/JDK仍为Java25/JavaFX25/JUnit5.11.3/Gradle9.2.0。

这是P2.2任务门槛，不是P2整分支合并批准；下列队列、退出、恢复UI验收仍未完成。

### P2.3 接入契约与当前分层

- `Snapshot.recordingEnabled` 仅代表工作区偏好；不能直接作为整个运行时允许写入的标志，还要检查草稿保护及共享writer状态。
- P2.3a已将同步API接到共享writer，真实Path构造也复用唯一store；FX捕获与UI消费尚未接入。
- P2.3a已接通工作区CLEANUP/结构性失败的共享writer粘性停用，普通工作区损坏/独立禁用不误停草稿。未来UI仍须消费明确错误及全局mode，不能只看记录偏好。
- P2.3a已使清空/启停/删除前的旧排队快照失效。P2.3b仍需使尚未提交的内存候选失效，并确保重新开启不自动覆盖上一次工作区、界面保存状态等待实际结果。
- 一般关闭单标签应更新布局；退出要在binding脱离前冻结完整布局。取消或部分失败不得把不完整布局当作成功退出快照。
- 初次读取损坏/未知清单时仍可展示独立草稿；未开始新工作或明确清空时，启动初始化不能写空布局覆盖旧清单。

## P2.3a 验证范围

本轮改动前root全量：session50743，`./gradlew.bat test --no-daemon --console=plain`，上述JDK，scoped `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false` 后恢复，exit0/32秒；154 suites、1430 total、1427 passed、0 failures/errors、3原有live跳过。

以下验收映射对应源码 `2cb002d4de6103cfc07a690e82da8ef02ed486d2`，新增22个测试case均已通过，root独立复跑和独立任务审查通过：

RED已确认：实现代理报告 `./gradlew.bat test --tests com.datacube.config.SqlWorkspaceRuntimeTest --no-daemon --console=plain` 编译成功后exit1/13.149秒。root直接读取02:03:48.765Z XML：22项全部因 `UnsupportedOperationException: Workspace runtime not implemented` 失败，0 errors/skips；实际diff只新增编译骨架/default Backend入口及枚举，没有运行时行为。root确认后才授权GREEN实现。

通过证据（上述JDK）：

- 实现代理定向GREEN同命令，exit0/12.695秒，22 passed、0 failures/errors/skips；root也直接读取02:06:13.527Z通过XML。
- 相邻回归：`./gradlew.bat test --tests com.datacube.config.SqlDraftCoordinatorTest --tests com.datacube.config.SqlDraftWriteQueueTest --tests com.datacube.config.SqlWorkspaceRuntimeTest --tests com.datacube.config.SqlWorkspaceStoreTest --tests com.datacube.config.SqlWorkspaceStoreFaultTest --no-daemon --console=plain`，代理exit0/10.451秒；root在全量XML核对对应五套20+11+22+9+16共78通过。
- 全量：`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，代理scoped `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，exit0/49.685秒。root直接汇总实际XML：155 suites、1452 total、1449 passed、0 failures/errors、3 skipped，仍为Redis standalone与SchemaDiff Oracle/PostgreSQL三条原有live用例。原有SqlEditorResultFilterContractTest unchecked提示仍在，不描述为无警告。
- root独立复跑：`./gradlew.bat test --tests com.datacube.config.SqlWorkspaceRuntimeTest --tests com.datacube.config.SqlDraftCoordinatorTest --tests com.datacube.config.SqlDraftWriteQueueTest --no-daemon --console=plain`，exit0/9秒，8 tasks中1 executed/7 up-to-date，53 passed、0 failures/errors/skips。没有冒称第二次全量执行。
- `workspace-runtime-bridge-task-1-report.md`保留完整命令、RED片段、矩阵及来源区分；源码提交仅Coordinator和新测试，未改store/queue/FX。

| 要求 | 精确测试名（SqlWorkspaceRuntimeTest） |
| --- | --- |
| 未触及启动与关闭不写布局 | untouchedStartupReadAndShutdownNeverCreateLayoutFiles |
| 非UI写入、UI交付后才完成 | saveRunsOffUiAndOnlySettlesAfterDiskAndUiDelivery |
| 单保存背压、调用者取消隔离 | singleOutstandingSaveIsBoundedAndCallerCancellationDoesNotCancelPublication |
| 清空不复活旧布局且不丢待写草稿 | clearInvalidatesQueuedLayoutButPreservesQueuedDraft |
| workspace独立关闭与重开 | workspaceDisableCancelsQueuedLayoutWithoutDisablingDraftProtection |
| P1清空/删除/总开关失效，refresh不误取消 | draftManagementInvalidatesOldWorkspace；refreshDoesNotInvalidateAcceptedLayout |
| 管理失败也失效旧任务、保留旧文件 | failedManagementStillInvalidatesOldSaveAndRetainsOldFiles |
| workspace损坏不停止正常草稿 | workspaceCorruptionDoesNotStopDraftProtection |
| CLEANUP/P1偏好损坏停止共享写入 | cleanupStopsSharedWriterBeforeLaterDraftCanPublish；invalidDraftPreferenceStopsWorkspaceAndSubsequentDraftWrites |
| 普通写失败可显式重试 | ordinaryWriteFailurePreservesOldLayoutAndAllowsExplicitRetry |
| shutdown排空、晚到UI回调也结算、释放锁 | acceptedSaveDrainsAndCompletesEvenWhenShutdownPrecedesUiDelivery |
| owner/null/初始化/busy准入 | wrongThreadInitializingNullAndOverlappingManagementAreRejected |
| writer/UI拒绝不留下未结算future | writerRejectionSettlesOutcomeAndMakesRuntimeUnavailable；uiRejectionSettlesOutcomeAndStopsFurtherWrites |
| 已开始保存先完成，清空后不复活 | runningSaveFinishesBeforeClearRatherThanResurrectingAfterIt |
| 真实Path构造与LocalBackend四入口 | publicPathOwnerUsesSameStoreForReadWritePreferenceAndClear |

### P2.3a 独立任务审查

`workspace_runtime_bridge_review`（sol）对完整冻结两文件diff、brief和报告给出 Spec compliant / Task quality Approved：0 Critical / 0 Important，1 Minor为既有SqlEditorResultFilterContractTest unchecked编译提示，进入P2整分支审查记录，不为本任务扩大无关修复。

审查定向核对了原queue空ID/barrierAll顺序、stop的CLEANUP粘性及shutdown/owner语义；CodeGraph跨worktree提示后的检查使用本worktree源码。其“未独立重跑历史RED/full”由root实际RED XML、全量XML及独立53项复跑证据补足；“b/UI未实现”作为明确未完成项保留。root核对build/wrapper/JDK仍为Java25/JavaFX25/JUnit5.11.3/Gradle9.2.0。此批准不等于P2整体验收或合并许可已满足。

下一阶段的新增核对事项：当前AppShell将`closeAllManagedTabsMandatory`直接交给AsyncShutdownCoordinator；后者只在COMPLETED后执行destructiveTeardown，FAILED_PARTIAL保持终态、CANCELLED才允许重试。P2.3b应在破坏性清理前解决清单保存决策，同时核对已关闭managed registry在“取消退出”后的可用性，不能只包一层future就声称取消/重试安全。此处是当前代码发现的接入风险，未修改原关闭流程。

root已直接核对当前worktree `AsyncManagedTabRegistry.finishCloseAll`：COMPLETED转为CLOSED，只有CANCELLED转回OPEN。故“受管标签已经全部关闭，再因布局失败对外返回CANCELLED”不能直接作为b的实现；必须在其设计/测试中处理registry可继续使用，不能把已有CLOSED/FAILED_PARTIAL状态无条件重置。

P2.3b还需把捕获层的“最新候选”纳入同一失效契约：本桥只失效已经提交到queue的保存，尚在FX内存等待BUSY解除的候选不能在P1清空后重新提交。b的计划应增加明确的代次观察/通知与候选废弃测试，不能只覆盖已排队任务。首次草稿成功保存后的savedAt变化也须触发捕获，否则刚保存的新标签可能一直不进入布局。退出冻结不能等binding脱离后再从installedContent重新推断原标签集合。

独立开关的用户意图也需由b保留：关闭记录请求失败时，存储桥如实返回失败且旧偏好仍存在；未来自动捕获层不能因旧偏好仍为开启而自动恢复记录，须在本次会话暂停并显示“设置未保存”，待用户明确重试/重新开启。a目前只有显式调用，不提供自动捕获策略，不能用其“普通写入可显式重试”测试替代隐私开关UI验收。

## 后续集成必须补验的风险

### P2.3b 启动基线与接入方案

2026-08-31从独立worktree `62997db5f4278e472061927fd3f708e87c4c84be` 继续；工作树干净。root执行 `./gradlew.bat test --no-daemon --console=plain`，作用域内设置并恢复 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，JDK仍为25.0.1+8，session41295 exit0/61秒。直接读取当前XML：155 suites、1452 total、1449 passed、0 failures/errors、3既有live skips。此为实现前基线，不是b完成证据。

设计与计划提交 `4c14aca620e1c673b618014d6a2d727436c76a93`：[活动设计](../specs/2026-08-31-sql-workspace-activity-design.md)、[集成计划](../plans/2026-08-31-sql-workspace-activity.md)。只读架构子代理确认应在registry最终状态转换前运行gate，等待tab+mandatory-abort结算，并在ownershipLock内原子轮换tracker与提交取消转换。最终保存身份从同writer的已验证草稿快照解析，不依赖flush回调顺序。计划包含实际FX/AppShell路径和取消后重新打开managed tab验收；不接受只完成状态模块。

以下保留启动时的设计与测试质量记录；最终实现、回归及审查结论在后续小节，不能将方案本身当成通过证据。

首轮测试质量检查（不是有效RED证据）：实际XML02:37:20Z为8个activity和2个UI测试UOE；root发现断言没有执行其名称所描述的行为，拒绝GREEN。activity文件8项均只有一个状态相等断言：`failedDisableRemainsPausedUntilExplicitSuccessfulEnable`与`emptyNeverSavedExcludedButClearedCheckpointIncluded`只检查新建空owner为IDLE；management/busy/debounce等仅检查PENDING。返回固定状态、不执行清空/暂停/落盘也能满足这些断言，故不能验证计划。要求以真实运行时/临时store/手动时钟和真实FX节点重写，重新观察有意义的失败后才实现。此记录保留审查来源，不把名称或失败数当作测试覆盖。

### P2.3b 实现及验证

源码提交`0a7203b0c67a64140dd15945ccfee9bbf4b19e61`，11个允许的源/测试文件。新建无FX的`SqlWorkspaceActivity`和FX适配器`SqlWorkspaceUi`，复用唯一runtime/timer/writer；AppShell惰性构造已接通ContentTabPane关闭生命周期。registry在reservation完成后冻结，再关闭guards，最终gate等abort结算并保存布局；取消转换和tracker轮换在同一ownershipLock下完成。尚不提供P2.4恢复界面。

真实TDD起点由root直接核实：XML02:38:57.110Z，一个使用实际临时store、手动disk/UI executor、已存在清单的未触及退出用例UOE；断言检查确切旧文件与没有新发布，之后才ACK实现。后续RED/GREEN为实现代理报告并保留逐次输出：代次/隐私、真实FX捕获、重复取消退出、abort错误不可降级、terminal hook提交取消后再抛异常等。早期`firstCheckpoint...`实为延迟发布测试，已改名`changedLayoutPublishesAtIdleDeadline`；真正的首次草稿成功保存由新的FX用例验证。

一次测试调度失误也保留：代理在旧全量进程尚未真正结束时启动定向用例，后者因output.bin占用失败。这不是行为RED，不计验收。确认两个进程退出后，串行重跑获得真正失败用例、定向GREEN，再重新完整运行。未使用删除/清理规避文件锁。

- 最终串行全量命令：`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，scoped non-headless、JDK25.0.1+8；代理session99543/chunk22d2d4 terminal exit0，46.4339074秒，8 tasks全部executed。
- root在自己的定向复跑前直接汇总这套最终XML：157 suites、1497 total、1494 passed、0 failures/errors、3 skipped。跳过名称仍为本文件基线的Redis standalone、Oracle SchemaDiff、PostgreSQL SchemaDiff三项；未开启真实数据库测试。
- 本任务三套：Activity26、Registry14、UI14，合计54项无跳过。编译仍有既有`SqlEditorResultFilterContractTest` unchecked提示及Gradle problems报告提示，不声称无警告。
- root独立复跑命令：`./gradlew.bat test --tests '*SqlWorkspaceActivityTest' --tests '*SqlWorkspaceUiTest' --tests '*AsyncManagedTabRegistryTest' --tests '*SqlWorkspaceRuntimeTest' --tests '*ManagedCloseBarrierTest' --no-daemon --console=plain`。同JDK、scoped non-headless并显式保留native exit；session40303 exit0/12秒。实际XML03:07:20–22Z：26+14+14+22+2=78 passed，0 failures/errors/skips。这不是第二次全量。
- root确认diff仅允许文件，`git diff --check`无错误；CRLF转换提示保留。root确认main仍为`7710ecb526d10a22e3fbff65367c50b04e44ed9d`，用户未提交SqlDraftStore及`.testagent/`名称仍在，未访问其内容。

下表A=SqlWorkspaceActivityTest，U=SqlWorkspaceUiTest，R=AsyncManagedTabRegistryTest；完整三套均在上述最终全量与root独立定向中通过。

| 要求 | 精确测试 |
| --- | --- |
| 未开始工作直接退出不覆盖旧布局 | A/U.untouchedSessionAndExitPreservePreviousLayout |
| 首次保存确认自动入列、未保存空页排除/已清空检查点保留 | U.firstCheckpointBecomesEligibleWithoutAnotherUserAction；U.emptyNeverSavedExcludedButClearedCheckpointIncluded |
| 1000ms空闲/10000ms持续上限、时间戳轮询不制造写入 | A.changedLayoutPublishesAtIdleDeadline；A.continuousActivityIsCoalescedWithBoundedDeadline；A.timestampOnlyObservationDoesNotWriteAgain |
| 一在途一最新、BUSY可继续 | A.busyKeepsLatestCandidateAndDoesNotLoseCompletion；A.runtimeBusyIsBackpressureNotFailure |
| P1/P2管理成功或失败均废弃旧候选 | A.everyAcceptedManagementInvalidatesOldCapture（clear/delete/draftOff/draftOn/workspaceClear/workspaceOff/workspaceOn/failedClear/failedWorkspaceOff） |
| 失败关闭保持会话暂停、迟到结果不恢复记录、成功关闭显示正确状态 | A.failedDisableRemainsPausedUntilExplicitSuccessfulEnable；A.staleAcceptedWriteCompletionCannotReleaseFailedDisablePause；A.lateReadCompletionCannotOverwritePauseOrNewGenerationCandidate；A.successfulDisableReportsDisabledAndPreservesLayout |
| 已关闭/损坏偏好或读取失败不按默认开启处理 | A.firstActivityReadsDisabledPreferenceWithoutTryingPublication；A.corruptPreferenceProtectsExistingLayout；A.failedSnapshotIsNotTreatedAsEmptyEnabledLayoutAndRequiresRetry |
| 普通失败仅明确重试，保留最新候选 | A.ordinaryFailureRequiresExplicitRetry |
| 真正标签顺序、非SQL选择为空、反向UTF-16选择 | U.sqlOrderSelectionAndReversePositionsFollowActualTabs |
| 普通单页取消不删除，实际移除才更新 | U.cancelledSingleCloseKeepsLayoutButActualRemovalUpdatesIt |
| 退出前冻结含最终新保存草稿、重复取消不缩短快照 | U.exitFreezesBeforeRemovalAndIncludesFinalDraftCheckpoint；U.cancelledExitRetainsFrozenUntilExplicitActivity；U.repeatedCancelledExitKeepsOriginalFrozenLayoutUntilExplicitAction |
| 布局失败取消后能重新打开受管页，重试/忽略持久化不同 | U.layoutFailureCancelAllowsNewManagedTab；U.layoutFailureRetryAndIgnoreHaveDifferentPersistence |
| abort部分失败不打开registry/不清理应用，最终回调不能降级错误 | U.partialAbortFailureNeverReopensRegistryOrRunsTeardown；U.finalCallbackCannotDowngradeMandatoryAbortFailure；R.gateCannotDowngradeFatalOrCancelledAndExceptionFailsClosed |
| 正在安装的reservation进入冻结，冻结完成先于guards | U.reservationFinishingDuringExitIsCapturedBeforeGuardClose；R.reservationsSettleThenFreezeCompletesBeforeAnyGuard |
| 调用方取消不取消内部写入/退出，terminal异常不提前报成功 | A.callerCancelledFrozenPublicationStillPersistsAndSettlesInternalAdmission；U.callerCancellationDoesNotCancelInternalCloseOrPublication；R.callerCancellationCannotCancelInternalGateOrReopenAdmission；R.terminalExceptionAfterCancelledCommitFailsClosedAndCannotStartAnotherAttemptInsideHook |

首轮独立审查`workspace_activity_review`：Spec不通过 / Needs fixes，0 Critical、1 Important；既有unchecked提示为Minor。问题是registry在terminal hook返回前仍保留pending旧attempt，而ContentTabPane在重复关闭时无条件获取并hardSeal当前tracker。默认/尚未创建SQL owner路径的后台abort结算可在“已轮换新tracker并OPEN、旧future尚未完成”间隙遇到第二次FX关闭，导致旧attempt与新sealed tracker错配，取消后所有新factory被拒绝。SqlWorkspaceUi.finish自身FX串行不能保护未初始化owner的既有流程。

root读当前两文件核对交错成立，已要求ContentTabPane在获取/封存tracker之前按attempt去重，补无workspace owner的真实可控交错回归，保留terminal异常fail-closed语义。上述1497全量/78定向是审查前版本的真实结果，不能替代修复后的重跑。复审通过前不将P2.3b标为完成。P2.4恢复入口和P2.5桌面/打包/全分支审查仍独立待办。

### P2.3b 审查修复与最终完成证据

修复`2fa333c900a068036c9ef650bfc018ea6318a177`仅改变ContentTabPane及新增ContentTabPaneCloseAttemptTest，未改变registry/P1 guards。ContentTabPane在读取tracker前建立内部关闭attempt占位，pending及已完成非CANCELLED结果均返回隔离副本，只有真正完成的CANCELLED允许下一尝试。这样重复关闭不再接触新tracker，同时保留terminal异常失败关闭语义。

- 真实回归RED：root直接读取XML03:13:56.181Z，`ContentTabPaneCloseAttemptTest` 2 tests、1 failure、0 errors/skips。`repeatedCloseDuringWorkerTerminalGapDoesNotSealRotatedTracker`在后台hook提交/轮换后以latch停住，FX发起第二次关闭，两次均CANCELLED后实际新factory为null；失败点正是预期缺陷，不是超时或编译失败。代理exit1/12.4206037秒。
- 修复后定向GREEN 2/2，exit0/10.2851951秒；六套覆盖80/80，exit0/11.9621156秒。另一个`ContentTabPaneCloseAttemptTest.synchronousEmptyAttemptAndCallbackFailureAlwaysSettleReturnedCopies`验证同步空关闭、异常回调和重复结果均结算。
- 最终串行全量仍为`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，同JDK/scoped非headless；session89686确认terminal exit0且无session_id，48.7315575秒。root在复跑前用只读XML汇总再次确认：158 suites、1499 tests、1496 passed、0 failures/errors、3原有live skips，名称与基线完全一致。本任务四套56/56无跳过。
- root修复后独立复跑：上文五过滤命令增加`--tests '*ContentTabPaneCloseAttemptTest'`，session60993 exit0/12秒。直接汇总实际XML六套80 passed、0 failures/errors/skips。原有编译unchecked提示仍留存，不声称无警告或远端CI通过。
- `workspace_activity_review`在原完整审查后，仅复读修复两文件package与更新报告，最终给出 **Spec compliant / Task quality Approved**：0 Critical、0 Important，无新增问题；既有unchecked提示为Minor进入P2最终整分支审查。确认attempt先于tracker准入、旧attempt完整结算、取消隔离和实际新factory回归。未冒称完成整P2审查。

P2.3b按计划完成。下一步P2.4启动页/草稿页显式恢复入口与偏好/清空UI；P2.5真实桌面、打包、进程验收与整分支审查后才本地合并main。

以下是当前代码检查所得的具体接入风险，不是已实现缺陷或已通过测试。

1. `SqlDraftUi` 拥有 writer、timer、binding 和 installedContent，`SqlDraftCoordinator` 内部 LocalBackend 独占 SqlDraftStore。工作区存储应共享同一 directory 锁与序列化通道，不能再 open 同一目录形成第二 writer。
2. P2.2已仅增加 `workspace.bin`、`workspace-preferences.bin` 两个精确文件名；保留规范UUID `.draft`、原偏好格式、上限、身份戳和发布/清理失败类型。后续运行时应复用已验收的存储边界，不另建宽松路径或写入方式。
3. 初始化 coordinator 会先清理过期草稿；解析必须容忍布局引用因此缺失。不能用清单将过期正文永久保留，也不能因一个缺失条目把其余草稿都挡住。
4. installedContent 是绑定/安装的身份映射，不是已落盘的集合；关闭时 binding detach 移除身份。捕获退出清单必须早于这一移除，且成功保存记录须以实际检查点为准。
5. ContentTabPane 的 managed tabs 删除监听会触发异步关闭。恢复重排需要受控的顺序变更接口，禁止直接 remove/re-add 来移动活标签，否则可能误触清理/事务流程。
6. AppShell 的 shutdownAsync 先走 `closeAllManagedTabsMandatory` 再 destructive teardown，非普通 close-all。P2 的退出冻结/失败反馈不得改变 mandatory 事务处理或使未完成关闭被误认成功。
7. 普通 SqlEditorPane 构造传入 boundConn 时可创建会话包装、监听全局连接和预热。恢复必须复用 recoverDraft 的被动构造入口；恢复位置要在实际 CodeArea 文本装载后夹取，不能改写 P1 的 recoveredUneditedSql 原文。
8. 清空/关闭草稿保护会触发代次/屏障，工作区排队任务也须失效。写入成功后再报告保存状态；UI 设置不能采用静默 best-effort 偏好。
9. 当前 SQL 草稿 UI 是 lazy 初始化；启动页恢复提示若要加载清单，应异步初始化共享 owner，不在 FX 线程扫描文件，也不能在没有用户工作区动作时写一个空快照覆盖上次布局。

## P2.4 显式恢复界面验证

源码提交`553e0621fb6735871480ce3dfa5c27e41aed09e0`，基线`32360ea1aebb4c4bbc84b5d561191746246ce3a4`，8个生产文件/3个测试文件。AppShell启动页和SQL草稿共用管理对话框；启动入口仅回调、不提前初始化writer或显示SQL。区块读取有效草稿/清单后显示数量，点击恢复重新读取并校验代次，再按实际受管标签装配。清空只清布局，开关写入结果与本次暂停状态分别显示。

调试记录保留：首个batch RED见前述root原始XML；随后manager/startup10项3失败（初读UOE、实际组合区块和入口按钮缺失），root实际XML10:36:35–37Z核实。新增stale-toggle及queued-callback错误反馈用例真实RED由实现代理记录。空草稿夹具未调用edited、错误用目录模拟普通不可读、断言受管关闭应删除非受管页，均修正为正确夹具/预期，不冒充产品缺陷。

关闭边界发现及修复：DISABLED草稿保护允许恢复旧布局，但原finish尝试保存新清单，产生错误工作区决策。root核实真实timeout/嵌套showDecision栈，要求计数决策返回CANCEL的确定性回归；该DISABLED用例期望COMPLETED、实际CANCELLED。PAUSED草稿的原P1 flush会拒绝；最初也要求COMPLETED是测试预期错误而非产品缺陷，已明确改为CANCELLED且编辑器保留。最终SqlWorkspaceUi只在关闭结果已COMPLETED且明确DISABLED/PAUSED时跳过新工作区发布，不跳过UNAVAILABLE/真正写失败、不改原守卫/abort。

### 命令与结果

所有命令使用`D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8`，作用域内设置并恢复`JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`，原生exit单独保留，进程串行。没有真实数据库连接。

- 实现代理最终定向：`./gradlew.bat test --tests '*SqlWorkspaceRecoveryTabsTest' --tests '*SqlWorkspaceManagerTest' --tests '*WorkspaceStartPaneTest' --no-daemon --console=plain`，exit0/37s，14+28+8=50通过。
- 相邻回归：追加的五套`SqlWorkspaceUiTest/SqlDraftRecoveryTabsTest/SqlDraftManagerTest/SqlDraftFailureFeedbackTest/ContentTabPaneCloseAttemptTest`，exit0/15s，14+9+26+6+2=57通过。
- 最终完整：`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，session46291 terminal exit0/78s。root在独立复跑前实际XML汇总160suites、1542total、1539passed、0failures/errors、3skip；名称仍仅Redis standalone、Oracle SchemaDiff、PostgreSQL SchemaDiff三个既有live用例。
- root独立复跑上述八套，完整命令为`./gradlew.bat test --tests '*SqlWorkspaceRecoveryTabsTest' --tests '*SqlWorkspaceManagerTest' --tests '*WorkspaceStartPaneTest' --tests '*SqlWorkspaceUiTest' --tests '*SqlDraftRecoveryTabsTest' --tests '*SqlDraftManagerTest' --tests '*SqlDraftFailureFeedbackTest' --tests '*ContentTabPaneCloseAttemptTest' --no-daemon --console=plain`，session77910 terminal exit0/41s，实际XML8suites/107passed/0failures/errors/skips。
- 原有SqlEditorResultFilterContractTest unchecked编译提示及Gradle配置缓存建议保留；不声称无警告或远端CI通过。

### 需求到具体用例

下表R=`SqlWorkspaceRecoveryTabsTest`，M=`SqlWorkspaceManagerTest`，S=`WorkspaceStartPaneTest`。只针对本轮功能，不宣称测试覆盖率百分比或真实桌面验收通过。

| 需求 | 精确方法名与断言 |
| --- | --- |
| 新标签顺序/选中、UTF-16反向选择、CRLF控件和空检查点夹取 | R.orderedPartialRestoreClampsNewControlAndPreservesEditedReuseAndUnrelatedSlots；R.selectedFallbackUsesFirstSuccessExceptNullPreservesPriorTab，真实Tab/正文/anchor/caret |
| 不覆盖复用标签正文/Schema/位置，不移动无关SQL及非SQL页、重复不创建 | R.orderedPartialRestoreClampsNewControlAndPreservesEditedReuseAndUnrelatedSlots；R.involvedSlotPermutationPreservesUnrelatedSqlAndAllManagedCloseGuards，真实身份顺序、编辑值、工厂数、关闭后资源释放 |
| 原选中缺失/失败回退，null保留原当前或首成功 | R.selectedFallbackUsesFirstSuccessExceptNullPreservesPriorTab，missing/failed/null-existing/null-empty四种实际选中身份 |
| 部分成功准确计数、零成功不消费清单/激活记录、重试与批次抑制 | R.zeroSuccessKeepsSelectionOldManifestAndInactiveSessionForRetry；R.recoverySuppressesIntermediateCaptureAndRejectsNestedBatch；R.orderedPartialRestoreClampsNewControlAndPreservesEditedReuseAndUnrelatedSlots |
| 改名/删除/同名异ID/类型变化/未知Schema均离线 | R.offlineRestorePreservesCheckpointIntentWithoutResolvingProvider，五组正文、Schema、intent、原文与四探针0、global connection和admission未绑定 |
| 初读等待初始化、展示真实数量、点击前不建编辑器、重复点击单次 | M.initialReadShowsRealSnapshotCountsAndCreatesEditorsOnlyAfterExplicitRestore；M.initializationDefersExactlyOneReadUntilRuntimeReady |
| 清空/关闭/删除变更代次或页面关闭后，迟到读不建页 | M.lateReadAfterGenerationChangeOrClosedPaneNeverCreatesEditors，四组真实backend读后门闩和runtime操作 |
| 空/不存在/损坏/未知版本/不可读/读失败、显式重试、错误回调、坏偏好 | M.snapshotProblemsHaveFixedMessagesNoAutomaticLoopAndExplicitRetry；M.ordinaryReadFailureLabelsPriorCountsAndRequiresExplicitRetry；M.invalidPreferenceIsNeverDisplayedAsEnabledAndCannotToggle；M.callbackFailureShowsFixedNoticeAndKeepsPreviousManifestForRetry |
| 两种保护关闭仍可恢复且无新发布；DISABLED退出、PAUSED拒绝保持 | M.disabledRecordingOrDraftProtectionStillRestoresOldLayoutWithoutNewWrites；M.disabledDraftsCloseButPausedDraftGuardStillCancelsWithoutWorkspacePublicationOrDecision，原文件/写入计数/决策计数/真实关闭结果 |
| 开关落盘前不报成功、失败本次暂停、刷新后仍暂停、显式再启用、旧偏好禁用按钮 | M.toggleWaitsForPersistenceAndFailedDisableStaysSessionPausedUntilExplicitEnable；M.stalePreferenceCannotExecuteToggleUntilExplicitRefresh |
| 清空取消/成功/失败、只清布局不删除草稿或当前页、空清单成功、坏文件保护 | M.clearOnlyChangesManifestAfterConfirmationAndFailureRetainsRecoveryCount；M.clearingAlreadyEmptyManifestIsSuccessfulOperation；M.corruptManifestClearRemainsProtectedAndSingleDraftRestoreStillWorks |
| 启动实际按钮、旧TabPane/旧helper、对话框实际组合/单订阅释放/writer继续 | S.recoveryEntryOnlyInvokesCallbackOnClickAndKeepsExistingTabPane；M.dialogComposesWorkspaceAndDraftControlsWithOneDisposedSubscriptionAndLiveWriter；相邻P1管理/恢复回归 |

root源差异核对未见新增磁盘owner/线程/timer/数据库执行路径；测试断言覆盖实际节点、文本、选择、文件、受管清理、权限/生命周期拒绝，不以状态名或数量代替具体行为。独立`workspace_restore_ui_review`给出Spec compliant / Task quality Approved，0 Critical、0 Important：确认实际节点/持久化/拒绝结果断言，无新增无断言或纯空值用例；只读审查未声称自己执行测试。审查为核实mandatory abort作一次聚焦外部检查（SqlDraftRecoveryTabs、ContentTabPane），确认原所有权与退出屏障；root核实不变的Java/JavaFX25、JUnit5.11.3、Gradle9.2.0版本。

审查Minor保留到P2.5：`SqlWorkspaceManagerTest.lateReadAfterGenerationChangeOrClosedPaneNeverCreatesEditors`已覆盖旧读取失效，但尚未覆盖“旧回调返回时新attempt已开始”的完整组合；针对`SqlWorkspaceManagerPane.valid`的attempt token条件补一个确定性测试，验证旧回调既不建页也不清除新pending/result。此为静态未验证的测试缺口，不是已复现运行时缺陷。P2.5整分支审查必须再评估此项及既有unchecked编译提示，不静默丢弃。

P2.4完成。P2.5真实桌面、跨进程和打包仍待执行，当前任务批准不等于main合并/发布批准。

## P2 完整验收清单

### P2.5 跨进程与桌面边界

忽略目录内的SqlWorkspaceAcceptanceLauncher及init脚本仅为验收工具，不进入发行包。实现代理最终matrix（修正40秒子进程等待及增加真实Handle检查点后）session38486 exit0/64s，17个独立JVM，根目录 `C:/Users/hetia/AppData/Local/Temp/datacube-workspace-process-14862248713823088493`。首编译错误Mode.ACTIVE应为ENABLED仅是夹具错误，不是产品缺陷。

root独立执行 `./gradlew.bat -I .superpowers/sdd/workspace-acceptance.init.gradle verifySqlWorkspaceProcesses --no-daemon --console=plain`：session52720 terminal exit0/57s；根目录 `C:/Users/hetia/AppData/Local/Temp/datacube-workspace-process-9961000438763930905`。17次结果均符合约定（异常子进程37，其余0），root另行读取全部17份日志，确认各自四探针0、16份正常关闭/CHILD_PASS、异常前检查点标记，无AssertionError、未捕获异常或强制清理。场景为正常退出→恢复、确认检查点后异常中断→恢复、单标签关闭→单页恢复、取消退出→恢复旧布局、未触及启动→再次恢复、持久关闭记录→仍可恢复旧布局、清空布局→草稿保留且可逐条恢复。均断言真实Tab/CodeArea文本、顺序、选中和8:2/9:3反向选择，不以文件读取成功代替界面状态。

独立任务审查认为功能符合，但以Important要求消除原始Scene产生的CSS lookup/String-to-Paint警告并明确隔离已知unnamed-module启动诊断，以免掩盖新问题。正在修正**验收夹具**及复跑，不修改产品样式或关闭通用日志；当前不将此任务标记为最终Approved。

上述审查项现已修正并复审Approved，0剩余发现：夹具实际注册/释放ThemeManager，CSS警告消失；只在Platform.startup期间匹配logger=`javafx`、WARNING、无throwable/参数且完整unnamed-module消息的单条诊断，将原文保留为 `FRAMEWORK_STARTUP_CAPTURE`，finally恢复原filter。负例确认错误级别/其他logger/其他消息/异常不被拦截，父进程拒绝每个子日志中的其余warning/error/CSS诊断。未关闭logger级别/handler/stderr，没有修改产品源文件。修订期间的变量名遮蔽导致一次helper编译失败已纠正并保留报告，不称产品RED。

修正后实施代理session3876 exit0/53s，目录 `C:/Users/hetia/AppData/Local/Temp/datacube-workspace-process-1813757519229105852`；root逐份审计17日志后，再独立执行同matrix，session4397 terminal exit0/54s，新目录 `C:/Users/hetia/AppData/Local/Temp/datacube-workspace-process-11611965504443793803`。root再次核对全部17日志：每份恰好一条已知诊断capture、其余warning/error为0、四探针0，16正常cleanup/pass及异常37前确认检查点，未强制终止。最终launcher SHA-256 `FF6D0EF653FC9D60418D0F8A1C0C9CA662B560E052C253EB5125A7D9783C1A14`；init `DF8342E190AFA8BA41C9CED4B9441E3C8B15EB7EB341BE62CA07405D334D83E6`。这一完整跨进程门槛通过，不改变下方桌面尚未验收的状态。

真实AppShell已用已标记profile启动：session85204、PID26932，标题 `DataCube SQL工作区隔离验收`，输出路径与本轮独占profile一致。Computer Use先返回唯一目标窗口，尝试读取界面时报 `Computer Use helper already has an active request`；重新定位后重试一次仍为相同错误。没有成功截图/控件树、没有点击或输入，故明暗主题/键盘/真实入口**未验收**。按技能停止输入，向维护者报告后，只在核对exe、launcher、profile均匹配的情况下结束本轮PID26932，未操作其他进程。JavaExec因此exit-1、Gradle exit1/1m59s，这是工具阻塞后的验收实例清理，不是正常退出通过或产品断言失败。合成profile/标记/草稿全部保留；桌面控制通道恢复后必须重新启动并继续，不能以自动化进程结果替代。

桌面阻塞不妨碍只读整分支审查。main的重叠未提交文件仍需用户方向；在桌面门槛及合并边界解决前，不本地合并、不推送/tag/发布。

### P2.5 当前运行证据（2026-08-31）

[总体验收计划](../plans/2026-08-31-sql-workspace-acceptance.md)及[独立进程计划](../plans/2026-08-31-sql-workspace-process-acceptance.md)已记录。以下并发回归和进程夹具阶段不改产品行为；其后的整分支审查修复单独记录。

并发回归提交 `81fde83fce1aba6c7c03b88670c14ba345da9930` 仅为 `SqlWorkspaceManagerTest` 增加112行。`oldRestoreCompletionCannotAffectNewPendingRefresh` 持有真实旧读取的owner投递，持久关闭记录并推进代次，启动并持有新刷新，再分别放行两轮结果；断言旧结果不建编辑器、不修改新pending/按钮/notice，新结果展示准确数量和关闭偏好，原清单字节不变。测试只拦截调度，不制造manager状态；关闭路径恢复原executor并排空持有结果。

- root新基线manager28项：session80622，exit0/31s，实际XML28/0failures/errors/skips。
- 新用例首先通过现有实现（exit0/36s，1项），不是产品缺陷RED。受控变异只临时删除 `token != attempt`，exit1，root直接读取实际XML1项1失败、0errors/skips，失败为editor factory expected0/actual1，非超时。
- 生产文件恢复前后SHA-256均 `0578FA44F1BF86C5CD444700B8B9DDF6A30C55C6B87F30C79F409908FBE4AFE6`，root实际diff检查0。恢复后manager29项exit0/39s。
- root在提交后独立运行 `./gradlew.bat test --tests '*SqlWorkspaceManagerTest' --no-daemon --console=plain`，session31851 terminal exit0/32s，实际XML29通过/0failures/errors/skips；复核生产SHA-256仍相同。
- 实施代理唯一最终完整命令 `./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，JDK25、scoped非headless，terminal exit0/1m11s。root独立聚合实际XML：160suites、1543total、1540passed、0failures/errors、3既有live skips；仅Redis standalone、Oracle及PostgreSQL SchemaDiff，名称与上阶段相同。既有unchecked编译提示保留。
- 并发任务独立 `workspace_attempt_review` 给出Spec compliant / Approved，0 Critical/Important/Minor；核对真实投递顺序和失败排空。root补齐其无法由diff独立证明的历史XML、精确变异失败及生产复原检查。不能据此提前关闭整个P2。

root同时实际构建免安装镜像：`./gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain`，session92442，exit0/53s，14任务全部执行。仅本进程暂时清空 `JAVA_TOOL_OPTIONS` 后恢复；保留JDK25的JEP493/jmods提示。`jimage list build/jpackage/DataCube/runtime/lib/modules` exit0，包含SqlWorkspace模型/存储/运行时/Manager/RecoveryTabs/Ui与P1草稿类；未检出验收launcher、DraftConnectionProbe或本项目Test类。`app/DataCube.cfg` 主入口是 `com.datacube/com.datacube.DataCubeFx`，无隔离user.home。modules SHA-256：`B860046634E550E8F65D015387365C330716E26B141F68DA412F36207F4140DC`。默认3.0.0仅为本地构建版本，不是新发布号；没有安装或运行带更新检查的发行入口。

桌面专用目录已新建并标记：`C:/Users/hetia/AppData/Local/Temp/datacube-workspace-ui-3a97aa5b837644eaaad0636a6eeed5cf`。已核对AppShell配置均由启动时user.home确定；实际启动后因控制通道连续两次失败而停止桌面验收，详细清理证据见上文。main仍是7710ecb，未提交SqlDraftStore与本分支重叠；只检查路径名，未读取/暂存/覆盖，已请求整合方向。所有验收继续在独立分支，未合并/推送/tag。

### P2.5 整分支审查与保存反馈修复

独立整分支审查冻结区间`7710ecb..4cfda74`，生产/测试diff读至EOF：0 Critical，1 Important，1既有Minor，结论With fixes / 不可合并。Important为普通活动保存/读取/捕获失败会锁定FAILED并停止自动重试，但管理页只显示记录偏好和旧恢复数量，没有消费失败状态或调用显式retry，可能令用户误以为新布局仍被保护。此结论先由源码确认，不能冒称审查代理已执行复现。Minor为未修改的SqlEditorResultFilterContractTest泛型varargs unchecked提示，不阻塞本次功能，另行清理。

已记录[保存反馈设计](../specs/2026-08-31-sql-workspace-save-feedback-design.md)和[实现计划](../plans/2026-08-31-sql-workspace-save-feedback.md)，冻结基线`fb19ebba0880c2f3380def393c2cbae50d85db11`。新增独立的当前布局状态与“重试保存布局”，通过现有UI owner重新捕获最新安全位置；不新增自动重试、不改变存储/代次/暂停/退出守卫、不以立即返回的retry future冒充落盘成功。实施代理先提交真实FX失败用例供root核实，然后实现、完整测试、交回原整分支审查者复审，并重新验证进程与镜像。桌面及main边界不因代码修复自动解除。

真实RED已由root核实：保留的`workspace-save-feedback-task-1-red.xml`时间2026-08-31T11:41:34.587Z，1test/1failure/0errors/skips，实施代理native exit1。`activitySaveFailureIsVisibleAndExplicitRetryPersistsLatestLayout`先保存/恢复真实草稿并确认SAVED，再注入实际saveWorkspace失败并等待FAILED；缺少可见状态Label的assertNotNull失败，不是编译错误或超时。root同时检查用例后续最新位置、无自动重试、旧清单及离线断言，并核实当时两生产文件diff为0，确认后才允许实现。

修复提交`e984c0c974867f3aa278c43d431367b26b4c4062`仅修改SqlWorkspaceManagerPane、SqlWorkspaceUi和SqlWorkspaceManagerTest，303新增/2删除。新增“当前布局：”状态行与“重试保存布局”按钮；重新捕获已安装/已有草稿检查点的控件位置再显式重试，实际异步落盘成功前保持待保存。17个新增测试场景保持原持久化、代次、恢复批次和退出守卫。测试中一次调用package-private异常构造器导致编译失败，改为现有结构故障分类器识别的backend异常；这是夹具修正，不是产品RED。

实施代理最终定向命令`./gradlew.bat test --tests '*SqlWorkspaceManagerTest' --tests '*SqlWorkspaceUiTest' --tests '*SqlWorkspaceRecoveryTabsTest' --no-daemon --console=plain`，session98565 exit0/58s，46+14+14=74通过/0失败或跳过，原始XML已另存。最终完整命令`./gradlew.bat test --rerun-tasks --no-daemon --console=plain`，session33067 native exit0/1m37s、8任务执行；root实际读取完整日志并聚合XML160suites、1560total、1557passed、0failures/errors、3既有live skipped（名称与基线相同）。所有命令串行、JDK25、作用域内设置/恢复非headless。原unchecked提示保留，没有连接真实数据库。

下表方法均属于SqlWorkspaceManagerTest：

| 需求 | 精确方法名与行为证据 |
| --- | --- |
| 失败可见、刷新/定时不自动重试，重试保存最新位置 | activitySaveFailureIsVisibleAndExplicitRetryPersistsLatestLayout：真实文件/编辑器/失败、旧文件字节及写次数、最新4/9位置与SQL不变 |
| 写入中不报成功、重复点击、再次失败保留旧恢复点 | activityRetryWaitsForPublicationAndFailedRetryPreservesRecoveryPoint：实际保存门闩、单次接纳、原字节、修复后9/3位置与SAVED |
| 活动读取失败修复后重试、捕获失败后重新捕获 | activityReadFailureCanRetryAfterRepairWithoutAutomaticLoop；activityCaptureFailureRetryCapturesCurrentInstalledPositions：读取不循环、真实3/8及10/4位置、非FX拒绝 |
| 暂停/关闭记录、关闭runtime/adapter/manager、冻结/恢复中/管理忙拒绝 | activityRetryRejectsLifecycleAndManagementGuards：10组实际生命周期操作及控制器/事件拒绝，不清失败状态或新增写入 |
| 结构性不可用时不给不可执行的重试邀请 | activityRetryRejectsStructurallyUnavailableRuntimeWithoutClearingFailure：实际backend错误进入UNAVAILABLE，显示固定重启指引、不泄漏异常内容 |
| 真正退出期间拒绝重试、页面关闭后忽略完成 | activityRetryRejectsRealOwnerCloseWhileFinalValidationIsPending；closedManagerIgnoresRetryPublicationAndCannotSubmitAgain：真实关闭/保存门闩及最终发布次数、关闭后状态不更新 |

边界披露：捕获失败用例调用现有public captureFailed后使用真实控件恢复，不声称已注入retry内部第二次capture异常；没有为此增加生产测试接口。退出拒绝同时满足closing和FROZEN，不声称单独变异证明closing字段。代码已交原整分支审查者复审；以下新源码独立复测、镜像、进程结果单独补充。

原整分支审查者对`fb19ebb..e984c0c`完整diff、设计、计划、brief和报告复审至EOF：Spec compliant / Approved，原Important已解决，0剩余Critical/Important、无新Minor；合并此前整分支审查后代码批准。既有unchecked提示仍为独立非阻塞项。审查者没有运行测试，不把提供的验证报告当作其独立执行。整体合并仍不批准，须完成其余验收并解决main重叠。

root修复后独立重跑同三类定向命令，session1390 terminal exit0/56s；实际XML3suites、74tests、0failures/errors/skips。与完整回归分开记录；不把定向74项称作全量1560项再跑。

修复后免安装镜像：root `./gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain`，session52844 terminal exit0/40s、14任务执行，作用域内清空并恢复JAVA_TOOL_OPTIONS。保留JEP493/jmods提示。真实DataCube.cfg仍为正式DataCubeFx模块入口、无隔离user.home；jimage内容包含最新工作区UI类。首次辅助检查用不区分大小写的Test子串误报生产类QueryXlsxLayoutEstimator和ConnectionTestController，核对两者实际src路径后，改为测试源文件对应的精确类路径（包含内部类）和验收helper精确类名检查，exit0、无测试/探针/launcher混入；此为检查表达式误报，不是包缺陷。新modules SHA-256 `99B364305F3D502602AD7C96F55D1553A2E8FFC9C9375F19FA752FE14F28C492`，替代此前镜像hash；未安装或运行带更新检查的发行入口。

修复后跨进程：root在同一源码`e984c0c`运行`./gradlew.bat -I .superpowers/sdd/workspace-acceptance.init.gradle verifySqlWorkspaceProcesses --no-daemon --console=plain`，session32518 terminal exit0/54s；新合成根目录`C:/Users/hetia/AppData/Local/Temp/datacube-workspace-process-8330616676396494667`。逐份独立复核17日志与预期场景名，所有四探针0，每份恰一条已知框架诊断capture、其他warning/error为0；16份正常cleanup/pass和异常37前已确认检查点，未强制终止。夹具两份SHA-256与此前已审查版本相同，所有目录/日志保留。该矩阵与JUnit完整回归不等于实际桌面鼠标键盘验收。

最终main只读核对：仍为`main`/`7710ecb526d10a22e3fbff65367c50b04e44ed9d`，`src/com/datacube/config/SqlDraftStore.java`用户未提交修改仍存在，`.testagent/`只检查名称；未读取其内容、暂存、清理或覆盖。已询问能否读取整合重叠文件，尚无答复；桌面通道本轮不再重试。因此本轮停在已审查独立分支，不合并main、不推送/tag/发布，不提前推进P3。

- [x] P2.2 I/O 故障注入、偏好/清空/未知文件保护、同 JVM 与多 JVM 单写者；25项新测试通过且独立审查零发现。排队清空竞态属下一项。
- [x] P2.3a 共享队列异步API、单保存背压、管理代次失效、故障隔离/停用、关闭排空与future结算；22项新测试和独立审查通过。
- [x] P2.3 活动快照、防抖、失效代次、退出冻结、取消/部分失败/未触及启动不覆盖；b实际FX/AppShell接入及修复后复审通过，最终1496通过/3原有跳过/0失败，root独立80项通过。
- [x] P2.4 启动页及草稿页入口、原选中/光标/选择、重复定位、其他标签顺序、部分失败可见；553e062任务审查和回归通过。
- [x] 更名/同名不同 ID/删除/类型变化/不存在 Schema，批量恢复四探针0；原P1连接切换保持既有离线行为。真实桌面另验。
- [x] P2.4审查Minor：旧请求完成晚于新attempt启动的确定性用例及受控变异验证，81fde83已通过独立审查；既有unchecked提示经整分支审查确认为非阻塞、单列技术债。
- [x] 整分支Important：当前布局保存失败可见、显式重试捕获最新布局、失败与写入中不虚报成功，e984c0c回归及复审通过。
- [x] 非 headless 全量回归、独立进程重启与异常中断恢复；最新1557通过/3原有跳过、17进程矩阵通过。
- [ ] 合成配置真实桌面：关闭单标签 vs 退出、取消退出、明暗/键盘、开关/清空与重启。
- [x] 正式入口免安装包与源码一致；不夹带测试入口/临时 profile，新镜像hash及检查已记录。
- [x] 整分支代码审查及Important修复复审通过；既有unchecked提示单列为非阻塞技术债。
- [ ] 桌面验收及main重叠整合边界解决后本地合并 main，再做 main 回归；不隐含远端 CI/推送/发布已通过。

未触及真实用户数据、`.testagent/` 内容和已有 P1 验收目录。P0.2 发布验收及用户反馈仍是独立未完成事项。

## 桌面部分验收续跑（2026-08-31）

使用此前同一已标记合成profile `datacube-workspace-ui-3a97aa5b837644eaaad0636a6eeed5cf`，未重新播种或清理草稿。功能worktree与已推送c8c53aa的src/test/构建配置差异为0；launcher及init的SHA-256仍分别为FF6D0EF653FC9D60418D0F8A1C0C9CA662B560E052C253EB5125A7D9783C1A14、DF8342E190AFA8BA41C9CED4B9441E3C8B15EB7EB341BE62CA07405D334D83E6。

启动原桌面Gradle入口，session76758、PID19788、窗口标题“DataCube SQL工作区隔离验收”，启动输出确认独占profile。通过Computer Use的实际截图和控件树观察：

- 初始为暗色启动页，没有自动创建SQL标签；“恢复 SQL 工作区…”入口可见。
- 点击入口，先看到初始化/禁用状态，再稳定显示“共2，可用2，缺失0”、记录已开启，以及独立“当前布局：工作区记录尚未开始”，重试保存按钮禁用。
- 只显示合成alpha/beta草稿及missing-synthetic连接描述，旁边明确提示恢复不自动连接数据库；未操作真实连接、设置、删除或隐私开关。
- 点击管理页“关闭”后回到启动页，无SQL标签；焦点回到恢复入口，具有可见焦点框。随后按Space实际重新打开管理页，确认该键盘入口可用。

未通过或不作结论的项目：一次Escape输入后管理页仍存在，不能记作键盘取消通过或断言是产品缺陷；“恢复工作区”输入因工具报告`call get_window_state before using this window`未获成功确认；未看到恢复后的标签或执行SQL，也未做主题/单标签关闭/正常退出后的重启验收。

工具期间出现`Computer Use helper already has an active request`，按规则重新定位及观察可恢复；随后其他应用持续覆盖截图，而控件树仍描述验收窗口，画面与输入目标不能可靠对应。为避免向其他应用发送输入，停止进一步桌面操作，不绕过工具改用PowerShell UI Automation，也不将该情况包装为产品已修复或验收通过。截图仅由工具展示，未另外保存含其他应用内容的截图文件。

停止后用只读进程信息核对PID19788的JDK路径、launcher和独占profile全部匹配，仅结束本次拥有的合成JVM，保留profile/草稿/标记。Java因此exit-1、Gradle native exit1/4m37s，属于工具阻塞后的定向清理，不是正常退出通过或产品测试失败。未操作其他应用，未留下本次Gradle进程。

下一次需要一个隔离窗口可稳定处于前台、不与其他应用交错输入的时段，再从同一profile继续：显式恢复→核对两个标签顺序/选中/位置→重复恢复不增页→明暗和键盘→正常退出/重启。清空/记录偏好等隐私或删除操作保留既有合成自动化证据，不通过桌面工具绕过其限制。P2完整桌面门槛继续未勾选，本轮不提前实施P3。
