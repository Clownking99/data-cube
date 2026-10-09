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
## C9：main集成与运输复核，root接管P3

开发分两笔提交：48b51d63ee89edb8dcb0187d7dd43367c558f347（13工具/属性文件）、6aefd9d136058ec67cab688b28158dcc4416fb9d（3804证据/报告/属性文件）。root以4ddba09d6b848dca4532b575b6db930702c79211合入main；产品、测试、buildSrc、资源、构建和CI无差异。开发已停止Gradle及写入。

首次运输核对实际exit1：仅P1报告被Git检出换行转换（原e5aeb50f...、检出97b36ea1...），P2报告/工具/证据字节均正确。root为两份G11报告追加精确-text规则，按HEAD blob恢复原字节，提交62ea18d1981e8ac65b4b727566786d56b6a65371；未改任一冻结证据根。独立transport_review.py重新实际exit0，3817文件精确集合和Git原始blob一致，[回执](evidence/g11-p2-root-review/main-transport.json)。首次失败单列transport-first-failure.json。

root准备全新[g11-p3-813154b3fa4c-package](evidence/g11-p3-813154b3fa4c-package/preparation.json)，受验main62ea18d，875输入/12工具。共享工具字节与P2完全相同；一次性编排器只替换新前缀/main绑定并将assert改为显式require，SHA a4d749799719d130a748c6ebd7e0b609e46ac5f7e477b8c23f873d609573df19。冻结check-core内部合成目录保留g11-p2-synthetic名称，但嵌入本轮唯一g11-p3控制ID，均为本轮全新原件，非复用P2。

已由root启动Python -I -S -B完整控制及五阶段序列，soleGradleOwner=root；每阶段新的独占UUID运行树。当前无P3通过声明，后续只以原始实际退出/XML/产物核对为准。下一步完成P3并封存、更新使用说明/交接，最后推main核对精确SHA四任务CI；尚未推送或改变tag。
### C9.1：P3定向原件复核

root当前main62ea18d，完整新合成序列已通过，targeted完成后立即独立读取原件：56套件292用例=291通过+1精确Redis live skip，0failure/error；875输入前后一致且与磁盘相同、408类型、12冻结工具、10进程/40日志身份均正确。回执见g11-p3-813154b3fa4c-root-review/targeted-review.json。原生headless=false，跳过不计通过；11项RedisPaneBudgetTest仍需在最终全量/镜像原件一起核对。

clean全量已启动，buildSrc/image/linked仍未验；尚不计P3整体通过。只读进度查询曾猜错gradle进程子目录及command.json文件名，Get-Item返回1；没有修改或停止被测进程，正式sequence及实际退出独立记录，不将查询失败冒充Gradle失败。
## C10：P3接受，封存并准备main交付

root新P3实际Python/shell exit0，80项合成控制及五工程阶段完成。独立读取当前XML、原始进程/流、875输入/408类型/12工具/外部exe和镜像实物：定向291通过/1精确Redis live跳过；clean全量4711通过/3精确live跳过；buildSrc8通过/0跳过；image183文件与linked前后身份一致、26088类无408测试类型/框架/探针泄漏。四个linked命令0，驱动connectCalls0、Redis socketsSettled=true/realServices0。11项RedisPaneBudgetTest原生用例在定向和全量均实际通过，另一个关闭顺序unit未算native。

验收脚本首次扫描运输后的P2清单遇到旧首失败留下的空目录缺席（Git不保存空目录），实际exit1；没有丢失文件。P3审核器增显式transported-archive，只容许这个精确根且manifest零文件时单列缺席，其余文件/非空根继续严格对照；完整重验exit0。旧P1/P2、共享12工具与正式序列原件均未改。P1的1777和P2的2022文件在main重算不变；错误记录没有删除或改成通过。

P3封存1900文件/33218660字节，manifest SHA923c436ba191f2627d9b34ec24dee176b39f13bea4c1461c86fcdb675afc80d6，见g11-p3-813154b3fa4c-frozen/manifest.json。冻结包含实际工具、一次性编排/审查/封存脚本、五阶段/合成原件、交付脚本与定位；不复制整棵runtime、驱动、源码或JDK。详细结果见2026-10-09-g11-main-verification.md。

