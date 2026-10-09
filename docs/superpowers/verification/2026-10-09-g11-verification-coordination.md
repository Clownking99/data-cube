# G11 验证工具实施与独立验收

## C0：范围与基线

目标：执行维护整理后的第一优先项，[G11计划](../plans/2026-10-09-g11-verification-core.md)限定四个验证工具契约和本轮薄入口。用户“继续”授权推进，root规划/审查/交付，原GPT‑6.1-sol实施；不扩展后续JVM夹具或产品拆分。

实际基线：main `3beb965746ade7d76224cb4aba03cafb7cfe2e34` 范围内干净；上轮Verify37876466269四任务和真实delivery-result passed=true已读取。开发会话 `01a11b86-2026-7ed3-86c5-1ec232640653` idle，cursor `431e0858-03dc-4a74-84cd-3c2876284d87:181`。复用worktree aed5，当前HEAD7ebf97d2，仅旧未跟踪 `.g10-verify-blobs.ps1`，原样保留。

改动：登记实施契约、负例/真实试点/P2/P3验收顺序；更新CURRENT为本轮进行中。旧冻结证据和脚本未改。验证：已读最小设计、验证入口和G10旧runner，未运行Gradle或G11合成测试。失败/未验：暂无新实现结论；现有runner中无界流累计/无限等待只是本次改进起点，不把旧通过当新工具证据。

下一步：同一开发会话从本轮计划提交创建codex/g11分支，先提交P0文件/API/schema/路径与进程结算矩阵，root审过后进入P1。P0不跑Gradle、不提交产品、不合main或推送。旧自动跟进维持暂停，新持久调度没有创建。

### C0.1：独立分支与进程语义核对

计划提交 `099dd677a653731bf2fad998cacb65ef33f2c827`，开发已在aed5从该提交建立 `codex/g11-verification-core-20261009`，root直接核对实际branch/HEAD一致；旧未跟踪脚本保留。P0 turn `01a11ebc-48a5-7552-ba12-0e4cd22b5b5e` active，游标重置后最新 `06ef570a-1804-47a5-a9ff-5e6aa3d1f8df:2`，尚未收到冻结报告。

root读取旧G10 Gradle/镜像入口及隔离init，确认新工具需要保留生成资源/图标目录重定向、现有任务/过滤器及native FX设置；旧脚本仅作只读参考。核对[Microsoft Process.Kill](https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.process.kill?view=net-9.0)和[WaitForExitAsync](https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.process.waitforexitasync?view=net-9.0)说明：等待根进程退出不证明后代已退出，取消等待也不能替代物理退出。已下发要求在schema分开rootExited、streamsCompleted、捕获后代结算和未知范围，期限覆盖pipe收尾。此为API语义与源码审查，不是G11实测通过。

## C1：P0 准入，实现 P1

目标：将四个契约落为小型共享工具，先验证合成负例，再运行唯一的完整Redis/关闭定向试点。worker P0 turn已completed/idle，cursor `06ef570a-1804-47a5-a9ff-5e6aa3d1f8df:4`；root独立完整阅读worker报告，SHA-256 `782dd401bec3bc45df9af58a599a956014711a2b6d80307608b2b5e6d3668af3`。报告只新增设计，无实现/Gradle/提交。

审查：直接读取当前gradlew.bat，确认默认参数`-Xmx64m -Xms64m`、appname及wrapper jar的结构化调用对应；JAVA_OPTS/GRADLE_OPTS在隔离环境为空，不改变产品任务。实际pwsh为7.6.5、.NET10.0.11，文档版本不作为运行版本证据。接受冻结工具副本、独立host双流文件泵、直接和已捕获进程范围、首失败保留、严格XML及新目录单写设计。

P1实施条件：显式冻结并校验gradlew.bat映射身份，不默默适配未来脚本；包含带空格/中文/引号/空字符串参数的原样传递控制。XML中同名case不能静默合并，若遇JUnit合法同显示名则保留文件及顺序身份并报告，不修改产品显示名来满足工具；重复文件/suite和属性矛盾仍拒绝。工程输入与工具副本均从显式repo解析，不因从run/tools启动而误指向证据目录。进程/IO统计失败须保留原件和真实结算范围，任何未观察退出不得写0或成功。旧runner对照仅新目录合成适配，不改写旧证据。

