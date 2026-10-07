# SQL 在途整窗关闭：独立审查

## S0：基线、目标与已批准矩阵

2026-10-07。维护者继续推进产品；沿用 GPT-6.1-sol 开发、root 独立审查/修正/本地集成与 main 推送授权。main 57b044497175cf906fbf8dfc8254b768d07d63d2，检查范围干净；旧 Verify 37609086110 是历史基线，不作为本轮证据。复用现有干净 worktree，从该 main 建立 codex/sql-inflight-shutdown-20261007，不创建新线程或代理。

root 已独立读取真实 AppShell SQL 标签装配、SqlEditorPane mandatory close、SerialSessionOperationQueue、JdbcEditorSession、SqlExecutionControl 与生产 runner。SQL 的关闭先取消可取消的当前操作并等待物理 idle，再回滚未提交事务和释放连接；原 5 秒提示只是 STILL_CLOSING，SQL 没有 DataGrid 的 15 秒自动失败。当前未发现已复现的产品缺陷，不制造红灯或改写状态机。

已批准最小四例：PostgreSQL/Oracle × 取消后及时释放/取消已响应但物理执行跨原 5 秒提示。真实 AppShell → SQL 编辑器 UI → 生产 runner → mock JDBC；先以合成 DML 建立手动待提交事务，再阻塞执行，经原 mandatory guard 和 WindowShutdownController 发起关闭。验证队列封闭、排队操作不启动、取消请求、pending 中不提前回滚/释放、物理完成后 rollback/close/各级 finalizer 唯一次序以及迟到 progress/terminal 不更新 UI。COMMIT 非可取消整窗在途组合本轮不扩张，保留待验。只有出现可复现真实缺陷才最小修复。

worker 独占 Gradle，root 不并行执行。先定向与源码/原始 XML 审查，再批准冻结版全量、强制 buildSrc 和 jpackageImage；main 合并后独立新复验。此处协调脚本按旧审查流程复制到本轮独占前缀并解析，只是工具准备，不是产品验证。

边界：.testagent 禁读/改/枚举/暂存/清理；仅 mock、合成 SQL/profile 和独占临时目录，不访问原配置/凭据/历史/业务文件，不真库、不剪贴板/原生输入/安装更新/外部联系。只按既有授权更新 main，不 fetch/PR/新 tag；v3.2.9 对象和目标不动，datacube 保持 PAUSED。shown FX/程序化 handler 不是原生输入、系统缩放、正式启动器、安装升级或签名验收；完整 M8 不宣称完成。下一步审核新集成实现与首次定向证据。

## S1：首轮失败与夹具修正审查

root 独立核验 001-sql-inflight-targeted command/exit/实际 Task/XML：新 UUID profile 与真实 8.3 temp、compileTestJava 成功、test 实际执行，4/4 failure、0 error/skip、exit1。全部是 Fixture.visible 调用 report().summary() 的 NPE；真实 onExecute 已清空 batchResults，使 report 为 null。不能作为产品行为红灯，也未完成本轮矩阵。批准只使可见快照正确保留 nullable report，同时在前置 DML 成功时确认非 null，不降低其余状态/行/列/revision 比较或超时。

root 已读新增两个测试文件及真实 JdbcScriptExecutor：执行返回 updateCount73 后确实 publish 进度，queue 的真实任务线程结束后，JDBC rollback 屏障保留尚未 finalize 的 UI；此时实际可见状态应不变，随后放行回滚检查资源释放顺序。生产 runner/dialect、实际 UI 控件、mandatory guard 与整窗 handler 保留，关闭缓存资源为标明来源的 close-only mock。要求修正 cleanup 无条件 productAssertionsAlreadyRecorded=true 的不实日志，成功 SQL_EXIT 必须出现在 fixture cleanup 之前。当前未有本轮通过/全量/image/main/CI 结论。

## S2：四例首次完整绿灯与原件复核

