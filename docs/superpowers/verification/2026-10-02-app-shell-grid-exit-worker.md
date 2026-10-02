# 完整 AppShell DataGrid 在途退出：N1 worker

2026-10-02。worktree `C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾`，分支 `codex/app-shell-grid-exit`，开发基线 `f154c62c6f2e567110437791b2e889e3acffabf1`。完整读取本轮计划后实施；受限权限下所有 worktree 写入/Gradle 经 require_escalated 自动审核。未改产品，未暂存/提交/合并，无桌面 fixture 或原生动作。

## 检查点 1：实际路径与安全 mock
- 目标：实际 AppShell.treeActions.openDataGrid → DataGridPane → DataEditService → JdbcDataEditor 事务链及真实 mandatory close。
- 改动：新 AppShellGridShutdownTest 六组 PG/Oracle 合成类型 × 首行/第二行在途/物理超时。反射仅注入实际 manager.providerResolver、被动取得生产 mandatory 方法引用捕获的实际 pane 和 coordinator；没有另造 ContentTabPane、替换 guard、改 15 秒常数或产品 public seam。
- mock：独立 ShellGridJdbcProbe，记录使用 CopyOnWriteArrayList/AtomicInteger，按 cached connection 与逐行 dedicated connection 分开 close计数与 trace。每 statement bindings 同步 map，实际准备/执行顺序由一个 JDBC worker 串行写；观察在 entered/SaveAttempt.settled 的 happens-before 后。去掉旧夹具未用 faults/counts/onCommit；无真实驱动加载、网络或真库。
- 失败：首次 apply_patch.bat 参数超过 Windows 8K；stdin 被引擎拒绝（requires PATCH argument）；一次合并更新 patch 超过 Windows 32K。均为工具写入失败，不是权限审核拒绝、编译失败或产品红。原原因记录在 tool-errors.json；直接同一 Codex apply_patch 引擎、按文件拆分参数后成功。首次大 patch 没有写出测试。
- 下一步：先固定六组执行，按实际证据决定是否修产品。

## 检查点 2：首轮与审查补强
- 验证：Run-Targeted.ps1 -Run first-sixcases，exit0，BUILD SUCCESSFUL 1m13s，新 suite 6/6、零skip/error/failure。原 raw/XML/参数/hash 保留。两个真实 awaitClose 超时分别约15秒，不注入时长。
- 主体证据：每组实际 close 触发 JDBC interrupt；真实 PT5S warning 后 close.pending/SaveAttempt.settled未完成，生产 closeAttempt.status=STILL_CLOSING，实际tab还在且disabled；cached浏览连接与当前写lease close均0，global runner仍接受任务。首行在途释放后execute[1]/commit[]/rollback[1]；第二行在途 execute[1,2]/commit[1]/rollback[2]，第三行未执行。成功全局清理后每个连接close=1。
- 审查补强：release后的FAILED_PARTIAL仍缓存，actual tab contained+disabled/registry managed、finalizer=false，cached connection不提前close；成功路径直接断言tabremoved/registryunregistered/finalizer=true；scope确实closed，迟到状态/rows不应用、不自动page/save。构造失败也finally式释放barrier并有界清理。
- 验证：reviewed-sixcases，exit0，BUILD SUCCESSFUL 1m12s，6/6零跳过/失败。未复现生产缺陷，因此仅补测试与证据。
- 下一步：将构造失败中pane与shell清理分成BestEffort步骤，防缺pane异常跳过global资源释放；运行三组最终focused。

