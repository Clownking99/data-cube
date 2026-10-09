# G11 worker：P0 最小验证内核设计

日期：2026-10-09（Asia/Shanghai）。**P0 设计完成，停写交 root 审查；未实现共享核心、未运行 Gradle 或合成验证，没有 G11 新通过声明。**

已完整阅读[本轮计划](../plans/2026-10-09-g11-verification-core.md)、[最小设计](../../maintenance/verification-runner-design.md)、[执行约定](../../maintenance/verification-guide.md)，并只读核对G10 main runner/审计及当前gradlew.bat。共通授权/安全边界引用这些文档，下面仅列本轮具体选择。

## 1. 身份和范围

创建分支前HEAD `7ebf97d248f96f1b954406b09132e4e9d43f7a2e`、允许范围干净；从本地`099dd677a653731bf2fad998cacb65ef33f2c827`创建`codex/g11-verification-core-20261009`，当前HEAD同此值。工作区仍aed5。旧`.g10-verify-blobs.ps1`原样保留且未读；Git显式排除它及`.testagent`。未reset/fetch；本轮只新增本报告，未提交。产品/测试/CI不改，夹具迁移与架构拆分不在范围内。

## 2. 最小文件和职责

拟在 root 准入 P1 后新增以下文件；现在均未创建：

| 文件 | 责任 |
| --- | --- |
| `scripts/verification/VerificationCore.psm1` | New-OwnedScope、Invoke-OwnedProcess、白名单路径与结构化请求；主控期限、owned身份、结果传播 |
| `scripts/verification/OwnedProcessHost.ps1` | 无profile的独占子host，运行单个结构化工具请求；内嵌小型.NET双流pump，不在线程上执行依赖runspace的PowerShell脚本块 |
| `scripts/verification/evidence_tools.py` | 标准库CLI：输入前后绑定、测试类型名单、严格XML统计；输出UTF-8 JSON，无第三方依赖 |
| `scripts/verification/run-stage.ps1` | 本轮薄入口：阶段任务、现有过滤器、预算及验收政策；不复制执行/统计内核 |
| `scripts/verification/isolated.gradle` | 本轮已有隔离构建/生成资源路径适配，产品构建逻辑不改 |
| `scripts/verification/check-core.py` | 新独占合成验收编排和权威fixture矩阵；不作为产品JUnit新夹具 |
| `scripts/verification/fixtures/ProcessFixture.cs` | 自有合成进程：双流、非零、迟闭pipe、子进程/邻居、可控退出；编译产物仅在owned目录 |

核心三个生产文件；单独host隔离可能挂起的pipe泵。现有PowerShell/.NET编译合成C#，已有Python用`-I -S`，无新依赖；P1核对实际工具路径/版本/hash。

PowerShell API 草案：

```text
New-OwnedScope(Repo, EvidenceRoot, RunName, StageId,
               JdkHome, GradleCache, PythonExe, PowerShellExe, InputManifest)
  -> OwnedScope
Invoke-OwnedProcess(Scope, ProcessSpec, DeadlinePolicy, LogPolicy)
  -> ProcessReceipt
```

Python CLI 草案：

```text
evidence_tools.py snapshot --spec <admitted-json> --out <new-json>
evidence_tools.py verify --before <json> --after <json> --out <new-json>
evidence_tools.py types --inputs <json> --out <new-json>
evidence_tools.py xml --spec <admitted-json> --out <new-json>
```

未知/重复key、非法类型/范围显式拒绝，不靠assert；任务、过滤器与skip政策仍只由薄入口决定。

## 3. 路径准入与工具冻结

### 3.1 访问之前的检查

