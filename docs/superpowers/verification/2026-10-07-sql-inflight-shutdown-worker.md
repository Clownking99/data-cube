# SQL 在途整窗退出 worker 验证

2026-10-07，GPT-6.1-sol worker；基线/HEAD 57b044497175cf906fbf8dfc8254b768d07d63d2，分支 codex/sql-inflight-shutdown-20261007，C复用worktree。root负责独立审查、集成/main/CI，D主仓未改。本轮未确认生产缺陷，只有新增 AppShellSqlShutdownTest.java 和 ShellSqlJdbcProbe.java；生产代码、关闭状态机、全局5秒helper及生产timeout均未改变。

## 真实链路与四例

POSTGRESQL/ORACLE × timely/跨原PT5S warning后释放，共4例。AppShell.treeActions.openSqlEditor → 真实SqlEditorPane UI MANUAL切换/execute按钮 → SerialSessionOperationQueue → JdbcEditorSession → PgSqlRunner/OracleSqlRunner/JdbcScriptExecutor → 原mandatory guard → 真实WindowShutdownController安装的Stage close handler。仅provider/ConnectionFactory/JDBC seam使用合成mock；不替换EditorSession或guard。900×600 shown Stage使用已有反馈全文/几何断言，未调用原生桌面输入。

先通过UI成功执行带WHERE的合成DML，真实事务ACTIVE/pendingWrites=1，再执行被阻塞脚本。退出调用Statement.cancel一次；另一个真实session请求已排入实际queue，在关闭时取消而不启动，第三条脚本语句不启动；queue及AppShell实际入口关闭准入。慢例004实际warning在PG5010ms、Oracle5009ms出现（生产PT5S）；此时仍真实pending，同一等待card、物理execute活跃，rollback/sessionClose/globalClose均0，无fatal，资源/tab仍归原所有者。

释放mock execute后正常返回不同updateCount73：JdbcScriptExecutor实际publish迟到progress，UI侧admission过滤；完成结果进入真实queue，callbacksEnabled=false使SerialSessionOperationQueue.dispatch直接返回，terminal分发被抑制，没有宣称terminal已排入FX队列。捕获实际单次任务虚拟线程并有界join，FX屏障后核对完整status/result/revision/nullable report未刷新。在mock rollback短观察屏障期间检查，不把finalize清空混同于late输入。严格trace均为 execute-return:73 → statement-close:BLOCKED → rollback-enter → ui-unchanged-after-late-update:73 → rollback-released → rollback-complete → session-close → global-close。最终COMPLETED，0commit/1rollback、每Statement close1、dedicated connection close1、合成global cached connection close1、tabs释放、等待card移除、Stage hidden1、重复请求shutdown1；后续准入拒绝。uiFinalized/coordinator finalizerInvoked是原有终态flag观察，未伪称直接计次探针。

global缓存连接是fixture植入的close-only合成资源，断言真实ConnectionManager.closeAll所有权；不宣称覆盖其真实获取。fixture release/rollbackRelease在finally开启，cleanup/fallback明确不是产品恢复，SQL_EXIT仅所有产品断言完成后且cleanup前输出。

## 本轮新执行与首次失败

统一证据根为 evidence/sql-inflight-20261007-worker，每目录均有实际command.json、exit.json、gradle.log和执行Task原XML。

| 完整目录名 | suites/tests | failed/error/skip | exit | 解释 |
| --- | --- | --- | --- | --- |
| 001-sql-inflight-targeted | 1/4 | 4/0/0 | 1 | 编译成功；夹具visible直接解引用被真实第二次onExecute清空的report导致NPE。不是产品红灯。原源码保存failed-fixture-source。 |
| 002-sql-inflight-fixture-corrected | 1/4 | 1/0/0 | 1 | 四个SQL_EXIT完成断言，但首次Toolkit在fixture profile下提取native DLL，Windows锁文件导致JUnit TempDir清理失败；整次不算通过。原失败/抑制异常XML与源码保留。 |
| 003-sql-inflight-native-fixture-corrected | 1/4 | 0/0/0 | 0 | 首次四例完整通过，21秒构建。BeforeAll复用既有FX native preload，在本次runner独占home先初始化，再切fixture home。 |
| 004-expanded-targeted | 17/184 | 0/0/0 | 0 | 完整扩展定向，1m20s；SQL四例11.619s，含原PT5S两条真实warning。 |

