# G10 Redis 资源预算：协调与独立验收

## C0：新授权、现状与首个诊断

当前目标：独立推进 [G10](../plans/2026-10-09-g10-redis-resource-budget.md)，完成后再按 [维护成本计划](../plans/2026-10-09-maintenance-cost-review.md)整理；不混入 G9、G8 外部验收或其他功能。当前会话继续负责规划/审查/返工/集成，沿用既有 GPT-6.1-sol 开发会话，使用独立 G10 分支。

main 为 b6622c95d5efc0b240dc7ae4b788f44542c87b5a，范围内干净。G9 最终四项 CI 通过和 PAUSED 状态已核对，并将独占 build 目录中的关键最终回执与原始逐 job 日志按旧 manifest 哈希复制到[前轮结案归档](evidence/g10-redis-20261009-baseline/prior-g9-delivery/subset-manifest.json)，避免后续 clean 丢掉实际结案依据。未重写 G9 冻结证据或把历史成功记为 G10 成功。

改动：仅本轮计划、当前合成诊断与协调记录，没有产品/测试源码变动。已直接读取 RespCodec、RespClient、RedisSession、RedisConsoleSupport、KeyTreeBuilder、两个 Redis pane 和现有测试，确认解码大分配/递归、控制台和键树累计、格式化放大仍缺少完整预算。SCAN COUNT 为提示的协议语义已核对官方文档。

验证：当前源码单独编译到独占 UUID 目录，清空环境，只传必要 OS/JDK/home/temp；每个诊断使用 -Xmx32m/-Xss256k 独立 JVM，零网络。新[baseline.json](evidence/g10-redis-20261009-baseline/baseline.json)记录源哈希、编译和三个命令/exit/raw：13 字节巨大 array 头触发 OutOfMemoryError；6000 层数组触发 StackOverflowError；100,000 字节 simple line 完整接受。诊断进程按预期观察旧缺陷而 exit0，不代表 G10 验收通过，也不把捕获 OOM 当产品方案。

失败/未验：上述三个现存问题待修；协议预算、socket 串线/重试、UI 保留、真实 Redis/原生桌面均未取得 G10 新通过证据。源检索最初误用 RedisClient.java/ui 目录，已改为实际 RespClient.java/fx 文件；未因此修改产品。G9 的 Windows 初始化偶发超时继续保留根因未知。

下一步：将计划与基线提交本地，通知既有开发会话从最新本地 main 新建 codex/g10-redis-resource-budget-20261009，停在 P0 设计审查；root 通过后交给开发唯一 Gradle 执行权，按 P1a/P1b 逐阶段验收。维护成本整理等待 G10 交付，不删历史证据。v3.2.9 与两个旧 PAUSED 跟进不改；本轮尚未推送或访问任何真实服务。

## C0.1：P0 已下发，独立补验连接恢复

当前目标：审查 G10 设计后准入 P1a。计划与首诊断已提交 main `66edd24f50a327a82628d70de72fb7b7911c2ae2`；root 已通过正式消息向既有 6.1-sol 会话 `01a11b86-2026-7ed3-86c5-1ec232640653` 下发 P0，并独立核对 aed5 worktree 为 `codex/g10-redis-resource-budget-20261009`、同 HEAD、范围内干净。旧 G9 分支仍 `6b8ceb93`；开发重新核对 3516+524 冻结原件一致，尚未运行 Gradle。

独立审查补充：现有成功回调内格式化/候选页构造若直接抛预算异常，会绕开控件恢复；须先形成可接受模型并处理回调异常，不能卡住 busy/input。另发现客户端只记初始 DB，而 typed/raw SELECT 可改变 DB；root 使用当前 RespClient/RespCodec 在全新清空环境/独占目录编译，只绑定 127.0.0.1 的两连接脚本，实际观察 SELECT 7 已成功后，GET 断线重试时恢复 SELECT 2。新[原始结果](evidence/g10-redis-20261009-design-review/result.json)含源哈希、编译/运行 argv、exit/raw；两个 socket 与服务线程实际结束，未访问真实 Redis。此为新复现缺陷，不是验收绿灯。

