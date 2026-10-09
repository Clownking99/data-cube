# 下一轮验证工具的最小设计

状态：设计已整理，**尚未实现或切换正式验证流程**。依据是[G10实际协调诊断](../superpowers/verification/2026-10-09-g10-redis-coordination.md)及[维护成本基线](2026-10-09-maintenance-cost-baseline.md)。只迁移下一轮新验证，旧冻结脚本和manifest不动。

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