裁决：P3本地接受。已更新CURRENT、验证使用说明、最小设计与计划检查点；新增文件/文档不改变工程输入。下一步精确暂存/本地提交，main-only push（必要时仅本次7897代理）并核对同SHA四任务Verify、原始日志与远端SHA。交付回执写入build/owned-g11-ci-c7f5f3e81c2c474e81269515c2730c1a，由已提交delivery-intent.json定位，避免CI完成后改变受验HEAD；只有实际delivery-result.json passed=true才记交付通过。当前检查点不提前声称CI完成。

未验：完整桌面、真实Redis/关系库、签名安装升级回退及G10 RSS/阻塞OS等局限仍保留；private Job和实际handle不外推委托服务，故障注入不是实际不可杀进程。不fetch/tag/PR/发布、不创建后续开发任务或自动跟进。
## C11：精确SHA CI失败，必要的PID就绪协议纠正

main cfd9d4ed4ebd08d1b9f2ff138ae25690901effe9已直连推送，远端SHA相同，无代理；Verify37893270726的wrapper和Redis integration通过，Ubuntu unit失败，Windows仍执行。因此G11只完成本地验收，远端交付尚未通过；没有重试到绿或伪报结束。

Ubuntu原始job113698755125日志保存在g11-ci-pid-readiness-20261009/ubuntu-first-failure-stdout.raw，实际4614 cases / 1failure / 1763CI环境skip，失败PgDumpRunnerReliabilityTest.capturedFamilyHoldingPipeIsStoppedAfterParentExitAndIndependentNeighborSurvives[2] tree，第169行NumberFormatException空字符串。CI跳过不算通过，不能覆盖本地native证据。

root源码定位：PgDumpProcessHelper Files.writeString创建PID文件后尚未写入/关闭的窗口，PgDumpTestJobs.awaitFile仅检查exists，测试便readString/parseLong；并非G11工具吞错。已下发同一6.1-sol会话，从本地cfd9d4ed建codex/g11-ci-pid-readiness-20261009，只修正PID完成发布/读取协议及必要确定性回归，覆盖child/grandchild消费者，不放宽5秒/skip/断言，不做JVM夹具迁移或产品改造。属于当前交付被实际CI失败阻止的必要局部纠正，常规方案自主记录。

开发先交源码/证明方案，不Gradle/提交；root源码审后再明确转移唯一Gradle执行权。冻结P1/P2/P3不改；后续修正将使用新输入身份和新验证目录，当前P3通过不冒充修正后的新证据。下一步审查小修正，再本地验证/集成/新SHA CI。没有新增线程、自动调度或外部服务访问。
### C11.1：PID最小修正源码准入

root完整阅读三文件diff：测试专用PgDumpProcessHelper新增publishPid，CREATE_NEW写入正PID并关闭后才创建.ready；PgDumpTestJobs.awaitPid先沿用原5秒awaitFile等待完成标记，再由readPublishedPid确认标记/解析正long，空串/非法/溢出转换为明确IOException；PgDumpRunnerReliabilityTest三个消费者改用此接口，原capture/physical/neighbor断言不动。

新增3个参数化回归方法10case：pidReaderRejectsEmptyPartialAndCompletePayloadUntilPublication（空/12前缀/完整但未发布均拒绝，发布后完整123456），pidPublisherPublishesCompletePositivePayloadForBothFamilyRoles（child与grandchild），publishedPidMustBeCompleteAndPositive（空/0/-1/非数字/溢出）。采用code-testing-agent focused流程，不创建中间状态、不扩展测试框架。源码准入通过不等于测试通过。

已把唯一Gradle交给开发，仅允许一次最窄PgDumpRunnerReliabilityTest重编译/执行，复用冻结core与新一次性薄入口/有界owner；共享12工具、原stage-policy过滤器和旧封存根不改。root停止Gradle；待实际退出/raw XML/输入/薄入口归档审查后才提交和接管修正后main复验。常规预算与5秒保持不变，未调用CI重跑。
## C12：PID修正定向与封存独立接受

唯一真实定向为cleanTest test --tests com.datacube.export.PgDumpRunnerReliabilityTest --rerun-tasks --offline --no-daemon --console=plain，Gradle实际41秒/exit0，33通过（原23+新10）、0skip/failure/error。此前相对spec被共享路径准入在启动进程前拒绝exit1；原始拒绝保留，改为绝对参数后未改任何入口/工具字节。受验是cfd9d4ed加三文件工作区修正，875文件SHA前后相同；不能称原cfd9d4ed已含修正。

