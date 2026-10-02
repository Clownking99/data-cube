# AppShell 关闭恢复 N1 worker 账本

2026-10-02；独立 worktree `C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾`，分支 `codex/app-shell-shutdown-recovery`，开发基线 HEAD `4d1940aa1506be61026a4419de777e7cc4c83fe5`。完整读取本轮计划后实施。没有暂存、提交或合并；没有启动桌面 fixture。所有文件内容和缓存 Connection 都为合成输入；未访问真实数据库、原有 profile/凭据/历史或业务文件。

## 检查点 1：真实 AppShell 首红

- 目标：行为确认关闭取消是否损坏文件身份。
- 改动：新增 `AppShellShutdownRecoveryTest`，实际构造 AppShell、Scene、文件 pane、SqlScriptFileController 和原注册表；仅文件任务调度器及 mandatory guard 为合成可控协作者。合成 guard 返回 REJECTED，实际 shell 结算 CANCELLED。
- 验证：`Run-Targeted.ps1 -Run red-first`，exit 1；1 项执行、1 项失败、0 跳过。实际断言为取消后 `registry.select(existing)` 应 true、实际 false。原 tab 尚存；产品却在 guard 结果前永久关闭入口和注册表。
- 失败/未验：首红是合成 mandatory rejection，并非真实工作区决策或原生关闭按钮。源码定位过程的错误猜测路径仅为检索错误，未作验证成功依据。
- 下一步：保留 registry 原身份，暂挂入口、取消后恢复新代，永久释放放在真正 destructive teardown 中。

## 检查点 2：最小修复及第一轮扩充

- 改动：AppShell 私有 attempt，调用方拿 copy，公开 future cancel 不影响私有结算；入口 suspend 增加代号，CANCELLED/提交前可恢复异常才 resume；原有 registry 不清空重建。文件永久清理在后台销毁实际启动后派发 FX，registry 保持 FX 线程所有权。
- 验证：`green-first` exit 0，首回归 1/1。`green-expanded` exit 1，4 项中 2 项失败：身份/保存等主体断言通过，两个夹具退出等待 10 秒 Timeout。所有首次日志、XML 保留。夹具退出工作区决策改为合成 IGNORE，避免退出时未控制的模态等待；没有增大 timeout。
- 边界修正：recent 工作执行前短锁判断代号，已准入写允许完成，磁盘 I/O 不持有 FX admissionLock。排队旧代写/读/error 在等待和恢复后均拒绝；不声称撤回已开始的原子元数据写。
- 下一步：以 blocked recent 验证 FX 不阻塞，并覆盖 fatal/启动失败/清理失败。

## 检查点 3：关闭提交后的启动失败

- 验证：`green-boundaries` exit 1，7 项中 6 项通过。唯一失败为当时错误预期 starter 失败可恢复，再开文件导致 FX 调用 Timeout。实际 ContentTabPane 已完成关闭并 sealed，不能再接受 managed tab；该失败是新的真实恢复边界发现，不能通过恢复已封闭 registry 规避。
- 改动：仅 AppShell 包装层根据实际 tab COMPLETED 结果设置 volatile 提交标记；此后异常统一 FAILED_PARTIAL，缓存私有 attempt 并保持暂挂。底层 AsyncShutdownCoordinator 不改。真正 pre-tab supplier 异常仍可恢复。
- 清理：整个 FX dispatch+join 是 BestEffort 步骤；FX 内 entry.close 与 registry.close 也 BestEffort，单步失败不阻止其余 owner 的资源释放。
- 失败：`green-final-boundaries` compileTestJava exit 1，缺 FxTaskRunner import；当时脚本误复制上一轮 XML，原件保留并用 `xml-provenance.json` 明确 stale/noTestsExecuted。`green-focused-final` compileTestJava exit 1，错误假设 runner 有 isClosed 方法；改为实际 submit 被 RejectedExecutionException 拒绝的行为断言。此轮未执行测试且没有归档 XML。
- 下一步：修正编译和 XML 归档规则，定向验证最终源码。

## 检查点 4：最终 focused 通过、交还执行权

