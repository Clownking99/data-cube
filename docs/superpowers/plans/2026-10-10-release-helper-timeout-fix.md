# v3.2.11 发布测试超时修复

日期：2026-10-10。协调/独立审核/main 集成：当前会话；开发：既有 GPT-6.1-sol 线程 01a11b86-2026-7ed3-86c5-1ec232640653。

## C0：失败基线与范围

- 目标：修复 PortableUpdateHelperTest 的受控 PowerShell 夹具等待、失败诊断与退出结算，完成新证据验证、main 集成和发布闭环。
- 当前 main：3fb6f3ae0b52c22cf02821f4ca1b04f650de82dd；scoped 工作区干净。v3.2.11 已推送并指向该提交。
- 实际失败：Build and Release 38037623220，build job 114172976047，Verify release source 阶段；4736 tests / 1 failed / 3 skipped。installedHandoffCannotClaimInstallerCompletionOrReplaceTheRunningImage 在 run 的 20 秒等待处失败。同一次工作流前置 Windows Verify 已通过，不能代替失败的发布构建验收。
- 原始日志：build/owned-release-helper-fix-bb14f6bacde74718a89a5a36284a6eae/failed-release-build.log，SHA256 532e3528356a58ee8b751763fb13779847d0a304d62c5e536c24ccdad787668d；下载实际退出 0。程序包构建与发布步骤均未执行。
- 独立源码发现：20 秒同时覆盖 PowerShell 启动和脚本执行；超时错误不携带输出/阶段；finally 仅 destroyForcibly，未等待物理退出。INSTALLED 场景已注入 WaitOriginal/Launch，未实际运行安装器；生产分支没有启动确认等待。
- 未知：现有失败日志不能区分启动慢、脚本执行慢或其他主机因素，不将推断写成根因事实。

## 执行与验收

1. P1：开发在独立 worktree 新分支实现最小 test-only 修复。保留生产脚本、安装状态和原镜像断言。用有界启动/执行阶段和可诊断输出处理等待；超时/异常/中断后核实自有进程退出。增加实质回归，不跳过、不重试到绿、不仅放大超时。
2. P2：当前会话独立阅读 diff、原始命令/退出/当前 XML/哈希；发现问题向同一线程返工。开发线程独占 Gradle。接受定向后，完成新全量、强制 buildSrc 与镜像；测试专用改动按影响记录镜像/linked 必要性，不扩展验证工具体系。
3. P3：审核通过才提交合并 main；当前会话接管新隔离环境复验与精确 SHA CI。保留失败 v3.2.11，修复通过后使用下一个未占用版本发布，不移动或删除旧 tag。只认新 tag 的实际发布结果。

安全边界：.testagent 禁读禁改禁枚举；不读取原连接/profile/凭据/SQL 历史/业务文件；合成镜像与独占临时目录；不访问真实服务，不启动真实安装器，不本机安装更新，不 fetch/PR/外部联系；旧冻结证据和旧 tag 保持原样。沿用本轮修复、提交、main 推送和发布授权。

下一步：开发阶段 P1；协调线程保存 GitHub 原始运行状态并独立检查执行契约。

### C0 原件封存

原始失败构建日志、运行 JSON 与两条读取命令退出值已保存至 `docs/superpowers/verification/evidence/release-helper-root-bb14f6bacde74718a89a5a36284a6eae/`。manifest SHA256：`afb83a3edb0669f21896b87ac26b540496b64eb275c83463eb63e85347d97a97`。此根作为失败基线冻结；开发证据另建新根。`-Xlint:unchecked` 仅编译提示，失败是 JUnit 的进程等待断言。

## C1：测试执行器首轮独立审查（未接受）

开发分支 `codex/release-helper-timeout-20261010`，仍基于 3fb6f3ae。初稿仅改 PortableUpdateHelperTest 并新增测试专用 UpdateHelperProcess / UpdateHelperProcessTest，生产 helper 与原状态/镜像断言未改。尚未作为通过证据。

