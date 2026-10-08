# DataCube 产品成熟度推进计划

**2026-10-09 G9 当前检查点：P2已授权实施。** [整表导出可靠性计划](2026-10-08-g9-table-export-reliability.md)。P0/P1a/P1b/P1c均已独立通过；严格DDL新增12例首红、014的50冻结/1348证据/49运行快照和85当前XML已核实，1181通过、0失败/错误/跳过，旧011/A/B未变。无法保真的PG列定义明确拒绝，基本列/PK及字符numeric修饰保留，见[协调账本C4](../verification/2026-10-08-g9-table-export-coordination.md)。同一GPT-6.1-sol线程继续唯一Gradle执行者，完成P2新完整定向/clean全量/强制buildSrc/image与新镜像探针后本地提交、停写交根会话P3审查合并复验。P2/P3未完成，G9未交付，main产品未合并。datacube-g9保持ACTIVE至P3交付后暂停；旧datacube/v3.2.9及Redis/G8外部范围不动，G1–G8历史编号不变。

**2026-10-08 最新产品：[XLSX 文本保真](../verification/2026-10-08-xlsx-text-fidelity-review.md)** 已完成源码 ce2a096a、证据 5f176b0b、main 集成 8fb039c 与新复验。控制字符、回车和字面转义外观精确回读，孤立 UTF16 明确拒绝；真实查询结果发布保留旧文件并可重试。新增 142 项测试，分支/main 各定向 286、全量 4303 通过/3 live 跳过、强制 buildSrc 8、jpackageImage/183 文件隔离审计通过；镜像 20 包/40 单元格独立解码及 6 项无效文本拒绝，三产物 SHA 相同。518 份开发原件 Git 字节核验，失败与跳过保留。最终 main 推送/精确 SHA CI 以本轮 delivery-intent 所指回执为准；原生 Excel/桌面、真库、慢磁盘、整表原目标失败保护、安装签名及完整 M8 仍待验，前轮 CI 助手超时根因未知。v3.2.9 与 PAUSED 跟进不变；下方各“最新”为历史。

**2026-10-08 最新产品：[XML 导出忠实性](../verification/2026-10-08-xml-export-fidelity-review.md)** 已完成源码 c553e4c5、证据 eda89701、main 集成 33e167b7 与新复验。非法字符明确拒绝并保留原文件，合法正文/列名精确回读，旧可解析标签映射保留。新增 86 项回归，分支/main 各定向 144、全量 4161 通过/3 live 跳过、强制 buildSrc 8、jpackageImage/镜像 XML 七组回读及隔离审计通过，三产物 SHA 相同。失败与夹具/权限诊断原件完整保留，525 份 worker raw/Git 字节复核。最终 main 推送/精确 SHA CI 以本轮 delivery-intent 所指回执为准；原生/真库/慢磁盘/安装签名与完整 M8 不由本轮证明。v3.2.9 和 PAUSED 跟进不变；下方各“最新”为历史。

**2026-10-08 SQL取消执行身份修复（最新）：** 修复旧异步取消误伤后继SQL，以及旧物理cancel晚异常关闭后继连接的竞态；取消发起时绑定执行，保留原物理取消资源所有权，并补全未启动任务拒绝的句柄结算。GPT-6.1-sol开发、root独立审核修正；源f656be08、分支证据dcdfa288、main集成fd5159ce。新增15项行为/服务回归，分支/main各新定向357、全量4075通过/3明确live跳过、强制buildSrc8、jpackageImage及183文件镜像审计通过，三产物SHA一致，566份worker原件Git字节复核。首红、过期结构断言失败及拒绝遗漏补验全部保留。详见[独立审查记录](../verification/2026-10-08-sql-cancel-identity-review.md)。本轮为mock/合成FX证据；真驱动取消/事务、剩余原生/OS缩放、终态恢复、无Gate启动、安装升级签名及完整M8仍待验。旧eaa Verify无记录原因未知，本次最终main推送/精确SHA CI以delivery-intent指向的新回执为准，不预报通过；v3.2.9和PAUSED跟进不动。下方各“最新”为历史轮次。

**2026-10-07 SQL批量错误询问取消修复（最新）：** 修复取消已发生后旧“执行遇错”仍显示并卡住执行队列的问题。每次执行独立询问门禁，先禁止旧选择，再在实际取消finally后释放；保留继续/全部继续/取消/X、拒绝关闭和旧回调隔离。GPT-6.1-sol开发、root独立审核及接管修正；源7030423f、分支证据a5be5b20、main集成c5c25706。新增19个合成UI及6个helper例，分支/main各新定向249、全量4060通过/3明确live跳过、buildSrc8、jpackageImage/183文件零连接镜像审计通过，三产物SHA一致，459份worker原件Git字节复核。产品首红、夹具失败和shell统计调用诊断均保留。详见[独立审查记录](../verification/2026-10-07-sql-script-dialog-review.md)。queued脏tab文件确认前的modal顺序与慢取消调度注入均明确限定，不称原生验收。真驱动、原生/OS缩放、终态恢复、无Gate启动、安装升级签名和完整M8仍待验；最终main推送/精确SHA CI以delivery-intent指向的实际回执为准，v3.2.9与PAUSED跟进不动。下方各轮均按历史解读。

**2026-10-07 显式事务整窗退出缺陷修复（最新）：** 修复COMMIT/ROLLBACK/SET_MODE失败在退出时被吞掉并继续回滚、释放和隐藏窗口的问题。AppShell在异步workspace freeze前捕获所有SQL标签的未呈现事务，pane等真实queue idle后保留FAILED_PARTIAL可见保护；已显示旧错误、退出取消后恢复和非选中tab均有真实链路回归。源提交c71dd241、分支证据11f70af5、main集成c1711dca；新22例，分支/main各新定向207、全量4035通过/3明确live跳过、buildSrc8、jpackageImage/183文件零连接镜像审计通过，三产物SHA一致。首红、剩余竞态红灯和夹具同步失败原件均保留。详见[独立审查记录](../verification/2026-10-07-sql-transaction-shutdown-review.md)。本轮仅mock/合成；真实驱动事务、剩余原生/OS缩放、终态进程内恢复、无Gate启动、安装升级签名与完整M8仍待验。最终main推送和精确SHA CI以实际交付回执为准，v3.2.9与PAUSED跟进不动。下方各轮为历史，不把旧“最新”视为当前结论。

**2026-10-07 SQL在途整窗退出集成验收（最新）：** 新增PG/Oracle各两例真实AppShell→SQL编辑器→生产runner→mock JDBC→原mandatory guard→整窗handler回归；跨默认5秒仍等待，物理结束后回滚/关闭、队列封闭、迟到回调抑制均通过。未发现生产缺陷，产品源码未改。测试5b3d7172、证据8acdb188、main集成cf8a4390；分支/main各新定向184、全量4013通过/3明确live跳过、buildSrc8、jpackageImage/183文件零连接镜像审计通过，三产物SHA一致。378份worker raw/Git及含报告的原件清单均复核，夹具失败和报告换行归档诊断保留。详见[独立审查记录](../verification/2026-10-07-sql-inflight-shutdown-review.md)。非可取消COMMIT在途、真驱动取消/事务、原生/OS缩放、终态恢复、无Gate启动、安装升级签名及完整M8仍待验；最终main推送和精确SHA CI以交付回执核对，v3.2.9与PAUSED跟进不动。下方“最新”均按历史轮次解读。

