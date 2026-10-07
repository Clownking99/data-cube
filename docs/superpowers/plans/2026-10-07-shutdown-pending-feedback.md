# 退出在途等待反馈

2026-10-07。本轮维护者继续推进产品，沿用 GPT-6.1-sol 开发、root 独立审核/返工/集成的授权。复用 C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾，分支 codex/shutdown-pending-feedback-20261007，基线 98e6d0797f6890aa8e971455eee567b19de28c33。D 主仓不改，旧轮证据保留。

## S0：已读问题与限定目标

WindowShutdownController 当前 begin 后仅禁用 body，只有 FAILED_PARTIAL 显示终态保护说明；在途关闭没有可见说明。本轮补即时、持久、主体外可读的等待提示，告知正在等待退出步骤、留意需确认的对话框、无需重复关闭、不保证数据库回滚或文件保存。迁移确认拒绝之前不能进入等待态。

最小结构：controller 拥有唯一反馈节点；begin 成功后显示等待说明，再调用真实 shutdown supplier。RECOVER 删除等待并恢复 body，允许再次关闭；COMPLETED 删除等待后正常关闭；FATAL 将等待替换为既有固定保护说明，不能叠加或恢复主体。复用现有 card 样式和 StackPane sibling，不新增状态机/计时进度/百分比/超时/退出按钮/重试/强杀/重启。AppShell、ShutdownQuarantine、物理15秒和公共FX5秒常数不变。

## S1：红绿与审查

先只新增生产 handler 的真实 shown pending 双主题双尺寸(900×600/1200×800)红灯，测全文Text、实际Scene/screen边界、主题颜色和布局，不以 isVisible 为唯一证据。root 审查最小结构后实施。回归覆盖等待即时可读且不继承禁用、原body尺寸、重复关闭仅请求一次、明确后台latch和FX屏障、cancel/error/null后节点清理与新一轮等待、COMPLETED关闭一次、FAILED_PARTIAL互斥替换和迟到不恢复。复用实际AppShell/mock JDBC的PG/Oracle15秒失败分支验证在途等待/终态替换与资源所有权；现有真实Shell恢复测试必要接入同一controller，不复制实现。

## S2：冻结与新执行

独占Gradle、offline JDK25、每次新UUID profile和8.3 temp，新目录记录命令/Task/exit/XML，首错原件保留，skip和UP-TO-DATE不算通过。worker报告 docs/superpowers/verification/2026-10-07-shutdown-pending-feedback-worker.md，证据 evidence/shutdown-pending-20261007-worker（起始局部 .gitattributes * -text）。定向通过后冻结源码及完整patch/hash，root独立审查批准再串行全量、root :buildSrc:test --rerun-tasks、jpackageImage --rerun-tasks。root负责镜像审计、提交/合并/main push和CI，本worker不做这些动作。

## 边界与未验

仅mock、合成profile及独占temp；.testagent禁读改枚举暂存清理，不读原连接/profile/凭据/历史SQL/业务文件，不真库/剪贴板/原生输入/安装更新/外部联系，不fetch/push/tag/PR/新线程/新代理。tag v3.2.9和PAUSED跟进不动。合成Scene截图不是原生桌面；原生键盘/鼠标、OS缩放矩阵、原生确认、终态进程内恢复、无Gate启动、安装升级回退、生产签名和完整M8单列未验。旧5秒FX超时不归因、不改timeout。本轮结束不扩展下一功能。

S4：源码6ef4b3e，root独立定向40/全量4009通过+3live跳过/buildSrc8/image与365原件审查通过；183文件镜像隔离及零连接驱动发现通过。准备证据归档/main集成复验，不预报main/CI/原生结论。