改动：计划补充已确认 DB 恢复、未确认 SELECT 不重放、不可恢复复杂 raw 状态明确失效及 UI 拒绝后的恢复。已向开发发送具体 P0 补充，不扩展权限或完整 CLI 状态机。

失败/未验：全部产品问题仍待实现验证；P0 报告尚未完成。新建每 15 分钟持久跟进尝试被自动审批拒绝，理由是本次继续 G10 的授权未明确覆盖持久调度与未来 main 集成/推送副作用。已单独向维护者请求明确授权，未重试或绕过；当前会话内的审查/开发继续，旧两个跟进保持 PAUSED。

下一步：收到 P0 后独立裁决和下发 P1a。自动跟进仅在维护者明确答复允许后创建；缺该授权不阻碍当前会话已授权工作，不能伪报为整个 G10 阻塞。

## C0.2：P0 兼容性裁决，报告仍待交付

当前目标：收敛 P0，先进入协议/请求/连接预算的 P1a，后审 UI。开发最新消息确认请求 UTF-8 预分配、嵌套 error 尾部、manager 健康检查吞异常并重连、已确认 SELECT 恢复均纳入设计；仍 active，尚未落盘 P0 报告，无产品实现或新测试通过声明。最新已观察 cursor 为 `431e0858-03dc-4a74-84cd-3c2876284d87:87`、turn `01a11df1-b111-7190-a06d-809e89457b93`。

root 已纠正计划中错误内容边界的歧义：新的预算/协议/期限拒绝及审计日志使用固定安全信息；正常、完整、在行长/总量预算内的顶层 server error 保留既有诊断消息和连接可继续语义。不得为本轮把所有 server error 改成固定摘要或普遍放宽旧精确消息断言。嵌套错误若未读完整响应可用固定类别拒绝并丢弃连接。此裁决及“先交可审 P0，不再扩展调查”已直接下发开发会话。

验证：本检查点为源代码与契约审查，无新增 Gradle 运行；C0/C0.1 的四项合成诊断仍为已复现待修缺陷。自动跟进明确授权问题仍待维护者答复，未创建新的调度。下一步读取实际 P0 报告、给出具体准入/修正、移交唯一 Gradle 执行权；必要时继续等待同一开发会话，不另建线程或代理。

## C1：P0 独立审查完成，准入 P1a

当前目标：实施协议/请求预算、读取期限与最小连接恢复修正；暂不进入 P1b UI 或 P2 全量冻结。开发 P0 turn 已完成，cursor `431e0858-03dc-4a74-84cd-3c2876284d87:90`。root 完整阅读 291 行报告并保存[原件快照与哈希](evidence/g10-redis-20261009-p0-review/raw-manifest.json)。main 为 `e805a770e0e23e31ec551371eb0e01299010f367`；开发仍 `66edd24f`，仅 P0 报告未跟踪，无产品/测试更改。受限 Git 工作区读取首次 exit128，独立提权只读重试 exit0；不是产品失败或验收证据。

审查裁决：接受集中不可变预算、迭代 RESP2 解码、分配前 UTF-8/帧预检、typed failure/delivery、共享读取期限、独占 lease 身份、最后确认 DB、manager 短状态锁及代际校验。接受报告对 RSS、DNS/阻塞写、native close 的明确局限。完整顶层 server error 消息兼容要求不变。

P1a 必须补足两个约束：

