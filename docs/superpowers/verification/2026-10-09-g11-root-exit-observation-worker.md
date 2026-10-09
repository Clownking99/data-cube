# G11 root exit observation — source checkpoint

2026-10-09，Asia/Shanghai。分支`codex/g11-root-exit-observation-20261009`，基线`e368a1b16bdee226925b363f06b4dc4f003c1f0f`。本检查点只有源码/控制准备与语法检查；没有执行工程、进程控制、Gradle或提交。旧失败P3与所有历史冻结原件只读，原三个PID测试不改。

精确diff：`evidence/g11-p2-root-exit-source-abddf454863f4846921eea16c475b20d/source.diff`，SHA256 **9818FBB4A7482272C6355D70AF5BF07C51FECBB17341688D1CEA749AD4A2627D**；同根source-identities.json列出12工具当前SHA/长度和4个修改路径。只改OwnedProcessHost.ps1、VerificationCore.psm1、run-stage.ps1、check-core.py；12文件闭包及导出接口不增加，四契约、stage-policy、原过滤器/skip/预算/流容量不变。四文件保留基线LF，全部12工具CRLF计数为0。

## 组合根因

只读原件确认同一个PID21608/startTimeUtc在host receipt和captured handle均实际退出0，但root-exit.json缺失，顶层rootExited=false/rootExitCode=null却status=passed。相同PowerShell7.6.5的只读解析实测：默认ConvertFrom-Json将ISO日期转为System.DateTime，与捕获ISO字符串比较false；DateKind String保留System.String、ordinal比较true。loop尾快退出漏事件、默认日期转换导致handle身份匹配失效、passed门禁缺失共同造成矛盾，不以一个fallback代替闭环。

## 身份与退出协议

host持有Process.Start返回的实际root Process/handle，先取PID与StartTime.ToUniversalTime().ToString('o')。identity仍是两个精确字段pid/startTimeUtc，采用同目录CreateNew临时文件、flush/close再Move原子发布。root-exit/v1事件精确六字段：schema、pid、startTimeUtc、exitCode、observationTick、elapsedMs；PID为正int32，UTC字符串必须七位小数Z且可精确roundtrip，exitCode为int32范围整数，tick为正且不晚于当前同机Stopwatch，elapsed为非负整数。

Observe-RootExit统一覆盖loop头、满足退出条件的loop尾、loop后、finally；仅在实际handle.HasExited后读取ExitCode。首次exit/tick固定，重复调用不覆盖原事件。非零首因按同一个tick登记；文件发布失败保持失败，不用receipt猜测退出。

parent循环中与收尾迟读身份全部通过Read-RootIdentity，显式ConvertFrom-Json -AsHashtable -DateKind String和严格schema/类型/UTC/ordinal校验。事件必须与identity完整PID/startTime一致，且不得把host自身PID当root。最终Resolve-RootExitProof强制与host receipt的pid/startTime/exit/tick/布尔观察状态一致；若parent捕获到对应PID，实际handle的startTime和已观察ExitCode也必须一致。缺identity/event、损坏JSON、错误类型、矛盾及无根观察都拒绝passed，新增rootExitProof/rootExitEvidenceError记录证据来源与拒绝原因。

短命root允许parent没有轮询捕获。信任链为：经过路径/角色/exe/12工具SHA准入的独占scope → frozen host request → host实际handle先赋私有Job → parent才释放gate → host以受控环境启动root并持有其实际handle → 原子identity/event与request指定路径绑定 → parent再次严格校验并交叉核对host summary → Job自然清零、host退出、双流/已捕获handles实际结算。证据来源明确为host-held-root-handle-event；不是只用PID、不是复制host汇总，也不声称证明被委派到Job之外的后代。

最终passed还要求根退出0、完整rootProof、实际Job/host/已捕获handle结算、host双流EOF/无错误/无截断及既有父泵检查。已有root7、取消、预算、日志等首因按原单调tick规则保留；退出证据拒绝在已存在失败时追加次因，失败不会因后续事件完整而变passed。

