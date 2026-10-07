# SQL 批量错误确认的取消与退出

2026-10-07，基线 main `25df305f8f8682d9330d86c8c5827666f4db4548`，范围内工作区干净。复用既有 GPT-6.1-sol 开发代理及独立工作树 `codex/sql-script-dialog-cancellation-20261007`，root 独立审核、验证和集成。维护者本轮“继续推进产品”沿用既有开发、审核、main 合并和推送授权。

## S0：待验证问题与范围

沿日常任务“批量执行 → 理解错误 → 取消或安全退出”检查原“执行遇错”确认框。静态读取发现：JdbcScriptExecutor 在调用错误策略前及下一语句前检查取消；SqlEditorPane.askScriptError 排队显示 Alert 时只检查 tasks.isClosed，并在后台等待 latch；session.cancel 在没有活动 Statement 时关闭连接，但不会自动释放此 UI 等待。目前仅为风险线索，不称已复现缺陷，也不声称取消后会继续执行 SQL。

优先验证取消动作与排队错误框的真实竞态：错误发生后、确认框显示前，已经排队的取消按钮动作先执行，旧确认框不得再出现或使执行永远等待。显示中的应用模态框会阻止主窗原生输入；程序化关闭只能作为合成生命周期证据，不能冒称原生用户可达。

保留真实 AppShell、SQL 控件、生产 PG/Oracle runner、queue/session、原 Alert 与关闭 guard；只 mock provider/JDBC。先诊断并保存首红，再批准最小实现。保持正常继续/全部继续/取消/关闭对话框契约；关闭被拒绝或草稿恢复不能无意取消当前执行。不得通过调大 timeout、sleep 或删弱断言消除失败。

## S1：交付顺序

1. worker 只读诊断与首红方案，root 独立复核调用链和可达性。
2. 批准后新增稳定、有界的合成回归，保存实际首红命令、退出码、XML、日志和源快照。
3. 只修已确认的错误确认生命周期；不扩展终态进程内恢复、不改格式化或重写执行器。
4. 定向验证与源码冻结后独立审查，再执行全量、强制 buildSrc 和 jpackageImage；所有跳过明确列出。
5. 本地提交、合并 main、重新验证并更新交接；推送 main 后核对精确 SHA CI。既有 tag 不变。

## 安全及证据边界

`.testagent/` 禁读、改、枚举、暂存和清理；不读取原连接、配置、凭据、SQL 历史或业务文件。只用 mock、合成 profile 和独占 UUID 临时目录。无真库、原生输入、剪贴板、安装更新、外部联系；不 fetch、PR、发布或新 tag。开发代理不做 Git 写入或网络操作，不创建新线程或代理；root 负责集成。datacube 跟进维持 PAUSED。

上一轮精确 SHA CI 四任务通过只作基线，原件先保存以免后续 clean 覆盖，不充当本轮证据。真实驱动、原生输入/OS 缩放、多屏、无 Gate 正式启动、安装升级回退、签名与完整 M8 仍待验。

## S2：首红后批准的实现与矩阵

001为夹具空标题NPE，原件保留，不算产品红灯；002两provider各实测取消已应用而旧框仍出现、queue不结算，后继SQL未执行。root独立复核后批准仅 SqlEditorPane + package-private ScriptErrorQuestionGate。每次执行独立身份；seal只禁止询问/接受继续，物理cancel Callable finally才终结ABORT。Alert因seal退出showAndWait也不能提前释放worker。正常执行finally回收自己的gate，旧FX及cancel回调不得覆盖下一执行。异常、任务拒绝及FX调度失败不得留下无人结算的询问。只在关闭已获批准后seal，原拒绝关闭继续可用。

批准精简19例实际UI矩阵：PG/Oracle首红2（含下一次执行）、正常继续/全部继续/取消/窗口X共8、整窗shown/queued共4、PG单tabshown/queued批准2、单tab拒绝1、整窗draft拒绝恢复1、慢取消与新执行隔离1；另少量helper异常路径。显示中modal的程序化关闭仅是合成生命周期证据。先新2例绿，再完整定向并冻结，root审核后才full/buildSrc/image。原错误策略、queue/session/executor、状态机及生产时限不改。

## S3：最终矩阵语义与接管

root接管完成006的25项（19 UI+6 helper）及007的249项相关定向，全部0skip通过，源码/runner冻结。004/005夹具失败与002产品首红原件保留。queued单tab若错误询问嵌入更早的未保存文件确认，关闭尚未获准，测试显式选择原错误询问“取消”再完成原关闭；不绕过文件guard，也不改变产品为提前取消。shown单tab拒绝后继续、整窗draft拒绝后的恢复和慢取消+旧callback隔离均保留。实际程序化交互不升级成原生证据。全量/buildSrc/image和main/CI待后续新运行。

## S4：分支验证完成及集成检查点

最终源7030423f、分支证据a5be5b20，root审查通过并no-ff合并main c5c25706。分支新定向249、全量4060通过/3明确live跳过、强制buildSrc8、jpackageImage14任务/183文件镜像审计均通过；459原件Git字节一致，失败原件保留。下一步在main新隔离profile跑同一249定向、clean全量、强制buildSrc及image，核对真实产物SHA和原件，再更新交接并按既有授权推送main、只读核对精确SHA CI。合成FX证据和完整M8待验边界不变。

## S5：本地交付完成

main c5c25706新定向249/249、clean全量4060通过/3明确live跳过、强制buildSrc8、强制jpackageImage14项及183文件零连接镜像审计全部通过；main源树与审阅源一致、三项实际产物与分支SHA相同。具体失败/跳过/场景限制见独立审查S1–S7。交接和M8表已更新实际结果，不提升完整发布等级。最后仅提交证据、推送main并核对精确SHA CI及Windows原始任务日志，回执目录见coordination/delivery-intent.json；既有tag和PAUSED跟进保持，不自动下一轮。