1. raw 会话上下文无法恢复时应持续失效，不能 discard 后下一调用又按旧 DB/旧 AUTH 静默重建。直接 SELECT 的完整 OK 仍可可靠追踪；无法解析目标却得到 OK，或 MULTI/EXEC 间接 SELECT、RESET、raw AUTH 等造成无法完整追踪的状态，不扩大为完整 CLI 状态机。允许最小保守恢复保护：这类状态一旦使恢复不可信，连接损坏后禁止自动重试及新显式调用静默恢复，明确要求新会话；测试至少证明 raw AUTH、事务中 SELECT、RESET 与非法 SELECT 成功回包均不会在错误上下文发送后继业务。若选择发送前限制，必须窄化、明确兼容影响并独立审查。官方 [SELECT](https://redis.io/docs/latest/commands/select/) 说明 DB 属于连接；[RESET](https://redis.io/docs/latest/commands/reset/) 会改变 DB、认证及其他会话状态，不能只跟踪构造 DB。
2. manager 清除映射不代表关闭成功。closeAll 要尝试全部 snapshot，保留/汇总关闭失败，不能吞掉 Error 或用空 map 宣称物理完成；关闭中迟到创建、配置变化和重新显式打开分别验证。修改仅限 Redis manager，不扩展全局 ConnectionManager/AppShell。

改动与验证：本检查点只有审查和原件归档，未运行 Gradle，无 G10 通过声明。P1a 执行权交给同一 6.1-sol 会话，其成为唯一 Gradle 执行者；root 只读代码和新原件。开发先完成 P1a 新定向证据并停写交审，root 接受后才准入 P1b。P2 的 clean 全量、buildSrc、jpackageImage 后置，不用 P0 或旧 XML 代替。

失败/未验：已复现的四项缺陷尚待修复；复杂 raw 上下文、预算、物理关闭、UI 均未取得新通过证据。自动跟进授权仍待答复，未创建新自动化，旧两个跟进继续 PAUSED。下一步正式下发 P1a 并跟踪实际实现，G10 完成交付后再做维护成本整理。

## C1.1：Codec 新定向通过，client/manager 初审返工

当前目标：继续 P1a，不提前准入 P1b。C1 本地提交 `967874aea125e2ba3e73ceaa9a7d81e6bbf2d05f`；开发已实际实现初版预算、迭代 Codec 和连接/manager 修正，仍在写、未冻结。root 未运行 Gradle。

新验证：开发证据目录 `aed5/朝花夕拾/docs/superpowers/verification/evidence/g10-redis-20261009-p1a-worker/` 的 `001-codec-first` 因脚本 argv 拼接失败，未编译产品，原失败保留。修正后 `002-codec-corrected-runner` 命令为离线、rerun-tasks 的两个 Codec 测试类；root 独立读取 command/exit/stdout/两个 XML 及 831 项前后输入，确认 exit0、15 tests、0 failures/errors/skips、输入一致，日志显示 8 个实际执行任务。XML SHA256：RespBudgetTest `49abfba64424aa7b110cd8f2186b391bdfd2f0f0a6ae8bbce0cc7b2880498051`；RespCodecTest `d4775d935d25492ac36bee01f2f5cb6004b619bbe7ebafd435f77c41092055a8`。它只证明当次 Codec 阶段，不能覆盖后来 client/manager 或 P1a 最终输入。

独立源码审查发现并已下发：

1. 所有重放/SELECT/上下文判断必须绑定实际编码帧，不能在编码后回读调用者可变参数来决定另一请求的策略；开发已采用从编码帧提取短参数。
2. 共享期限开始后，重试 connect 和每个 AUTH/SELECT/业务 write 前要校验剩余时间；当前仅 attempt 入口检查、connect 固定 5 秒可能使已耗尽期限后仍发送下一帧。DNS/阻塞 write 无硬中断的局限不变。
3. 新建 session 在首次 PING 通过前未被 manager 任何集合持有，closeAll 暂时无法关闭其阻塞 I/O。要求最小 pending ownership 和代际验证；阻塞 factory 仍只能迟到关闭，不能伪称可中断。
4. cached PING TRANSPORT 后的关闭 RuntimeException 被 suppress 后仍自动重建，丢弃原异常会隐藏关闭失败。要求报告关闭失败并停止该次替换，正常纯 transport 替换保留；Error 不吞。

这些为代码审查发现，尚无独立运行的缺陷复现或修复通过声明。下一步继续读取新 client/manager 定向及小堆证据，确认上述修正和物理资源结算后审查 P1a 冻结。自动跟进授权仍未收到，不重试创建。

## C2：P1a 独立审查通过，准入 P1b

当前目标：开始 Redis 展示、保留和候选提交预算。开发 turn `01a11e10-ebc5-73a0-b2f6-e516fd212fcc` 已停写完成，cursor `431e0858-03dc-4a74-84cd-3c2876284d87:123`。HEAD 仍 `66edd24f`，产品未提交；root main 当前 `967874ae`。root 已审六个产品文件、六个变更测试文件及旧错误消息断言，没有放宽原消息兼容要求；diff --check 无错误（工作区行尾提示单列）。

改动：C1.1 发现已修正并有定向断言。另补业务读取中非 Redis RuntimeException/Error 的 lease 清理，原异常对象原样抛出，不转换为预算失败。root 读到并核对合成 AssertionError 后 peer 关闭、后继新连接成功测试。最后一轮修改将新 socket 因已关闭而拒绝时的 close 移出状态锁，随后重跑最终定向。

独立验证：[root 验收回执](evidence/g10-redis-20261009-p1a-review/receipt.json)重新计算开发 manifest 全部 **284** 项长度/哈希，全部匹配；最终 `009-p1a-frozen-targeted` 的 **834** 项输入 before/after/当前工作源码一致。重新解析14份当前XML，75 total = **74 pass + 1 skip**、0 failure/error、exit0；唯一skip为未配置的真实 Redis 集成测试，不记通过。命令与原日志已读，未拿 buildSrc compile 当 buildSrc:test。root 未运行 Gradle，开发仍为唯一执行者。

最终 `008-final-smallheap-probes` 的6项 argv/exit/stdout/stderr 已逐项复核，每项 `-Xmx32m -Xss256k`、exit0且实际退出；巨大array/bulk各读13字节、深度读132字节、长行读65,538字节、节点总量读360,085字节、请求在UTF-8/frame副本前NOT_SENT拒绝。旧OOM/SOE诊断与这批新受控拒绝是不同证据。P0、P1a报告快照及开发manifest/binding在root归档，不重写开发冻结目录。

准入裁决：P1a阶段接受，可直接实施P1b；不代表G10完成或允许提前合main。集中预算/分配前准入、共享期限、请求帧绑定策略、已确认DB及保守raw模式恢复、pending ownership和异常清理满足本阶段边界。raw AUTH/事务/RESET等模式发送后恢复不可信，即使完整server error也保守保持该状态，连接损坏后要求新会话；这是已记录兼容限制，不新增完整CLI状态机。工厂返回session之前、DNS/阻塞写/native close/GC的局限继续保留。

P1b按已审P0依次完成纯格式化/retention/树与页面候选、console接入、browser接入和真实可执行的FX mock回归。格式化/页面拒绝必须恢复控件并保留旧结果/游标/来源，不能把预览变成可保存值；原始行身份不能取自截断显示。无效UTF-8 key整页明确拒绝已接受，不新增binary key编辑器。UI只允许当前模式真正完整且底层读取完整的数据保存；不能用切模式绕过不完整读取，完整raw可恢复出完整合法模式的情形应保留正常编辑能力。单飞与一个最新待处理意图，不扩大通用queue/AppShell。

失败/未验：首轮脚本启动失败保留；真实Redis、原生桌面、最大合法多会话RSS、P1b、P2全量/buildSrc/image及P3集成/CI仍未完成。自动跟进授权未答复，未创建新自动化。下一步下发P1b，完成新定向后停写交审；G10最终交付后才开始维护成本整理。

## C3：P1b 实施中，纯展示首轮证据与独立修正

当前目标：继续有界展示与候选提交，开发 turn `01a11e2d-78eb-77a3-a6f8-fc68d46ca39b` active，已观察cursor `431e0858-03dc-4a74-84cd-3c2876284d87:132`。C2 已本地提交main `c8e43772db6f2a7e30fb567af07b6215dc9754db`，产品仍在aed5未提交。开发先落地Redis局部 display limits、retention、bounded projections、key snapshot及树构造；这些是同一P1b的局部职责拆分，不引入通用框架或维护成本重构。

root 复读现有两pane和通用queue，补充两个来源/所有权验收：旧TreeCell菜单、详情按钮、modal返回必须绑定其产生时的session/source/generation，不能在切DB后使用this.session发送旧key；queue关闭会丢弃FX回调，新open/ping中的会话不能仅靠成功回调接管/释放，pane.close必须能关闭factory已返回的opening session，返回之前仅承诺迟到自关。两项均已正式下发同一开发会话。

首版helper审查发现集合页先形成全部显示rows、最后才累计pageChars，可能先分配1000行×2格×4Ki再按256Ki拒绝；已要求改为逐行累计，最多一个有界被拒行，后续元素不得访问。malformed UTF-8的固定说明也必须计入editorChars。新测试已加入可观察尾部访问的guarded List和tiny editor，但尚待修正后的新执行，不能用首轮结果覆盖。

验证：root 重新解析 `g10-redis-20261009-p1b-worker/001-pure-first` 当前XML，16 total/16 pass、0 failure/error/skip、exit0，输入before/after一致。该证据属于修改前首轮纯helper，不是FX、整页早停修正或P1b最终验收。root未运行Gradle。P1a原件冻结不动。

失败/未验：P1b仍在实现，两个pane、跨DB旧操作、opening-session关闭、FX控件恢复和最终冻结均未完成；没有新增真服务/桌面/发布验收声明。自动跟进授权未答复，旧跟进保持暂停。下一步继续审新代码和原始结果，P1b停写通过后才准入P2。

### C3.1：浏览器待处理刷新被旧树选择失效

root 继续只读审查两个 pane 接入。控制台已将格式化放在工作线程，输入准入不先构造新全文，输出与历史双维淘汰，回调 finally 恢复控件；仍待实际 FX 测试。浏览器新实现将候选 UI 安装完成后才发布 session/snapshot，安装失败恢复旧树、详情和分页控件；opening session 在 PING 前可被 pane.close 找到。以上为源码观察，不代替执行证据。

发现并下发具体修正：已有同 DB 树且读取未结束时，requestRefresh 挂起 Refresh(gen2)，随后点击旧树另一键会在 loadKey 中先递增 generation 为 gen3，但 pendingRefresh=true 又阻止替换 pending；finish 调用 startRefresh(gen2) 后立即因 stale 返回，刷新和键选择均丢失。tree 未被 updateControls 禁用，因此用户可触发。要求在改变 generation 前落实已挂起刷新优先，或正确合并最新意图；补实际请求、最终来源和 busy 恢复的 FX mock 断言，保持单飞及至多一个待处理意图。

当前开发仍 active，已观察 cursor `431e0858-03dc-4a74-84cd-3c2876284d87:135`。root 没有运行 Gradle、修改开发源码或准入 P2。main 范围内仅本协调记录有未提交变化；没有新增自动化。

### C3.2：纯阶段新增通过，FX 首轮失败保留

当前目标：P1b 实际 FX mock 交互验证，仍未冻结。C3/C3.1 已本地提交 `8888f1f099942cb567b15c4beb161efc17346583`。root 独立读取 `002-pane-compile-pure` 的 argv、exit、stdout/stderr 和五份 XML：839 项输入 before/after 相同，20 total/20 pass、0 failure/error/skip、exit0；包含 early-stop/tiny-notice 修正。运行明确 headless=true，此阶段不声称执行了 FX 控件交互。

新测试源码审查下发两项纠正：zset 行修改测试要经过实际 UI 使用的共享 action，不能新增仅测试可达的替代分支；提取 updateRow 后 list 修改一律刷新 cursor0 会退回第一页，应保留当前页，并用实际截断的 index 显示证明发送的是原始 long 身份。pendingRefresh 在 loadKey 改变 generation 前已加 guard，尚待执行证据。

`003-fx-first` 明确 headless=false，已实际运行但 exit1，原日志保留 29 tests / 8 failures。root 已读原失败：八项在测试查控件时找不到 SplitPane/ScrollPane 下的 redis-keys/database/console-output，不能算产品行为通过，也不能改为跳过规避。需修正测试控件遍历后重新执行，再判定交互行为；一次关闭阶段通过也不代表整个 FX 阶段完成。尚未运行 P2，全量/buildSrc/image/真服务/原生桌面局限仍在。

### C3.3：FX 行为修正的新通过，尚待最终冻结

`004` 因 Gradle wrapper cache lock 权限失败，未编译产品；原件保留，后续受控提权使用新编号运行。`005` exit1、30 tests/3 failures，已实际走到交互断言。产品状态文本把长错误放前面，截断后丢失来源和“未完整加载”，开发已将关键状态前置；模式切换从依赖 onAction 改为监听 valueProperty，保证没有 skin 时也一致更新编辑权限。list 修改保留页偏移、zset 分数编辑使用实际共享路径的新断言已加入。

root 独立读取 `006-fx-mode-and-source-status` 的 argv 对应日志/exit/六份 XML，841 项输入前后一致，headless=false、exit0，30 total/30 pass、0 failure/error/skip，其中 RedisPaneBudgetTest 10 项实际执行。它是当次输入的通过，仍非最终冻结。需补旧 action 调用后同 FX callback 中不启动请求的断言，防止异步写未执行就被 fixture.close 取消造成假通过；另补 collection 合法旧页→超限最终页→保留旧表/raw/next→同 cursor 重试成功的实际 FX 回归，不能只靠纯 helper 拒绝断言。

当前目标仍 P1b。root 未运行 Gradle，开发继续唯一执行；完成上述后停写交审，不扩大新功能或维护重构，不提前执行 P2。尚无 G10 合 main、推送或发布验收结论。

## C4：P1b 独立验收通过，准入 P2 工程验证

当前目标：冻结 G10 产品范围，执行完整定向、clean 全量、强制 buildSrc:test、jpackageImage 和隔离镜像/linked 探针。开发 P1b turn `01a11e2d-78eb-77a3-a6f8-fc68d46ca39b` 已完成停写，cursor `431e0858-03dc-4a74-84cd-3c2876284d87:157`。main `8888f1f0`，开发 HEAD `66edd24f`，G10 产品仍未暂存/提交。根会话未运行 Gradle。

独立审查已完成八个 P1b 产品文件及三份新测试的来源、页提交、输入与输出预算、二进制身份和关闭路径。C3 各项发现已修正；最终新增 collection 回归证明旧 next17 在超限 cursor0 及值页安装异常后都不推进，随后同 cursor 重试成功；旧 DEL/旧 HSET action 在 FX callback 内不改变 activeRequest、busy 保持 false，避免未执行的异步写被收尾取消所造成的假通过。

[独立验收回执与核验脚本](evidence/g10-redis-20261009-p1b-review/receipt.json)重新计算 P1a 全部284项和 P1b 全部212项原件长度/哈希，全部一致；P1a 六个产品文件仍与该阶段最终输入一致。P1b manifest SHA256 `1a4e9c6580159c4e00b1ece2f7b09e8907e7f41aacd7c84659bf1b32b74c23f9`。`007-p1b-final-targeted` 841项输入 before/after/当前一致，15份XML重新解析为88 total = **87 pass + 1 live Redis skip**，0 failure/error、exit0，11项FX mock实际执行且无skip；headless=false、8个Gradle任务实际执行。argv、原日志、exit和失败历史均已独立阅读。根回执不重复复制整个开发证据目录，只保留报告/manifest/binding原件与核验结果。

准入裁决：P1b 阶段接受；允许同一开发会话执行 P2。保持独占 UUID home/temp/build、清空环境、mock/127.0.0.1 helper、现有本地工具及离线缓存；开发仍独占 Gradle。全量不得以强制 headless 大量跳过 FX 换绿；工具包实际失败与真实服务前置跳过分别记录。每阶段保存当前命令/退出/输入/原日志/XML/产物哈希，首次失败保留；新代码修复须说明影响并取得对应新结果。最终停写交审，尚不授权开发提交、合 main、推送或操作 tag。

失败/未验：P1b 的首轮夹具失败、缓存锁权限失败、状态/模式行为失败均保留；P2全量/buildSrc/image、P3独立集成/精确SHA CI尚未完成。实际FX控件/mock不是完整原生桌面、真Redis或发布验收；最大合法多会话RSS、DNS/阻塞写/native close/GC局限保留。新自动跟进授权仍未答复、未创建；旧跟进保持PAUSED。下一步下发P2并独立复核新原件，通过后才进入P3；G10交付后再整理维护成本。

### C4.1：P2 已启动，独立回执已完整提交

P2 turn `01a11e52-eddb-7191-8b5c-bd06e4bc8346` active，已观察 cursor `431e0858-03dc-4a74-84cd-3c2876284d87:159`。开发仍为唯一 Gradle 执行者。root 读取新隔离脚本与仓库原 build.gradle，对应生成版本/图标/资源目录和 jpackage icon 均重定向独占 UUID build；版本、母版和 generator 的原逻辑保留。`001` 错把 jpackage DSL 方法读作属性，配置失败；`002` init script 无法直接解析 buildSrc 类，生成图标前失败。两轮没有产品测试通过，脚本快照及失败日志保留；`003` 改为读取 project buildscript classloader 后新执行，尚待最终结果核验。

C4 已本地提交 `3ebd45110b633e86629379b9033b8dc697ccbda7`。root 的常规 Git add 多次 exit0 但七个新回执文件未进入索引（原因未确定，未把成功退出当提交证明）；没有生成缺件提交。随后用逐项白名单、拒绝 `.testagent`/越界/多级文件名的索引接口写入同一批准文件集合，逐字节核对 Git blob 与 frozen manifest 后才提交。没有目录扫描、禁用路径读取或外部操作；此次 Git 索引异常不是产品测试失败。该提交后 main 范围内干净、仅文档与原件，G10 产品尚未提交。

### C4.2：完整定向通过，镜像审计与冻结范围修正

root 独立解析 `003-complete-targeted` 的56份XML、前后863项输入、命令/exit/raw日志：292 total =291 pass+1真实Redis skip，0 failure/error、exit0，native FX未强制headless。全新目录的cleanTest显示UP-TO-DATE仅表示无旧结果可清理；test实际执行，8个任务执行。`004-clean-full` 已启动，当前未结算，不能记为通过。

镜像脚本初审发现输入中408个test源码路径全部采用Windows反斜杠，原`^test/`匹配使测试类泄漏名单为空；已要求先规范分隔符并断言覆盖全部408项。开发已修正。其image相对路径替换经root检查实际为单反斜杠转换，不是另一个已确认缺陷。linked helper源码已读：加载新image中的命名产品模块，脚本socket显式127.0.0.1，检查确认DB恢复、普通错误消息/同socket继续、资源拒绝、展示和raw身份，并join/EOF结算；尚未执行成功，不先记绿。

冻结脚本计划复制全部863输入及多轮全部class；root已在执行前裁决缩为最小原件集合，避免再复制未修改驱动jar和整个已版本化源/测试树。完整输入、全部编译产物/image仍保留长度/哈希清单；仅复制本G10新增/修改源/测试、实际launcher、Redis/两pane与新测试相关class、必要buildSrc工具/probe、产品jar/launcher。完整image保留在具名UUID路径并绑定manifest。此为尚未生成的P2原件选择，不删历史、不修改P1a/P1b、不减少测试或断言；维护成本正式盘点仍后置。下一步继续独立核验全量、buildSrc和image实际结果。