assignment pre-gate失败：若host实际direct handle已退出、Job查询空、gate未释放且两个pump从未启动，可以保留ownedSettlement=complete的物理结算事实；此时工具root未启动，rootProof缺失、主因仍PARENT_FAILURE，绝不passed、不虚构EOF或后代捕获。已经启动pump的异常路径必须实际任务完成；已捕获handles也必须退出。start失败没有host退出事实，仍不声明完整结算。原赋Job/start/unobserved-settlement控制及其预期不放宽。

## 待执行确定性控制

新增check-core.root_exit_checks / --check-root-exits，18项，与原21进程控制分开。仍调用冻结run-stage及有界outer，inner7000ms/settle1500ms、outer22秒，不新增预算或重试到绿。每项保留原request、实际返回码、流、身份/event/receipt和Job结算；证据变异前复制`.before-fixture-fault`原件。所有fault枚举在parent准入前校验stage=fixture；host仅接受三个明确尾部gate fault。

| 控制名称 | 确定触发 | 预期（尚未执行） |
|---|---|---|
| exit-tail-zero | helper等tail-release；host本轮已见HasExited=false后才放行，等待实际退出及双泵；tailBoundaryForced=true | 原子event/rootProof 0，passed，首次tick稳定 |
| exit-tail-seven | 同一尾部边界，helper实际退出7 | NONZERO_EXIT、真实root7/event7，完整结算 |
| exit-late-identity | 同一尾部边界；parent跳过循环身份读取及capture，收尾才读identity | capturedDescendants为空、parentCaptured=false，但强身份/event/exit/tick一致且passed；覆盖组合缺陷 |
| exit-no-capture-zero | parent不捕获handles，helper短命0 | 明确host-held事件证明，passed，不伪造capture |
| exit-no-capture-seven | parent不捕获handles，helper短命7 | 真实root7，NONZERO_EXIT，event强身份完整 |
| exit-missing-event | 真实0退出结算后，仅删除own event，原件保留 | ROOT_EXIT_EVIDENCE拒绝；即使summary/handle均0也不接受 |
| exit-corrupt-event | own event改为损坏JSON | ROOT_EXIT_EVIDENCE拒绝 |
| exit-wrong-pid | own event PID与identity不一致 | identity mismatch拒绝 |
| exit-wrong-time | own event startTime与identity不一致 | identity mismatch拒绝，不按PID猜测 |
| exit-wrong-code | event改7，actual host/handle为0 | host/capture contradiction拒绝，不把注入7伪装真实exit |
| exit-missing-identity | 删除own identity | missing identity拒绝，不能用summary fallback |
| exit-corrupt-identity | own identity损坏JSON | identity解析拒绝 |
| exit-summary-mismatch | own host summary exitCode改7，与事件/实际退出矛盾 | host contradiction拒绝 |
| exit-date-coercion | final身份解析故意使用默认DateTime转换 | ROOT_IDENTITY_TIME_TYPE拒绝，而正常/late路径必须保留String |
| exit-root-is-host | identity/event PID都改为hostPID | ROOT_IDENTITY_IS_HOST拒绝，不混淆两个handle |
| exit-missing-seven | helper真实7先被观察，结算后删除event | 首因NONZERO_EXIT/root7保留；缺事件只追加次因 |
| exit-cancel-missing | 原3秒取消continuous helper，事件缺失 | 实际CANCELLED、cancelled=true；缺证据为次因，仍检查物理收尾 |
| exit-budget-missing | continuous helper按原7秒预算退出处理，事件缺失 | DEADLINE首因保留；不宣称未知root事件已完整 |

原21进程/阶段控制保持独立原预期：短命0/7、argv、双流、持续输出deadline、overflow首因、root7后迟闭/溢出、detached descendant、启动/assignment失败、unobserved settlement、精确cap边界、取消、工具身份变更、compile主因及精确skip政策。两组均复用原独立邻居存活检查和最终direct neighbor wait；不杀其他进程，不重定义0/7/124语义。