001改为真实nullable report整体快照，并先断言前置DML的实际report存在；002不跳过TempDir清理，使用既有preload解决native归属。001/002旧cleanup曾恒写productAssertionsAlreadyRecorded=true，不能作成功证据；最终统一fixtureCleanupNotProductEvidence=true，成功SQL_EXIT另输出。003→004仅增加原5秒warning实际millis证据与>=4500ms断言；004字节冻结后不改。无EOF变更。

004的17suite精确清单/各项计数见targeted-summary.json及004-expanded-targeted/summary.json，涵盖真实session/queue、PG/Oracle执行控制、SqlEditor close policy/sequence/session contract、WindowShutdownController反馈/quarantine、AppShell recovery/workspace/grid、async coordinator/registry及FxTaskScope；跳过0。UP-TO-DATE的其他编译Task不充当测试通过，实际:test本轮执行。

## 冻结与runner

final-source保存两个最终源码原字节；final-tests.patch包含两份untracked新增测试完整diff。frozen-sha256.json保存两文件、patch和runner SHA256。源码分别0440db0e1b6915ef2aad39424fcb0029493413487fc4e19a436e6824ca61b473 / f578ad9190d8191dd760bfd8da45038b40fa933c3d80a23f54a69d140532a8a8。完整patch SHA5f6fadd402a66ed516955119bcf212f67ab88acf1746e5b3d66e6450879bdee6；runner SHAa83e73533744c1ce5dcbfcb7282e0e77d8a18019c7808d50df4710e14b43b9f4。

空白检查无输出，no-index新文件差异正常exit1；初次归档脚本将Git safecrlf转换提示当作异常，未改源码，改为仅命令级core.safecrlf=false后完成真正空白检查，未改Git配置。证据局部.gitattributes起始为* -text。runner从前轮复制只改专用datacube-sql-inflight-worker- UUID前缀，每次新profile/temp/真实8.3别名，offline/JDK25/no-daemon，无live环境，单Gradle串行。未读取原profile/凭据/历史或.testagent。仅读取本轮合成XML，不读业务文件。

root已独立审核源码与004原始184项；全量/root buildSrc/image将分别另建新目录并追加实际统计，旧证据不覆盖。当前尚未提交/合并/push。

## 未验边界

非可取消COMMIT在途不扩张；真库/真实PG或Oracle驱动取消和事务行为、原生键鼠、OS缩放、终态进程内恢复、无Gate启动、安装升级/回退/签名及完整M8未验。本轮只补指定mock SQL执行在途整窗真实链路证据，不宣称全部发布验收。

## 冻结版本全量/buildSrc/image与交接

root审核后按冻结字节串行执行，新目录与专用profile/temp互不复用：

| 完整目录名 | 实际任务/结果 | 新XML统计 | 构建耗时 |
| --- | --- | --- | --- |
| 005-clean-test | clean test，exit0，:test实际执行 | 315套4016总项，0失败/0错误/3跳过，4013执行通过 | 4m43s |
| 006-root-buildsrc-test | root :buildSrc:test --rerun-tasks，exit0，实际执行 | 1套8项，0失败/0错误/0跳过 | 8s |
| 007-jpackage-image | jpackageImage --rerun-tasks -Image，exit0，14Task全部executed | 该构建不是测试，不复用测试XML | 44s |

005精确跳过3项，仅live环境条件跳过，不算通过：

- com.datacube.redis.RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()
- com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas()
- com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas()

005/006原XML、各run command/exit/log与summary.json保留；007只构建镜像，未安装/启动/发布。jlink日志提示本JDK使用JEP493模块安排，任务成功，并非测试失败。镜像内容、提交/main/CI由root独立审查和执行，worker不宣称已交付main。

post-build-sha256.json确认两份测试、完整patch、runner均与冻结SHA逐字节匹配。run-sequence.json记录7次command→exit时间无重叠、7个新独占home及真实8.3 temp路径；root未运行Gradle，worker没有并发构建。所有运行已正常结算，Gradle现交回root，不继续自动扩功能。报告和证据总SHA清单记录在evidence-sha256.json，manifest本身排除以避免递归哈希。