验证/失败/未验：当前仅设计、脚本与API语义审查；没有G11新测试通过。P1允许新增工具、合成矩阵和一个定向Gradle试点，开发线程持有唯一Gradle执行权；不进入P2、不提交或合并，等待root独立读取源码与原始证据。原失败不能覆盖，修复后必须新UUID重验。下一步下发P1并审查实际实现。

### C1.1：历史兼容性预检，非本轮测试证据

root只读核对G10 main冻结XML：定向56文件337452字节、最大112239；全量351文件10876155字节、最大993137；buildSrc 1文件1231字节。P0的单文件16MiB/合计128MiB预算能容纳该历史集合，但不作为未来实际结果或通过数。历史定向中AppShellSqlTransactionShutdownTest的`[1] POSTGRESQL`、`[2] ORACLE`各出现7次，其他参数化类也有合法同名；因此必须采用文件/suite/用例序号身份，保留显示名和重复出现提示，不按类名+显示名拒绝、去重或合并。

另直接核对build.gradle：src/test目录参与编译，resources与具名驱动参与构建。只用Git已跟踪名单不能发现实际编译的未跟踪源码，要求绑定器对已准入工程根核对真实输入集合，先排除禁止路径和生成目录、不得先进入禁止目录后过滤；新文件必须明确准入或拒绝，不得静默遗漏。该检查为工具契约补正，不改产品/测试。P1 active turn `01a11ec6-fcba-74c1-8cf5-881a7d62f8e5`，尚无实现验收。

### C1.2：输入实盘与首版源码审查

root以显式八目录、禁止段预剪枝、reparse拒绝及buildSrc生成目录排除作只读实盘核对：858文件=tracked858，无新增/缺失；src427、test408、buildSrc3、resources6、品牌assets7、drivers2、gradle2、workflows3。五个根文件存在并已跟踪，gradle.properties不存在，应明确记录optional缺席，运行间出现仍应检测。测试XML即使有文件但总case为零也不能通过。

首版evidence_tools.py已有同名case序号与zero-case拒绝；源码审查发现main输出路径先lstat再查禁止段、load未统一准入、repo未先词法检查、no_links从leaf检查可能先穿过祖先junction、尾点/空格/设备别名未拒绝。已向worker下发具体修正，要求首次合成运行前完成；此时未执行该代码或访问任何禁止路径。还未到P1验收，工具的首版不能作为已通过结论。下一步审查修正及其余进程/范围源码，读取首次合成原件。

### C1.3：进程核心首审返工

路径修正已出首版；root随后指出全层级忽略名为build的目录会漏掉buildSrc的合法datacube/build包，已改为生成目录精确排除。开发报告5个路径字符串及两个同名case的小控制通过，尚未独立核对其原始回执，不计完整合成/P1通过。

开发将P0的捕获进程方式改为private Windows Job+gate：先将host归属job再释放工具启动，常规实现选择获准，但仍须证明成员物理结算。[Microsoft Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)、[TerminateJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-terminatejobobject)及[QueryInformationJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-queryinformationjobobject)已核对；仅覆盖job成员，不外推WMI/service委托。

首版父模块/host仍有阻止执行的问题，root已下发修正：job assignment失败后的直接host回收、kill-on-close和完整finally、Start失败/退出后identity读取不能丢回执、host日志不能排空至Null、Task完成不等于无fault、父/host期限差不能丢失首个root错误、日志/pipe失败不能覆盖原退出、Scope及实际冻结工具归属校验、run创建碰撞防护。超cap泵首版继续读取的问题已改停止；其余需复审源码和原始负例后判定。当前不准入进程fixture或Gradle，worker先完成这些修正。下一步仍是完整P1合成后唯一一次定向，未进入P2或提交。

## C2：首轮合成原件审查，日志丢失返工

关键回收与路径修正落地后，root已允许由独立outer owner保护的helper矩阵，尚未放行Gradle。原件位于worker `docs/superpowers/verification/evidence/g11-p1-development/process-matrix-first/` 及其逐例g11-synthetic目录；root直接读取八个check，包含normal、argv、nonzero、dual、continuous、overflow、child-pipe、nonzero-child-pipe。dual两流各1638400字节，nonzero保留7；nonzero-child-pipe主因NONZERO_EXIT、observedRootExitCode7、secondary DEADLINE，回执包含实际捕获对象退出值，邻居各例仍运行。此为已读到的局部结果，不是完整矩阵/P1通过。

