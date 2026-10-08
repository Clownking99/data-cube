# SQL取消执行身份独立审核

## S0 基线与诊断

2026-10-08：main eaa59237，范围内干净；旧worker干净a5be5b20，root从当前main切新codex/sql-cancel-execution-identity-20261008。旧错误询问交付仍有效，但未涵盖自然完成后的取消请求竞态。旧SHA GitHub Verify再次只读查询仍空，不能标CI通过。旧build回执原字节复制到本轮coordination/baseline-ci-eaa59237，后续clean不会丢失异常证据。

root已读pane错误询问/取消路径、JdbcEditorSession执行及状态字段、FxTaskScope提交调度；本轮目标仅诊断并修复有证据的旧取消误伤新执行问题。GPT-6.1-sol先只读方案，不改生产/跑Gradle；root继续独立阅读物理取消及资源所有权。当前没有新产品首红或新测试通过。安全和验证顺序见本轮计划。

## S1 首红方案批准

root与GPT-6.1-sol各自确认两层静态风险：pane异步任务真正运行时才读session当前操作，gate仅保护旧UI回调；服务cancel在旧Statement.cancel阻塞/异常后对当时的connection无代际保护地fallback。SqlExecutionControl的Activation只绑定Statement，不覆盖session Connection。finishOperation释放运行状态后，queue/UI是否允许下一轮须用真实链路证实。

批准只编写4个首红场景（PG/Oracle×2），生产不动，尚无Gradle许可：A限定Executor调度注入，仅暂存实际取消按钮同步提交的原ScopedFuture，第一条自然结束、第二条实际开始后原executor投递；B仅JDBC屏障让旧cancel在途、第一条自然返回、旧cancel较晚抛异常。每个Connection/Statement独立状态并绑定control。B最终契约允许保护性等待后才进入第二条，不能把“必须提前进入”当通过条件；关键是旧请求不能触碰新执行/资源，安全结算后真实按钮仍能完成新执行。FX不得等JDBC；用实际阶段/barrier及有界finally恢复，不sleep或扩大生产timeout。root先读测试再授予唯一首红运行许可。

保留契约：无活动Statement时及时连接fallback、取消失败关闭自己的连接、打开连接期间取消、严格关闭/事务所属连接、上一轮错误询问gate。最小修复需同时处理请求捕获和fallback摘除的原子所有权，单个check后无锁breakConnection不足。是否等待物理取消由首红决定，不提前扩大EXPLAIN功能范围。当前仍无新产品失败/通过结论。

## S2 首红源码审查和运行许可

root完整读取AppShellSqlCancelIdentityTest与ShellSqlCancelIdentityJdbcProbe及worker方案。4例保留真实AppShell/实际按钮/queue/session/PG、Oracle runner；独立Statement owner及Connection状态，产品断言和fixture-only清理分开打印。A暂存的是原ScopedFuture而非替换取消逻辑，恢复原executor后只投递一次；B只在JDBC屏障处阻塞/晚throw，读取queue/旧线程栈确认旧执行返回或等待取消后再按真实按钮准入。第二轮真实执行期望normal1/failed0/cancelled0/updateCount97。静态审查批准先跑001-cancel-identity-red，仅此新类；新XML与首红源码先保存，运行结束需交回唯一Gradle许可。当前生产未改、尚无首红结果。等待若为compile/fixture错须单列，不能直接当产品缺陷或未经审核重跑。

## S3 实际产品首红确认

root独立重新解析001 command/exit/实际:test/新XML：1套4项，4产品失败、0error/skip，构建28秒、suite3.084秒，exit1；compileTestJava成功。生产diff为空，5源原字节/SHA与失败快照保存。两provider结果一致：A旧SQL已自然结束、queue idle、真实按钮准入第二次SQL后，原延迟取消任务使新control cancellationRequested=true、新Statement.cancel1，连接保持；B旧Statement.cancel已进入后旧SQL返回、真实第二次执行开始，旧cancel晚throw使新执行正在使用的conn1 close1、session BROKEN，新control未取消/新Statement.cancel0。每条事实打印均早于产品断言和fixture清理。四例单独标记的fixture清理完成（各handle close1/global1），不能称产品恢复。