## 检查点 3：最终 focused
- 改动：仅补新fixture构造失败的BestEffort清理，主失败原样保留、cleanup失败作为suppressed；Run-Targeted扩为AppShellGridShutdownTest、DataGridSaveFlowTest、AppShellShutdownRecoveryTest。
- 命令：从上述worktree执行 ./docs/superpowers/verification/evidence/app-shell-grid-exit-worker/Run-Targeted.ps1 -Run final-focused。实际JDK/参数/新UUID profile见该run parameters.json；offline/no-daemon/max-workers=1/rerun-tasks/console=plain，既有sol-p0-p2 isolation.init.gradle，清除live/Oracle/PG/Redis/DATACUBE_与外部JVM/Gradle注入环境。
- 结果：final-focused exit0，BUILD SUCCESSFUL 1m19s；实际XML为AppShellGridShutdownTest 6、DataGridSaveFlowTest 10、AppShellShutdownRecoveryTest 9，共25项而非预估32项，failures/errors/skipped均0。源文件实际Test声明与XML执行数一致，无编译失败或陈旧XML复用。脚本先删build中精确suite XML，只在实际 :test执行且mtime>=本次start时复制。
- 最终真实物理等待：POSTGRESQL 15004ms，ORACLE 15001ms；FAILED_PARTIAL前后cached浏览连接close0、global runner未teardown、当前write lease未提前释放，确认失败后才release。迟到rollback当前行、write lease close1，但tab仍contained/disabled/managed、finalizer=false且shell仍FAILED_PARTIAL。fixture随后才清理自己的cached/global资源，全部连接close1。
- 最终profile：datacube-shell-grid-worker-52fb2206-de68-439e-9349-fcaf72d3a2d7；实际start 2026-10-02T09:59:37.8118912Z，finish 10:00:57.7574321Z。
- Verify-Evidence.ps1实际exit0，于10:01:38Z生成manifest：三次真正test执行、XML mtime/hash/计数有效，最终源码hash一致，ownedProfileJavaProcesses=[]。三个Gradle session均已exit，没有独立桌面fixture。当前Gradle/桌面执行权交还根线程，不再启动验证。
- 后续：根线程进行全量/buildSrc/image与main两阶段，worker不代报通过。本轮仅新测试/夹具和worker证据，git diff --quiet -- src实际exit0、git diff --check排除.testagent后通过，产品保持基线。

## 准确范围与未验
PG/Oracle是合成provider类型，JdbcDataEditor为真实实现，但不等于真驱动/真库覆盖。保存确认以程序化FX Button.fire进行，明确不计原生交互；没有正式DataCubeFx启动器或发布证据。失败确认后才release JDBC；其迟到Callable finally完成由真实SaveAttempt.settled证明，绝不用FutureTask.cancelled/isDone替代物理结束。产品FAILED_PARTIAL不变；随后仅fixture直接清理自身owner，不能冒充产品恢复。

mock物理barrier35秒截止且finally release；FX/settled/cleanup调用均有界，不sleep/retry/扩大产品timeout。测试仅合成数据/UUID profile，SQL UPDATE仅mock，未访问原配置/凭据/历史/业务文件或.testagent。没有真实业务写入。

新文件：test/com/datacube/fx/AppShellGridShutdownTest.java、ShellGridJdbcProbe.java；本账本；worker evidence下Run-Targeted.ps1、Verify-Evidence.ps1、tool-errors.json、manifest及三个run目录。raw.log受gitignore，根归档时仅精确force add这些worker raw路径。产品源码保持基线。

