# SQL 草稿恢复验收记录

## 范围与状态

工作树：`D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`；分支：`codex/sql-draft-recovery`；起点：`main@0c4ecb9`。

P1 尚未完成。存储、调度、编辑器保护、离线恢复、管理页和重复恢复生命周期实现及任务审查已完成。整分支审查两项Important反馈缺口已在7d17728修订并独立复审通过，无剩余Critical/Important；主代理最新完整非headless回归1365项无失败、3项原有live跳过，跨进程恢复和jlink/jpackageImage重新验证通过。桌面捕获/访问被系统拒绝，实际暗亮主题与交互验收未通过。全部P1门槛完成后才本地合并，未推送/打tag/发布。下方按时间保留各阶段证据，旧阶段状态不代表当前状态。

设计见[SQL 草稿恢复设计](../specs/2026-08-30-sql-draft-recovery-design.md)。执行计划：

- [P1.1 格式](../plans/2026-08-30-sql-draft-format.md)
- [P1.2 文件边界](../plans/2026-08-30-sql-draft-directory.md)
- [P1.2 存储策略](../plans/2026-08-30-sql-draft-store.md)
- [P1.3 保存状态](../plans/2026-08-30-sql-draft-save-state.md)
- [P1.3 有界写入队列](../plans/2026-08-30-sql-draft-write-queue.md)
- [P1.3 应用协调器](../plans/2026-08-30-sql-draft-coordinator.md)
- [P1.4 编辑器自动保存](../plans/2026-08-30-sql-draft-editor-integration.md)
- [P1.4 离线恢复factory](../plans/2026-08-30-sql-draft-offline-editor.md)

异步运行时边界另见[运行时整合约束](../specs/2026-08-30-sql-draft-runtime-contract.md)，该文档是后续设计约束，不是实现证据。

仅使用合成数据与隔离测试目录，不读取真实连接凭据或SQL历史，`.testagent/`未读取/修改。

## P1.3 协调器任务（实现与任务审查完成）

协调器实现 `533210c` 仅新增运行时源文件与测试文件；与修订后的完整计划代码逐字规范化比对，两项均一致。`draft_coordinator_review` 独立审查结论为 Spec compliant / Approved，0 Critical / 0 Important。原有 unchecked 编译说明作为非阻塞事项保留，未宣称输出无提示。

审查中标为跨任务未验证的FX计时器/编辑器接入、拒绝关闭保持订阅、恢复零provider调用等均已明确列入下一阶段，不能作为本任务完成的产品能力；测试运行证据由主代理独立复跑及XML核对补充。底层Store的部分删除故障注入测试缺口和Queue的直接inline-executor测试建议仍交由最终P1整体审查评估；本任务的部分清理测试证明协调器刷新实际结果，不冒充底层删除循环的故障注入证明。

- RED19：主代理直接读取当时XML确认19 tests / 19 failures / 0 errors / 0 skipped，均为刻意保留的未实现异常；编译成功。
- 修订后RED20：子代理报告20 tests / 20 failures / 0 errors / 0 skipped；主代理未在覆盖前直接读取该次XML，不将其描述为独立核验。
- GREEN：子代理报告聚焦草稿测试 exit0；完整强制非headless exit0、40秒。主代理直接核验对应XML：144 suites / 1307 tests / 1304 passed / 0 failures / 0 errors / 3 live skipped。
- 主代理在同一代码 `533210c` 另行强制完整回归：exit0、39秒、8项任务执行，环境已恢复；直接XML复核同为144 suites / 1307 tests / 1304 passed / 0 failures / 0 errors / 3原有live skipped。

本轮修改前的主代理强制完整基线：exit0、38秒；143 suites / 1287 tests / 1284 passed / 0 failures / 0 errors / 3 live skipped。原有 unchecked 编译说明和 Gradle 信息提示仍存在。此数字不是新增协调器的完成结果。

后续真正的界面验收必须覆盖：独立草稿状态不覆盖查询/导出状态、关闭期间文本冻结、取消后继续保存、恢复不创建会话/不访问元数据、启动及管理失败仍显示实际可恢复记录。运行时的假时钟/可控调度器不能替代这些真实FX行为验证。

### 协调器 Requirement | Evidence

以下测试均位于 `test/com/datacube/config/SqlDraftCoordinatorTest.java`；测试使用真实状态/队列/临时存储，后台故障与UI调度通过显式边界控制。

| Requirement | Evidence |
| --- | --- |
| 初始化期间不承诺保存、不逐键捕获全文 | `initializationKeepsOnlyLatestInputAndNeverPromisesAFlush` |
| 连续编辑10秒捕获，只有最新版本显示已保存 | `continuousEditsCaptureAtTenSecondsAndSavedStatusWaitsForLatestRevision` |
| flush不依赖UI状态回调，调用者取消不取消写入 | `flushSharesPendingPublicationAndExternalCancellationCannotCancelIt` |
| 初始空文本不写，已有快照后清空应覆盖 | `emptyBeforeFirstCaptureDoesNotSaveButEmptyAfterOfferReplacesOldText` |
| 清空后关闭不复活旧文本，清空后新编辑正确排序，删除只影响目标 | `clearCancelsOldSnapshotsAndCloseDoesNotRecreateUneditedText`, `postClearEditIsOrderedAfterClearAndDeleteOnlyInvalidatesTarget` |
| 管理完成通知先排入内部UI状态更新 | `managementCompletionQueuesOwnerStateBeforeConsumerUiAction` |
| 禁用失败保持本次暂停，成功启用才重新捕获 | `failedDisableStaysPausedUntilExplicitSuccessfulEnableRecapturesText` |
| 永久禁用必须有持久化证据 | `successfulDisablePersistsAndAllowsCloseWithoutClaimingSaved` |
| 普通失败保留旧检查点，不循环重试 | `ordinaryWriteFailureKeepsCheckpointAndOnlyRetriesOnRequest` |
| 结构性失败在UI回调前阻断后续写入 | `structuralFailureCancelsOtherWritesBeforeUiProcessesAnyCallbacks` |
| 捕获失败脱敏且不触及存储 | `captureFailureIsSanitizedAndDoesNotTouchStorage` |
| 部分清理失败返回实际幸存记录，管理操作互斥 | `partialManagementFailureReturnsActualSurvivorsAndRejectsOverlap` |
| 启动/刷新清理保护全部已打开ID | `startupAndRefreshPruneExpiredWhileProtectingAllOpenIds` |
| 拒绝调度仍结算flush，关闭释放锁 | `rejectedWriterSettlesFlushAndShutdownStillReleasesLock` |
| 启动部分清理失败可见记录且不宣称成功 | `startupPartialPruneKeepsActualSurvivorsVisibleWithoutSuccessClaim` |
| 恢复检查点及外部禁用不隐式写入 | `restoredCheckpointAndExternalDisableNeverTriggerImplicitWrite` |
| 退出排空已接受写入并阻止新捕获 | `shutdownDrainsAcceptedSaveAndPreventsNewCaptureWhileCallbacksArePending` |
| 初始化失败及UI所有权约束 | `openFailureNeverClaimsSavedAndOwnerThreadIsEnforced` |
| 新配置父目录在后台任务执行时创建 | `publicFactoryCreatesFreshParentOnlyWhenBackgroundWorkRuns` |