实际失败：continuous每10ms写tick，但期限结束后stdout长度0、SHA为空内容，host的FileStream缓冲在终止时丢失诊断前缀。已要求两级pump及时落盘，并加入强制结束后的非空前缀/原字节/hash断言，保留first原件。nonzero正常EOF日志却随全局失败标partial也需按流修正。外层owner需独立查询job清零或实际handle等待，不能仅Terminate请求或信被测receipt。首轮XML自检曾因ParseError未纳入期待异常而退出1；随后修改后运行退出0，但旧TemporaryDirectory模式未保留全部负例输入，不能据此满足原件验收。

其他已下发闭环：每个XML/input负例用新子目录保存原件，统一stage首失败聚合避免统计输出缺失遮盖编译失败，skip仅准入明确live前置条件、意外native FX skip拒绝，运行home/temp/build移至独占UUID临时树，避免把完整build/image放入证据归档。当前P1仍开发返工，源码尚未冻结，无真实定向/P2/P3新证据。下一步修复后新目录完整合成矩阵及旧逻辑对照，达到门槛后才定向。

root随后逐个重算八例共32份原始stdout/stderr/host日志的长度及SHA，并独立解码argv字面值（空格、中文、引号、空字符串）；全部身份匹配回执。保存[初审结果](evidence/g11-p1-root-preliminary/first-matrix-review.json)，`completeAcceptance=false`，仍保留实际日志丢失、partial语义和outer物理结算缺口，未把摘要里的reportedSettlement当独立证明。

## C3：第二轮进程局部结果与剩余门槛

worker修正后的process-matrix-second包含12例，root独立读取全部回执并重算40份实际日志长度/hash。continuous stdout2060字节均为完整tick行前缀；normal/argv/dual正常，nonzero及迟闭管道保留实际7；脱离pipe仍存活的child强制回收时明确失败；start/assign故障和故障注入的未观察结算没有假成功。每例独立outer Job在调用兜底终止前已查询为空、后再次为空，邻居一直存活且最终actualWait记录已读取。结果在[第二轮初审](evidence/g11-p1-root-preliminary/second-matrix-review.json)，仍`completeAcceptance=false`。

尚缺：冻结版本的完整合成矩阵、旧runner同fixture对照、外部exe哈希/版本、cap恰好/+1、取消输入真实结算、编译/统计失败的阶段聚合、run/工具身份拒绝等P0门槛。已下发补齐；P1唯一真实定向尚未开始。工具持续修改后必须新目录验证受验版本，不能把前两轮通过项直接当最终冻结证据。root此次读取摘要命令曾有PowerShell foreach管道语法错误（exit1、无执行/修改测试），加数组包装后读取成功；不是被测工具失败。

### C3.1：第三轮捕获的错误归因竞争

process-matrix-third在overflow退出1，root读取原check：host主因LOG_LIMIT、两流各读49152/写32768，随后Python因pipe关闭退出120；parent将后观察到的120归为NONZERO_EXIT，覆盖了更早预算超限。已要求按可证明的事件顺序合并，既保留该例LOG_LIMIT，也保留相反顺序的root7首因；不以固定优先级掩盖真实顺序。outer查询仍empty，属于工具归因失败，不是遗留进程或产品失败。原件不得覆盖。

另tool-checks-third因传入相对路径触发ABSOLUTE_PATH_REQUIRED，实际Python exit1，shell后打印exit=1导致外层返回0；只承认实际Python失败。后续显式绝对路径运行退出0，但正式证据命令必须使用Python `-I -S`，保留实际码后`exit`传播，不能用后续打印覆盖。root已下发；当前仍不放行真实Gradle。

## C4：最终合成版本核对，等候P1工程原件

worker第4轮19例、第5轮21例已增加取消、cap恰好/+1、工具身份变更、编译失败后的零XML/统计失败、root7之后子进程超限、live与意外native skip。root直接核对第5轮126份冻结工具对应当前6文件（tool-change的故意变更单列），84份日志长度/hash一致。旧逻辑7例的原始Run-P3字节与G10一致，normal/argv/nonzero/dual的新旧实际stdout/stderr逐字节相同；迟闭pipe由outer有限终止并查询清零，零XML差异明确记录。[独立核对回执](evidence/g11-p1-root-preliminary/fifth-matrix-and-legacy-review.json)仍不冒充完整P1通过。