**2026-10-07 退出等待可见反馈（最新产品）：** 关闭进入在途后即时显示等待与对话框指引；取消/可恢复异常清除、成功关闭清除、partial互斥切换保护说明，保留事务/资源/关闭时限。GPT-6.1-sol开发、root独立审核；源码6ef4b3e、证据b6cd398d、main集成f94c102。分支/main各新定向40、全量4009通过/3明确live跳过、buildSrc8、jpackageImage及镜像零连接审计通过，三产物SHA一致；366份worker原件Git字节复核。详见[独立审查记录](../verification/2026-10-07-shutdown-pending-feedback-review.md)。首红与调度诊断保留。仅工程与合成FX交付；原生输入/OS缩放、完整在途/终态恢复、无Gate启动、安装升级签名及完整M8仍待验。最终main推送与精确SHA CI以交付回执核对；v3.2.9不动，datacube保持PAUSED。下方各“最新”按历史轮次解读。

**2026-10-07 退出部分失败可见反馈（最新产品）：** FAILED_PARTIAL后现在在禁用工作区之外持续显示原因与核对/手动结束指引，保持终态隔离、不重试事务、不自动强杀/重启。GPT-6.1-sol开发、root独立审核；源码33e7773、证据210574a、main集成84dd0c0。分支/main各新定向32、全量4005通过/3明确live跳过、buildSrc8、jpackageImage和镜像零连接审计通过；三产物SHA一致，375份worker原件Git字节复核。详见[独立审查记录](../verification/2026-10-07-shutdown-failure-feedback-review.md)。首红/夹具及归档诊断保留。仅工程与合成FX交付；终态进程内恢复、原生输入/系统缩放、无Gate启动、安装升级/签名及完整M8仍待验。最终main推送和精确SHA CI以交付回执核对；v3.2.9不动，datacube不恢复。下方各“最新”按历史轮次解读。

2026-10-07 CI 跟进：收藏小窗口最终证据 bdf274c 已推送；Verify 37593098366 首次 Windows 旧概览排序测试发生 5 秒 FX 等待超时，Windows linked image 跳过，其他三任务成功。独立审查无确定根因；保留失败原件，同 SHA 失败任务只重跑一次，不改 timeout/断言。同 SHA 唯一一次重跑 attempt 2 四任务成功，Windows 单元测试和 linked image 实际通过；首次超时未复现但根因未知，仍为待诊断项。源码/测试/timeout未改。本次证据提交合并后的最终 SHA 另以新 Verify 回执核对，不预报结果。

2026-10-07 最新产品：[SQL收藏小窗口修复](../verification/2026-10-07-favorites-compact-review.md)已main集成f5bc602并独立复验。真实双主题480/640窗口的取消/说明裁切已修复，滚动与焦点可达、SQL内部编辑及旧保存确认状态保留。分支/main各定向84、全量3997通过+3明确live跳过、强制buildSrc8、jpackageImage/零连接镜像审计通过；三产物SHA一致、370份开发原件Git字节验证。原生/OS缩放/安装升级签名及完整M8仍待验；只按已有授权推送最终main并核对精确SHA CI，v3.2.9不移动，datacube保持PAUSED。下方各“最新”按历史轮次解读。

2026-10-07 最新产品：[SQL收藏写入/刷新结果修复](../verification/2026-10-07-favorites-refresh-outcome-review.md)已main集成00527dd并新复验。真实临时库重复UUID红灯已关闭，完成状态与重读失败分离，显式重读、真正写失败、取消关闭及迟到回调契约保留。分支/main各定向61、全量3974通过+3明确live跳过、强制buildSrc8、jpackageImage/零连接镜像审计通过；三产物SHA一致、397份开发原件Git字节验证。原生/OS缩放/安装升级签名及完整M8仍待验；最终main仅按既有授权推送、精确SHA CI由交付回执核对，v3.2.9不移动，datacube保持PAUSED。下方各“最新”按历史轮次解读。

2026-10-07 最新产品：[表/视图查找小窗口修复](../verification/2026-10-07-schema-object-compact-review.md)已本地main集成53d2dc6并独立复验。分支/main各定向160/160、全量3954通过+3明确live skip、强制buildSrc8/8、jpackageImage/零连接镜像审计通过，三产物SHA一致；719份worker原件字节核验。名称候选裁切与焦点可达性已修复，仍只是工程/合成FX证据，原生/OS缩放/安装签名与完整M8未完成。仅按授权更新main，v3.2.9不移动；实际托管CI见最终交付回执，datacube保持PAUSED。下方各“最新”按历史轮次解读。

**2026-10-06 字段查找小窗口修复（最新产品）：** 真实640×480 JavaFX窗口复现按钮裁切及同焦点Ctrl+F无法回到查询；GPT-6.1-sol实现、root独立审核返工，新增滚动与焦点可见性，保留目标绑定/明确动作/取消关闭。产品58278f7、main集成194371a；分支/main各新定向103、全量3944 passed/3 live skipped、强制buildSrc8、jpackageImage/镜像零连接审计通过，三产物SHA一致，1040份worker原件字节复核。见[本轮独立账本](../verification/2026-10-06-metadata-search-compact-review.md)。这是工程与合成FX交付，原生字段键盘/OS缩放、完整在途恢复、安装升级与签名仍待验；完整M8不称完成。v3.2.9保持原发布内容，本轮仅更新main，datacube保持PAUSED。