## 基线与首轮证据

新工作树基线：强制非headless完整回归，exit0、38秒；138 suites / 1216 tests / 1213 passed / 0 failures / 0 errors / 3 live skipped。

首轮代码`97dab37`：

- RED：`gradlew.bat test --tests com.datacube.config.SqlDraftCodecTest --rerun-tasks --no-daemon --console=plain`，编译成功后19项全部失败（无行为stub）。
- 同命令GREEN：exit0、17秒；19项通过，无跳过。
- 完整强制非headlessGREEN：exit0、31秒；139 suites / 1235 tests / 1232 passed / 0 failures / 0 errors / 3 live skipped。主代理另行读取XML核对总数与跳过名称。
- 跳过：`RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle`、`SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas`、`SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas`。
- 基线已有`SqlEditorResultFilterContractTest` unchecked-operation编译说明仍存在；未修改无关测试。

完整回归命令（恢复原环境）：

```powershell
$draftPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$draftPreviousJavaOptions -Djava.awt.headless=false".Trim()
    .\gradlew.bat test --rerun-tasks --no-daemon --console=plain
    $draftTestExit = $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $draftPreviousJavaOptions }
exit $draftTestExit
```

## 审查与纠偏（P1.1已关闭）

任务审查范围`1af69b8..97dab37`初评Approved、1项Minor：连接ID和Schema的非法代理字符测试缺失。主代理对照完整计划检查当前工作树，额外发现Important：`writeText`漏掉编码前长度检查，超限大字符串仍可能先分配UTF-8缓冲区。首轮19项通过不能证明这一内存边界。

修复按原计划恢复完整代码/断言，并新增受限48MiB JVM测试32MiB合成字符串：退出码44代表编码先分配导致OOM，42代表测试输入本身分配失败（不能作为有效RED），0代表在分配编码缓冲区之前显式拒绝。修复、实际RED/GREEN及复审证据完成后追加于此。恢复的断言和新探针是首轮实现之后添加，不追溯计入原19项RED。

修复前新增回归RED已观察：focused Gradle编译成功、exit1，20 tests / 1 failure / 0 errors / 0 skips。唯一失败为`oversizedTextIsRejectedBeforeAllocatingEncodingBuffer`，主代理读取XML确认`expected: <0> but was: <44>`，不是样本分配失败42。该证据确认原代码存在编码前额外分配；随后才恢复生产长度检查。GREEN与复审尚待记录。

修复提交`4548dd6`：恢复原计划的编码前长度检查、完整断言与可读代码。focused GREEN exit0、20秒、20 tests / 0 failures / 0 skips；完整强制非headlessGREEN exit0、36秒、139 suites / 1236 tests / 1233 passed / 0 failures / 0 errors / 3原live skips，环境已恢复。主代理独立核对XML；两份生产文件与计划代码块逐字匹配，测试除一处空行外代码一致。复审范围`966003d..4548dd6`已Approved，0 Critical/Important/Minor，无剩余待核实项。

主代理在提交`4548dd6`上再次执行完整强制非headless回归：exit0、29秒、8 tasks全部执行；139 suites / 1236 tests / 1233 passed / 0 failures / 0 errors / 3相同live skips。环境恢复；4个新增文档相对链接检查通过。P1.1格式任务完成，不代表P1整体恢复功能完成；下一步执行独立文件边界计划。

## P1.2文件边界（已完成）

提交`045a5dd`只包含SqlDraftDirectory及对应测试。编译成功后stub RED17项全部失败；实现后focused目录+格式GREEN exit0、27秒。完整强制非headlessGREEN exit0、30秒，140 suites / 1253 tests / 1250 passed / 0 failures / 0 errors / 3原live skips；原JAVA_TOOL_OPTIONS已恢复。主代理另行核对XML总数、17项文件测试均通过，并逐字核对生产/测试代码与计划一致。

实际运行（非跳过）：独立Java子进程抢锁/释放后成功、Windows符号链接拒绝、大小写别名保护。原子移动不支持、写入失败、清理失败、目标变化与目录条目上限也已通过。审查范围`0eb3957..045a5dd`已Approved，无任务内阻塞问题；已披露的基线unchecked编译说明作为非阻塞后续事项保留给整体审查，不额外修改无关测试。尚未实现SqlDraftStore策略及应用保存/恢复入口。

## P1.2存储策略（已完成）

提交`82c54b1`只包含SqlDraftStore及对应测试。编译成功后RED11项全部失败，主代理实际读取XML核实；完整实现后的Store/Directory/Codec focused回归exit0，完整强制非headless回归exit0，141 suites / 1264 tests / 1261 passed / 0 failures / 0 errors / 3相同live skips，环境恢复。主代理独立读取最终XML，并逐字核对两份文件与完整计划代码块一致。

已验证严格启停、有效草稿重开、异常设置/未知文件保留、100条与32MiB边界、7天过期规则和打开草稿保护；尚未运行应用内自动保存/恢复。冻结审查范围`3192384..82c54b1`（另含控制器保存状态计划文档）已Approved，无Critical/Important；Minor保留至整体审查：为clear/prune的部分删除失败补充定向注入测试。审查标注的应用启动/管理页调用prune并传入全部打开ID，属于后续整合验收项，不在同步Store内伪称已完成。基线unchecked编译说明仍保留，不宣称构建输出零警告。

