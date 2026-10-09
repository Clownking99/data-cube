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
