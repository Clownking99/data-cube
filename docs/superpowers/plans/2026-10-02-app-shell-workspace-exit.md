# DataCube：完整AppShell工作区退出决策验收

客户端日期：2026-10-02。维护者在前轮交付后要求继续推进，沿用既有GPT-6.1-sol实施、当前线程独立审查/返工、本地提交和main合并复验的授权。

## N0：基线与本轮范围

- 当前目标：补齐实际AppShell的工作区最终保存失败、实际生产确认对话框CANCEL/RETRY/IGNORE及取消后的继续工作证据。
- 改动：从main/worktree相同242946facc6c4c648cf1550d995aa9237b6544d4建立codex/app-shell-workspace-exit，复用既有独立worktree；当前没有产品修改。
- 验证：两边授权范围干净。已阅读前轮计划/交付、实际SqlWorkspaceUi.freeze/finish/validateAndSave/showDecision、SqlDraftUi及AppShell.shutdownAsync。现有AppShell恢复测试替换过mandatory guard/decision，组件测试没有完整shell；不能把这些旧结果算本轮新证据。
- 失败/未验：初次查DataCubeFx用了旧fx目录，实际由rg确认src/com/datacube/DataCubeFx.java，仅检索错误。AGENTS.md文件检索无命中（使用用户本机约定）；记忆快速检索无项目命中。当前实际workspace决策与完整shell恢复全部待本轮运行，不预设产品缺陷。
- 下一步：复用现有GPT-6.1-sol代理实施N1；根线程N2独立审查源码/原件及具体返工，N3新分支/main验证并本地集成。

## 验收矩阵

实际AppShell/SqlFileEntry/ContentTabPane/SqlDraftUi/SqlWorkspaceUi及原mandatory关闭路径；实际SqlDraftStore/SqlWorkspaceStore/SqlDraftDirectory写入自己的UUID临时目录。仅对自己的workspace.bin发布操作注入可计数的失败，草稿/checkpoint仍真实落盘，不能靠替换decision supplier、mandatory guard、全局shutdown结果制造CANCELLED。默认草稿初始化以真实事件/refresh屏障确认，不盲等或用旧XML。

1. 实际workspace失败对话框出现，目标owner是本轮shell，文案/三按钮/default cancel对应真实生产对话框。点击实际“取消退出”得到CANCELLED：旧恢复点原始字节/SHA不变，真实守卫结算范围如实记录（此前已关闭的tabs不能称为仍保留）；全局task/file入口/注册表保持可用，无连接/执行/业务历史读取。
2. 重复取消不重复全局teardown，不隐式写入/抹掉旧恢复点；无明确新活动时冻结恢复点行为保持。取消后通过真实shell准入新合成SQL文件/脚本、文件去重和保存身份继续工作；明确新活动后的再次退出使用真实新layout，成功发布后才正常关闭。
3. 实际“重试”：故障释放后重试相同冻结布局/已checkpoint的draft id、顺序/选中项/位置，最终COMPLETED，真实持久化与资源释放计数；不重放SQL/数据库写入。
4. 实际“忽略本次工作区更新并退出”：故障仍在，最终COMPLETED但旧workspace字节保持。若补充实际对话框dismiss，验证仍按默认取消语义；只记合成FX动作，不称原生键盘/标题栏验收。

凡发现产品缺陷，保留首红和原因，以最小修复及有意义回归证明；未复现则仅补证据，不新增功能/公开测试入口。不改变CANCELLED与FAILED_PARTIAL差异，不自动扩展终态恢复、正式launcher或更新链路。

## 分工、验证与交付

复用/root/sol_schema_diff_stability（GPT-6.1-sol），不创建更多线程。代理只实现限定测试/必要修复与worker账本/本轮新原件，不stage/commit/merge、不改交接/路线图或根账本。根线程统一审查、具体返工及本地集成。Gradle/桌面单一执行者，代理交还后根执行分支及main新的定向/全量/buildSrc/jpackageImage。

每个检查点记录当前目标、改动、验证、失败/未验、下一步。每次执行独占新合成profile，offline/no-daemon/rerun-tasks，清除live和JVM外部参数；只采纳本次实际test task及新XML。保留所有首红/编译/工具错误，不覆盖原manifest。镜像隔离/零连接发现、原始哈希及暂存字节独立核验，最后更新handoff/roadmap与证据等级。

禁止读取/修改/枚举/暂存/清理.testagent；Git广域状态/diff显式排除。禁止原凭据/配置/profile/SQL历史/业务文件；仅mock/合成profile/独占临时SQL与工作区。没有真库操作或既有Oracle专用表访问；不push/fetch/tag/PR/发布/安装更新/外部联系，不启动真实DataCubeFx外部更新自检。受限workspace-write下worktree写/Git元数据/必要缓存写走已授权本地动作的自动审核升级，不假定旧权限。

本轮为程序化生产对话框的合成FX证据，非原生/正式launcher/真库。原生完整退出、字段输入/键盘/小窗、FAILED_PARTIAL后产品恢复、OS多屏、安装升级/签名/CI/用户任务/发布继续单列；M8不称完成。datacube保持PAUSED，本轮交付后不自动下轮。

## 最终本地交付

限定目标已完成。新增4项真实AppShell/原mandatory guard/实际workspace原子存储和production Alert的合成FX场景；取消/重复取消、明确新活动后真实文件准入/脏文本去重/save、重试相同冻结布局、忽略保留旧字节及合成dismiss已验。原标签在决策前已关闭，不声称全部标签仍保留。每例真实草稿成功atomic publish，provider请求0；成功资源释放/真实存储锁释放均验证，不使用fixture失败清理充当成功。

实现25b770a，独立证据e94f77a，本地main合并代码b57c6acd8e811854e7ace0102a4d5b7f3996e784；产品源码未改。分支/main各新定向235/235、全量3935 passed/3 live skipped、buildSrc8/8、jpackageImage及183文件镜像/零连接审计通过，778源稳定、217 Java差异仅换行、三项SHA一致。首计数失败/口头统计更正/暂存字节拒绝和原件保留，见[独立账本](../verification/2026-10-02-app-shell-workspace-exit-coordination.md)和[实际结果](../verification/evidence/app-shell-workspace-exit-coordination/results.json)。最后证据集成不改受验源码。

本轮是程序化生产对话框的合成FX，未新增原生/真库/正式launcher证据；原生workspace CANCEL/完整shell退出、FAILED_PARTIAL后产品恢复、字段输入/键盘/小窗、OS多屏、安装升级/签名/CI/用户任务/发布继续待验，M8不称完成。既有Oracle专用表保留且未访问/清理，datacube保持PAUSED，本轮交付不自动下一轮。