主代理在未改变生产代码的`92091b2`上独立执行完整强制非headless回归：exit0、37秒、8 tasks执行；XML141 suites / 1264 tests / 1261 passed / 0 failures / 0 errors / 3原live skips。环境已恢复，7个文档相对链接和diff whitespace检查通过。

## P1.3保存状态（已完成）

提交`5da7b9c`只包含SqlDraftSaveState及对应12项纯状态测试。实施代理记录编译后stub RED12/12失败，focused GREEN exit0；随后完整强制非headless回归exit0，142 suites / 1276 tests / 1273 passed / 0 failures / 0 errors / 3原live skips。主代理读取最终XML并逐字核对生产/测试代码与计划一致；主代理未在覆盖前单独读取本次RED XML，因此RED来源为实施报告，不追溯声称独立验证。报告总数与跳过数正确，实施代理短摘要的“1276/1276”不能理解为全部通过。

已覆盖1秒静默、10秒持续输入、写入期间新编辑、旧成功/失败回调、同revision重试的attempt、清空/暂停失效和时钟边界。这只是无SQL/无线程/无I/O的状态契约；实际异步队列、存储屏障和恢复界面尚未实现。独立审查范围`8c85c51..5da7b9c`已Approved，0 Critical/Important/Minor；审查列出的协调器串行化、计时器替换、结构性失败和恢复零DB访问继续作为整合验收项，不以此纯模型替代。

## P1.3有界队列（已完成）

提交`eaebe8c`只包含SqlDraftWriteQueue及对应11项测试；编译成功后的stub RED11/11失败，主代理读取当时XML核实。实现后的草稿focused回归exit0，71 tests / 71 passed / 0 failures / 0 errors / 0 skipped；完整强制非headless回归exit0，143 suites / 1287 tests / 1284 passed / 0 failures / 0 errors / 3相同live skips。主代理读取最终XML、核实3项名称，并逐字核对代码/测试与计划一致。报告随后补齐明确的跳过名称与提交号，未修改源代码或重跑测试冒充新实现。

覆盖同ID一千次合并、独立ID顺序、清空/单条删除取消、运行中写入后的屏障顺序、异常Future结算、执行器拒绝与排空关闭；真实临时Store也验证清空后不会恢复被取消的旧排队文本。这里的可控交错不是跨线程压力或完整FX应用验证。独立审查范围`bb01976..eaebe8c`已Approved，0 Critical/Important；应用级协调器及UI仍未实现。审查提出的两项Minor为补充inline执行器/空闲后第二轮调度测试、继续准确披露原有编译说明/Gradle信息，保留给整体审查跟进。

主代理随后在`eaebe8c`同一生产代码上独立重跑完整强制非headless回归：exit0、39秒、8 tasks执行，XML143 suites / 1287 tests / 1284 passed / 0 failures / 0 errors / 3原live skips；环境恢复。8个文档相对链接与diff whitespace检查通过，原unchecked说明仍存在。

## 下一整合阶段与保留事项

- 应用级协调器必须把FX快照、计时器、generation/revision、单写者和清空/禁用屏障真正接通，并覆盖初始化/禁用持久化失败、结构性失败立即暂停、关闭刷新与锁释放。底层Store的格式/容量/偏好已有独立证据，不代表这些调用路径已经实现。
- 恢复UI必须显式恢复原文，按稳定ID+类型匹配且首次执行前复核，证明恢复/补全文本赋值零数据库调用；重复恢复只聚焦。实际调用计数不能由旧的source-text测试替代。
- 整体审查需再次处理非阻塞测试补强：Store部分clear/prune删除失败；Queue inline执行器及空闲后第二轮调度。基线unchecked说明继续披露，不把无关清理混入当前功能。
- P1还需合成首次启动、重启/异常退出、桌面状态、完整回归及集成审查；通过后才按授权本地合并main。P2/P3及发布门槛也未关闭。

## Requirement | Evidence

