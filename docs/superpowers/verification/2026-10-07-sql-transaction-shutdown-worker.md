# 显式事务在途退出 worker 验证

2026-10-07，GPT-6.1-sol worker，C复用worktree分支codex/sql-transaction-shutdown-20261007；基线3494b670f82af1257bc63c13de91ae8fee2d29e9。root独立审查/集成/main交付，本轮旧CI不算新证据。D主仓不改。

## 首次真实产品红灯

证据evidence/sql-transaction-20261007-worker/001-explicit-commit-red：offline专用UUID profile/真实8.3 temp/JDK25，compileTestJava成功，:test实际执行，exit1，构建18秒；PG/Oracle共2tests/2failures/0errors/0skips（suite3.743秒）。唯一运行现已正常退出，未重跑、未改生产源码。

真实链路：AppShell.treeActions.openSqlEditor→真实SqlEditorPane UI MANUAL和executeBtn前置WHERE DML（真实PgSqlRunner/OracleSqlRunner）→commitBtn→原WriteSafetyDialog确认请求(TEST自然无需modal)→真实queue COMMIT(false)→真实JdbcEditorSession→mock Connection.commit barrier→原mandatory guard→真实WindowShutdownController Stage close。只provider/JDBC合成mock。前置ACTIVE/pendingWrites1，COMMIT物理线程活跃、queue.idle未完成，但session.snapshot.running=false；关闭pending说明实际全文/几何断言通过，无cancel/interrupt/提前rollback/close。

在产品期望断言前实录，两个provider一致：outcome=COMPLETED，showing=false，pending/failureFeedback=false，session=CLOSED，commit1/rollback1/sessionClose1/globalClose1，uiFinalized=true、managed=false。原trace为实际DML→commit-enter→commit-throw:synthetic→rollback-enter→rollback-complete→session-close→global-close。root确定契约要求尚未向UI显示的显式事务失败必须沿既有FAILED_PARTIAL可见保护，保留所有权且禁止自动rollback/retry，因此这些是行为产品红灯，不是编译/timeout/夹具失败。COMPLETED只表示清理结算，不代表提交成功或数据已撤销。

pre-red-source保存两个首次测试/probe及基线SqlEditorPane/SerialSessionOperationQueue原字节；001/source-sha256.json记录SHA，summary.json、command/exit/log和原XML完整保留。夹具cleanup日志fixtureCleanupNotProductEvidence=true，首红产品实录先于cleanup。正式绿色后必须补FAILED_PARTIAL fixture-only清理，不能用原close取得fatal缓存结果后只关Stage而泄漏；将产品资源保留断言和fixture释放trace明确分开。首红probe暂沿旧结构复制，正式矩阵前精简未使用的BLOCKED/可取消SQL分支，首红快照不覆盖。

## 首红后初步方案历史（已由下述S4替换）

建议只在SqlEditorPane记录一个未被UI处理的事务Attempt token：真实Callable保存失败后原样抛出，UI terminal成功处理后按identity清field，提交拒绝清理；startMandatoryCloseAttempt在FX入口beginClosing前稳定捕获token，后台仍等唯一真实queue.idle后检查失败，若有直接FAILED_PARTIAL而不进入transactionGate/destructiveCleanup。无额外future/等待，不改队列/会话/状态机或timeout。已显示旧错误不粘滞；current已clear而failure FX尚未处理仍由field捕获；queued取消未执行不产生失败。SET_MODE也可能在原Alert选择commit/rollback后失败，需要root限定是否纳入同一SQL事务保护。

后续矩阵待root确认：成功非可取消COMMIT/ROLLBACK跨原PT5S等待；失败COMMIT/ROLLBACK不自动重复事务；已生效后commit异常的不确定结果；queue已clear/FX未显示竞态；旧失败显示后恢复再正常退出；必要实际SET_MODE确认Alert路径。不将基线commit失败+自动rollback成功写成绿色规格。

verification/.gitattributes保留旧行，只新增本报告单文件-text；worker证据起始局部* -text。runner复制上一已审版本，仅专用datacube-sql-transaction-worker- UUID前缀。全量/buildSrc/image尚未授权且未运行；worker不commit/merge/push。

安全边界保持：.testagent禁读改枚举暂存清理；不读原profile/凭据/连接/历史/业务文件、不真库/剪贴板/原生输入/安装更新/外部联系。仅合成独占temp/mock；v3.2.9和PAUSED跟进不动。原生输入/OS缩放/真实驱动数据库事务及完整M8未验；不扩其他模块或下一功能。

## S4 首红与补充竞态的实际推进