root 直接阅读初稿后下发修正：退出 0 但无 bootstrap 就绪标记不得成功；双流读取和 XML 诊断必须有界并标明不完整；实际阻塞回归须先确认 body 到达阻塞点；用持有的 Process 身份验证真实退出并关闭无用 stdin；诊断异常也应恢复中断。上述是初稿问题，未合 main。命令级语法拼接错误发生在下发工具调用前，随后正确下发；首次默认沙箱读取工作树被拒，获准只读升级后读取成功，未绕过限制。

下一步：同一开发线程修正并运行本轮 update 包定向；root 继续独立审查，开发期间 root 不运行 Gradle。

## C2：修正后源码初审与入口拒绝

root 复读确认：成功要求 bootstrap 标记；输出每流最多读取 64 KiB 加一字节用于超限判断，超限标记 NOT_COMPLETE 并失败；真实阻塞/中断回归先观察 body 标记，再基于实际 Process 检查退出。启动 90 秒、执行 20 秒、清理 10 秒分别记录；并未证明原 CI 的 20 秒发生在启动阶段。新增 7 项回归（3 个模型，4 个实际 PowerShell），原 11 项 PortableUpdateHelper 断言保留。

首次定向入口在 Gradle 前被 RECEIPT_INPUT_OUTSIDE_OWNED_SCOPE 拒绝，测试未运行，不算失败测试或通过；开发线程保留原件，只修正本轮控制器把 baseline 原字节复制进 owned scope，并使用新 UUID。共享 12 工具和测试源码未因该入口修正而变化。下一步：核对修正后的首次实际定向 XML。

## C3：定向独立接受

修正后独占目录 `redis-binary-p2-2fecc37fd6c9448ebcc8dcbfc151faa5-update-targeted`，scope `62021077aeba4419bd6ff032a18bb5f9`。本轮实际 12 XML / 92 通过 / 0 失败 / 0 跳过；原 11 helper 场景和新增 7 项逐一存在。执行只有预先声明的一次，没有重跑到绿。

root 独立重算 24 入口、3 源码和 12 工具身份；7 条内部命令的原始 root-exit / root-identity / host / parent 证明匹配，实际退出均为 0，日志 EOF/长度相符，无截断。外层实际 0、60.235 秒，private Job 前后均为空。XML 中 18 条 helper 回执的 Base64 与 SHA256 已重算；其中 3 条明确为模型，其余 15 条来自实际 PowerShell，不能混为 OS 证据。审核回执 `build/owned-release-helper-fix-bb14f6bacde74718a89a5a36284a6eae/targeted-review.json`，SHA256 `59f4a148c23967365d09c056de7627ce27977522777003996051e96f41eb6b1c`。

已授权同一开发线程在冻结源码上独占执行新 clean 全量、强制 buildSrc 和 jpackageImage；无需重复无关控制矩阵。接着 root 复核原件后集成 main。原失败具体卡点仍未知；真实安装/更新/公司数据库未执行。

## C4：完整工程结果与 linked 新失败

root 独立重算：full 354 suites / 4743 cases，其中 4740 通过、3 个明确 live 前置跳过，0 失败；buildSrc 8 通过、零跳过。两阶段 20 个命令的原始身份、退出事件与四条流已核对，551 份原件身份记录于 `engineering-partial-review.json`，SHA256 `87fa3761054678aefc0f9215a6cde0371c8fd9eb7d6f4cf2c475db199a1b10b0`。root 审核器首版遗漏 JUnit `Assumption failed: ` 包装导致误拒；原脚本与失败另存，只读 v2 按精确前缀+既定原因通过，未重跑测试或改变跳过准入。

