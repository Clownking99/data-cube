# 退出在途等待反馈：worker 验证记录

2026-10-07。GPT-6.1-sol 开发，root 独立审核/返工/集成。分支 `codex/shutdown-pending-feedback-20261007`，基线 `98e6d0797f6890aa8e971455eee567b19de28c33`；工作树 `C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾`。仅本限定增量，worker 不提交/合并/push，不修改 D 主仓或旧证据。

## 行为与最小实现

WindowShutdownController 拥有唯一反馈节点，原迁移确认通过且 quarantine.begin 成功、主体禁用之后，先插入主体外等待卡片，再调用真实 shutdown supplier。标题“正在退出，请稍候”；正文“正在处理退出请求。若出现确认对话框，请先完成选择；无需重复关闭窗口。”和“等待期间请勿强制结束进程，以免中断尚在进行的操作或丢失未保存内容。”。没有计时进度、百分比、额外超时、按钮、自动退出/重试/强杀/重启，不保证保存或回滚，不显示异常/SQL/路径。

取消、可恢复异步异常、null 结果清除节点并恢复主体，可再次关闭创建新等待；COMPLETED 清除节点且正常关闭一次；FAILED_PARTIAL 替换等待为既有固定保护说明，不叠加、不遗留等待、不恢复主体。只改 controller 与三测试；DataCubeFx 已使用这个生产 controller，不复制启动器 lambda，不修改 AppShell、ShutdownQuarantine、资源状态机、物理 15 秒守卫或公共 FX 5 秒助手。

复用既有 StackPane sibling、主题 token、最大 760px card 与 16px 边距。正常主体占满 Scene，等待/fatal 均不改变 body 布局；反馈在禁用 body 外保持字号、颜色和透明度。root 已独立审查 production diff、实际 XML 和 pending 合成图，通过后授权完整验证。

## 首红、绿与实际原件

证据根 `docs/superpowers/verification/evidence/shutdown-pending-20261007-worker` 一开始创建局部 `.gitattributes` 为 `* -text`，保留原件字节。复制旧 offline Run-Main.ps1 后仅改独占 temp 前缀为 `datacube-shutdown-pending-worker-`，测试截图变量为本轮 `SHUTDOWN_PENDING_SNAPSHOT_DIR`；旧 runner/证据不改。每次新 UUID profile/真实 8.3 temp，离线 JDK25，命令级清除 Java/Gradle/DataCube/live DB 环境，串行单 Gradle。各目录 command.json/gradle.log/exit.json 与实际执行 Task XML 绑定，本轮 XML 统计见 summary.json；不把历史、跳过或 UP-TO-DATE 当成新通过。

|目录|实际结果|说明|
|---|---|---|
|001-red-pending|4 tests / 4 failures / 0 errors / 0 skipped，exit1|旧生产 controller 下真实 shown Stage 缺少等待节点；compileTestJava 成功。不是编译红灯。红阶段两个源码副本及 red.patch 保留。|
|002-green-pending|16 / 0 / 0 / 0，exit0，9s|13 个 handler 例和 3 个 quarantine 例新执行通过；四例 pending 几何/双主题转绿。|
|003-expanded-targeted|40 / 0 / 0 / 0，exit0，1m10|六套全部实际执行，涵盖真实 AppShell Alert、15 秒物理守卫与状态互斥。|

003 套件为 DataCubeFxShutdownContractTest 13、ShutdownQuarantineTest 3、AppShellGridShutdownTest 6、AppShellShutdownRecoveryTest 9、AsyncShutdownCoordinatorTest 5、AppShellWorkspaceShutdownTest 4。不是旧五套清单，实际 modal suite 包含在本轮结果中。

root 审查指出三测试新增多余 EOF 空行；在 003 成功后只规范这三文件 EOF，未改断言或语义。root 明确批准无需重复 003，随后冻结四文件及 patch/runner；diff --check 通过。最终源码副本 final-source、完整 final.patch、final-source-sha256.json 保留。完整 patch SHA-256 `894455B2089CFD0535F35A38F25F41FD171DABFCC028D62822A0C0530C8EEAA7`。

## 真实状态、资源与几何

handler 回归证明等待节点在 shutdown supplier 调用之前已插入且 body 已禁用；重复关闭请求数 1、不替换节点。明确后台 finished latch 后等待 FX 屏障，不用 sleep 或加大全局 timeout。取消/异步异常/null 逐项断言旧节点 parent=null、root 仅剩 body；新一轮等待为新节点，完成后无 pending/fatal 残留。FAILED_PARTIAL 断言等待 detached、root 仅 body+fatal、迟到结果不替换保护态、重复关闭不重试。迁移拒绝前没有等待且不禁用主体/不调用 shutdown。

