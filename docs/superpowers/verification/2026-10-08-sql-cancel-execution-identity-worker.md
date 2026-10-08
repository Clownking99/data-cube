# SQL取消执行身份：worker记录

## S0 只读诊断与首红方案（2026-10-08）

基线 eaa59237a565c1278f7701eae0b44daf074bf08b，分支 codex/sql-cancel-execution-identity-20261008。root批准仅新增首红测试/probe；生产保持基线，尚无Gradle许可、尚未编译或执行。

真实入口为 AppShell.treeActions.openSqlEditor、AUTO_COMMIT 的 SqlEditorPane 执行/取消按钮、实际 SerialSessionOperationQueue、JdbcEditorSession 和 PgSqlRunner/OracleSqlRunner。仅provider/JDBC使用合成mock。独立UUID合成profile写入JUnit TempDir；FX native预加载先使用runner独占home。全部SQL为带WHERE合成DML；没有真实数据库、原用户配置或历史数据。

风险A：pane1894到后台调用时才读取session当前执行；1897-1900的error-gate身份仅约束旧UI回调。测试显式调度注入仅暂存cancelBtn.fire同步提交到FxTaskRunner.execute的原ScopedFuture，其他ExecutorService方法/任务全部原样委托。旧SQL返回后读取原UI按钮是否准入；若准入则启动第二轮，再由原executor一次投递原任务；若拒绝则断言第二轮执行次数零，物理取消结算后真正执行第二轮。finally恢复executor且只投递尚未投递的原任务一次。

风险B：session391-400旧Statement.cancel阻塞后抛错，fallback602-605不判断旧执行身份就取当前Connection。测试仅mock JDBC：第一条SQL和其Statement.cancel有各自有界barrier；cancel进入后释放旧execute，观察真实queue idle或真实worker在finishOperation等待物理取消的栈，不写产品状态。读取第二轮实际按钮准入，允许两种安全策略：先启动第二轮且旧cancel不能碰它，或物理取消未结束时拒绝第二轮、结束后允许执行。没有强制控件状态、伪造queue完成或sleep。

两风险各PG/Oracle共4例。每个Connection/Statement独立closed、close/cancel计数与owner，记录SQL入口、execute返回、Statement关闭、旧cancel投递/晚throw、连接关闭、queue/session/UI/next control事实后再断言。关键断言：旧取消不得取消第二轮control/Statement或关闭其连接；第二轮最终真实report normal=1/failed=0/cancelled=0/updateCount=97，status与report.summary一致。fixture-only资源清理在产品断言之后单独标记，不冒充产品恢复。

新增文件：test/com/datacube/fx/AppShellSqlCancelIdentityTest.java、test/com/datacube/fx/ShellSqlCancelIdentityJdbcProbe.java。runner由root提供于 evidence/sql-cancel-identity-20261008-worker/Run-Main.ps1；本阶段不运行。首红若出现编译/夹具错误会保留首错，不能作产品红灯。

尚待root静态审查/首红许可；尚未确认缺陷，尚未决定生产修复或是否需让finishOperation等待物理取消。既有无active Statement fallback、cancel失败连接关闭、开连接期间取消、关闭取消→idle→资源结算契约及原error gate必须保留。EXPLAIN/参数化入口只读关注，不自动扩张本轮测试或实现。

## S1 首红001（2026-10-08）

root授予唯一001运行许可后，先将3相关生产源与2新测试/probe原字节保存于 evidence/sql-cancel-identity-20261008-worker/before-001-source/，包含SHA与run-request。精确命令：Run-Main.ps1 -Name 001-cancel-identity-red -Tasks @('test','--tests','com.datacube.fx.AppShellSqlCancelIdentityTest')。本轮独占UUID profile d0f28977b08445d4a4febc8ebd9916df，实际8.3 temp DA035B~1，offline/no-daemon；生产基线差异为空。

实际compileTestJava与:test运行，退出码1，BUILD FAILED in 28s，新XML 1 suite / 4 tests / 4 failures / 0 errors / 0 skipped，suite time 3.084s。A两provider实际允许新执行先开始；原取消任务投递后，第二轮control.cancelRequested=true、第二轮Statement.cancel=1，连接仍保留。B两provider实际允许新执行先开始；旧Statement.cancel晚抛错后，第二轮control身份/取消标记未变，第二轮Statement.cancel=0，但其正在使用的连接被close一次，session变BROKEN。均在记录原始物理事实后于所有权断言失败，非编译/夹具错误。