image 已实际通过。linked 的 `redis-linked` 在 `redis-binary-p2-0ae879a0104f4d00926148f6f0f617e1-linked/23c5808652a14d07a8e578442413a0d1` 返回 `OWNED_DESCENDANT_REQUIRES_TERMINATION`，外层实际 1，首失败保留且不接受为通过。原件 host/root 都退出 0，最终两份 captured 句柄退出 0，但终止请求为 true；这不足以证明 Job 判定误报。

源码检查显示：旧 VerificationCore 使用固定 1 秒 grace，终止前 PID 快照变量未写回执，决策又重新查询 PID。因此原件缺少触发终止的实际成员信息。官方说明仅用于理解接口，不能反推出本次原因：[Job PID list](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_process_id_list)、[Job accounting](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_accounting_information)。

下一步：仅准备一次有界、本地、独占的 linked 诊断，复用同一冻结镜像/探针，保持原 1 秒 grace 和判定门槛；在独立副本加入 Job 成员/持有句柄/退出时序观测，先由 root 审查。不修改共享工具，不重复 Gradle，不将诊断绿色覆盖首失败。修复尚未提交/合并，下一 tag 尚未创建。

### C4 诊断准备预审

首个诊断准备补丁 `8516c9b079c74016943b501417ebbb0e-linked-diagnostic-prep` 在执行前被 root 拒绝：补丁意外移除原 failureTick 初始化，并在 StrictMode 下读取未初始化的 script:diagOverflow；未执行实际 probe。已要求保留 v1，生成 v2 准备原件并补纯内存的空/单/双成员数组形状检查，确保观测不改变查询结果用法。共享工具仍未修改。

### C4 源码提交与诊断 v2 准入

已审查的三个测试文件本地提交为 `84a4855c633c0d665ae236383a8c660360d54617`，与定向/全量受验源码 SHA 一致；仅源测试提交，未合 main、push、tag。此提交不表示 linked 通过。

诊断 v2 的 51 个绑定文件已由 root 重算；补丁 SHA `d3ba35dbae20531eef0643acc9c02e910b11b3025729bec498cded366292b028`，输入清单 SHA `2885ba99928d0fb74096abdda199688e9fb8aeff8641085ed87e7a5d8b52b2e6`。纯内存数组形状与语法检查通过，实际 probe 仍未执行时完成准入。随后仅授权一次有界诊断，固定原门槛、同一镜像与 probe，外层记录实际退出和自有 Job 结算；无自动重试。

## C5：一次诊断未复现，转 root 独立 P3

唯一一次实际诊断完成，Java root 11540、host 33192、外层均实际退出 0，流完整且 Job 为空；query-04 成员为空，没有终止请求。新增观测与持有句柄可能影响时序，不能据此把首失败认定为误报或宣称已修复工具稳定性。原 980 文件首失败封存及 55 文件诊断封存保持，诊断 manifest SHA `018f40537d4c04ea9e43671dfbf303e96ddd5b1559196a60bb46afc81f49db96`。诊断外层入口一次 Python nonlocal 编译错误发生于任何子进程启动前，现场保留，修正后只执行了授权的这一次实际 probe。

技术裁决：三个测试源修复经独立源码、定向、全量审查，可本地可逆集成。开发 P2 明确为 partial（linked 失败），诊断不是正式验收。不扩展共享工具改造、不改变门槛。root 接管全新隔离 P3，完整正式阶段全通过才推送 main 和新 tag；若再次出现同类失败则停止发布定位，不反复重试。该顺序保持最终验收标准，以新的独立正式结果裁决交付，原工具偶发失败仍列待办。

下一步：开发线程只封存并提交准确的 partial 证据/报告，停止 Gradle；root 核对提交原字节后本地集成并接管验证。

## C6：main 本地集成与新 P3 准备

已核对工作树提交 4243325c 的 1271 个变更路径及 1266 个 handoff 文件，Git blob 与开发工作区逐字节一致；独立回执 SHA256 `0622478071bb9a209477aa6d619956672af33bfec294c424e36c84add51caae5`。审核器首版重复拼接 manifest 的仓库相对根，读取首文件前失败；保留首版，v2 只修正路径根，不改变哈希标准。

