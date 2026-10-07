# SQL 提交/回滚在途关闭：独立审查

## S0：基线、目标与待澄清语义

2026-10-07。维护者继续推进产品，沿用GPT-6.1-sol开发、root审查/修正/本地集成和main推送授权。main3494b670f82af1257bc63c13de91ae8fee2d29e9范围内干净；上一轮Verify37615796776四任务成功回执已读，仅作历史基线。现有C worktree干净，从该main建立codex/sql-transaction-shutdown-20261007，复用既有开发agent，不创建线程/代理。

选定上一轮明确未验的显式COMMIT/ROLLBACK在途整窗关闭组合。root独立读取SqlEditorPane真实事务按钮/确认、SerialSessionOperationQueue、JdbcEditorSession及SqlEditorCloseSequence：COMMIT/ROLLBACK是非可取消queue操作；commitAdmitted/rollback没有beginOperation，因此session.snapshot.running=false不等于物理空闲，真实queue才是关闭等待依据。mandatory先停准入/取消排队操作，等待idle后根据事务状态回滚，再破坏性清理；transactionGate失败返回FAILED_PARTIAL，保留后续资源所有权。原5秒只是warning，不改变常数。

已要求worker先给最小成功/失败矩阵再实施。待核实的产品语义：in-flight commit失败后terminal回调被抑制，mandatory若另行rollback成功，可能COMPLETED；这仅表关闭结算，不能推断提交成功或真实数据已撤销。不能直接把现状写成“正确期望”，须结合已有错误可见性和事务契约判断是否缺陷，必要时先隔离复现供root审批最小修正。当前未确认新产品缺陷、未批准最终矩阵、未执行新测试。

保持真实AppShell树入口、事务控件/原确认、queue/session、PG/Oracle runner和原mandatory guard/WindowShutdownController，只mock provider/JDBC。先合成DML形成pending，验证等待期间不cancel/interrupt/提前回滚关闭、物理完成后唯一清理、队列准入与迟到回调。沿用隔离runner，新UUID profile/8.3 temp、单Gradle，失败原件保留，定向冻结独立审查后再full/buildSrc/image及main新复验。协调脚本已换本轮前缀并解析，仅工具准备，不算产品验证。

边界：.testagent禁读/改/枚举/暂存/清理；不读原profile/连接/凭据/SQL历史/业务文件、不真库/剪贴板/原生输入/安装更新/外部联系。仅mock/合成profile/独占temp；不fetch/PR/新tag，已有v3.2.9对象与目标不动，datacube保持PAUSED。worker不提交/合并/push；root最终只按既有授权推送main并核对精确SHA CI。原生/真实驱动/OS缩放/无Gate启动/安装升级签名与完整M8待验，结束不自动扩功能。

## S1：确认缺陷与最小修正契约

root 独立核验 001-explicit-commit-red 的 command、exit、实际 Task 和新鲜 XML：compileTestJava 成功，test 实际执行；PostgreSQL/Oracle 两例均因产品期望失败，2 tests/2 failures/0 errors/0 skipped，exit1，18秒。真实手动模式、合成 DML、实际 commit 按钮及 queue COMMIT 均已通过前置断言；关闭期间 currentCancellable=false、物理未完成时无提前释放。首红不是编译、夹具或超时失败。

两例实际轨迹一致：commit-enter → commit-throw:synthetic → rollback-enter/complete → session-close → global-close；结果 COMPLETED，窗口隐藏，无失败提示，session CLOSED、commit1/rollback1/sessionClose1/globalClose1、UI finalized、标签注销。这确认提交异常在整窗关闭中被吞掉，且退出额外发起回滚，不能代表提交失败已被用户知晓或数据已撤销。

批准仅 SqlEditorPane 的最小修复：跟踪尚未由 UI terminal 正常处理的非可取消事务 attempt，整窗关闭 FX 入口稳定捕获，在真实 queue idle 后、任何事务收尾/破坏性清理前检查失败；失败沿用 FAILED_PARTIAL 的现有可见保护，保留所有权，不自动重试或额外回滚。普通 UI 已呈现处理的旧错误及时清除，不能永久阻止以后退出。SET_MODE 内的显式提交/回滚属于同一抑制路径，批准最小实际控件/原 Alert 覆盖。queue/session/关闭状态机/5秒提示常数不改。

