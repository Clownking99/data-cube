# 名称查找读取准入：本地验收账本

日期：2026-09-30。限定本地增量，基线 main `918d7198cb7ec07d509bbce024dd442972889f6b`，独立分支 `codex/schema-object-admission`。实现 `30556d03f5103483fec354b69a4c71bccf8d8fde`，main 合并代码 `50924650da51d0b17892bb89d23172180094a672`。分支和 main 新定向/全量/强制 buildSrc/jpackageImage/镜像审计全部通过，完成本地工程交付；M8 外部验收仍待验，不称发布验收。

## 行为和证据

旧名称查找在窗口关闭后立即释放连接树的引用，Future.cancel 可能早于底层名称读取返回；新窗口可再取得连接。实际 AppShell + mock Connection/MetadataReader 在 PG/Oracle × 重开名称/字段入口四项均证实峰值 2。修复后名称窗口及时关闭，读任务实际返回及 FX 关闭清理前保留占用，两个 Schema 检索菜单禁用并显示“等待读取结束”；结束只恢复入口，不自动读取。

名称窗口内重复“重新读取”有逻辑准入，读取时字段按钮禁用且强制 fire 也拒绝；旧请求结束后才允许明确再读。Pending 区分排队/运行/返回，取消排队任务后其 callable 不能进 loader；任务提交期间关闭会取消随后交出的 Future。运行任务的 finally 另发物理完成通知，不依赖已取消 Future 的 done 或被 scope 抑制的结果回调。FX 完成通知与背景 close 均不等待 JDBC。

只改名称 Dialog/树的局部生命周期，保留已有服务、10001 名称请求上限、确切目标、筛选/复制/迟到回调保护和实际 AppShell 的 SELECT/只读数据/DDL。没有改线程池、FxTaskScope、服务 SQL、事务或写门禁。Submitter 的结果回调表示工作已返回；实际生产 submitter 为已有 scope。README 更新读取/关闭后的等待和明确重试说明。

| 要求 | 精确测试与本轮证据 | 断言 |
| --- | --- | --- |
| 关闭后重开名称/字段 | MetadataSearchShellRoutingTest.closedNameSearchKeepsBothEntriesReservedUntilInterruptedReadActuallyReturns，PG/Oracle 四项，实际 AppShell 合成 FX | 中断已到达但 closes=0；旧峰值 2 → 新峰值 1；菜单等待/禁用、恢复不查询、明确再开成功，资源平衡，写/执行 0 |
| 运行中关闭，Future 取消但读取仍在途 | SchemaObjectSearchLifecycleTest.cancelledFutureCannotDisposeAnUninterruptibleReadOrAffectOtherRunnerWork，FX/后台关闭两项 | 窗口先隐藏、完成通知未完成；其他 runner 任务可运行；旧读取明确释放后才在 FX 完成且无迟到候选 |
| 重复读和内嵌字段入口 | SchemaObjectSearchLifecycleTest.runningReadRejectsReloadAndMetadataEntryUntilPhysicalReturnThenRequiresExplicitRetry | 两次 reload/强制字段 fire 不开新读取；结束不自动读，明确 retry 第 2 次读取 |
| 排队/提交期间关闭 | SchemaObjectSearchDialogTest.closingBeforeQueuedWorkRunsCancelsWithoutCallingLoader；SchemaObjectSearchLifecycleTest.closeDuringSubmissionCancelsQueuedFutureAndPreventsItsLoaderFromStarting | queued Future 取消，完成可释放；迟到 work 调用抛取消且 loader=0 |
| 后台空闲关闭 | SchemaObjectSearchLifecycleTest.backgroundIdleCloseWaitsForFxCleanupWithoutReading | close 返回但 FX 清理前不完成，清理后零读取完成 |
| 致命错误和拒绝 | SchemaObjectSearchLifecycleTest.fatalReadFailureReleasesOwnershipAndKeepsDiagnosticSafe；SchemaObjectSearchDialogTest.rejectedSubmissionIsNotStuckLoadingAndCanRetry | Error 保留传播，入口可再读且不泄漏诊断；拒绝后可明确重试 |
| 旧回调不得替换新快照 | SchemaObjectSearchDialogTest.pendingReloadIsRejectedAndLateCompletedCallbackCannotOverwriteNewSnapshot | 旧读取未结束重复请求被拒绝，完成后可发新请求；旧成功/失败均不得覆盖新候选 |
| 只读/原请求/三动作/字段取消/筛选/复制 | 9 类定向实际重验 | 原功能和请求身份保护不变；程序化 FX 不升级为原生 |

## 新运行与失败

