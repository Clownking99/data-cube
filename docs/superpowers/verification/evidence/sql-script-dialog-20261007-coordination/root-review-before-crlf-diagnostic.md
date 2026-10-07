# SQL 批量错误确认：独立审查

## S0：基线和限定诊断

2026-10-07。main `25df305f8f8682d9330d86c8c5827666f4db4548`，检查范围内干净；原事务退出增量已完成推送和精确 SHA Verify 37627760993，四任务通过，Windows buildSrc:test/test/jlink 实际执行原始日志可核。该结果仅作历史基线。

本轮选定批量 SQL 错误确认与取消的竞态，范围和证据分层见对应实施计划。root 已独立阅读 JdbcScriptExecutor、SqlExecutionControl、JdbcEditorSession.cancel、SerialSessionOperationQueue 和 pane 的 askScriptError/onCancelExecution；已排队的错误框只看 tasks 是否关闭，尚未见独立取消或执行身份门禁。Executor 已有下一条语句取消检查，不能夸大为取消后必然续跑。

干净 C 工作树从当前 main 建立 `codex/sql-script-dialog-cancellation-20261007`。复用 GPT-6.1-sol，先只读诊断，不跑 Gradle、不改生产；root 待审首红方案。当前没有新测试结果，也没有确认产品缺陷。

边界保持：仅 mock/合成/独占 temp；禁访问 `.testagent`、原 profile/凭据/历史/业务文件及真库；不做原生输入、安装更新或外部联系，不改 tag，不恢复自动跟进。正常对话框选择、拒绝关闭后的执行和资源所有权均须保留。

## S1：首轮夹具诊断

root 已逐段审阅两个新测试/probe 文件。首轮 001-earlier-cancel-red 的命令、exit、XML 和实际 Task 独立核验：compileTestJava 成功，test 实际执行，2项/2失败/0error/0skip，10秒，exit1。失败是 Stage.getTitle() 可为 null 而夹具直接调用 equals，在产品事实断言前抛 NPE；不能称产品红灯。原件、源码和独占 profile 信息保存。

批准仅改为常量 equals 后新 002 复现，生产和验收期望不变。调度利用已进入的 FX 取消动作阻止后排错误框显示，后台真实 askScriptError/latch 等待由只读栈观察证明；取消按钮使用实际控件。物理连接 close 只是旧实现取消已生效的基线诊断，不是最终必须关闭连接的产品契约。夹具兜底通过该 fixture 原 Alert 的取消按钮释放后清理，与产品结算严格区分。

设计审查要求已经下发：不能先释放 policy 等待、再让旧异步 cancel 误伤下一执行；取消意图与实际取消完成必须有执行身份及顺序保障，不能只依赖可被抑制的 FX 回调终结等待。关闭只在草稿/用户决定已批准后取消询问，拒绝关闭保持原执行。当前仍无修复和通过结论。

## S2：实际产品首红及最小实现授权

002-earlier-cancel-behavior-red 的 command、exit、实际 test Task、两例新 XML 及其中原始轨迹已独立审阅：2项/2产品失败/0error/0skip，9秒，exit1。PG/Oracle 一致：controlCancelled=true，session BROKEN/AUTO_COMMIT/IDLE 但 running=true/cancelling=true；sessionClose1、statementClose1、globalClose0、cancelCalls0，仅执行 FIRST。错误框仍1个、owner=null、APPLICATION_MODAL，queueIdle=false/current=EXECUTE。说明取消已实际应用，原询问仍显示且后台等用户选择，不能解释成取消后继续执行第二条。

fixture-only 点击原 Alert 取消后，队列才结束，shell 清理 globalClose1。产品断言和兜底恢复有独立标记，不冒称产品自行恢复。首轮夹具 NPE 和本轮产品首红均保留。开发代理曾因模型容量中断，002实际已结束；root 和worker分别核对后未重复执行，仍使用原 GPT-6.1-sol。

批准限定 pane 及必要小 helper 的每执行询问身份：FX同步 seal 阻止旧框显示/接受继续；实际 cancel Callable 的 finally 终结 ABORT，再释放物理执行等待，避免旧取消误伤新执行。终结不得只依赖可被抑制的UI callback；拒绝/异常/调度失败不得制造新死等。关闭获批准才取消询问，取消关闭仍保留原选择。对话框在FX设置所属窗口。queue/session/executor及关闭状态机/timeout保持；若必须扩大这些生产范围，先回报具体依据。当前进入最小修复和精简定向矩阵，尚无绿灯或完整验证。

## S3：最小实现和两例首次转绿