001-explicit-commit-red首次PG/Oracle2例真实产品红灯，commit抛错后自动rollback/close且COMPLETED隐藏窗口；原源码/XML/log保留。首轮pane FX-owned pending List+mandatory稳定copy使002-transaction-matrix的18例16通过，但physical idle/FX未呈现两例仍失败，整次不算绿。真实SqlWorkspaceUi.freeze明确Platform.runLater，故应用退出FX入口到pane mandatory之间原正常error callback可先呈现并remove token；只是guard入口快照还不够。

root批准最终两文件方案：复用pane Node.properties中的既有身份（私有key改名SQL_EDITOR_OWNER，favoriteText语义不变）；AppShell新shutdownAttempt建立后、异步shutdown之前同步遍历所有Tab.content，包括非选中tab，capture pending immutable List并返回无阻塞identity释放closure；AppShell每一种终态FX回调最前释放。采用唯一ApplicationCloseCapture对象，避免List.copyOf(empty)共享空List破坏跨代identity。pane mandatory入口合并早期snapshot与current pending，真实queue idle后检查物理失败，失败直接原FAILED_PARTIAL保护，不做再次rollback/retry或资源清理。未改queue/session/关闭状态机/timeout；没有使用root.isDisabled吞callback或增加replay状态机。原UI callback正常处理后identity移除当前pending；前置workspace异步间隙允许实际ERROR呈现，但应用进入退出时尚未呈现的失败仍由早期快照保留；guard suppress后迟到terminal仍不更新UI。

003-first-red-and-boundary-recovery先跑8例，8/0失败/0错误/0skip，exit0/17s，四方法PG/Oracle分别首红commit保护、物理idle/FX未呈现race、真实双tab非选中race、真实mandatory draft flush失败取消恢复。恢复仅本fixture owned .draft mover注入IOException，实际CANCELLED恢复后commit仍在途，释放后真实ERROR呈现，再实际rollback恢复/二次exit正常，不能将内部字段清空冒充恢复。fixture清理固定pane.closeResources→shell.shutdownRemaining→FX finalizer，额外tab若有同样清；before/after事务模型与计数/trace分别标notProductRecovery。probe精简为单WHERE DML与不可取消commit/rollback物理barrier，首红死分支源码仍原封快照。

root批准004-final-targeted：22新矩阵（10事务结果含原首红2、2 FX idle race、2显示旧错恢复、2 SET_MODE实际combo/原Alert、2 queued mode不覆盖running、2非选中双tab、2真实draft flush取消恢复）加17旧相关suite和SqlFavoriteTabsTest，共19suite。完整定向结束才最终冻结2src+2test/probe+patch+runner，待root审核批准full/buildSrc/image。本轮SET_MODE已获批准纳入同SQL事务记录，原12组合提案不作为当前规格。

## S5 完整定向的夹具时序修正

004-final-targeted实际19suite/207tests/1failure/0errors/0skips，exit1/1m50s，不算通过。唯一Oracle COMMIT fail=true appliedBeforeFailure=true在before==after断言失败，实际仍FAILED_PARTIAL、窗口showing/固定failureFeedback保留、commit1/rollback0/sessionClose0/globalClose0、committedWrites1；正常错误UI在尚未完成草稿异步flush的前置间隙呈现。此前3帧layout只验证排版，不能证明I/O完成和mandatory已suppress。004/source-at-failure保存四文件原源码，command/exit/log/XML和summary原件保留，不归为产品保护门禁退化。

root独立审核确认仅修fixture前置同步：现有SqlDraftUi.observe在FX以真实queue monitor读取callbacksEnabled，注册后立即check并finally注销，5秒有界确认false才释放JDBC，trace记录mandatory-callbacks-suppressed-before-jdbc-release；不改字段、不sleep、不调整FX/产品timeout，不增加生产修改。普通in-flight、SET_MODE和queued三组建立guard-suppress时序；early race、非选中tab和draft取消恢复保持各自真实前置时序及可见错误断言。下一005完整同19suite/207项，未运行全量。

本次编辑命令首次因无效python路径未执行，未写文件、未运行Gradle；随后用原生PowerShell精确修改，不作为产品红灯或测试结果。

## S6 最终定向与冻结

