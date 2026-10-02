# DataCube：SchemaDiff 间歇失败根因与本地闭环

客户端日期：2026-10-02。维护者在 P3 交付后再次要求“继续推进”；本轮处理已记录的工程红灯，不扩展新功能或外部目标。

## 基线与范围

- main 和隔离 worktree 起点：055bda4a9d0f9d561cb9242971dbabb8e8f6c3e7，授权范围工作区干净。隔离分支 codex/schema-diff-stability，复用 C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾。
- 前轮全量首次失败：SchemaDiffServiceTest.providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview，CompletionException / Schema snapshot failed；原始 full-first XML 保留。后续通过不是根因证据。
- 本轮先检查服务两侧并行读取、mock 记录容器与凭据保护的实际调用。先复现并捕获未脱敏前的异常类型/安全堆栈，再判定测试夹具或产品缺陷；不凭推测修改产品，也不增加自动重试或掩盖失败。
- 既有开发线程正式 id 仍不在活动/归档列表中，不把 client-new-thread 标识充当正式 id。不新建侧栏线程；沿用维护者授权的 GPT-6.1-sol 委派开发，由当前线程负责下发边界、独立审查、main 集成和新复验。

## 本轮顺序

1. 用 mock、合成配置、独占临时目录建立并发诊断及有意义红灯；保留原始输出和准确归因，必要时区分多个潜在原因。
2. 只修已证明的原因；保留两侧读取、取消、关闭、错误脱敏和配置快照语义。回归覆盖记录完整性和相关服务行为，不以重复运行碰运气替代修复证据。
3. 开发交付改动、原始红绿及定向验证；当前线程独立核对源码、原始证据和待验声明，再完成分支全量、buildSrc 和 jpackageImage/镜像审计，精确提交、本地合并 main，并用新 profile 复验。
4. 更新本轮账本、交接和路线图。只有该间歇失败被证明解决，才撤除对应待验；完整桌面、真库、签名、安装升级、CI 和发布仍另列。本轮不自动扩展后续范围。

## 安全与证据

不读、改、枚举、暂存或清理 .testagent/。不读既有连接/profile/凭据、SQL 历史或业务文件；mock 使用合成内容。本轮不访问真库、不 push/fetch/tag/PR/发布/安装更新/外部联系。原有指定 Oracle 专用表权限不扩大，专用表保持原状。

单 Gradle 进程、既有 JDK 25、离线运行、清除 live/JVM 注入环境、新 user.home。旧通过、跳过和 UP-TO-DATE 不算新执行。实际操作与 FX/mock/原生证据分级，不为工具不可用改变产品语义。每个检查点记录目标、改动、验证、失败/未验、下一步；原日志/XML、raw SHA/长度和实际 Git blob 字节保留，默认 checkout 换行差异须明确证明。

## S0 检查点

- 当前目标：确定前轮间歇红灯的真实原因。
- 改动：新计划，隔离分支；未改产品或测试。
- 验证：main 与隔离 worktree 为精确 055bda4；授权范围干净；已读取原始首次失败 XML、服务与 mock 的调用。
- 失败/未验：首次异常经过产品安全摘要后没有底层 cause，不能直接归因；开发侧栏正式 id 缺口仍存在。本轮尚无新测试通过。
- 下一步：向 GPT-6.1-sol 开发代理下发限定诊断/修复任务，当前线程独立审核并完成本地交付。

## S1 本轮本地交付

- 目标与改动：已证明的mock记录竞态和schema双引用夹具修复；仅SchemaDiffServiceTest及证据/文档，产品未改。GPT-6.1-sol开发代理实际完成，协调线程下发有界等待要求并独立审核。
- 验证与集成：实现75ed0c6、分支证据7e12468，main本地验收合并a353117；分支/main各新定向9、全量3917 passed /3 live skipped、buildSrc8、jpackageImage/183文件镜像及零连接发现通过，三项SHA一致。原始红灯、双线程越界异常、安全同参数通过和精确归因限制见 [开发账本](../verification/2026-10-02-schema-diff-stability.md)；最终 [独立审查/main复验](../verification/2026-10-02-schema-diff-stability-coordination.md) 与 [实际结果](../verification/evidence/schema-diff-stability-coordination/results.json) 可复核。
- 失败/未验：记录红灯8192→6177/关闭8192；同步后8192完整。双线程unsafe同类ArrayList异常与safe完整400000均实际取得，旧历史那一次cause依然缺失，不能精确确认。归档CR、JDK内部类型误报和汇总相对路径失败全部保留，修复限外置工具。本轮无原生/真库、完整M8/发布未验。
- 下一步：最终证据字节审计和文档提交/fast-forward main后交付；既有heartbeat继续PAUSED，没有恢复或新建自动任务，不自动扩大后续范围。