所有四例fixture-only BEGIN/END清理完成：全部Connection/Statement独立close计数为1，global close为1；不记为产品恢复。原command.json/exit.json/gradle.log/XML、summary.json与五文件source-at-failure/SHA均保存在001-cancel-identity-red目录。本阶段未修改生产、未重跑。Gradle session 44518已退出，运行许可已交回root，等待独立首红审查与最小实现批准。

## S2 最小实现待静态复审

root独立确认001四例产品红灯后，批准两生产文件身份/物理所有权方向。JdbcEditorSession新增所属session的一次性ExecutionHandle、executeCancellable真实Callable上下文、captureCancellation绑定请求（包含始终empty的无参快照）、abandonExecution未启动失效。3个beginOperation后在连接复用/事务命令前检查取消。physical counter只在后台开始时计数，重复请求不在原cancel仍进行时并发fallback；finishOperation持singleFlight在后台等待，打断时仍完成所有权结算并恢复中断。fallback在同monitor内验证身份/摘除连接/修改BROKEN，实际close与JDBC调用在锁外，counter finally归零通知。

pane普通EXECUTE、EXPLAIN、数据库筛选Prepared通过既有submitSessionOperation共用包装，接受时即绑定handle，包含recordHistory到beginOperation之前空隙。onCancelExecution在FX同步捕获绑定或empty请求，后台不再查当前操作；原scriptErrorGate seal与物理cancel finally finish不变。取消的未启动队列Future通过既有CompletionStage终端观察abandon，提交Runtime/Error也失效，正常UI terminal按identity清除。冻结关闭后的cancelCurrentSession仍是同步后台取消→idle既有链路，fallback兼容cancel即时捕获当前操作；并非延后请求。

新增服务测试 JdbcEditorSessionCancellationIdentityTest 共10例：4种prepublication入口（SCRIPT/EXPLAIN/PREPARED/手动事务已有连接COMMIT）、finished及empty请求跨新执行、Runtime/Error预操作异常2例、所属session/嵌套/reuse/abandon、物理cancel成功/晚throw2例兼顾重复请求、中断finish等待与下一操作singleFlight所有权。服务mock只作契约风险检查，不冒充PG/Oracle真实provider验收；原4例真实UI首红源码与通过条件不改。

截至此记录仅代码完成/diff --check，无编译或绿灯运行，无Gradle许可。root正在静态读diff后再授予定向许可，不能把这些待跑测试记为通过。

### S2静态返工与002首绿

root静态指出并完成窄返工：服务Runner使用一次性blockClaimed（warmup不消耗claim）以真实阻塞第二次操作；物理cancel线程显式捕获Throwable与CancelOutcome，断言成功CANCELLED/晚异常CONNECTION_CLOSED；empty请求的非script UI callback无owner，成功要求handle非null且identity匹配，失败同理，不能用null==null复用身份。上述仅在002运行前修改，原4例UI/probe与001SHA保持一致。

root授予002唯一许可后归档2生产+3测试/probe+runner原字节/SHA于before-002-source，精确filter两suite。独占UUID profile 6fa984dff2a848498fbe7486c55baabf，实际8.3 DA25FF~1。实际compileJava、compileTestJava、:test执行，exit0/BUILD SUCCESSFUL17s，新XML2 suite/14 tests/0 failure/error/skip：真实UI4例2.997s，服务10例0.051s。

实际A两provider第二轮可开始，投递旧原ScopedFuture后新control取消false、newStatement.cancel0、原连接未close；第二轮最终normal1/failed0/cancelled0/update97且status等于report.summary。实际B两provider旧物理cancel未结束时真实按钮禁用并拒绝第二轮（execute列表只有FIRST），queue EXECUTE/session running+cancelling，原连接仍开；释放旧cancel晚throw后只关闭原conn1，idle之后真实新执行打开conn2并正常update97。四例fixture-only清理完成，所有handle close1/global1。原始物理事实见新XML system-out，不冒充原生输入/自然race或真实驱动验收。

002命令/exit/log/XML、summary与source-at-pass六文件原字节/SHA均保存；运行前后六SHA相符，源码稳定。session72612退出、Gradle许可归还root，等待独立核验和广定向/最终冻结授权。尚未跑全量/buildSrc/image。

## S3最终定向003首错保留

root确认002并授予003唯一许可，原字节复制root的related-targeted-tasks-draft.json为worker/final-targeted-tasks.json（30类），冻结当前2生产+3测试/probe+runner于final-freeze并核对与002一致。独占UUID e76767a592564041a74b411eca4bef54、实际8.3 DA7955~1；仅:test实际执行，源码/编译任务UP-TO-DATE不记新编译通过。

