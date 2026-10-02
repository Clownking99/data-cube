# DataCube 完整 AppShell DataGrid 在途退出：独立审查与集成

客户端日期：2026-10-02；起点 main f154c62c6f2e567110437791b2e889e3acffabf1。依据[限定计划](../plans/2026-10-02-app-shell-grid-exit.md)，复用既有 GPT-6.1-sol 代理和独立 codex worktree，当前线程独立审查及本地集成。无新增用户线程，无原生或真库操作；datacube 跟进本轮只读实查为 PAUSED。

## N0：基线与独立源码审查

- 当前目标：实际 AppShell 生产 openDataGrid 构造下，补齐在途事务、默认 5 秒提示和真实 15 秒物理等待失败的行为证据。
- 改动：codex/app-shell-grid-exit、限定计划和 5 个独立验证/审计脚本。新隔离 root 以 UUID 命名；脚本语法解析通过，[N0记录](evidence/app-shell-grid-exit-coordination/n0-baseline.json)。此时没有产品源码修改。
- 验证：main/worktree 同 HEAD 且授权范围干净；独立阅读实际 AppShell TreeActions、DataGridPane SaveAttempt、DataEditService prepareSave、ConnectionManager dedicated write、JdbcDataEditor runGuarded、FxTaskScope、AsyncTabCloseGuards/Coordinator 和 managed registry。
- 独立源码结论：实际 dedicated 写连接由逐行 try-with-resources 所有，cached 浏览连接由全局 manager 所有；关闭 scope 中断执行，runGuarded 在 commit 前检查中断并尝试 rollback。SaveAttempt settled 表示物理 Callable finally，不能拿 FutureTask 的取消状态代替。默认 5 秒只改变关闭提示，blocking guard 的真实 15 秒失败归 FAILED_PARTIAL；源码设计不算本轮行为已经验证。
- 失败/未验：几次初次查读使用了旧目录猜测，实际文件经 rg 确认后读取；属于只读检索错误，没有测试/产品失败。旧验证不充当新证据；当前全部新用例待执行，原生退出、正式启动器、工作区真实 CANCEL、其他 M8/发布缺口继续未验。
- 下一步：代理建立两个合成 provider 类型的真实 shell/DataGrid/JdbcDataEditor 用例，根线程审查原始结果和源码，只对复现问题作最小修复，然后在分支与 main 分别重新验证。

## N1–N2：真实路径新证据与独立审查通过

- 当前目标：审查完整 shell 的在途行事务、超时隔离及迟到 UI，不重复实现已有功能。
- 改动：代理新增 AppShellGridShutdownTest 和线程安全 ShellGridJdbcProbe，未改 src/resources/build。真实 treeActions.openDataGrid、实际 pane/service/JdbcDataEditor 与 production mandatory guard；反射注入仅 synthetic resolver/被动观察。没有替换 guard 或 timeout，也没有新的产品 public seam。
- 实际验证：三次独占 profile，首轮 6/6、审查补强 6/6、最终三 suites 25/25（6+9+10），零失败/错误/跳过。独立读原 XML/日志、参数及最终源哈希，原始等待 PG 15004ms、Oracle 15001ms。实际默认 PT5S warning 后 pending/STILL_CLOSING；首行在途 rollback[1]，第二行在途 commit[1]/rollback[2]，第三行未执行，dedicated/cached分别计数。
- 具体返工：增加迟到 JDBC settled 后 tab contained+disabled/registry owned/finalizer=false、原 FAILED_PARTIAL 不转成功与 cached浏览不提前close；成功路径 tab removed/registry unregistered/finalizer invoked；夹具构造失败采用 BestEffort 分开 pane/global 清理并保留主失败；删去不使用的 fault 机器，绑定记录同步化。最终源码与原始证据独立审查通过，worker交还执行权，自有 Java 进程0。
- 失败/未验：首次 bat 8K、stdin不支持PATCH参数及合并patch32K长度错误均为写入工具失败，原原因在worker tool-errors.json，不是自动审核拒绝/产品红。根审计脚本已成功，但外围命令误以原生 LASTEXITCODE 判断无原生返回的PS脚本而exit1；[原错误](evidence/app-shell-grid-exit-coordination/n2-audit-command-first-error.json)保留，用原已成功JSON及终止错误语义继续，没有重跑测试或改原件。没有原生/真驱动/真库/正式launcher/发布证据；fixture最后直接清理不算产品从FAILED_PARTIAL恢复。
- 下一步：根线程取得单一Gradle执行权，本地提交新增行为测试/原件，分支与main各新定向/全量/buildSrc/image及镜像隔离/零连接审计，完成本地交付和实际待验对账。

