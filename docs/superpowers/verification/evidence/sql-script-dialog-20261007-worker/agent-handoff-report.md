# SQL脚本错误询问取消 worker 记录

2026-10-07，C复用worktree codex/sql-script-dialog-cancellation-20261007，基线25df305f8f8682d9330d86c8c5827666f4db4548；root独立审核/集成。root计划/review/coordination仅root编辑，worker不改。生产暂不修改。

## S0 只读发现与首红批准

AUTO_COMMIT才调用真实askScriptError（JdbcEditorSession effectivePolicy MANUAL=null）。SqlEditorPane1799-1833使用局部latch/AtomicReference，queued FX仅tasks.isClosed检查，Alert没有owner，也无pane取消/close生命周期注册。provider/JdbcScriptExecutor20行下一迭代已有取消检查；不声称已取消仍执行后继SQL。首次错误Statement已经release/close，session.cancel仍running=true但没有activeStatement，因此control标取消并breakConnection；这会物理close但不释放policy latch。mandatory/single-tab实际cancel后无界queue idle因询问可能不结算。已显示APPLICATION_MODAL会阻断主窗原生输入，程序化生命周期补充不冒充原生可达。

root批准PG/Oracle两例首红：真实AppShell.openSqlEditor/AUTO/executeBtn→真实session/queue/native provider runner→两条纯合成SQL第一SQLException、第二计数。保留先进入的FX调度块，释放mock error并≤3秒只读真实worker WAITING+askScriptError/CountDownLatch.await栈，证实询问已排队未显示，随后真实cancelBtn.fire；基线connectionClose通知只是事实观察，不要求修复必关闭连接。下一FX原错误Alert可实际出现，先记录window owner/modality/queue/session/control/counters/trace，再断言旧询问不得出现且queue应结算，后继SQL不得执行。finally只点击该fixture原Alert取消释放，随后实际shell清理；notProductRecovery明确标识。

只mock JDBC/provider，不替换guard/queue/session/runner/原Alert；公开FX5秒和生产常数未改。runner复制已审版本仅新datacube-sql-script-dialog-worker- UUID前缀，起始证据.gitattributes * -text，本报告单文件-text。首红执行一次后暂停审查，不自动实现gate或运行扩大范围/full。

待首红后最小方案讨论：每执行身份持有pane owned错误询问gate；先seal不展示/不接受continue，实际cancel Callable finally中终结ABORT，再queue idle，不能FX先释放导致旧cancel误取消新执行；释放不依赖可能被task scope抑制的UI callback。关闭拒绝不终结，只有真实draft/用户决策已批准、物理取消前才seal。submit拒绝/Error/platform dispatch失败须有终结路径；尚未实施。

安全边界继续：.testagent禁读改枚举暂存清理；禁止原配置/凭据/SQL历史/业务文件/真库/网络/原生/剪贴板/安装更新/外联；仅合成独占temp，不提交合并push/tag/PR或新增代理/线程，datacube PAUSED保持。前轮证据不修改或作为本轮验证。

## S1 首次夹具失败，非产品红灯

001-earlier-cancel-red compileTestJava成功、实际:test执行，exit1/10s、2tests/2failure/0error/0skip；Stage默认title=null，errorWindows以s.getTitle().equals触发NPE，发生在取消动作/产品事实断言之前，finally同筛选也受影响，不能声明产品红灯或fixture清理成功。原pre-red-source及001/source-at-failure、原XML/log/command/exit/summary保留。root批准常量equals精准修正，唯一新源码变化仅该test筛选null安全比较；生产/期望/timeout未改。新002首红一次，完成再停审。初始两个独立3秒观察deadline在同一FX5s helper最坏可能累计超限，root已要求最终同步改统一总体≤3秒或分回合，但首红保持原同步观察且以实际结果为准，不放大helper。

## S2 真实产品首红确认与暂停

002-earlier-cancel-behavior-red compileTestJava成功、实际:test执行，exit1/9s，PG/Oracle2tests/2产品failure/0error/0skip。真实栈为askScriptError1825 latch.await→JdbcScriptExecutor37→PG106/Oracle112原runner→JdbcEditorSession224→SerialSessionOperationQueue186。两provider实际一致：旧Alert1、owner=null、APPLICATION_MODAL、Stage showing；queue idle=false/current EXECUTE；session BROKEN/AUTO_COMMIT/IDLE且running/cancelling=true，control cancellationRequested=true；sessionClose1/statementClose1/Statement.cancel0/globalClose0，仅FIRST，后继未执行。是在已观察原policy await、真实取消动作并确认实际取消应用之后，旧弹框仍然出现且worker不结算，满足真实行为红灯而非预设其他缺陷。

finally只点击该fixture原Alert取消，trace fixture-only-original-Alert-abort之后原queue结算、实际shell cleanup COMPLETED/globalClose1，notProductRecovery明确区分。connectionClose只是首红已应用取消的事实，非新规格要求，修复可能正常NOTHING_RUNNING而不关闭连接。002原command/exit/log/XML/source-at-failure/source-sha256/summary全部保留，001夹具NPE及其清理失败同样保留。session63132已exit1，Gradle停，无重复002或生产修改；root独立审确认两例产品缺陷并允许最小gate实现。

拟定最小结构（已提交root精简矩阵，尚未编码）：pane每执行身份持package-private ScriptErrorQuestionGate，原policy闭包捕获该对象；gate管理待答Question+FX Alert（FX读取真实Window owner）。先seal禁止旧展示/继续，实际cancel Callable finally终结ABORT，不依赖scope可能抑制的UI callback，避免policy先释放/idle后旧cancel误取消下一轮。close只有原draft/用户决策批准后才seal，原session.cancel返回/throw后的finally终结→原idle。正常四种选择保留；执行finally清理，terminal/旧cancel UI回调identity校验，不覆盖终态或新执行。同步拒绝/立即cancelled task/FX dispatch失败终结，无额外queue/session/executor改动或timeout变化。

拟19个实际链路例：首红2且真实下一轮执行身份、正常continue/all/abort/X两provider8、whole shown/queued两provider4、单tab shown/queued批准PG2、单tab拒绝PG1、whole draft拒绝PG1、慢取消/旧callback隔离PG1；helper故障路径另少量，不跨provider无谓组合。最终fixture观察改统一总体≤3秒或分阶段FX回合，FX helper仍5s，历史首红真实latch栈原件不改。待root矩阵审查后先首红2绿再扩大定向，不自动full。
