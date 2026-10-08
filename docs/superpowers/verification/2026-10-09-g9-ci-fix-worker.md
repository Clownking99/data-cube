# G9 Linux CI 测试兼容修正

**本轮测试修正与Windows本地验证完成：受影响81通过、完整G9 1182通过、clean全量4664通过/3 live跳过，强制headless另16跳过。首次Windows CI初始化超时未复现、根因仍未知；实际Linux修正仍待根会话新精确SHA CI。**

本次日期为2026-10-09（Asia/Shanghai）。根会话明确下发P3返工，唯一Gradle执行权暂交本worker。当前分支 `codex/g9-table-export-reliability-20261008`，起始HEAD `b76b75c9106709121fd542108cb3bbde944cfe15`。不merge/main/fetch/push，不改旧冻结报告、A/B/C/P2原件，不做外部操作。

## 修正前证据与最小设计

精确main文档SHA `f634f7f4b5ec95d91ce9d8ed544c2b8920963b85` 的Verify run `37822449579`、Linux job `113466665854` 原日志已从根会话明确指定文件只读复制到独立 [g9-ci-fix-20261009-worker](evidence/g9-ci-fix-20261009-worker/)；origin/copy SHA及长度保存在 `ci-log-provenance.json`。日志实际为4566 completed、14 failed、1751 skipped，不能用此前Windows通过替代。844个既有P2输入在修正前全部SHA/长度仍相同，另有本轮before manifest及三个待修文件原始snapshot。

- G9新增/修改测试精确路径扫描只有两处 `bin/java.exe`：PgDumpRunnerBaselineRedTest启动入口与PgDumpProcessHelper子孙入口。两者将调用同一测试专用Java路径函数，由当前 `java.home` 与Windows平台标志选取bin/java.exe或bin/java。补一个路径选择回归case，覆盖Linux/Windows选择及本机实际JDK可执行文件，不调用pg_dump或安装工具。
- AppShellTableExportJdbcShutdownTest的selection参数化入口在构造mock/profile/selection/runner及assertThrows之前先执行现有 `FxUiTestSupport.call` 空动作。无显示时Assumption作为明确skip传播；有显示时原ui-error AssertionError/callback/listener/资源断言保持不变。另一个同类assertThrows入口在AppShellTableExportShutdownTest中，已先构造会调用同一显示门禁的Fixture，无需改变；其他G9入口未发现同一包装问题。不改全局FxUiTestSupport或CI桌面设置。

仅修改测试，产品/构建树必须与已验e795相同。新UUID隔离、清空子进程环境、现有JDK25/离线缓存下依次运行受影响进程/整窗测试、强制headless参数化类、完整G9定向、clean全量，保存新command/exit/log/XML和物理回执。headless skip与live skip分别统计。没有Linux运行环境，不宣称Linux实测；由根会话后续精确SHA CI验证实际Linuxhelper。

上段为修正前设计记录；下面保留当时追加观察与本轮实际验证结果。

## 协调追加的首次Windows CI观察

同一Verify run的Windows job `113466665853` 原raw log已从明确指定路径只读复制，SHA/长度在 `windows-log-provenance.json`。实际为4666 completed、1 failed、3 skipped，后续linked image因此未执行。失败为既有 `AppShellSqlCancelIdentityTest.oldPhysicalCancelFailureCannotCloseTheNextExecutionConnection(POSTGRESQL)`，TimeoutException发生于Fixture构造中的FxUiTestSupport.call/FutureTask.get，尚未进入实际旧cancel行为。根因未知，不据此改变生产逻辑、超时或断言。

当时完整G9定向已在按冻结输入运行；按追加要求，之后另一次单独运行该完整4-case类，再运行原定clean全量自然覆盖它。不压力重复直到绿。该额外run的专用launcher及847输入另有 `input-freeze-identity.json`，原846输入manifest不变。如本轮未复现，报告仍保留首次失败身份及根因未知待诊断。

## 本轮实际结果与冻结交付

修正仅三个测试文件，20行新增/2行替换；没有修改原断言、超时、sleep、生产、构建、README、FxUiTestSupport或CI配置。新增平台路径回归case覆盖Windows和Linux文件名选择，并验证当前JDK实际Java文件regular/executable；根helper与子孙helper都调用同一个函数。Windows本机实际进程族断言继续执行，Linux分支路径仅作纯路径选择断言，不宣称Linux实测。

