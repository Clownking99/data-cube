# 验证入口与证据索引

先读[当前状态](../handoffs/CURRENT.md)，再选本轮计划和受验提交。历史通过只作基线，不能填入新一轮结果。

## 现有执行方法

G11当前实现为[scripts/verification](../../scripts/verification/run-stage.ps1)：四个小内核、阶段政策、镜像审计与有界外层owner。首次CI的PID发布竞态及后续P3的退出观察矛盾均保留失败原件；修正已合入main，最新裁决见[退出观察修正后main报告](../superpowers/verification/2026-10-09-g11-root-exit-main.md)。[首次P3](../superpowers/verification/2026-10-09-g11-main-verification.md)及[被拒绝的P3](../superpowers/verification/2026-10-09-g11-ci-correction-main.md)只作各自版本的历史证据。旧[G10 main runner](../superpowers/verification/evidence/g10-main-20261009/Run-P3.ps1)与[镜像审计](../superpowers/verification/evidence/g10-main-20261009/Run-Image-Audit.ps1)保留为历史原件，不再作为新轮次的整份复制模板。

每轮把实际执行的12文件工具闭包与阶段spec冻结到新的具名证据目录，记录原字节/SHA；工程输入是显式允许名单，绑定当次提交，核对准入工程根内实际输入，不能仅信Git已跟踪集合。每阶段新UUID home/temp/build，保持唯一Gradle执行者；实现或输入修正后新目录重验，旧原件不覆盖。仅审计入口误计、而本轮受验命令确已成功时，可以严格校验原件和同版本输入后只读重审，必须分别保留原入口失败和续接来源，不能改写原退出码或套用其他轮次通过。开发冻结后由root独立审核，再接管main的新环境完整复验。

G11入口的最外层是`python -I -S -B scripts/verification/run-owned.py --spec <绝对路径>`。spec必须明确repo、tools、out、stageEvidence、mode、inputSpec、jdk、cache、pwsh、python、runtimeParent、imageSourceScope、deadlineSeconds、fixture、processDeadlineMs、settleMs、streamCap、outerFixture十八字段。本轮五阶段依次为targeted/full/buildsrc/image/linked；linked的imageSourceScope必须指向前一成功image的scope.json。普通工程阶段fixture与outerFixture为null。完整本机示例来自G11新包的五个`*-spec.json`，只能在新的运行目录按当前路径生成，不能原样重跑冻结spec。

当前准入名称保留G11的`g11-p2-`/`g11-p3-`，另经本次独立审查加入`redis-binary-p[23]-<32位小写UUID>[-suffix]`，仅允许evidence直属运行根、owned UUID子目录及精确工具/镜像来源层级；70条新名称边界、98共享控制和实际同根冲突拒绝证据见[Redis二进制键修复计划](../superpowers/plans/2026-10-10-redis-binary-key-fix.md)。工具闭包仍为精确12文件，内部G11运行时命名保留；未来轮次仍需显式审查命名范围。阶段任务、过滤器、期限和精确live skip由[stage-policy.json](../../scripts/verification/stage-policy.json)决定。动态测试类型覆盖从实际受验源码推导；历史408和本次410都是实测数量，不是未来硬编码名单。

XML只读本轮归档，suite与case四项计数分别比对；零文件/零case、损坏、重复suite、矛盾或超预算失败。合法同名参数case保留文件/suite/ordinal身份，不去重。单XML16MiB、合计128MiB、最多2000suite/100000case。内层stdout/stderr各32MiB、host各1MiB，外层每流1MiB；截断和不完整分别记录，不能作为成功日志。

| 阶段 | 核对重点 |
| --- | --- |
| 定向 | 过滤器实际命中、当次重新编译/执行；当前 XML 的 testcase 与 suite 数一致 |
| clean 全量 | 同一受验产品；明确 live/headless 前置跳过；新空目录的 clean UP-TO-DATE 不等于 test 缓存通过 |
| buildSrc | 强制 `:buildSrc:test` 的实际 XML/退出，不能用编译替代 |
| jpackageImage | 实际任务退出、完整镜像长度/哈希、正式配置及测试类名单隔离 |
| linked 探针 | 使用刚生成的运行时/命名产品模块，外置探针；记录子进程退出和自有资源结算 |
| 精确 SHA CI | 提交/push 后只认相同 head_sha 的实际任务与日志；远端 main 同 SHA；不把测试成功推成完整发布验收 |

取消 Future、空 ownership map、调用 destroyForcibly 均不能单独证明物理退出。只检查自有 PID/UUID 范围；真实外部服务另行授权。`.testagent` 始终禁读禁改禁枚举，Git与搜索带排除，文件集合尽量用精确白名单。

G11 Windows private Job在gate前归属，查询成员与等待实际handle后才判结算。根0而残留child需要终止也会失败；root7先于后续管道问题时保留7，预算先发生则保留预算主因。归属失败仅覆盖已捕获直接root，不承诺WMI/service等委托进程，也不承诺同步OS调用永久阻塞时的物理硬期限。故障注入的未结算分支不是实际不可杀进程。Python采用隔离模式、PowerShell不加载profile；offline Gradle仍使用明确指定的既有依赖cache。

成功回执必须有带PID、精确UTC字符串、实际退出码和首次单调tick的原始退出事件；parent用`-DateKind String`解析并交叉校验identity、host持有handle的观察与已捕获handle。短命进程未被parent轮询捕获时明确标明host持有handle事件来源，不能只凭汇总或PID判成功。缺失/损坏/矛盾事件一律拒绝成功，先发生的非零/取消/额度失败仍保留。当前完整控制为21 Python、21原进程、18退出观察、31政策/角色/镜像、7外层，共98项；实测运行时为Windows、PowerShell7.6.5和Python3.12.14。