[实际结果](2026-09-30-schema-object-admission-results.json) 包含命令、实际任务、跳过/失败、日志 SHA 和 XML 全清单 hash；[原始清单](evidence/schema-object-admission/manifest.json) 固定 raw bytes。分支记录 HEAD 为基线，测试包含当时未提交源码；五项最终源码 raw SHA/canonical Git blob 固定于 source-snapshot.json。

| 记录 | 实际结果 | 说明 |
| --- | --- | --- |
| name-red-runtime | 4 failed，0 passed | 实际运行复现两条连接重叠，原测试源码/日志/XML保留 |
| name-green-routing | 4/4，0 skipped | 同四项修复后连接峰值 1 |
| name-lifecycle | 8 suites / 173 passed，0 skipped | 首轮生命周期、既有名称/字段/服务行为 |
| branch-directed | 9 suites / 180 passed，0 skipped | 增加提交时关闭、后台空闲关闭后的最终源码 |
| branch-full | 309 suites / 3901 tests：3898 passed、3 live skipped | 新 profile、实际 cleanTest/test，0 failure/error |
| branch-buildsrc | 8/8，0 skipped | --rerun-tasks，实际 buildSrc:test |
| branch-image / branch-image-audit | jpackageImage 成功，类/配置泄漏 0，connectCalls=0 | PG/Oracle 驱动发现；没有载入用户 profile 或凭据 |
| main-directed | 9 suites / 180 passed，0 skipped | 合并代码、新 profile、实际 test |
| main-full | 309 suites / 3901 tests：3898 passed、3 live skipped | 合并代码、新 profile、实际 cleanTest/test，0 failure/error |
| main-buildsrc | 8/8，0 skipped | --rerun-tasks，实际 buildSrc:test，0 failure/error |
| main-image / main-image-audit | jpackageImage 成功，类/配置泄漏 0，connectCalls=0 | 新临时目录驱动探针，只发现 PG/Oracle 驱动，没有载入用户 profile 或凭据 |
| image-comparison | 三项产物 bytes/SHA 与分支一致 | 已验证源码/测试/README/buildSrc/build.gradle 与实现提交无差异 |
| main-evidence-audit / main-staged-evidence-audit | raw hashes、日志/XML清单、源码 canonical blob 和暂存原始字节匹配 | 最终归档 22 份记录、109 份 raw 文件；追加审计记录后用 -Staged -NoRecord 校核，避免自引用循环 |

原强制 reload 替换未结束任务测试，与资源准入约束冲突；改为等旧任务结束后再发新请求，同时保留原 late-success/late-failure 拒绝断言。没有删除旧候选、参数、只读或资源平衡检查，没有把失败或跳过改为通过。

两次全量均单列三项 live 跳过：RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle、SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas 和 postgresqlSafeDeploymentConvergesInDisposableSchemas。测试进程剔除 live 环境，缺少显式目标/凭据或完整环境及写授权，不能计通过。本轮最终定向与 buildSrc 无跳过。历史 SchemaDiffServiceTest 偶发失败根因未证实关闭。没有本轮原生桌面证据，也没有重试此前失败的模态输入操作。

main-full 完成于 21:19:10，main-buildsrc 完成于 21:21:48，main-image 完成于 21:23:18，main-image-audit 完成于 21:28:06（均为本地 +08:00）。强制 buildSrc 已结束后才开始镜像构建。JavaFX/native access、unchecked 与 jlink JEP 493 警告保留在原始日志，不影响此次实际任务成功。

| 分支/main 镜像产物 | bytes | SHA-256 |
| --- | --- | --- |
| DataCube.exe | 595968 | 6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F |
| app/DataCube.cfg | 369 | E53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D |
| runtime/lib/modules | 102374856 | 7CA191D96F390A6B4F708826F380F8A0AAE3B24A9B07D888BA89DCE51364665D |

## 边界和集成

仅 mock、合成 profile、固定名称/查询和独占临时目录；user.home 在创建 AppShell 存储前替换。禁止 .testagent、真实连接/凭据/SQL 历史/业务文件，未 push/fetch/PR/tag/发布/安装更新或联系外部。

分支全量/buildSrc/jpackageImage、审查及原始/暂存证据校核后，已本地提交与 --no-ff 合并 main；合并时整树与实现分支一致。main 用新 profile 全部复验，归档实际 SHA/结果，不以旧通过替代。最终证据和交接提交只改文档，已验证产品/测试保持冻结；以限定路径暂存、校核并检查 main/worktree 授权范围干净。不执行任何外部操作。

原生名称等待/重开及完整字段请求 → SELECT/只读数据/DDL、Oracle 桌面，实际数据库驱动中断/超时/取消/权限/事务，OS 缩放/多屏/全键盘，正式启动器/安装升级/签名/远端 CI/真实用户任务/发布仍待验。M8 不称完成，本轮交付不自动扩大范围。