实际 AppShellWorkspaceShutdownTest 安装相同 controller；保留生产 Alert 的 owner/title/content/default button 与真实按钮 fire。5 次 Alert 显示时，等待完整可读且按钮可执行、重复主窗关闭不新发 shutdown；取消/重复取消/dismiss 清除等待并恢复准入，retry/ignore/正常最终完成清除并关闭。原工作区字节、合成 SQL 文件保存与重新准入、dispatcher/registry/runtime 资源断言均保留。这些是合成 FX 事件下的实际 production Alert，不是原生鼠标/键盘验收。fixture 最终 Stage disposal/fallback 显式是合成资源清理，不冒充产品恢复。

PG/Oracle 的真实 AppShell/mock JDBC 分支先检查 pending，原 5 秒 warning 后仍保持同一等待节点；实际 FAILED_PARTIAL 为 PG15327ms/Oracle15142ms，替换既有 fatal 且 pending detached。缓存浏览连接与全局任务 scope 保留、活动 owner 不 finalizer；迟到物理结算不启动全局清理或恢复 UI，dedicated write lease 释放一次；原 mock 执行/提交/回滚/待保存修改与禁止晚刷新断言不变。只 mock，无真实数据库访问。

实际 Stage 900×600/1200×800，各 light/dark；Scene885.3333×562.6667/1185.3334×762.6667。body 边界从原点起，FX 像素舍入差最大0.667px；等待/fatal 前后相等。卡片、各 Label 与真实 Text 全文检查，无 ellipsis，字号至少13px，透明度1，不继承 disabled；localToScene/localToScreen 全部在实际内容边界内，主题实际 fg/bg 及对比至少4.5，不以 isVisible 单证据。没有错误强求正文必须换行。

003/scene-snapshots 中四 PNG 为纯合成 FX Scene.snapshot：pending/fatal × light/dark，900×600 Stage，886×563 图像；PixelReader/JDK ImageIO，无新依赖、无正式 Application.start、无原生桌面截图。worker/root 已查看 pending 两图，文字完整、样式可读；旧截图不覆盖。

## 完整验证

root 批准冻结版本后，以下三阶段按顺序新执行，均退出0。

|目录|实际 Task 与结果|
|---|---|
|004-full-clean-test|clean test；314 suites / 4012 tests / 0 failures / 0 errors / 3 skipped，4009 项执行通过，4m51。|
|005-buildsrc-forced|root :buildSrc:test --rerun-tasks；1 suite / 8 tests / 0 failures / 0 errors / 0 skipped，10s。|
|006-jpackage-image|jpackageImage --rerun-tasks -Image；14 tasks 全部 executed，BUILD SUCCESSFUL，58s。不是测试运行，不复制旧 XML。|

本次全量三项跳过不算通过：`com.datacube.redis.RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()`、`com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas()`、`com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas()`。未启用 Redis live 配置或真实数据库显式写门禁及 provider 环境，精确原始原因在 004/skips.json。关闭反馈六套最终定向仍为0 skipped。

工作树 `build/jpackage/DataCube` 为本次镜像输出；仅构建，worker 未启动正式应用/镜像原生进程。jlink JEP493 jmods 提示保留在原日志，构建正常成功。root 负责后续镜像隔离/驱动发现审计、提交/集成/main复验。

四文件、完整patch与runner最终哈希复验均匹配 final-source-sha256.json，记录 freeze-recheck-final.json。run-ledger.json 保存六次实际命令/exit/统计，evidence-sha256.json 为worker原件清单（不包含清单自身）。首红源码/XML/log与四张合成图均保留，旧证据不改。diff --check 通过，Gradle 已交还 root，worker 不再运行构建或扩展功能。

## 未验边界

原生键盘/鼠标、OS 缩放矩阵、原生 migration 确认、无 Gate 启动、真实数据库、终态进程内恢复、安装/升级/回退、生产签名和完整 M8 未验。等待反馈只说明当前在途，不保证进程最终成功退出。旧单次 5 秒 FX 超时未定位，不归因、不调整 timeout。本轮仅 mock、合成 profile/SQL 和独占 temp，不读 .testagent/原凭据/连接/profile/历史 SQL/业务文件，不联网/剪贴板/原生操作/安装更新/外联，不更改 tag v3.2.9 或 PAUSED 跟进。