| Requirement | Evidence / 当前边界 |
| --- | --- |
| 精确格式、Unicode/空白、独立字节夹具 | `writesExactVersionOneBytesAndReadsIndependentFixture`；首轮通过 |
| 空串与null元数据、稳定连接身份 | `distinguishesNullMetadataEmptyMetadataAndEmptySql`、`retainsIdentityAcrossSupportedTypesWithoutNameMatching`；首轮通过 |
| SQL/元数据UTF-8容量边界 | `sqlByteLimitRejectsOnlyAboveBoundary`、`everyMetadataFieldUsesUtf8ByteLimit`；修复GREEN，遗漏断言已补回 |
| 有界输入、损坏格式、尾部数据拒绝 | `maximumCombinedPayloadIsAcceptedAndWholeFileLimitIsBounded`、`rejectsBadHeadersEveryTruncationAndTrailingData`、`rejectsInvalidLengthsBeforeReadingPayload`；首轮通过 |
| 身份、类型与UTF-8/UTF-16拒绝 | `rejectsInvalidIdentityTypeAndNullSqlOnWire`、`rejectsMalformedUtf8AndUnpairedSurrogatesWithoutSubstitution`；修复GREEN，ID/Schema代理字符用例已补回 |
| 日志/异常不暴露SQL或连接详情 | `valueValidationAndDiagnosticsNeverExposePrivateText`；首轮通过 |
| 编码前内存上限 | `oversizedTextIsRejectedBeforeAllocatingEncodingBuffer`；修复前RED退出44，修复后GREEN退出0 |
| 文件写入/替换/重开、关闭保留锁文件 | `SqlDraftDirectoryTest.publishesReopensReadsAndKeepsLockFile`；GREEN |
| 同进程及跨进程单写者 | `secondWriterFailsWithoutBreakingFirstAndCloseIsIdempotent`、`operatingSystemLockRejectsAnotherProcessUntilClose`；GREEN，实际子进程运行 |
| 失败保留旧文件、临时文件清理、目标变化 | `unsupportedAtomicMoveKeepsOldFileAndCleansTemporary`、`failedWritePreservesOldFileAndCleanupFailureIsVisible`、`changedTargetIsNotOverwrittenAfterTemporaryWrite`；GREEN |
| 字节/目录枚举边界、文件名/符号链接保护 | `readAndPublishRejectOversizeWithoutTruncating`、`scanHasExactBoundAndDoesNotDeleteUnknownFiles`、`rejectsNamesOutsideOwnedFiles`、`rejectsCaseAliasesWithoutOverwritingOrDeletingExistingBytes`、`rejectsSymlinksWithoutReadingWritingOrDeletingTheirTargets`；GREEN，无符号链接跳过 |
| 精确草稿重开、同文本独立ID、空草稿 | `SqlDraftStoreTest.savesDistinctIdsReplacesExactlyAndRecoversAfterReopen`；GREEN |
| 严格启停与损坏偏好保护 | `disableIsPersistedExactlyAndKeepsRecoverableDrafts`、`invalidPreferenceNeverDefaultsOnOrHidesValidDrafts`、`atomicFailureKeepsOldDraftAndPreference`；GREEN |
| 总容量与旧版本保留 | `countLimitAllowsReplacementButNeverEvictsOtherDrafts`、`totalByteBoundaryUsesPublishedBytesAndRetainsOldVersion`、`invalidNewSnapshotNeverReplacesLastSuccessfulBytes`、`externallyOverfullDirectoryIsPreservedAndCanStillPersistDisable`；GREEN |
| 过期规则与异常条目保护 | `expiryUsesEmbeddedTimeAndPreservesOpenFutureAndInvalidEntries`、`corruptUnknownAndMismatchedFilesArePreservedWithValidNeighbors`、`unreadableOversizeEntryDisablesSavingWithoutHidingNeighbor`；GREEN |
| 静默/持续输入保存期限 | `SqlDraftSaveStateTest.idleDeadlineMovesWithInputAndOnlyPublicationMarksSaved`、`continuousInputCannotPostponeCapturePastTenSeconds`；纯状态GREEN |
| 旧回调与重试失效 | `inputDuringPublicationStartsNewWindowAndRejectsOldSuccess`、`oldFailureAndRepeatedCompletionCannotOverwriteNewResult`、`ordinaryFailureWaitsForExplicitRetryWithANewAttemptTicket`；纯状态GREEN |
| 清空/暂停后的状态规则 | `clearInvalidatesTicketsAndCloseCannotResurrectUneditedText`、`disableCancelsPendingAndEditsDoNotImplicitlyResume`、`unavailableRequiresOwnerRecoveryAndCanResumeWithoutSavingEmptyText`；纯状态GREEN，不代表磁盘屏障 |
| 待写快照合并与不同ID顺序 | `SqlDraftWriteQueueTest.oneThousandPendingVersionsRetainOnlyLatestAndSettleSupersededFutures`、`independentIdsSurviveAndNewestReplacementOccupiesCurrentTail`；GREEN |
| 取消/屏障与正在写入的顺序 | `clearCancelsPendingAndPostBarrierSaveCannotMoveBeforeAction`、`targetedDeleteKeepsOtherIdsAndSerializesNewTargetSnapshotAfterIt`、`clearDuringRunningWriteWaitsForItAndCancelsOnlyPendingVersion`；可控交错GREEN |
| 排空、执行器拒绝与异常结算 | `closeDrainsAcceptedJobsAndRejectsNewJobsWithoutClosingExternalExecutor`、`rejectedExecutorSettlesQueueAndDoesNotLeakItsErrorMessage`、`unexpectedWriterErrorStillSettlesSaveAndDrainFutures`；GREEN |
| 真实文件清空后旧排队文本不再写回 | `isolatedRealStoreClearCannotResurrectOldQueuedText`；隔离Store GREEN，非完整编辑器流程 |
| 真实自动保存、关闭/清空/禁用并发顺序 | P1.4实际FX/临时Store/应用timer测试已通过；最终桌面及重启验收仍待完成 |
| 离线恢复零DB调用、连接身份安全 | 生命周期代码分析完成，尚未实现/验收 |
| 重启、异常退出、桌面可见状态 | 尚未验收 |

未报告覆盖率百分比；没有用真实数据库跳过项或未实现界面充当通过证据。

## P1.4 编辑器自动保存接入（本任务已完成）

实施基线：`8d81dc4`，独立 worktree `codex/sql-draft-recovery`。本轮主代理强制非 headless 完整基线回归 exit0、46秒、8 tasks执行；XML144 suites /1307 tests /1304 passed /0 failures /0 errors /3原live skips。与此前协调器结果分别记录，不把基线通过写成新界面已通过。

完整实施计划：[编辑器接入](../plans/2026-08-30-sql-draft-editor-integration.md)。主代理已独立读取新增测试 RED XML：`SqlEditorDraftIntegrationTest`12项全部因缺少bindDraft失败；`SqlDraftUiTest`1项因缺少应用owner类失败；0 errors/0 skips。补全弹窗的冻结旁路另需行为RED，不由缺方法RED代替。

| 用户边界 | 本轮实际通过的行为测试 |
| --- | --- |
| 原文、Schema、查询状态互不覆盖 | `autoSavePreservesRawTextSchemaAndIndependentQueryStatus` |
| 初始空编辑器不落盘 | `newEmptyEditorDoesNotCreateCheckpoint` |
| 初始化赋值后安装订阅、恢复句柄保持干净 | `historyInitializationQualifiesButRestoredCheckpointStartsClean`；仅句柄初始化，不是完整恢复入口证据 |
| 最后快照期间冻结文本、格式化、注释、补全 | `closingWaitsForLatestSnapshotAndBlocksProgrammaticEditingActions` |
| 最新编辑比排队快照更新 | `closingCapturesEditNewerThanAlreadyQueuedAutosave` |
| 初始化期间关闭拒绝后继续保存 | `initializationRefusesMandatoryCloseAndRetainsSubscriptions` |
| 保存失败保留标签及准确恢复控件状态 | `writeFailureRefusesMandatoryCloseAndRestoresExactFlagsThenSavesNewEdit` |
| 清空后关闭不复活旧文本 | `clearDoesNotResurrectUneditedTextOnClose` |
| 显式禁用不同于保存失败 | `explicitDisableAllowsCloseWithoutClaimingLatestSaved` |
| 阻塞构造中止解除订阅及句柄 | `constructionAbortDetachesHandleAndSubscriptions`；完整managed-tab安装失败路径另验收 |
| 显式连接绑定保存稳定身份 | `explicitAdmissionUpdatesStoredStableConnectionIdentity`；并非恢复零DB调用证据 |
| 取消关闭/显式放弃仅作用本次、保留旧版本 | `interactiveCancelKeepsEditorAndExplicitDiscardKeepsPreviousCheckpoint` |
| 真实应用定时器、writer排空与锁释放 | `SqlDraftUiTest.applicationTimerSavesAndBackgroundShutdownReleasesStoreLock` |