1. 显式绝对repo/工具路径，不展开环境或用户profile；访问前大小写不敏感拒绝禁止路径段、`..`、越界、控制字符、ADS/设备路径/通配表达式，再规范路径/类型。不得先枚举禁止目录再过滤；旧临时脚本、`.git`和用户配置不在许可集合。
3. 工程输入根为具名 `src/test/buildSrc/resources/datacube-brand-assets/assets/drivers/gradle/.github/workflows/scripts/verification`；build/settings/README/gradlew等用精确文件白名单。调用者提供明确manifest；可用带禁止排除的受限Git tracked名单形成该manifest，新增工具由精确名单补入。重复、缺失/未知输入报错，禁止沿用旧runner的静默Test-Path筛除。
4. 路径分隔符正规化为`/`后才去重、分类/生成测试类型名单；Windows大小写冲突和别名重复拒绝。访问前检查已准入路径及祖先，不接受reparse point/symlink/junction；不追随链接来寻找真实用户文件。owned temp的实测8.3别名仅作同一新目录的运行别名，不作为工程输入的替代路径。
5. EvidenceRoot只能在本repo具名新G11 evidence根，拒绝任何已有run目录、旧冻结根、输入文件所在位置或同名输出。原子创建新run，再用新UUID建立home/temp/build/host控制目录；父目录必须已准入、无reparse。每个输出单写，不能覆盖。检查/创建之间的外部恶意替换竞争不承诺原生CAS防护；无并行写者是本轮前提。
6. 子环境从空集合构建，仅五个具名OS变量及显式工具/owned变量；cache仅运行用途，不透传JAVA/GRADLE/PYTHON继承注入。

### 3.2 当前可复核的输入身份

输入schema `input-binding/v1`包含 `testedCommit`、`repo`、`expectedPaths`、`files[{path,length,sha256}]`、`toolFiles`、`externalTools[{role,path,length,sha256,versionReceipt}]`。路径排序固定，长度为整数，SHA-256为64位hex；所有未知/缺失/额外路径拒绝。

工具闭包显式列出module、host、Python、薄入口、isolated.gradle和fixture（合成阶段）；不动态下载/import未归档脚本。启动前把实际执行文件原字节复制到本run/tools，生成工具manifest；由冻结副本加载核心/host/Python，配置和薄入口也有副本及hash。运行前后校验工作源与执行副本均未变。不能只冻结入口却执行未来可变的共享模块。

完整工程输入只保存长度/哈希；raw副本限本轮改动工具/必要配置/probe。不再复制未改产品、整树测试、驱动jar或旧证据。基线Git身份、UUID产物路径和完整产物manifest保留，历史工具/原件不改。

类型名单保持G10的相对源路径映射，不引入Java语法分析；记录sourceCount/typeCount与逐源覆盖，期望来自当前manifest而非硬编码408。空/遗漏/重复拒绝；既有JUnit/probe/配置隔离检查保留。

## 4. 结构化argv、日志预算与总期限

`ProcessSpec/v1 = {exe,args:string[],cwd,environment:map,role}`，exe必须是已准入/冻结身份的显式工具，cwd是repo或owned根。采用ProcessStartInfo.ArgumentList、UseShellExecute=false；不执行拼接命令字符串，不把用户字符串交给`-Command`、cmd `/c`或shell插值。

Gradle试点直接调用已有JDK的java.exe：

```text
args = ["-Xmx64m", "-Xms64m", "-Dorg.gradle.appname=gradlew",
        "-jar", <repo>/gradle/wrapper/gradle-wrapper.jar,
        "cleanTest", "test", "--rerun-tasks", "--offline",
        "--no-daemon", "--console=plain",
        "-Dorg.gradle.java.home=<explicit-jdk>", "-I", <frozen-init>,
        "--tests", <filter-1>, ...]
```

这对应当前gradlew.bat的固定JVM默认参数与`-jar`启动，不解析或拼接bat中的任意命令；同一wrapper.jar、properties和环境入manifest。G10的JAVA_OPTS/GRADLE_OPTS本已清空，本轮仍清空。若将来gradlew默认行为变更，入口契约拒绝旧映射并要求审查，不悄悄偏离。P1用wrapper/argv结构控制证明等价；不访问网络。

初始默认总期限：定向10min、全量15min、buildSrc3min、image10min、linked单命令2min；这些是新工具外层期限，不改JUnit等待。试点依据G10定向约2m29s/全量约6m15s，并非性能承诺。合成矩阵用2–5s独立期限。薄入口可显式更小/阶段必要值，全部写入receipt；无隐式续期或重试。

日志每流32MiB、合计64MiB，固定最大读取chunk16KiB；配置可更小，不允许环境绕过。stdout/stderr独立并发泵到新二进制文件，保留原始bytes；不先ReadToEnd到内存。每流计已读/已写/完整EOF，超cap是`LOG_LIMIT`失败并进入终止/结算，不丢尾后称完整。可能有一个检查chunk，记录该少量额外读入；只存允许前缀，partial标志/hash明确，诊断不污染原日志。