## 当前证据与待验收项

纯语法检查：3个PowerShell文件Parser.ParseFile无错误；Python ast.parse通过；限定四路径git diff --check通过。以上只证明源码语法，不证明18项新控制或旧矩阵已通过。下一步等待root源码准入与独占执行授权后，冻结新12文件闭包再执行授权矩阵/工程阶段。当前不提交、merge、push或重跑CI；不访问.testagent或旧.g10脚本，不运行真实服务。

## 最终源码稿：控制汇总门禁补充

root预审要求已落实：process_checks只对expected非空的负例解释outer NONZERO_EXIT；正例必须receipt.status=passed且实际outer code=0，负例必须receipt.status=failed且实际code非0，同时原primaryFailure精确/前缀规则仍成立。正例的实际process/v1必须rootExited=true、rootExitCode=observedRootExitCode=0、rootExitProof非空且exitCode=0、ownedSettlement=complete。复合skip-live从processReceipt读取实际process并验证status/schema/根证明，不误读顶层不存在的root字段。原21预期及预算未改。

最终四文件精确diff在`evidence/g11-p2-root-exit-final-source-0c7e63f31b1043aab36e321208837958/source.diff`，SHA256 **808B5CEBA61F1A82E47D8FA43CA7DD3D93E76CC9EFD9400D7BB74F4B382A7580**；同根source-identities.json为最终12工具身份。前一source checkpoint原件保留。三个PowerShell Parser与Python AST、限定git diff --check再次通过，仍未执行任何新控制/工程，不提交。

## 98项冻结控制首轮：拒绝并暂停

root准入后，冻结包`g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935`保存了准入版12工具、实际controller/spec及15文件entry-manifest，SHA **4894AE513AF128A8B052E0AF59D4A62091294AF04EEFB357830E9FFF454D19DB**。实际Python调用为-I -S -B，未启用-O。21 Python与原21 process均通过；新增18中的第一项exit-tail-zero实际失败，controller退出1并立即停止。其余17 root-exit、31政策/角色/镜像、7 outer尚未运行，不声称98通过；没有运行Gradle或工程。

失败原件：exit-tail-zero inner primary=DEADLINE、root/event未完整、host及root实际被Job终止为124，host receipt缺失；root-exit.json缺失被ROOT_EXIT_EVIDENCE正确追加次因，status=failed。inner ownedSettlement=complete、outer实际exit1/自然Job查询empty、neighborStillRunning=true。控制汇总未把此正例错误包装为通过。

只读诊断确认request.argv gate与host identity+'.tail-release'规范路径相同，文件实际内容release。路径长**264字符**：PowerShell7.6.5/.NET10.0.11 Exists=true且ReadAllText=release；同一Python3.12.14 Path.is_file=false、stat FileNotFoundError/WinError3。helper实际argv正确进入exit-tail分支，但等不到其API可见的gate，最终按原预算停止。诊断源码、实际argv/exit0、双流原件在`evidence/g11-p2-root-exit-path-fix-f2651246cd2e4a219312a113267034d9/`，没有通过重跑得出此结论。

必要最小源码diff为同根source-delta.diff：check-core.py增加stdlib uuid导入，并将synthetic evidence根从拼接整个matrix目录名改为固定前缀+独立UUID+mode。保持路径规则、实际尾部门协议、原case预期/所有预算不变；不使用被禁止的扩展路径前缀，不放宽Path.is_file判断、不增加sleep/重试。原控制器已执行的assert保持原件；另准备controller-next.py供下一新包使用，将身份/组数量/98总数门禁全部转为显式require。新工具与controller只做AST语法检查，未执行进程控制或工程重验；等待root对这个必要diff准入。

