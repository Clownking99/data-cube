# 维护成本基线与下一步

本轮先完成 G10，再独立整理维护成本。基线为已交付 main `567a5293528a20c2d4eae6daafabe2242064b676`，G10 [Verify 37874713840](https://github.com/Clownking99/data-cube/actions/runs/37874713840) 四任务通过。盘点是一次测量，不是长期性能统计；本轮不改变产品、测试或构建逻辑。

## 实测成本

[采集器](collect_costs.py)只读取 Git 中明确允许的 `src/test/buildSrc/docs/scripts` 元数据和 Java/交接文档内容，排除 `.testagent`。没有枚举仓库根、用户目录或业务文件。首轮 `scripts` 不存在；未跟踪的新整理文件不算入该基线。数据见[最终采集结果](../superpowers/verification/evidence/maintenance-cost-20261009/baseline-final-collector.json)。

| 项目 | 当前测量 |
| --- | --- |
| 允许范围内已跟踪文件 | 28,985 个，逻辑大小 809.55 MiB |
| 其中冻结证据 | 27,764 个，798.19 MiB，占逻辑大小 98.60% |
| 证据中的 XML | 19,885 个，562.03 MiB |
| 验证脚本 `.ps1/.py/.gradle` | 381 个文件，235 个不同 Git blob，146 份额外的完全相同副本 |
| 相同证据 blob 的重复逻辑字节 | 150.11 MiB；**不是可回收磁盘空间**，Git已经对相同blob去重 |
| Java（src/test/buildSrc） | 837 个文件、145,789 行；用于定位检查范围，不作为坏代码判定 |
| 历史交接/路线图 | 259/330 行，83/74 个Markdown链接，合计79处“最新”字样 |

这里的字节按 Git blob 长度计算，不等于 `.git` 压缩体积、checkout磁盘占用或删除收益；没有测量后两者。脚本重复数仅指原始字节相同，变体是否等价尚未推断。Java import 统计只是语法前缀，不是解析后的调用图。

证据中最大的阶段是 Windows CI 路径修正 root/worker 两目录，分别约57.21/44.59 MiB；数据直接来自阶段分组。当前增长更多来自每轮全量XML、命令/绑定副本和失败历史。保留它们对审计有价值，本轮不删除、迁移或压缩历史；减少未来重复执行规则和不必要源/驱动副本，比追求“删掉大目录”更稳妥。

[本轮实际耗时](../superpowers/verification/evidence/maintenance-cost-20261009/timings.json)：main定向2m37s、clean全量6m41s、buildSrc9s、image2m7s；四阶段串行Gradle合计11m34s，另有审计时间。CI Linux Unit tests56s、Windows Unit tests417s、Windows linked image49s，Windows整个job500s，临时Redis测试44s。两平台环境及执行/跳过条件不同，**不能把56s与417s直接解释成产品速度差**；这只是首个时间基线，尚未归因，也未提出删测试/放宽超时的优化。

## 已落地

1. 新增稳定[当前状态](../handoffs/CURRENT.md)，README、原路线图和历史交接跳转到这一入口。详细历史保留原处，不再不断新增多个“最新”段落。
2. 新增[验证说明与证据索引](verification-guide.md)，区分开发验证、root main复验、CI和外部待验；指出旧runner的路径/名单/过滤器限定，避免直接重放旧脚本。
3. 新增可复现只读采集器及[合成检查脚本](check_collect_costs.py)。[最终fixture](../superpowers/verification/evidence/maintenance-cost-20261009/collector-final-fixture.json)证明7个真实Git文件、3个Java/5行、14字节两份脚本/7字节重复量、脏输入拒绝和禁止路径字符串门禁；没有创建或枚举禁止目录。第一次fixture和首次基线保留，最终脚本加强了不可被Python优化模式取消的路径门禁，后再次执行验证，核心基线数字一致。
4. 新增[未来runner最小设计](verification-runner-design.md)，保留首失败、环境隔离、当前XML/输入绑定及物理结算。仅是设计，本轮未切换正式验证工具。

## 优先级与实施边界

root已独立核对原GPT‑6.1-sol会话的只读建议（turn `01a11e86-70c8-7ee2-8a94-a9973fd0aa1b`），没有新增会话/代理。排序依据是近期实际修正、出错后果和回归成本，不以行数排序。

| 优先级 | 依据与建议边界 | 候选接口 / 收益 | 迁移、回滚及必需回归 |
| --- | --- | --- | --- |
| 下一轮首先做 | G10 runner发生配置失败、路径名单遗漏等真实诊断；多处重复维护隔离/退出/XML规则。只共用新验证内核，旧冻结脚本不动 | `New-OwnedScope / Invoke-OwnedProcess / Read-TestResults / Verify-InputBinding`；一次纠正覆盖未来入口 | 先试点一个新定向阶段，工具/入口单独提交，回滚恢复调用。覆盖非零码、空skip、零XML、编译前失败、输入变化、反斜杠、首失败、超时和实际退出；见详细设计 |
| 随后做 | 三个旧测试的超时finally调用destroyForcibly后未再等待退出：[SqlDraftDirectoryTest](../../test/com/datacube/config/SqlDraftDirectoryTest.java) 72–80、[SqlWorkspaceStoreTest](../../test/com/datacube/config/SqlWorkspaceStoreTest.java) 187–196、[SqlDraftCodecTest](../../test/com/datacube/config/SqlDraftCodecTest.java) 184–196 | 测试包内 `OwnedJvmProbe.start(spec)/awaitOrKill(timeout)`；保留具体探针断言，统一双流与物理退出回执 | 先迁两个锁/工作区探针，独立提交可回滚。覆盖锁忙/释放、持续输出、非零码、强杀后等待和邻居不受影响。[PgDump helper](../../test/com/datacube/export/PgDumpRunnerBaselineRedTest.java) 31–40必须保留被测builder环境，不能由夹具代替产品清理变量 |
| 再评估 | [RedisKeyBrowserPane](../../src/com/datacube/fx/RedisKeyBrowserPane.java) 92、197、389的generation/request owner/pending刷新/value epoch跨方法约束；G10已有刷新优先级修正证据 | Redis局部 `RedisBrowserRequestState`：admitRefresh/admitSelection/isCurrent/settle；显式token与下一意图 | 先只迁准入/settle，pane保留FX候选安装/回滚、opening所有权；不扩大通用queue。单提交回滚，保留11项FX尤其旧action零入队、刷新优先、cursor拒绝、安装失败、三个opening关闭窗口 |
| 再评估 | [AppShell](../../src/com/datacube/fx/AppShell.java) 266–295关闭入场/恢复与305–314破坏性顺序分散；新增资源容易漏同步契约 | Shell局部 `ShutdownAdmission.begin()->Attempt.finish(...)`，复用已有协调器 | 只迁freeze/resume/事务释放入场，关闭顺序与tab factory暂留；不再造通用生命周期框架。回滚恢复facade方法体；验证重复关闭、取消后再次操作、tabs提交后失败不能恢复，以及事务/导出/JDBC/workspace/grid全部shutdown契约 |

上述“强杀后未再等待”是超时失败分支的源码发现，**未在本轮触发，也不表示已经观察到孤儿进程**；G10本轮新探针与UUID范围结算仍按实际回执接受。后续改造需验证该失败分支，不靠延长统一timeout。

`SqlEditorPane` 为3220行，`AppShell` 918行，`RedisKeyBrowserPane` 435行。后两者的维护热点是状态/所有权交叉，说明仅按“大类”排名会漏掉真正边界。较大的两种Schema renderer以及SQL scope重写/格式化在本轮只记录体量，不因此发起抽象合并或恢复SQL美化主线。

## 检查点与未实施项

目标：G10交付后完成一次可复现盘点和低风险入口整理。改动：仅docs/README及只读采集工具。验证：最终采集器fixture通过、与初始核心数字一致；本轮新增本地链接、变更白名单和产品树一致性在[验收回执](../superpowers/verification/evidence/maintenance-cost-20261009/review-receipt.json)记录。文档整理没有本地重跑产品Gradle，不把G10旧结果算作脚本测试；最终文档提交的CI单独记录在[交付入口](../superpowers/verification/evidence/maintenance-cost-20261009/delivery-intent.json)。

工具诊断：初次按计划枚举可选scripts目录时目录不存在；随后采集器使用Git明确路径白名单，不依赖目录存在。一次测试文件路径按旧猜测定位失败，已通过允许目录中的精确文件名查到config/export位置后独立读源码。这些不是产品测试失败。没有修改历史失败或冻结原件。

尚未实施共用runner、JVM夹具和pane/Shell拆分，也未升级依赖、重写Git历史或移动证据。Windows FX偶发初始化超时根因仍未知，不以本轮CI通过宣称已修复。真实服务、原生桌面、签名/安装/完整发布待验继续按[当前状态](../handoffs/CURRENT.md)管理。下一轮优先验证工具试点，本轮整理完成后交付，不自动展开上述全部改造。