002-sql-inflight-fixture-corrected exit1，4 tests/1 failure/0 error/skip。四条 SQL_EXIT 产品断言已到末尾，但首 PG 用例的 JUnit TempDir 清理因首次 FX native 缓存 DLL 锁定失败，root 已独立读到 .openjfx/cache/25+29 的 AccessDeniedException。不是产品缺陷，也不是整次通过。沿用现有 AppShellWorkspaceShutdownTest.preloadFxNatives，在本轮 runner 独占 home 先加载 FX；不跳过临时目录清理、不改生产超时。

003-sql-inflight-native-fixture-corrected 的 command/exit/实际 Task/新鲜 XML 已独立核对：4/4 执行通过、0 failure/error/skip，21 秒。保留真实 UI 手动模式/前置 DML，四条 SQL_EXIT 记录完整，两个慢例跨默认 PT5S 仍 pending，JDBC release→Statement close→rollback entered→晚更新 UI 不变→rollback complete→session close→global close。无隐式提交、后续/排队 SQL 不执行；实际 WindowShutdownController 只请求一次并正常关闭。终态 finalizer 观察原 boolean flags，与资源唯一次数分开表述；cleanup 日志已修正为明确非产品证据。

已继续既定 expanded 定向（原 session/queue/provider/guard/controller/AppShell 等），等最终字节冻结和全部原件审查后再批准 full/buildSrc/image。当前产品 src 无修改，仅新增两份 test/probe；完整 M8、原生与真库等未验边界不变。

## S3：最终定向与源码审核通过

004-expanded-targeted 新命令/实际 test Task/exit0/17套新鲜XML独立核验，184/184、0failure/error/skip，1m20。既有session、queue、两provider、SQL close、controller、quarantine、真实AppShell和managed registry的新执行结果与四例组合证据共同保存，旧结果未计入。

最终仅新增 AppShellSqlShutdownTest 与 ShellSqlJdbcProbe 两文件：root逐段审查，真实树入口、事务控件与执行按钮保留；原guard、SQL runner/dialect、实际progress与串行queue不替换。关闭前/跨PT5S/物理结算后各阶段分别断言，所有释放屏障在fixture退出时打开；末尾真实COMPLETED与资源计次先于显式fixture清理。原错误两轮源码副本保留。已建立独立 reviewed-source.json 并批准字节匹配后串行全量clean test、强制root buildSrc、强制jpackageImage。当前没有产品源码修改；未证明其他事务/原生/真库组合完成。

## S4：分支完整验证与提交

root 独立 Audit-Worker 重新统计实际任务和新鲜 XML：定向17套184通过；全量315套4016总项/4013通过/3live跳过；root buildSrc强制8通过，0failure/error。构建各为1m20、4m43、8s；jpackageImage强制14任务执行44s。三跳过为Redis standalone及Oracle/PG SchemaDiff live，原原因/名字保留，不记通过。两个最终测试、完整patch、runner哈希与冻结一致。runner精确字符串初次比较因多一个末尾换行拒绝；逐行及TrimEnd独立比较确认仅专用前缀和尾部换行差异，诊断归档，不改冻结原件。

两份新增测试已提交5b3d7172c5e6b61d49cba166ae37bd60d16625ab；生产src/resources/build脚本/workflow未改。root镜像审计passed：183文件、模块与cfg没有测试类/profile或测试JVM选项；仅Oracle/PG driverFor发现，connectCalls=0，未调用connect/open。exe595968字节、cfg369、modules102405401；SHA分别6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F、E53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D、06D4956F06D5B54C4EB6D7633C735BBADFDDA20F30D4B243C2B4D14BEA5D160D。probe与driverFor源码再次独立读取确认无连接。

378项worker原件/报告清单已逐项长度和SHA独立复核，包含两次失败及其源码，待进一步冻结Git原字节。当前只是分支验证，main复验、推送和精确SHA CI尚待；不把mock组合结论扩张到真实驱动、非可取消COMMIT、原生或完整M8。