本次拒绝轮次与诊断已封存为`evidence/g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935-rejected/manifest.json`，accepted=false，**49根/736文件/8413693字节**，SHA **95A136CD033D36FDCEE971106B3E84BC6D01CF056CBAA352099E52FF7FAC3624**。长路径原件使用.NET只读枚举/hash封存，未删改或重命名；包含实际冻结工具/控制器、首次失败、42项已完成控制原件、邻居退出、路径诊断和实际seal-rejected.ps1。旧P1/P2/P3/当前失败源文件不改。封存根此后停写；本报告位于这些根之外。

## 短根与归档配套最终稿（未执行）

新增审查目录`evidence/g11-p2-root-exit-controller-review-b1c95f19622f4eabb00d399aa91c6e33/`保存同一最小source-delta.diff及controller-next.py。后者SHA **4DE7B59D3B0EE21F9DEDB4042D03A79ABB90FF38BBCEDD0E656F2B974486517B**，AST检查通过，assert数量0；没有改写前一已封存controller/诊断。

controller从每例实际保存在本轮process/root-exit/outer目录中的spec读取stageEvidence/out，校验精确18字段、repo/本轮frozen tools/执行文件、fixture阶段、两类case allowlist。synthetic stage根必须为本repo evidence下`g11-p2-synthetic-<32位UUID>-<实际fixture>`，UUID不得重复，out严格为该stage根加-owner；outer7根单独验证实际outerFixture与owner/stage配对。仅访问这些显式声明根，再由scope文件中的owned/Repo/EvidenceRoot/stage/runtime父根和12工具SHA/声明路径验证身份。归档清单archive-roots.json逐项记录实际spec、stage/out、scope和声明但未创建的根；runtime只记录排除事实，绝不枚举或拷贝。

这些根在成功与首失败路径都由同一收集器保存，不再按旧长目录公式猜测。工具变更控制的frozen payload原件仍可如实归档：检查scope中的原始12工具绑定身份/路径，不能把故障注入后的实际bytes错误要求成未变。后续封存只消费该显式archive-roots.json；旧P2/P3历史freeze/audit脚本完全不动。当前只准备源码/语法审查，尚未重新冻结或运行98项，等待root准入。

## 第二轮完整98控制：通过并封存，工程尚未授权

root只读path-fix-admission.json准入后，本轮从零执行全部98项，没有复用拒绝轮次42项。新包为`evidence/g11-p2-rx2-e46234f8c9fa48e9b504156a3f0c9054`，12工具均与准入SHA一致；实际控制器使用准入的 **47200cd5876a23e6fd59f652f3a7b7c6ef34fbbdad0a07c45f98ba370b0d4c55**，不是配套提案4de7版本。只读归档器独立冻结，SHA **b7b196d457cbcfc2ea79a5109488914e002c82dabeff03eadf46a66f689809eb**。16文件entry-manifest SHA **50dd4d52b58681b8d4728b5a8639c5c93f5b3a702aec20bff4ca8d289f168be3**；tool-manifest SHA **ed50ebc85e6aab51ac42b49d033381eedb472131352e7d196da48f060707d7df**。

实际保存的run-controls.ps1依次以Python -I -S -B执行controller.py和只读archive-controls.py，operator-result记录二者实际退出码均0、engineering=false。21 Python、21原process、18 root-exit、31政策/角色/镜像（16 cases+15 rolePolicy）、7 outer全部通过。尾边界实际强制命中；短根gate已实际可见；失败证据拒绝、首因保留、无捕获/晚身份及邻居存活由冻结检查器实际验证。控制预算及原预期未改，没有运行Gradle。

独立audit-seal.py再次核对组原始计数、16入口和操作脚本身份、12工作工具身份、39 process控制的邻居存活/outer实际Job empty、成功根证明和尾边界及原始双流长度/SHA。第一次审计仅因政策计数漏加rolePolicy而退出1，未写manifest；该脚本v1及错误说明已保留。修正审计计数后实际退出0，未重跑任何控制。最终清单只消费本轮archive-roots.json的显式91根，逐组件拒绝禁止路径及链接；未复制runtime/JDK/cache/build或整个项目。