root逐行核对一次性stage.ps1及outer.py相对冻结共用版差异，只选择窄任务与本轮薄入口、17entry文件（outer运行身份闭包16）和赋Job后gate。预算/双流/结算/环境沿用，无新增任意命令平台。独立audit_narrow.py实际exit0：1XML33case、875输入、408测试源码、12工具、5进程20日志全部正确。独立audit_archive.py实际exit0：103文件910931字节，manifest SHA04ee175ba44263ac75a6e528e19c0d6f1cafcaea1e72c46e599657cb79f72379；17entry、outer两流与Job自然清零、3源文件工作区SHA及10新增case/family两case ordinal映射一致。参数XML仅有display名，映射按已审源码声明及唯一连续组，不伪造方法字段。

[审核原件](evidence/g11-ci-pid-readiness-20261009/root-review/archive-review.json)。已准许开发两笔本地提交：三测试文件正常文本规范化；新证据/manifest/报告精确原字节及报告专用-text属性。开发停止Gradle，不合main/push。root合入后用新输入身份完整重验；旧P3仍保持不变且不冒充新结果。

首轮CI现已终态：wrapper、Windows test及linked、Redis integration均成功，只有Ubuntu PID读取竞态失败，整体failure；原API终态已保存在同CI证据根。root新增失败文档提交后，旧Delivery.py正确拒绝HEAD变更的旧推送核对（HEAD changed after push），随后只读固定run API并显式核对旧cfd9d4ed，没有绕过修改冻结脚本。

## C13：PID修正合入main，新完整P3启动

开发提交52374f7112eca999ffb2a28551e5ef6b8f2b2946（三个测试文件）与ca055cc597fa92ce0d13b52ea892d2cd4bea72ef（精确证据/报告/属性）已经root审查后合入main e368a1b16bdee226925b363f06b4dc4f003c1f0f。独立audit_transport.py核对109个变更路径、103原件、manifest/报告和三源码Git blob；属性只增加一个报告保护规则，既有规则逻辑保留。main两份Java检出为CRLF，与受验开发字节只存在换行差异，完整文本一致；新运行绑定当前main实际磁盘SHA，不冒充旧工作区SHA。见g11-ci-pid-readiness-20261009/root-review/transport-before.json与transport-after.json。

开发已停止Gradle与写入，旧.g10-verify-blobs.ps1保持未跟踪不读不动。root接管唯一Gradle，在全新g11-p3-581b459dad08-package准备875输入/12工具。控制器SHA844df3e07a93ecdb727fcf53608ef05e5b2700f2fe357a6c75597f988f39c48f，仅新前缀/受验main及显式require；受验共享12工具与上一轮冻结字节相同。实际Python -I -S -B序列已启动80项合成控制和五工程阶段。旧P3及首CI失败原件不改；当前没有修正后完整P3/CI通过声明。下一步逐阶段独立核对原始XML、日志、输入、镜像，再封存提交/推main及新SHA CI；仍不扩展下一轮或真实服务验收。

## C14：独立门禁拒绝矛盾通过，runner必要修正

目标仍是G11交付。新序列实际Python/shell exit0，五阶段已自然收尾，root不再运行Gradle。定向独立审核exit0：291通过/1live跳过、875输入/408类型/12工具、40日志一致。全量原始XML351suite/4724case，4721通过/3live跳过，PID33例实际全通过；buildSrc8通过；image/linked返回0。以上不能推成整体P3接受。

root原审核器review_stage.py在full/java-version处实际exit1：process-receipt.json为passed/complete，却rootExited=false、rootExitCode=null。hostReceipt及同PID21608/startTime真实captured handle记录退出0，两流EOF；root-exit.json不存在。全轮进程回执和流哈希只发现这一处矛盾，见g11-p3-581b459dad08-root-review/round-verdict.json。没有放宽审核器；为持久保留诊断，针对同一不可变原件再捕获一次同样的审核拒绝exit1，未重跑工程。无退出事件的读取诊断曾产生FileNotFound，正是缺席事实，不算工具或Gradle失败。

