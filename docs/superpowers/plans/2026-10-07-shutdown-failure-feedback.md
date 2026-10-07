# 退出部分失败的可见反馈

2026-10-07，维护者继续推进产品，沿用 GPT-6.1-sol 开发、root 独立审核和 main 集成授权。当前 main 与复用 worktree 为 5fd42ce02a621a4b8070447ebe8defc36d15b11e，授权范围干净；既有最终 Verify 37596231516 四任务成功是基线历史，不充当本轮新测试。独立分支 codex/shutdown-failure-feedback-20261007。

## S0 目标与依据

DataCubeFx 的关闭 handler 在 FAILED_PARTIAL 后只向 stderr 输出信息并保持整个 AppShell root 禁用。普通桌面用户看不到原因或可行下一步；现有 DataCubeFxShutdownContractTest 只做源字符串检查，不能证明实际可见行为。需用真正安装的生产 handler 和 shown Stage 建立红灯，再作最小修复。

本轮只补充部分退出失败的可见说明与安全指引，保持 ShutdownQuarantine、mandatory guard、物理等待、资源所有权和 FAILED_PARTIAL 终态。现有取消/退出前异常可恢复交互，COMPLETED 才正常关闭。不得自动重试事务、恢复已隔离准入、强杀进程、自动重启，或暗示在途操作已经回滚/保存。

## S1 实现与独立审查

优先小型生产关闭控制器/反馈节点以便执行真实 handler，不复制启动器逻辑造测试，不运行会访问真实配置或更新的正式 Application.start。可见说明需在应用主体禁用后仍可读，在重复关闭、迟到结算后继续保留；给用户准确后续指引。控制器只承担窗口关闭与提示，AppShell 仍拥有资源与操作生命周期。

新回归覆盖：pending 重复关闭不重复请求；FAILED_PARTIAL 的真实可见提示及敏感异常/SQL不泄漏；重复关闭不重试/不恢复/不重复清理；CANCELLED 和退出前异常恢复且可再关闭；COMPLETED 正常关闭一次。必要时以现有真实 AppShell/mock JDBC 15 秒物理失败用例接入同一生产 handler，证明实际资源仍受保护；不得替换 mandatory guard 常量或把夹具清理称为产品恢复。

## S2 验证与交付

GPT-6.1-sol 负责红绿、最小源码/必要回归及 worker 原件，root 独立源码/原日志/XML/存储与资源计数审查后再作完整验证。Gradle 单一执行者，所有运行新 UUID profile/独占 temp，保留首次失败，跳过不算通过，实际 Task/退出码/新 XML 与受验源码绑定。分支/main 各定向、全量、root buildSrc 强制执行、jpackageImage 及镜像隔离/仅驱动发现审计；代码冻结后提交、合并 main、新复验与交接。

仅按既有授权推送既定 GitHub main，命令级 7897 代理并核对精确最终 SHA Verify；不 fetch/force/tag/PR/发布。v3.2.9 保持原对象/目标，datacube 保持 PAUSED，不创建新侧栏线程。完成本限定增量后交付，不自动扩展下一轮。

## 边界与未验

.testagent 禁读改枚举暂存清理；不读取原有凭据/连接配置/profile/SQL历史/业务文件，不访问真库或既有 Oracle 表。仅 mock/合成 profile/独占目录；不使用系统剪贴板、原生桌面输入、安装更新或外部联系。合成 shown FX 不是原生或完整发布验收。新提示不等于终态进程内恢复，FAILED_PARTIAL 后完整产品恢复、原生输入/OS缩放、无Gate启动、安装升级回退及生产签名仍单列。上一轮单次旧 FX 5 秒超时未定位，不盲增 timeout 或当成本轮缺陷。

S0 检查点：main/worktree 与历史最终 CI 回执核对；只读源码确认缺少 fatal 可见说明，尚无本轮新行为红绿。先由 sol 返回最小结构方案再执行，root 继续独立审查；无真实数据与外部写操作。

S1：保持旧语义的生产handler提取并接入DataCubeFx后，真实shown Stage两尺寸/双主题4例全部因没有可见提示失败，0skip；root独立核对快照、实际Task/XML与几何。实现用StackPane外层保持body与反馈隔离，最终须验证正常布局和主题。红灯不是编译失败，原件保留。继续最小反馈及旧关闭分支/真实mock超时验证。

S2：root审查真实handler、终态资源接入和两合成Scene截图；修正后台结算/FX提示排队的测试时序后，004新定向32/32、0skip，实际PG/Oracle15秒守卫保留。4文件源码冻结，批准串行全量/buildSrc/image。核心关闭状态机未改，本轮仍不是终态进程内恢复或原生/完整M8。

S3：分支源码33e7773已提交。root独立新XML/源hash/373原件校验通过：32定向、4005全量通过+3明确live跳过、buildSrc8、image/183文件镜像隔离与零连接发现；新modules SHA7FC8A42E…已绑定。准备证据冻结与main集成复验，不提前宣称CI/原生通过。
