# 验证入口与证据索引

先读[当前状态](../handoffs/CURRENT.md)，再选本轮计划和受验提交。历史通过只作基线，不能填入新一轮结果。

## 现有执行方法

当前参考实现是[G10 main runner](../superpowers/verification/evidence/g10-main-20261009/Run-P3.ps1)与[镜像审计](../superpowers/verification/evidence/g10-main-20261009/Run-Image-Audit.ps1)。它们是冻结的历史执行原件，含本机 JDK/cache 路径、G10过滤器和408个测试源码的名单断言，**不是可原样套用到未来版本的通用脚本**。

新一轮先复制到新的具名证据目录，核对实际工具路径、工程输入和过滤器，记录来源哈希与改动；每次运行用新编号和独占 UUID home/temp/build，保持唯一 Gradle 执行者。沿用环境清空、native FX 与 `--rerun-tasks`，保存每轮命令、实际退出、stdout/stderr、当前 XML、运行前后输入及产物身份。失败保留后新开一轮，不覆盖原件。开发冻结后由 root 独立审查，再接管 main 新环境复验。

| 阶段 | 核对重点 |
| --- | --- |
| 定向 | 过滤器实际命中、当次重新编译/执行；当前 XML 的 testcase 与 suite 数一致 |
| clean 全量 | 同一受验产品；明确 live/headless 前置跳过；新空目录的 clean UP-TO-DATE 不等于 test 缓存通过 |
| buildSrc | 强制 `:buildSrc:test` 的实际 XML/退出，不能用编译替代 |
| jpackageImage | 实际任务退出、完整镜像长度/哈希、正式配置及测试类名单隔离 |
| linked 探针 | 使用刚生成的运行时/命名产品模块，外置探针；记录子进程退出和自有资源结算 |
| 精确 SHA CI | 提交/push 后只认相同 head_sha 的实际任务与日志；远端 main 同 SHA；不把测试成功推成完整发布验收 |

取消 Future、空 ownership map、调用 destroyForcibly 均不能单独证明物理退出。只检查自有 PID/UUID 范围；真实外部服务另行授权。`.testagent` 始终禁读禁改禁枚举，Git与搜索带排除，文件集合尽量用精确白名单。

## 证据索引

| 结论 | 审核入口 | 原件索引 |
| --- | --- | --- |
| G10 协议准入与恢复 | [P1a root 回执](../superpowers/verification/evidence/g10-redis-20261009-p1a-review/receipt.json) | [worker manifest](../superpowers/verification/evidence/g10-redis-20261009-p1a-worker/raw-manifest.json) |
| G10 展示、保留与生命周期 | [P1b root 回执](../superpowers/verification/evidence/g10-redis-20261009-p1b-review/receipt.json) | [worker manifest](../superpowers/verification/evidence/g10-redis-20261009-p1b-worker/raw-manifest.json) |
| G10 开发侧工程验证 | [P2 root 回执](../superpowers/verification/evidence/g10-redis-20261009-p2-review/receipt.json) | [worker manifest](../superpowers/verification/evidence/g10-redis-20261009-p2-worker/raw-manifest.json) |
| G10 main 独立复验 | [P3 回执](../superpowers/verification/evidence/g10-main-20261009/receipt.json) | [main manifest](../superpowers/verification/evidence/g10-main-20261009/raw-manifest.json) |
| G10 main 推送与 CI | [实际交付结果](../superpowers/verification/evidence/maintenance-cost-20261009/g10-delivery-result.json) | [完整原始响应/日志位置](../superpowers/verification/evidence/g10-main-20261009/delivery-intent.json) |
| 维护成本盘点 | [报告](2026-10-09-maintenance-cost-baseline.md) | [最终采集器基线](../superpowers/verification/evidence/maintenance-cost-20261009/baseline-final-collector.json)、[耗时](../superpowers/verification/evidence/maintenance-cost-20261009/timings.json) |

## 复现只读成本盘点

使用 Python 3，在仓库根运行；输出必须是 `docs` 内一个尚不存在的新文件，不能重用冻结目录中的文件名：

```powershell
python docs/maintenance/collect_costs.py --repo . --out docs/maintenance/new-cost-snapshot.json
python docs/maintenance/check_collect_costs.py --out docs/maintenance/new-fixture-receipt.json
```

只统计 `src/test/buildSrc/docs/scripts` 的已跟踪文件；首轮不存在 `scripts` 目录。源码/文档有已跟踪改动时采集器拒绝继续，以免混合 HEAD 与工作区。新脚本自身未计入首次基线。合成 fixture 验证真实计数、重复脚本、脏输入拒绝及禁止路径字符串；不会创建禁止目录。逻辑字节不是 `.git` 压缩体积或可释放空间。