monotonic总deadline在host启动前建立，包含host admission、工具启动/执行、stdout/stderr收尾与终止结算。正常root exit不重置deadline，持续输出不重置deadline。预留最后5s用于终止/结算（fixture单独较小预留），执行/流正常阶段最多到T-reserve；所有Wait/任务收尾只取剩余额度。不得有无参WaitForExit或无限GetResult，亦不得在finally进行无期限的stream等待。

独占host通过新owned控制通道接收JSON请求、报告工具PID/创建身份；不把工具参数放入host shell命令字符串。主控与host均保存阶段事件，主控先启动其监控和日志/结果位置，host才执行工具。host的泵被继承pipe拖住时，主控仍按同一deadline走结算，并可终止自己的host，不能依靠泵完成才报失败。host启动后的日志流本身也有同样有限收尾。

同步OS/.NET启动、句柄关闭/终止若永久不返回，不能靠逻辑timer保证物理硬上限；host隔离缩小影响，主控有限等待后的结果仍必须未结算/失败。P1需实测正常/取消/流收尾实现，不能以假定Dispose会解锁读取来宣称通过。

## 5. 进程所有权与结果schema

不建立全机器进程树平台。根tool和host由本轮直接创建，保留PID、creation identity及可等待的实际handle；已捕获后代记录父子依据/捕获时间/handle，不能只凭一个后来可能复用的PID杀进程。枚举只能在CIM服务端按已拥有ParentPID和生命周期范围缩小、只取必要ID/时间；验证后打开handle。不得获取全部java命令行再过滤，不通过路径猜测杀邻居。

正常结束也须检查已有captured后代与两个pipe，不仅root exit。异常/期限/log cap触发时，只向有所有权证据的root/host/已捕获后代发送终止；记录每次请求成功、异常和实际exit观察。已退出root不能成为“全树已结束”的证据。迟到发现无法安全归属的对象只记unknown，不扩大查询/终止范围。

未捕获的快速派生、脱离/委托系统服务、不可访问对象仍是明确局限；scope只证明直接和已捕获范围。已观察对象退出未知、pipe未EOF、host无法结算或边界信息不够时，结果不得成功；保留PID、owned控制/日志路径和未完成状态供root。无观察并不证明任意后代不存在，schema始终保留此限制。

官方依据：[Microsoft Process.Kill](https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.process.kill?view=net-9.0)说明WaitForExit/HasExited仅表示关联根进程退出；Kill(true)后也可能仍有后代，缺权限对象可能跳过。因此不设置掩盖范围的单个`allProcessesSettled=true`。

稳定 `process-receipt/v1` 示例（字段类型和语义，不是本轮运行结果）：

```json
{
  "schema": "process-receipt/v1",
  "runId": "<uuid>",
  "exe": "<admitted-exe>", "args": ["literal arg"], "cwd": "<admitted-cwd>",
  "totalTimeoutMs": 600000, "settlementReserveMs": 5000,
  "started": true, "rootPid": 123, "rootIdentity": "<creation-time/handle-identity>",
  "rootExited": true, "rootExitCode": 1,
  "hostPid": 122, "hostExited": true,
  "timeout": false, "cancelled": false,
  "termination": {"requested": false, "requests": []},
  "stdout": {"path":"stdout.log","bytesRead":0,"bytesWritten":0,"eof":true,"truncated":false,"sha256":"<64hex>"},
  "stderr": {"path":"stderr.log","bytesRead":0,"bytesWritten":0,"eof":true,"truncated":false,"sha256":"<64hex>"},
  "streamsCompleted": true,
  "capturedDescendants": [{"pid":124,"identity":"<identity>","parentPid":123,"exitObserved":true,"exitCode":0,"terminationRequested":false}],
  "ownedSettlement": "complete",
  "coverage": "direct-and-captured-only",
  "uncapturedDescendantScope": "not-proven",
  "status": "failed", "primaryFailure": {"kind":"ROOT_EXIT_NONZERO","exitCode":1},
  "secondaryFailures": []
}
```

`rootExitCode`在未观察实际exit时为null，不能写0；`ownedSettlement`为complete/incomplete/unknown。成功条件是root code0、host与已捕获范围退出可证、两个stream EOF/完整文件、无timeout/cancel/log limit/泵异常；仍不外推为所有本机进程或任意逃逸后代。事件/终止请求单独追加，不能把kill成功当exitObserved。