只读复现使用同一PowerShell7.6.5：默认ConvertFrom-Json将ISO startTimeUtc转为System.DateTime，字符串比较false；-DateKind String后strict身份字符串可匹配。源码还有host循环尾刚退出后break未统一发布exit事件，以及parent最终passed检查遗漏根退出观察的组合。已下发原6.1-sol会话从e368a1b1建codex/g11-root-exit-observation-20261009，最小方案准入：统一实际handle观察/原子且带身份的事件，晚读身份/严格比对，缺失/损坏/矛盾拒绝成功，首因tick/非零/取消/额度、Job和双流真实结算不退化。确定性组合控制先交源码审查；当前开发只可编辑/语法检查，未准入进程控制或Gradle，不提交/合main/push。

本轮1904文件33165510字节已按g11-p3-581b459dad08-rejected-frozen/manifest.json封存，SHA40fed9cfd3b881fe62bd6e237d27eed090dd275316f75e08df9b11f060754b97，accepted=false/deliveryAllowed=false。原准备的交付/暂存脚本未执行，不创建通过回执，不推本轮main。旧冻结P1/P2/P3与首CI失败不改。下一步独立审核共享工具修正和确定性控制，再在新输入/工具身份下完整开发验证、集成main及新P3/精确SHA CI；当前完整交付、真实服务和发布验收均未完成。

## C15：退出观察修正源码准入，移交合成验证

拒绝轮次与检查点已本地提交854507bca10e94c68bd70424ec16290a878e31a8，1916文件（1904封存原件及其manifest/文档/运输审查），索引原字节逐项相同；提交后范围内干净，未推送。7份更新文档的93个本地链接存在。

root阅读同一开发会话的最终四文件diff808b5ceba61f1a82e47d8fa43ca7dd3d93e76cc9efd9400d7bb74f4b382a7580：host统一实际handle退出观察、原子identity/event及首次tick；parent显式String日期解析、强PID/startTime关联、晚读身份和event/host/捕获handle交叉验证；成功必须有rootExitProof、0退出、真实Job/流/已捕获handle完整收尾。短命root不要求parent必然轮询捕获，证明来源明确为已准入host持有handle的带身份事件，不能仅靠host汇总或PID。

预审修正两点已落实：pre-gate assignment失败在未启pump时保留实际direct host已退出/Job空的结算事实，状态仍失败、不虚构root证明或EOF；process_checks正例强制passed且outer实际0、负例强制failed且实际非0，成功process及复合skip-live的嵌套process均要求根退出证明，不能把所有outer非零都当作预期负例吞掉。已有21项预期与预算不放宽。

源码准入回执g11-root-exit-20261009-review/source-admission.json逐项重算12工具，只有OwnedProcessHost.ps1、VerificationCore.psm1、run-stage.ps1、check-core.py变更，均保持LF；原PID三测试、其他8工具与产品不变。新18项覆盖循环尾0/7、迟读/空capture、缺失/损坏/身份/码/summary矛盾、日期类型、root/host混淆及7/取消/预算首因。源码/语法通过不是控制通过。

已授权开发独占新冻结合成验证：21 Python+21原process+18新root-exit+31 policy+7 outer共98项；root不运行控制或Gradle。此时不准入工程/提交/合main/push，任何首失败保留并按必要diff诊断。下一步独立审核98项原始身份事件、实际退出/流/Job和邻居，接受后才下发新冻结完整工程；后续controller必须显式接入18新项，不能复用仅80项或旧工具SHA作为新证据。

### C15.1：新控制首失败保留，未准入工程

开发新冻结包g11-p2-root-exit-controls-e6244da5d13b4af9aa887578b0fe2935在21 Python与21原process组后停止：首个新项exit-tail-zero实际DEADLINE，controller-result.json passed=false；其余17新项、policy和outer组未运行，不计通过。case原件显示outer非零、host被期限收尾、缺root-exit事件被ROOT_EXIT_EVIDENCE拒绝，未产生矛盾passed；邻居存活/外层实际Job清零。root只读请求确认helper收到指定tail gate路径与0参数，gate实物长度7存在、stderr/host-stderr为空。具体源因仍诊断中，不推断为产品失败或延长预算。

已要求开发只读核对helper分支、.NET/Python路径可见性与长路径等，报告实际诊断和必要最小diff；不直接重跑、不改原冻结controller。该一次性controller的assert在实际-I-S-B下启用，但下一新版本改显式require，旧身份/实际首失败保留。没有Gradle运行权限或新交付通过声明。

### C15.2：长路径源因接受，新冻结98项重验准入

