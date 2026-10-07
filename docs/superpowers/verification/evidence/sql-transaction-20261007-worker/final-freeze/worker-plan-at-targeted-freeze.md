# 显式事务操作在途整窗退出

2026-10-07；C复用工作树codex/sql-transaction-shutdown-20261007，基线3494b670f82af1257bc63c13de91ae8fee2d29e9。GPT-6.1-sol worker开发、root独立审核与main交付；旧轮证据不算本轮新验证。

## S0 源码与产品契约

真实路径保留AppShell.treeActions.openSqlEditor→SqlEditorPane事务模式控件/commitBtn/rollbackBtn→原WriteSafetyDialog→SerialSessionOperationQueue→JdbcEditorSession→原mandatory guard→WindowShutdownController。真实PG/Oracle runner先执行合成WHERE DML形成ACTIVE，mock仅provider/JDBC。COMMIT(false)/ROLLBACK(false)不可取消；commitAdmitted/rollback没有beginOperation，session.running=false不能当物理结束，检查queue.current/idle和真实JDBC线程barrier。mandatory等无界idle，再以新鲜pending状态rollback；transactionGate失败才FAILED_PARTIAL且不跑destructiveCleanup，session/global/tab保留。生产PT5S仅warning，FX helper5秒不改。

root确定契约：退出不能静默吞掉尚未向用户显示的显式事务操作失败；不确定阶段沿现有FAILED_PARTIAL可见保护，保留session/global/tab所有权，禁止自动重试事务操作。COMPLETED只代表关闭结算，不能推断提交成功或数据撤销。已显示并处理的旧错误不能粘滞导致后续退出fatal。

## S1 首红先行

批准先新增PG/Oracle两例：真实MANUAL/UI DML pending→commit按钮在途→整窗退出→mock物理commit正常等待后抛合成SQLException。断言之前记录实际outcome、Stage showing、pending/failure反馈、commit/rollback/close/global及trace；期待FAILED_PARTIAL保护、无自动rollback/retry、所有权保留。首红源码快照/XML/log完整归档，再停止Gradle等待root审红与最小实现方案，不先改生产。TEST配置仍经真实WriteSafetyDialog.confirm/request.confirm，仅自然无需modal，不另增PRODUCTION例。

## S2 待首红审核后调整矩阵

成功commit/rollback须等待非可取消操作，跨原PT5S依旧pending，物理成功后才释放；显式commit/rollback失败应保持保护，不能自动再rollback/close/finalize。另考虑mock已生效但commit抛错的不确定结果；物理完成/queue.current清空而FX failure callback尚未显示竞态；已显示并恢复后旧错误不粘滞。SET_MODE同为非可取消且有failure callback，需列受影响分析，未授权扩其他模块。原12组合不盲实施。

## S3 证据与边界

新worker证据sql-transaction-20261007-worker（起始局部* -text）；verification/.gitattributes只加本轮worker报告单文件-text保留旧行。已审runner复制仅改专用UUID前缀；offline/JDK25/真实8.3temp、新隔离home、单Gradle。root审查冻结后才全量/buildSrc/image，worker不commit/merge/push。

.testagent禁读改枚举暂存清理；禁止原profile/连接/凭据/历史业务数据、真库/剪贴板/原生输入/更新/安装/外联/网络/新线程代理；仅mock合成独占temp。D主仓、v3.2.9及PAUSED自动跟进不动。仅本轮限定退出事务链路，不自动扩下一功能。

## S4 首红与补充竞态的实际推进

001-explicit-commit-red首次PG/Oracle2例真实产品红灯，commit抛错后自动rollback/close且COMPLETED隐藏窗口；原源码/XML/log保留。首轮pane FX-owned pending List+mandatory稳定copy使002-transaction-matrix的18例16通过，但physical idle/FX未呈现两例仍失败，整次不算绿。真实SqlWorkspaceUi.freeze明确Platform.runLater，故应用退出FX入口到pane mandatory之间原正常error callback可先呈现并remove token；只是guard入口快照还不够。

root批准最终两文件方案：复用pane Node.properties中的既有身份（私有key改名SQL_EDITOR_OWNER，favoriteText语义不变）；AppShell新shutdownAttempt建立后、异步shutdown之前同步遍历所有Tab.content，包括非选中tab，capture pending immutable List并返回无阻塞identity释放closure；AppShell每一种终态FX回调最前释放。采用唯一ApplicationCloseCapture对象，避免List.copyOf(empty)共享空List破坏跨代identity。pane mandatory入口合并早期snapshot与current pending，真实queue idle后检查物理失败，失败直接原FAILED_PARTIAL保护，不做再次rollback/retry或资源清理。未改queue/session/关闭状态机/timeout；没有使用root.isDisabled吞callback或增加replay状态机。原UI callback正常处理后identity移除当前pending；前置workspace异步间隙允许实际ERROR呈现，但应用进入退出时尚未呈现的失败仍由早期快照保留；guard suppress后迟到terminal仍不更新UI。

003-first-red-and-boundary-recovery先跑8例，8/0失败/0错误/0skip，exit0/17s，四方法PG/Oracle分别首红commit保护、物理idle/FX未呈现race、真实双tab非选中race、真实mandatory draft flush失败取消恢复。恢复仅本fixture owned .draft mover注入IOException，实际CANCELLED恢复后commit仍在途，释放后真实ERROR呈现，再实际rollback恢复/二次exit正常，不能将内部字段清空冒充恢复。fixture清理固定pane.closeResources→shell.shutdownRemaining→FX finalizer，额外tab若有同样清；before/after事务模型与计数/trace分别标notProductRecovery。probe精简为单WHERE DML与不可取消commit/rollback物理barrier，首红死分支源码仍原封快照。

root批准004-final-targeted：22新矩阵（10事务结果含原首红2、2 FX idle race、2显示旧错恢复、2 SET_MODE实际combo/原Alert、2 queued mode不覆盖running、2非选中双tab、2真实draft flush取消恢复）加17旧相关suite和SqlFavoriteTabsTest，共19suite。完整定向结束才最终冻结2src+2test/probe+patch+runner，待root审核批准full/buildSrc/image。本轮SET_MODE已获批准纳入同SQL事务记录，原12组合提案不作为当前规格。

## S5 定向时序收口

004实际19suite/207tests/1failure/0error/0skip：唯一Oracle applied-before-failure仍正确FAILED_PARTIAL且资源保留，错误UI在异步draft flush完成前呈现，触发guard-suppress用例before==after不成立。root核原XML后批准仅修测试同步。新增真实SqlDraftUi observer立即check、以queue monitor观察callbacksEnabled=false、5秒有界且finally注销再release JDBC；不set、不sleep、不改生产或timeout。early race/双tab与取消恢复保持允许前置错误呈现的真实窗口。005在新UUID隔离profile/temp执行同19suite/207项，完成才冻结并暂停供root审查，full仍未授权。