- 验证命令：从该 worktree 执行 `./docs/superpowers/verification/evidence/app-shell-shutdown-recovery-worker/Run-Targeted.ps1 -Run green-reviewed`。实际参数在 parameters.json：JDK 25.0.1+8，Gradle `--offline --no-daemon --max-workers=1 --rerun-tasks --console=plain`，既有 isolation.init.gradle，新 UUID profile `datacube-shell-recovery-99ef5f8b-1765-4243-88c6-f6e564bec136`。
- 环境：后续脚本清除 live/Oracle/PG/Redis/DATACUBE_ 以及 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS/JAVA_OPTS/GRADLE_OPTS。首红与首绿使用较早清除集合，参数原件保留，不能倒称当时已使用更新脚本。
- 结果：exit 0，BUILD SUCCESSFUL 29s；AppShellShutdownRecoveryTest 9、AppShellTest 2、SqlScriptFileEntryTest 17，共 28 项、failures/errors/skipped 均 0。新 suite 没有条件 skip。source-hashes.json 保留执行前源码 SHA256；最终产品/测试未再改动。
- 新 9 项行为：取消后原 tab/dirty 文本/去重身份保留，实际 Save 和 Save As 写合成临时文件且新路径去重复用；等待拒绝新准入、迟到旧读/error 抑制、公开 future cancel 不恢复入口、重复取消及重试；排队旧 recent 不发布；blocked 已准入 recent 中 FX shutdown/pulse 返回；实际默认 PT5S 提示后 shell future pending、tab 仍在且 disabled、mock 关闭/UI finalizer 均 0，释放后均 exactly once 为 1 且 COMPLETED；pre-tab 异常恢复后旧文件复用/新文件可开；实际 tab 提交后 starter 失败与 guard FAILED_PARTIAL 不可重试；dispatcher.close 抛错后 registry.createOwner 抛 closed、global runner submit 被拒绝、仅 close 的合成缓存 Connection.close=1，结果 FAILED_PARTIAL 且重复调用不再 close。
- 证据准确范围：5 秒证明的是实际 AppShell/ContentTabPane 管理的可控合成 blocked resource；不是 DataGrid 保存事务/真实 JDBC，也不是物理 15 秒路径。fixture cleanup 中 IGNORE 是程序化决策，不能冒充真实用户工作区取消。原生窗口关闭、正式 DataCubeFx 启动器、工作区 Decision.CANCEL 保留 editor、DataGrid 物理等待与事务路径均未获本轮证据。
- 执行结束：所有 Gradle session 已 exit；未创建桌面独立 fixture 进程。测试中的虚拟 worker 均有界等待并释放，测试 JVM 随 Gradle 退出。仅 close mock 无数据库/SQL 调用；在途 synthetic close 与 UI finalizer 1/1；失败清理 cached connection 1 次。没有业务写入/执行；有明确授权的合成 SQL 文件保存和 recent/draft 测试元数据写入。
- 下一步：交父线程独立审查，运行其两阶段相关定向/全量/buildSrc/image 与 main 复验。本 worker 未运行全量/buildSrc 独立验证/image，未声称发布或 M8 完成。

## 文件与原始证据

- 产品仅 `src/com/datacube/fx/AppShell.java`。
- 测试新增 `test/com/datacube/fx/AppShellShutdownRecoveryTest.java`；`AppShellTest.java` 删除错误的关闭前 registry 必须清空源码断言，相关行为由新 suite 负责，保留 mandatory wiring 的现有文本检查。
- 本账本及 `evidence/app-shell-shutdown-recovery-worker/Run-Targeted.ps1`。
- 7 个独占 run 目录：red-first、green-first、green-expanded、green-boundaries、green-final-boundaries、green-focused-final、green-reviewed。各保留 parameters/raw/result；已执行测试的 run 含 XML（compile 失败的 green-final-boundaries XML 特别标明 stale）。最后 run 含三份有效 XML、source hashes、provenance。
- 归档脚本已防旧 XML：先移除 build 下精确选定 suite XML，只在 `:test` 实际执行且 XML mtime 晚于本次 start 时归档。最终 green-reviewed 在该增强前启动，但独立 provenance 核对其清理、task 标记及时间，三份均 current。
- `Verify-Evidence.ps1` 可独立重算 7 次 run 的 raw/task/XML/计数，排除两个 compile failure，核对最终源码哈希，并检查仅本轮 7 个 synthetic profile 所属的 Java 进程。2026-10-02T05:39:18Z 实际 exit 0，生成 `manifest.json`，最终源码哈希一致、当前 ownedProfileJavaProcesses 为 []。桌面与 Gradle 执行权已交还；不再启动验证进程。