尚未完成恢复管理页、显式无连接恢复、实际provider/session/metadata计数、合成重启/异常退出和桌面验收；这些仍是完整P1合并main之前的门槛。

初版接入提交`f41e1d5`：实施代理报告13项focused GREEN、补全旁路1项行为RED后GREEN、完整1320项回归通过。主代理实际读到了13项初始RED和13项GREEN XML；补全专用RED只引用实施报告，不追溯声称独立读取。

任务审查要求修复关闭期间的回调丢失、草稿保护前的关闭决策对话框、源代码与测试可读性，并补齐弹窗隐藏/默认取消。按用户常规设计授权，采用“先完成草稿保护，再进入原事务决策”的顺序；不改变commit/rollback语义。对应[修订计划](../plans/2026-08-30-sql-draft-editor-review-amendment.md)。

修复前新增断言RED由主代理实际核实：16项编辑器集成测试中5项失败、0 errors/0 skips。分别为结果回调未到达、过早出现关闭决策对话框、交互/强制关闭时补全弹窗仍显示，以及取消按钮非默认。随后主代理读取focused GREEN XML：16项编辑器+1项真实timer测试+5项原Pane lifecycle+13项原Session contract均通过（35项，0失败/错误/跳过）。该GREEN是修复过程证据，最终提交/全量/复审另记。

主代理在初版`f41e1d5`上再次强制非headless完整回归：exit0、46秒、8 tasks全部执行，XML146 suites /1320 tests /1317 passed /0 failures /0 errors /3相同live skips，环境恢复。这是初版历史证据，不是后续修复的通过结论。

修复提交`89f6f00`后，主代理独立完整强制非headless回归exit0、37秒、8 tasks全部执行；XML146 suites /1324 tests /1321 passed /0 failures /0 errors /3原Redis/Oracle/PostgreSQL live skips，环境恢复。复审正在核实此前全部发现；当前自动化成功尚不代表复审通过，更不代表恢复管理页或完整P1已经验收。

最终窄修复`24d5e42`补齐测试断言失败时的finally取消及discard安全默认断言，去除重复补全guard。此项是测试清理/行为保持整理，没有新增产品行为RED，不虚构red-green证据。独立复审Spec compliant / Approved，此接入任务此前发现全部关闭。主代理最终完整强制非headless回归exit0、36秒、8 tasks全部执行；XML146 suites /1324 tests /1321 passed /0 failures /0 errors /3相同live skips。原unchecked编译说明仍存在，环境恢复。

恢复factory另有[完整计划](../plans/2026-08-30-sql-draft-offline-editor.md)，进度见下节。该任务与恢复管理页/重复恢复/重启验收分别记录，不能将本节自动保存完成解释为P1整体完成或已合并main。

## P1.4 离线恢复factory（本任务已完成）

2026-08-31继续执行，基线`3e7fb7b`；提交`4087945`仅包含五个源代码/测试文件。下列7项行为测试已通过，独立任务差异审查Spec compliant / Approved，0 Critical/Important：

| 边界 | 指定实际行为测试 |
| --- | --- |
| 匹配原连接仍然离线；文本、补全、Ctrl-click、全局切换和关闭 | `matchingRestoreKeepsExactTextAndAllPassivePathsOffline`：真实FX控件、真实ConnectionManager、拒绝网络的provider/session/metadata计数 |
| 删除原ID不能用同名或当前连接替换 | `deletedIntentCannotFallBackToGlobalOrSameName` |
| 类型改变拒绝、同ID同类型更新采用最新快照、准入后会话固定 | `changedTypeIsRejectedButCurrentMatchingSnapshotIsAdmitted`：实际创建synthetic session，无SQL执行或网络 |
| 重新选择仅改变意图，执行准入前再校验 | `explicitReplacementIsIntentOnlyAndIsRevalidated` |
| 目标缺失时继续编辑保存不丢原身份/Schema | `savingEditedMissingTargetRetainsOriginalIdentityAndRawSchema`：临时真实Store写回 |
| Schema编辑不改写未编辑SQL的原始换行 | `schemaOnlyEditPreservesRecoveredOriginalLineEndings` |
| 普通构造兼容及CRLF高亮 | `normalBoundConstructorStillOwnsItsEagerSession` |

Counter边界：session计数来自真实ConnectionManager调用provider.sqlRunner的构造路径；network计数为拒绝连接工厂访问，不是已打开socket数。恢复显示使用RichTextFX的LF段落，未经SQL编辑的持久化原文保留原CRLF/CR；不将显示LF声称为逐字节相同。factory测试不代表最终管理页按钮、安装失败、重复标签、重启或桌面已通过。

首轮可编译RED exit1，主代理在GREEN覆盖前实际读取XML：7 tests /7 failures /0 errors /0 skips，时间2026-08-30T16:04:19Z。六项恢复测试因显式factory stub失败；普通构造测试失败为未挂Scene/CSS就lookup控件的测试夹具NPE，不能计作产品CRLF缺陷。计划/brief已更正为读取实际editorArea字段并增加finalizer；后续GREEN、完整回归和复审另行记录。

最终夹具选择先安装Scene/CSS/layout再使用lookup（主控允许的另一种修正），而不是字段方案；计划/brief已同步，保留真实CodeArea文本断言及finalizer。实施报告focused GREEN和完整1331项通过。主代理随后在`4087945`独立重跑完整强制非headless回归，exit0、37秒、8 tasks执行；XML147 suites /1331 tests /1328 passed /0 failures /0 errors /3原有live skips，环境恢复。

当前完整XML中上述focused五类合计41项均通过、0跳过（recovery7 + editor16 + owner1 + session contract13 + admission4）。三个原有跳过名称保持为：`RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle`、`SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas`、`SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas`。原unchecked编译说明仍存在。管理页、实际执行按钮、重复恢复、重启和完整P1合并门槛仍未完成。

独立审查核实两处session立即归属与缺失连接拒绝路径；没有扩大验证范围。非阻塞Minor纳入最终P1审查：补充孤立CR及仅更换连接后保存的原始SQL换行组合。当前CRLF/LF、Schema-only和实际SQL编辑分别已有通过证据，不以此宣称所有组合均覆盖。

## P1.5 草稿管理与标签恢复（本任务已完成）