root读取两套实际argv/exit0和原始双流：尾部门路径264字符，PowerShell7.6.5/.NET10.0.11下Exists=true、内容release；Python3.12.14 is_file=false/stat WinError3，规范路径与host相同。不是尾部协议无门，也未加长预算。必要diff仅check-core.py导入stdlib uuid并将synthetic根改为固定前缀+独立UUID+mode，原scope/Job/预算/18项及21原预期不变；下一新controller将所有assert转显式require，旧已执行控制器不改。

root以.NET只读逐项复核首失败49根/736文件/8413693字节精确集合与SHA，manifest95a136cd033d36fdcee971106b3e84bc6d01cf056cbaa352099e52ff7fac3624，仍accepted=false；原件包含264字符长路径文件，未重命名或删除。见g11-root-exit-20261009-review/rejected-controls-review.json。新check-core SHA d96581bf2fbd32dc0e388317eff43fc36452ae7e90c727bba53d2293c9364e3e，controller47200cd5876a23e6fd59f652f3a7b7c6ef34fbbdad0a07c45f98ba370b0d4c55；其他11工具与前次源码准入相同，path-fix-admission.json保存12文件身份。

已授权开发在新冻结目录从头执行完整98项，不把旧42项补入新通过数，按实际保存spec派生短UUID归档根并验证所属本轮。开发继续独占控制，root不运行Gradle/进程控制；尚不准入工程/提交/合main/push。下一步读取新控制实际结果，若再失败先保留并定位源因，不能调整预算或跳过门禁。

## C16：98项原件独立接受，开发接管工程验证

目标仍为G11当前交付。root独立audit_controls.py实际exit0，确认rx2封存91根/1357文件/16591899字节，manifest b2de1c70097c8baa033030a469dbca6e10a2d133b4ee924d2325e08ffaedcce9；逐项重算12当前/冻结工具、16entry及operator，实际controller/archive退出均0。21 Python、21旧process、18退出观察、31政策/角色/镜像、7 outer合计98；39进程案例按实际spec的短UUID派生归档根，预算不改，逐个核对raw stage/process/outer/邻居与实际wait，232日志身份正确，18份强根退出证明与原event/identity/host/捕获handle一致。见g11-root-exit-20261009-review/controls-review.json。

新增尾边界0/7和迟读身份确有tailBoundaryForced，空capture不伪造捕获；缺失/损坏/身份及summary矛盾均拒绝通过，7/取消/期限先因保留。短根跟原长路径失败原件分开，旧失败不变。root审核器首轮exit1源于错误要求parent首tick等于host：parent先观察root17836的7，tick7623430718739早于host7623430721775；修正为正且不晚于host、原event和实际captured7交叉验证后退出0。v1和auditor-first-failure.json保留，无受验控制重跑。一次只读文件名查询误用-spec.json而实际是-owner-spec.json，未修改任何受验原件。

已向同一开发线程交付唯一Gradle执行权：保持12工具与工程输入不变，新UUID冻结工程控制器，完整targeted/clean full/强制buildSrc/image/linked。已接受98控制按manifest绑定同版本，无需为只增加工程编排重复控制；root后续新P3仍需完整98和五阶段。开发当前不提交/合main/push，root不运行Gradle。工程与main新P3/精确SHA CI尚未通过，下一步独立审阅工程原件。未启动下一轮、真实服务或发布验收。

### C16.1：工程入口原生用例误计，定向原件接受后精确续接

开发新工程包g11-p2-eng-11965af76c694c6798f2074fff74d8bf的实际controller14d3a048...在targeted退出0后审计报11 native RedisPane失败，controller/operator真实exit1，其余四阶段未启动。表达式RedisPane前缀匹配误把1项RedisPaneCloseSequenceTest普通单测加入11项RedisPaneBudgetTest，共12。首失败213文件1767823字节、manifest9cc7a935cbfe5ac1d7c4826c9cdb5e4ceff99be3bb88438ace25002b5bb3552f保持accepted=false，root重算精确集合与所有字节一致。

root增强review_stage.py对原targeted实际exit0：875输入/408类型/12工具、10process强退出证明和40日志、56suite292case=291pass+1精确Redis live skip；独立audit_targeted_continuation.py确认11原生全部执行并通过，queueFailureStillAttemptsSessionClose普通unit另计。proposal cfc783054449d410042f9181fe5ae307ca46adad0e918e958130aff9686afbe8仅把收集表达式改成精确RedisPaneBudgetTest，无共享工具/工程/预算变化。

