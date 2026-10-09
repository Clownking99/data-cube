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