封存清单`evidence/g11-p2-rx2-e46234f8c9fa48e9b504156a3f0c9054-frozen/manifest.json`：**91根/1357文件/16591899字节**，SHA **b2de1c70097c8baa033030a469dbca6e10a2d133b4ee924d2325e08ffaedcce9**。这些根此后停写；两次源码checkpoint、拒绝轮次及诊断原件继续保持。限定四工具git diff --check通过。当前基线仍e368a1b16bdee226925b363f06b4dc4f003c1f0f，仅四工具工作区修复，未提交/merge/push；停在root独立复核节点，工程阶段须另行授权。

## 工程授权与新包执行中

实际controller.py SHA256 **14d3a048f13cc2c8c299ef071c66233d6ab5699a5b4f04c9850b4bcda2ad60cb**，运行后不改入口字节。

root已独立接受rx2 98控制，controls-review.json明确engineeringAuthorized=true；worker独占Gradle执行。新包`evidence/g11-p2-eng-11965af76c694c6798f2074fff74d8bf`，19文件entry-manifest SHA **972e1300e6b9dbada9cbc6208fbec0e956c30d433ca396e15bd8e595ce1db939**，包括实际controller.py、run-engineering.ps1、prepare.py、config/inputs/controls-binding/tool-manifest及12工具。875输入路径沿用上一轮清单，testedCommit更新为实际HEAD e368a1b16bdee226925b363f06b4dc4f003c1f0f。controls-binding精确绑定rx2冻结清单及相同12工具，完整21/21/18/31/7组门禁保留；未重复运行已接受控制。

实际Python -I -S -B入口由已冻结run-engineering.ps1执行，命令写入operator-command.json，双流原件合并保存在controller.log。按原660/1000/240/660/200秒outer预算及原stage-policy内部预算依次执行targeted/full/buildsrc/image/linked。每阶段实际spec保存唯一短UUID stage/out，image到linked绑定实际scope；每阶段全部process原件必须通过根identity/event/host/proof/captured、实际0、owned结算及四双流身份核对，outer需实际0/Jobempty/EOF。XML独立重算并单列11 native RedisPane。当前执行中，不提前声明工程通过，不提交/合并/push。

## 工程首异常：审计范围误计，已停止并封存

修正提案controller-next.py SHA256 **cfc783054449d410042f9181fe5ae307ca46adad0e918e958130aff9686afbe8**，仅AST解析通过，未执行。

