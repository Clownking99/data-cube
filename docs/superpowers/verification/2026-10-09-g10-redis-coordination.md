# G10 Redis 资源预算：协调与独立验收

## C0：新授权、现状与首个诊断

当前目标：独立推进 [G10](../plans/2026-10-09-g10-redis-resource-budget.md)，完成后再按 [维护成本计划](../plans/2026-10-09-maintenance-cost-review.md)整理；不混入 G9、G8 外部验收或其他功能。当前会话继续负责规划/审查/返工/集成，沿用既有 GPT-6.1-sol 开发会话，使用独立 G10 分支。

main 为 b6622c95d5efc0b240dc7ae4b788f44542c87b5a，范围内干净。G9 最终四项 CI 通过和 PAUSED 状态已核对，并将独占 build 目录中的关键最终回执与原始逐 job 日志按旧 manifest 哈希复制到[前轮结案归档](evidence/g10-redis-20261009-baseline/prior-g9-delivery/subset-manifest.json)，避免后续 clean 丢掉实际结案依据。未重写 G9 冻结证据或把历史成功记为 G10 成功。

改动：仅本轮计划、当前合成诊断与协调记录，没有产品/测试源码变动。已直接读取 RespCodec、RespClient、RedisSession、RedisConsoleSupport、KeyTreeBuilder、两个 Redis pane 和现有测试，确认解码大分配/递归、控制台和键树累计、格式化放大仍缺少完整预算。SCAN COUNT 为提示的协议语义已核对官方文档。

验证：当前源码单独编译到独占 UUID 目录，清空环境，只传必要 OS/JDK/home/temp；每个诊断使用 -Xmx32m/-Xss256k 独立 JVM，零网络。新[baseline.json](evidence/g10-redis-20261009-baseline/baseline.json)记录源哈希、编译和三个命令/exit/raw：13 字节巨大 array 头触发 OutOfMemoryError；6000 层数组触发 StackOverflowError；100,000 字节 simple line 完整接受。诊断进程按预期观察旧缺陷而 exit0，不代表 G10 验收通过，也不把捕获 OOM 当产品方案。

失败/未验：上述三个现存问题待修；协议预算、socket 串线/重试、UI 保留、真实 Redis/原生桌面均未取得 G10 新通过证据。源检索最初误用 RedisClient.java/ui 目录，已改为实际 RespClient.java/fx 文件；未因此修改产品。G9 的 Windows 初始化偶发超时继续保留根因未知。

下一步：将计划与基线提交本地，通知既有开发会话从最新本地 main 新建 codex/g10-redis-resource-budget-20261009，停在 P0 设计审查；root 通过后交给开发唯一 Gradle 执行权，按 P1a/P1b 逐阶段验收。维护成本整理等待 G10 交付，不删历史证据。v3.2.9 与两个旧 PAUSED 跟进不改；本轮尚未推送或访问任何真实服务。