补充审查要求：不得因后来排队 token 覆盖在途失败；如真实入口不能保证单个未处理 attempt，采用 FX 所有的小集合并捕获快照。无新 Future 等待，排队取消/同步拒绝不能制造死等。验证成功 COMMIT/ROLLBACK 跨原提示阈值、失败不额外操作、mock 已生效后抛错、物理 idle 而 UI 尚未呈现时关闭、旧错误恢复后退出、SET_MODE 同路径。所有 FAILED_PARTIAL 产品断言和轨迹记录后，夹具显式兜底清资源，不冒充产品恢复。

当前仅首红已确认，未有修复通过、全量、image 或 main 新验证。worker 持有唯一 Gradle 许可，先实施及定向，root 审核冻结后再批准完整验证。原生、真库及完整 M8 未验边界不变。

## S2：18例首轮修复验证与前置调度竞态

002-transaction-matrix 的实际 Task/XML/log 已独立核验：18项执行，16通过、2失败、0error/skip，46秒；不记整轮通过。首红两例及成功COMMIT/ROLLBACK跨原PT5S、ROLLBACK失败、mock已生效再commit异常、SET_MODE原Alert、排队mode取消不覆盖current失败、旧错误显示并明确rollback恢复均通过。两项物理queue已idle/current为空但FX错误尚未呈现的真实Stage关闭仍实际COMPLETED/自动rollback/释放/隐藏，属于剩余产品缺陷。

root独立追到AppShell→ContentTabPane→AsyncManagedTabRegistry.beforeGuards→SqlWorkspaceUi.freeze：freeze明确Always enqueue Platform.runLater，已排队的failure callback因此能先于SQL mandatory guard运行并清除attempt。仅在SQL guard入口捕获仍然太晚。已要求保留002原件及当时源码，不改race的失败保护期望、不提前直接调用pane guard绕过整窗链路。

未批准单凭root.isDisabled丢弃失败callback：非选中tab内容未必继承窗口disable，且取消退出/草稿拒绝恢复时会遗失反馈或控件粘滞。批准为本缺陷必要的最小显式应用退出边界方案讨论，要求覆盖非选中失败tab及取消后的释放，避免扩大到状态机重构。workspace freeze前原本允许的普通callback显示与guard suppress后的迟到回调须分别描述；前者必须仍保留本次退出的未知事务失败，后者保持既有抑制。

当前worker停止Gradle待方案审查；全量/buildSrc/image/main/最终CI尚未进行。两份新测试/probe逐段已读，真实AppShell、原队列/runner/guard未替换，所有物理屏障有界；FAILED_PARTIAL产品记录与fixture-only资源清理已明确分开，mock已生效状态和清理回滚计数分开保留。需要修正测试helper命名并保证自己创建的Alert失败时能关闭。下一步确定早期捕获生命周期并继续定向。

## S3：必要的两文件修正与边界首次通过

批准并审查AppShell+SqlEditorPane显式早期捕获：AppShell建立本次shutdownAttempt后、异步workspace freeze之前，遍历所有tab content，经已有SQL pane身份映射同步捕获pending事务；SQL mandatory入口再合并当前pending，真实idle后检查失败。AppShell所有现有终态FX处理分支先按identity释放本次早期快照。未引入disabled推断、失败callback丢弃/replay、新注册表或状态机变更；私有Node身份key重命名，favoriteText行为不变。

003-first-red-and-boundary-recovery 的command/exit/实际Task/新鲜XML已独立核对：8/8通过、0failure/error/skip、17秒，新UUID profile/真实8.3temp。首红PG/Oracle两例均FAILED_PARTIAL/窗口保留/失败提示，commit1、rollback0、session/globalclose0。单tab及非选中双tabrace四例，物理queue已idle/current为空且关闭入口尚无错误UI；原callback在workspace freeze前置间隙正常显示ERROR，但退出早期快照仍保留失败并阻止回滚/清理。此段与guard suppress后的迟到回调不可混为同一时序。

取消恢复两例通过真实owned .draft原子发布mover合成IOException，实际mandatory flush拒绝→CANCELLED→窗口恢复、原commit仍物理阻塞；恢复mover后commit抛错正常显示，再经实际rollback按钮恢复，第二次关闭COMPLETED。没有反射改guard、queue或捕获field；trace明确恢复→commit throw→ERROR UI→rollback→session/globalclose，产品断言先于fixture兜底。新Alert答题辅助只识别自己创建的窗口，断言失败时关闭该modal；helper命名已纠正。

已批准004-final-targeted：本轮22例、上轮17套相关回归以及favorite入口身份回归；结束后冻结2src/2test与diff/runner再审。当前没有全量/buildSrc/image/main新验证，原两轮失败及源码保留，未验边界不变。