2026-08-31，完整任务基线`ca91d07`，源代码提交`ffb2ba9`，严格13个源码/测试文件。增加SQL历史旁的“SQL 草稿”入口、显式预览和恢复、连接重选、普通及恢复标签UUID定位、删除/清空/启停与真实失败状态。管理页关闭只解除观察，不停止应用writer。实现计划见[草稿管理](../plans/2026-08-31-sql-draft-manager.md)。

主代理独立完整强制非headless回归session69717：exit0、40秒、8任务全部执行。实际XML149 suites /1358 total /1355 passed /0 failures /0 errors /3原有live skips。新增manager18和recovery9共27项全部通过且没有新跳过；原unchecked编译说明仍存在。

新增行为覆盖显式选择后完整只读预览、LF/CR/CRLF逻辑行与原始记录分别断言、管理忙期间事件guard、默认取消、部分清理实际幸存项、启停失败准确状态、晚到回调、重复定位、关闭拒绝与成功释放、真实安装失败后的abort、显式重选连接不产生provider/session/metadata/network调用。连接重选测试保存原始CRLF及孤立CR，补齐上节对应组合。AppShell入口目前仅源码/编译核实，不能冒充桌面验收。

测试过程保留真实问题：首次缺API编译失败后曾过早写实现，不能当作行为RED。按要求退回可编译stub；manager15项UOE由主代理观察。第一次recovery9项中8项是Fixture.ready超时，判为无效RED并修正前置观察器。修正后主代理实际读取2026-08-30T16:41:24.841Z的9/9 UOE、0 errors/skips，无readiness超时。另一个新增换行回归3项中孤立CR失败由实施代理观察，主代理只读到后续GREEN，不追认独立RED。修复仅规范TextArea显示，不改存储原文。

独立任务审查正在进行。下一门槛为[独立进程验收](../plans/2026-08-31-sql-draft-process-acceptance.md)、[桌面及P1完整清单](2026-08-31-sql-draft-acceptance-checklist.md)、打包与整分支审查，完成前不本地合并main；无推送、tag或发布。

任务审查发现原计划的resource-only abort没有解除应用设置监听，失败的恢复编辑器可能被继续引用。按常规设计自主推进授权完成[修订](../plans/2026-08-31-sql-draft-manager-abort-fix.md)：提交`8ecd521`让两处abort回调在既有后台线程关闭资源，再等待FX finalizer；资源失败仍尝试释放监听，错误不被吞掉。主代理修复前读取真实RED：9项中上述初始化/安装失败2项uiFinalized断言失败；修复后56项覆盖测试通过。最终独立复审Spec compliant / Approved，0 Critical/Important。主代理最终完整回归session30832 exit0、40秒、8任务，XML149 suites /1358 total /1355 passed /0 failures/errors /3原live skips，环境恢复。此管理任务完成不代表P1进程/桌面/打包/整分支门槛完成。

## P1.6 独立进程与桌面验收（进行中）

2026-08-31，受控验收启动器与Gradle init脚本均为`.superpowers/sdd/`忽略产物，不进入发行包。实施代理在`f353d12`执行`verifySqlDraftProcesses`，exit0、28秒；主代理已检查完整脚本和独占目录的实际日志标记。八个顶层进程结果为normal0、restore0、abrupt37、restore0、disable0、verify-disabled0、lock-holder0、restore0；嵌套locked-probe0发生于写锁持有期间。异常退出只在确认检查点后触发，未保存尾部不被误当作可恢复内容。真实FX路径的provider/session/metadata/network探针为0，正常与异常退出后锁均释放，禁用偏好跨进程保留。

首轮保留目录：`C:/Users/hetia/AppData/Local/Temp/datacube-draft-process-15294609357667274564`。日志有无Stage的非headless测试夹具产生的JavaFX CSS查找/转换警告，包括caught Paint转换ClassCastException；无测试断言失败，但不称输出纯净。它不是桌面视觉验收。主代理随后独立重跑进程验收与`jpackageImage`，结果另记。

实际AppShell隔离启动使用`C:/Users/hetia/AppData/Local/Temp/datacube-draft-ui-3135239206dc40a9887289e64f6e85ed`、标记文件、启动前独占user.home和合成缺失连接草稿。JavaExec输出PID4136和正确目录，窗口标题“DataCube SQL草稿隔离验收”被Computer Use返回。启动器没有调用DataCubeFx外层公共更新检查，因此不代表完整发布入口离线验证。

桌面观察受系统阻断：首次capture报`IGraphicsCaptureItemInterop.CreateForMonitor ... 0x80070057`；按技能重新定位并重试一次后报`GetCursorPos failed: 拒绝访问 (0x80070005)`。未获取有效截图/控件树，未点击或输入；停止桌面路径并通知维护者恢复可交互会话。随后核对PID4136命令行包含本轮launcher、desktop模式和独占目录，只终止该可丢弃JVM，保留所有临时文件。该清理导致JavaExec退出-1、Gradle exit1（2m17s）；不是正常退出验收，也不是应用断言失败。

入口可发现性、实际明暗主题、管理页与恢复编辑器的桌面观察仍为**未验收**。目前不合并main，不将工具限制转写为产品通过；其他构建和审查继续进行。

主代理独立命令`./gradlew.bat -I .superpowers/sdd/draft-acceptance.init.gradle verifySqlDraftProcesses jpackageImage --no-daemon --console=plain`，session61680，exit0、56秒、17任务（9执行/8 up-to-date）。八个顶层进程退出码和PROCESS_ACCEPTANCE_PASS由主代理直接读取；新独占目录`C:/Users/hetia/AppData/Local/Temp/datacube-draft-process-8454722275964971643`保留。`jlink`及`jpackageImage`实际执行成功；有当前JDK关于jmods/java.base与JEP493的构建提示。构建成功不代表安装、升级或打包程序桌面运行通过。

随后只读检查实际镜像：`jimage list build/jpackage/DataCube/runtime/lib/modules` exit0，包含本轮SqlDraft生产类，没有SqlDraftAcceptanceLauncher或DraftManagementProbe匹配项。运行时modules文件SHA-256为`8E8C3EAC994659336CE6E878924473023B2AA270EF4F42051D182C02D54F8938`；cfg主入口为`com.datacube/com.datacube.DataCubeFx`且没有测试user.home覆盖。默认版本`3.0.0`仅为本地构建配置，不是新发布版本。未启动此发行入口。