## N1 追加检查点：全量首失败后的独立定位（起点 e7f55b7）
- 根第一次full：312 suites、3934 tests、2failure/0errors/3skip；仅MetadataSearchShellRoutingTest两项在Fixture.close断言COMPLETED得到CANCELLED。实际grid六项通过。根原件已按字节归档于app-shell-grid-exit-coordination/branch-first-failed/full及full.log，本worker不覆盖。
- 隔离审查：该Metadata Fixture每case在构造AppShell前把user.home设为独占JUnit TempDir，finally恢复；没有新grid跨profile污染证据。失败两项最终SELECT刚创建真实SQL pane即退出。SqlDraftCoordinator初始化为异步；INITIALIZING eligible.flush会拒绝，SqlDraftEditorBinding.prepareClose(true)正确返回REJECTED→CANCELLED。既有初始化语义测试源码支持这个保护约定，旧通过不作本轮新证据。
- 外置诊断使用原private Metadata Fixture的真实菜单/SELECT、AppShell/SqlEditor/mandatory guard，仅控制actual draft runtime初始化writer；同步替换同一owner的workspace UI以绑定该受控runtime，原空runtime先正常shutdown。所有操作只在新合成profile。受控writer30秒截止/finally release，初始化事件与refresh barrier均有界；未换mandatory guard、未忽略workspace。
- diagnostic-shell-routing-red：exit1，No tests found，build.gradle覆盖过早添加的外置sourceDir；actualTestsExecuted=false，无XML。projectsEvaluated再加外置sourceSet后恢复。原log/result保留并附provenance，非预期行为红。
- diagnostic-initializing-red：实际1test/1failure，原Fixture.close:529同样COMPLETED→CANCELLED，日志mode=INITIALIZING、managementPending=true、flushFailure=INITIALIZING；有JUnit native DLL TempDir清理suppressed。diagnostic-released-green：主体同一fixture.close=COMPLETED，但native字体/effect DLL在TempDir，JUnit清理主失败exit1，因此不称完整通过。两run原件全部保留。
- 给外置diagnostic加BeforeAll，仅在worker profile预热一个合成Label字体/effect；其内存snapshot不是桌面内容/截图，也无原生验收价值，不保存图像。
- diagnostic-initializing-clean-red：exit1，1test/1failure、0skip/error；唯一原Fixture.close:529 CANCELLED，无TempDir清理污染。日志直接证明INITIALIZING/busy=true/flushFailure INITIALIZING，finally释放后barrier ENABLED/nonbusy、正常closeCOMPLETED。diagnostic-released-clean-green：相同源文件/实际guard，只提前release并await observe初始化→refresh成功barrier，exit0、1/1，无skip。该对照证明夹具缺少真实初始化前置条件；不是产品缺陷。
- 历史归因限制：首次full原XML没有现场runtime.mode/flushFailure，不能百分百反查那两次的具体状态。新受控诊断确定复现同类原因，与快速SELECT后立即关闭的原失败位置一致；未把新cause伪写成旧现场。没有证据表明跨profile污染。
- 最小修正仅MetadataSearchShellRoutingTest.Fixture.close：peek已创建owner；observe其真实初始化结束，严格断言ENABLED、managementPending=false；actual runtime.refresh成功且snapshot可写之后才按原mandatory shutdown。初始化失败直接失败，不吞异常。原COMPLETED/连接平衡/写0断言完整保留。无sleep/retry、无timeout扩大、无guard或product改动。
- correction-focused使用新UUID profile，四suite为新grid、Metadata、AppShell恢复、DataGridSaveFlow；source-hashes包含修改Metadata文件。外置诊断init未加入此最终focused，不将历史诊断case误算常规tests。运行结束后原件审计仅写manifest-correction.json，已提交的原manifest及所有旧run字节不覆盖。
- 重现脚本的适用版本：diagnostic红/绿基于修正前Metadata fixture，源commit e7f55b7与每run source-hashes可对账；修正后原Fixture.close会主动等待初始化，不能把再运行旧诊断的行为当成历史重现。外置源目录仅由diagnostic.init.gradle加入，不改变普通/full test sourceSet。
## N1 追加交付：correction-focused 已结算

最终命令：`& ./docs/superpowers/verification/evidence/app-shell-grid-exit-worker/Run-Targeted.ps1 -Run correction-focused`。实际参数保存在该 run 的 `parameters.json`：JDK 25.0.1+8、offline/no-daemon/max-workers=1/rerun-tasks，四个 suite 定向，独占 UUID profile `datacube-shell-grid-worker-91ccf026-86a0-41e5-8e2e-dcc1b9174049`。开始 UTC 10:23:39，结束 UTC 10:25:21，exit 0；fresh XML 实际为 Grid 6、Recovery 9、DataGridSaveFlow 10、MetadataSearchShellRouting 44，共 69/69，failure/error/skip 均 0。

执行 `Verify-CorrectionEvidence.ps1`，exit 0，新写 `manifest-correction.json` 汇总六次增量运行；原 `manifest.json` 与提交 e7f55b7 保持一致（SHA-256 `653F7F3F17CDB4E00DD02B05B94A303CAE62A1A05268EF5852FF1BC953C394FF`）。七项源码哈希全部匹配；旧 XML 不计入发现失败。原首次 full、诊断失败及 native DLL 清理失败均保留。`git diff --check` 通过，按明确 src 路径与 e7f55b7 比较无产品修改。

归因边界不变：受控真实 runtime 初始化证明同类 INITIALIZING → flush 拒绝 → actual mandatory close CANCELLED；历史两例未保存现场 mode，不能百分百确认历史状态。只为旧 Metadata fixture 补实际初始化/refresh 成功前置条件，未改产品保护或超时，未追加盲 retry。

最终自有合成 profile Java 进程为 0，测试 JVM/Gradle 已退出，无独立桌面 fixture；桌面与 Gradle 执行权交还根线程。未执行全量/buildSrc/image/main 新复验，未暂存或提交。下一步由根线程做独立工程复验与精确归档。
