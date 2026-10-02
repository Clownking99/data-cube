# DataCube：完整 AppShell 关闭与恢复验收

客户端日期：2026-10-02。维护者要求继续推进既有产品验收；沿用已授权的 GPT-6.1-sol 实施、当前线程独立审查、本地提交及 main 合并复验。本轮不扩展产品功能，不重复上轮字段模态输入的工具失败。

## 基线与范围

- 当前 main 与复用 worktree 均为 `4d1940aa1506be61026a4419de777e7cc4c83fe5`，授权范围干净；worktree 为 `C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾`，本轮分支 `codex/app-shell-shutdown-recovery`。
- 上轮字段原生输入、键盘、模式和实际小窗未验通过；工程测试通过不能替代这些证据。本轮选择独立的完整 AppShell 在途退出、可恢复取消及超时安全路径。
- 源码审查候选问题：`AppShell.shutdownAsync()` 在受管标签关闭结果产生前永久关闭 `SqlFileEntry` 与 `SqlFileTabRegistry`。需要真实 AppShell 的 FX 行为测试证明取消后打开、去重或保存身份失效，再决定最小修复；源码推断不计为已复现。

## 分工与检查点

现有 `/root/sol_schema_diff_stability` GPT-6.1-sol 代理承担 N1：建立红绿复现、最小修复和原始证据。当前线程承担 N0 计划及基线，N2 独立审查/具体返工，N3 分支与合并 main 的新验证及证据交接。复用现有代理与 worktree，不创建更多线程或自动恢复 heartbeat。代理不得提交、合并 main 或改交接/路线图；当前线程审查后统一提交集成。桌面与 Gradle 单一执行者，代理交还后当前线程再运行。

每个检查点记录目标、改动、实际验证、失败/未验和下一步。首次失败日志保留，不覆盖；每次验证独占新 profile，所有测试使用 mock 和合成数据。

## 本轮完成依据

1. 实际 AppShell 取消退出后，原有文件身份仍可去重，已有与新 SQL 文件入口及 Save/Save As 身份可继续使用；重复取消与重试不会提前关闭全局资源。
2. 关闭等待期间拒绝新文件准入，旧一代读回调、错误反馈及 recent 写回不越过关闭边界，也不在恢复后的新一代制造标签或污染身份。永久关闭只发生于可以进行最终清理的阶段；不能靠清空后重建注册表恢复身份。
3. 实际 AppShell 中受管在途资源的关闭请求保持所有权：5 秒标签超时提示不伪报完成；可控 mock 释放后最终完成、连接释放/事务行为与迟到 UI 抑制符合实现；重复关闭不重复清理。若验证 15 秒物理等待失败，必须是 FAILED_PARTIAL/不可重试隔离，不能称为可恢复取消。
4. 桌面证据按程序化初始化、合成 FX 与原生动作分别记录；实际 AppShell 外部 fixture 的关闭 handler 若复刻启动器，须明确不等于正式 DataCubeFx 启动器。避免直接启动会检查外部更新的正式入口。原生输入工具失败不得用程序化 fire 充当成功。
5. 修复相关定向测试、全量、buildSrc、jpackageImage 在分支与合并 main 各重跑；汇总原始 XML、命令退出码、跳过原因、镜像隔离和资源计数，冻结源码并核对两边一致。审查通过后本地提交、合并 main、复验、更新交接与待验项。缺少正式启动器/真库/发布证据仍单列，不宣称 M8 或发布验收完成。

## 安全边界

禁止读取、修改、枚举、暂存或清理 `.testagent/`；Git 状态与 diff 显式排除。禁止读取原有凭据、连接配置/profile、SQL 历史、业务文件。仅 mock JDBC、合成 profile 和 UUID 独占临时目录；不访问真实数据库，不触碰既有 Oracle 验收表。不 push/fetch/tag/PR/发布/安装更新/外部联系，不读取其他应用内容。桌面动作按 Computer Use 技能执行。常规修复自主决定并记录；必要权限或重大范围变化才请求维护者决定。

## N0 记录

- 当前目标：建立完整 AppShell 关闭/恢复的新证据并修复已复现缺陷。
- 改动：建立本计划及独立 codex 分支；尚无产品修改。
- 验证：当前 main/worktree HEAD 与授权范围干净；独立阅读 AppShell、SqlFileEntry、SqlFileTabRegistry、ContentTabPane、AsyncShutdownCoordinator、ShutdownQuarantine、AsyncTabCloseCoordinator、DataGrid 保存关闭及相关测试。
- 失败/未验：源码候选问题尚未行为复现；完整 AppShell 在途/恢复、原生关闭和正式启动器均未获本轮证据。上轮字段模态输入工具失败仍待验。
- 下一步：GPT-6.1-sol 建立复现、最小修复和新原始证据，当前线程独立审查后集成复验。
