# DataCube 当前状态

更新：2026-10-09（Asia/Shanghai）。此页是后续会话的首个入口；历史交接中的“当前/最新”均按各自日期理解。

## 已完成

- **G10 Redis 资源预算已交付。** 产品 `edc41088`，集成 `58e27c34`，交付 main `567a5293528a20c2d4eae6daafabe2242064b676`。[Verify 37874713840](https://github.com/Clownking99/data-cube/actions/runs/37874713840) 四任务通过；实际日志、远端 SHA 已核对。[交付回执副本](../superpowers/verification/evidence/maintenance-cost-20261009/g10-delivery-result.json)与[完整证据入口](../superpowers/verification/evidence/g10-main-20261009/delivery-intent.json)。
- main 新隔离定向 **291 通过 / 1 live 跳过**，clean 全量 **4711 通过 / 3 live 跳过**，buildSrc **8 通过**，jpackageImage、183文件镜像审计和外置 linked 探针通过。11项 Redis FX 实际执行；跳过不算通过。[协调账本](../superpowers/verification/2026-10-09-g10-redis-coordination.md)保留首失败、修正和局限。
- **维护成本首轮整理已落地。** 新增可复现的只读盘点、当前入口、验收索引和未来验证工具的最小设计；产品、测试及构建逻辑未改。[成本报告与优先级](../maintenance/2026-10-09-maintenance-cost-baseline.md)。本次整理的最终推送/CI以[交付入口](../superpowers/verification/evidence/maintenance-cost-20261009/delivery-intent.json)指向的实际回执为准。

## 当前进行中

维护者继续授权后启动 **G11 验证 runner 共用内核**，范围为四个小内核与本轮新验证入口，原6.1-sol会话开发、root审查/集成。见[实施计划](../superpowers/plans/2026-10-09-g11-verification-core.md)、[协调账本](../superpowers/verification/2026-10-09-g11-verification-coordination.md)。当前P0，尚无新实现/测试通过声明。

## 后续候选

G11按[最小设计](../maintenance/verification-runner-design.md)推进，只迁移本轮新验证。交付后再考虑整理测试专用受控 JVM 夹具的超时/退出结算。Redis 请求状态和 Shell 关闭入场拆分是后续独立候选，范围与必需回归见成本报告；本轮未实施架构改造。

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
