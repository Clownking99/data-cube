# G11验证工具的最小设计与实现

状态：G11首次P1/P2/P3通过后，修正后的新P3检出退出观察矛盾，当前交付未接受，正在最小修正。实际诊断和裁决见[当前复验报告](../superpowers/verification/2026-10-09-g11-ci-correction-main.md)。依据是[G10实际协调诊断](../superpowers/verification/2026-10-09-g10-redis-coordination.md)及[维护成本基线](2026-10-09-maintenance-cost-baseline.md)。只迁移本轮新验证，旧冻结脚本和manifest不动。

## 边界

保留阶段脚本决定任务、过滤器、预算及验收矩阵，提取四个小单元。PowerShell负责Windows进程与路径，Python负责证据解析和哈希；不引入依赖包、Gradle插件或全局配置。

| 单元 | 输入/输出契约 |
| --- | --- |
| New-OwnedScope | 显式repo/JDK/cache与阶段ID；返回全新UUID home/temp/build、Windows实测短路径及应保存的环境策略；不读取用户profile，不默认联网 |
| Invoke-OwnedProcess | exe、结构化argv、工作目录、受控环境、期限；并发读取stdout/stderr，返回真实exit、timeout、PID与结算状态，首失败原日志不可覆盖 |
| Read-TestResults | 本轮唯一XML目录；独立统计suite与testcase，识别空 `<skipped/>`，校验failure/error/skip数量；零XML/不一致直接失败 |
| Verify-InputBinding | 显式允许路径集合、前后长度/哈希与受验commit；先标准化路径分隔符再生成测试类名单，名单为空/漏项拒绝；输出可审计回执 |

物理结算单独报告：超时发出kill后继续有限等待真实退出，失败保持未结算状态，不能以“已请求终止”返回成功。只处理自有进程范围，不泛扫或停止邻居。任一子阶段非零保留错误并传播，不能让随后打印/统计覆盖退出码。

## 最小试点与回滚

1. 先用合成 process/XML/input fixture 验证四单元。包括持续输出、非零退出、编译前失败、空skip、XML计数矛盾、Windows反斜杠、输入变化、重复run名、超时未退出和邻居进程。
2. 选下一轮的一个定向阶段作试点，与现有阶段脚本对同一合成输入产生可比结果；共享工具与薄入口的实际字节/哈希一起冻结，未来工具升级不能改变旧证据含义。
3. 试点通过后才迁移该轮全量/buildSrc/image入口。一次只改工具调用；不同时改产品、测试等待时间、过滤器或skip策略。
4. 单独提交工具与入口切换。回滚恢复薄入口的旧调用；保留失败原件和两套执行身份。验收输出只保存必要源快照、完整哈希/当前XML/原日志，避免再次复制未修改驱动和整个源码树。

完成标准是输出一致、错误不被吞、退出可证明、历史可复现和重复维护点减少。不能仅以代码行数下降或 CI 变快判成功。Windows FX 偶发初始化超时仍需独立诊断；不通过放宽等待、重试到绿或强制headless规避。

## 实际落点与剩余成本

四契约落在`VerificationCore.psm1`及`evidence_tools.py`，进程host、外层owner与镜像审计分别保持单独职责；`run-stage.ps1`和`stage-policy.json`负责本轮任务。两份Java probe保留G10来源原字节，仍外置编译运行。调用和额度见[验证说明](verification-guide.md)。实现用12文件闭包，没有新增依赖包、Gradle插件或更改产品/构建/CI。共享工具接入未修改JUnit；首轮CI随后暴露旧PgDump测试PID发布竞态，另以三个测试文件的最小协议修正和10个回归处理，详见[修正后main验收](../superpowers/verification/2026-10-09-g11-ci-correction-main.md)。测试等待、过滤器和skip策略保持不变。

后续只复制受验工具快照，不再维护多份独立变体；执行证据仍保留各阶段快照、原始日志和XML。G11为证明错误路径保存了多轮失败和合成原件，短期证据字节增加；没有清理历史或宣称释放磁盘。下一步候选仍是受控JVM测试夹具合并，另立范围后再做；本轮不迁移夹具或拆分Redis/Shell。

退出观察修正保持12文件闭包，只改四文件：host统一实际handle观察并原子发布带身份事件，parent保留UTC字符串并严格关联，最终成功必须有完整根退出证明；合成目录改独立UUID，避免Windows长路径使Python误判gate不存在。新增18项组合控制，完整矩阵98项。两次必要修正及编排/审核错误均保留原件；短命进程的未捕获分支明确以已准入host持有handle的事件证明，物理结算仍另行检查。最新main验证与未验范围见[修正后报告](../superpowers/verification/2026-10-09-g11-root-exit-main.md)。