003-final-targeted session72545退出1，BUILD FAILED1m57s，新XML30suite/356test/355pass/1failure/0error/0skip。唯一失败SqlEditorSessionContractTest.closeWaitsForSessionQueueAndUsesStrictFinalResources第114行：既有源码contains断言要求tasks.submit(editorSession::cancel，恰为本轮有意消除的未绑定异步调用文本。只读判断是静态实现文本契约过时，此用例没有实际运行资源/关闭逻辑，不能归为运行时产品回归；仍由root独立决定修正，不自行修改或重跑。其余355项本次实际通过，不把整个003称通过。

原command/exit/log/30 XML、summary与source-at-failure（六冻结文件加唯一失败contract源）完整保留。冻结六SHA仍一致，原UI/probe与001一致。session已结束、许可归还root，停止后续Gradle，未full/buildSrc/image、未提交或合并。

root独立确认上述旧结构契约过期后，授权仅修改SqlEditorSessionContractTest旧第114行断言：在onCancelExecution方法范围禁止未绑定method reference，检查captureCancellation早于首个tasks.submit并且后台使用cancellationRequest。其余关闭资源断言、生产及真实14例不动。diff --check通过，修正尚未编译/执行；已交root静态复审。下一次最终冻结源清单由5源增为6源（加该旧契约测试），旧final-freeze及003失败原件均保持，不覆盖。

### S3单断言修正后的004

root复审通过后授予004唯一许可，新final-freeze-004保存六源+runner原字节/SHA，旧freeze不覆盖；同一30类实际compileTestJava/:test运行。独占UUID6a35ea3b30dc4c5d9acceabe979af53d，实际8.3 DAE5DD~1，session60504退出0/BUILD SUCCESSFUL2m11s。新XML30suite/356tests/356pass/0failure/error/skip，七文件SHA与本次冻结相符。004 command/exit/log/XML/summary/source-at-pass均保存，Gradle许可已交回root。

root并行静态发现额外未启动任务拒绝遗漏：queue.scheduleNext捕获runner RuntimeException后completion.completeExceptionally，submit不会抛；pane whenComplete仅isCancelled时abandon，因此callback抑制时未启动拒绝handle仍有效。004运行期间不改源，结束后只读确认并报告最小新增真实queue拒绝测试方案及无条件abandon终端方案。尚未批准/实现/运行这项收口，故004已验矩阵不覆盖该新发现边界，不能宣称本轮最终完成。

## S4未启动拒绝补验005首红

root批准新增SqlEditorCancellationSubmissionTest单例，复用原真实Fixture但仅queue.runner换独立已close FxTaskRunner、suppressCallbacks；私有真实submitSessionOperation(EXECUTE)走原queue拒绝完成路径，finally恢复原runner/reopen/控件且明确原runner未close。生产与原14例不改，before-005-source保存六源+新test+runner八文件原字节/SHA。

root独立审源后授予005-submission-rejection-red唯一许可，新UUID cc33f7f11e184de899322fbf4851244d/实际8.3 DA2E6E~1，session67310实际compileTestJava/:test，exit1/BUILD FAILED10s，XML1test/1failure/0error/0skip/1.453s。原事实queue current=null/idle=true，operation0/callback0，实际pane保留handle/originalRunnerClosed=false；旧handle被executeCancellable实际重用并打印oldHandleWasReused=true，期望IllegalStateException未抛。非编译/夹具红灯。

fixture-only清理BEGIN/END完成：连接/Statement无打开（未进入原Callable），global-close1。command/exit/log/XML/summary与8文件source-at-failure/SHA保持，新测试及当前源稳定。Gradle已停止并交回root，等待首红确认/无条件abandon的最小修复许可，不自行修或重跑。

## S5最小拒绝收口后的006最终定向

root独立确认005产品红灯，授权仅pane whenComplete删除isCancelled条件，无条件editorSession.abandonExecution(execution)，加注释覆盖queue内部拒绝和queued取消，started不受影响；全部测试不改。root逐字节复审通过后更新最终八文件审核SHA，授权006唯一31类运行。

旧30类清单原字节另存final-targeted-tasks-30.json，root最终31清单复制为final-targeted-tasks.json；新的final-freeze-006保存七源+runner原字节/SHA，旧冻结不覆盖。新UUID7b7301bc09b6447699eb8700a374aaa3、实际8.3 DA1E87~1，session33113实际compileJava/:test执行（compileTestJava本次UP-TO-DATE不记新编译），exit0/BUILD SUCCESSFUL1m59s。新XML31suite/357tests/357pass/0failure/error/skip。

拒绝补测原事实queue current=null/idle=true，operations0/callback0，原runner仍未close，旧handle实际不可重用、原assertThrows通过；fixture-only清理END/globalclose1完成。八文件SHA与本次freeze相符，原UI/probe仍与001原字节一致；完整command/exit/log/XML、summary/source-at-pass均已归档。全部Gradle已结束，许可归还root，源保持冻结，等待独立审核/后续授权，尚未full/buildSrc/image/提交。

本轮原件目录完整保留：001-cancel-identity-red（4产品红灯）；002-cancel-identity-green（14/14）；003-final-targeted（355/356，旧结构契约过期，整次失败）；004-final-targeted（356/356，未覆盖后发现拒绝边界）；005-submission-rejection-red（1产品红灯）；006-final-targeted（最终357/357）。原生操作/真实驱动与真数据库、OS缩放/多屏未验；A显式原ScopedFuture调度注入，B是合成JDBC与只读真实等待栈/队列观察，不宣称自然竞态或原生验收。公共FX/生产超时常数不变。

## S6 root提交后顺序验证与worker停止

root独立核006并提交七源为 f656be084cecf20e437091e0bd473673dd6d9d2e（main eaa的直接后继），授予仅连续007-full→008-buildsrc→009-image→010-image-audit验证许可。每步独立run/profile，所有源/测试/runner字节保持final-freeze-006，未Git写入或网络操作。

- 007-full：clean test实际执行，session55346退出0，BUILD SUCCESSFUL5m27s；实际重新编译/资源生成和:test。原Summarize-Tests验证新鲜XML321suite/4078total/4075pass/0failure/error/3skip。独占UUIDb277f98522734574a48ec6f6af365460、实际8.3 DAAA4A~1。
- 008-buildsrc：root :buildSrc:test --rerun-tasks实际执行，exit0/BUILD SUCCESSFUL7s，新鲜XML1suite/8tests/8pass/0failure/error/skip。独占UUIDce5b70e2921d4698a38238016fce5bbd、实际8.3 DA36CE~1。
- 009-image：jpackageImage --rerun-tasks -Image，session7243退出0/BUILD SUCCESSFUL40s；14 actionable全部实际执行，含jlink/jpackageImage，Image隔离方式没有将测试JVM flags写进产品配置。
- 010-image-audit：原Audit-Main-Image脚本，ReviewedCommit为f656，exit0/audit.passed=true；classLeaks/fileLeaks为空、optionLeaks=false，driverExit0，发现oracle.jdbc.OracleDriver与org.postgresql.Driver，connectCalls=0。审计隔离profile UUID0bf4444e42ef48769374ba5c72e2b3ab，无凭据或原profile加载。

全量3个live跳过精确为：com.datacube.redis.RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()（缺DATACUBE_REDIS_HOST与DATACUBE_REDIS_PASSWORD显式live环境）；com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas()与postgresqlSafeDeploymentConvergesInDisposableSchemas()（缺explicit write gate及完整provider环境）。跳过不记通过，未寻找或注入原凭据。

最终八文件原SHA逐项复核与final-freeze-006一致，git diff HEAD -- src test为空，HEAD仍f656。所有Gradle session及镜像审计均已结束；worker归还唯一许可并停止源/test/runner/报告/证据写入，由root负责-text归档、raw原件复制/冻结、Git/main与CI。未生成raw-manifest、未修改任何历史raw、未提交/合并/push。

最终本轮run清单为001/002/003/004/005/006/007/008/009/010，原件均在 evidence/sql-cancel-identity-20261008-worker；失败001四产品、003一旧结构契约、005一拒绝生命周期产品原件及当时源保留，未以成功覆盖。007/008原command/exit/log/XML及原统计summary、009原command/exit/log与任务summary、010 audit.json/modules/files/config/driver日志全部保存。

范围仅SQL取消请求执行身份与必要未启动拒绝生命周期；未修改queue、关闭状态机、WriteOperation门禁或生产/FX等待常数。原生输入/原生桌面、真实JDBC驱动取消/真库/事务未知结果、OS缩放与多屏、本机外部环境/托管CI仍未验。本轮main集成/精确SHA CI由root后续完成，不能借旧结果记通过。
