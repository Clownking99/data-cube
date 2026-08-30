# SQL 草稿恢复验收记录

## 范围与状态

工作树：`D:/Projects/朝花夕拾/.worktrees/sql-draft-recovery`；分支：`codex/sql-draft-recovery`；起点：`main@0c4ecb9`。

P1 尚未完成。当前实现为草稿值/格式基础，文件存储、保存调度、恢复界面与重启验收仍在后续阶段；不将格式测试当成用户可用恢复功能。完成整条P1路径后才本地合并，未推送/打tag/发布。

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

## 审查与纠偏（未关闭）

任务审查范围`1af69b8..97dab37`初评Approved、1项Minor：连接ID和Schema的非法代理字符测试缺失。主代理对照完整计划检查当前工作树，额外发现Important：`writeText`漏掉编码前长度检查，超限大字符串仍可能先分配UTF-8缓冲区。首轮19项通过不能证明这一内存边界。

修复按原计划恢复完整代码/断言，并新增受限48MiB JVM测试32MiB合成字符串：退出码44代表编码先分配导致OOM，42代表测试输入本身分配失败（不能作为有效RED），0代表在分配编码缓冲区之前显式拒绝。修复、实际RED/GREEN及复审证据完成后追加于此。恢复的断言和新探针是首轮实现之后添加，不追溯计入原19项RED。

## Requirement | Evidence

| Requirement | Evidence / 当前边界 |
| --- | --- |
| 精确格式、Unicode/空白、独立字节夹具 | `writesExactVersionOneBytesAndReadsIndependentFixture`；首轮通过 |
| 空串与null元数据、稳定连接身份 | `distinguishesNullMetadataEmptyMetadataAndEmptySql`、`retainsIdentityAcrossSupportedTypesWithoutNameMatching`；首轮通过 |
| SQL/元数据UTF-8容量边界 | `sqlByteLimitRejectsOnlyAboveBoundary`、`everyMetadataFieldUsesUtf8ByteLimit`；首轮通过，遗漏断言正在补回 |
| 有界输入、损坏格式、尾部数据拒绝 | `maximumCombinedPayloadIsAcceptedAndWholeFileLimitIsBounded`、`rejectsBadHeadersEveryTruncationAndTrailingData`、`rejectsInvalidLengthsBeforeReadingPayload`；首轮通过 |
| 身份、类型与UTF-8/UTF-16拒绝 | `rejectsInvalidIdentityTypeAndNullSqlOnWire`、`rejectsMalformedUtf8AndUnpairedSurrogatesWithoutSubstitution`；首轮通过但ID/Schema代理字符用例缺失正在补回 |
| 日志/异常不暴露SQL或连接详情 | `valueValidationAndDiagnosticsNeverExposePrivateText`；首轮通过 |
| 编码前内存上限 | `oversizedTextIsRejectedBeforeAllocatingEncodingBuffer`；新增回归，尚未记录修复通过 |
| 文件持久化、单写者、偏好、过期 | 已有完整计划，尚未实现/验收 |
| 自动保存、关闭/清空/禁用竞态 | 尚未实现/验收 |
| 离线恢复零DB调用、连接身份安全 | 生命周期代码分析完成，尚未实现/验收 |
| 重启、异常退出、桌面可见状态 | 尚未验收 |

未报告覆盖率百分比；没有用真实数据库跳过项或未实现界面充当通过证据。