## 证据索引

| 结论 | 审核入口 | 原件索引 |
| --- | --- | --- |
| Redis二进制键修复 main | [main验收报告](../superpowers/verification/2026-10-10-redis-binary-key-main.md) | [1200原件manifest](../superpowers/verification/evidence/redis-binary-p3-703d50245ffb4b799a3616f70ce0ba39-frozen/manifest.json)、[Windows checkout补正后精确SHA交付入口](../superpowers/verification/evidence/redis-binary-p3-daea00f81fc04ab9a7ccd87e5842bc9e-ci/delivery-intent.json) |
| G10 协议准入与恢复 | [P1a root 回执](../superpowers/verification/evidence/g10-redis-20261009-p1a-review/receipt.json) | [worker manifest](../superpowers/verification/evidence/g10-redis-20261009-p1a-worker/raw-manifest.json) |
| G10 展示、保留与生命周期 | [P1b root 回执](../superpowers/verification/evidence/g10-redis-20261009-p1b-review/receipt.json) | [worker manifest](../superpowers/verification/evidence/g10-redis-20261009-p1b-worker/raw-manifest.json) |
| G10 开发侧工程验证 | [P2 root 回执](../superpowers/verification/evidence/g10-redis-20261009-p2-review/receipt.json) | [worker manifest](../superpowers/verification/evidence/g10-redis-20261009-p2-worker/raw-manifest.json) |
| G10 main 独立复验 | [P3 回执](../superpowers/verification/evidence/g10-main-20261009/receipt.json) | [main manifest](../superpowers/verification/evidence/g10-main-20261009/raw-manifest.json) |
| G10 main 推送与 CI | [实际交付结果](../superpowers/verification/evidence/maintenance-cost-20261009/g10-delivery-result.json) | [完整原始响应/日志位置](../superpowers/verification/evidence/g10-main-20261009/delivery-intent.json) |
| 维护成本盘点 | [报告](2026-10-09-maintenance-cost-baseline.md) | [最终采集器基线](../superpowers/verification/evidence/maintenance-cost-20261009/baseline-final-collector.json)、[耗时](../superpowers/verification/evidence/maintenance-cost-20261009/timings.json) |
| G11 P1契约与试点 | [root审核](../superpowers/verification/evidence/g11-p1-root-preliminary/manifest-review.json) | [worker manifest](../superpowers/verification/evidence/g11-p1-frozen-20261009/manifest.json) |
| G11 P2冻结工程与镜像 | [root审核](../superpowers/verification/evidence/g11-p2-root-review/completion-v2-review.json) | [worker manifest](../superpowers/verification/evidence/g11-p2-frozen-20261009/manifest.json)、[五阶段定位](../superpowers/verification/evidence/g11-p2-package-v2/progress.json) |
| G11 main独立P3 | [main验收报告](../superpowers/verification/2026-10-09-g11-main-verification.md) | [1900文件manifest](../superpowers/verification/evidence/g11-p3-813154b3fa4c-frozen/manifest.json)、[独立审核](../superpowers/verification/evidence/g11-p3-813154b3fa4c-root-review/completion-review.json) |
| G11 首次main推送（CI失败） | [交付定位与判定](../superpowers/verification/2026-10-09-g11-main-verification.md#交付与未验) | [独占原始回执目录定位](../superpowers/verification/evidence/g11-p3-813154b3fa4c-package/delivery-intent.json) |
| G11 PID修正后P3被拒绝 | [矛盾回执及独立裁决](../superpowers/verification/evidence/g11-p3-581b459dad08-root-review/round-verdict.json) | [1904文件REJECTED manifest](../superpowers/verification/evidence/g11-p3-581b459dad08-rejected-frozen/manifest.json)、[当前复验报告](../superpowers/verification/2026-10-09-g11-ci-correction-main.md) |
| G11 退出观察修正与98控制 | [root独立审核](../superpowers/verification/evidence/g11-root-exit-20261009-review/controls-review.json) | [98控制manifest](../superpowers/verification/evidence/g11-p2-rx2-e46234f8c9fa48e9b504156a3f0c9054-frozen/manifest.json)、[工程manifest](../superpowers/verification/evidence/g11-p2-eng2-861d631887ec4c9790f3deb3f062ecae-frozen/manifest.json) |
| G11 修正后main新P3与交付 | [最新main报告](../superpowers/verification/2026-10-09-g11-root-exit-main.md) | [2501文件manifest](../superpowers/verification/evidence/g11-p3-2ba76738625d-frozen/manifest.json)、[工程独立审核](../superpowers/verification/evidence/g11-p3-2ba76738625d-root-review/engineering-review.json)、[本轮精确SHA交付入口](../superpowers/verification/evidence/g11-p3-2ba76738625d-package/delivery-intent.json) |

## 复现只读成本盘点

使用 Python 3，在仓库根运行；输出必须是 `docs` 内一个尚不存在的新文件，不能重用冻结目录中的文件名：

```powershell
python docs/maintenance/collect_costs.py --repo . --out docs/maintenance/new-cost-snapshot.json
python docs/maintenance/check_collect_costs.py --out docs/maintenance/new-fixture-receipt.json
```

只统计 `src/test/buildSrc/docs/scripts` 的已跟踪文件；首轮不存在 `scripts` 目录。源码/文档有已跟踪改动时采集器拒绝继续，以免混合 HEAD 与工作区。新脚本自身未计入首次基线。合成 fixture 验证真实计数、重复脚本、脏输入拒绝及禁止路径字符串；不会创建禁止目录。逻辑字节不是 `.git` 压缩体积或可释放空间。