## N3 首次全量失败、确定性诊断与修正审查

- 当前目标：完成全量门槛，保留失败而不是凭成功复跑抹去问题。
- 实际验证：实现 e7f55b7 上新定向14 suites 103/103；首次全量312 suites、3934 total =3929 passed+2 failures+3 live skips，errors0/exit1。两失败均MetadataSearchShellRoutingTest旧Fixture.close:529预期COMPLETED得到CANCELLED，新DataGrid六场景通过。333份首阶段文件整体归档为[branch-first-failed](evidence/app-shell-grid-exit-coordination/branch-first-failed/)，逐文件哈希不变；未合并main。
- 独立诊断：实际SQL mandatory close在draft INITIALIZING时保守拒绝；外置诊断只控制实际runtime初始化，复用原private Metadata Fixture、实际AppShell/SqlEditor/原guard。干净红1case/1failure与原529同断言，mode=INITIALIZING/busy=true/flush reason=INITIALIZING；释放初始化后observe+真实refresh屏障到ENABLED/nonbusy，原close COMPLETED，干净绿1/1。明确受控复现为PG合成案例；历史全量两例没有记录现场mode，Oracle同机制是源码推断，不能把诊断状态回填历史现场。未发现跨profile污染或需改产品的证据。
- 最小修正：只在Metadata Fixture关闭前peek已创建draftOwner，observe实际初始化退出，严格断言ENABLED/非busy、实际refresh成功/可写，再执行原mandatory shutdown及COMPLETED/连接平衡/写0断言。无盲retry、ignore、延长超时或guard替换；产品初始化拒绝保留。
- 实际复验与审查：worker四suite新profile69/69（6+9+10+44）零失败/错误/跳过，7源哈希稳定，根独立审查补丁/原XML/受控红绿；original manifest仍为提交e7f55b7原SHA，增量用manifest-correction，自有Java进程0，执行权再次交还根。[诊断审查](evidence/app-shell-grid-exit-coordination/n3-initialization-diagnosis-review.json)、[修正原件审计](evidence/app-shell-grid-exit-coordination/n3-correction-worker-review.json)。
- 工具/待验：诊断首run No tests found没有实际XML/case，不算预期红；未字体预热的两诊断run有Windows原生DLL TempDir清理失败，绿主体完成但整run不算通过，全部原件保留，采用既有@BeforeAll预热后才取得干净红/绿。根首诊断JSON用XML adapted属性遇null，保留原工具错误并改XPath，未改原件或重跑测试。[工具错误](evidence/app-shell-grid-exit-coordination/n3-diagnosis-audit-first-error.json)。正式启动器/原生/真库/M8发布待验没有提高等级。
- 下一步：提交精确fixture修正和全部增量原件，新的验证namespace冻结修正后源码，17个相关suite与全量/buildSrc/image全部重跑，再合并main并重新验证。

## N3a：修正分支全量、镜像与集成门槛