005-final-targeted-synchronized命令/exit/log及原XML已完整保存；:compileTestJava、:test实际执行，exit0，BUILD SUCCESSFUL 1m51s。实际19suite/207tests/0failures/0errors/0skips，其中新增真实事务22例全部通过（suite28.716秒）。原PT5S四例warning分别PG COMMIT5010ms、Oracle COMMIT5014ms、PG ROLLBACK5009ms、Oracle ROLLBACK5018ms；警告时物理线程仍阻塞、idle=false、session.running=false，无cancel/interrupt/提前事务或清理。成功严格物理return→sessionClose→globalClose且唯一关闭；失败（含mock已生效后抛错）FAILED_PARTIAL持久反馈，产品rollback/close/global均未追加。guard抑制后的completion进入真实queue而terminal分发被抑制，实际UI/status/report revision不变；early queue-idle/FX未呈现单tab和非选中双tab例则真实前置ERROR呈现仍保护，不能混称这些回调未呈现。旧错误实际显示→rollback恢复→正常退出、draft mandatory拒绝CANCELLED→交互恢复→晚失败实际显示→rollback→二次正常退出均通过。

全部运行串行且仅worker拥有Gradle许可，每次runner新UUID profile/temp并使用真实8.3alias、offline/no-daemon，live环境剔除。005专用owned datacube-sql-transaction-worker-72de6143254a4f7aa54975b9cbaaf785，temp别名DAE30A~1/temp；最后本次工具session8447正常exit0，Gradle任务已停止，本轮full/buildSrc/image未授权且未执行，不以UP-TO-DATE算测试通过。现暂停交root审源码/原件，待其批准下一阶段。

005/source-at-pass保存通过时四文件原字节。冻结前仅新test第38行空白行移除12空格，非语义规范，不为此重跑005，已保留此前源码；生产与probe无变动。最终4文件已冻结，final-freeze/source为原字节、source-sha256.json为最终SHA/长度；完整complete-source.patch含2src+2新untracked test/probe（不包含root文档或worker证据），52773B/SHA5CE11B313101E3087AC8A8B839DF2E90685D366A66D4BCDA54EB8A6D422C1186；runner3644B/SHA2625AE6547F5A048F8A11B26C0FEDE77C3AB7AF8CAA9D45AF7AC7B158F6B5FAF，artifact-sha256.json记录。跟踪文件git diff --check通过；新test/probe无行尾空格。报告单文件-text与证据局部* -text已预防本轮报告/原件Git换行改写。

原件清单：001-explicit-commit-red（2真实产品红灯）、002-transaction-matrix（18/2fail早期捕获缺口）、003-first-red-and-boundary-recovery（8/0fail首次边界/恢复通过）、004-final-targeted（207/1fail夹具未等抑制）、005-final-targeted-synchronized（207/0fail最终定向）；各目录含command.json、exit.json、gradle.log与实际原XML。001/pre-red-source与其SHA、002/source-at-failure（包括基线AppShell）、003/source-at-pass、004/source-at-failure、005/source-at-pass全部保留；无失败覆写/删断言/盲重跑。完整目录文件清单originals-list.txt与串行说明runner-concurrency-summary.json随freeze归档。

当前源码改动仅AppShell3行新增、SqlEditorPane56行新增/6行删除，共59新增/6删除。AppShell在真正shutdownAttempt建立后同步捕获所有tabs，终态FX回调统一identity释放；pane只跟踪尚未向UI呈现的实际COMMIT/ROLLBACK/SET_MODE物理失败，stable copy后真实queue idle才保护，已显示失败按identity清除。未改变queue/session/mandatory资源状态机或5s/15s常数，未新增自动强杀/重启/恢复准入/事务重试。fixture-only资源兜底仍在产品断言与实录之后，明确notProductRecovery，不能冒称FAILED_PARTIAL可恢复或数据库操作已保存/回滚。

未验边界：原生键鼠/OS缩放、真实PG/Oracle数据库及驱动、PRODUCTION环境额外WriteSafetyDialog modal；本轮TEST仍实际原WriteSafetyDialog请求且SET_MODE实际原Alert。完整M8仍不作已验声明；仅本轮真实mock整窗显式事务链路闭环。关闭期间显式事务失败结果不确定，合成模型已生效分支仅证明程序不会错误推断或自动清理，不代表真实数据库结果。worker不提交/合并/push，D主仓/v3.2.9/PAUSED自动跟进未动，不开展下一功能。