本轮controller实际退出1，首次异常为`ENGINEERING_REFUSAL:11 native RedisPane`，full/buildsrc/image/linked均未运行。targeted actual exit0、outer actual0/Jobempty/双流EOF，stage status=passed；独立诊断实际exit0，重新只读核对10个process/v1的identity/event/host/proof/captured实际根0、ownedSettlement complete、四流长度/SHA与EOF，以及875输入前后相同。原始XML独立重算56 suites/292 tests/**291 passed/1精确Redis live skip/0 failures/0 errors**。

原因在新controller的native行收集表达式：`classname.startswith('com.datacube.fx.RedisPane')`把11项native `RedisPaneBudgetTest`与另外1项`RedisPaneCloseSequenceTest.queueFailureStillAttemptsSessionClose()`合计12，再要求11，属于新增审计入口误计。11 native实际全部passed，另1非native也passed；共享12工具、测试/产品/过滤/skip/预算均未修改。必要最小修正只将该表达式改为精确`classname=='com.datacube.fx.RedisPaneBudgetTest'`，提案及minimal.diff保存于`evidence/g11-p2-eng-native-review-3c86ac32bf824e729d81c7ef043c885a`；原入口不改、不重跑到绿。

本轮包、实际targeted spec导出的stage/out共3根已封存：`evidence/g11-p2-eng-11965af76c694c6798f2074fff74d8bf-rejected/manifest.json`，accepted=false、engineeringComplete=false，**213文件/1767823字节**，SHA **9cc7a935cbfe5ac1d7c4826c9cdb5e4ceff99be3bb88438ace25002b5bb3552f**。包括原冻结入口、真实controller exit1、targeted actual0、原XML/日志、诊断入口和全部强证明；未复制或枚举runtime。封存根停写，工作报告在根外。已按首异常即停要求停在root复核节点，无提交/合并/push。

## 精确targeted续接（执行中）

root已独立接受原targeted actual0及全部XML/强证明，并准许一次精确续接。新包`evidence/g11-p2-eng2-861d631887ec4c9790f3deb3f062ecae`，controller SHA **e5c479c2c888047bf769554a0f49ffedacce364aa51ed4cfd06247784717959b**，20文件entry-manifest SHA **33a8197ab6814b3d8c86bbd90eb7f74b023847438aecb1a01ed6ffded6213c07**。原12工具和875输入不变。resume-targeted.json显式记录首轮213文件manifest SHA、原spec/actualExit0/实际scope和原controllerActualExit1；入口先验证全清单、同12工具、当前875基线一致，再用修正后audit_stage只读复审原targeted并写入progress（executionOrigin=immutable-first-controller，executionRepeated=false）。新入口只运行full/buildsrc/image/linked四阶段，不重复98控制或targeted，不修改原冻结根。新增逻辑仅限本次显式绑定，不是泛化恢复平台。

续接入口已实际绑定原targeted并通过修正后审计。full实际outer退出0，351 suites/4724 tests/**4721 passed/3精确live skips/0 failures/0 errors**，11 RedisPaneBudgetTest全部passed；buildsrc实际outer退出0，1 suite/8 tests/**8 passed/0 skip/0 failures/0 errors**。两阶段audit_stage已经核对每个process强根证明与完整双流、outer Jobempty及875输入一致，阶段spec/actual-exit/raw/audit都在新包或其实际UUID根。image进行中，linked尚未运行，工程尚未全部完成。

## 工程完成与最终封存

续接controller及run-engineering.ps1均实际退出0，全部阶段status=passed。targeted来自第一入口的实际运行，仅只读复核绑定，新入口没有重跑它；第一入口actual1原件继续完整保留。新入口只实际执行full/buildsrc/image/linked四阶段，未重复98控制。finish.py实际-I -S -B执行退出0，再次核对20入口文件、全部12共享工具、875输入前后及当前身份、五阶段57个process强根证明及228条inner原始流元数据、10条outer原始流、5/5/5/5/6个执行文件身份。所有进程均实际root0、host0、identity/event/host/proof相符、captured handle已退出、ownedSettlement complete、双流EOF且无截断/错误；所有outer实际0、Jobempty、流完整。

| 阶段 | 原始验收 | 来源 |
|---|---|---|
| targeted | 56 suites / 292 tests / 291 passed / 1精确Redis live skip；11 native全部passed | 第一入口实际运行，原controller exit1仅因审计范围误计；本入口只读绑定 |
| full | 351 suites / 4724 tests / 4721 passed / 3精确live skips；11 native全部passed | 续接入口实际运行 |
| buildsrc | 1 suite / 8 tests / 8 passed / 0 skip，强制rerun | 续接入口实际运行 |
| image | 明确image阶段，无测试XML；183文件完整镜像清单 | 续接入口实际运行 |
| linked | 408源/类型、26088镜像类无泄漏；原四命令实际0 | 续接入口实际运行 |

测试各阶段均0 failures/errors。linked的原始driver输出为oracle.jdbc.OracleDriver、org.postgresql.Driver及`connectCalls=0; no credentials or user profile loaded`；原始Redis输出确认`G10_LINKED_REDIS=true`、`socketsSettled=true; realServices=0`。image inventory SHA **b7daf23d0a99c294968cd4936cff84c7625aadb1f42394ccbf8b3e82206ed5d7**，source image清单/image-before/image-after完整相同，四个image core身份、runtime marker/source scope/result/manifest及跨阶段输入绑定保留。未复制runtime/JDK/cache/build或整项目到证据，未访问真实服务。

本次工程封存清单：`evidence/g11-p2-eng2-861d631887ec4c9790f3deb3f062ecae-frozen/manifest.json`，**9根/995文件/20084399字节**，SHA **02cb7a0b73bd7647b3ddaead8fdf2395d5bc51cc4c1f5ea8368f82c1da77e479**。实际spec/scope派生本次四阶段stage/out与包根，不按旧公式猜测；finish.py、engineering-audit.json、archive-roots.json、入口/命令/实际退出、XML、原日志、产物manifest和执行文件复核均在封存中。随后PowerShell独立重算995文件/20084399字节全部匹配，manifest SHA相同。封存根此后停写。

身份关系明确：已接受98控制manifest **b2de1c70097c8baa033030a469dbca6e10a2d133b4ee924d2325e08ffaedcce9**；原targeted/第一入口失败manifest **9cc7a935cbfe5ac1d7c4826c9cdb5e4ceff99be3bb88438ace25002b5bb3552f**；本次工程manifest **02cb7a0b73bd7647b3ddaead8fdf2395d5bc51cc4c1f5ea8368f82c1da77e479**。三者分别封存并以精确SHA绑定，同一12工具版本；没有把首次controller exit1改写为成功。此前长路径拒绝轮次49根/736文件manifest **95a136cd033d36fdcee971106b3e84bc6d01cf056cbaa352099e52ff7fac3624**仍原样保留，未来运输须.NET原字节读取/hash，不能因Python长路径不可见而漏文件。

最终限定四工具git diff --check通过；产品/测试/buildSrc/build/settings/resources/CI没有工作区diff，index未暂存本次工具或证据。HEAD保持e368a1b16bdee226925b363f06b4dc4f003c1f0f，原三个PID测试未改。当前仅完成验证与报告，**无commit/merge/push**；停止工程执行，交root独立复核。

## root验收后精确本地交付

root已独立接受全部工程并授权两笔本地提交，仍不允许merge/push/PR/tag。第一笔 **a59b3f15ae21c1514acfc5060b15ab41fe90c94b** 仅四共享工具，12工具当前SHA与rx2准入一致。第二笔交付依据四份精确manifest逐项读取.NET原字节并校验，不整棵evidence盲add；额外仅纳入两次source checkpoint、controller配套提案、native精确修正提案、报告、报告文件的精确-text规则与交付核验入口。现有属性行不改，仅追加本报告规则。

完整交付路径为`evidence/g11-p2-delivery-4830a1fcc6894ab9ab51e10622c99cf4/delivery-paths.nul`，每个payload的长度/SHA为同根`delivery-payload.json`；payload文件与路径清单自身由audit-blobs.ps1单独重算并核对Git blob。交付入口prepare-delivery.ps1从四份manifest及四个明确额外根建立allowlist，访问前拒绝禁止路径和reparse；长264字符首失败gate用.NET ReadAllBytes读取，不改名不遗漏。Git仅单命令使用`-c core.longpaths=true`，不改全局配置。audit-blobs.ps1核对暂存/commit精确路径集合及四工具第一提交，Git cat-file --batch原始二进制输出逐blob SHA256/长度和磁盘相同，回执保存在仓库外本会话visualizations目录，避免回执自引用。

本节为最终报告字节，随后只处理精确暂存、blob核对和第二笔提交，不再运行Gradle/控制或改写任何已封存根。第二笔实际SHA、完整路径清单/报告身份及最终范围状态由交付回执和最终答复给出；停写后交root接管P3。

交付核对首轮发现Git ignore排除了清单内10个日志/probe class，使用同一精确3320路径清单的`add -f`保留这些已审原件，未扩展范围。audit-blobs.ps1首次因CopyToAsync.GetAwaiter().GetResult的VoidTaskResult进入函数输出而类型转换拒绝；修正为显式丢弃该返回值，重新生成交付清单后核对，未运行测试/控制，未修改封存原件。
