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