**2026-10-06 正式发布读回：** 维护者已明确授权推送、删除旧验收 tag 并以正式版本 tag 触发自动打包；v3.2.9 指向 main 0f6ba02656fcf3752b180514c72e79151a3320df，GitHub [Build and Release 37448530784](https://github.com/Clownking99/data-cube/actions/runs/37448530784) 成功，[Release](https://github.com/Clownking99/data-cube/releases/tag/v3.2.9) 已发布 ZIP 和 EXE。本轮只读再次核实两资产 uploaded、远端 main/tag 指向一致。下方“不推送/不打 tag/未发布”是各轮历史边界，已被维护者后续明确授权覆盖；自动打包发布成功不代替安装升级、生产签名或完整 M8 验收。

2026-10-05 最新工程修复：[Windows CI 短路径修复与独立复验](../verification/2026-10-05-windows-ci-paths-review.md)。28项托管Windows失败已通过真实8.3别名红绿定位，修正更新路径误判、规范交接及SQL夹具身份；真实junction仍拒绝。实现d5dd127，main代码14875d7；两阶段定向180、全量3938 passed / 3 live skipped、强制buildSrc8、jpackageImage/镜像隔离/仅驱动发现通过。托管Verify 37319792169的Windows单测/jlink、Linux、Redis、wrapper全部通过，完整CI与原件见账本。新runtime不复用旧原生证据；真库跳过仍未验，安装升级/签名/完整桌面及M8发布仍待验。按维护者现有授权推送main并保留既有tag指向，datacube保持PAUSED，无新功能范围。

2026-10-05 最新交付：[工作区原生退出限定矩阵8/8完成](../verification/2026-10-05-workspace-native-escape-completion.md)。真实Esc取消后旧字节不变、准入/runner恢复；同profile原生Retry保持冻结布局并正常释放资源/锁；main新profileRetry亦已完成。仅证据/文档、无新工程测试声明；旧1038原件核验不变。M8完整发布仍待验，datacube保持PAUSED，不自动下一轮。下方7/8和待Esc为历史。


2026-10-05 最新限定原生状态：[工作区键盘续验](../verification/2026-10-05-workspace-native-key-acceptance.md)已7/8，真实Enter取消和main新profile原生Retry均已验证；只剩Esc实际按键，专用Alert已备，根停止输入。产品未改，无新Gradle声明，历史951原件不变；M8发布仍待验，后文旧6/8/main Retry待验均按历史轮次解读。


日期：2026-09-23；G1–G8 及本地跟进更新：2026-10-02。状态：M0、M1、M3、M4a–d、M5、M6、M7 本地工程完成，已合并 main 并复验；M2 部分完成（本地实现已合并 main 并复验，生产签名/真实升级待验）；M8 部分取得原生及指定 Oracle 真库证据，已取得限定 mock 桌面证据，最新字段原生键盘/小窗口有限尝试仍未完成，工程证据已本地合并并复验；完整发布验收待验，其他外部目标须明确授权。
2026-10-02 最新工程准备（原生目标未完成）：[工作区原生退出夹具与main复验](../verification/2026-10-02-workspace-native-exit-coordination.md)。外置真实AppShell/原guard/store/production Alert夹具已审查编译，只对专用workspace发布注入失败，程序化READY/草稿写入和可见Stage枚举已观察；Computer Use访问自有窗口却返回app approval timed out，没有新原生动作或截图，矩阵全部待实际跑。工具应用访问授权问题待维护者回复，不用编译/初始化或工程通过提升原生等级。main工程集成0a7ec1197f78916b3cb16221a38013efed4b0795，无产品改动；两阶段新定向235/235、全量3935 passed/3 live skipped、buildSrc8/8、jpackageImage/183文件镜像/零连接审计通过，778源稳定、217 Java仅换行、三SHA一致。首编译/隐藏启动/退出码误报/权限超时/字节及空白元数据失败保留；自有进程已安全核实停止，不算产品COMPLETED。[原生待验矩阵](../verification/evidence/workspace-native-exit-coordination/native-acceptance-matrix.json)、[实际工程结果](../verification/evidence/workspace-native-exit-coordination/results.json)。取得工具权限后仅继续本限定矩阵，datacube保持PAUSED，M8完整发布继续待验；下方各“最新”为历史轮次。

2026-10-02 最新本地验收：[完整AppShell工作区退出决策及main复验](../verification/2026-10-02-app-shell-workspace-exit-coordination.md)。新增4项实际shell/原guard/真实workspace原子存储和production Alert合成FX，用实际cancel/retry/ignore和合成关闭Dialog验证旧字节/冻结布局/明确新活动/文件准入及资源所有权；实际5条Alert，原tabs已移除，成功每例草稿实际写入、资源释放一次/锁释放，provider请求0。产品未改，实现25b770a、独立证据e94f77a、main代码b57c6acd8e811854e7ace0102a4d5b7f3996e784；分支/main各新定向235/235、全量3935 passed/3 live skipped、buildSrc8/8、jpackageImage/183文件镜像隔离/零连接发现通过，778源稳定、217 Java仅换行、三项SHA一致。首夹具后缀失败/口头统计更正/暂存换行审计失败原件保留。真实workspace CANCEL仅补合成FX；原生workspace/完整shell退出、正式launcher、终态产品恢复、字段原生输入/键盘/小窗、OS多屏、其他真库、安装升级/签名/CI/用户任务/发布仍待验，M8保持部分完成。datacube保持PAUSED，不自动下一轮；下方旧“workspace CANCEL未验”按历史轮次解读。

2026-10-02 最新本地验收：[完整AppShell DataGrid在途退出及main复验](../verification/2026-10-02-app-shell-grid-exit-coordination.md)。实际生产openDataGrid、真实pane/service/JdbcDataEditor与原mandatory guard，PG/Oracle合成类型共6项新用例证明首行回滚、第二行在途保留已提交首行/第三行不执行、默认5秒pending和真实15秒FAILED_PARTIAL资源隔离/迟到UI。实现e7f55b7；首全量2项旧Metadata夹具失败保留，受控PG初始化红绿后只修夹具前置条件d67bb37，产品未改、历史现场mode未知。独立证据911e5c5，main代码ecbc421a899a17ef04eac02582402f7473591d20；分支/main各新定向184/184、全量3931 passed/3 live skipped、buildSrc8/8、jpackageImage/183文件镜像隔离/零连接发现通过，777源稳定、216 Java仅换行、三项SHA一致。夹具失败后清理不算产品恢复；无新原生/真库/正式launcher运行，工作区真实CANCEL、原生完整shell/FAILED_PARTIAL后产品恢复、字段全链/键盘/小窗、OS多屏、安装升级/签名/CI/用户任务/发布仍待验，M8保持部分完成。datacube保持PAUSED，不自动下一轮；下方旧“DataGrid事务/物理15秒未验”只对应历史轮次。

2026-10-02 最新本地修复：[AppShell 关闭恢复与独立复验](../verification/2026-10-02-app-shell-shutdown-recovery-coordination.md)。真实shell合成FX首红确认退出取消后SQL文件身份失效；修复暂停/代号/保留注册表、私有关闭结算、提交后失败隔离和best-effort释放。实现404c3eb，本地main合并代码523d234；两阶段各新定向107/107、全量3925 passed/3 live skipped、buildSrc8/8、jpackageImage/镜像隔离/零连接发现通过。新证据包括真实文件Save/SaveAs、旧回调抑制、默认5秒warning仍pending及受管合成资源释放1/finalizer1；不称完整DataGrid事务/物理15秒或原生/正式launcher已验。真实工作区CANCEL、原生退出、字段全链/键盘/小窗、其他真库、安装升级/签名/CI/用户任务/发布仍待验；M8继续部分完成。原始失败和陈旧XML排除保留，没有新真库，既有跟进PAUSED；下方各“最新/当前”都是对应历史，不自动扩展。

2026-10-02 最新本地证据交付：[字段原生键盘限定尝试与独立复验](../verification/2026-10-02-metadata-native-keyboard-coordination.md)。真实AppShell新合成profile/外置mock，21状态/39原JPEG，原生菜单/空框/取消/正常空闲退出可验证，mock1/1且检索/写/执行0；owner/modal输入递送失败，type_text两次空、Tab/Esc未到达、模式未变、缩小无尺寸变化。原生输入/键盘/多结果/条件失效/小窗口目标未完成，不以预填或程序化输入替代、不因工程通过提高M8等级。仅外置证据01c9cb0本地合并main03a552b，分支/main各新定向83/83、全量3917 passed /3 live skipped、buildSrc8/8、jpackageImage/镜像/零连接发现通过；产品未改，无新真库。首定向漏suite/profile拒绝/PNG计数错误保留，已准确纠正。本轮有限交付后停止，不再盲试，不自动恢复跟进或扩大功能/外部范围；datacube保持PAUSED。完整桌面/发布待验与下方历史原生/Oracle证据分开记录。

2026-10-02 前轮本地交付：[SchemaDiff 夹具稳定性](../verification/2026-10-02-schema-diff-stability-coordination.md)，仅修并发记录和mock schema构造，产品未改；实现75ed0c6、main验收合并a353117，新定向9/9、全量3917 passed /3 live skipped、buildSrc8/8、新镜像/零连接发现通过，三项产物与分支相同。8192次真实mock完整记录/关闭、两线程unsafe越界与safe400000完整均有新原始证据。历史那一次异常缺cause，精确归因保留未知；本轮无新原生/真库，不提升M8证据等级。下方协作P3/Oracle等记录是其各轮历史，完整桌面/安装升级/签名/CI/用户任务/发布仍待验，既有跟进PAUSED。

基线：`main` / `792600c59e49bce3b300a71ccf412ed53d2b42e9`。

本计划承接维护者认可的整体产品审阅，重点由零散功能扩充转向安全一致性、连续工作流和可信交付。编制本文件不代表任何功能已经修复或发布。新会话先读[交接文档](../../handoffs/2026-09-23-product-maturity-goal-handoff.md)。

## 1. 产品目标与范围

服务日常使用 Oracle / PostgreSQL 查询、检查、编辑和导出数据的开发者，以及 Oracle → PostgreSQL 迁移维护者。Redis 保持现有功能与安全边界。

核心用户任务：找到正确目标 → 编写并执行明确范围的 SQL → 理解结果 → 审阅并保存修改 → 安全退出 → 下次继续。

本轮路线完成应带来：

1. 连接上显示的只读与生产环境规则在所有消费该连接配置的写入口一致生效。
2. 表格修改的保存时机明确，查询结果切换不丢失用户的浏览状态。
3. 当前语句执行、错误定位、补全与对象检索减少重复选择和切换。
4. 更新可信性、实际数据库兼容性和迁移验证有可检查的证据。
5. 日常工具栏、历史、草稿、文件与收藏分工清晰，不重复建设已有能力。

明确不做：新增大量数据库、AI 自动执行 SQL、Agent/插件平台、云同步、遥测、完整 SQL IDE 重写、全面 UI 换皮、无限扩充格式化语法。复杂 SQL 美化转入真实脱敏样本驱动的维护，不放弃语义保持类缺陷修复。

## 2. 事实、风险与证据等级

以下是 2026-09-23 的代码/文档审阅结果，不是本轮真实数据库或完整桌面体验测试。

| 编号 | 当前事实或风险 | 依据与限制 |
| --- | --- | --- |
| F1 | 表数据入口未统一使用连接的只读配置 | `ConnectionTreePane` 的 TABLE 入口传 `false`；`DataEditService` 写方法直接获取编辑器；`ConnectionManager.acquire` 未应用 `ConnectionSafetyOptions`。SQL 会话路径已有独立安全处理。静态链路已确认，真库写入未执行。 |
| F2 | 表格离开脏行或翻页可触发提交 | `DataGridPane.installRowLeaveCommit/commitRow/gotoPage`；`JdbcDataEditor.runGuarded` 已有单行影响数护栏，不能把它说成完全无保护。 |
| F3 | 整批执行完成后展示，切换结果重置视图 | `SqlEditorPane.onExecute/showBatchSelection`、`SqlBatchResults`。执行器先累计 outcomes；UI 的 1,000 项上限不等于执行阶段总内存预算。尚无本轮 OOM/时延压测结论。 |
| F4 | 执行范围只有有效选区或全文；补全仍有上下文局限 | `SqlExecutionRange.resolve`；`SqlEditorPane.parseAliases/addRef/membersFor` 使用正则，显式 Schema 在 alias 映射中丢失。已有 Ctrl+点击对象跳转，不应作为全新功能重复实现。 |
| F5 | 检索已有多个入口，但对象搜索范围有限 | README 的连接树查找、单 Schema 表/视图查找、历史/草稿/文件说明；不能说“尚无搜索”或“尚无工作区恢复”。 |
| F6 | 自动更新下载主要检查 Content-Length | `UpdateApplier.download`、`UpdateService.downloadAndApply`、`ReleaseInfo`；未看到该链路验证内容摘要或发布者签名，不能把 HTTPS/长度校验称为完整发布来源认证。 |
| F7 | 迁移验证目前是目标端统计，不是两端数据一致性证明 | `PgVerifier.verify` 只接收 PG 连接，读取对象数和 `n_live_tup`，后者为估算值。 |
| F8 | 自动化基线较广，但有未关闭的不确定性 | [数组美化验收](../verification/2026-09-23-sql-formatter-array-layout.md)：最终 3,481 通过、3 live 跳过；首轮 SchemaDiffServiceTest 失败后复跑通过，原因未明。不是本次文档工作的测试结果。 |
| F9 | 真实用户效果与完整发布验收仍需新证据 | 旧路线图保留试用、安装升级等待办；旧记录可能部分被后续补验覆盖，应逐项对账，不照抄旧勾选状态或宣称均未完成。 |

源码路径均相对仓库根，实施前重新检查调用链和行号。文档是线索，当前源码和可复现证据优先；不要把风险推断升级为已复现故障。

## 3. 授权、安全与集成边界

- 当前会话仅编写计划和交接，不实施、不提交、不推送、不设置运行目标或创建新会话。后续实现由维护者在新会话提交目标指令启动。
- 延续既有协作约定：实现使用独立 `codex/` 分支/worktree；完成审查和必要验证后可本地提交并合并回 main。常规技术和交互选型记录理由后自主推进，不逐项等待确认。
- 推送、创建/删除 tag、Release、PR、实际安装/自更新、外部联络、付费服务/签名证书/凭据配置需另行明确授权。不能因“目标模式”扩大权限。
- `.testagent/` 是用户内容，不读取、不修改、不暂存、不清理。已有 worktree、未提交改动、旧验收目录不是可随意清理的临时文件。
- 不读取真实连接配置、SQL 历史、业务文件或凭据来构造测试。仅使用合成 profile、mock JDBC 和独占临时目录。真实数据库读写也须有明确目标和权限，不因位于本机/私网就推定允许。
- 不上传 SQL、Schema、结果、连接信息或诊断原文；日志使用固定原因码、脱敏对象身份及必要元信息。
- 客户端只读是防误操作措施，不是数据库权限沙箱；SELECT、函数、触发器、DDL 隐式提交的副作用不能靠词法识别作绝对保证。最终权限由数据库账户限制。
- 草稿/历史/工作区恢复不自动连接、执行或重放事务；保持现有文件原子发布、连接稳定 ID、固定执行目标、迟到回调与关闭守卫。
- 并行代理仅在当时用户或适用指令明确允许时使用；本计划不额外授予子代理权限，不要求安装旧 superpowers 技能。

## 4. 里程碑、顺序与依赖

成本为相对复杂度，不是已承诺工期。每个子增量都应能单独回归和回退。

| 阶段 | 优先级 | 交付物 | 依赖 | 成本 |
| --- | --- | --- | --- | --- |
| M0 | P0 | 基线、验收账本、已知异常复查 | 无 | 小 |
| M1 | P0 | 所有 ConnConfig 写入口的统一只读与生产确认 | M0 | 中–大 |
| M2 | P0 | 更新完整性、来源验证方案、失败恢复 | M0；M1 优先交付 | 中–大，含外部决策 |
| M3 | P1 | 表格待保存修改与显式提交 | M1 | 大 |
| M4 | P1 | 结果状态保留、整批资源预算、增量展示 | M0；沿用 M1 边界 | 中–大 |
| M5 | P1 | 当前语句执行、错误定位、作用域补全 | M1；M4 的来源身份模型 | 大 |
| M6 | P1 | 字段/注释检索、SQL 收藏、入口整理 | M0；M5 元数据能力可复用 | 中 |
| M7 | P2 | 迁移预检查、逐表报告与对账 | M1 的原则；独立迁移安全设计 | 大 |
| M8 | 贯穿各阶段 | 桌面、真库、更新与发布验收；逐项保存证据 | 各阶段实现；外部目标与权限 | 持续，含外部依赖 |

默认顺序：M0 → M1 → M2 可自主完成部分 → M3 → M4 → M5 → M6 → M7；M8 每阶段更新。签名配置/真库授权等外部事项登记后，不冒险绕过；可继续无依赖的本地工作，但不得把相关阶段判为全部完成。

建议以独立目标运行：G1=M0+M1；G2=M2；G3=M3；G4=M4；G5=M5；G6=M6；G7=M7；G8=获授权后的最终 M8。新会话默认只启动 G1，完成后交付，不自行把目标扩大到整张路线图。

## 5. M0：建立本轮可信基线

- [x] 核对 main SHA、工作区状态、worktree、工具链及适用 AGENTS；不复用旧分支作为新任务工作目录。
- [x] 为新阶段建验收账本，记录代码基线、命令、通过/失败/跳过、桌面/真库/发布证据层级。
- [x] 对已有晚近验收记录去重对账；既有功能不重新从零实现。
- [x] 在隔离构建环境运行全量单测与 buildSrc 测试、开发镜像；记录环境差异。
- [x] 复查已记录的 SchemaDiffServiceTest 偶发失败；有限复跑并保留首轮证据。无法复现时记为“原因未明”，不改成“已修复”，不通过加 sleep、放宽断言或新增 skip 消除红灯。

退出：有可重复的测试/构建基线，或明确的既有失败清单及影响范围；阻断 M1 验证的基线问题优先处理。不要求为非阻断历史疑点无限重复运行。

## 6. M1：统一写入安全规则（首个目标）

### M1.1 写入口清点与策略设计

- [x] 建立“入口 → 编排服务 → JDBC 会话/runner → 策略 → 测试证据”矩阵。
- [x] 至少覆盖 SQL 执行、EXPLAIN ANALYZE、表增删改、表结构设计、对象 DDL、序列变更、Schema Diff 部署，以及关闭/退出时事务处理。
- [x] 明确独立迁移连接和 Redis 不使用关系库 ConnConfig 只读开关；标注现有模型的适用范围，不把它们静默记为“已统一保护”。不在 G1 顺便重建 Redis/迁移权限模型。
- [x] 定义无 UI 依赖的统一写入准入策略；在服务/执行边界拦截，不仅在菜单层检查，也不依赖 JDBC setReadOnly 单独兜底。
- [x] 区分可读操作、明确写操作、需要执行语句的计划分析与不确定操作。只读状态拒绝明确写入；不确定 SQL 沿用或加强保守处理，不能把词法检查称为数据库无副作用证明。

### M1.2 服务层实现

- [x] 优先封住 DataEditService，再接入 TableDesignService、DdlService；复查 SchemaDeploymentService 和 JdbcEditorSession，复用其已有效的门禁，避免第二套相互冲突规则。
- [x] 只读拒绝发生在获取写会话/准备语句/执行写 SQL 之前，失败信息固定且明确。
- [x] 生产写入需要明确目标、操作种类和范围的确认；确认与不可变请求身份绑定，不能拿旧确认执行新 SQL 或另一个目标。
- [x] 明确配置删除、改名、类型变化和执行前只读/环境收紧的处理；准入与执行使用一致身份，不回退同名连接，不因配置变化悄悄放宽权限。
- [x] 新操作的权限收紧不能导致对在途事务的隐式提交、强制回滚或重放。回滚、取消、关闭清理仍须可达；已有未提交事务的“提交”单独检查并明确提示。
- [x] 审查共享 JDBC 连接的事务隔离风险；必要的最小连接隔离可纳入本阶段，独立提交验证，不借机重构全部连接架构。

### M1.3 界面与交互一致性

- [x] 表数据页、对象/序列/表设计页显示正确目标与只读原因；明确写操作禁用并解释，读取、预览 DDL、导出仍可用。
- [x] 生产目标提示与服务端准入共享同一策略信息；取消确认不产生写操作。
- [x] 不新增“忽略只读仍执行”的绕过按钮，不暗中修改用户的连接设置。

### M1.4 必须通过的验证矩阵

| 场景 | 必须证明的行为 |
| --- | --- |
| Oracle/PG × 只读 × 每个明确写入口 | 服务拒绝；mock 写连接获取和写语句调用次数为 0；UI 不提供可执行绕过 |
| 可写开发/测试连接 | 正常路径不被误拒；原有行数、参数绑定和事务保护不回退 |
| 可写生产连接 | 未确认/取消为 0 写；确认后只执行绑定目标和请求 |
| 配置删除/类型变化/安全收紧/迟到确认 | 不执行错误目标；旧许可失效；不按名称替换 |
| 直接调用服务而不经过 UI | 同样受保护，不可通过内部公开入口绕过 |
| 只读查看、DDL 预览、导出 | 保持可用，不因保护写入而全面禁用数据库浏览 |
| 在途执行、待提交事务、取消和关闭 | 既有隔离与清理契约保持；不隐式提交或将取消说成回滚 |

退出：入口矩阵中所有适用写入口均有服务层门禁和可追溯行为测试；全量回归、buildSrc、jpackageImage 通过；完成本地差异审查并合并 main 后复验。原生桌面/真库/外部发布按 M8 单列，不以 mock 代替。G1 的“完成”只指上述本地工程目标，不代表已发布或所有真库场景已验收。

## 7. M2：可信更新与失败恢复

本地设计见 [G2 实施记录](2026-09-24-g2-trusted-update.md)，协议、信任根迁移与恢复边界见 [更新协议](../../updates/trusted-update-protocol.md)，实际命令/失败见 [G2 账本](../verification/2026-09-24-datacube-g2-trusted-update.md)。下列勾选仅为本地工作，不代表签名发布和真实升级通过。

- [x] 写更新威胁模型：传输损坏、错误资产、来源被替换、固定临时名冲突、压缩包路径、替换中断和重启失败。
- [x] 下载使用独占临时目录、合理大小/时间限制和明确取消；验证预期版本/平台/内容摘要后才允许进入执行与替换路径。
- [x] 区分完整性和真实性：同一被攻破 Release 中的普通校验和不能证明发布者身份。设计受信任签名/公钥及轮换方案；凭据、证书采购和远端配置需授权，不能在仓库生成并提交私钥。
- [x] 缺失或不匹配的验证信息禁止自动执行，不为兼容旧版本静默降级；提供清楚的手动下载/旧版迁移说明。
- [x] 替换使用逐次唯一 staging，检查精确路径归属；验证解压结构和越界条目，拒绝不确定路径。
- [x] 区分下载完成、替换完成、重启成功；保留可恢复版本，不在新版本未通过启动确认时过早删除回退材料。
- [x] 用合成安装目录和注入下载/进程边界验证失败，不真正安装或替换用户正在使用的软件。

退出：本地更新状态机和异常矩阵有验证，公开的摘要/签名协议与兼容迁移方案明确。签名配置、真实升级、远端发布仍待授权时，M2 标为部分完成，不宣传“安全更新已全面验证”。

## 8. M3：表数据编辑的显式保存

实施设计见 [G3 页面修改与显式保存](2026-09-24-g3-explicit-grid-save.md)，本轮新证据、失败及待验见 [G3 账本](../verification/2026-09-24-datacube-g3-explicit-save.md)。实现 `92a03a1`，main 合并 `3ebe872` 已复验；下列勾选仅表示本地工程完成，原生桌面/真库待验。

- [x] M3a：引入页面级变更集，标识新增/修改/删除；行切换只移动焦点，不发写请求；支持逐项及全部放弃。
- [x] M3b：提供 SQL/参数含义预览与显式保存，接入 M1；区分“保存到数据库”和“保存 SQL 文件”。敏感值不自动写日志。
- [x] M3c：处理翻页、刷新、筛选、关闭和退出时的待保存修改；默认不自动提交，取消动作保持编辑。
- [x] 定义第一版事务范围：同页变更是整体事务还是可报告的逐条提交，实施前落盘并在 UI 说明；不把“保存”模糊地当成全批可回滚。
- [x] 保留无主键情况下的精确定位/影响行数护栏；增加可判定的旧值冲突提示，不用“最后写入覆盖”掩盖并发更新。
- [x] 测试 NULL/空字符串、类型转换、主键修改、部分失败、保存中关闭、多标签和安全配置收紧。

退出：用户能在保存前识别、检查和撤销变更；仅明确保存触发写入，取消不写；每条失败和实际提交范围可见。原生 UI 关键路径按 M8 验证。

## 9. M4：查询结果的连续性与资源控制

M4a–c 实现 `f7fa9bc`，main 合并 `4d0467a` 已复验，见 [G4 账本](../verification/2026-09-24-datacube-g4-results.md)。维护者继续推进后，M4d 实现 `d40504e`、main 合并 `435df76` 也已复验，见 [M4d 账本](../verification/2026-09-24-datacube-g4d-results.md)。以下勾选仅代表本地工程证据。

- [x] M4a：同批次结果按 occurrence 身份保存筛选、排序、列显示/宽度/顺序、滚动与选区；重复 SQL/重复列/等值行不串状态；新批次和关闭释放状态。
- [x] M4b：建立执行阶段及保留阶段总预算，覆盖行数、列数、宽文本/LOB 表示、结果个数和摘要。超预算明示保留/省略状态，不通过增大 JVM 堆掩盖问题。
- [x] M4c：逐语句发布只读完成事件、当前进度和已返回结果；UI 更新有背压与批次身份，取消/关闭/新任务后旧回调失效。
- [x] 延续连接与事务串行执行；不要为了“先显示”并行执行原脚本语句或悄悄重跑查询。
- [x] M4d：每标签最多 3 份跨批次固定结果，共享有限行/单元格/文本预算；显示锁定目标、来源 SQL、Schema 和固定时间。超限拒绝新增而不驱逐旧项，仅本标签内存，关闭释放，不自动重查询或磁盘持久化。
- [x] 本地过滤仍标明“已加载”；沿用显式数据库筛选的准入，不增加隐式抓取或重查询，不把“导出全部”偷换为未经授权的重查询。

退出：A/B 结果往返不丢视图；长批次可看到已完成项；预算与取消在合成压力用例中可测，未保留数据有明确说明。真实驱动的抓取、取消行为仍须独立验证。

## 10. M5：以语句上下文为中心的 SQL 工作流

实现 `76dd221`、边界修正 `c62e86c`，main 最终合并 `0e7ca79` 已复验，见 [G5 账本](../verification/2026-09-24-datacube-g5-sql-context.md)。以下勾选只代表本地工程；Oracle 未确认错误位置、PG BEGIN ATOMIC 与复杂投影明确降级，原生桌面/真库仍待验。

- [x] M5a：独立新增“执行当前语句”和可配置快捷键，不静默改变原“执行选中/全部”。执行前显示范围；注释、空白、不完整语句和无法可靠识别的边界不给扩大执行范围的隐式回退。
- [x] Oracle 匿名块/包、斜杠分隔和 PostgreSQL dollar quote/函数体先建立方言样本；不要以分号 split 取代现有切分规则。
- [x] M5b：保留原始执行快照与编辑器偏移映射；错误定位只用驱动可确认的位置，正文已变化时提示过期，不跳错行或修改 SQL。
- [x] M5c：补全保留限定 Schema、quoted identifier、CTE、嵌套查询作用域与别名遮蔽；元数据按明确目标惰性读取，有超时和取消，不扫描所有连接。
- [x] 分别记录执行、抓取、渲染计时的口径，不能把已有 elapsed 数字说成全流程耗时。
- [x] 格式化继续验证 token/引用/注释保持、幂等、范围外不变、一次撤销；任何解析依赖引入先审查许可证、体积、方言覆盖和维护成本，不默认完整重写。

退出：当前语句边界可解释且有反例测试；错误定位不会指向后改文本；跨 Schema/嵌套别名补全不混表。无法证明的复杂方言明确降级，不能生成误导性候选或扩大执行范围。

## 11. M6：检索、复用与入口整理

维护者单独授权 G6 后实施；实现 `d4b02de`、main 合并 `c182128` 已重新通过 3753 passed / 3 live skips、fresh buildSrc 8/8 及 jpackageImage。设计与检查点见 [G6 实施记录](2026-09-24-datacube-g6-discovery-library.md)，失败历史、合成截图和明确待验见 [G6 账本](../verification/2026-09-24-datacube-g6-discovery-library.md)。以下实现勾选只表示本地工程完成。

- [x] M6a：在明确选定 Schema 范围内，按字段名/对象注释找表；对象名、字段名、注释匹配明确区分；读到什么就标明什么，不把不完整元数据当作不存在。
- [x] 结果支持只读查看数据、查看 DDL、生成未执行 SELECT；动作明确，不把输入搜索词当作执行授权。
- [x] M6b：命名 SQL 收藏的最小版本：名称、正文、可选分组、查找、修改和显式删除；不保存密码字段或结果，不自动绑定同名连接，不代替历史/草稿/源文件。SQL 正文本身可能含敏感值，明确提示明文存储。
- [x] 原子持久化、损坏恢复、容量和隐私提示纳入收藏设计，不默认云同步。
- [x] M6c：依据当前截图和核心任务整理工具栏；保留常用执行/取消/事务/保存，低频文本操作进入分组菜单并保持快捷键。保留现有雾紫品牌与主题，不做整体换皮。
- [ ] 完整桌面验收仍待补齐：已有实际 JavaFX 100%/150% 合成窗口、窄窗、明暗主题及 Schema→保存的原生 Tab 截图；搜索快捷键上下文通过 FX 行为测试。原生模态输入、完整原生 AppShell 流程、OS 缩放切换与多屏未验，不宣称消除了所有拥挤或可访问性问题。

退出：给定字段或注释能找到范围内对象；收藏可再次找到并离线打开；主要动作可发现，旧快捷键和安全规则不回退。跨 Schema 全局索引、连接分组/SSH/SSL 配置和完整脚本项目管理列为后续候选，不在本阶段自动扩展。

## 12. M7：迁移能力形成可信闭环

维护者单独授权 G7 后实施；主体 cc726a2、驱动修复 8b85bd6、载荷修复 ab6b61c，最终 main 代码合并 552b709 已独立通过 3809 passed / 3 live skipped、buildSrc 8/8、jpackageImage 和无连接的镜像驱动发现。见 [G7 账本](../verification/2026-09-25-datacube-g7-migration-evidence.md) 与 [实施检查点](2026-09-24-datacube-g7-migration-evidence.md)。勾选仅指本地工程；原生桌面/真库/发布待验，G8 不自动启动。

- [x] M7a：把现有“验证”准确标为目标端统计，估算行数明确标识；不把目标对象存在当成迁移数据正确。
- [x] M7b：迁移前检查版本、权限、类型映射、编码/时区、对象依赖、不支持项、目标已有数据和覆盖规则；破坏性操作明确目标与影响。
- [x] M7c：逐表任务状态、错误阶段、重试入口、脱敏报告；持久化检查点不包含密码，恢复不能默认重放写入。重试先验证身份、目标漂移与幂等性。
- [x] M7d：定义一致性窗口/源快照和目标校验时点，再做逐表精确行数、空值/关键列统计与可选分块校验。区分时区、浮点、LOB、字符规范化及不同数据库类型的比较语义。
- [x] 持续变化的源库、权限不足、不支持类型和抽样校验必须显示“无法证明/部分覆盖”，不能假报全量一致。
- [x] 首版不承诺 CDC、任意存储过程自动等价转换、跨库全事务或一键回滚全部迁移。

退出：用户能在执行前知道哪些不能自动迁移，执行后逐表了解成功/失败/未知与实际验证范围。先用合成导出文件和 mock 验证；真库迁移/对账在一次性授权环境验收。

## 13. M8：贯穿每阶段的验收与发布门槛

2026-09-30 新授权 Oracle 验收已实际执行，详见 [授权真库账本](../verification/2026-09-30-oracle-live-acceptance.md) 与 [实际结果](../verification/2026-09-30-oracle-live-acceptance-results.json)。仅指定 scmtest 服务、唯一专用表及 INSERT/UPDATE，绝不 DELETE/TRUNCATE/DROP；三张专用表和各 3 行合成数据保留。首轮 16/17 发现取消错标超时，修复实现 101f90a，main 代码 fbe6e30；分支与 main 新镜像真实 17/17，各 33/33 连接且 0 删除。main 新定向 114/114、全量 3916 passed/3 live skipped、强制 buildSrc 8/8、jpackageImage/镜像零连接审计通过，三产物 SHA 相同。Oracle 原生已取得只读数据页、字段真实命中 → SELECT 未执行/显式执行、表菜单 DDL、正常关闭；字段结果转数据/DDL、原生写确认/全键盘/在途关闭仍未验，取消实测约 3–9 秒不保证立即返回。原有 Schema Diff live 含 DROP USER，不能启用或算通过；PG/Redis/其他真库、正式启动器/安装升级/签名/CI/用户任务/发布仍待验，M8 不称完成。以下较早段落的“无真库/Oracle 原生”仅为当轮历史，不覆盖新证据。

2026-09-25 维护者明确授权 G8 本地验收。实际原生桌面范围、两处修复、失败限制和新自动化证据见 [G8 账本](../verification/2026-09-25-datacube-g8-local-acceptance.md)；本地交付已完成（实现 b1135d9，main 产品代码 4885c40，证据集成 e65edb3；main 全量 3815 passed / 3 live skipped，buildSrc 8/8，镜像/零连接探针通过）。总体 M8 仍为待外部验收，以下完整桌面/真库/发布条目不因局部证据自动全部勾选。

同日维护者要求继续推进产品，跟进 G8 的 SQL 小窗口草稿控制裁切；实现与新回归见 [小窗口账本](../verification/2026-09-25-sql-small-window.md)。本轮原生取屏失败，布局/键盘 FX 证据不替代完整原生验收；本地工程已交付：实现 3440a50，main 产品代码 584699c，新 profile 全量 3822 passed / 3 live skipped、强制 buildSrc 8/8、镜像/零连接审计通过。

2026-09-26 继续推进字段/注释检索的取消恢复：修复双任务已结束但提示仍等待的状态缺口，覆盖超时/条件变化/关闭和明确重试，见 [取消恢复账本](../verification/2026-09-26-metadata-search-cancellation.md)。本地工程已交付：实现 7856da2，main 产品代码 65ed18b，新 profile 全量 3832 passed / 3 live skipped、强制 buildSrc 8/8、镜像/零连接审计通过；原生工具再次失败仍单列待验。

2026-09-27 原生工具恢复后，已用三个独立合成 profile 验证字段名/字段注释输入、超时/明确取消、双任务相反完成顺序、明确重试/结果预览、条件变化失效、Ctrl+F/Enter、明暗和正常/在途关闭，见 [原生补证账本](../verification/2026-09-27-discovery-native-acceptance.md)。本轮产品源码未改；新全量 3832 passed / 3 live skipped、buildSrc 8/8、镜像/零连接审计通过。仅对话框局部流程，不代表完整 AppShell 下游动作、真实驱动取消、OS 缩放或发布验收。

2026-09-30 Schema 直接字段入口跟进已本地交付，见 [本轮账本](../verification/2026-09-30-schema-metadata-entry.md)。实现 71430dc、main 合并代码 7d5b043；新增直接入口并共用原服务/路由/单窗口，配置变化及改回、节点移除/换根/关闭取消旧读取、拒绝旧选择。main 新定向 84/84、全量 3876 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage/镜像零连接审计通过。新 PG 双入口和 Oracle 直接入口的实际 AppShell 合成结果路由与在途取消证据通过；原生仅取得 PG 入口/单窗口/提示/取消/正常关闭，模态输入仍受限，完整字段结果动作、Oracle 桌面/真库/发布保持待验。

### 本地工程门槛

2026-09-30 关闭后资源准入跟进已本地交付，见 [本轮账本](../verification/2026-09-30-metadata-search-disposal.md)。实现 219d3c2、main 合并代码 2f27e2b；实际合成 AppShell/JDBC 复现并修复字段窗口关闭后重开产生重叠连接的缺口。读取、取消与 FX 关闭清理结束前保留占用、禁用两个 Schema 检索入口并明确等待；结束后只恢复入口，需明确重试。main 新定向 96/96、全量 3888 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage/镜像零连接审计通过，三项 SHA 与分支一致；失败记录及取消异常/拒绝/Error/后台关闭证据保留。本轮无新原生/真库证据，M8 和外部发布仍待验，不自动扩大范围。

2026-09-30 名称查找读取准入跟进已本地交付，见 [本轮账本](../verification/2026-09-30-schema-object-admission.md)。实现 30556d0、main 合并代码 5092465；实际 AppShell + mock JDBC 复现 PG/Oracle × 重开名称/字段入口四项峰值 2，修复后保持 1。名称窗口关闭后等待实际读取返回及 FX 关闭清理，窗口内重复读取和内嵌字段入口也拒绝重叠；两个 Schema 菜单明确等待，结束仅恢复，需明确重试。main 新定向 180/180、全量 3898 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage/镜像零连接审计通过，三项 SHA 与分支一致。22 份记录与 109 份 raw 文件保留红灯、排队/提交时关闭、后台关闭、异常/拒绝/迟到回调和资源平衡证据；本轮无新原生/真库/发布证据，M8 仍待外部验收，不自动扩大范围。

以下勾选仅记录 G1 的本地证据，不表示 M2–M7 或最终 M8 完成。实际命令、首次失败、跳过与 main SHA 见 [G1 账本](../verification/2026-09-23-datacube-g1-write-safety.md)。buildSrc 使用独立 `--rerun-tasks` 后执行 `clean test`，确保不是 UP-TO-DATE 复用。

- [x] 定向回归先证明修复前的问题或新增行为，再验证实现；涉及取消/关闭/状态切换使用可控时序，不靠固定 sleep。
- [x] 执行 `./gradlew.bat clean :buildSrc:test test --no-daemon --console=plain`，检查 XML 中实际总数/失败/错误/跳过及原因，不只看最后 BUILD SUCCESSFUL。
- [x] 执行 `./gradlew.bat jpackageImage --no-daemon --console=plain`；正式运行时不得携带测试用 headless/profile 参数或合成验收入口。
- [x] 差异审查覆盖安全、错误恢复、性能预算、跨平台路径、资源释放和兼容性；仅在风险需要时提取大类，不进行顺手式全仓重构。
- [x] 本地合并 main 前核对工作区，必要时在最新基线上重验；main 合并后复验受影响路径并记录 SHA。

### 桌面、真库和发布门槛

- [ ] 在隔离 profile 中验收窗口、键盘、明暗主题、缩放和取消路径；不能用自动测试替代肉眼/原生交互证据。
- [x] G8 已取得明确限定的合成原生窗口证据：迁移预检查/确认/取消/在途关闭，结果 A/B/A 与固定查看器，显式保存/生产确认/只读，收藏离线打开，150% 窄窗和明暗提示，以及镜像内 AppShell 离线新脚本/输入/Ctrl+F/Escape/关闭。100%/150% 是进程输出比例；未验项目见 G8 矩阵，不能替代上方完整验收。
- [ ] 桌面锁定或工具访问失败时停止输入，记录待验项；可继续不依赖桌面的工作，不反复无效重试。
- [ ] 获授权的一次性 PG/Oracle 实例覆盖只读拒绝、生产确认、事务、表格编辑、批量结果、超时取消、Schema Diff。不得复用应用保存的公司连接。
- [ ] 经授权推送后核对同一 SHA 的 Windows/Linux/Redis/构建 CI；需要的关系库集成不得因缺凭据跳过却宣称通过。
- [ ] 经授权验证安装/便携升级、失败回退及重启恢复，再发布。源码测试通过不等于安装升级通过。
- [ ] 维护者安排 3–5 位目标用户，用合成/脱敏任务记录完成/失败、耗时、求助及误操作；代理不自行邀请、不添加行为遥测。样本用于定性排序，不虚构留存或效率提升。
- [ ] 变更说明给出源 SHA、用户可见变化、已知限制、兼容与回退方式；版本号、tag、发布动作另行确认。
- [x] G8 本地变更说明草案已附源基线、用户可见变化、已知限制与不删数据的 revert 方式；最终集成 SHA 记录于 G8 账本，未发布。

## 14. 执行与交接约定

每次工作按“核实范围 → 小设计/风险决策 → 行为回归 → 最小实现 → 验证 → 审查 → 本地集成 → 更新证据”闭环。准备进入 M2–M7 时再把该阶段拆成逐文件任务；不要现在虚构数月后的精确行号和固定工期。

状态只能使用：未开始、进行中、本地工程完成、待外部验收、已发布、阻塞（注明依赖）。勾选须附实际证据；一个目标完成不能自动勾选其他里程碑。目标工具的完成/阻塞/暂停规则遵循当时运行环境，不自行发明轮次或暂停策略。

每个检查点记录：当前目标和子任务、基线/提交、改动、测试命令和结果、失败/未验、下一安全动作。重大权限或范围变化才请求维护者决定；普通设计选择自行记录理由。

回退：按子增量提交回退，不删除用户数据；持久化格式改动先写旧版读取/降级方案。禁止 reset --hard、强制清理所有 worktree 或覆盖用户已有修改。

## 15. 当前推进账本

| 阶段 | 状态 | 证据 | 下一步 |
| --- | --- | --- | --- |
| 计划与交接 | 已提交 | M0 提交 7b52198 | 按各目标独立交付 |
| M0 | 本地工程完成 | [G1 账本](../verification/2026-09-23-datacube-g1-write-safety.md)，新基线/测试/镜像与历史失败对账 | 为 M1 保留证据 |
| M1 | 本地工程完成 | 实现 965c3a3；main 合并 6596c00；[G1 账本](../verification/2026-09-23-datacube-g1-write-safety.md)，定向/全量/buildSrc/镜像及 main 复验均通过 | G1 交付；原生桌面/真库等单列待验 |
| M2 | 部分完成 | 实现 39f7432；main 合并 153b95b；[G2 账本](../verification/2026-09-24-datacube-g2-trusted-update.md)，82 项定向、main 全量 3,593 通过/3 live skip、buildSrc 8、镜像及运行时探针通过 | 本地交付；生产公钥/签名发布/真实升级待授权，桌面交互单列待验 |
| M3 | 本地工程完成 | 实现 92a03a1；main 合并 3ebe872；[G3 账本](../verification/2026-09-24-datacube-g3-explicit-save.md)，定向 107、main 全量 3634 通过/3 live skip、buildSrc 8、镜像通过 | G3 交付；原生桌面/真库等单列待验 |
| M4a–c | 本地工程完成 | 实现 f7fa9bc；main 合并 4d0467a；[G4 账本](../verification/2026-09-24-datacube-g4-results.md)，main 全量 3656 passed/3 live skips、fresh buildSrc 8、镜像通过 | G4 本轮交付；原生桌面/真库单列待验 |
| M4d | 本地工程完成 | 实现 d40504e；main 合并 435df76；[M4d 账本](../verification/2026-09-24-datacube-g4d-results.md)，main 全量 3690 passed/3 live skips、fresh buildSrc 8、镜像通过 | G4 本地工程交付；原生桌面/真库单列待验 |
| M5 | 本地工程完成 | 实现 76dd221、修正 c62e86c；main 最终合并 0e7ca79；[G5 账本](../verification/2026-09-24-datacube-g5-sql-context.md)，main 全量 3724 passed/3 live skips、fresh buildSrc 8、镜像通过 | G5 交付；原生桌面/真库及明确降级单列 |
| M6 | 本地工程完成 | 实现 d4b02de；main 合并 c182128；[G6 账本](../verification/2026-09-24-datacube-g6-discovery-library.md)，main 全量 3753 passed/3 live skips、fresh buildSrc 8、镜像及 14 张合成桌面截图 | G6 交付；原生模态输入/完整壳流程、真库等单列待验 |
| M7 | 本地工程完成 | 主体 cc726a2；驱动修复 8b85bd6、载荷修复 ab6b61c；main 代码 552b709；[G7 账本](../verification/2026-09-25-datacube-g7-migration-evidence.md)，main 全量 3809 passed/3 live skipped、fresh buildSrc 8、镜像/驱动发现通过 | G8 已补合成迁移原生预检查/确认/取消/在途关闭；真库事务/权限/一致性与发布仍待验 |
| M8 | 本地工程与部分原生验收已交付，v3.2.9自动打包已发布；完整M8未完成 | 最新[XLSX 文本保真](../verification/2026-10-08-xlsx-text-fidelity-review.md)：分支/main各新定向286、全量4303通过/3 live跳过、buildSrc8、image/20包40单元格独立回读与6项拒绝及隔离审计；本次精确SHA CI以新回执为准；历史原生/真库证据保持各自运行时身份 | 原生Excel/LibreOffice、整表原目标失败保护、真驱动取消/事务、真实慢/网络磁盘、原生对话框/收藏/名称字段输入与完整下游动作、终态恢复、OS缩放/多屏、无Gate启动/闪屏、安装升级/回退/生产签名仍待验；datacube保持PAUSED |

## 16. 参考与历史记录

- [旧持续使用路线图](2026-08-30-product-continuity-roadmap.md)：作为已完成工作与历史边界证据，后续优先级以本计划为准；旧模板中的不可用技能或流程不自动成为新依赖。
- [当前 README](../../../README.md)：确认已存在能力和公开承诺。
- [最近完整验证记录](../verification/2026-09-23-sql-formatter-array-layout.md)：保留首轮失败及最终通过的区别。
- [DBeaver 编辑与保存](https://dbeaver.com/docs/dbeaver/Data-Viewing-and-Editing/)：M3 交互参考，不代表本项目行为已等同。
- [DataGrip 查询执行](https://www.jetbrains.com/help/datagrip/query-execution.html)：M4/M5 范围与结果保留参考。
- [DataGrip 搜索](https://www.jetbrains.com/help/datagrip/search-in-ide.html)：M6 入口组织参考。
- [PostgreSQL 统计视图](https://www.postgresql.org/docs/16/monitoring-stats.html)：`n_live_tup` 是估算值。