## P1 整分支审查与反馈修订

独立整分支审查使用冻结范围`0c4ecb9..6d52bfc`（54提交，812535字节差异包），结论Ready No。两项Important为：保存失败被折叠为通用WRITE/不可用，遗漏容量、输入限制及CLEANUP敏感SQL临时文件风险；编辑器清空成功只判断succeeded，未展示snapshot中仍受保护的损坏/未知文件。修复计划见[失败反馈修订](../plans/2026-08-31-sql-draft-failure-feedback.md)，任务基线`ce5acd0`，由单独实施代理处理。此处为发现与执行记录，不代表修复已验证。

按已有自主设计授权统一CLEANUP语义为检查修复本机目录后重启，本会话保持不可用，无自动重试/重新启用。普通容量/输入失败提示先复制文本另存；清空提示不得宣称受保护文件已删除。所有提示只用固定安全分类，不显示异常原文、SQL或路径。

非阻塞事项保留：管理列表当前缺失/类型改变提示可在桌面验收阶段评估，恢复后的编辑器已做稳定ID/类型验证且保持离线；Store实际删除循环故障注入及Queue inline/第二轮空闲调度测试建议未冒充已有覆盖。旧compiler/CSS/JDK提示继续披露。源码改变后完整回归、进程和镜像证据须重新生成；桌面限制与代码审查问题分别跟踪。

反馈修订首轮RED：编译成功后43 tests /6 failures /0 errors /0 skips，Gradle exit1。主代理在GREEN前独立读取2026-08-30T17:24:24.876Z起三份XML；Coordinator20项中CLEANUP分类1失败，新反馈6项中四种分类均为null而失败，编辑器17项中保护文件残留提示1失败。生产差异只有编译接口形状，尚无行为修复。已有重试/旧revision行为用例通过，未将其误报为新RED；测试输出的CSS警告及对话框helper清理NPE另外排查，不当作功能RED证据。

源码修订`7d17728`严格8个源码/测试文件。实际对话框helper在关闭后访问已脱离的Window导致清理NPE，最小修复为提前保存Window并将清理异常传播到测试调用者；不改变产品确认逻辑。定向GREEN首次70/1为旧“失败”文案断言；第一次误改CAPTURE仍失败，核对4097字符Schema实际走Store INVALID_DRAFT后，改为同时断言该类型及固定安全提示，保留所有强制关闭拒绝/flags/资源/后续保存断言。此过程不冒充额外产品RED。

主代理独立最终完整回归session50022：`test --rerun-tasks --no-daemon --console=plain`，仅本次命令JAVA_TOOL_OPTIONS追加非headless，随后恢复；exit0、41秒、8任务全执行。实际XML150 suites /1365 tests /1362 passed /0 failures /0 errors /3原有live skips（名称同上），新增7项无跳过。原unchecked编译提示仍存在。进程与打包复验单独记录，桌面仍未验收。

源码7d17728跨进程/打包复验session21608：同前述组合命令，exit0、52秒、17任务（9执行/8 up-to-date），jlink/jpackageImage实际执行。8个顶层子进程和锁内第二实例均通过，受控异常进程退出37符合预期，独占目录`C:/Users/hetia/AppData/Local/Temp/datacube-draft-process-12929717576398861958`保留。root直接读取检查点及locked-probe0日志。新镜像modules SHA-256为`94C5724987520D1982B71881039DEB60F248B86184A392BB9460C49FE7AD8E9C`；jimage检查有草稿生产类而无验收launcher/probe，cfg仍为真实DataCubeFx入口，无测试user.home覆盖。默认3.0.0不代表发布；原JDK/JEP493提示仍存在，未启动/安装此发行入口。

最终复审冻结范围`6d52bfc..694ccb9`（5提交、105272字节），独立审查完整差异、报告和修订计划后结论Spec-compliant / code review approved；两项原Important均关闭，无新增Critical/Important。清单末尾原“尚未执行”的历史文字已同步为真实进程验收状态。非阻塞管理行提示及低层额外测试建议继续保留。整体合并门槛仍为No，仅因必需桌面/主题/入口发现性验收受限；不将自动化证据替代桌面证据。按分支收尾流程保留工作树，main仍为0c4ecb9，未合并/推送/tag/发布。

## 2026-08-31 桌面会话恢复后的实际验收

此节更新当前状态；前述访问拒绝为历史失败记录，不再是当前环境阻塞。恢复后的任务重新开始阻塞审计，07:06 起受支持的 `@oai/sky` 成功取得截图、控件树并执行逐步交互，没有调整权限或使用其他自动化绕过限制。

源码仍为`7d17728`，当时分支文档HEAD为`d1b29b9`。主代理重新完整检查隔离启动器和Gradle脚本后，创建唯一目录`C:/Users/hetia/AppData/Local/Temp/datacube-draft-ui-9902939ae9cc4a1db847d93b54abadb9`及测试标记；在JVM启动前设置独占user.home。使用真实AppShell、ThemeManager及正常关闭流程，不调用DataCubeFx外层更新检查。这是实际AppShell隔离验收，不是完整发行入口离线启动验收。

命令两次相同：

```powershell
.\gradlew.bat -I .superpowers/sdd/draft-acceptance.init.gradle runSqlDraftDesktop '-PdraftAcceptanceHome=C:/Users/hetia/AppData/Local/Temp/datacube-draft-ui-9902939ae9cc4a1db847d93b54abadb9' --no-daemon --console=plain
```

第一JVM PID22936、Gradle session52399：

- 顶栏“SQL 草稿”入口可见；首次管理页先显示初始化，完成后1条预置合成记录。未选择时正文空白、恢复/删除禁用，选择后显示中文、缩进与完整逻辑行。
- “删除所选”明确只删除本机恢复记录、不清空编辑器，默认取消；点击取消后记录仍为1。“清空草稿”明确之后新修改仍会保存，默认取消；取消前后文件SHA一致。没有点击任何确认删除或隐私开关。
- 明暗主题管理列表、选择后正文及按钮无重叠；发现暗色空预览提示过暗，列入后续修正，不能据此宣称视觉门槛已全部通过。
- 恢复预置已删除连接草稿：实际编辑器为“未绑定连接”“尚未创建专用会话”，执行/执行计划/提交/回滚禁用，显示重新选择连接提示。没有触发连接或执行操作；零provider计数的证据来自已有自动化探针，不将桌面观察冒充插桩网络统计。
- 无连接时点击普通“新建 SQL”，确实创建独立未绑定空标签。在真实编辑区域输入合成文本 `-- desktop synthetic 草稿\nselect 'draft-only' as note;\n`，观察“草稿待保存”到“草稿已保存于07:12:52”。
- 再开管理页为2条；恢复该普通标签的草稿只聚焦原标签，仍为2个标签而不是3个。此时发现未绑定行拼接出`null`及空`Schema:`，列入显示层修正，不属于记录丢失。
- 经实际标题栏正常关闭，Gradle exit0、9m39s、9任务（1执行/8 up-to-date），未强杀进程。关闭前后两份草稿SHA不变。