裁决：targeted实际运行接受，整个旧controller仍失败。授权新冻结入口显式绑定这213份不可变原件及实际targeted退出0，校验当前875输入和12工具一致后只读重审原阶段；随后只执行剩余full/buildSrc/image/linked新UUID。无需重复同版本已通过98控制或targeted；不把新入口说成重跑了原targeted，不覆盖首失败。下一步核对精确续接入口及四阶段原件；开发仍独占Gradle，main/P3/CI交付未完成。

### C16.2：续接源码与full/buildSrc独立接受

新续接包g11-p2-eng2-861d631887ec4c9790f3deb3f062ecae的controller e5c479c2c888047bf769554a0f49ffedacce364aa51ed4cfd06247784717959b、20入口manifest33a8197ab6814b3d8c86bbd90eb7f74b023847438aecb1a01ed6ffded6213c07已逐字节核对。相对已审单行修正只增加本次213原件的显式manifest/spec/scope/退出绑定和同875输入校验，实际targeted只读复审且executionRepeated=false；只运行后四阶段，12共享工具未改。

root增强review_stage.py对full与强制buildSrc实际exit0，分别40日志和10process原始identity/event/host/proof/captured强证据全部一致，875输入/408类型/12工具前后与当前磁盘一致。full原始4724case=4721pass+3精确live skip，buildSrc8pass/0skip；audit_cases精确确认定向和全量各11 RedisPaneBudgetTest原生用例。PID全量33case全部通过，名称多重集合与原窄回归一致，新增10例不丢失。

PID只读审核器首轮exit1仅因旧目录规则要求-full-，新worker实际spec以-UUID-full结尾；保存audit_pid_cases_v1.py及错误记录，改为两种精确合法命名规则后同一原件实际exit0，没有测试/工具改动或工程重跑。根本结果见full-review.json、buildsrc-review.json、case-review.json及pid-case-review.json。image/linked仍执行中，尚未接受完整工程/main/P3/CI。

## C17：开发工程全部独立接受，准许精确提交

root增强review_stage.py已实际审核五阶段：57个process的原始identity/event/host/proof/实际捕获handle全部一致，根/host实际0且真实结算，228份inner日志长度/SHA相同；875输入/408类型/12工具前后与当前磁盘一致。定向291pass/1精确live skip（原首次实际运行）、续接full4721pass/3精确live skip、强制buildSrc8pass/0skip；11 native与PID33均另行核对。没有把首次controller exit1改成通过或重复运行targeted。

独立audit_engineering.py核对五阶段outer实际0、自然Job空、10外层原始日志；image实物183文件逐项身份与before/after相同，原始jimage26088类对408类型无测试/探针泄漏，cfg无隔离污染。四linked命令实际0，driver connectCalls0，Redis socketsSettled=true/realServices0。工程封存02cb7a0b73bd7647b3ddaead8fdf2395d5bc51cc4c1f5ea8368f82c1da77e479精确9根995文件20084399字节，独立audit_archive.py实际exit0。控制98 manifest、首targeted失败manifest与续接工程manifest分别保存，以精确SHA关联。

裁决：开发验证通过，已授权同一线程仅两笔本地提交：四工具和本轮原件/报告/精确-text规则；必须.NET核对长路径原件并检查Git blob原字节，交付清单明确所有路径。不合main/push、不再运行Gradle，旧.g10文件保持不动。root主线审核文档现已提交a9ecaf99；开发源基线仍e368a1b1并加四修正。下一步审核运输后合main，root接管唯一Gradle执行新完整98控制和五阶段，最终精确SHA CI仍未完成。完整桌面/真实服务/发布验收不在本轮通过声明内。

## C18：原字节运输接受，main集成并启动新完整P3

开发四工具提交a59b3f15ae21c1514acfc5060b15ab41fe90c94b和3320路径证据提交258a03db12648b821e85273adcc16b25e1efc099已完成。root独立audit_transport.py核对两提交精确链、全分支3324路径集合、四份manifest3301原件、报告与属性增量，以及全部Git原始blob长度/SHA；额外路径只限四个已知源码/提案根与交付入口。报告bf22248eb2e02b8ce1e3b246f1057d11d37112ce5ab855085af39a6052b15b6b原字节一致。

独立.NET逐项读取worker3324文件49529013字节与Git一致，最长266字符，其中8项超过259，全部保留。main以2c8fc13577337a9b5247351e0634e76ff3841999合入；main检出同3324文件49529013字节再次逐项完全相同，最长241。root运输审查提交69c14457先于集成；未push/fetch/tag/PR，旧.g10文件仍未跟踪且不读不动。