| 新run | 实际任务核心 | suites/cases | 通过 / failure / error / skipped | 物理回执 |
| --- | --- | --- | --- | ---: |
| `001-affected` | `cleanTest test --rerun-tasks`，PgDumpRunner*Test及AppShellTableExport*Test | 6 / 81 | 81 / 0 / 0 / 0 | 43 |
| `002-forced-headless` | 同任务，仅AppShellTableExportJdbcShutdownTest，明确headless=true | 1 / 16 | 0 / 0 / 0 / 16 | 0 |
| `003-g9-targeted` | 同任务，完整G9及受影响provider/migration/UI/连接取消过滤 | 85 / 1182 | 1182 / 0 / 0 / 0 | 135 |
| `004-cancel-identity-once` | 同任务，仅AppShellSqlCancelIdentityTest完整类一次 | 1 / 4 | 4 / 0 / 0 / 0 | 20 |
| `005-clean-full` | `clean test --rerun-tasks` | 345 / 4667 | 4664 / 0 / 0 / 3 | 157 |

五次实际Gradle child exit均为0、每次9任务实际执行，耗时分别37s、19s、2m15s、23s、5m33s。全部带offline/no-daemon/console=plain/既有JDK25。没有用UP-TO-DATE、旧XML或缓存任务代替本次实际结果；XML按child退出后复制，`actual-xml-summary.json`逐suite检查属性与case元素。新launcher也用显式skipped节点存在性统计，未改旧P2 launcher或旧便捷汇总。`all-physical-receipts.json`从本次XML的system-out提取PG process/家族/独占JDBC/整窗及cancel identity回执；先前较窄的physical-receipts提取也保留，不互相覆盖。

16个强制headless skipped全部是 `JavaFX controls require an available display`；`headless-skip-receipt.json`列全部ID/原因，其中 `[7] ui-error` 在资源分配和assertThrows之前从新门禁直接abort，不再变成AssertionFailedError。它们明确为skip，0通过。Windows有显示的单独81及完整1182/全量运行保持ui-error的实际AssertionError、资源/listener释放等原断言通过。

全量三个live skipped单独记录：RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle缺显式Redis环境；SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas与postgresqlSafeDeploymentConvergesInDisposableSchemas缺显式write gate/完整provider环境。各确切message及ID见新XML、actual summary和results.json；没有访问真库，也不计为外部验收通过。

首次Windows CI的Fixture FX初始化TimeoutException在单类一次4/4与clean全量该类4/4中均未复现；根因仍未知待诊断，未声称已修复、不增加压力重复直到绿。单类20回执包含两种provider真实AppShell/queue/cancel行为及原fixture结算，`windows-timeout-single-followup.json` / `windows-timeout-full-followup.json`独立保存该观察。Linux14失败/Windows1失败的首次原log、SHA/长度、命令及exit 1在first-ci-failures.json及各provenance中保留；旧失败SHA未由worker重跑。

常规run有846个冻结输入、专用identity run847个（多一个专用launcher），每次前后manifest与最终final-input-binding全部相同。三个旧/新test源码另有raw snapshot；原P2的3516冻结文档/证据文件全部重新核对SHA/长度相同，旧worker报告和A/B/C/P2原件不变。产品/构建树和所有对应当前原始输入与已验 `e7950123ed052b9c370ed355496f3333b35ad11c` 一致，本次按授权不重做buildSrc:test/jpackageImage。Gradle常规buildSrc编译不计作新的buildSrc测试或镜像验收。

本轮精确暂存范围仅三个测试、新报告和新g9-ci-fix证据根。以raw-manifest冻结SHA/长度、证据根 `* -text` 保持原始字节，ignored日志逐文件明确force加入；实际 `git hash-object --no-filters --stdin-paths`逐文件对比index，提交后逐已知合法路径cat-file对比commit blob。为避免自引用，manifest和index审计收据单独核对。所有本地提交SHA、最终冻结文件数及raw/commit核对结果由交付回复给出。

本次 `process-settlement.json` 确认Gradle/test/可控PgDumpProcessHelper根及子孙均0存活。完成本地提交和只读核对后停写停测、交回唯一Gradle执行权。剩余下一步由根会话独立审核、合并main与精确新SHA Windows/Linux CI验证；本worker未merge/main/fetch/push、未安装工具或运行真实pg_dump/数据库/原生外部验收。
