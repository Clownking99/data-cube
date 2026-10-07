# 退出部分失败可见反馈：worker 验证记录

2026-10-07。GPT-6.1-sol 开发，root 独立审查及 main 交付。分支 `codex/shutdown-failure-feedback-20261007`，基线 `5fd42ce02a621a4b8070447ebe8defc36d15b11e`；工作树为 `C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾`。本 worker 不提交、合并或推送。

## 结果与范围

DataCubeFx 使用同一个生产 WindowShutdownController 安装关闭 handler。FAILED_PARTIAL 仍为不可重试的终态，应用主体保持禁用；主体外持久显示标题和三段说明：部分关闭失败、在途操作可能部分完成且需独立核对、手动结束后重开可能中断操作和丢失未保存内容。提示不拼接异常、SQL 或路径，不宣称保存/回滚，不提供重试、自动退出、强杀、自动重启或准入恢复。

控制器仅负责窗口反馈，AppShell/ShutdownQuarantine/AsyncShutdownCoordinator 的资源及状态机不变。迁移确认拒绝在主体禁用与 shutdown 请求之前；pending 重复关闭不重复请求；取消、异步异常和空结果仍恢复交互；COMPLETED 仅关闭一次。界面采用 StackPane（root 已审查批准），正常阶段 body 占满原 Scene，提示为禁用 body 的 sibling，保留原窗口尺寸布局与主题。

## 红绿原件

证据根：`docs/superpowers/verification/evidence/shutdown-feedback-20261007-worker`。每次执行独立 UUID profile/真实 8.3 temp，离线 JDK 25；执行命令、HEAD、profile、Task 与退出码各保存在 command.json/gradle.log/exit.json，XML 是本次实际执行 Task 的副本。旧原件不覆盖；历史 CI 不作本轮通过证据。

|目录|实际结果|解释|
|---|---|---|
|001-red-feedback|4 tests / 4 failures / 0 errors / 0 skipped|保持旧语义提取生产 handler 并实际接入 DataCubeFx 后，真实 shown Stage 复现缺少提示。编译成功；不是缺类或编译失败。|
|002-green-handler|12 / 4 / 0 / 0|4 例为夹具错误要求长段落高度必须大于 20px；合法单行布局不应判错。8 个状态行为例通过。原件保留，后续改为完整 Text 内容及边界断言，提示最大宽度 760 保持可读行长。|
|003-green-targeted|32 / 0 / 0 / 0|五套新执行通过，保存 light/dark 900×600 的两张合成 FX Scene PNG。|
|004-final-targeted-reviewed|32 / 0 / 0 / 0|root 时序审查后，后台 complete 路径加入 finished latch；真实 AppShell future 完成后加入 FX 结算屏障。生产源码不变，再次新执行五套通过。|

红灯提取阶段源码在 `red-extraction-source`，tracked diff 为 `red-extraction.patch`，新 controller 的红阶段补充 diff 为 `red-controller.patch`。最终四文件完整副本在 `final-source`；`final.patch` 包含新 untracked controller，`final-source-sha256.json` 绑定四文件、完整 patch 和 runner。没有修改公共 FxUiTestSupport 的 5 秒超时。

最终定向五套：DataCubeFxShutdownContractTest 9，ShutdownQuarantineTest 3，AppShellGridShutdownTest 6，AppShellShutdownRecoveryTest 9，AsyncShutdownCoordinatorTest 5。实际 handler 覆盖 pending、正常完成、取消、异步异常、null、迁移拒绝/批准、后台结算、重复关闭。mock JDBC 的真实 AppShell 15 秒物理 timeout 分支中，PG 15341ms / Oracle 15262ms：FAILED_PARTIAL、可见说明、缓存浏览连接与任务 scope 保护、迟到回调不恢复和不重入清理均断言；测试最终 fixture 清理不称为产品恢复。

## 真实几何与合成图像

Stage 实际 shown 尺寸 900×600、1200×800，各 light/dark；对应 Scene 为 885.3333×562.6667、1185.3334×762.6667。正常无提示时 body 起点与 Scene 一致并占满 Scene，fatal 前后 body 边界相同。提示 VBox、每个 Label、真实 `.text` 节点内容及 Scene/screen 边界均检查；文字无 ellipsis/裁切，字号至少 13px，未继承 body 禁用，透明度 1，主题实际颜色对比至少 4.5。不是只断言 isVisible。

003 的 `scene-snapshots/synthetic-scene-900x600-light.png` 和 `synthetic-scene-900x600-dark.png` 为纯合成 FX Scene.snapshot，经 PixelReader 与 JDK ImageIO 保存；886×563 像素。worker/root 均看过完整排版与颜色，无新依赖、无正式 Application.start、无原生桌面截图、无原 profile 数据。004 保留原图，不重新覆盖。

## 全量与构建

root 在独立核验 004/四文件后授权串行三阶段；均使用新的独占 profile，退出 0。

|目录|实际 Task 与结果|
|---|---|
|005-full-clean-test|clean test；314 suites / 4008 tests / 0 failures / 0 errors / 3 skipped，4005 项执行通过，4m31。|
|006-buildsrc-forced|root :buildSrc:test --rerun-tasks；1 suite / 8 tests / 0 failures / 0 errors / 0 skipped，7s。|
|007-jpackage-image|jpackageImage --rerun-tasks -Image；14 tasks 全部 executed，BUILD SUCCESSFUL，38s。不是测试运行，不复制旧 XML。|

全量三项跳过不算通过：`com.datacube.redis.RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle()`、`com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas()`、`com.datacube.schemadiff.SchemaDiffLiveIntegrationTest.postgresqlSafeDeploymentConvergesInDisposableSchemas()`。未启用真实数据库写门禁或 Redis profile，精确本次原因在 005/skips.json。新关闭反馈最终定向仍 0 skipped。

镜像输出为工作树 `build/jpackage/DataCube`。仅构建，worker 没有运行正式应用或镜像原生启动；镜像隔离与驱动发现审计由 root 后续执行。007 显示 JEP 493 jmods 提示并正常成功，原始日志保留。代码冻结复验通过，完整 patch SHA-256 `F129FDD6831E1D41355AF0DED521873F0558DD64306212513E1426C17CB2A5D4`；四文件精确哈希在 final-source-sha256.json，最终复验在 freeze-recheck-final.json。Gradle 已交还 root，worker 不再启动构建。

## 限制

本轮是失败可见反馈，不实现 FAILED_PARTIAL 进程内恢复。原生键盘/鼠标、OS 缩放矩阵、原生确认对话框、无 Gate 启动、真实数据库、安装/升级/回退、生产签名及完整 M8 未验。旧托管 CI 单次 5 秒 FX 超时仍未定位，本轮不改 timeout、不归因。仅 mock、合成 profile 和独占 temp；没有读取 .testagent、原凭据/配置/历史/业务数据，没有网络或系统剪贴板操作。
