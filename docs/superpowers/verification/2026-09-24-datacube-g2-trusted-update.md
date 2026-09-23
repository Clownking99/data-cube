# G2 / M2 实际证据账本

范围：仅 M2 本地可信更新与失败恢复；不开展 M3–M8。日期 2026-09-24。
main 基线 `8a729746a7f93c9399963930836ac69e4721eec8`，tracked 干净。
分支 `codex/datacube-g2-trusted-update`，独立 worktree。
全部测试使用 mock、合成镜像、临时内存密钥和隔离 profile；未访问真实更新服务器、连接、凭据、历史或业务文件，未读取/修改 `.testagent/`。

原始日志目录：`C:\Users\hetia\AppData\Local\Temp\datacube-g2-1f44fa9766d448568538fca7e99585c3`。
测试参数：`--offline --no-daemon --console=plain --init-script <日志目录>/isolated-tests.gradle`；
`DATACUBE_G2_PROFILE=<日志目录>/profile`，init script 给 Test 任务设置该 user.home，去掉 Redis/schema-diff 真库测试环境变量。
不把旧报告、UP-TO-DATE 或跳过项计为本轮通过。

## 检查点 1：基线与失败证据

- 当前目标：验证现状并复现唯一临时文件缺口。
- 改动：M2 小设计、边界、失败矩阵；新增行为测试。
- 验证：`test --tests 'com.datacube.update.*' :buildSrc:test --rerun-tasks`，baseline 日志 exit 0；原 updater 3 项和 buildSrc 8 项。
- 失败：`g2-red.log` 唯一临时文件测试在旧实现失败，2 次请求返回同一路径（1 项 / 1 失败），未写该公共临时文件。
- 下一步：签名、受限下载、唯一暂存与服务取消。

## 检查点 2：核心与真实 helper 合成验证

- 当前目标：无真实安装的信任链和回退验证。
- 改动：Ed25519 精确字节协议、默认无生产信任根时禁止自动执行；流量/时间/重定向边界；安全解压；单次交接；主窗口正常关闭；精确目标和启动确认；保留 previous/failed-new。
- 验证：`g2-first-compile.log` 4 项通过；`g2-core-third.log` updater 77 项通过；后续 `g2-review-targeted.log` 78 项通过。其间每次确实执行 test。
- 失败与修复：`g2-core-first.log`、`g2-helper-first.log` 因 JDK 25 的 `jdk.crypto.ec` 弃用警告被 Werror 拒绝；移除冗余模块依赖，保留 Werror。当前 JDK 的 Ed25519 在 java.base，仍需对打包运行时复验。
- 失败与修复：`g2-core-second.log` 77 项 / 6 失败；`g2-helper-debug.log` 9 项 / 6 失败，定位 Windows PowerShell 环境不可用 Get-FileHash。改为 .NET SHA-256；没有放宽摘要校验或断言。
- 审查修复：交接后再次点击可重复 helper，已加服务实例级交接封锁；目标 supplier 只读取一次；注册表仅精确位置命中，查询失败/超时返回 UNKNOWN；不使用 DisplayName 或父子目录推断。
- 未验：生产公钥、受保护签名与真实发布、真实安装/UAC/执行策略、桌面升级、断电恢复；M2 最终只能列部分完成。
- 下一步：最终异常矩阵、全量/buildSrc/image、差异审查与本地集成后复验。

## 检查点 3：worktree 验证与审查

- 当前目标：本地工程集成门禁，仍不执行实际升级。
- 改动：补充真实 helper 的安装版交接和新进程仍存活场景；拒绝带数据的 ZIP 目录条目，防止其绕过展开字节计数；服务关闭先封闭回调再取消 I/O；安装版/便携版 UI 文案分开说明备份责任；增加实际截止时间关闭阻塞流的行为回归。
- 验证：`g2-full.log`，`test --rerun-tasks`，exit 0，2m42s，273 suites / 3,595 tests / 3,592 passed / 0 failures/errors / 3 skipped。此全量在最后的 UI 文案调整和新增 1 项定时器回归之前；不将 3,595 写作最终代码的完整测试数，main 将重新全量验证。
- 验证：`g2-targeted-reviewed.log`，最新代码 `test --tests 'com.datacube.update.*'`，exit 0，21s，10 suites / 82 tests，全部通过。包括 11 项 Windows 真实 helper 脚本测试，进程启动均注入替身，未执行安装文件。
- 验证：`g2-buildsrc.log`，`-p buildSrc clean test --rerun-tasks`，exit 0，6s，1 suite / 8 tests 全部通过，4 actionable tasks 全部执行。
- 验证：`g2-image.log`，`jpackageImage --offline --no-daemon --console=plain`，exit 0，42s；无测试 init script。`g2-runtime-crypto.log` 用该镜像的 `runtime/bin/java.exe` 执行临时独立探针，Ed25519 签名/验签、SPKI 公钥解码、SHA-256 全部通过（Java 25.0.1）。没有启动 DataCube。
- 镜像核验：jimage 列表包含 UpdateManifest、trusted-keys.properties、update-helper.ps1；生成 cfg 不含测试 user.home/headless/profile 或合成更新启动参数。
- 差异审查：执行入口仅接受当前 applier 的私有 Prepared；启动前重新校验摘要；无未验证 Path 的公开执行入口；close/cancel/重复请求、回退材料、精确路径、ZIP 边界和单次启动确认均审查；`git diff --cached --check` 通过。仅本地自行审查，无独立第二审阅者或远端 CI。
- 跳过：Redis live 1 项、Oracle/PG schema-diff live 2 项，均未授权且未设置必需环境；不计通过。
- 保留提示：测试源码 SqlEditorResultFilterContractTest 的既有 unchecked 提示、jlink 的 JEP 493 提示；不宣称零警告。G1 账本中的历史偶发 schema snapshot 失败未在本轮复现，根因仍未明确。
- 下一步：本地提交、main 合并及独立合成 profile 复验，再登记提交 SHA 和待外部验收。
