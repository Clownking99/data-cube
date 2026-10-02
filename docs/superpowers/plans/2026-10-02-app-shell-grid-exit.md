# DataCube：完整 AppShell DataGrid 在途退出验收

客户端日期：2026-10-02。维护者要求继续产品验收，沿用既有 GPT-6.1-sol 实施、当前线程独立审查、必要修正、本地提交和 main 合并复验的授权。

## N0：基线、范围与分工

- 当前目标：补齐真实 AppShell 生产 openDataGrid 路径下，在途保存退出的事务、资源所有权、默认 5 秒提示和真实 15 秒物理等待失败证据。
- 改动：从 main/worktree 相同的 f154c62c6f2e567110437791b2e889e3acffabf1 建立 codex/app-shell-grid-exit；复用 C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾。已有关闭恢复实现不重复开发。
- 验证：两边授权范围干净；阅读上轮计划/原始审查结论及 AppShell、DataGridPane、DataEditService、JdbcDataEditor 和既有保存测试。真实树动作字段为 treeActions，实际 JdbcDataEditor 位于 provider/jdbc，GridSaveProbe 位于 test/service。
- 失败/未验：本轮尚无新测试证据；旧 5 秒测试使用合成受管资源，不能证明真实 DataGrid 或事务；真实 15 秒等待失败此前未验。源码路径初次查读失败不算测试结果。
- 下一步：复用 GPT-6.1-sol 实施 N1，当前线程 N2 独立阅读源码和原始 XML/日志并下发具体修正，N3 完成分支与 main 新验证和本地交付。

代理只实施限定测试、必要最小修复及 worker 原始证据，不提交、不合并、不修改路线图和交接。桌面/Gradle 同时只有一个执行者；代理交还后根线程复验。每个检查点记录目标、改动、验证、失败/未验、下一步；保留首次失败原件，不把 skip 算通过。

## 本轮验收用例

使用实际 AppShell 及其注册的 TreeActions.openDataGrid，不另造 ContentTabPane 或替换 mandatory guard/15 秒常数；实际 DataGridPane、DataEditService、JdbcDataEditor 对 mock JDBC 工作。允许反射注入合成 provider resolver 与被动获取 pane，以实际 guard 形成真实关闭路径。合成 PostgreSQL 与 Oracle 类型，不加载驱动、不访问网络。

1. 首行 UPDATE 在途：保存后关闭实际 shell。真实中断和默认 5 秒提示出现后仍 pending，标签/写资源保持所有权；释放在途操作后当前行 rollback=1、commit=0，后续行不执行；结算 COMPLETED，连接 exactly once 释放，迟到 UI/分页/保存不得回写。
2. 已提交第一行、第二行在途：shell 关闭仅回滚当前行，第一行提交保留，第三行不运行；不得重放先前写操作或把所有修改称为回滚。
3. 在途操作忽略中断超过实际 SaveAttempt.awaitClose 的 15 秒：5 秒提示仍 pending，15 秒后 FAILED_PARTIAL 并缓存/不可重试，不提前全局 teardown 或释放仍在使用的写 lease。仅失败确认后释放操作，检查迟到行回滚和 lease 关闭，最终 shell 结果不得转为 COMPLETED、迟到 UI 不改报成功。夹具在物理线程结算后清理自己的资源，明确区分产品恢复和 fixture 清理。

所有 barrier 均有明确截止和 finally 释放；物理等待失败必须真实时间实测，不能注入缩短 timeout。并发 mock trace 使用线程安全结构，不能让测试自己的 ArrayList 竞态制造结果。

若用例发现生产缺陷，保留红证据后最小修复并独立审查；未复现则只补证据，不增加功能。

## 验证、集成与边界

修复分支与 main 各使用独占新合成 profile 执行定向、全量、buildSrc 和 jpackageImage；offline/no-daemon/rerun-tasks，清除外部 JVM 参数与 live 环境变量。记录真正执行的 task、XML 时间/套件/失败/跳过、退出码和源码冻结，防止编译失败复用陈旧 XML。核对镜像隔离和零连接 driverFor 发现、产物及源码一致。只暂存明确的新文件清单，本地提交/合并并更新交接和实际待验项。

禁止读取、修改、枚举、暂存、清理 .testagent；Git 状态/diff 显式排除。禁止原有凭据、配置、SQL 历史和业务文件；仅 mock、合成 profile、UUID 独占临时目录。无真库、push/fetch/tag/PR/发布/安装更新/外部联系。当前权限为受限 workspace-write：worktree 写、Git metadata 写和必要缓存写须使用已有本地授权对应的自动审批升级；不假定旧全盘权限。拒绝时记录实际动作/原因，完成未受影响工作。

本轮证据为完整 AppShell 的合成 FX/JDBC；没有原生动作则明确记为未验，不能升级为正式启动器、真库或发布证据。工作区真实 CANCEL、字段原生输入/键盘/小窗、其他数据库、签名/安装升级/CI/用户任务继续单列。既有 datacube heartbeat 保持 PAUSED，不新增用户线程或自动启动下一轮。

## 最终本地交付

本轮限定目标已完成。新增两个provider类型共6项实际AppShell/openDataGrid/service/JdbcDataEditor合成FX关闭用例；首行/第二行事务、真实5秒提示、真实15秒FAILED_PARTIAL/资源所有权/迟到UI均取得新证据，夹具清理不算产品恢复。实现e7f55b7；首次全量两项旧路由夹具关闭失败保留，受控PG初始化红绿后仅修夹具前置条件d67bb37，产品未改，历史失败现场mode未知。

分支证据911e5c5，本地main合并代码ecbc421a899a17ef04eac02582402f7473591d20。分支/main各新定向184/184、全量3931 passed/3 live skipped、buildSrc8/8、jpackageImage及183文件镜像隔离/零连接发现通过，777源文件稳定、216 Java差异仅换行、三项SHA一致。原始失败、XML/参数/审计见[协调账本](../verification/2026-10-02-app-shell-grid-exit-coordination.md)和[实际结果](../verification/evidence/app-shell-grid-exit-coordination/results.json)。最终文档/证据集成不改受验源码，datacube保持PAUSED，本轮结束，不自动启动下一轮。

本轮没有原生/真库/正式launcher运行；真实工作区CANCEL、原生完整shell退出、FAILED_PARTIAL后产品恢复、字段输入/全键盘/多结果/失效/小窗、OS多屏、安装升级/签名/CI/用户任务/发布仍待验，M8不称完成。原有Oracle专用表保留且未访问/清理，安全边界全部延续。
