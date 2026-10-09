# DataCube 当前状态

更新：2026-10-09（Asia/Shanghai）。此页是后续会话的首个入口；历史交接中的“当前/最新”均按各自日期理解。

## 已完成

- **G10 Redis 资源预算已交付。** 产品 `edc41088`，集成 `58e27c34`，交付 main `567a5293528a20c2d4eae6daafabe2242064b676`。[Verify 37874713840](https://github.com/Clownking99/data-cube/actions/runs/37874713840) 四任务通过；实际日志、远端 SHA 已核对。[交付回执副本](../superpowers/verification/evidence/maintenance-cost-20261009/g10-delivery-result.json)与[完整证据入口](../superpowers/verification/evidence/g10-main-20261009/delivery-intent.json)。
- main 新隔离定向 **291 通过 / 1 live 跳过**，clean 全量 **4711 通过 / 3 live 跳过**，buildSrc **8 通过**，jpackageImage、183文件镜像审计和外置 linked 探针通过。11项 Redis FX 实际执行；跳过不算通过。[协调账本](../superpowers/verification/2026-10-09-g10-redis-coordination.md)保留首失败、修正和局限。
- **维护成本首轮整理已落地。** 新增可复现的只读盘点、当前入口、验收索引和未来验证工具的最小设计；产品、测试及构建逻辑未改。[成本报告与优先级](../maintenance/2026-10-09-maintenance-cost-baseline.md)。本次整理的最终推送/CI以[交付入口](../superpowers/verification/evidence/maintenance-cost-20261009/delivery-intent.json)指向的实际回执为准。

## 本轮交付

**G11 尚未完成交付，当前在修正独立复验发现的 runner 退出观察缺口。** 首次本地P3通过并封存1900份原件；cfd9d4ed的Verify37893270726随后在Ubuntu暴露旧PgDump测试PID发布竞态，其余三个任务成功。三个测试文件的最小协议修正52374f71及证据ca055cc5已合入main e368a1b1。

修正后root完整新序列实际退出0，原始XML为定向291通过/1跳过、全量4721通过/3跳过（含PID33例）、buildSrc8通过，image/linked已自然结束；但独立审核发现java-version回执同时声称passed与rootExited=false，拒绝本轮P3。原因已实测为短进程循环尾漏退出事件、JSON日期类型造成身份比较失配、最终成功门禁不完整。1904份原件以REJECTED封存，未推送此轮main、未重试CI到绿。

[当前复验与拒绝结论](../superpowers/verification/2026-10-09-g11-ci-correction-main.md)、[协调账本C14](../superpowers/verification/2026-10-09-g11-verification-coordination.md#c14独立门禁拒绝矛盾通过runner必要修正)。同一6.1-sol开发会话正在codex/g11-root-exit-observation-20261009实施最小修正和确定性控制；源码复审后才准入新冻结完整验证、main再复验及精确SHA CI。旧交付定位不能作为新提交成功证明，未自动启动下一轮。

## 后续候选

G11按[最小设计](../maintenance/verification-runner-design.md)落地，只迁移本轮新验证。后续再考虑整理测试专用受控 JVM 夹具的超时/退出结算。Redis 请求状态和 Shell 关闭入场拆分是后续独立候选，范围与必需回归见成本报告；本轮未实施架构改造。

## 待验与边界

- G10 的完整原生桌面、真实 Redis/数据库、最大合法多会话 RSS、DNS/阻塞写/native close/GC 等仍未验或不作硬保证。CI 的临时 Redis 不能代替业务环境验收。
- G8 及后续修复保留的安装/升级/回退、生产签名、其余外部驱动/桌面/文件系统验收仍未完成；本页不宣称完整发布验收。
- `.testagent` 禁读禁改禁枚举；使用 mock、合成 profile 与独占临时目录。原有连接、凭据、SQL 历史与业务文件不读取。当前不访问真实服务、不 fetch/tag/PR/发布/安装更新/外部联系；`v3.2.9` 不动。
- 旧 `datacube`、`datacube-g9` 跟进保持 PAUSED。新的持续自动跟进未创建，仍有待答复的调度授权；不影响当前会话已授权的执行。

## 常用入口

| 用途 | 入口 |
| --- | --- |
| 当前验证方法与证据索引 | [验证说明](../maintenance/verification-guide.md) |
| G10 设计及实际分阶段裁决 | [计划](../superpowers/plans/2026-10-09-g10-redis-resource-budget.md)、[协调记录](../superpowers/verification/2026-10-09-g10-redis-coordination.md) |
| 维护成本范围与检查点 | [计划](../superpowers/plans/2026-10-09-maintenance-cost-review.md) |
| G1–G9 和早期修复历史 | [原路线图](../superpowers/plans/2026-09-23-product-maturity-roadmap.md)、[历史交接](2026-09-23-product-maturity-goal-handoff.md) |

维护约定：后续仅更新本页的当前结论和新证据链接；详细过程写入各阶段账本，不在历史交接顶部继续累积多个“最新”。旧冻结目录、失败日志与 manifest 保持原字节。