root另在新合成目录独立运行冻结Python工具21项契约检查，实际exit0；包括四类XML计数矛盾、重复suite、大小/负计数/双状态、空及有原因skip、完整类型覆盖/遗漏/大小写重复/optional新出现/空类型，以及7个禁止/非法字符串在lstat调用0次时被拒绝。[原件和回执](evidence/g11-p1-root-preliminary/extra-contracts-002/receipt.json)保留。首次root夹具手写binding缺少字段，工具正确报INVALID_BINDING，导致审核脚本期待错误不符exit1；[首失败说明](evidence/g11-p1-root-preliminary/extra-contracts-001-failure.json)和首版脚本/输入保留，改为真实source-only snapshot后用新目录复验。不是产品失败，也不把首轮当通过。

独占`build/owned-g11-junction-review-<uuid>`中创建仅指向合成target的junction，冻结工具实际返回REPARSE_PATH，lstat轨迹止于链接本身、叶文件没有访问。[链接边界回执](evidence/g11-p1-root-preliminary/reparse-review.json)及实际exit0已保存；此运行树保留在build下，不纳入证据树或Git。

开发已按授权完成唯一一次真实定向并报告291通过/1live跳过、56XML、869输入不变和408类型；root此刻尚未读取该轮完整原始XML和归档manifest，不作为最终工程验收结论。worker正在仅归档/更新P1报告；下一步root独立统计、哈希审计后才准入P2。root从未运行本轮Gradle，唯一执行权仍在开发会话。

### C4.1：定向原件独立复核

root已直接读取worker `g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529` 的原始XML、process receipt、输入和工具副本；独立统计56套件292用例=291通过+1明确Redis live前置跳过，无failure/error。869个工程/工具输入与当前磁盘逐项长度/SHA相同，before/after一致；408测试类型，8个工具/Gradle子阶段实际exit0，32份日志完整并重新核对hash，6工具与第5轮受验字节一致，三项外部exe身份未变。[定向审核回执](evidence/g11-p1-root-preliminary/targeted-review.json)接受本次定向证据，完整P1仍等worker归档manifest。

回执按RedisPane类名前缀列了12个通过用例：11个RedisPaneBudgetTest实际原生FX用例，另1个RedisPaneCloseSequenceTest为普通关闭顺序单元测试；root读取源码确认，不将该12全部称原生桌面用例。JDK版本原日志25.0.1+8、Python3.12.14、pwsh7.6.5/.NET10.0.11。本轮没有真实Redis/数据库或完整发布验收。

## C5：P1正式接受，P2工程接入准入

目标：完成P1原件归档核对，开始共享内核的剩余工程阶段。root完整读取worker新增P1交审结论及manifest生成器、实际命令台账、pilot外层owner和额外四项输入/XML控制。独立脚本实际exit0：69个允许根、1777文件、32511490字节逐项长度/SHA正确，磁盘精确集合与manifest相同；六文件集中冻结副本与当前源码逐字节一致。[核对脚本](evidence/g11-p1-root-preliminary/audit_p1_manifest.py)、[回执](evidence/g11-p1-root-preliminary/manifest-review.json)。manifest SHA `ac3f1e6289e942d86dfd9d47f9e9f22bd942bae84a1d11a9ac71056740d1cc9a`，P1报告SHA `e5aeb50fba434ae093360b692a4846bbe0df91dc67ea5725681c15f5360437df`。

裁决：P1通过。首轮真实失败保留、正式版本重新验证，pilot外层实际退出0且Job查询清零，非仅信内层receipt。P0的Python helper、private Job/gate和schema变更已在报告说明；接受其范围局限，不外推委托服务/同步OS永久阻塞的物理硬期限，也不把故障注入称真实不可杀进程。先前损坏XML首stderr仅存在chat、旧临时输入已丢失，保持失败台账，不追补伪原件。

下发同一会话P2：最小扩展全量、强制buildSrc、jpackageImage、既有外置linked审计。只读参考G10探针并绑定来源，jimage/javac/owned image java均需明确role/path/hash准入，实际类型名单动态推导。允许的live skip按现有源码逐项列明，image无XML是独立阶段，不削弱测试零XML门禁。先交P2源码供root审，再冻结并新UUID完整core矩阵/定向/clean全量/buildSrc/image/linked；旧P1根不修改。开发仍独占Gradle，暂不提交/合并/推送。