阶段 `stage-result/v1`含 `processReceipt`、`testResults`、`inputBinding`、`toolBinding`、`primaryFailure`、`secondaryFailures`和`status`。第一实际失败保持主因；例如编译exit1已保存，后续零XML另记`TEST_RESULTS_UNAVAILABLE`，不得统计异常覆盖编译退出。若process exit0而零XML，则`INVALID_TEST_RESULTS`主因。统计工具自身进程也按相同owned期限/身份执行。

## 6. XML与输入负例矩阵

XML仅从本轮owned、指定精确目录读取直接TEST-*.xml；拒绝reparse、非本轮路径、零文件、损坏/DTD/entity声明、非法/负计数、混合schema。使用标准库，限制每文件16MiB、总128MiB、suite数2000/case数100000，读取前检查长度，解析时实际case数也限制；禁止无限外部实体扩展。超限报统计失败，不丢XML换绿。可显式较小fixturecap。

本仓JUnit每文件单testsuite。独立比对suite tests/failures/errors/skipped与直接testcase/node数量，支持空skip；同case同时failure/error/skip、重复suite或同suite内重复case身份拒绝。跨suite相同方法名允许，以suite/class/name组合作身份；参数化name按原文保留，不合并为方法计数。保留每个skip ID/原原因（无message时仍是skip）。结果返回完整case计数与失败列表，是否容许live skip由阶段政策判定，skip不计pass。

| 合成负例/控制 | 必须观察 |
| --- | --- |
| root返回7、带一行stdout/stderr | rootExitCode7原样、日志/hash完整，阶段失败，无后续打印覆盖 |
| 双流超过pipe buffer且持续输出；恰好cap/+1 | 两流并发，无缓冲死锁；cap/+1受控失败，期限不续期 |
| 编译前exit1无XML；exit0无XML | 前者保留process主失败+统计不可用；后者非法测试结果，不能绿 |
| 合法pass/空skip/带原因skip | 属性与case一致，skip ID/原因保留，pass不含skip |
| tests/failure/error/skipped任何矛盾、重复身份、损坏/实体XML | 非零统计退出，原XML保留，不部分汇总成功 |
| before后修改/删除/新增未知输入 | 精确错误类别，before/after各保留，阶段失败 |
| 同一输入的反斜杠/slash/大小写重复；空/漏测试名单 | 正规化后重复拒绝，逐源覆盖，不能空集合隔离绿 |
| 禁止路径字符串/越界/ADS/reparse/输入输出碰撞 | 访问前拒绝；fixture不创建禁止目录，不查询用户文件 |
| 已有run名/旧冻结根/输出存在 | 原文件bytes不变，拒绝复用 |
| root退出0、已捕获child持有stdout或stderr迟闭 | rootExited=true但streamsCompleted=false，deadline内失败和有限结算 |
| root/child挂起、终止后实际exit | terminationRequested与exitObserved分别成立，所有fixture handle独立wait |
| 终止拒绝/不可结算adapter故障；捕获身份不足 | unknown/incomplete失败；不伪造0exit；fixture最终由外层拥有者有限收尾 |
| 独立邻居同时存活 | 不加入owned范围、不查询其命令行、不终止；fixture拥有者最后单独收尾 |
| host泵/统计进程失败或迟闭 | 同一总期限，原process receipt与首失败不丢，未结算不能成功 |

未退出分支采用测试专用wait/terminate观察adapter故障注入，禁止实际制造无法回收的系统进程。另用真实可回收helper覆盖root早退/child继承pipe；fixture协调器保留自己创建的handle，结束时逐个实际等待，不以被测工具自写receipt当物理证据。

## 7. G10对照和定向试点映射

P1先在新UUID fixture/evidence运行，不重跑或写入G10冻结目录。复制G10 main的Run-P3/审计逻辑到新比较fixture，记录origin SHA和逐项适配；没有读原用户profile的默认路径。对照只复用核心旧行为，不把旧任意shell字符串重新作为新API。

旧逻辑适配器仅将“Gradle invocation”替换为相同合成exe/argv，将XML/input来源替换新fixture；旧ReadToEnd/Wait/统计分支保留并有diff。外层拥有者给旧适配器同一有限总期限/日志cap，若旧迟闭挂起就保存prefix日志、外层终止并记录其未返回，不为比较无限等待。新/旧各自新scope，输入fixture bytes与command payload SHA一致。

