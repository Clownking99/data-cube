# SQL 执行在途的整窗退出实际链路

2026-10-07。维护者继续推进产品，GPT-6.1-sol 开发、root 独立审核/返工/集成。复用 C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾，分支 codex/sql-inflight-shutdown-20261007，基线57b044497175cf906fbf8dfc8254b768d07d63d2。D主仓不改，旧轮main/CI及证据不充当本轮新验证。

## S0：实际路径和发现

AppShell.TreeActions.openSqlEditor(738-749)构建真实SqlEditorPane，openSqlTab(689-720)将原pane::requestMandatoryClose绑定到managed tab。SqlEditorPane UI操作提交SerialSessionOperationQueue，ConnectionManager.openEditorSession创建真正JdbcEditorSession，生产执行层为PgSqlRunner/OracleSqlRunner和JdbcScriptExecutor（没有名为JdbcQueryRunner的类）。ConnectionFactory和JDBC接口可以仅用合成mock替代，不需要fake EditorSession或替换mandatory guard。

Mandatory guard先freeze/stopAcceptingAndCancelQueued，停止准入并抑制callbacks；关闭虚拟线程cancelCancellableCurrentSession之后通过sessionOperations.idle().join等待物理操作结算，再执行CANCEL_ROLLBACK、history/strict close等清理。SQL的join无界；原AsyncTabCloseCoordinator的默认5秒只给warning/STILL_CLOSING，并不将SQL变成DataGrid的15秒FAILED_PARTIAL。实际AppShell.isRunning仅指migration，不对SQL另建迁移确认。本轮保持这些语义与常数。

当前只读未发现确证产品缺陷，已有测试多是源字符串契约或单层queue/session/provider；需要真实整窗组合证据。先提交矩阵供root批准，批准前不运行Gradle或修改源码/测试。没有要求制造红灯：如无新证据缺陷，只新增mock集成证据；只有确证产品缺陷再最小生产修复并保留红绿。

## S1：最小矩阵与入口

PG/Oracle各两例（共4）：一例cancel后可及时释放，一例Statement.cancel返回但execute仍阻塞，跨原默认5秒warning后再release。通过AppShell.treeActions.openSqlEditor创建实际pane；真实事务模式控件切为MANUAL、先UI执行纯合成WHERE DML形成未提交事务，再UI执行后续脚本并在mock JDBC阻塞。通过真实WindowShutdownController Stage close发起退出。

断言：取消送达且无隐式commit；未物理释放前Statement/session连接、tab及global scope/cache所有权保留；慢例5秒后future未结算、同一等待说明可读、managed tab禁用且未finalize，不能错误出现FAILED_PARTIAL；release后rollback1，每个Statement及dedicated connection关闭一次，tab/registry/global资源最终释放一次，COMPLETED关闭窗口并清除等待。合成SQL第三条不在取消后执行；同一queue内待执行的真实session请求取消不启动；stop后queue新准入拒绝，AppShell关窗后新tab准入拒绝。迟到progress/terminal不得刷新已关闭UI或重新准入，重复close不重复shutdown/清理。

mock只替换JDBC与DatabaseProvider的连接工厂/metadata探针；sqlRunner/dialect保留实际Pg/Oracle生产类。History/drafts仅本次新隔离profile写入，SQL全合成。观察用反射读取实际pane/session/queue/coordinator，不替换guard/状态机。全局资源close计数若需插入close-only合成缓存Connection，明确其来源是fixture，清理由实际ConnectionManager.closeAll执行。Fixture兜底释放和cleanup单列，不冒充产品恢复。

非可取消COMMIT在途有不同承诺/等待语义，本轮建议不扩展，单列未覆盖；queue/session/provider既有测试本轮新跑，不把单层测试当整窗证据。

## S2：定向、冻结、独立审查与交付

新worker报告 docs/superpowers/verification/2026-10-07-sql-inflight-shutdown-worker.md；证据 evidence/sql-inflight-20261007-worker，起始局部.gitattributes为* -text。复制上一offline JDK25 runner，仅新独占UUID temp前缀；每次新profile及真实8.3 temp，剔除live环境，单Gradle串行，新目录保留command/exit/log与真正执行Task的XML。首编译/行为错误保留，不盲重跑、不加大全局5秒timeout、不降断言；skip/UP-TO-DATE不算通过。

矩阵批准后新增集成测试与必要probe；定向应含新SQL整窗suite、实际session/queue、PG/Oracle执行控制、原SQL close sequence和反馈/实际shell生命周期相关suite。root独立审源码与原结果后，冻结所有变更源码/测试、完整patch和runner，才授权全量、root :buildSrc:test --rerun-tasks、jpackageImage --rerun-tasks。root负责镜像审计、提交/合并/main push及CI，worker不做这些动作。完成限定增量不扩展其他功能。

## 边界和未验

.testagent禁读改枚举暂存清理；禁止原凭据/连接/profile/SQL历史/业务文件、真库、剪贴板、原生输入、安装更新、外部联系；仅mock合成独占temp，不fetch/push/tag/PR/新线程/子代理。v3.2.9和PAUSED自动跟进不动。原生键盘/鼠标、OS缩放、真库驱动取消/事务、非可取消COMMIT在途、终态进程内恢复、无Gate启动、安装升级回退/签名及完整M8未验。本轮只给合成整窗实际链路证据，不宣称完成全部发布验收。

S5：两测试提交5b3d7172、证据8acdb188，main集成cf8a4390；分支新定向184/全量4013通过+3live跳过/buildSrc8/image与零连接镜像审计通过。root独立审查及401原件Git字节核验完成；main新复验进行中，尚不预报main或CI通过。生产源码未改，完整M8和边界未验保持。

S6：main cf8a4390 新定向184/全量4013通过+3live跳过/buildSrc8/jpackageImage及183文件零连接镜像审计均通过，分支/main三产物SHA一致。原报告换行归档问题已恢复原字节并设单文件-text；原件清单重新核对通过。限定mock组合本地完成，无生产代码改动；非可取消COMMIT/真驱动/原生/完整M8等保持待验。最终main推送及精确SHA CI以实际回执为准。
