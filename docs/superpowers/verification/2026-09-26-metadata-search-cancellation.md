# 字段 / 注释检索取消后恢复：本地证据

维护者要求“继续推进产品”。本轮从 main 83a87f5d9f3ab0448629586f5a9500874e1f2192 建独立 codex/metadata-search-cancellation，修复取消或超时后读取已结束、界面仍显示等待释放资源的状态缺口。沿用本地提交合并授权及原有安全边界，不扩展格式化、数据库、发布或安装范围。

本地工程交付完成：实现 7856da2，main 产品代码 65ed18b；main 全量、强制 buildSrc、jpackageImage 与零连接运行时审计重新执行通过。原生与外部发布验收仍待验。

## 用户可见行为与设计

明确取消、超时或改变条件时继续立即失效旧结果。在读取与 JDBC 取消任一任务尚未返回时，查找入口保持关闭；两者都返回后，提示明确说明读取结束及旧结果未采用。只有用户再次明确查找才提交新请求。清空文字后即使任务结束也不开放空查询。

实现仅改 SchemaMetadataSearchDialog：同一个 Pending 保留读取完成标记和对应完成提示；完成与取消回调在 FX 线程共同检查所有者、双任务完成及可用性。关闭/目标失效不发布完成提示，旧回调不修改后继请求。取消抛错也不声称数据库成功取消，只说明读取已结束、旧结果不采用。

## Requirement | Evidence

| Requirement | Evidence |
| --- | --- |
| “继续推进产品”：取消、超时、条件变化与清空后给出准确完成状态，双任务完成前不重入 | SchemaMetadataSearchDialogTest.cancelledReadBecomesRetryableOnlyAfterBothTasksFinish(String,String,boolean)，8 组原因/完成顺序/取消抛错场景 |
| 旧结果不发布、不自动重试；明确重试绑定当前目标、Schema、模式和检索词 | 同一方法断言旧列表/预览为空、动作禁用、读取次数不增；再明确提交，核对完整 Request 和仅有的新结果 |
| 关闭/目标失效后迟到回调不能重新开放动作或修改状态 | SchemaMetadataSearchDialogTest.closedOrInvalidatedSearchDoesNotPublishCancellationCompletion(boolean)，2 例 |
| 服务与取消控制原有行为 | targeted-final：test --tests com.datacube.fx.SchemaMetadataSearchDialogTest --tests com.datacube.service.SchemaMetadataSearchTest --tests com.datacube.spi.SqlExecutionControlTest，3 suites / 33 passed / 0 skipped |

测试仅使用 mock Statement、固定合成表和受控独立任务；任务结束信号在 FX 回调入队后触发，再等待 FX 队列屏障。没有 sleep、真实 JDBC 连接或放宽旧断言。完整请求与状态断言不依赖已实现方法的返回值作自我验证。

## 本轮验证

JDK 25.0.1+8 / JavaFX 25 / Gradle 9.2.0，全部 offline；每轮独占合成 user.home，测试内 headless=false，移除 Redis/Schema Diff 真库环境。复用证据 helper 的 G8 环境变量名，但使用本轮全新目录与 profile。镜像不带测试 init script，且审计 cfg 与模块中的已知探针/夹具泄漏；驱动探针只做 Oracle/PG 发现，connectCalls=0。

| 执行 | 实际结果 |
| --- | --- |
| cancellation-red | 10 tests / 2 passed / 8 failed / 0 skipped，8 项均在旧等待文案断言失败 |
| cancellation-green | 2 suites / 22 passed / 0 skipped；第三个过滤器包名写错，未算取消控制覆盖 |
| targeted-final | 修正包名后 3 suites / 33 passed / 0 failed/error/skipped；同时生成合成探针编译 classpath |
| branch-full | clean test：307 suites / 3835 tests / 3832 passed / 0 failed/error / 3 live skipped，3m12s |
| branch-buildSrc / image / runtime | --rerun-tasks：8/8，0 skipped；jpackageImage 成功，40s；零连接运行时审计见实际 JSON |
| main-full | 65ed18b 的新 profile clean test：307 suites / 3835 tests / 3832 passed / 0 failed/error / 3 live skipped，3m14s |
| main-buildSrc | 65ed18b 的 --rerun-tasks：8/8 passed / 0 skipped，7s |
| main-image / runtime | 65ed18b 的 jpackageImage 成功，31s；cfg/模块无测试参数或已知探针，Oracle/PG 驱动发现通过且 connectCalls=0 |

完整参数、时间、exit code、实际 Test 任务执行、XML 计数/清单 SHA、日志 SHA 见 [结果 JSON](2026-09-26-metadata-search-cancellation-results.json)。[证据目录](evidence/metadata-search-cancellation/) 保存原始日志、目标 suite XML、合成探针与 helper；全部 XML 保存在独占临时目录。源文件清单绑定最终测试工作区，早期日志中的 baseline HEAD 不表示未修改的 main 已通过。

复现时将 run-check.ps1 / isolated-tests.gradle 复制到新的独占临时目录，并设置 JAVA_HOME。分别以全新 ProfileName 执行 clean test 和 :buildSrc:test --rerun-tasks；镜像以 -ImageBuild 执行 jpackageImage，再执行 audit-image.ps1。完整调用参数保留在结果 JSON。

## 失败、警告与未验

原生合成探针首次启动因 Java @args 文件的 Windows 反斜杠转义导致 NoClassDefFoundError；修正 classpath 为正斜杠后，新 profile 启动且输出比例 1.0。激活工具失败 GetCursorPos 0x80070005；重新枚举并绑定唯一窗口后取屏失败 CreateForMonitor 0x80070057，停止交互，没有成功截图或输入。核对自有进程 PID/路径/标题后停止空白探针，不算优雅关闭。保留启动错误、重试日志与 JavaFX unnamed-module 警告。

本轮原生取消/重试体验、小窗口完整键盘/滚动、字段检索原生输入、OS 多屏/缩放仍待验。真库、签名/正式启动器/安装升级与回退、远端 CI、真实用户任务及发布继续待授权或人工验收。真库 skip 不算通过；编译与打包警告如有保持原始记录，不称零警告。不得用旧 G8 或小窗口截图代替本轮证据。

## 审查与交付检查点

分支提交前的历史检查点：过程见 [实施检查点](../plans/2026-09-26-metadata-search-cancellation.md)。本轮定向、全量、强制 buildSrc 与镜像均通过；原有 3 项 Redis/Oracle/PG live 测试缺少显式真库环境而跳过，不计为通过。两个改动源文件 SHA 固定并复核未变；审查双任务所有权、取消失败、重入、关闭/失效、旧结果和明确重试，未发现额外问题。原始证据在首次暂存前设 -text，避免换行转换；日志以精确路径暂存并比对原始 blob。

交付复核：实现 7856da22ba1de7dc89626f946d41ddcbe19c1272，本地 main 产品合并 65ed18b234106c62615d399fc2181d763bf02bdd。合并前 main 仍为 83a87f5 且授权范围干净；合并后全部源码/测试/构建 Git 内容相同，两个改动文件原始 SHA 相同。main 新 profile 全量、强制 buildSrc、镜像/运行时审计均通过；分支/main 的 DataCube.exe、cfg、runtime modules 三项 SHA 完全相同。最终归档 11 个执行记录和 30 份原始证据文件，摘要校验通过。最后文档提交不改产品代码，不冒充另一套重新执行的测试。

本轮没有依赖/文件格式/版本号变更。需要回退时可审查后 revert 实现 7856da2，不删除用户数据；这只是本地回退说明，未执行回退、推送、tag、PR、安装更新或发布。