对正常exit/非零/合法XML/输入不变的语义结果要求一致；对已识别旧缺陷（迟闭pipe、无界等待/输出、矛盾XML、名单/缺失输入静默过滤）允许且要求新工具明确拒绝，保存差异期望表。只有合成控制的差异，不把人为旧逻辑失败称产品RED。比较失败不自动重试到绿。

唯一P1 Gradle试点保持G10 main完整Redis/关闭过滤器原顺序：

```text
com.datacube.redis.*
com.datacube.fx.RedisPane*Test
com.datacube.fx.*Close*Test
com.datacube.fx.*Shutdown*Test
com.datacube.fx.*Construction*Test
com.datacube.fx.*Lifecycle*Test
com.datacube.fx.ShutdownQuarantineTest
com.datacube.fx.task.*
com.datacube.fx.WindowShutdownControllerTest
com.datacube.service.ConnectionManager*Test
```

| G10逻辑 | 新薄入口对应 |
| --- | --- |
| 全新编号+UUID/短temp | New-OwnedScope准入；旧冻结目录明确拒绝 |
| 固定任务/过滤器 | 薄入口原样传cleanTest/test、10过滤器、offline/no-daemon/rerun-tasks/native FX |
| PowerShell -Command拼bat | 结构化java wrapper argv，保留当前bat默认JVM/Gradle参数与环境语义 |
| 双流ReadToEnd+无参Wait | 独占host、文件pump、总deadline与分字段结算 |
| Test-Path筛输入 | 显式要求的manifest，缺失/未知/变更失败，工具闭包一起绑定 |
| XML便利summary | 独立严格Python结果，当前原XML仍是审核入口 |
| 镜像测试类型名单 | 正规化逐源覆盖；P1只验合成名单，P2才接实际镜像审计 |

试点不再执行一次旧Gradle定向；新结果与同fixture旧对照、现有任务/过滤器契约对应，真实产品只由新入口执行一次。历史292/4714等计数不是新期望硬编码；按当前受验源码和新XML实数报告。任何FX失败保持原失败，不改等待、强制headless或自动重跑。

## 8. 证据、准入与回滚

每run保留实际工具闭包、request/事件/进程receipt、原日志/hash/XML、before/after/类型名单和stage result；root独立重算，不只信summary。只保存新工具/必要probe原件，不复制旧树。阶段门槛按计划：P0接受才P1合成+一个定向，P1接受才P2，P3 root接管。未来工具/薄入口单独提交，回滚只恢复本轮入口旧调用，失败/两套身份不删。当前停在P0，无实现、fixture/Gradle执行或提交，等待root裁决。

## P1 完成：停写冻结，交 root 审查

日期：2026-10-09。P0已由root准入后实施。本节补充实际结果，以上P0原文未重写；[准入时P0原字节](evidence/g11-p1-development/approved-p0.md) SHA-256仍为`782DD401BEC3BC45DF9AF58A599A956014711A2B6D80307608B2B5E6D3668AF3`。**P1实现、合成与唯一一次定向试点完成；现在停写。没有P2、提交、合并或推送。**

### 结果和可复核原件

- 唯一定向：[stage-result.json](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/stage-result.json)。实际Gradle退出0；56套件、292用例，291通过、0失败、0错误、1个live Redis前置跳过。跳过单列，不计通过；原生FX使用`headless=false`。18条重复显示名提示保留文件/suite/顺序身份，没有去重或修改产品测试。
- [原始Gradle stdout](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/processes/gradle-targeted/stdout.bin)、[stderr](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/processes/gradle-targeted/stderr.bin)、[实际结构化请求](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/processes/gradle-targeted/request.json)、[进程回执](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/processes/gradle-targeted/process-receipt.json)与该scope的`xml/`原始56文件均保留。原有10个Redis/关闭过滤器没有增删，任务为`cleanTest test --rerun-tasks --offline --no-daemon --console=plain`。
- [运行前输入](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/inputs-before.json)、[运行后输入](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/inputs-after.json)、[绑定结果](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/input-verification.json)：869项长度/SHA前后一致，受验commit为`099dd677a653731bf2fad998cacb65ef33f2c827`。实际磁盘集合与显式名单对照，未用gitignore过滤磁盘；生成目录按精确相对路径排除，合法`src/.../build/`包不会被跳过。根文件5项明确required，缺失的`gradle.properties`明确optionalAbsent；出现也会拒绝。408个测试Java源码动态生成[非空类型名单](evidence/g11-p1-targeted-001/943306aa3f28470cb7ae679ca20b0529/test-types.json)，没有408硬编码准入。
- [独立外层pilot owner](evidence/g11-p1-development/pilot-owner-result.json)实际等待退出0、查询私有Job成员清零；bootstrap工具前后不变。内部Job结算complete，捕获成员实际退出保留。整个P1只执行一次真实Gradle定向；旧对照没有运行Gradle。

