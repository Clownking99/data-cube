# N1 工作区原生退出外置 probe worker 账本

基线 5d5ce30fa7206a6242c46352e73d39f02bfa82ba，分支 codex/workspace-native-exit。计划完整阅读；仅 docs 外置 probe/编译启动脚本，不修改 src/test/产品，不操作桌面或 Gradle。

## 检查点 1：可审核外置夹具

目标：供根线程原生操作实际 AppShell/原 SqlDraftUi/SqlWorkspaceUi/store/mandatory guard/production Alert。改动：WorkspaceNativeExitProbe.java、Invoke-Probe.ps1、raw .gitattributes -text。本轮标题 UUID namespace，真实 Stage 外置关闭 hook 复用 ShutdownQuarantine begin/settle，明确无正式启动器/migration/update 链路。

初始化为程序化合成真实两文件读取、checkpoint、选中/位置和旧 workspace seed；实际 observe+refresh 确认 runtime ENABLED/非 busy，无固定 sleep。只在自有 workspace.bin 原子 mover 注入 IOException，其余原 mover 委托成功后计数，providerResolver 拒绝全部请求。真实 Alert 只被动观察 owner/title/default/buttons/key/click，不 fire/关闭任何 decision。

可观察夹具按钮：释放工作区故障、重新开启故障、打开 after-cancel.sql；按钮明确夹具。modal 时父按钮不可用，Launch parameters/manifest 提供专用 releaseMarker，根可文件工具创建作夹具释放，mover 下次观察，不冒充原生产品动作。CANCELLED 保留窗口/恢复入口并记旧 SHA，COMPLETED 检查实际关闭/锁重新打开再退出；FAIL passed=false，fixture 安全清理不作为产品完成。

验证/未验：尚待 --system 已审计 D main 镜像 javac；原生实际全部待根线程。模块列表确认存在；旧 coordination 脚本通配路径检索一次未命中，仅检索错误。
下一步：只编译当前外置源码，保留日志/参数/镜像三 artifact SHA/exit，交还根审查；不自行纯合成 GUI 自测。

## 检查点 2：首编译失败与审查补强

compile-main-first 以只读 D main 已审计镜像 --system 编译，exit1：AppShell.openSqlFile 是包内入口，默认包外置 probe 不能直接调用。保留 raw/parameters/result，属外置编译失败，不是产品红/原生结果；改反射调用同一实际入口。独立审查补强：实际 quarantine、IGNORE prior SHA/fault 断言、正常/RETRY 与真实 frozen entries/selection/positions 比较、draft id 持久化存在、retry 当前尝试末两 SHA 一致、无明确活动重复 cancel 冻件不变、checkpoint acknowledgement、JSON SHA 数组/编号summary、失败收尾10s有界。

## 检查点 3：编译交付，原生待根验证

实际命令：`& ./docs/superpowers/verification/evidence/workspace-native-exit-worker/Invoke-Probe.ps1 -Mode Compile -ImageRoot D:/Projects/朝花夕拾/build/jpackage/DataCube -Run compile-main-reviewed`。只读已审计 main 镜像，JDK 25.0.1+8 javac --system，exit0；仅 unchecked 编译提示，无 GUI/Gradle 执行。最终源码 SHA256 `EC6A027A8F5457CED06B2936E2B52DC9EB4BA0F9FB0FF7FD8E9220714F60516C`，source/镜像三 artifact SHA/实际参数/首次失败与本次结果见不可覆盖的两 run 和 compile-manifest.json。PowerShell parser 通过，src/test 相对 baseline 无改动，git diff --check 通过。

启动需根先使用 intended ImageRoot 执行 Compile，再 `-Mode Launch -ImageRoot <same image> -Run <new unique run> -CompileDirectory <successful compile run>`。源码或镜像三 artifact SHA 与 compiled.json 不同即拒绝 Launch；每 Launch 新 UUID profile/title，javaw Start-Process WindowStyle Hidden，不隐藏 FX Stage。releaseMarker 位于本次独占 profile，实际 marker 路径由 parameters/launch.json 返回；文件工具创建只算夹具释放。请仅归档本轮 events.jsonl、summary编号原件、launcher日志等已知自有证据，不把整个 profile 当成输出。

交付范围：仅外置 probe、Invoke-Probe、raw .gitattributes、两编译 run/compile-manifest 和此 worker 账本。未启动任何 GUI/fixture/javaw，没有自有运行进程需关闭；桌面与 Gradle 从未取得或使用，root继续独占。原生输入、实际 Alert 决策、启动后的资源/存储断言全部待 root，不宣称编译等于行为通过；未修改产品、test、根计划/账本，未暂存提交。

## 检查点 4：启动器可见交互后继修正

根报告 baseline-cancel-sequence 已启动 PID13524 且 READY，但 sky list_windows/list_apps 无自有窗口；不计原生通过，Hidden 是候选，未归因产品。根的 Launch 后 LASTEXITCODE null 检查另有假误报，实际 PID 已运行，两类问题分别保留原件。仅 Invoke-Probe.ps1 增加根明确授权的 -VisibleInteractive：本轮可见交互 Stage 使用 Normal/javaw 无终端，默认仍 Hidden；parameters/launch 显式记录 visibleInteractive，launch 记录 windowStyle，Launch 尾显式 exit0。

新 launch-visible-correction/result.json 记录 parser PASS、脚本新旧 SHA、旧 compile-manifest SHA、Java源码不变、未重新编译/未启动GUI。旧 compile-manifest 和所有 compile/run 原件保持原值，不能拿旧 scriptSHA 当作后继脚本执行证据。根清理旧专用 PID 并新 profile 重启，worker 不操作 GUI/Gradle；原生可见性与行为待根独立验证。