两层缺陷已确认；批准限定修复方向，要求worker先给具体API/同步设计：请求在发起时绑定执行，fallback和结算保护原资源；物理JDBC及等待必须后台，不能只做无锁identity前置判断；旧请求不可污染新状态。特别审核pane已接受但activeControl未发布的空隙，早捕获不能丢掉此取消或将其延后命中新操作。兼容cancel、无Statement/取消失败连接fallback、事务/关闭及旧error gate都保留。队列/关闭状态机/timeout及公共WriteOperation安全门禁不重写。当前Gradle许可已归还root，尚无生产修改/绿灯；先审设计，再实现和针对回归。

## S4 最小设计批准

批准JdbcEditorSession每次接受执行创建session所属、一次性的ExecutionHandle；pane在submitSessionOperation的可取消种类提交前生成，后台executeCancellable(handle, Callable)用ThreadLocal仅绑定这次调用上下文，finally清理/终结；不改WriteOperation安全门禁或queue。FX captureCancellation(handle)只发布该句柄意图并返回绑定Callable，无JDBC；兼容cancel()捕获当前active handle走同路径。未开始/activeControl未发布时取消由本句柄保留，在资源/runner前生效，不借用下一control。

物理cancel计数只在后台启动时，于短operationMonitor中按身份增加；finishOperation保持singleFlight等待自己的物理cancel计数归零后撤下active/running。fallback在同锁精确确认/摘除原连接和状态，JDBC.cancel/close及close-retain处理在锁外但计数finally覆盖其完成；FX捕获不等待驱动。中断不丢弃所有权，后台等待恢复中断标记。pane取消callback共用handle身份，旧error gate仍负责自己的询问结算。提交拒绝或queue取消未进入Callable也必须终结handle，不靠可被抑制的FX回调独自收尾。

root额外强调：句柄跨session/reuse/nested应拒绝；ThreadLocal所有异常路径清除；预取消须覆盖已有连接复用和manual命令，不能只检查opener；beginOperation若已发布后抛错，finally必须仍能清理。范围仅两个生产文件+针对测试。新增服务例围绕prepublication、stale/idle句柄、物理取消/重复及中断风险，EXPLAIN/Prepared通过共用包装做契约回归。worker实现后root再读源码，当前无Gradle许可，也不预告绿灯。

## S5 初稿独立审查返工

root已完整读取两生产文件初稿diff，handle所属/一次性、ThreadLocal finally移除、三个beginOperation后的预取消检查、短锁保护counter和fallback、后台finish保留singleFlight等待方向符合约束；无queue/WriteOperation/timeout改动。尚未编译/运行，不能算修复通过。

返工：pane onCancelExecution在handle为空时仍把editorSession.cancel交给后台，保留未绑定查找入口。要求改为FX同步捕获active或永远NOTHING_RUNNING的empty请求，后台不得再次查询当前执行；兼容cancel即时捕获再调用。新增empty捕获后新执行再开始也不可触碰的服务例。冻结关闭链中同步后台cancelCurrentSession的兼容fallback可保留，由停止准入和队列所有权约束。其余服务风险例编写中，完成后root审源码并授权首轮绿灯；当前无Gradle运行。

## S6 服务回归与空owner复审

root完整读更新diff和10个服务风险例（4种prepublication、finished/empty、2种预操作异常、跨session/nested/reuse/abandon、2种物理cancel含重复/中断/后继等待）。生产无参capture现已在发起时冻结active或永久empty，后台不再查当前执行；重复物理cancel返回已请求，避免旧Statement释放后重复fallback抢占原取消仍在使用的连接。暂无编译/测试。

进一步返工两点：测试Runner用call>1快速返回会使warmup后second阻塞场景未到entered，须一次性block claim；cancel线程还需显式异常与CancelOutcome断言，不能只join。非script UI回调必须有非null owner且同一identity，null==null在下一次执行完成后会重复，不能当代际保护。原4首红通过条件不改，完成这些静态修正后再授权002，不把尚未跑的10例记通过。