第二JVM PID10588、Gradle session14427：

- 使用同一独占目录重新启动，新进程没有自动恢复标签。管理页仍为2条，必须显式选择后才显示完整SQL；恢复新建草稿后只有1个恢复标签，SQL中文及末尾空行可见，仍未绑定且执行/事务按钮禁用。
- 在恢复标签中仅将空Schema输入为合成`desktop_schema`，再次观察待保存到“草稿已保存于07:20:49”，SQL未编辑。
- 实际标题栏正常关闭，Gradle exit0、5m13s、9任务（1执行/8 up-to-date）。随后读取仅本轮合成检查点，UTF-8字节后缀与首次输入（含末尾LF）逐字节相等；Schema更新未改变SQL原文。

文件证据（位于上述隔离目录的`.datacube/sql-drafts/`）：

| 文件 | 检查阶段 | SHA-256 |
| --- | --- | --- |
| `9310c1cf-279a-4d54-aa2b-4178a485ce95.draft` | 清空取消前后、首次正常关闭前后、第二次关闭后均相同 | `901DFF1BE8CF687045A6696E430F9F842B378BC0D2ECCB44EFEC25D662ADFE94` |
| `bc388dad-2b3e-4dab-a7be-8e39cd898a0a.draft` | 首次保存及正常关闭前后 | `D65C8ADF708CFB6725B52068D457C4B85C4AF37B3CD87A9ACDC2D0AECCF5EDE5` |
| 同上 | 第二次仅Schema修改并正常关闭后 | `C3C49D300FC59ED9C4BD456DD68C60854C11EEB04D9EAA16B05E07574F10EAED` |

删除取消没有测量独立的操作前哈希，仅记录UI条数及后续文件存在，不扩大证据。没有访问真实连接、SQL历史、密码或`.testagent/`。两次受控进程均正常结束，临时产物保留。实际桌面首次使用是已有预置合成记录下新建普通SQL；完全空目录首次保存由既有自动化测试覆盖，不混称为空目录桌面验收。

后续只需[局部呈现修正](../plans/2026-08-31-sql-draft-presentation.md)、审查及最终源码复验，再按授权本地合并main。当前尚未合并，不推送/tag/安装/发布。

## 最终呈现修正及桌面复验（源码5e50f21）

`5e50f21`仅修改草稿管理行文案、限定ID的提示色及管理页测试。首次RED为26项/6失败，其中亮色背景类型断言是夹具错误，未冒充产品失败；改为检查实际渐变各色标后，主代理直接核对23:28:41Z XML，4项元数据及暗/亮对比度1.03147/1.76223共6项真实失败，生产代码仍未改动。随后定向26项全部通过。独立任务审查Spec compliant / Approved，无Critical/Important/Minor；实际OS焦点明确由桌面复验补证。

主代理最终组合命令：`gradlew.bat -I .superpowers/sdd/draft-acceptance.init.gradle test verifySqlDraftProcesses jpackageImage --rerun-tasks --no-daemon --console=plain`，session6314，exit0、99秒、18任务全执行。测试强制非headless，实际XML150 suites /1373 total /1370 passed /0 failures /0 errors /3原有live skips；新增8项没有跳过。8个顶层子进程及nested locked-probe通过，异常退出37符合预期，目录`C:/Users/hetia/AppData/Local/Temp/datacube-draft-process-6617288954716498219`保留；主代理直接读取normal/restore/locked-probe成功标记。已有fixture CSS与unchecked提示仍披露。

组合命令携带的`JAVA_TOOL_OPTIONS`使打包插件输出多条`java/javac failed: Picked up JAVA_TOOL_OPTIONS`，尽管构建exit0。为区分环境诊断噪声，单独暂时清除该进程变量后重跑`gradlew.bat jpackageImage --rerun-tasks --no-daemon --console=plain`并恢复变量：session16539，exit0、43秒、14任务全执行，上述噪声不再出现，原JDK/JEP493提示仍存在。没有据此修改构建脚本或系统设置。最终镜像modules SHA-256为`381FAC8D419CCE7CAB899FF20BD96FEA4C18EAFEEF356008A783B574E2B64A3B`；jimage读取成功，含SqlDraft与Manager生产类、不含测试launcher/probe；cfg为真实DataCubeFx入口且无隔离user.home。默认3.0.0不是发布号，未安装或启动完整发行入口。

最终实际AppShell桌面复验使用前述同一独占profile和启动命令：PID29692，session90232。暗色列表显示“未绑定连接”而非null，保留desktop_schema；未选中时仅显示清晰预览提示。点击提示后，实际accessibility.focused_element为`64 编辑 ID: JavaFX87`，截图中提示仍可见。切换亮色、重新打开管理页，实际焦点为`103 编辑 ID: JavaFX143`，提示仍清晰。两种主题选择草稿后完整SQL可读，无重叠；亮色显式恢复后只有1个恢复标签，Schema与SQL保留，未绑定、尚未创建专用会话及执行/事务禁用状态均直接核对。空Schema和未命名连接的分支由实际cell测试覆盖，不冒充本次profile中的桌面记录。

经实际标题栏正常关闭，exit0、4m19s、9任务（1执行/8 up-to-date）；两份草稿SHA仍分别为901DFF1B…FE94与C3C49D30…EAED（完整值见上表），没有新编辑或文件变化。实际焦点验收补齐任务审查中的不可从diff核验项。没有活跃验收JVM或Gradle。此时P1代码/自动化/打包/桌面门槛已满足，最后由整分支审查复核新增差异与证据，再本地合并main并在main回归；发布/远端CI/P0.2其余交互门槛不随之关闭。
