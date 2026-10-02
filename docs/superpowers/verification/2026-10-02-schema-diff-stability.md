# SchemaDiff 稳定性开发账本

日期：2026-10-02。隔离分支 `codex/schema-diff-stability`，起点 `055bda4a9d0f9d561cb9242971dbabb8e8f6c3e7`。本轮仅 mock 定向开发验证，无真库、业务数据或外部联系。

## S1 原始证据与诊断设计

- 当前目标：独立确定历史间歇红灯的可证实原因。
- 已读：指定计划、旧 `sol-p0-p2/full-first/TEST-com.datacube.service.SchemaDiffServiceTest.xml`、SchemaDiffServiceTest、SchemaDiffService、ConnectionManager、CredentialCipher、DPAPI/AES 实现及已有 SchemaDiffConcurrencyTest 的记录方式。
- 发现：服务分别提交两个 virtual reader；openDedicated 不串行；测试 RecordingConnectionFactory.open 在两侧线程上直接向同一个 ArrayList.add。DPAPI 每次调用使用独立 confined Arena，AES 每次新建 Cipher；尚无凭据保护缺陷证据。
- 历史限制：旧 XML 只有安全摘要与 service coordinator 堆栈，没有原 cause。因此不能百分百证明历史那一次的根因；后续只报告实际复现的缺陷。
- 改动：先增加夹具并发回归，32 个固定线程在 barrier 后各打开 256 个独立合成连接，共 8192；收集底层异常并比较记录、唯一 ID、物理 close 次数。增强原比较测试的双侧 snapshot/schema、host/password 和每连接 close 断言。尚未修复记录容器。
- 验证：首次红灯正在执行，参数与独占临时 profile 已保存于 `evidence/schema-diff-stability/red-first/parameters.json`；统一脚本 `Run-Targeted.ps1` 保留原日志、XML 与 exit/result，旧隔离 init 只复用过滤规则。
- 失败/未验：首次新执行尚未结束；尚未改变产品代码或宣称修复。
- 下一步：保留首次计数红灯；只对已证明夹具竞态做最小修复，再用新的独占 profile 定向验证。

## S2 首次红灯与最小修复

- 当前目标：修复已证明的夹具记录竞态，并验证双侧快照与实际关闭。
- 原始红灯：`red-first/raw.log`、两份 XML、parameters/result 已保留，exit=1，9 tests / 2 failures。并发回归实际 expected=8192、recorded=6177、physicallyClosed=8192、workerFailures=0，证明无异常情况下仍丢失 2015 条记录。该红灯是记录完整性失败，不是历史 `Schema snapshot failed` 的底层异常复现。
- 另一个失败：新 schema 断言发现原 partialSnapshot mock 把已引用的 schema.original 再按 catalog 名规范化，产生双引用。这是旧合成夹具构造偏差，不是产品快照缺陷；首次日志如实保留。
- 改动：仅测试文件中 opened 使用 `Collections.synchronizedList(new ArrayList<>())`；mock reader 先验证实际传入 schema 与连接 ID 一致，再用明确合成 catalog owner 构造快照；close 代理记录真实方法调用，每 ID 使用独立 AtomicInteger。barrier=5s、future=10s 是测试有界同步，无 sleep、retry 或产品 timeout 修改。
- 验证：`green-first` 使用全新独占临时 profile、相同定向两类、offline/no-daemon/max-workers=1/rerun-tasks；执行中。
- 失败/未验：历史底层 cause 仍未知；本次没有证据指向凭据保护或产品读/关连接错误。本轮未跑全量/buildSrc/image/main。
- 下一步：核对新 XML 中计数与测试结果，记录最终文件字节摘要，再交父线程独立审核。

## S3 新绿灯与双线程异常机制证据

- 当前目标：结束开发侧定向闭环，准确界定历史归因置信度。
- 验证：green-first exit=0 / BUILD SUCCESSFUL，8 actionable tasks 全执行；SchemaDiffServiceTest 4 tests、SchemaDiffConcurrencyTest 5 tests，均 failures=0/errors=0/skipped=0。并发记录回归 expected=8192、recorded=8192、physicallyClosed=8192、workerFailures=0；唯一 ID 与每 ID close=1 全部通过。双侧 snapshot/schema、开连接配置和每侧实际关闭断言通过。现有并行、取消、配置快照及错误脱敏定向测试全部通过。
- 外置诊断：`TwoAddProbe.java` 使用两个线程在新空 ArrayList 上各 add 一次，固定 200000 pairs；每 barrier 5s、future 60s，无 sleep/retry；unsafe 与 safe 各执行一次，实际约 2s。无需应用 profile、凭据或产品接口，所有元素仅整数。
- unsafe：expected=400000、recorded=399940、invalidPairs=60、rawFailures=1，Java 实际 exit=1。安全原始堆栈捕获 `ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0`，JDK `ArrayList.add` 行 485/497。
- safe：同参数换同步列表，expected=recorded=400000、invalidPairs=0、rawFailures=0，Java 实际 exit=0。对应原始日志和 exit 单独保存；PowerShell 记录包装命令自身 exit=0 不代替 Java exit。
- 实际命令：`D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8/bin/java.exe docs/superpowers/verification/evidence/schema-diff-stability/TwoAddProbe.java` 与追加 `safe`。定向命令：`./docs/superpowers/verification/evidence/schema-diff-stability/Run-Targeted.ps1 -Run red-first` / `-Run green-first`；完整 Gradle 参数与不同独占 profile 路径见各 parameters.json。
- 归因：原夹具记录竞态已由真实工厂完整计数证明（高置信度）；同一个空 ArrayList 的双侧 add 底层异常机制已实际复现（高置信度）。服务会把这种 read 路径 runtime failure 摘要为 Schema snapshot failed，故其与历史故障一致且合理；历史那一次仍因原 cause 缺失而不能百分百归因。无凭据保护异常证据，也没有真实产品缺陷证明。
- 改动范围：仅 SchemaDiffServiceTest、当前账本和 schema-diff-stability 证据目录。产品代码、产品脱敏语义、协调计划、其他 worktree 未修改；未暂存或提交。
- 失败/未验：首次红灯原样保留；测试源码编译已有 unchecked 提示。全量/buildSrc 专用测试/image/main 新复验由父线程执行；当前结果不等于 M8 或发布完成。
- 下一步：父线程独立审核、精确提交与后续验证。文件 raw SHA-256/长度、raw Git object ID 和受 Git 过滤后的 object ID 见 evidence 文件字节清单，允许识别默认 checkout 换行差异。
