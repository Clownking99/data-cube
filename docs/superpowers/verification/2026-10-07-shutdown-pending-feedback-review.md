# 退出等待可见反馈：独立审查

## S0 基线与限定目标

客户端日期2026-10-07。维护者继续推进产品，沿用GPT-6.1-sol开发、root审查/修正/集成模式。main98e6d0797f6890aa8e971455eee567b19de28c33及范围内干净，前轮Verify37603054420四任务成功回执仅存为历史基线。复用既有干净worktree，新分支codex/shutdown-pending-feedback-20261007从此main开始；不创建线程/恢复自动跟进。

root独立读取WindowShutdownController、DataCubeFx接入、AppShell.shutdownAsync、ShutdownQuarantine、AsyncShutdownCoordinator及现有真实handler回归：当前pending只禁用body、没有可见等待说明，尚未有本轮行为红灯。选定即时等待card、取消/可恢复错误清除、成功关闭清除、partial替换已有fatal说明；不改事务/状态机/5秒或15秒等待，不加按钮/自动退出/重启/计时百分比。迁移确认拒绝前不进入等待。普通窗口布局和双主题必须保留。

已向既有6.1-sol开发下发任务，批准唯一反馈节点结构、自然固定文案和真实shown四例首红。要求异步时序可控、重复close计数、pending各结局互斥、再关闭新等待、真实PG/Oracle15秒守卫与真实AppShell取消/production Alert。root单独维护协调证据，worker暂独占Gradle，最终源码须独立复审再全量。脚本沿用已审流程但使用新独占前缀及动态显式期望计数，语法校验不算产品验证。

安全/未验：.testagent禁读改枚举暂存；mock/合成profile/独占temp，不真库、原配置/凭据/历史SQL/业务文件、剪贴板、原生输入、安装更新、外部联系。原生键鼠/OS缩放/完整在途流程/终态进程内恢复/无Gate启动/安装升级签名和完整M8仍待验；旧孤立FX超时根因未知。常规只读检查中不存在root AGENTS.md及误用runner文件名已核正，C worktree沙盒内Git解析失败后使用授权提升读取成功，不作为产品失败。

本轮按既有授权最终仅推送main并检查精确SHA CI；v3.2.9对象及目标不动，datacube保持PAUSED。当前没有新测试通过声明。下一步审查首红/最终源码和原始证据。

## S1 首红核验

root独立读取001-red-pending命令/exit/实际Task/XML：基线98e6d07、新UUID profile及8.3 temp、compileTestJava成功、4tests/4failed/0error/0skip，全部为实际handler发起关闭后#shutdown-pending-notice为空。确认为行为缺口，不是编译失败或旧结果。四例为两主题与900×600/1200×800；worker保留red-source/red.patch。结构和固定自然文案已获审查认可，继续唯一反馈节点及旧路径集成。尚无本轮绿灯/全量/镜像/main验证。

## S2 初绿与审查补充

002-green-pending原始XML独立核对：production handler13+quarantine3共16项，0failure/error/skip，exit0。生产唯一VBox字段保存反馈，showPending先于shutdown supplier，RECOVER与COMPLETED清理，FATAL用相同样式及不变固定文案替换。源码最小变化符合目标，仍待最终集成回归。

审查明确真实Shell可控guard取消不足以证明modal交互，要求将现有AppShellWorkspaceShutdownTest四例接同一controller，保留生产Alert/owner/default按钮、旧字节/冻结布局/文件准入/资源断言，新增pending与cancel/dismiss/retry/ignore/完成时的反馈生命周期断言。worker接受，最终定向由五套增为六套；AppShellShutdownRecoveryTest原源不改但新跑。不增新业务状态机或放宽断言。

协调Push-Verify脚本针对前轮已发生的状态查询TLS超时增加最多三次连续只读重试，另显式验Windows两个必需步骤与现有tag对象/目标；只做语法核验，还未执行网络写。当前完整工程/镜像/main/最终CI尚待。

## S3 最终定向与独立源码审查

003-expanded-targeted原始命令、Task、exit0及六套XML独立核对：handler13、quarantine3、grid6、shell recovery9、workspace4、coordinator5，共40/40，0failure/error/skip。PG实际15327ms、Oracle15142ms默认物理等待；5条production Alert覆盖取消两次/真实新活动保存、重试、忽略与合成dismiss，保留原工作区字节、冻结快照、资源和provider请求0等断言。不是程序化fixture cleanup当产品恢复。

root逐行审核controller及三测试差异，等待节点安装早于supplier；pending反复关闭不再次调用shutdown，RECOVER/COMPLETED确实移除而非隐藏、第二次关闭新节点，fatal互斥替换且迟到不恢复，原状态机及5秒/15秒未改。真实workspace Fixture接同一controller、生产modal按钮仍可操作，取消/成功后清理反馈；最终fixture disposal先清除handler，显式不算产品恢复。AppShellShutdownRecoveryTest原文件保持但新跑。

root已查看003两张886×563纯合成Scene图，900×600 shown Stage明暗提示完整可读；几何、实际Text全文与contrast>=4.5由真实测试断言补足，不称原生桌面。仅要求移除三测试新增EOF空行，语义不变，不为此重复003；必须冻结最终字节。审查通过、已批准worker按冻结版串行全量clean test、root buildSrc强制、jpackageImage。当前全量/main/镜像/CI仍待，旧历史通过不充当本轮证据。

## S4 分支完整验证及源码提交

root独立Audit-Worker实算冻结4文件、实际Task/exit/新鲜XML：定向40、全量314套4012总数/4009执行通过/3live跳过、buildSrc强制8，0failure/error。全量4m51、buildSrc10s；image强制14task执行58s。3跳过为Redis standalone和Oracle/PG SchemaDiff live，原原因保留不计通过。365份worker原件独立实算长度/SHA与清单一致，完整patch/runner及四文件冻结未变。

源码4文件已提交6ef4b3eee757f79f46499bd483579b71fa777104。root镜像审计passed：模块/183文件/cfg无测试或profile/测试JVM选项混入；已独立审查外置probe及driverFor源码，只做Oracle/PG驱动发现，connectCalls=0，未调用connect/open。模块102405401字节、SHA06D4956F06D5B54C4EB6D7633C735BBADFDDA20F30D4B243C2B4D14BEA5D160D，exe/cfg/modules完整身份归档。

worker最终报告末尾多余空行仅规范为一个EOF换行，原始证据不改；raw目录起始-text属性保留。准备冻结Git字节、证据提交与main合并/新复验。当前本地分支通过不能代替main或最终CI通过，全部原生/完整M8等边界保持。
