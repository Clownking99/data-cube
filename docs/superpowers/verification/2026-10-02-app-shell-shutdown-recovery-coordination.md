# DataCube 完整 AppShell 关闭恢复：独立审查与集成

客户端日期：2026-10-02；起点 main `4d1940aa1506be61026a4419de777e7cc4c83fe5`。依据[限定计划](../plans/2026-10-02-app-shell-shutdown-recovery.md)，复用 GPT-6.1-sol 代理与独立 codex worktree；当前线程负责审查及 main 本地交付。既有 heartbeat 保持 PAUSED，无新增线程、真库或外部操作。

## N0：基线与计划

- 当前目标：补齐完整 shell 关闭/可恢复取消/超时所有权的本地证据，并修复行为复现的问题。
- 改动：新分支 `codex/app-shell-shutdown-recovery`、限定计划及本轮验证脚本；未更改功能范围。
- 验证：main 和 worktree HEAD 相同，授权范围干净；脚本语法通过，记录在 [N0](evidence/app-shell-shutdown-recovery-coordination/n0-baseline.json)。旧测试不充当新证据。
- 失败/未验：正式启动器、完整原生输入/键盘、小窗口、其他真库、安装升级/签名/CI/用户任务/发布待验仍保留。
- 下一步：代理建立真实 AppShell 红绿复现，当前线程独立审查。

## N1：首红与审查发现

- 当前目标：取消退出后恢复文件入口且保留文件身份，关闭中拒绝旧代回调。
- 改动：代理新增实际 AppShell/SQL 文件 pane 的行为测试，以受控 dispatcher 与合成 mandatory guard 的 REJECTED 路径触发 CANCELLED；属于合成 FX，不能称原生或真库。
- 验证：首轮 1 项实际失败，断言“cancelled shutdown must preserve the original file identity”得到 false；原 XML/日志保留。当前线程独立阅读原始 XML、真实 shell/file registry 与 source diff，确认提前永久关闭可以造成该行为。独立哈希采样时产品草稿已经变化，不能宣称独立冻结了首红源码。
- 失败/未验：首次独立 JSON 的单 XML node 管道未提取 case 详情，保留 [原审计](evidence/app-shell-shutdown-recovery-coordination/n1-red-independent-review.json)，以 XPath [纠正审计](evidence/app-shell-shutdown-recovery-coordination/n1-red-corrected-review.json)确认原断言；不修改原 XML。代理首脚本环境隔离缺 JAVA_OPTS/GRADLE_OPTS，已要求后续补齐，首命令保留。
- 下一步：最小暂停/恢复、代 token 与私有 attempt 边界；永久文件清理只能进入实际 destructiveTeardown 后执行。公开 future 的 cancel 不得重新打开仍在关闭的入口。

## N2：审查修正通过，集成验证待执行

- 当前目标：审查修复的关闭线程、迟到回调、取消重试和 best-effort 资源释放。
- 具体修正已下发：初稿在 recent 文件磁盘写期间持有 admissionLock，FX suspend 会等该锁；要求避免慢磁盘阻塞 FX，并用受控在途 recent 写验证及时返回。关闭之前已经开始的元数据写可以安全结算，尚未开始的旧代记录必须拒绝，边界须准确记录。
- 具体修正已下发：初稿 filesClosed.join 在 BestEffortCloseSequence 外，文件关闭失败会跳过剩余清理；要求纳入 best-effort 步骤并确认失败归 FAILED_PARTIAL。
- 新实证与修正：首个 starter failure 测试原以为可恢复，实际标签已 COMPLETED/sealed，开新文件产生 Timeout；按提交状态在 AppShell 将之后的异常归 FAILED_PARTIAL，保持隔离。底层 coordinator 不改；新 pre-tab supplier 失败测试证明未提交关闭时可以恢复文件准入。
- 当前验证：独立审查最终源码、测试与所有原 XML/日志，最终 focused 3 suites 共 28/28、0 failure/error/skip，执行前源码哈希与当前工作副本一致；7 个独占 profile。明确排除两次 compileTestJava 失败的 XML，第一次陈旧副本保留。原始实证见 [worker 账本](2026-10-02-app-shell-shutdown-recovery-worker.md) 和 [独立审计](evidence/app-shell-shutdown-recovery-coordination/n2-worker-review.json)。
- 审查结论：产品仅 AppShell 一处；暂停不清空原 registry；代 token 拒绝旧读/error/排队 recent；已准入元数据写允许结算且不阻塞 FX；私有 attempt 的公开副本取消不撤销真实关闭；只有可恢复结果重开入口；永久 FX 文件清理嵌入 best-effort，单项错误也尝试全局资源释放。新增行为测试替换原错误关闭顺序文本断言，不靠删断言掩盖失败。
- 审计失败/修正：根线程首次独立 totals 使用 OrderedDictionary 的 Measure-Object 返回空值，审计拒绝，不是产品失败；复现并保留[转录](evidence/app-shell-shutdown-recovery-coordination/n2-audit-first-rejected.json)，用独立脚本显式累加后 28/28 准确核实。缺失 worker .gitattributes 的读取错误后只新增本轮 raw 目录字节保留规则，未改变任何原始证据。
- 证据等级：没有本轮原生或真库运行；默认 5 秒证据只限实际 AppShell 管理的合成 blocked resource。真实工作区 Decision.CANCEL、实际 DataGrid 整体退出/物理 15 秒/事务、正式 DataCubeFx 启动器和既有发布待验保留。
- 下一步：当前线程取得桌面/Gradle 执行权；本地提交复现驱动的修复，再运行分支相关定向/全量/buildSrc/jpackageImage，完成审查后合并 main 并重新验证。未完成或失败的验证不能标通过。

