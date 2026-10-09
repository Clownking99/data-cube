# G11 main独立验收

本报告记录修正前main62ea18d的首次本地P3，原件保持不变。随后cfd9d4ed的Verify37893270726在Ubuntu暴露旧PgDump测试PID发布竞态，整体CI失败；没有将本地通过冒充交付成功。[PID修正后的P3](2026-10-09-g11-ci-correction-main.md)随后因退出观察矛盾被拒绝；最新受验版本和交付判定见[退出观察修正后main报告](2026-10-09-g11-root-exit-main.md)。首失败见协调账本C11–C12。

2026-10-09，root接管唯一Gradle执行。工具提交`48b51d63ee89edb8dcb0187d7dd43367c558f347`，开发证据提交`6aefd9d136058ec67cab688b28158dcc4416fb9d`，合并`4ddba09d6b848dca4532b575b6db930702c79211`，受验main为`62ea18d1981e8ac65b4b727566786d56b6a65371`。其后的交付文档和证据提交不改变875个工程/工具输入。

## 实际结果

完整新序列以Python `-I -S -B`执行，最终实际退出0。12共享文件与开发冻结字节相同；只将一次性编排器改为新P3前缀/受验main与显式require，不改产品、测试、构建、CI、等待或过滤器。每阶段有全新UUID home/temp/build，offline Gradle、清空后受控环境与native FX。

| 阶段 | 当次证据 |
| --- | --- |
| Python契约 | 21项通过 |
| 进程/阶段控制 | 21项通过，保留实际根退出、取消、首失败、邻居与Job结算 |
| 镜像/角色/skip政策控制 | 31项通过 |
| 外层owner | 7项通过，真实helper、双流预算、赋Job失败与残留子进程 |
| 定向 | 56 XML / 291通过 / 1精确Redis live前置跳过 / 0失败错误 |
| clean全量 | 351 XML / 4711通过 / 3精确live前置跳过 / 0失败错误 |
| 强制buildSrc | 1 XML / 8通过 / 0跳过失败错误 |
| jpackageImage | 成功，183文件实物逐一长度/SHA复核 |
| linked | 4命令实际0，26088类对408动态测试类型无泄漏，六个Redis必需产品类齐全 |

定向与全量中11项RedisPaneBudgetTest原生FX用例逐项成功，普通RedisPaneCloseSequenceTest不计为原生用例。三个允许跳过均核对了完整class/case/前置原因；跳过未加到通过数。每工程阶段875输入前后一致并与磁盘逐项核对，12工具与exe身份一致；228内层日志、10外层日志及66份存在的合成控制流日志重算正确。正常工程root0/完整收尾，outer无强制终止且Job自然清零。

image前后全文件集合相同，cfg不含隔离选项，测试/JUnit/Mockito/TestFX/acceptance/probe未进入产物。驱动原始输出`connectCalls=0; no credentials or user profile loaded`；Redis探针确认`G10_LINKED_REDIS=true`、`socketsSettled=true`、`realServices=0`。探针编译位于镜像外。

## 原件、修正与运输

- [五阶段原件定位](evidence/g11-p3-813154b3fa4c-package/progress.json)、[实际shell退出](evidence/g11-p3-813154b3fa4c-package/shell-exit.json)。
- [独立完成审核](evidence/g11-p3-813154b3fa4c-root-review/completion-review.json)、[精确skip与原生FX审核](evidence/g11-p3-813154b3fa4c-root-review/case-review.json)。
- [P3封存manifest](evidence/g11-p3-813154b3fa4c-frozen/manifest.json)：1900文件，33218660字节，SHA256 `923c436ba191f2627d9b34ec24dee176b39f13bea4c1461c86fcdb675afc80d6`。
- P1 1777文件、P2 2022文件在main重算不变；[3817文件运输核对](evidence/g11-p2-root-review/main-transport.json)证明worker提交与main检出原字节相同。P1报告曾被检出换行转换，已用精确属性保护恢复原blob；不是改写原证据。
- 首次P3归档审核因Git不保存P2首失败的一个空目录而退出1；审核器仅对这一个精确根且清单零文件的情况单列缺席后完整重验退出0。没有丢失文件，也没有放宽非空根校验。[审核夹具更正记录](evidence/g11-p3-813154b3fa4c-root-review/reviewer-corrections.md)。

P1/P2早期失败及root审核夹具错误仍在[协调账本](2026-10-09-g11-verification-coordination.md)，未覆盖原件或采用重试到绿。仅工具/文档改变，不以本轮通过充当外部验收。

## 交付与未验

本地P3接受。只推main并验证精确HEAD的四任务Verify；[交付定位](evidence/g11-p3-813154b3fa4c-package/delivery-intent.json)指向本机独占build目录中的实际push、API、日志、最终delivery-result和manifest。该定位文件本身不是CI成功证据；最终回执须`passed=true`且remoteHead/head相同。没有fetch/tag/PR/发布。

完整桌面流程、真实Redis/数据库、安装升级回退及生产签名仍未验。Job只覆盖实际成员/直接持有handle，不外推委托服务；同步OS永久阻塞不作物理硬期限承诺，故障注入不等于实际不可杀进程。G10最大合法多会话RSS、DNS/阻塞写/native close/GC等边界继续保留。CI临时Redis不能代替真实业务环境验收。

G11到此范围结束。受控JVM夹具合并和Redis/Shell拆分仍为后续候选，本轮不实施、不创建自动下一轮。
