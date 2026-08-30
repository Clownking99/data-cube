# SQL 草稿恢复验收记录

## 范围与状态

工作树：`D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`；分支：`codex/sql-draft-recovery`；起点：`main@0c4ecb9`。

P1 尚未完成。当前已完成草稿值/格式及文件边界，存储策略、保存调度、恢复界面与重启验收仍在后续阶段；不将基础测试当成用户可用恢复功能。完成整条P1路径后才本地合并，未推送/打tag/发布。

设计见[SQL 草稿恢复设计](../specs/2026-08-30-sql-draft-recovery-design.md)。执行计划：

- [P1.1 格式](../plans/2026-08-30-sql-draft-format.md)
- [P1.2 文件边界](../plans/2026-08-30-sql-draft-directory.md)
- [P1.2 存储策略](../plans/2026-08-30-sql-draft-store.md)

仅使用合成数据与隔离测试目录，不读取真实连接凭据或SQL历史，`.testagent/`未读取/修改。

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
| 草稿总容量、严格偏好、过期与异常条目 | SqlDraftStore已有完整计划，尚未实现/验收 |
| 自动保存、关闭/清空/禁用竞态 | 尚未实现/验收 |
| 离线恢复零DB调用、连接身份安全 | 生命周期代码分析完成，尚未实现/验收 |
| 重启、异常退出、桌面可见状态 | 尚未验收 |

未报告覆盖率百分比；没有用真实数据库跳过项或未实现界面充当通过证据。
