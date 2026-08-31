# P2 SQL 工作区恢复验证记录

## 当前范围

P2.1 基础模块和P2.2严格存储已完成，源码提交分别为 `6b0bbe1`、`4611f54`，测试与独立任务审查通过。尚无用户可用的工作区恢复入口。工作分支 `codex/sql-workspace-recovery`，起点 main `7710ecb526d10a22e3fbff65367c50b04e44ed9d`，设计/计划提交 `48faecc`、`eaa8de0`。main 保持不变；无推送/tag/发布。

设计：[P2 工作区恢复](../specs/2026-08-31-sql-workspace-recovery-design.md)。已完成计划：[P2.1 基础模块](../plans/2026-08-31-sql-workspace-foundation.md)、[P2.2 共享锁持久化](../plans/2026-08-31-sql-workspace-persistence.md)。协调与 UI 尚未实施，不以存储单测通过替代这些验收。

## 基线证据

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

### P2.3 接入契约（尚未实施）

- `Snapshot.recordingEnabled` 仅代表工作区偏好；不能直接作为整个运行时允许写入的标志，还要检查草稿保护及共享writer状态。
- 新的同步API只能在共享writer上调用。不得在FX线程执行，也不得重新open相同目录获取第二个owner。
- `SqlDraftCoordinator` 已有清理失败分类与结构性停用：`CLEANUP` 必须使共享writer粘性停用并使排队任务失效。新工作区领域失败尚未接入该分类，不得描述为运行时保护已完成。
- 清空布局/关闭记录都需使旧的排队布局快照失效；恢复开启不能自动覆盖上一次工作区。界面保存状态必须等待实际落盘结果。
- 一般关闭单标签应更新布局；退出要在binding脱离前冻结完整布局。取消或部分失败不得把不完整布局当作成功退出快照。
- 初次读取损坏/未知清单时仍可展示独立草稿；未开始新工作或明确清空时，启动初始化不能写空布局覆盖旧清单。

## 后续集成必须补验的风险

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

## P2 完整验收清单

- [x] P2.2 I/O 故障注入、偏好/清空/未知文件保护、同 JVM 与多 JVM 单写者；25项新测试通过且独立审查零发现。排队清空竞态属下一项。
- [ ] P2.3 活动快照、防抖、失效代次、退出冻结、取消/部分失败/未触及启动不覆盖。
- [ ] P2.4 启动页及草稿页入口、原选中/光标/选择、重复定位、其他标签顺序、部分失败可见。
- [ ] 更名/同名不同 ID/删除/类型变化/不存在 Schema，恢复及切换均为零数据库调用。
- [ ] 非 headless 全量回归、独立进程重启与异常中断恢复。
- [ ] 合成配置真实桌面：关闭单标签 vs 退出、取消退出、明暗/键盘、开关/清空与重启。
- [ ] 正式入口免安装包与源码一致；不夹带测试入口/临时 profile。
- [ ] 整分支审查通过后本地合并 main，再做 main 回归；不隐含远端 CI/推送/发布已通过。

未触及真实用户数据、`.testagent/` 内容和已有 P1 验收目录。P0.2 发布验收及用户反馈仍是独立未完成事项。
