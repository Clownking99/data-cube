# G11 CI PID readiness worker

2026-10-09（Asia/Shanghai）。这是现有G11交付被真实CI失败阻止后的最小测试夹具修正，不是新的维护阶段。分支`codex/g11-ci-pid-readiness-20261009`，受验源码为基线`cfd9d4ed4ebd08d1b9f2ff138ae25690901effe9` **加以下三文件未提交工作区修正**；不声称原提交已包含新回归。

## 原因与修正

root提供的原始Ubuntu CI日志只读确认：Verify 37893270726 / job113698755125，`capturedFamilyHoldingPipeIsStoppedAfterParentExitAndIndependentNeighborSurvives [2] tree`在第169行`Long.parseLong("")`失败。PID文件创建到写入关闭间存在空文件或可解析数字前缀窗口；存在性不能证明完成，非空检查也不能解决部分数字。

仅修改`PgDumpProcessHelper.java`、`PgDumpTestJobs.java`、`PgDumpRunnerReliabilityTest.java`：producer使用CREATE_NEW写入正PID，writeString返回并关闭payload后才CREATE_NEW创建同名`.ready`事件；consumer统一awaitPid等待此事件，再由readPublishedPid校验事件存在并解析正long。全部三个child/grandchild消费者已替换。原5秒等待、实际capture、物理结算、邻居存活、发布保护和handle控制断言不变。没有产品、共享12工具、Gradle/CI、JVM夹具合并或历史冻结目录修改。

## Focused Requirement | Evidence

按code-testing-agent focused流程执行，未创建中间状态目录或额外线程/agent；root先审三文件源码，再授权唯一一次最窄工程验证。

| Requirement | Evidence |
|---|---|
| “不能仅等非空，因为部分数字也可解析” | `pidReaderRejectsEmptyPartialAndCompletePayloadUntilPublication`：3参数case（空串、12前缀、123456完整但未发布）确定性同步拒绝，关闭完整payload并发布后读取123456；无概率sleep/重跑 |
| “在PID写入关闭后另发ready事件” | `pidPublisherPublishesCompletePositivePayloadForBothFamilyRoles`：child-pid/grandchild-pid两个参数case，实际producer创建完成标记、完整payload与consumer一致；源码writeString完成先于createFile |
| “解析完整正PID” | `publishedPidMustBeCompleteAndPositive`：5参数case（空、0、-1、12x、long溢出）在已发布状态也确定性拒绝 |
| “覆盖所有child/grandchild消费者” | 3处实际消费者统一awaitPid，`capturedFamilyHoldingPipeIsStoppedAfterParentExitAndIndependentNeighborSurvives`的parent/tree两个case和`throwingNormalHandleControlStillJoinsItsWorkerAndForcesOnlyOwnedFamily`均实际通过 |
| “保持实际capture、物理结算、邻居存活要求” | 原family parent/tree、throwingNormalHandleControl及`blockedHandleControlCannotBlockObservationAndItsLateReturnIsStillOwned`均通过，原断言未削弱 |
| “只这一阶段，不全量/image/linked” | 唯一实际Gradle命令cleanTest test --tests com.datacube.export.PgDumpRunnerReliabilityTest --rerun-tasks --offline --no-daemon --console=plain，附明确JDK与冻结isolated.gradle；实际exit0，BUILD SUCCESSFUL in 41s，9 actionable tasks（8 executed/1 up-to-date） |

33测试=原23+新增10参数case，1 suite / 33 passed / 0 skipped / 0 failures / 0 errors。原始XML保留重复display name及独立ordinal，未去重。`audit-result.json`将新增3组10case、原family两case及handle控制case对应到唯一连续display组与零基XML ordinal；明确Gradle参数XML仅存display名称，方法映射依据源码声明，未伪造方法字段。

## 有界执行与原件

证据入口：`evidence/g11-p2-ci-pid-4983fab79aa14a5da1626836b240525c/`。执行前冻结17文件entry-manifest（12原字节共享工具、薄入口stage.ps1/outer.py、spec/input/tool清单），SHA **C1F8DF6527DEF27934900A77D2CE402C5FA19672EF95C05528DA950A1939FC18**。outer以已冻结run-owned.py原内核为基础，仅显式选择本轮薄入口/工具根、补充入口身份闭包，并在赋Job后释放gate；root已独立逐行审查准入。12共享工具源及副本不变，不修改stage-policy过滤器。

第一次操作将相对spec传给outer，核心词法路径校验正确拒绝ABSOLUTE_PATH_REQUIRED，实际shell退出1，未创建scope/未启动进程/未运行Gradle。原始`outer-invocation.log`保留。之后只改调用参数为绝对路径，入口和工具字节不变；`actual-invocation.json`及`outer-invocation-absolute.log`保存实际调用，后者实际shell退出0。

本轮唯一真实Gradle使用新独占UUID home/temp/build、native FX `java.awt.headless=false`、清空继承业务环境及现有JDK/Gradle缓存。内层每进程600000ms/收尾5000ms、双流各32MiB；外层总预算660秒、双流各1MiB/16KiB检查块，沿用私有Job、有限收尾和首因保留。实际inner root exit0/ownedSettlement complete；outer actualExitCode0、firstFailure null、实际Job查询empty、两流EOF完整且未超限；所有辅助进程也有独立共享核心命令/退出/流原件。

实际stage receipt：`evidence/g11-p2-ci-pid-4983fab79aa14a5da1626836b240525c-stage/b7c032677cc04d2cbea16fb8a21b609d/stage-result.json`；outer receipt：同prefix的`-owner/result.json`。stage内保留实际narrow-command、scope、gradlew.bat映射SHA、875文件inputs-before/after、input-verification、完整raw XML、共享核心所有process request/root-identity/status/双流原件，以及exe阶段后SHA/长度。独立解析XML与内核统计完全一致；875输入前后相同；所有exe和12工具/薄入口前后身份相同。

最终封存：`evidence/g11-p2-ci-pid-4983fab79aa14a5da1626836b240525c-frozen/manifest.json`，**103文件 / 910931字节**，SHA **04EE175BA44263AC75A6E528E19C0D6F1CAFCAEA1E72C46E599657CB79F72379**。包含入口、首次拒绝、当前真实定向全部最小原件和实际audit-and-freeze.py，不包含整棵runtime/build/JDK/源码或驱动副本。三文件工作区SHA在manifest和audit-result逐一保存。

本轮已停止Gradle与写入，等待root归档审查；暂未提交、合并、推送、全量/image/linked或CI重试。旧未跟踪`.g10-verify-blobs.ps1`未读未改；未访问`.testagent`、真实数据库/Redis、外网或业务配置。
