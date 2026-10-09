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