实际各套统计：
| 套件 | tests | fail/error/skip |
| --- | ---: | --- |
| com.datacube.fx.AppShellGridShutdownTest | 6 | 0/0/0 |
| com.datacube.fx.AppShellShutdownRecoveryTest | 9 | 0/0/0 |
| com.datacube.fx.AppShellSqlShutdownTest | 4 | 0/0/0 |
| com.datacube.fx.AppShellSqlTransactionShutdownTest | 22 | 0/0/0 |
| com.datacube.fx.AppShellWorkspaceShutdownTest | 4 | 0/0/0 |
| com.datacube.fx.AsyncManagedTabRegistryTest | 14 | 0/0/0 |
| com.datacube.fx.AsyncShutdownCoordinatorTest | 5 | 0/0/0 |
| com.datacube.fx.AsyncTabCloseCoordinatorTest | 19 | 0/0/0 |
| com.datacube.fx.DataCubeFxShutdownContractTest | 13 | 0/0/0 |
| com.datacube.fx.ShutdownQuarantineTest | 3 | 0/0/0 |
| com.datacube.fx.SqlEditorClosePolicyTest | 2 | 0/0/0 |
| com.datacube.fx.SqlEditorCloseSequenceTest | 6 | 0/0/0 |
| com.datacube.fx.SqlEditorSessionContractTest | 16 | 0/0/0 |
| com.datacube.fx.SqlFavoriteTabsTest | 1 | 0/0/0 |
| com.datacube.fx.task.FxTaskScopeTest | 5 | 0/0/0 |
| com.datacube.fx.task.SerialSessionOperationQueueTest | 6 | 0/0/0 |
| com.datacube.provider.oracle.OracleSqlRunnerExecutionControlTest | 15 | 0/0/0 |
| com.datacube.provider.postgres.PgSqlRunnerExecutionControlTest | 12 | 0/0/0 |
| com.datacube.service.JdbcEditorSessionTest | 45 | 0/0/0 |

并发文档首版生成误用PowerShell false（缺$），故原runner-concurrency-summary.json为null；此文档命令错误已保留初版与原hash，另存runner-concurrency-summary-corrected.json准确串行摘要，不影响源码/测试/原始Gradle证据。root独立审207项原XML及4源+runner冻结、TrimEnd审计后批准006-full clean test、007 root :buildSrc:test --rerun-tasks、008 jpackageImage --rerun-tasks；严格单Gradle串行，每步新UUID隔离，任何失败先停。

## S7 审查后全量、buildSrc与镜像的最终结果

root独立审005原XML、4源hash和005通过源码→当前仅空白规范的TrimEnd审计，冻结4源+runner并授权以下严格串行。本轮worker已按冻结字节完成，均新UUID/profile/8.3temp、offline/no-daemon、首错保留，没有测试或生产常数改动。

| 新运行目录 | 实际执行 | 结果 |
| --- | --- | --- |
| 006-full | clean、compileJava、compileTestJava、test | exit0，5m5s；316suite/4038总/4035通过/0fail/0error/3live skip |
| 007-buildsrc | root :buildSrc:test --rerun-tasks（4 tasks均执行） | exit0，7s；datacube.build.IcoGeneratorTest 8/0fail/0error/0skip |
| 008-image | jpackageImage --rerun-tasks -Image，jlink与jpackageImage均执行（14 tasks全部执行） | exit0，40s；Image独占build home，JAVA_TOOL_OPTIONS=null，无测试XML预期 |

006三项skip精确为：com.datacube.redis.RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()（缺显式Redis host/password live环境）；com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas()和postgresqlSafeDeploymentConvergesInDisposableSchemas()（缺显式write gate及完整provider live环境）。它们未验，不计通过；runner剔除live变量符合本轮仅mock/合成边界。其余真实执行4035项通过，普通SQL相关定向与全量均为本轮新结果。

006专用81e9cb9c14514203987c6b9e1a2b9869 / DAC455~1；007专用09cfe51adf4548b4800c903394f1faef / DA87B9~1；008专用99b46876f6724e5ba1db389b03741b26 / DA5739~1。原command.json实际记录完整路径/参数，exit.json与gradle.log原件、006/007实际XML、各summary独立目录保留。无从旧目录复制测试结果，UP-TO-DATE不计通过。

全部worker Gradle会话已正常退出（006 session43328 exit0、007即时exit0、008 session26416 exit0），已移交唯一Gradle许可root，worker不再运行任务。root随后独立镜像审计/提交/main复验/推送/CI，worker没有启动正式App、没有commit/merge/push。final-source-verification.json复验4源+runner+完整patch冻结SHA完全一致；源码与runner今后不再改。本轮最终报告原字节与最终清单另存final-freeze/worker-report-final.md、originals-list-final.txt、final-evidence-manifest.json、final-document-sha256.json；早期targeted快照与并发摘要null错误原件全部不覆盖。最终worker单文件-text继续保持。

截至移交，无未结算worker构建或待修测试；所有失败001/002/004与其原始源码/XML保留，003/005定向、006全量、007构建逻辑、008打包分别记录。未验边界保持S6，不扩大功能。FAILED_PARTIAL仍是现有不可恢复保护终态，测试兜底释放仅fixture-only且不改变这一产品契约。