失败/未验：本检查点没有P2工程运行证据；P3/main集成及精确SHA CI尚未执行。下一步审查薄入口与产物审计实现后准入P2运行；不扩展产品、JVM夹具迁移或架构重构。

### C5.1：P2源码增量审查

P1审核证据与检查点本地提交`ac3488949b0ef6fa12cd8c62a13d42d62cd595c2`，46个精确文件经原字节暂存核对；未推送。worker继续基于099dd677实施，不同步root的审核文档，不争用Gradle。

新增阶段政策已核对现有任务、10个定向filter及源码中的三个live前置skip：targeted仅Redis，full允许另两个精确SchemaDiff case，buildSrc不准skip。linked沿用两个G10外置probe，新增javac/jimage/image-java角色路径准入。root首审发现source.runtime只验UUID叶名仍可触及错误父目录，已要求访问marker前约束到创建scope时实际准入的temp父根，并核对marker的scope值、使用前source result/manifest与image核心文件身份。首段PowerShell修正已落盘，Python镜像工具和完整入口仍在实现，尚未裁决通过。

另要求P2最外层owner显式清空继承环境、日志预算和有限结算，并冻结自身实际字节。P1内层受控环境/日志证据仍成立，但不能将外层包装作为P2预算例外。下一步完整源码/合成控制审查后才运行工程阶段；本检查点没有新增测试通过数。

### C5.2：外层包装源码返工

root读取新增run-owned.py，发现递归rglob工具目录没有访问前禁止路径/链接剪枝、Popen之后Job assignment失败未回收直接进程且丢回执、close曾强制结束遗留成员却未纳入最终非零判定、非零根退出后迟闭pipe可能只归deadline，以及P2限定前缀无法让同冻结版本用于P3。已下发精确修正：显式工具闭包及实际wrapper/导入身份，Start/assignment失败持有直接对象有限回收和保存回执，根错误/流/Job结算分别记录，残留/未知不得通过，本轮P2/P3具名证据前缀。没有执行该首版包装，属于源码返工，不能记为运行失败或通过。

薄入口已完成多阶段首版，image生成与linked读取有输入交叉校验及镜像前后清单核对；完整冻结与合成负例尚未完成。下一步只审修正和控制结果，仍不准入Gradle。

root另以纯路径字符串实测`Path.Combine(...,'probes/One.java')`保留混合分隔符，而GetFullPath转换为反斜杠，两者`-eq`为false（exit0，不访问该合成字符串路径）。已要求source/frozen/expected身份比较统一词法规范化，避免新probe子目录误拒绝所有命令；内层Python统一`-I -S -B`，避免image_tools导入同目录模块向冻结闭包旁写字节码。此为静态缺口及字符串控制，不计工程测试通过。

## C6：P2源码准入，冻结后开始完整验证

目标：审完新增多阶段入口/镜像绑定/外层owner后，移交开发独占工程验证。root已读取修正后的完整入口、image_tools及run-owned、角色与文件身份检查；路径规范化、RuntimeParent/marker范围、镜像前后清单、输入交叉绑定与Python禁止旁写均已落地。两个外置probe与G10来源逐字节一致。

开发source-controls-003保留31项镜像/角色/skip控制；outer-source-controls-001有七项真实自有helper控制。root直接读取每例原始result、控制脚本及实际输出，重算10份存在的日志长度/SHA：[源码准入回执](evidence/g11-p2-root-review/source-controls-review.json)。正常root/wrapper均0；Start失败rootExit=null；assignment故障仅direct-root-handle范围并观察实际退出；root0残留child在close前确有成员51952，回收后empty且wrapper1；root7+迟闭pipe/子流超限保持7；overflow主因OUTER_LOG_FAILURE、根终止124、两流各保留1048576字节。Start/assignment无日志文件的partial字段不被当作完整零字节日志。

裁决：源码准入通过，已下发同会话自主冻结并顺序执行完整core21控制/Python契约/P2政策及外层控制，再targeted、clean全量、强制buildSrc、image、linked。临时控制不是最终冻结版本证据，需新目录重验。所有权范围仍有明确限制；未执行root Gradle、没有P2工程通过声明或发布验收。

root另准备独立[原件审核脚本](evidence/g11-p2-root-review/review_stage.py)，仅AST语法检查exit0，尚未审核任何P2工程run；脚本不导入受验实现，直接重算输入/工具/日志和XML。下一步等待冻结工程原件，独立核对后才提交/集成及P3。