本地合入 main 为 `bb84b72a47f5ed21ce9833b609a301e29b4a9ebd`，未推送或打 tag。P3 首次准备在启动任何测试前发现两个 checkout 的 256 个既有文件字节不同；逐个与开发实际文件核对后确认均仅 LF/CRLF 差异，三个受改测试与 12 工具原字节一致。未转换任何源文件；main 独立使用实际 880 文件字节建立新基线，保留被拒准备目录和 v1 脚本。新的定向目录 `redis-binary-p3-d84e38ab1f5a44ce80dccf56556ddd01-update-targeted`，仅运行一次；root 现为唯一 Gradle 执行者。

下一步：接受新定向后，按原门槛依次 clean 全量、强制 buildSrc、image、正式 linked。开发 P2 的 linked 首失败和诊断仍分列，不转换为通过。

## C7：main 新定向接受，工程阶段执行中

main 的一次新定向完成，12 XML / 92 通过 / 零跳过；原 11 helper + 新 7 项场景完整。7 个命令与 18 条 helper 回执由 root 独立重算，实际外层退出 0、自有 Job 前后均为空，81.843 秒。独立审核 SHA256 `2e65ca04e0809b41ee24be1e99124508a9abfb4f85554ef9233fd15f1ea16794`。136 个原件冻结于同名 `-frozen/manifest.json`。

工程控制器位于 `redis-binary-p3-953bd2a12f5745e19ec22d2e05481bac-update-engineering`。只迁移 main 路径、P3 UUID 命名和 root 执行者标记，原阈值与共享工具不变。历史 98 控制只重核版本和原件，执行次数为 0；对历史绝对工作区引用只在新控制器内映射至 main 的逐字节相同副本，旧结果未改。新工程阶段使用本轮 P3 定向基线，不借开发结果冒充当前执行。

远端只读核对 main 仍为 3fb6f3ae，v3.2.11 对象和目标未变，v3.2.12 当前未占用。下一步：全量/buildSrc/image/linked 全部正式接受后才准备 main 推送和新 tag。

## C8：main 新 P3 正式全部接受

本轮唯一一次 main 正式工程序列 full/buildSrc/image/linked 全部实际退出 0。全量 354 suites、4740 通过、3 个预先声明的 live 跳过；buildSrc 8 通过、0 跳过；更新 18 个关键用例和 Redis 原生 14 项完整通过。root 独立核对 47 个工程命令的退出事件、持有身份、四流完整性和当前 XML，810 文件审核 SHA256 `cc97c688583a93717dd0bcb47514c0ce76c44f3a30ea4519ddde2b06c1bc65a4`。

jpackageImage 与本轮首次正式 linked 均通过，无强制终止请求，Job 实际为空。183 文件镜像当前哈希与构建前后清单一致；412 测试类型与探针均未泄漏进产品，真实服务 0。独立镜像审核 SHA256 `bb9813cd1aa7ce61c9b919d55fdaaae4847b783a9fbd00e563b22eadd2d9c121`。

1175 份原件封存于 `release-helper-main-a1da55c48e12411fa0e100190b029308/manifest.json`，SHA256 `04e7ab257ebd68266f1ea266984f6acfec48c4a1ece2a36345da70a8fd953bd7`。开发 P2 的首次 linked 失败仍为失败；诊断只说明未复现，本轮新 P3 不能说明旧工具偶发收尾原因已修复。最初 CI 等待发生在启动还是脚本执行仍未知。

下一步：提交本轮文档/证据，核对 Git 原字节后推 main，并核对该精确 SHA 的 Verify 四任务；再以未占用 v3.2.12 触发正式自动打包，等待实际发布结果。真实数据库/真实安装更新/签名与桌面完整发布验收未执行，不作通过声明。