### 实现与P0的实际差异

新增六文件：`VerificationCore.psm1`、`OwnedProcessHost.ps1`、`evidence_tools.py`、`run-stage.ps1`、`isolated.gradle`、`check-core.py`。本轮薄入口只包含targeted与fixture；P2阶段尚未接入。

- 以同一标准库Python helper代替拟议单独C# ProcessFixture文件，避免编译额外fixture executable；C#只用于内嵌Win32 Job/短路径与有界双流泵。没有新依赖、安装或产品/JUnit修改。
- 采用私有Windows Job与gate：无profile host赋Job成功后才放行工具；赋Job失败不放gate，按已持有的直接host handle有限回收。Job设置KILL_ON_JOB_CLOSE，所有后处理有finally释放。所有权证明为Job成员与已保留的实际PID/creation identity，不做全局PID/Java命令行扫描。父进程捕获root身份时对照creation identity；捕获成员有实际退出码。
- host与parent双层泵按16KiB块持续读、写后flush；工具每流32MiB、两流64MiB，host诊断另每流1MiB。超cap最多多读一个检查chunk，立即停止该泵；保留已写前缀及原始字节hash，partial按每流EOF/超额/错误判定，不因exit7把完整日志标partial。
- 总期限在Invoke准入开始计时，涵盖host/tool/流/Job收尾；预留有限settle时间。只有实际达到截止才记录DEADLINE。可选CancellationToken默认不取消；合成用CancelAfter验证运行中取消与保留tick前缀。取消、终止请求、实际退出与结算分别记录；无真实退出观察不伪造0。
- 首失败按共同Stopwatch单调tick合并；预算先超限再broken pipe派生exit120，主因仍LOG_LIMIT；先root7、后子流超限/迟闭则主因仍NONZERO_EXIT。完整root0但残留不持pipe子进程，有限宽限后标OWNED_DESCENDANT_REQUIRES_TERMINATION，不把强杀当成功。
- XML/input/type职责为Python CLI的`xml/snapshot/verify/types`。CLI所有路径先词法拒绝，再从卷根到叶逐级检查reparse；禁止路径负例仅字符串，spy实证lstat调用为0。suite属性与每个case状态独立对照，空skip有效、零XML/零cases失败、重复suite/矛盾/损坏拒绝；合法重复显示名不合并。阶段try/catch/finally保留首失败，统计启动/写结果失败不覆盖编译7；只允许明确RedisLiveIntegrationTest前置skip，其他native/fixture skip失败。
- 实际执行完整冻结闭包六文件，显式Repo传入，不从归档目录推算repo。初始bootstrap源与后续冻结执行字节对应，外层before/after hash核对；每scope归档原始工具字节及长度/SHA。当前gradlew.bat映射以原字节`FEDAD02C18E266EC094995A5751B7FE1EB6E74F66BF75DB64FAE2E50EB22C234`为准，变化必须重新审查；Java argv复现该batch的Xmx64m/Xms64m/appname/jar映射，不拼接可执行命令字符串。

真实home/temp/build位于独占系统temp UUID树，证据目录仅保留必要原件，不复制整棵build/runtime/JDK/驱动树。运行路径、短别名和受控环境在各scope记录；实际编译产物仍留在自己的临时树供交审，未清理未知范围。

### 合成和旧逻辑对照

[正式进程/阶段第5轮](evidence/g11-p1-development/process-checks-fifth.json)21控制实际完成；每例独立外层Job查询empty、旁邻存活，最后[邻居直接handle等待退出](evidence/g11-p1-development/process-matrix-fifth/neighbor-exit.json)保留。矩阵覆盖正常/7/双流各1638400字节/字面argv空格中文引号空串、持续输出超时前缀、cap恰好/+1/溢出、子pipe迟闭、先7后pipe迟闭/超限、root0残留无pipe子进程、实际Start失败、赋Job前故障、观察结算故障注入、取消、工具变更拒绝、编译7+零XML/统计进程启动失败、live允许skip/native拒绝skip。故障注入不是不可回收OS进程；外层真实回收/查询另有原件。