## S7 修正复核和首次绿灯许可

root逐行复核指定返工：空owner成功/失败callback均不能更新UI；Runner按一次性blockClaimed而非调用序号进入屏障；实际cancel线程Throwable和成功/异常对应CancelOutcome有显式断言。root计算原4UI/probe SHA与001失败快照完全一致（14D8EDCE…186D6、053F1602…14F1D），没有改产品期望。diff --check通过。批准002-cancel-identity-green只跑原4UI+新增10服务用例，运行前2生产+3测试/probe+runner归档；当前编译/运行中，尚无通过结论。广定向及full/buildSrc/image待新结果审核后再执行。

## S8 首次绿灯独立核验

root重新读取002原command/exit/XML/system-out和完整生产diff，确认实际compileJava、compileTestJava、test，exit0/BUILD SUCCESSFUL17秒，2套14项0fail/error/skip。A两provider在第二次真实SQL已开始后投递旧任务，新control未取消、Statement.cancel0、连接未关闭；B两provider在旧物理cancel结算前不准入新SQL，旧cancel晚throw仅关闭原连接，结算后真实按钮打开新连接并返回normal1/update97。四场景fixture收尾与产品事实分别标记。两原首红文件不变。无未处理生产审查项；未因通过少量测试宣称全量或发布完成。

批准保持源码不变，冻结5源+runner后按final-targeted-tasks.json的30类运行003-final-targeted（唯一Gradle许可交worker）；完成独立核对再进行full/buildSrc/image。首次读取误用了不存在的source-manifest.json文件名，实际清单为sha256.json；该读错不涉及测试失败或证据覆盖。当前无新全量/打包证据。

## S9 相关回归旧结构契约失败