## N3a：修复分支验证通过

- 当前目标：验证独立审查通过的修复提交，再准备 main 本地集成。
- 改动：本地实现提交 `404c3ebd597fe4eeeb4df5fd0d5bca621d608498`；58 个文件经 staged blob/raw 字节审计，全部原证据精确保留，源码只允许已证明的 LF/CRLF 差异。
- 实际验证：新 UUID profile、JDK 25.0.1+8、offline/no-daemon/rerun-tasks；定向 12 suites 107/107，全量 311 suites、3928 total = 3925 passed + 3 live skipped，failure/error 均 0；buildSrc 8/8，jpackageImage exit 0。每次实际 test task/本次 XML 时间和所需 suite 完整性均核对，775 个源码文件冻结且执行前后不变。镜像 0 测试类型/文件/参数泄漏，外置 driverFor 发现 Oracle/PG 驱动且 connectCalls=0，不连接数据库。
- 证据：[分支原始目录](evidence/app-shell-shutdown-recovery-coordination/branch/)，包含全部 XML、参数、退出码、源文件哈希、镜像文件/模块索引与驱动发现。
- 失败/未验：3 live 跳过不计通过；本轮没有新原生或真库。main 尚未复验；工作区真实决策、完整 DataGrid 在途事务/15 秒、正式启动器、其他 M8/发布缺口继续待验。
- 下一步：再次核对 main 起点/授权范围干净后本地合并，再用独占 profile 重跑定向/全量/buildSrc/镜像；不推送或发布。

## N3b：main 复验与限定交付

- 当前目标：完成本轮修复的本地交付，准确更新实际证据和未验项。
- 改动：证据提交 `3f1ddb513eeef9c0a2b27e9a74b15c552998f18e`；再次核对原 main/授权范围干净，本地 no-ff 合并 `523d23456642f430b0528865055ca0ef867839a5`。待最终证据提交只更新 docs，不改已验证产品/测试/build 树。
- 新实际验证：main 独占 profile 定向 107/107，全量 3925 passed/3 live skipped、buildSrc 8/8、jpackageImage 全部通过，failure/error 均 0。两个镜像各 183 个文件、14 个实际任务全部执行；测试类/夹具/文件/参数泄漏均 0，Oracle/PG driverFor discovery 各 connectCalls=0。三项产物 SHA（exe、cfg、runtime modules）两边一致。
- 源码与证据：775 个源码/测试/build 文件执行前后稳定，Git 树一致；214 个 Java 文件的跨 checkout 差异严格证明仅 LF/CRLF。完整 [两阶段结果](evidence/app-shell-shutdown-recovery-coordination/results.json)、[源码对账](evidence/app-shell-shutdown-recovery-coordination/source-comparison.json)、[main raw](evidence/app-shell-shutdown-recovery-coordination/main/)、[本地集成](evidence/app-shell-shutdown-recovery-coordination/main-integration.json) 可审查。最终 raw manifest 和 staged 字节审计覆盖两阶段及首失败，不混用旧测试或把 skip 算通过。
- 审计失败/修正：首次 summary 的相对路径被前一个 image 脚本 Set-Location 改到 main，而新证据在 worktree，路径检查失败；保留[工具输出转录](evidence/app-shell-shutdown-recovery-coordination/summary-first-path-error.json)，以绝对路径执行原脚本后完成汇总，没有重复/覆盖测试或镜像证据。
- 当前结论：取消退出破坏 SQL 文件入口/身份的已复现缺陷完成本地修复；等待中的旧代读/error/排队 recent、公开副本 cancel、提交前异常恢复、提交后与清理失败隔离均有新行为验证。默认 5 秒提示仍 pending 的完整 AppShell 合成资源证据可用，释放后 cleanup/finalizer exactly once。
- 失败/未验：3 live 测试仍跳过，不是通过；未新访问 Oracle/PG/Redis，既有 Oracle 表未触碰。未取得本轮原生退出、真实工作区 Decision.CANCEL、完整 AppShell DataGrid 在途事务/物理 15 秒、正式启动器证据；字段原生输入/键盘/多结果/失效/小窗、OS 多屏、安装升级/签名/CI/用户任务/发布仍待验。M8/发布不称完成，不能从本轮合成资源测试升级到这些证据。
- 下一步：交付本轮修复和证据，既有 datacube heartbeat 实查为 PAUSED 且未修改。下轮由维护者指定继续的验收项；不自动扩大功能/外部范围或创建线程。