## C7：P2冻结版合成完成，定向原件接受

开发冻结v1首次编排先创建了process-controls目录，随后core再要求新建而失败exit1；sequence.log和sequence-result.json原件保留。v2只修正归档编排器的重复创建与新目录名，12个受验共享文件未改。root独立逐项重算v2包12文件，与当前工作源码长度/字节/SHA一致；[冻结审核](evidence/g11-p2-root-review/frozen-v2-review.json)记录当次归档控制器SHA `853c05437eed258e351d2aec74a30fba41edd5feb14f916fd4856dd4f005ee61`。该一次性控制器使用assert做辅助编排断言，实际入口使用`-I -S -B`而非优化模式；四项可复用准入契约显式拒绝，root另独立核对原件，不以控制器汇总作为唯一门禁。

开发报告冻结版Python契约、21进程控制、31政策/镜像/角色控制、7外层控制已通过，进入工程顺序执行。root已读相应case与冻结入口；完整最终原件manifest仍待P2结束核对。父级Git属性限定`verification/** -text`及`g11-*/** -text`，旧属性行保留、旧冻结根未新增属性文件；提交后还需逐项核对Git blob和main检出字节。

root对已完成v2 targeted运行独立脚本实际exit0：56XML套件292用例，291通过/1精确Redis live前置跳过，0失败/错误；875输入前后一致并逐项匹配当前磁盘，408类型覆盖，12工具source/frozen身份一致，10进程回执实际root0/完整结算，40日志长度/SHA相符。[原件审核回执](evidence/g11-p2-root-review/targeted-v2-review.json)。当前clean全量运行中，强制buildSrc/image/linked及P3仍未验，未将P1旧结果充作本轮证据。

另将当时SHA仍为`e5aeb50f...`的[P1已接受完整报告](evidence/g11-p2-root-review/accepted-p1-worker-report.md)原字节保存，供后续报告追加P2内容后复核。下一步继续独立读取本轮全量及镜像原件，再裁决完整P2。

## C8：P2独立审核通过，准许精确本地提交

目标：核验全部冻结工程与合成原件并移交P3。root以独立review_stage.py实际退出0复核五阶段：定向291通过/1 Redis live跳过；全量4711通过/3精确live跳过；buildSrc8通过/0跳过；image和linked明确无XML的产物阶段，不计测试通过数。各阶段875输入/408类型/12工具前后及磁盘身份一致，5阶段进程日志共228份重算长度/SHA；均实际root0、完整结算。外层另10份日志身份正确，无终止请求且Job自然清零。

独立audit_completion.py重算P1的69根1777文件32511490字节不变，P2的79根2022文件38054904字节与磁盘精确集合一致；manifest SHA分别ac3f1e6289e942d86dfd9d47f9e9f22bd942bae84a1d11a9ac71056740d1cc9a与0ba356b38c3257891b22de26c21820ddaf5edb7c0027eee2ad08300c5d329a0b。P2报告单独存于2026-10-09-g11-p2-worker.md；没有修改P1报告或冻结根。

镜像实物183文件逐一重算，linked前后清单相同、4核心文件与marker/source result绑定正确；原始jimage的26088类对408测试类型无泄漏，六个Redis产品类存在，cfg/文件无测试隔离污染。四个linked命令实际0；原始驱动输出connectCalls=0，Redis输出socketsSettled=true、realServices=0。21 Python契约、21 core控制、31政策/角色/镜像控制及7 outer控制复核，66份存在的控制流日志重算一致；不存在的partial日志不视为完整零字节。负例首因、root7及邻居存活/实际Job清零保留。

[完整审核脚本和回执](evidence/g11-p2-root-review/completion-v2-review.json)。root审核夹具曾误取顶层runtimeParent及要求所有outer负例wrapperExit=1，分别实际退出1；后者正确原件为保留7/124。修正只读审核夹具后全套审核实际exit0，见reviewer-corrections.md；受验实现和原件未改，不将首次审核失败冒充通过。

裁决：P2通过，已授权同一开发线程只按12工具+两属性文件+两报告+P1/P2 manifest明确原件本地提交，要求Git blob原字节核对；不合main、不推送、不再运行Gradle，旧.g10-verify-blobs.ps1保持未跟踪。root随后审核运输、合main并接管唯一Gradle，在新UUID完成P3。尚无P3/main新运行及最终CI证据，不宣称完整桌面/真实服务/发布验收。