# DataCube 当前状态

更新：2026-10-10（Asia/Shanghai）。此页是后续会话的首个入口；历史交接中的“当前/最新”均按各自日期理解。

## 当前发布修复（本地通过，远端发布待回执）

v3.2.11 的发布构建曾在合成更新 helper 的总等待期限处失败，原 tag 保留。仅三个测试文件的分阶段等待、失败诊断和实际退出清理修复已合入受验 main `bb84b72a47f5ed21ce9833b609a301e29b4a9ebd`，生产 helper 和原更新状态/镜像断言不变。

root 在全新隔离环境的首次正式序列全部通过：更新定向 92/0 跳过、clean 全量 4740/3 live 跳过、buildSrc 8/0 跳过、jpackageImage 和正式 linked。183 文件镜像身份已复核。开发 P2 的 linked 首失败仍保留，诊断未复现不能证明旧收尾问题已修复。真实数据库与真实安装更新未执行。

[本轮验收报告](../superpowers/verification/2026-10-10-release-helper-main.md)及[检查点](../superpowers/plans/2026-10-10-release-helper-timeout-fix.md)记录原件和局限。最终 main 推送、精确 SHA CI 与 v3.2.12 实际程序包发布结果以报告的交付入口为准；没有 `passed=true` 回执，不宣称发布完成。
## 当前缺陷修复

维护者截图报告的Redis非UTF-8键导致整页加载失败已修复，并合入受验main **af3fe27369973c2d0254e712968f5984b8dc2b86**（源码c33b9ef4、证据20778239）。原始字节身份贯穿键操作，二进制/空键显示明确，分页预算与旧Binding保护保留。root在main新隔离环境实际定向303通过/1 live跳过、clean全量4733通过/3 live跳过、buildSrc 8通过/0跳过、jpackageImage及linked通过；14项Redis原生测试均执行。1200原件已封存。[main验收报告](../superpowers/verification/2026-10-10-redis-binary-key-main.md)和[分阶段计划](../superpowers/plans/2026-10-10-redis-binary-key-fix.md)保留首失败与修正。

首次3af30f12推送的Windows CI因证据路径过长在checkout失败，已保留原件。补正仅对两处Windows checkout临时设置Git长路径支持，合成正负例与精确diff经独立审核；876其余工程输入和12工具不变，两个workflow另列哈希迁移。最终main推送、精确SHA Verify四任务的结论以[补正后交付入口](../superpowers/verification/evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/delivery-intent.json)定位的`delivery-result.json`为准，只有`passed=true`才表示远端交付完成。v3.2.10仍指向a39ffc47，本次截图版本未确认，真实连接未访问；未自动打tag或发布下一版。

## 已完成

- **G10 Redis 资源预算已交付。** 产品 `edc41088`，集成 `58e27c34`，交付 main `567a5293528a20c2d4eae6daafabe2242064b676`。[Verify 37874713840](https://github.com/Clownking99/data-cube/actions/runs/37874713840) 四任务通过；实际日志、远端 SHA 已核对。[交付回执副本](../superpowers/verification/evidence/maintenance-cost-20261009/g10-delivery-result.json)与[完整证据入口](../superpowers/verification/evidence/g10-main-20261009/delivery-intent.json)。
- main 新隔离定向 **291 通过 / 1 live 跳过**，clean 全量 **4711 通过 / 3 live 跳过**，buildSrc **8 通过**，jpackageImage、183文件镜像审计和外置 linked 探针通过。11项 Redis FX 实际执行；跳过不算通过。[协调账本](../superpowers/verification/2026-10-09-g10-redis-coordination.md)保留首失败、修正和局限。
- **维护成本首轮整理已落地。** 新增可复现的只读盘点、当前入口、验收索引和未来验证工具的最小设计；产品、测试及构建逻辑未改。[成本报告与优先级](../maintenance/2026-10-09-maintenance-cost-baseline.md)。本次整理的最终推送/CI以[交付入口](../superpowers/verification/evidence/maintenance-cost-20261009/delivery-intent.json)指向的实际回执为准。

## 本轮交付

**G11 本地验收完成；远端交付结论以本轮实际回执为准。** 四文件退出观察修正a59b3f15和证据258a03db已合入受验main **2c8fc13577337a9b5247351e0634e76ff3841999**，3324文件在Git、worker和main间原字节一致。root随后在全新隔离目录完整执行98控制和五个工程阶段，并独立审查原始命令、退出、XML、镜像和进程结算，全部接受。

新P3定向 **291通过/1 live跳过**、clean全量 **4721通过/3 live跳过**、强制buildSrc **8通过/0跳过**；PID33项、Redis原生11项均实际通过，jpackageImage的183文件与外置linked探针通过。2501份原件已封存，跳过不算通过。[最新main验收报告](../superpowers/verification/2026-10-09-g11-root-exit-main.md)、[协调账本C19](../superpowers/verification/2026-10-09-g11-verification-coordination.md)。

最终文档/证据提交沿用同一875工程输入和12工具。只推main并核对相同SHA的Verify四任务；最终commit、远端main、CI链接、原始日志和passed结论由[本轮交付入口](../superpowers/verification/evidence/g11-p3-2ba76738625d-package/delivery-intent.json)定位到独占build目录中的delivery-result.json及manifest。没有该回执或passed不为true时，不得宣称远端交付完成。首次CI的PID竞态失败、1904份矛盾退出REJECTED原件、开发长路径和用例误计失败均保留，不冒充通过。未自动启动下一轮。

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