root逐段审阅 pane diff及新 ScriptErrorQuestionGate：同一次执行使用独立gate，策略pending/answer由同步块保护，Alert仅FX操作；seal关闭框后showAndWait的finally不提前答复，物理cancel的finally才finish。queued show和dismiss分别携带自己的Question；旧cancel UI回调按执行身份判断。原ScriptErrorPolicy的继续/全部继续/取消按钮和300字符截断保持，Alert新增FX取得的真实所属窗口。执行前recordHistory也纳入finally清理，提交拒绝有终结；未修改queue/session/executor/关闭状态机。

003-first-two-green的命令、exit、新XML和具体轨迹已独立核验：2/2通过、0failure/error/skip、17秒。两provider均dialogCount0，queueIdle=true/current=null，session running/cancelling=false，只有FIRST被执行，statementClose1/sessionClose1/globalClose0，Stage仍显示。activeControl已在执行finally清除，日志controlCancelled=false代表无当前control，不是未取消。产品断言早于fixture清理。

此为最小两例的首次通过，源码尚未最终冻结；下一步是批准的19场景与少量异常专项、相关定向。显示中modal的关闭仍仅程序化生命周期验证，不宣称原生键盘/窗口验收。本轮尚未有全量/buildSrc/image、main或CI新结果。

## S4：完整新矩阵与夹具诊断

root完整阅读19个实际UI例及probe，并要求每个mock Connection独立closed/autoCommit、close按handle唯一断言、Statement绑定owner，避免新连接掩盖旧handle问题。整窗关闭在同一FX回合记录controller实际supplier返回的future和调用数，不另起退出重试。慢取消单例明确使用测试调度注入：暂存来自实际cancel线程的原封FX Runnable，其他仍原dispatcher，新结果出现后只送回一次，finally恢复；不称纯JDBC-only或自然调度证据。

root独立增强6项helper回归，使用有界真实虚拟线程，覆盖seal不提前释放、Runtime/Error调度拒绝、dismiss拒绝、中断标记保留及closed owner不排队；晚show只走未创建UI的已终结分支，不冒充桌面交互。没有改生产接口。开发代理第二次容量中断后复用原GPT-6.1-sol恢复，未新增线程或重复测试。

004-script-dialog-matrix实际25项，19通过/6失败/0error/skip、51秒，exit1，不算整轮通过。root独立读取原XML：3项新执行已通过真实owner2执行SECOND，但断言禁止摘要出现“取消”错误，因为SqlScriptExecutionReport.summary固定含“取消 0”。批准改为当前摘要及实际normal/failed/cancelled/updateCount，不降低业务期望。另3项单tab均FX5秒超时；静态确认AppShell安装的fileController会先询问未保存文件，夹具只答SQL事务确认，需要核对并通过原文件Alert继续，不能绕过文件guard或改超时。普通8例、整窗4例、draft拒绝1例及helper6例本次通过；原失败及当时源码保留，待诊断后新矩阵和完整相关定向。

## S5：root接管后的完整定向与冻结

GPT-6.1-sol完成产品实现及矩阵后交回唯一Gradle许可，root接管修正和最终验证。005此前未启动，没有被中断的Gradle需要重跑。原代理交接报告原字节另存agent-handoff-report.md。003阶段没有单独归档完整test/probe快照，原命令/log/XML保留，不作为最终源字节证明；006/007和后续完整验证才对应最终冻结文件。

root运行005前按源码纠正夹具owner期望：原“关闭SQL文件”归属主窗，原“关闭SQL编辑器”没有owner，本轮新增的错误询问归属主窗，不改无关旧确认框。005实际25项/24通过/1失败，22秒；日志实际证实文件“不保存”→SQL关闭确认顺序，正常选择、新执行count73和慢取消后count97/真实旧callback不覆盖新UI均通过。剩余queued单tab在未保存文件modal内嵌入错误询问，文件确认的nested loop须等待内层询问先结束；夹具只答文件确认，故FX回合不返回。这发生在关闭尚未获准前，不能为让测试通过而提前取消用户执行。

最终该合成queued单tab场景显式选择原错误询问“取消”，再由原文件/SQL guard完成原关闭，并保持观察器直到同一CloseAttempt结算；shown单tab则仍验证获准关闭自动结束错误询问、拒绝关闭可继续回答。该调整忠于实际确认顺序，不声称所有queued错误框都必须在文件确认前消失；取消按钮首红和整窗链路分别覆盖本轮修复门禁。所有产品断言、fixture清理及这一程序化modal选择分开记录，原005失败快照保留。