- 当前目标：以已审查的修正提交 d67bb37a1c89ca9c495b20834eaa2668fde91fe0 重新验证全部本地门槛，决定是否合并。
- 改动：产品源码仍无改动；冻结777份源码/测试/build文件，新UUID隔离namespace，不覆盖branch-first-failed的333份原件。
- 实际验证：新定向17 suites 184/184；全量312 suites、3934 total=3931 passed+3 live skipped，零失败/错误；Metadata路由44/44及新增Grid6/6均实际执行。buildSrc8/8。定向/全量各8 tasks、buildSrc4 tasks、jpackageImage14 tasks均executed，无up-to-date替代。
- 镜像审查：183文件，358测试类型隔离审计无class/file/option泄漏；Oracle/PG driverFor零连接发现通过，connectCalls=0、不加载凭据或原profile。777源文件阶段内稳定、source scope干净；三项产物SHA见branch/image-audit.json。
- 失败/未验：本次门槛无失败；全量3项Redis/Oracle/PG live因未配置明确写门禁/目标而跳过，跳过不算通过。首次两项全量失败和受控诊断红/工具失败原件继续保留。本轮仍仅合成FX/JDBC，无原生、真库、正式launcher或发布验收。
- 下一步：提交本轮独立证据，复核main起点干净后本地no-ff合并；以实际main合并代码新profile重跑定向/全量/buildSrc/image和独立审计，结果通过后更新交接和待验项。

## N3b：main新复验与本轮交付

- 当前目标：交付限定的完整AppShell合成DataGrid在途退出证据，不自动扩展功能或恢复跟进。
- 改动：实现e7f55b726cc7990367200c0b507eb57909d44def、旧测试夹具初始化修正d67bb37a1c89ca9c495b20834eaa2668fde91fe0、独立分支证据911e5c5673463c76c4ccb8bb136cb126ba8fceff。本地no-ff合并main代码ecbc421a899a17ef04eac02582402f7473591d20；本节和最终集成只改文档/证据，受验src/test/resources/build树保持一致，产品未改。
- 实际验证：main新独占profile定向17 suites 184/184、全量312 suites 3931 passed/3 live skipped、buildSrc8/8，零失败/错误；各实际8/8/4 tasks。jpackageImage14 tasks，183文件镜像、358测试类型隔离及零连接driverFor发现审计通过，Oracle/PG connectCalls=0；三项产物SHA与分支相同。777源文件稳定，216 Java跨checkout差异逐字严格确认仅LF/CRLF；[最终汇总](evidence/app-shell-grid-exit-coordination/results.json)、[源码比较](evidence/app-shell-grid-exit-coordination/source-comparison.json)。
- 行为证据：main定向实际首行回滚、第二行在途保留第一行提交/回滚第二行/不执行第三行。默认5秒warning仍pending/STILL_CLOSING、资源仍归原tab所有；真实物理等待PG15004ms/Oracle15002ms得到FAILED_PARTIAL，仍执行的lease/cached浏览连接不提前关闭。物理结算后仅迟到当前行回滚和dedicated连接释放，不把原FAILED_PARTIAL变成功，tab仍owned+disabled/无finalizer；夹具清理明确不算产品恢复。记录见main/directed/TEST-com.datacube.fx.AppShellGridShutdownTest.xml。
- 失败/未验：首次全量2failures原件/333文件归档、受控诊断干净红及此前工具/清理失败均保留；受控PG证明初始化拒绝机制，历史现场mode仍未知，不把源码推断写成当时观测。全量3项live跳过明确不计通过。本轮只有程序化合成FX/mock JDBC；没有原生交互、真实驱动事务/真库、正式launcher、FAILED_PARTIAL后产品恢复或发布验收。工作区真实CANCEL、字段原生输入/全键盘/多结果/失效/小窗、OS多屏、安装升级/签名/CI/用户任务等待验保留，M8继续部分完成。
- 下一步：本轮限定目标已完成，更新计划/交接/路线图和原始文件哈希、暂存字节审计后本地集成证据。最终HEAD为受验main的文档/证据后继，源码一致以只读复核确认，不以旧测试充当新的执行。datacube已只读实查PAUSED，两个独占验证namespace的Java进程0，无新真库/外部动作；既有Oracle专用表不访问/清理，不自动启动更多线程或下一轮。