开发已交接停写，root接管唯一Gradle。全新g11-p3-2ba76738625d-package绑定受验main2c8fc135、875输入、12已审工具和98控制；17入口manifest2aaa601bf03f0114ecfa2c8569cfd6102aee209cc19fededafb29f3a6ebc39fc，实际controller14f6ff7eb55ca20eb7988a04feec59bb55ab189cfd60b94b5b82cbd071d6404d。控制器基于原五阶段入口，只更新本轮身份/前缀并明确加入18新退出观察项，原21/21/31/7与各预算不改；这次P3完整新跑98和五工程阶段，不复用开发targeted续接结果。

实际PowerShell -NoProfile调用Python -I -S -B已启动，operator-command与实际shell-exit分别保存，尚无新P3通过声明。原件和所有历史失败不变；下一步逐阶段强退出/原始XML/镜像独立审查，封存后才提交main并核对精确SHA CI。仍不宣称真实服务、完整桌面或发布验收。

### C18.1：main新定向原件接受

新P3当前已实际完成98控制序列并进入clean全量，root尚未接受整轮。对本轮targeted的增强独立review_stage.py实际exit0：56suite292case=291pass/1精确Redis live skip，875输入/408类型/12工具均与当前main一致；10process强退出事件/身份/host/已捕获handle和40原始流身份一致，真实root0与完整结算。见g11-p3-2ba76738625d-root-review/targeted-review.json。本轮未沿用开发targeted原件，main新UUID实际重编译执行；full/buildSrc/image/linked尚未完成。

### C18.2：main新full/buildSrc原件接受

本轮main2c8fc135的新full与buildSrc分别经增强review_stage.py独立actual0：full4724case=4721pass/3live skip，buildSrc8pass/0skip，均0failure/error；每阶段875输入/408类型/12工具和10process/40日志、强根退出证据一致。audit_cases精确核对三个live skip身份/原因、定向和全量各11 RedisPaneBudgetTest实际通过，关闭顺序unit另计。PID33case全量实际全过，与已审窄回归case名称多重集合一致。image已开始，尚未接受整轮P3或推送。

## C19：新main完整P3独立接受，封存并进入精确SHA交付

目标为完成本轮G11，不扩展后续候选。新P3实际controller/shell退出0，五阶段已自然结束，没有运行中的Gradle。root最后核对image/linked的强退出证据、原始日志、镜像实物和外置探针，全部接受：183文件、26088类、408测试类型无泄漏，四linked命令实际0，driver connectCalls=0，Redis socketsSettled=true/realServices=0；五阶段共57个process强证明、228内层日志、10外层日志。定向291pass/1skip、full4721pass/3skip、buildSrc8pass；PID33与原生11另行逐项接受，跳过仍是跳过。

新98控制独立audit_p3_controls实际0：21/21/18/31/7，232日志和18强根退出证明一致，91派生控制根属于本轮保存的spec，未重用开发结果。audit_packet实际0再次核对17入口manifest2aaa601bf03f0114ecfa2c8569cfd6102aee209cc19fededafb29f3a6ebc39fc、实际controller14f6ff7eb55ca20eb7988a04feec59bb55ab189cfd60b94b5b82cbd071d6404d及operator argv/exit，12工具与当前源码逐字节一致；各阶段875输入/408类型均未变。

本轮g11-p3-2ba76738625d-frozen/manifest.json已封存102根2501文件37637132字节，SHA fc70f285710a1d9baac4af58e0a0f1f4ee2130aaf1b3e12f51b23d987421acfb，accepted=true/deliveryAllowed=true。首次CI失败、1904拒绝原件、开发长路径/编排失败及审计器原失败均保持原样；本轮新P3没有失败。更新CURRENT、验证说明、设计现状和[最新main报告](2026-10-09-g11-root-exit-main.md)，受验main仍2c8fc135，最终仅文档/证据提交。

下一步精确原字节暂存/提交、只推main并核对同SHA Verify四任务/原始日志/远端main；实际结果由新报告链接的delivery-intent及其独占build回执定位。本检查点不提前称远端CI通过。真实服务、完整桌面与发布验收仍未完成；开发停写，无新增线程/自动调度，不动tag，不自动启动下一轮。