006新25/25通过，0error/failure/skip，20秒；007完整相关定向22套249/249通过，0skip，1m57。root独立重统计001–007的实际Task、新XML及退出码，失败仍不记通过。最终5源及隔离runner哈希由root reviewed-source.json冻结并逐一复核，final-freeze保留原件；生产范围仅SqlEditorPane与ScriptErrorQuestionGate，3个新test/probe。全量008已启动，当前尚未有全量/buildSrc/image/main/CI的新完成结论。

## S6：分支完整验证、原件归档及main集成

007定向249/249之后，008全量实际318套4063项、4060通过/3跳过/0failure/error，5m38；009强制buildSrc8/8，9秒；均actual Task和新XML独立核实。3个跳过是Redis真实服务及SchemaDiff的Oracle/PostgreSQL live例，不算通过。新源码与reviewed-source六文件哈希始终一致，五源单独提交7030423fae2669a34e0bf2501141d6f840b9bda7。010强制jpackageImage14项实际执行，40秒；011镜像183文件、无测试类/profile/测试JVM参数泄漏，隔离driverFor发现通过。已审探针不调用connect，输出connectCalls=0为源码声明，不是插桩计数。实际三产物SHA见audit.json。

worker最终报告含root接管段与原报告分别归档；459份原始文件按SHA256/长度冻结，Git blob原字节一致，报告单独-text及原件副本也一致，证据提交a5be5b2087ba686190829fd062e20abc1775d9a6。首次红灯、夹具失败和当时快照没有被通过记录覆盖。审查没有剩余阻断意见：生产只改pane和gate；gate的seal/finish顺序保护错误询问等待及实际取消结算，旧UI回调隔离有真实callback测试，不声称证明所有无错误/近完成SQL取消竞态。

main原25df305f检查无tracked改动，本轮root未提交计划/审查/coordination与worker范围不重叠；no-ff集成c5c25706d4e578092c78b0671c7cd77e32581f39。main与已审源提交在src/test/resources/buildSrc/build.gradle/workflows的Git树一致，459份worker raw/Git再次核验。main五源实际checkout哈希独立保存；换行归一化不混同于源语义变化。001-main-targeted已在新独占profile及实际8.3临时别名启动；尚无main全量/buildSrc/image或新CI完成声明。

main 001定向实际22套249/249通过、0skip，2m1，原XML/Task/exit均保留。首次父shell在纯PowerShell统计脚本后错误检查未设置的LASTEXITCODE，虽然summary已成功却报wrapper exit1，后续full未启动；记录summary-invocation-note.json，随后单独启动002-full，没有重跑定向或修改源码。002当前运行中。

## S7：main完整复验与本地交付

main c5c25706新独占profile复验完成：001定向22套249/249、0skip，2m1；002 clean全量318套4063项，4060通过/3明确live跳过/0failure/error，5m20；003强制buildSrc8/8，9秒；004强制jpackageImage14项实际执行，37秒。root再次独立解析全部新XML与实际Task/退出码，main-independent-tests.json保留结果。005镜像183文件，无测试类/profile/JVM测试参数泄漏，隔离零连接驱动发现通过。main源checkout哈希稳定，Git源树与7030423f相同；程序、cfg及modules与分支三SHA/字节完全相同，main-branch-comparison.json保存实际值。

本轮交付行为：取消发生在旧错误询问显示前时不再出现旧框或卡住执行队列；显示中的询问在已批准关闭后按原取消完成顺序释放。正常继续/全部继续/取消/X、拒绝关闭后的继续、draft失败恢复、下一次真实执行及旧取消UI callback隔离均有新回归。queued脏tab若尚处于更早文件确认，原错误框仍须按实际modal顺序回答，测试对此显式记录。未改SQL执行器/事务/关闭时限。此前失败与root统计调用失误仍保留，不以绿灯覆盖。

本地审查、提交、main合并和全套复验已完成。交接及路线图更新本轮事实与待验。最终证据提交后仅推送main，按delivery-intent.json指向的独占build回执目录，保存精确SHA Verify四任务及Windows原始日志和实际buildSrc:test/test/jlink检查；当前文档不预报CI通过。CI的linked image不是jpackage安装包，本轮不移动v3.2.9或触发新Release。既有datacube维持PAUSED，不启动下一轮。

待验保持：真实Oracle/PostgreSQL驱动错误取消/事务、原生对话框与键盘交互、OS缩放/多屏、终态进程内恢复、无Gate正式启动/闪屏、安装升级/回退/生产签名、完整M8。3个live跳过不得计入通过。本轮仅mock、合成profile及独占临时目录；无.testagent、原连接/配置/凭据/SQL历史/业务文件访问，无真库、原生输入、外部联系或安装更新。
