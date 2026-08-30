# SQL 草稿恢复验收记录

## 范围与状态

工作树：`D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`；分支：`codex/sql-draft-recovery`；起点：`main@0c4ecb9`。

P1 尚未完成。当前已完成草稿值/格式、文件边界、存储策略、纯保存状态、有界写入队列和应用级协调器；编辑器接入、恢复界面与重启验收仍在后续阶段，不将运行时测试当成用户可用恢复功能。完成整条P1路径后才本地合并，未推送/打tag/发布。

设计见[SQL 草稿恢复设计](../specs/2026-08-30-sql-draft-recovery-design.md)。执行计划：

- [P1.1 格式](../plans/2026-08-30-sql-draft-format.md)
- [P1.2 文件边界](../plans/2026-08-30-sql-draft-directory.md)
- [P1.2 存储策略](../plans/2026-08-30-sql-draft-store.md)
- [P1.3 保存状态](../plans/2026-08-30-sql-draft-save-state.md)
- [P1.3 有界写入队列](../plans/2026-08-30-sql-draft-write-queue.md)
- [P1.3 应用协调器](../plans/2026-08-30-sql-draft-coordinator.md)

异步运行时边界另见[运行时整合约束](../specs/2026-08-30-sql-draft-runtime-contract.md)，该文档是后续设计约束，不是实现证据。

仅使用合成数据与隔离测试目录，不读取真实连接凭据或SQL历史，`.testagent/`未读取/修改。

## 当前协调器任务（实现与任务审查完成）

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

恢复factory下一任务另有[完整计划](../plans/2026-08-30-sql-draft-offline-editor.md)，尚未实施。该任务与恢复管理页/重复恢复/重启验收分别记录，不能将本节自动保存完成解释为P1整体完成或已合并main。
