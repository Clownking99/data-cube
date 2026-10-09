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