[正式Python第5轮](evidence/g11-p1-development/tool-checks-fifth.json)包含21个词法/无访问/XML/输入控制，分case原始XML与before/after/源字节保留，不覆盖或删除正式负例输入。[重复run控制](evidence/g11-p1-development/run-reuse-result.json)实际退出1/RUN_COLLISION，原owner marker hash不变。[补充4控制](evidence/g11-p1-development/additional-controls/results.json)使用同一pilot冻结Python核心，不改核心；实际git check-ignore证明合成`src/legitimate/build/Ignored.java`被ignore，磁盘集合仍拒绝未知源码，另覆盖optional出现、重复suite文件、未知binding字段。对应git原始命令/退出/stdout也保留。该补充在pilot后只核对冻结工具，不再执行Gradle。

[最终旧逻辑7对照](evidence/g11-p1-development/old-comparison-final/comparison-results.json)新目录保存G10 Run-P3原字节、受控adapter、diff、实际同字节helper、每例原始输出/退出和外层Job查询。正常/argv/7/dual及合法同名混合结果XML计数保持语义；旧child-pipe两例由独立8秒外层deadline回收，观察插桩先保存实际root0/7，不改原ReadToEnd/Wait/GetResult顺序；旧零XML仍exit0/tests0是已识别旧缺陷，新核心拒绝。对照是工具行为控制，不是产品RED声明。root另已报告正常四类原始输出逐字节相等及独立契约检查，本worker不把root检查计入自己的通过数量。

### 首次失败、版本与冻结

[首次失败说明](evidence/g11-p1-development/first-failures.md)与[实际退出台账](evidence/g11-p1-development/command-ledger.json)区分debug/失败/正式通过。第一轮损坏XML适配器漏捕ParseError；首次stderr未重定向，诚实只保留chat原始tool输出。第一轮进程汇总错误passed，但continuous前缀为0，原件保留为失败；第三轮overflow主因被派生120覆盖，原件保留；第四轮仍有未到期限却记录DEADLINE的问题，因此正式凭据为第5轮。第三轮Python工具实际1而shell打印后返回0的wrapper错误也单列，绝不取shell0当通过；后续严格-I -S -B并传播实际码。

实际工具版本：PowerShell7.6.5、.NET10.0.11、Python3.12.14、Temurin25.0.1+8。版本原始stdout及host回执在pilot scope；不是.NET9文档版本。exe长度/SHA运行前在scope、运行后[再次核对](evidence/g11-p1-development/executables-after-pilot.json)三项一致，不归档整个运行时依赖树。

冻结：[manifest.json](evidence/g11-p1-frozen-20261009/manifest.json)，1777文件、32,511,490字节，包含失败历史与正式原件、各次实际工具闭包；不含临时运行树。manifest SHA-256：`AC3F1E6289E942D86DFD9D47F9E9F22BD942BAE84A1D11A9AC71056740D1CC9A`。独立集中[最终工具原字节](evidence/g11-p1-frozen-20261009/tools/)与pilot使用字节一致。冻结后不修改这些根中的文件；本worker报告位于manifest根外，另有最终报告hash。

局限：Job只证明成员范围，外部WMI/service委托不受本工具证明；OS同步调用永久卡住没有物理硬时限保证，外层有限观察失败时不宣称结算。未退出分支采用观察适配器故障注入并独立真实回收；没有制造不可杀进程。真实Redis/DB/pg_dump、签名安装、完整真实OS/驱动以及P2 clean全量/强制buildSrc/image/linked未验；正常buildSrc编译作为定向依赖不等于P2强制buildSrc测试。v3.2.9及暂停跟进不动。

范围内Git状态只有新共享工具和本报告（证据另单列）；src/test/buildSrc/构建文件/resources/CI无产品修改。分支仍`codex/g11-verification-core-20261009`，HEAD仍`099dd677a653731bf2fad998cacb65ef33f2c827`。旧`.g10-verify-blobs.ps1`未读、未暂存、未清理；没有新chat/agent、fetch/commit/merge/push或持久调度。下一步由root审核此P1与原件，明确准入P2后再执行后续阶段；当前停写。