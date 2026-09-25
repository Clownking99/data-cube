# SQL 小窗口连续工作流：本地证据

维护者在 G8 交付后要求“继续推进产品”。本轮仅修复 G8 已实际记录的 SQL 页底部裁切，沿用本地实现、验证与合并授权。基线 main 为 5b721aa62bf1d1e616552094740be8e87051a8c4，分支 codex/datacube-editor-small-window；不重复 G1–G8 已有功能，不扩充格式化主线。

## 行为与实现

查找/替换展开时，工具栏、SQL/结果最小高度、状态和草稿区会超过小窗口高度。SQL 页现在按需滚动到底部，顶部布局入口仍可返回；正常尺寸保留自动填充。外部根 VBox、收藏来源、编辑器/结果实例、连接选择、键盘及生命周期守卫保持原身份。

产品只修改 SqlEditorPane 的内容容器；没有增加依赖、格式迁移、连接动作或持有资源。测试新增 7 个参数场景，并为四个既有夹具补齐离屏 CSS 初始化，不删断言、不加 sleep 或 skip。

## Requirement / Evidence

下列方法均位于 com.datacube.fx.SqlPanelLayoutIntegrationTest，使用临时文件、合成 SQL 与 DraftConnectionProbe：

| Requirement | Evidence |
| --- | --- |
| 小窗展开替换后草稿开关、清除与说明可达；能返回顶部布局入口 | findAndReplaceKeepDraftControlsReachableWithoutChangingWork(double,double,String)：640×600、900×700、1200×900，明暗，分屏/SQL/结果，4 例 |
| 滚动与布局不改 SQL/物理换行、dirty/undo、结果对象/选区或文件，不取连接 | 同一方法检查原始混合换行文件、编辑器快照、结果列表身份与 Beta 选区，provider/session/metadata/network 全为 0 |
| 从底部 Ctrl+F/H/G 进入可见输入框，Escape 回到可见编辑器 | keyboardEntryFromBottomRevealsFocusedControl(String)：3 例；真实 JavaFX Stage 与 post-layout pulses，断言精确焦点节点及可见范围 |
| 布局分隔、冻结/关闭、文件、收藏、历史与新脚本行为不回退 | 扩展定向 12 suites / 139 passed；全量另见下表及实际 JSON |

这些是 FX 合成事件与布局测试，不是原生鼠标/键盘验收。

## 命令、环境与证据

JDK 25.0.1+8、JavaFX 25、Gradle 9.2.0；所有命令 --offline --no-daemon --console=plain。每次运行独占合成 user.home，测试内 java.awt.headless=false，移除 DATACUBE_REDIS_* / DATACUBE_SCHEMA_DIFF_*；镜像构建不带测试 init script。证据脚本沿用 G8 helper 的环境变量名，但本轮路径、profile、日志与产物全新。

可复现命令（在目标仓库执行；将证据 helper 复制到独占临时目录，再传入 Repository 和全新 ProfileName）：

```powershell
& ./run-check.ps1 -Name full -Repository '<checkout>' -ProfileName fresh-full -GradleArgs @('clean','test')
& ./run-check.ps1 -Name buildSrc -Repository '<checkout>' -ProfileName fresh-buildSrc -GradleArgs @(':buildSrc:test','--rerun-tasks')
& ./run-check.ps1 -Name image -Repository '<checkout>' -ImageBuild -GradleArgs @('jpackageImage')
& ./audit-image.ps1 -Repository '<checkout>' -Name runtime
```

最终定向过滤器与每次完整参数见 [实际结果](2026-09-25-sql-small-window-results.json)。[原始日志及 helper](evidence/sql-small-window/) 保留通过、失败与摘要；完整 XML 留在本轮临时证据目录，仓库保留 XML 清单摘要及新增场景所属 suite XML。源文件摘要绑定本轮工作区修改，避免把日志中的旧 HEAD 误当成未改基线。

| 执行 | 实际结果 |
| --- | --- |
| viewport-red | 4 tests / 0 passed / 4 failed；包括 3 个真实裁切与 1 个测试换行假设错误 |
| viewport-red-corrected | 产品修改前 4 tests / 1 passed / 3 failed，正常大窗通过 |
| viewport-green-candidate | 77 passed / 0 skipped |
| keyboard-entry-check | 3 passed / 0 skipped |
| targeted-final | 96 passed / 0 skipped |
| branch-final-full（首次全量） | 3825 tests / 3807 passed / 15 failed / 3 live skipped |
| targeted-final-expanded | 四个夹具补 CSS 后，139 passed / 0 skipped |
| branch-full-verified | clean test；307 suites / 3825 tests / 3822 passed / 0 failed/error / 3 live skipped，3m14s |
| branch-buildSrc / image / runtime | 强制 buildSrc 8/8；jpackageImage 成功，34s；无测试配置/探针泄漏，Oracle/PG 驱动发现成功且 connectCalls=0 |
| main full / buildSrc / image / runtime | 待合并后新 profile 执行 |

三项 live skip 是 Redis、Oracle Schema Diff、PostgreSQL Schema Diff，缺少显式真库环境/写入开关且本轮主动移除环境；跳过不算通过。历史 SchemaDiffService 偶发失败若未复现，只记未复现，不称根因已修复。

## 失败与待验

首次红测对 CodeArea 的换行归一化假设错误已修正为编辑器快照/物理文件分别断言，真实裁切失败保留。首次全量 15 例是 ScrollPane 未建立 skin 时旧夹具查找不到后代控件；临时 Scene + applyCss 后保留所有原有业务断言，新脚本 draft bind 也在相同初始化后检查。

原生工具先遇到 GetCursorPos 0x80070005，重新枚举并绑定同一窗口后取屏仍失败 CreateForMonitor 0x80070057；按边界停止重试，没有成功截图或原生输入。独占空白探针只输出 OUTPUT_SCALE=1.5，核对 PID 13240、JDK javaw 路径和精确标题后停止进程；强制清理不计优雅关闭。原生滚动、完整键盘遍历与实际缩放观感仍待验，旧 G8 截图未复用为本轮证据。

真库、OS 多屏/缩放切换、签名、正式启动器/安装升级/回退、远端 CI、真实用户任务继续待授权或人工验收。本轮不读取 .testagent、真实连接、凭据、SQL 历史或业务文件，不访问真实实例，不 push/tag/PR/发布。

## 审查与交付检查点

目标、过程、失败与下一步见 [实施检查点](../plans/2026-09-25-sql-small-window.md)。分支全量、强制 buildSrc、镜像/零连接探针均已通过；6 项改动的源文件/测试 SHA 与最终验证时一致。根节点属性、连接选择插入点、取消/关闭守卫与分隔条逻辑审查无额外问题。27 份日志/元数据/新增布局 suite XML 已归档核对，原始证据在首次暂存前设置 -text。保留 unchecked 与 JEP 493 提示，不称零警告。当前准备本地提交合并；main 复验仍待执行。