003实际30套356项，355通过/1失败/0error/skip，exit1/BUILD FAILED1m57。root独立读原XML，唯一失败SqlEditorSessionContractTest.closeWaitsForSessionQueueAndUsesStrictFinalResources第114行，要求源码包含tasks.submit(editorSession::cancel；这正是本轮首红确认的迟读取当前操作入口。实际AppShell取消/事务/关闭和新14例均通过。该源码字符串断言已过期，不能为满足旧断言恢复缺陷，也不能把整轮算通过。

保留旧契约文件及003全部原件后，只更新这一条结构检查：在取消方法体内禁止旧未绑定异步入口，并确认同步capture在后台提交前且使用绑定request；其他关闭资源断言和原4行为首红期望不动。生产无需改动。root复审后重跑完整30类，最终冻结范围增加这一旧契约测试；当前尚无新全量/buildSrc/image通过。

S9修正复审：root确认diff只替换旧method-reference字符串期望，限定onCancelExecution方法体检查capture-before-submit及绑定request；其他关闭资源断言、生产和14行为例不变，diff --check通过。原契约第114行字节已加入003/source-at-failure。批准新final-freeze-004六源+runner及004同30类重跑；旧final-freeze不覆盖。结构检查仅补充审计，实际取消行为证据仍来自原首红/服务例和真实AppShell链路。

## S10 最终调用链审查发现未启动拒绝路径

004继续使用既定冻结源完成，不在测试期间改源。root额外逐段检查SerialSessionOperationQueue.scheduleNext，发现执行器RuntimeException拒绝在queue内部转换为completion.completeExceptionally；pane.submit的catch不会运行，现有whenComplete又只处理isCancelled，因此尚未启动的ExecutionHandle未失效，callback被suppress时也不会得到生命周期收尾。这是S4已要求的拒绝路径遗漏，不能用此前正常取消通过代替。

要求worker先保留004实际结果，再设计最小真实queue拒绝回归，保留原4UI首红文件；首红后只修completion结算的abandon逻辑（服务abandon只终结未启动handle）。生产仍限定原两个文件，不改queue/关闭状态机或timeout；后续必须按最终新源码重新跑相关及完整验证。当前没有交付或全量完成结论。

004新XML独立统计30套356/356通过、0skip/fail/error，exit0/BUILD SUCCESSFUL2m11。该轮完成了旧结构断言修正的验证，源码七文件稳定；它不覆盖刚发现的queue拒绝遗漏，也不是最终修复后的证据。批准新增一项EXECUTE真实queue拒绝测试（同一路径不复制EXPLAIN），先存原件并审源码，再首红，不自动改生产。

root已完整审读SqlEditorCancellationSubmissionTest：仅该pane的queue runner换为独立已关闭runner，原共享runner保留；同一真实submit捕获RuntimeException，callbacks明确suppressed，检查queue idle/实际operation0/callback0后尝试复用所保留handle。finally恢复runner/reopen/控件，原Fixture仍自行按真实关闭链清理。该例为任务准入失败的生命周期证据，不称新的用户SQL执行/原生交互。批准005单例首红，七源+runner原件冻结，生产不改；后续最终定向增加此类至31类，旧30类列表另存保留。

005原command/exit/新XML/system-out经root独立核实：1项/1产品失败/0error/skip，exit1/BUILD FAILED10秒。实际queue current=null/idle=true、operation0/callback0且原runner未关闭；测试随后实际成功复用旧handle并打印oldHandleWasReused=true，期望IllegalState未抛。fixture恢复/原shell清理单独打印，connection和statement均空、global close1，不能称产品自行恢复。七源+runner当时原件/SHA已保存。

批准仅whenComplete无条件调用abandonExecution；其内部只终结未started handle，因此覆盖queue内部拒绝/queued取消而不提前终结已运行操作。生产仍原两文件，所有行为测试不改。root复审最终diff及重新冻结七源+runner后，006将运行31类完整相关回归，随后才提交源和full/buildSrc/image。

## S11 最终源复审与完整回归许可

root复核最终completion修复仅移除isCancelled条件并加作用说明；其余服务及全部测试和before-005完全同SHA。原4UI/probe首红字节仍不变。八文件（七源+runner）重新冻结至reviewed-source-final.json，diff --check通过。最终审查无剩余阻断项；明确不保证真实驱动取消必然及时返回，实际关闭时限和FAILED_PARTIAL隔离规则未改，真驱动需另取证。

006-final-targeted使用新增拒绝类后的31类，新的final-freeze-006保留最终原件；旧30列表和所有失败/通过快照均保留，不覆盖历史。唯一Gradle许可交worker，完成独立审核后方可提交源码和进行clean全量、强制buildSrc、强制jpackageImage。当前尚无最终源广定向/全量/打包通过结论。

## S12 最终相关回归通过与源提交

006新原command/exit/XML经root独立解析：31套357/357通过、0fail/error/skip，实际compileJava/test，BUILD SUCCESSFUL1m59。新增拒绝例在同样queue idle/operation0/callback0条件下，旧handle复用明确抛IllegalState，无oldHandleWasReused打印，原runner及fixture正常收尾。最终八文件SHA完全稳定，原四首红文件与001同字节。

root精确暂存并只提交七个源码/测试文件，提交f656be084cecf20e437091e0bd473673dd6d9d2e；生产仍仅pane与session，queue/WriteOperation/关闭状态机/timeout未改，源范围干净。批准worker依次007 clean全量、008强制buildSrc、009强制jpackageImage、010镜像审计，任一失败即停止保存原件。当前这些尚未完成，不冒称全量或main已通过。

## S13 分支全量和buildSrc

007 clean全量实际321套4078项，4075通过/3明确live跳过/0failure/error，exit0/BUILD SUCCESSFUL5m27；008强制buildSrc实际1套8/8、0skip，7秒。root逐份重新解析新XML、actual Task及exit并汇总worker-independent-tests.json，001/003/005失败仍保留失败状态。三跳过分别为Redis真实服务和SchemaDiff Oracle/PostgreSQL安全部署所需的显式live环境未启用，不算通过。

源提交仍f656，最终源稳定。009强制jpackageImage及010镜像审计待结束再独立核对；尚未main合并/复验，没有新CI或原生/真库结果。

## S14 镜像审核、归档与main集成

009强制jpackageImage实际14项任务执行、exit0/40秒；010审计183文件，未发现测试类、profile或测试JVM参数泄漏。root重新计算实际DataCube.exe、cfg和modules三项字节/SHA并与audit一致；driverFor探针及实现已读，仅Class.forName/DriverManager.getDriver，无connect调用。connectCalls=0是已审源码声明，不是插桩计数，不称实际连库验证。最终八文件仍与审核SHA一致。

开发报告已读且停写；报告保存之后的多余text=片段出现非终止shell诊断（代理报告），root确认S6及最终结论已完整落盘，未影响任何Gradle原件。报告以-text及原件副本保存。566份raw按长度/SHA256冻结并与Git blob字节一致，分支证据提交dcdfa288f1552634c4fe7736385be55c2266475d。首红、过期结构断言和拒绝补验均按各自实际结果保留。

main从eaa经no-ff集成为fd5159ce731d6ffa8532bbc4ac97550c70f160ca，源Git树与f656完全一致，566份raw合并后再次逐字节核验。main七源实际checkout哈希独立保存（源语义与换行字节分开记录）；main 001新profile定向已启动。尚无main全量/buildSrc/image或最终SHA CI完成结论。

main 001新定向实际31套357/357、0skip/fail/error，exit0/BUILD SUCCESSFUL2m2；实际compileJava、compileTestJava、test，新XML已独立统计。002-main-full已从clean启动新独占profile，当前未结束。报告变化不涉及源码，不用旧分支通过替代本次main证据。

main 002 clean全量新321套4078项，4075通过/3相同live跳过/0failure/error，exit0/BUILD SUCCESSFUL5m7；003强制buildSrc实际8/8，7秒，0skip。root独立解析新XML与actual Task，main-independent-tests.json记录本轮而非旧分支证据。七源与worker文本仅Windows换行差异，归一化后完全相同，main实际checkout哈希稳定。004强制镜像正在生成，完成审计后再更新最终交接。

## S15 main完整复验与本地交付

main fd5159ce的001定向357/357（2m2）、002 clean全量4075通过/3明确live跳过（5m7）、003强制buildSrc8/8（7秒）均有新原command/exit/XML和actual Task。004强制jpackageImage实际14任务、exit0/35秒；005镜像183文件隔离/仅驱动发现审计通过。root再次读取审计并重算实际程序/cfg/modules SHA，与分支三项字节及SHA完全相同，main-branch-comparison.json保存实际值。七源checkout哈希始终稳定，Git源树与f656一致；与worker原件仅CRLF换行差异已单独验证。

本轮交付：旧延迟取消请求不能取消后继SQL；旧物理cancel晚异常也不能关闭后继执行的连接，结算前保留原执行/连接所有权且FX不等JDBC。接受后activeControl尚未发布的取消按自身句柄消耗，重复/空请求/中断、prepare/explain及已有连接复用、事务/关闭保持；queue内部拒绝和queued取消同样终结未启动句柄。正常执行、事务门禁、只读/生产确认及旧错误询问关闭链均通过相关和全量回归，未改这些公共状态机/超时。

本地审查、源码/证据提交、main合并和全套复验已完成，交接、路线和M8待验行已更新。本轮只有mock、合成profile和独占临时目录；无.testagent、原凭据/配置/SQL历史/业务文件或真库访问，无原生输入、安装更新或外部联系。A含明确的原ScopedFuture调度注入，B含mock JDBC屏障与只读等待观察，拒绝例反射真实私有提交；不能称自然竞态、真驱动或原生桌面验收。

待验：真实Oracle/PostgreSQL驱动取消/事务、剩余原生对话框/键盘与OS缩放/多屏、终态进程内恢复、无Gate启动/闪屏、安装升级/回退/生产签名及完整M8。3项live跳过不计通过。旧main eaa的Verify仍无记录、原因未明，其15份原回执已保留；本次最终main证据提交后按delivery-intent.json所指独占build目录推送main、保存remote refs和精确SHA CI实际查询，再核Windows原日志（若产生）。本段不预报CI通过；无运行必须记为待验，不用旧绿灯/空提交/改workflow或改tag代替。v3.2.9保持，datacube仍PAUSED，不自动下一轮。