## S4：完整定向暴露夹具时序假设

004-final-targeted 实际19套207项，206通过/1failure/0error/skip，1m50；不是整轮通过。root独立读取唯一失败Oracle commit已mock生效后抛错：产品结果FAILED_PARTIAL、showing=true、failureFeedback=true，commit1/rollback0/sessionClose0/globalClose0，committedWrites1/pendingWrites0。失败仅是SQL可见快照仍旧的断言，实际普通callback在guard真正抑制之前显示了ERROR。

根因是夹具只awaitLayoutPulses即释放JDBC，不能证明异步draft flush已经接受并调用真实queue.suppressCallbacks。已批准仅夹具修正：要求声称“guard已抑制后迟到”的在途案例，在释放JDBC前有界观察真实callbacksEnabled=false，不写状态、不改生产常数；早期race类继续验证workspace前置间隙正常ERROR呈现仍不可关闭。保留004原始XML/log/命令和当时源码，产品源码不为此改动。

同时审过空List共享identity细节：早期快照改为每次唯一ApplicationCloseCapture记录对象，内部List仍不可变；释放按记录对象身份比较，避免旧closure误清新空快照。004已使用该字节，003源码保留。下一步同一207项重跑，待全绿和最终字节冻结后才完整验证。

## S5：最终定向与源码独立审核通过

005-final-targeted-synchronized 的command、exit、实际test Task和19套新鲜XML已由root重统计：207/207通过，0failure/error/skip，1m51。新22例全部完成，四个成功非可取消慢例实际warning约5010/5014/5009/5018ms，原PT5S未改；新fixture观察真实callbacksEnabled=false后再放JDBC。已生效再失败两例产品状态committedWrites1、rollback0/close0/global0，FAILED_PARTIAL可见；fixture之后的rollback单独记录，不能解释为撤销已提交效果。

最终产品范围为AppShell三行早期捕获/终态释放及SqlEditorPane事务attempt/唯一ApplicationCloseCapture/mandatory gate/私有Node身份改名，合计59新增6删除；两个新测试/probe完整审查。执行/EXPLAIN原队列、事务会话、生产runner、关闭状态机、warning及FX helper时限未改。正常错误呈现后移除pending，早期快照跨workspace gap保留此次失败并在终态释放，取消恢复和非选中tab均走真实窗口链路。

协调侧另将上轮3494b670精确SHA CI的7个原始回执按哈希复制到baseline-ci-3494b670，避免main后续clean删除build内历史原件；此项仅历史保存，不算本轮通过。下一步核对最终冻结字节并执行分支全量、强制buildSrc、jpackageImage；目前尚无这些新结论，main和最终CI尚待。

冻结核对：root独立reviewed-source.json与worker final-freeze四源完全一致，runner也已冻结。005受验原件与最终test只有第38空白行删除12空格，逐行检查确认无其他差异，诊断保存targeted-freeze-whitespace-audit.json；005不冒称字节完全一致，后续冻结版full和main定向会重新覆盖最终字节。已准串行006 clean test、007强制root buildSrc、008强制image，任一失败须先诊断。

## S6：分支完整验证、源提交与镜像审计

root Audit-Worker重新校验冻结5文件和原始实际Task/新鲜XML：005定向207通过；006全量316套4038总/4035通过/3live跳过，5m5；007root buildSrc强制8通过，7s；008强制jpackageImage14任务执行，40s，均exit0。三跳过仍为Redis standalone及Oracle/PG SchemaDiff live，原假设理由保存，未计通过。

已仅提交2src+2test为c71dd24100644dc5a60cc674137bf0a3416412e2。镜像独立审计passed：183文件，模块/cfg无测试类、profile或测试JVM选项；独立读过driverFor实现和探针，仅发现Oracle/PG驱动，不调用connect/open。exe595968字节/SHA6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F；cfg369/SHAE53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D；modules102411629/SHAED9DDEAE2C3C79A386C17908118F9139C9FAD2AF62562612C4FDAEF6939B3071。

worker已停止全部Gradle并交还许可，最终434项原件清单由root逐项长度/SHA验证；原报告17152字节、SHA875AE479831841FE2CB6A39F20E7B7DC3C4C61AF243E1FEEAD44BD6D5C9C4040与冻结副本一致。当前进入Git原字节归档门禁，然后main合并和全新复验；本段不预报main或CI成功。所有原生/真库/安装签名与完整M8待验边界保持。
