# G11 退出观察修正：main 独立验收与交付

本轮本地P3已独立接受，受验main为 `2c8fc13577337a9b5247351e0634e76ff3841999`。最终推送/CI按下文交付入口读取实际结果；本报告不提前宣称尚未发生的远端结果。旧失败原件保持原样，不能作为新轮通过证据。

## 改动与集成

四文件工具修正 `a59b3f15ae21c1514acfc5060b15ab41fe90c94b` 和证据 `258a03db12648b821e85273adcc16b25e1efc099` 已合入上述main。host在循环头、尾及最终收尾统一观察实际handle并原子发布退出事件；parent保持UTC字符串身份，交叉核对identity、事件、host和已捕获handle。成功必须有完整根退出证明、真实0退出、Job与双流结算；缺失/损坏/矛盾证据拒绝成功，并保留先发生的非零、取消或预算原因。合成根采用独立短UUID避免Windows长路径可见性差异，不延长预算。

其他8工具、产品、构建和CI未因这次修正改变。此前三个PgDump测试文件的PID完成标记修正仍保留，等待预算未增加。3324运输文件、49529013字节已在Git、worker和main间逐项核对，[main运输回执](evidence/g11-root-exit-20261009-review/transport-main-disk.json)。

## 新P3实际验证

root接管唯一Gradle后，用全新 `g11-p3-2ba76738625d` 范围执行完整98控制及五阶段；没有复用开发阶段续接结果。PowerShell无profile，Python `-I -S -B`，JDK25.0.1+8、PowerShell7.6.5、Python3.12.14；仅mock、合成profile、独占home/temp/build、既有offline依赖cache和受控本机helper。实际控制器及shell均退出0。

| 验证 | 当轮独立结果 |
| --- | --- |
| 合成控制 | 21 Python + 21原进程 + 18退出观察 + 31政策/角色/镜像 + 7外层 = 98项接受；232原始日志及18强退出证明核对通过 |
| 定向 | 292 case，291通过、1 Redis live跳过，0失败/错误 |
| clean全量 | 4724 case，4721通过、3 live跳过，0失败/错误；PID33项含新增10项全部通过 |
| 强制buildSrc | 8通过、0跳过/失败/错误 |
| jpackageImage | 实际退出0；183镜像文件逐项核对，正式配置无隔离污染 |
| linked探针 | 四命令实际0；26088类对408测试类型无泄漏，driver connectCalls=0，Redis socketsSettled=true、realServices=0 |

定向和全量各11项 `RedisPaneBudgetTest` 原生用例实际执行并通过；普通关闭顺序单测单列。三个live跳过分别为Redis standalone、Oracle Schema Diff和PostgreSQL Schema Diff，均因明确环境/授权前提未满足；[精确名称和原始原因](evidence/g11-p3-2ba76738625d-root-review/case-review.json)。跳过不算通过，11项原生测试不代表完整桌面验收。

五阶段均独立核对875工程输入、408测试类型、12工具；57个process的原始身份、退出事件、host、proof与实际捕获handle一致，228内层日志和10外层日志完整。各阶段审核回执及脚本见 [root审查入口](evidence/g11-p3-2ba76738625d-root-review/engineering-review.json)、[控制审查](evidence/g11-p3-2ba76738625d-root-review/controls-review.json)、[PID审查](evidence/g11-p3-2ba76738625d-root-review/pid-case-review.json)。

17入口manifest SHA `2aaa601bf03f0114ecfa2c8569cfd6102aee209cc19fededafb29f3a6ebc39fc`，实际控制器 SHA `14f6ff7eb55ca20eb7988a04feec59bb55ab189cfd60b94b5b82cbd071d6404d`，逐项原字节核对及实际命令/退出见[入口审查](evidence/g11-p3-2ba76738625d-root-review/entry-review.json)。

最终[封存manifest](evidence/g11-p3-2ba76738625d-frozen/manifest.json)：**102根、2501文件、37637132字节**，SHA **fc70f285710a1d9baac4af58e0a0f1f4ee2130aaf1b3e12f51b23d987421acfb**，accepted=true、deliveryAllowed=true；不复制运行时、cache或镜像二进制树。

## 失败保留与交付判定

首次精确SHA CI [Verify37893270726](https://github.com/Clownking99/data-cube/actions/runs/37893270726) 在cfd9d4ed暴露PID发布竞态，整体失败。PID修正后的第一次root序列虽然实际退出0，独立审核发现缺失根退出事件却声称passed，1904份原件仍为REJECTED。开发侧264字符门路径失败736份原件、原生用例误计的controller失败213份原件也均保留。详见[协调账本C14–C19](2026-10-09-g11-verification-coordination.md)。必要审计器修正只重审同一原件；没有覆盖失败、放宽skip/预算或重跑到绿。本轮新P3没有失败。

最终提交只增加本轮证据和交接文档，不改变受验875工程输入及12工具。受验main和最终交付commit分别记录，不能混淆。已提交的[交付入口](evidence/g11-p3-2ba76738625d-package/delivery-intent.json)定位 `build/owned-g11-ci-402f09274d90434dbcf90332f30cba87`；其中 `delivery-result.json` 才是最终main SHA、远端SHA、Verify链接、四任务终态与passed的实际结论，`manifest.json` 索引原始API、命令、退出与完整CI日志。没有该回执或passed不为true时，本地P3通过不等于远端交付完成。

root按既有授权只推main，直连失败才对本次命令使用7897；核对相同SHA的wrapper-validation、Ubuntu、Windows及Redis integration四任务，并确认Windows单测/linked步骤及原始buildSrc/test/jlink日志。交付回执置于独占build目录以避免记录自身提交SHA导致循环提交；冻结证据和受验工程不再改动。

## 保留待验

真实Redis/数据库/pg_dump、完整人工桌面、最大合法多会话RSS、DNS/阻塞写/native close/GC、其他外部驱动及文件系统边界仍未由本轮验收。CI临时Redis不能替代业务环境验收。安装/升级/回退、签名和完整发布验收仍未完成。Windows Job不承诺未捕获的委托后代或永久阻塞OS调用的物理硬期限。

`.testagent`禁读禁改禁枚举；不读取原有连接、配置、凭据、SQL历史或业务文件。不fetch/tag/PR/发布/安装更新/外部联系，v3.2.9不动；旧跟进保持PAUSED，不自动启动后续JVM夹具迁移或架构拆分。
