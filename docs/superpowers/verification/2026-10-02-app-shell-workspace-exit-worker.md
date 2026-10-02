# N1 实际 AppShell 工作区退出 worker 账本

基线 `242946facc6c4c648cf1550d995aa9237b6544d4`，分支 `codex/app-shell-workspace-exit`。完整阅读本轮计划；仅合成 FX、真实生产 Alert，非原生/正式 launcher/真库证据。

## 检查点 1：建立真实路径与首轮定向

目标：真实 AppShell / 原 mandatory guard / SqlDraftStore / SqlWorkspaceUi.showDecision 覆盖取消、重复取消后新文件活动、重试、忽略及合成 dismiss。
改动：新增 AppShellWorkspaceShutdownTest.java 和本轮独占 Run-Targeted.ps1。底层自有 SqlDraftDirectory.mover 仅对 workspace.bin 注入 IOException，其他调用原 mover；草稿实际落盘，实际初始化 observe+refresh 严格确认 ENABLED/非 busy。真实 Alert 仅按本轮 owner 识别，检查三按钮、文案和默认取消，使用 Button.fire 的合成 FX 动作。
验证：源码确认 PUBLISH 非 structural，生产 Alert 在原标签已经守卫结算并移除后出现；CANCEL 不声称原标签保留。@BeforeAll 字体/effect 预热在受控 JVM profile；不访问外部应用内容。原 SqlWorkspaceStore 误检索到 sql/draft 旧路径一次，已按 config 实际路径恢复，只是检索错误。
失败/未验：尚未执行首轮；不预设产品缺陷。fixture 当前以文件task delegate 包装记录 completion/close，不替换真实任务实现或 mandatory/decision。
下一步：独占新 UUID profile 定向首次运行，原始结果无覆盖。

## 检查点 2：首轮夹具计数失败

first-workspace 实际 test task 执行，fresh XML 4 项、2 failure、0 error/skip，exit 1；RETRY/IGNORE 已通过。两取消案例只在 draftMoves > 0 断言失败，新增计数器错误使用 .bin，实际 SqlDraftStore.filename 为 UUID.draft。保留首 XML/raw/参数/源码 SHA，不能视为产品红。修正计数为 .draft，并禁止 providerResolver 调用、断言 requests=0，证明没有数据库读/执行/写准入。下一步同样四项新 profile 定向；尚无产品改动。

## 检查点 3：审查修正及最终交付

corrected-fourcases 实际 4/4、exit 0，fresh XML failure/error/skip 均 0。实际 PRODUCTION_ALERT 为 5 条（取消两次，加 dismiss/retry/ignore 各一次）；向根报告曾口头误计六次，已纠正，原 XML 不改。取消时原标签已移除、dispatcher close=0/global tasks 可执行/runtime ENABLED/旧 workspace 字节不变；新文件通过真实 shell 入口，脏文本重复打开复用同一标签，真实 save 落盘。明确新活动后新 layout 最终发布；重试两次发布字节完全相同（draft ids/顺序/选中项/anchor/caret），忽略时故障仍启用且旧字节不变。

按独立审查仅补夹具：构造失败独立释放 shell 和自有 Stage、保留主失败与 suppressed、恢复 user.home；close 失败 fallback 只作 fixture 安全收尾，不能计为产品成功。dispose 使用有界后台结算；成功草稿原子移动计数只在原 mover 返回后递增，所有四场景完成均要求 draftMoves>0。没有替换 mandatory guard/decision supplier，也没有产品修改。

最终命令：`& ./docs/superpowers/verification/evidence/app-shell-workspace-exit-worker/Run-Targeted.ps1 -Run final-related-focused`。实际参数由 parameters.json 保存，JDK 25.0.1+8、offline/no-daemon/max-workers=1/rerun-tasks，清除 live/JVM 注入环境，新独占 profile `datacube-shell-workspace-worker-5a9c7f35-0c29-4e29-8132-fe7956bc84a4`。exit 0，实际 test task 与 fresh XML：新 AppShellWorkspaceShutdownTest 4、AppShellShutdownRecoveryTest 9、SqlEditorDraftIntegrationTest 17、SqlWorkspaceUiTest 14，共 44/44，failure/error/skip 全 0，BUILD SUCCESSFUL 34s。

`Verify-Evidence.ps1` exit 0，首次 manifest.json 记录三轮原件 SHA、final 源码七项 SHA 全匹配、自有 profile Java 进程为空。`git diff --quiet baseline -- src` exit 0，`git diff --check` 通过。实际成功路径 fileDispatcher close exactly 1、runtime CLOSED、registry CLOSED，重新打开 SqlDraftStore 成功读取最终布局证明 writer lock 已释放；provider requests=0。资源安全 fallback 不作为通过证据。

交还：所有本轮 Gradle/test JVM 已退出，自有 Stage 关闭，桌面与 Gradle 执行权交还根线程。未暂存/提交；未执行 full/buildSrc/image/main 新复验，也无原生、正式 launcher、真库或发布证据。下一步根线程独立审核与工程复验；本轮未复现产品缺陷，只增加行为证据。
