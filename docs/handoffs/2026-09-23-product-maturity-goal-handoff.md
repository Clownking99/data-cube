# DataCube 新会话交接：产品成熟度与首个目标

**2026-10-09 当前任务：G9整表导出可靠性已完成本地集成与独立复验。** GPT-6.1-sol开发产品e7950123、证据b76b75c，root审核后main集成5b9dfeca；3565份开发代码/原件Git字节、844输入及183镜像文件独立核对，合并后3518开发原件再次一致。分支/main均新定向1181通过、clean全量4663通过/3 live跳过、强制buildSrc8、jpackageImage和镜像外置SQL/XLSX/旧XML文本保真探针通过，exe/cfg/modules哈希一致。三种整表导出保护原目标，pg_dump有总期限与物理结算，SQL/XLSX用绑定配置的专用连接和单cursor完整读取；严格结构导出的保守类型限制已在README声明。 首红/工具失败/跳过不抹除；真实DB/pg_dump、原生桌面/chooser/Excel、慢盘/文件系统竞争、安装签名与完整M8仍未验。见[协调账本C7](../superpowers/verification/2026-10-08-g9-table-export-coordination.md)。最终main推送和精确SHA Verify以[delivery-intent](../superpowers/verification/evidence/g9-main-20261009/delivery-intent.json)指向的实际回执为准；成功后按授权暂停datacube-g9，不启动下一目标。旧datacube继续PAUSED，v3.2.9不变；下方“最新”为历史记录。

**2026-10-08 XLSX 文本保真（最新）：** 修复 XLSX 控制字符丢失、回车改变、字面 `_xHHHH_` 被解码改变及部分字符形成损坏文件；合法 UTF16 保留，孤立代理项明确拒绝，查询结果原目标保护与安全重试通过。GPT-6.1-sol 开发、root 独立审核；源码 ce2a096a、证据 5f176b0b、main 集成 8fb039c。新增 142 项测试，分支/main 各新定向 286、全量 4303 通过/3 live 跳过、强制 buildSrc 8、jpackageImage 及新镜像 20 包/40 单元格独立回读、6 项无效文本拒绝通过，三产物 SHA 一致；518 份开发原件 Git 字节复核。详见[独立审核](../superpowers/verification/2026-10-08-xlsx-text-fidelity-review.md)。最终 main 推送及精确 SHA CI 以本轮 delivery-intent 所指回执为准；前轮 Windows 助手超时及单次重跑原件已归档，原因未知。原生 Excel/桌面、真库、慢/网络磁盘、整表原目标失败保护、安装升级签名及完整 M8 仍待验。v3.2.9 和 PAUSED 跟进不变；下方各“最新”为历史轮次。

**2026-10-08 XML 导出忠实性（最新）：** 修复控制字符静默删除、正文/列名空白回读改变及部分 Unicode 列名无法解析；不可表示内容明确失败并保留原目标，保留原可解析标签映射和重试能力。GPT-6.1-sol 开发、root 独立审核补验；源码 c553e4c5、证据 eda89701、main 集成 33e167b7。新增 86 项回归，分支/main 各新定向 144、全量 4161 通过/3 live 跳过、强制 buildSrc 8、jpackageImage 和镜像 7 组 XML 回读/隔离审计通过，三产物 SHA 一致，525 份开发原件 Git 字节复核。详见[独立审核](../superpowers/verification/2026-10-08-xml-export-fidelity-review.md)。原生桌面、真库、慢/网络磁盘、安装升级/签名和完整 M8 仍待验。最终 main 推送及精确 SHA CI 以本轮 delivery-intent 所指回执为准；上一轮 2b2e004b 的四项 Verify 已成功，其 15 份原件已保存。v3.2.9 不变，跟进仍 PAUSED；下方各“最新”为历史轮次。

**2026-10-08 SQL取消执行身份修复（最新）：** 修复旧异步取消误伤后继SQL，以及旧物理cancel晚异常关闭后继连接的竞态；取消发起时绑定执行，保留原物理取消资源所有权，并补全未启动任务拒绝的句柄结算。GPT-6.1-sol开发、root独立审核修正；源f656be08、分支证据dcdfa288、main集成fd5159ce。新增15项行为/服务回归，分支/main各新定向357、全量4075通过/3明确live跳过、强制buildSrc8、jpackageImage及183文件镜像审计通过，三产物SHA一致，566份worker原件Git字节复核。首红、过期结构断言失败及拒绝遗漏补验全部保留。详见[独立审查记录](../superpowers/verification/2026-10-08-sql-cancel-identity-review.md)。本轮为mock/合成FX证据；真驱动取消/事务、剩余原生/OS缩放、终态恢复、无Gate启动、安装升级签名及完整M8仍待验。旧eaa Verify无记录原因未知，本次最终main推送/精确SHA CI以delivery-intent指向的新回执为准，不预报通过；v3.2.9和PAUSED跟进不动。下方各“最新”为历史轮次。

**2026-10-07 SQL批量错误询问取消修复（最新）：** 修复取消已发生后旧“执行遇错”仍显示并卡住执行队列的问题。每次执行独立询问门禁，先禁止旧选择，再在实际取消finally后释放；保留继续/全部继续/取消/X、拒绝关闭和旧回调隔离。GPT-6.1-sol开发、root独立审核及接管修正；源7030423f、分支证据a5be5b20、main集成c5c25706。新增19个合成UI及6个helper例，分支/main各新定向249、全量4060通过/3明确live跳过、buildSrc8、jpackageImage/183文件零连接镜像审计通过，三产物SHA一致，459份worker原件Git字节复核。产品首红、夹具失败和shell统计调用诊断均保留。详见[独立审查记录](../superpowers/verification/2026-10-07-sql-script-dialog-review.md)。queued脏tab文件确认前的modal顺序与慢取消调度注入均明确限定，不称原生验收。真驱动、原生/OS缩放、终态恢复、无Gate启动、安装升级签名和完整M8仍待验；最终main推送/精确SHA CI以delivery-intent指向的实际回执为准，v3.2.9与PAUSED跟进不动。下方各轮均按历史解读。

**2026-10-07 显式事务整窗退出缺陷修复（最新）：** 修复COMMIT/ROLLBACK/SET_MODE失败在退出时被吞掉并继续回滚、释放和隐藏窗口的问题。AppShell在异步workspace freeze前捕获所有SQL标签的未呈现事务，pane等真实queue idle后保留FAILED_PARTIAL可见保护；已显示旧错误、退出取消后恢复和非选中tab均有真实链路回归。源提交c71dd241、分支证据11f70af5、main集成c1711dca；新22例，分支/main各新定向207、全量4035通过/3明确live跳过、buildSrc8、jpackageImage/183文件零连接镜像审计通过，三产物SHA一致。首红、剩余竞态红灯和夹具同步失败原件均保留。详见[独立审查记录](../superpowers/verification/2026-10-07-sql-transaction-shutdown-review.md)。本轮仅mock/合成；真实驱动事务、剩余原生/OS缩放、终态进程内恢复、无Gate启动、安装升级签名与完整M8仍待验。最终main推送和精确SHA CI以实际交付回执为准，v3.2.9与PAUSED跟进不动。下方各轮为历史，不把旧“最新”视为当前结论。

**2026-10-07 SQL在途整窗退出集成验收（最新）：** 新增PG/Oracle各两例真实AppShell→SQL编辑器→生产runner→mock JDBC→原mandatory guard→整窗handler回归；跨默认5秒仍等待，物理结束后回滚/关闭、队列封闭、迟到回调抑制均通过。未发现生产缺陷，产品源码未改。测试5b3d7172、证据8acdb188、main集成cf8a4390；分支/main各新定向184、全量4013通过/3明确live跳过、buildSrc8、jpackageImage/183文件零连接镜像审计通过，三产物SHA一致。378份worker raw/Git及含报告的原件清单均复核，夹具失败和报告换行归档诊断保留。详见[独立审查记录](../superpowers/verification/2026-10-07-sql-inflight-shutdown-review.md)。非可取消COMMIT在途、真驱动取消/事务、原生/OS缩放、终态恢复、无Gate启动、安装升级签名及完整M8仍待验；最终main推送和精确SHA CI以交付回执核对，v3.2.9与PAUSED跟进不动。下方“最新”均按历史轮次解读。

**2026-10-07 退出等待可见反馈（最新产品）：** 关闭进入在途后即时显示等待与对话框指引；取消/可恢复异常清除、成功关闭清除、partial互斥切换保护说明，保留事务/资源/关闭时限。GPT-6.1-sol开发、root独立审核；源码6ef4b3e、证据b6cd398d、main集成f94c102。分支/main各新定向40、全量4009通过/3明确live跳过、buildSrc8、jpackageImage及镜像零连接审计通过，三产物SHA一致；366份worker原件Git字节复核。详见[独立审查记录](../superpowers/verification/2026-10-07-shutdown-pending-feedback-review.md)。首红与调度诊断保留。仅工程与合成FX交付；原生输入/OS缩放、完整在途/终态恢复、无Gate启动、安装升级签名及完整M8仍待验。最终main推送与精确SHA CI以交付回执核对；v3.2.9不动，datacube保持PAUSED。下方各“最新”按历史轮次解读。

**2026-10-07 退出部分失败可见反馈（最新产品）：** FAILED_PARTIAL后现在在禁用工作区之外持续显示原因与核对/手动结束指引，保持终态隔离、不重试事务、不自动强杀/重启。GPT-6.1-sol开发、root独立审核；源码33e7773、证据210574a、main集成84dd0c0。分支/main各新定向32、全量4005通过/3明确live跳过、buildSrc8、jpackageImage和镜像零连接审计通过；三产物SHA一致，375份worker原件Git字节复核。详见[独立审查记录](../superpowers/verification/2026-10-07-shutdown-failure-feedback-review.md)。首红/夹具及归档诊断保留。仅工程与合成FX交付；终态进程内恢复、原生输入/系统缩放、无Gate启动、安装升级/签名及完整M8仍待验。最终main推送和精确SHA CI以交付回执核对；v3.2.9不动，datacube不恢复。下方各“最新”按历史轮次解读。

2026-10-07 CI 跟进：收藏小窗口最终证据 bdf274c 已推送；Verify 37593098366 首次 Windows 旧概览排序测试发生 5 秒 FX 等待超时，Windows linked image 跳过，其他三任务成功。独立审查无确定根因；保留失败原件，同 SHA 失败任务只重跑一次，不改 timeout/断言。同 SHA 唯一一次重跑 attempt 2 四任务成功，Windows 单元测试和 linked image 实际通过；首次超时未复现但根因未知，仍为待诊断项。源码/测试/timeout未改。本次证据提交合并后的最终 SHA 另以新 Verify 回执核对，不预报结果。

**2026-10-07 SQL收藏小窗口修复（最新产品）：** 真实480×480/640×480双主题窗口已复现并修复取消按钮/说明裁切；新增垂直滚动与焦点显露，同焦点Ctrl+F、长SQL内部滚动和选区保持，保存/删除/恢复/重读及旧写完成状态不变。GPT-6.1-sol开发、root独立审查；源码125f954、证据06fbab9、main集成f5bc602。分支/main各新定向84、全量3997通过/3明确live跳过、buildSrc8、jpackageImage及镜像隔离/零连接检查通过，三产物SHA一致、370份worker原件Git字节复核。详见[独立审查与main复验](../superpowers/verification/2026-10-07-favorites-compact-review.md)。编译/夹具诊断失败原件保留；这是工程与合成FX交付，原生输入、OS缩放/多屏、安装升级和生产签名及完整M8仍待验。最终main按已有授权推送，精确SHA CI由最终回执核对；v3.2.9不移动、无新tag，datacube保持PAUSED。下方“最新”为历史轮次。

**2026-10-07 SQL收藏写后刷新修复（最新产品）：** 已用真实专用临时收藏库复现“保存落盘后读取失败，再保存重复UUID”，现将写完成结果与刷新失败分离，冻结重复写/旧快照动作并要求显式重读；真正写失败和关闭/取消/迟到回调行为保留。GPT-6.1-sol开发、root独立审核修正并接管中断后的工程验证。源码3b34183、证据9fca371、本地main集成00527dd；分支/main各新定向61、全量3974通过/3明确live跳过、buildSrc8、jpackageImage及镜像隔离/零连接审计通过，三产物SHA一致、397份worker原件Git字节复核。见[独立审查与main复验](../superpowers/verification/2026-10-07-favorites-refresh-outcome-review.md)。中断full-final未算通过，失败/夹具诊断均保留。本次工程/合成FX交付不替代原生/OS缩放/安装升级/生产签名，完整M8仍待验。最终main按既有授权推送，精确SHA CI以最终交付回执为准；v3.2.9保持原发布内容，无新tag，datacube保持PAUSED。下方“最新”为各历史轮次。

**2026-10-07 表/视图查找小窗口修复（最新产品）：** 真实480×480窗口已复现并修复候选行裁切，新增滚动与动态焦点可见性，保留快照/本地筛选/明确Enter及取消关闭契约。GPT-6.1-sol实施、root独立审核；产品74517f6、本地main集成53d2dc6。分支/main各新定向160、全量3954通过/3明确live跳过、强制buildSrc8、jpackageImage及镜像隔离/零连接审计通过，三产物SHA一致，719份worker原件Git字节复验。首full四旧尺寸夹具失败及诊断保留，未充当通过。详见[本轮独立账本](../superpowers/verification/2026-10-07-schema-object-compact-review.md)。本次为工程与合成FX交付，原生名称/字段键盘、OS缩放、多屏、安装升级/生产签名和完整M8仍待验；main按既有授权推送，实际CI以最终交付回执为准。v3.2.9保持原发布内容，无新tag，datacube保持PAUSED。

编写日期：2026-09-23。此文档旨在让新会话不依赖旧聊天全文即可接手。

**2026-10-06 字段查找小窗口修复（上一轮产品）：** 真实640×480 JavaFX窗口复现按钮裁切及同焦点Ctrl+F无法回到查询；GPT-6.1-sol实现、root独立审核返工，新增滚动与焦点可见性，保留目标绑定/明确动作/取消关闭。产品58278f7、main集成194371a；分支/main各新定向103、全量3944 passed/3 live skipped、强制buildSrc8、jpackageImage/镜像零连接审计通过，三产物SHA一致，1040份worker原件字节复核。见[本轮独立账本](../superpowers/verification/2026-10-06-metadata-search-compact-review.md)。这是工程与合成FX交付，原生字段键盘/OS缩放、完整在途恢复、安装升级与签名仍待验；完整M8不称完成。v3.2.9保持原发布内容，本轮仅更新main，datacube保持PAUSED。

**2026-10-06 正式发布读回：** 维护者已明确授权推送、删除旧验收 tag 并以正式版本 tag 触发自动打包；v3.2.9 指向 main 0f6ba02656fcf3752b180514c72e79151a3320df，GitHub [Build and Release 37448530784](https://github.com/Clownking99/data-cube/actions/runs/37448530784) 成功，[Release](https://github.com/Clownking99/data-cube/releases/tag/v3.2.9) 已发布 ZIP 和 EXE。本轮只读再次核实两资产 uploaded、远端 main/tag 指向一致。下方“不推送/不打 tag/未发布”是各轮历史边界，已被维护者后续明确授权覆盖；自动打包发布成功不代替安装升级、生产签名或完整 M8 验收。

**2026-10-05 Windows CI 修复（最新）：** 维护者提供的 3938 tests / 28 failed / 3 skipped 已用真实 Windows 8.3 临时路径复现并修复。更新入口按逐级 NOFOLLOW 文件属性识别链接/重解析点，交接与启动回执使用先验证后规范化的目标；SQL 关闭/工作区夹具规范化自建根目录，生产 SQL registry 和更新脚本安全检查保持原契约。实现 d5dd127、main 集成 14875d749ec07ce54bbbeb7b490674ab4784d72f；分支/main 各新定向180、全量3938 passed / 3 live skipped、强制buildSrc8、jpackageImage及镜像隔离/驱动发现通过，738份分支原件独立审核。新modules SHA0860780E…与分支一致，旧原生证据仍只绑定旧镜像。托管Verify 37319792169的Windows单元测试/jlink、Linux、Redis、wrapper全部通过，完整CI与提交收尾见[独立审核账本](../superpowers/verification/2026-10-05-windows-ci-paths-review.md)。main已按现有授权使用命令级7897代理推送，既有acceptance tag不移动；无真库、真实升级/安装、原生桌面补验或发布，M8完整发布仍待验，datacube保持PAUSED。

**2026-10-05 正式启动器续验：** 未改DataCube.exe/cfg/modules，在外置启动前home断言与回环拒绝更新代理下，原生完成两页中文离线SQL编辑、正常关闭、同profile显式恢复及再关闭，限定6/6通过。实际draft UUID/字节不变，workspace A/B顺序、首项和55编辑位置吻合；父launcher两次exit0，JVM shutdown且自有进程消失。根91原件/15状态/18截图、worker26原件、旧1087原件核对，独立复核与实际镜像3SHA通过；产品未改，无新Gradle/buildSrc/image声明。详见[启动器账本](../superpowers/verification/2026-10-05-formal-launcher-acceptance.md)。闪屏视觉、无Gate默认运行、安装升级/签名/CI结果/其他完整桌面与发布仍待验，M8不称完成。本轮fa0754b已合并main9a51384，main旧1087/worker26/根91原件、三实际镜像SHA和两轮草稿独立解码复验通过。既有datacube保持PAUSED；下方正式launcher未验描述属于历史，不覆盖本轮限定证据。
**2026-10-05 最终限定原生验收（8/8完成）：** 维护者Esc已由真实ESCAPE→CANCELLED核实，取消后72字节workspace与前快照完全一致；根随后原生Retry保留相同冻结布局，COMPLETED资源关闭1/锁重开/provider0，无fallback，自有PID5808自然退出。旧1038原件、新49冻结原件/4状态/5截图独立审计通过，源与main镜像3SHA绑定；只证据/文档，无新产品改动或工程测试声明。详见[完成账本](../superpowers/verification/2026-10-05-workspace-native-escape-completion.md)。本限定矩阵和main新profileRetry均已完成，不再等待按键；完整M8/正式launcher/发布仍待验。datacube保持PAUSED，停止本轮、不自动启动其他范围。本轮分支41ea991已合并main641f046，main旧1038/新49原件、实际存储/事件/资源和三镜像SHA复验通过。下方7/8及等待Esc属于历史快照。

**2026-10-05 最新原生续验（7/8）：** 人工Enter真实取消已核实，后续故障释放后的正常退出不算Esc；main6cf6788新profile原生Retry已通过，冻结字节/真实draft布局/资源关闭1/锁重开/provider0核实。历史951原件、新9状态/11截图/87冻结原件审计，产品未改，无新Gradle声明。只剩人工Esc：新专用窗口尾5ac9286bb578449abaaa10585abacbcc/PID5808已备真实Alert，根暂停输入；小弹窗标题栏聚焦后只按Esc，必须核验实际事件/旧字节。详见[本轮账本](../superpowers/verification/2026-10-05-workspace-native-key-acceptance.md)。main新profileRetry不再待验，M8发布仍未完成；原边界沿用，datacube不恢复。本轮分支0d70d3a已合并main4636441，main复验旧951/新87原件、实际事件/存储/资源和三镜像SHA通过。下方6/8、旧窗口与旧待验项属于历史快照。

**2026-10-04 工作区退出原生续验（当前6/8，目标未完成）：** 桌面工具本轮读取/输入恢复，新javac及当前main镜像3SHA绑定。实际Cancel/Dialog标题栏取消、取消后真实文件编辑保存、Retry冻结字节、Ignore旧布局和成功资源关闭/锁释放已验；两份真实COMPLETED、provider0、无fallback。独立解码workspace/真实draft/保存select 404;字节、75份存档截图哈希及旧772原件未变，产品/测试/构建无改动；本轮没有新Gradle工程通过声明，历史仍历史。Enter/Escape工具请求没有DIALOG_KEY，仍UNVERIFIED；已准备唯一keyboard窗口末尾d11c0aa9502a4c1dac99fa8524fe77d2，请人工Enter、返回Alt+F4、新弹窗Esc后核对真实事件，根暂停该窗口输入，既有授权无须重复。详见[本轮计划](../superpowers/plans/2026-10-04-workspace-native-acceptance.md)、[实际账本](../superpowers/verification/2026-10-04-workspace-native-acceptance.md)、[矩阵](../superpowers/verification/evidence/workspace-native-20261004/native-matrix.json)。已本地提交61366a2/main合并6a3b154，main原件/真实存储/事件资源/产品树/镜像审计通过；185暂存文件字节、179新冻结原件核验，75份存档图像含动作前重复状态，不称75次独立验收。最新keyboard日志0真实按键/0cancel/0failure，自有PID2916保留等待；待两项按键及main新profile Retry，不扩大范围/M8发布口径，datacube不恢复、真库与原业务内容不访问。下方0原生或工具授权/Windows错误文字均为旧检查点。
**2026-10-02 授权后的原生续验（当前技术依赖）：** 维护者“已允许啊”已明确确认，不再待人类授权。基线main c4807bc，新codex/workspace-native-completion和独占profile；复制外置夹具原字节，本轮javac成功/当前main镜像身份和真实初始化通过。sky枚举新自有Stage后两次访问均返回 **GetCursorPos failed: 拒绝访问。 (0x80070005)**；不是应用授权超时或自动审批拒绝。按技能刷新一次后停止，0截图/0输入/0原生行为通过，不能断言锁屏，已询问桌面是否解锁且可交互。精确核实并停止自有PID，profile/原始错误/事件/workspace字节保留，安全停止不算COMPLETED。产品/测试/构建未改，本轮没有新Gradle证据；上一轮工程结果及746原件不改写。见[续验计划](../superpowers/plans/2026-10-02-workspace-native-completion.md)、[实际账本](../superpowers/verification/2026-10-02-workspace-native-completion-coordination.md)、[新待验矩阵](../superpowers/verification/evidence/workspace-native-completion-coordination/native-acceptance-matrix.json)。原生目标/M8仍未完成；仅等待技术会话恢复后继续原矩阵，datacube不恢复，不扩大范围。下方权限待答文字为已发生的旧检查点。 本轮记录提交e8d854a，main集成79ad40e；合并后实际核验旧746/新26原件、产品树、三镜像SHA与初始workspace字节通过，八项原生仍未验；后继文档记录不改变受验产品源码。
**2026-10-02 工作区退出原生验收准备（最新工程交付，原生目标未完成）：** 继续推进后复用现有GPT-6.1-sol编写docs外置AppShell/真实guard/store/production Alert夹具，根独立审查返工：自有workspace.bin失败、冻结布局/重试字节/Ignore旧字节与故障/原草稿ID/checkpoint/真实quarantine与资源锁断言，不自动fire决策，不进入正式镜像。程序化twofile/READY和实际草稿写入已观察，可见自有Stage被sky枚举；访问窗口却返回 **Computer Use app approval timed out**，本轮没有任何已验证原生动作或截图，原生生产按钮/默认Enter/Esc/标题栏dismiss/取消后编辑保存/成功关闭资源仍未验，不能称本轮原生目标或M8已完成。工具实际应用权限问题已异步询问维护者，等待答复；安全停止只限本轮核实PID/profile/launcher，不冒充COMPLETED。

分支1dd1c0a、main工程集成0a7ec1197f78916b3cb16221a38013efed4b0795；仅docs外置夹具/证据、产品未改。分支/main各新定向235/235、全量3935 passed/3 live skipped、buildSrc8/8、jpackageImage/183文件镜像隔离/零连接发现通过，778源稳定、217 Java差异仅换行、三项SHA一致。首外置编译包内访问失败、隐藏启动、根退出码误报、应用权限超时、暂存文档换行和raw空白元数据问题均保留首原件、独立修正；不是产品红或自动权限审核拒绝。[限定计划](../superpowers/plans/2026-10-02-workspace-native-exit.md)、[worker](../superpowers/verification/2026-10-02-workspace-native-exit-worker.md)、[独立审核/main复验](../superpowers/verification/2026-10-02-workspace-native-exit-coordination.md)、[工程结果](../superpowers/verification/evidence/workspace-native-exit-coordination/results.json)、[原生待验矩阵](../superpowers/verification/evidence/workspace-native-exit-coordination/native-acceptance-matrix.json)。最终文档/原件集成保持受验代码不变，已有原生/Oracle证据仅作历史，不充当本轮通过。

本轮未访问真库或旧Oracle专用表，原有profile/凭据/连接/SQL历史/业务文件与.testagent禁读改，不推送/fetch/tag/PR/发布/更新/外部联系；datacube实查PAUSED并保持。待工具访问许可后仅继续本限定原生矩阵，正式launcher/完整在途退出与FAILED_PARTIAL产品恢复、字段输入键盘多结果失效、小窗/OS多屏、其他真库、安装升级/签名/CI/用户任务/完整发布仍待验。

**2026-10-02 完整AppShell工作区退出决策（最新本地交付）：** 继续推进后复用现有GPT-6.1-sol，当前线程独立审核和具体返工。新增4项实际shell/原mandatory guard/真实workspace存储与production Alert合成FX用例：取消/重复取消保留旧工作区字节并继续真实文件准入、脏文本去重/save，明确新活动后发布新layout；重试相同冻结布局，忽略故障中的本次更新而保留旧字节，合成Dialog关闭按默认cancel。实际5条Alert，原标签在决策前已守卫结算/移除，不能称原标签全部保留；每例真实草稿成功原子写入，成功资源释放一次/存储锁释放，provider请求0，未用fixture失败fallback充当成功。

产品源码未改，实现25b770a，独立证据e94f77a，本地main合并代码b57c6acd8e811854e7ace0102a4d5b7f3996e784。分支/main各新定向235/235、全量3935 passed/3 live skipped、buildSrc8/8、jpackageImage/183文件镜像隔离/零连接发现通过，778源稳定、217 Java差异仅换行、三项SHA一致。首夹具后缀计数4/2失败、口头Alert6次误计纠正为5、暂存换行字节审计拒绝与全部原件保留；不是产品红或自动权限拒绝。详见[限定计划](../superpowers/plans/2026-10-02-app-shell-workspace-exit.md)、[worker账本](../superpowers/verification/2026-10-02-app-shell-workspace-exit-worker.md)、[独立审查/main复验](../superpowers/verification/2026-10-02-app-shell-workspace-exit-coordination.md)、[实际结果](../superpowers/verification/evidence/app-shell-workspace-exit-coordination/results.json)。最终文档原件集成保持受验源码不变。

真实workspace CANCEL已补合成FX证据，本轮无新原生/正式launcher/真库证据；原生workspace及完整shell退出、FAILED_PARTIAL后产品恢复、字段输入/全键盘/多结果/失效/小窗、OS多屏、其他真库、安装升级/签名/CI/用户任务/发布仍待验，M8不称完成。既有Oracle表未访问/清理，.testagent/原有凭据配置历史业务内容禁读改，无推送/外部联系等操作；datacube实查PAUSED并保持，本轮交付不自动下一轮。下方“当前/最新”按历史轮次解读，旧workspace合成CANCEL未验已由本轮限定证据补齐，不提升为原生或发布验收。

**2026-10-02 完整AppShell DataGrid在途退出（最新本地交付）：** 复用既有GPT-6.1-sol实施、当前线程独立审查返工。新增PG/Oracle合成类型共6项实际treeActions.openDataGrid→DataGridPane/DataEditService/JdbcDataEditor关闭用例，未替换mandatory guard或15秒常数。首行回滚、第二行在途保留第一行提交/回滚第二行/不执行第三行、默认5秒提示仍pending、真实15秒FAILED_PARTIAL不提前teardown或释放仍在使用的资源、迟到UI不报成功均有新行为证据；失败后夹具清理不算产品恢复。

实现e7f55b7；首次全量2项旧Metadata夹具关闭失败和333份原件保留，受控PG初始化红绿后仅修夹具等待真实draft初始化的前置条件d67bb37，产品未改，历史现场mode仍未知。独立证据911e5c5，本地main合并代码ecbc421a899a17ef04eac02582402f7473591d20；分支/main各新定向184/184、全量3931 passed/3 live skipped、buildSrc8/8、jpackageImage/183文件镜像隔离/零连接发现通过，777源文件稳定、216 Java差异仅换行、三项SHA一致。完整原始失败/诊断/参数/XML/字节审查及实际待验见[限定计划](../superpowers/plans/2026-10-02-app-shell-grid-exit.md)、[worker](../superpowers/verification/2026-10-02-app-shell-grid-exit-worker.md)、[独立审查及main复验](../superpowers/verification/2026-10-02-app-shell-grid-exit-coordination.md)、[实际结果](../superpowers/verification/evidence/app-shell-grid-exit-coordination/results.json)。最终文档/证据集成保持受验源码不变。

本轮只有程序化合成FX/mock JDBC，无新原生、真库或正式launcher证据；真实工作区CANCEL、原生完整shell退出、FAILED_PARTIAL后产品恢复、字段原生输入/全键盘/多结果/失效/小窗、OS多屏、安装升级/签名/CI/用户任务/发布仍待验，M8不称完成。既有Oracle表不访问/清理，.testagent/原有业务内容禁读禁改，无推送或其他外部动作；datacube实查PAUSED并保持，本轮结束，不自动启动下一轮。下方“当前/最新”按各历史检查点解读，先前未取得的DataGrid合成事务/物理15秒已由本轮限定证据补齐，不升级为原生或发布验收。

**2026-10-02 AppShell 关闭恢复（最新本地修复）：** 继续推进时，现有 GPT-6.1-sol 代理在真实 AppShell 合成 FX 复现 CANCELLED 后 SQL 文件注册表失效；修复只涉及 AppShell：关闭等待暂停入口、旧代回调隔离、取消/提交前异常恢复且保留原身份，私有结算防公开 future cancel 越界，标签已提交后的启动失败/清理失败保持 FAILED_PARTIAL 隔离。协调独立审查返工避免 recent 磁盘锁阻塞 FX，并以 best-effort 尝试各项释放。实际文件 dirty 文本/去重/Save/Save As、旧读/error/排队 recent、重复取消、5 秒默认 warning pending 与合成资源释放1/finalizer1、清理失败下缓存 Connection.close1均有新行为证据。实现404c3eb、证据3f1ddb5，本地 main 合并代码 `523d23456642f430b0528865055ca0ef867839a5`；分支/main各新定向107/107、全量3925 passed/3 live skipped、buildSrc8/8、jpackageImage/183文件镜像隔离/零连接驱动发现通过，775源文件稳定、214 Java差异仅换行、三项SHA一致。首失败、两次编译失败与陈旧XML排除、独立汇总/路径错误全部保留。详见[限定计划](../superpowers/plans/2026-10-02-app-shell-shutdown-recovery.md)、[worker](../superpowers/verification/2026-10-02-app-shell-shutdown-recovery-worker.md)、[独立审查与main复验](../superpowers/verification/2026-10-02-app-shell-shutdown-recovery-coordination.md)、[实际结果](../superpowers/verification/evidence/app-shell-shutdown-recovery-coordination/results.json)。本轮没有原生或真库运行；完整AppShell证据只限合成受管资源，不能称DataGrid事务/物理15秒或正式启动器通过；真实工作区CANCEL、原生退出、字段输入/全键盘/多结果/失效/小窗、OS多屏/正式安装升级/签名/CI/用户任务/发布仍待验，M8未完成。既有Oracle表不访问/清理，.testagent/原有业务内容禁读禁改，无推送/外部动作；datacube实查PAUSED并保持，下轮不自动扩展。下方“当前/最新”均按历史检查点解读。

**2026-10-02 字段原生键盘有限尝试（最新交付）：** 继续推进时，GPT-6.1-sol代理使用真实AppShell、独占合成profile、外置三结果mock夹具验证空框输入/键盘/模式/实际缩小；协调独立审查并下发有界Tab诊断和账本基线澄清。仅取得菜单/空框/坐标取消/正常空闲shell退出，工具只提供owner，type_text两次空框、Tab/Esc无按键日志、mode未变化、drag前后663×639且零尺寸事件；21真实状态/39原JPEG、mock打开关闭1/1、检索/写/执行0均保留。本轮原生目标没有完成，不是产品修复或发布验收；不能从accessibility焦点/API无异常或无效果拖动推断通过。没有产品/src/resources/test/build改动；外置证据01c9cb0本地合并main 03a552b70be908bfa3281144c67a963bd2137aea。分支/main各新定向83/83、全量3917 passed /3 live skipped、buildSrc8/8、jpackageImage及镜像/零连接发现通过，三项SHA一致。首定向筛选漏suite、profile拒绝及workerPNG计数错误保留，纠正后实际重跑/重算；原生输入/全键盘/多结果/条件失效/小窗口待验不删除。详见[限定计划](../superpowers/plans/2026-10-02-metadata-native-keyboard.md)、[worker原始尝试](../superpowers/verification/2026-10-02-metadata-native-keyboard-worker.md)、[独立审查与main复验](../superpowers/verification/2026-10-02-metadata-native-keyboard-coordination.md)、[实际结果](../superpowers/verification/evidence/metadata-native-keyboard-coordination/results.json)。下次不能原样重复无效尝试并声称完成，需要可用owned-dialog输入通道或人工证据补齐。没有新真库操作，既有专用表不访问/清理，.testagent及原有业务内容禁读禁改；无推送/外部动作，datacube保持PAUSED。M8/发布仍未完成；下方所有“当前/最新”按其历史检查点解读。

**2026-10-02 SchemaDiff 夹具稳定性（最新交付）：** 维护者在前轮P3交付后要求继续推进。GPT-6.1-sol开发代理实际复现mock并发记录8192次仅保留6177（物理关闭8192）、schema快照双引用两处夹具缺陷；另一个有界双线程诊断捕获ArrayList.add越界异常，同步列表同参数400000条完整/0异常。仅修SchemaDiffServiceTest的同步记录和catalog构造，并增强双侧快照/配置、唯一记录和每连接关闭断言，产品源码未改。实现75ed0c6、审核证据7e12468，main本地验收合并a35311769e80219341baa5eca86e9cec51d1a19f；分支和main各新定向9/9、全量3917 passed /3 live skipped、buildSrc8/8、jpackageImage/镜像与零连接驱动发现通过，三项产物SHA一致。完整原始红绿/异常、首次归档及审计误报、参数/XML/字节审计和精确待验见 [开发账本](../superpowers/verification/2026-10-02-schema-diff-stability.md)、[独立审查及main复验](../superpowers/verification/2026-10-02-schema-diff-stability-coordination.md)、[实际结果](../superpowers/verification/evidence/schema-diff-stability-coordination/results.json)。已证明并修复该夹具竞态及同类异常机制，但旧历史那一次缺底层cause，精确归因依然不能确认；不把后来的通过称为已确定历史根因。本轮无新原生或真库，完整M8/发布待验范围保留；既有Oracle专用表未访问/清理，安全边界不变。既有datacube跟进继续PAUSED，本轮不自动启动更多功能；下方P3等记录及其中“当前”称谓按历史检查点解释，不能充当本轮新证据。

**2026-10-02 GPT-6.1-sol 协作验收闭环（当前）：** 维护者授权由协调线程制定计划、下发独立开发线程任务并审查集成。开发提交 a69f7db（仅外置 mock 夹具/原始证据，产品未改），经独立源码/图像/全部 XML/原始与 Git 字节审查，本地 main 合并 669ee536e8d5babba145f1a881d9b6d1a7c0ed99。main 新独占 profile 定向 75/75、全量 3916 passed /3 live skipped、强制 buildSrc 8/8、新 jpackageImage /镜像和零连接发现通过，三项产物 SHA 与开发镜像一致。新原生证据限定 PG mock 表的字段命中转只读数据/DDL、生产取消/批准、在途交互关闭拒绝、取消剩余/仅剩余行重试、夹具入口调用实际强制关闭 guard 以及 DDL 局部键盘；查询词/两行草稿程序化预置不算原生输入/编辑。旧截图引用错误、FAILED_PARTIAL 后旧夹具自行收尾、首次镜像误报和协调换行误报均保留且不计通过。详见 [开发账本](../superpowers/verification/2026-10-02-sol-p0-p2-acceptance.md)、[独立审查](../superpowers/verification/2026-10-02-sol-coordination-review.md)、[main P3 账本](../superpowers/verification/2026-10-02-sol-p3-main-acceptance.md) 及 [实际结果](../superpowers/verification/evidence/sol-p3-main/results.json)。本轮没有真库或 main 新原生运行；完整字段输入/键盘/结果导航、原生 Oracle/view/SELECT/配置失效、完整 AppShell 在途退出/超时恢复、窗口缩小/OS 多屏、正式启动器/安装升级/签名/CI/用户任务/发布仍待验，SchemaDiff 首次间歇失败根因未定，M8 不称完成。线程 API 未提供正式 id，按精确提交/worktree 审查，无剩余返工，不伪报修正已下发；证据提交集成后按授权暂停跟进，不自动启动下轮。既有 Oracle 专用表未访问/清理，安全边界全部保留；下方各日期记录为历史，不重复实施或把不同证据等级混合。

**2026-09-30 授权 Oracle 真库验收（当前）：** 维护者明确授权指定 Oracle scmtest 目标，随后允许创建唯一前缀专用表并仅新增/修改合成数据，禁止删除。首轮实际 16/17，发现明确取消被 Oracle SQLTimeoutException 错标超时；最小修复 Oracle/PG/共享预编译分类优先级，PG 仅 mock，无真库访问。实现 101f90aaace4b117bad0bcdcb6b4f4d0ff82c643，main 合并代码 fbe6e30de20e7ae332e6cd8c6956de0b5e6cfcb2，新定向 114/114、全量 3916 passed /3 live skipped、强制 buildSrc 8/8、jpackageImage/零连接审计通过，三项镜像 SHA 与分支一致。修复分支与 main 新镜像真库均 17/17、各 33/33 连接，0 删除语句；三张 SCMTEST.DCA_D161141EA4BB43_T、DCA_D161141EA4BB44_T、DCA_D161141EA4BB45_T 各保留 3 行（ID 1、2、12），不要清理。原生取得 Oracle 只读数据/禁用写按钮、Schema 字段查找真实命中 → 生成 SELECT 未执行 → 显式执行 3 行、表菜单真实 DDL；正常关闭 3/3 连接、0 写。字段命中转数据/DDL、原生写确认/完整键盘/在途关闭受工具限制或未验；取消实测约 3–9 秒，不承诺立即响应。首次真库/mock 红灯、原生与归档失败保留，凭据未持久化；见 [授权验收账本](../superpowers/verification/2026-09-30-oracle-live-acceptance.md) 与 [实际结果](../superpowers/verification/2026-09-30-oracle-live-acceptance-results.json)。本轮仅授权单 Oracle 目标及专用表；PG/Redis、原 Schema Diff 的 DROP USER 清理、其他外部操作未获授权，M8 完整桌面/安装升级/签名/CI/用户任务/发布仍待验。下方各轮“无 Oracle/真库”按历史日期解读；不重复实施或自动扩展。

**2026-09-30 名称查找读取准入（最新）：** 维护者继续推进产品，本轮在实际 AppShell + mock JDBC 复现名称窗口关闭后重开名称/字段入口造成两份读取连接同时占用（PG/Oracle 四项，峰值 2）。修复后窗口及时关闭，但名称读取实际返回及 FX 关闭清理前仍保留占用，两个 Schema 检索入口禁用并显示“等待读取结束”；结束只恢复入口，需明确重试，峰值保持 1。窗口内重复读取和内嵌字段入口也拒绝在途重叠；排队/提交期间关闭、后台关闭、异常/拒绝与旧回调保护均有实际回归。实现 30556d03f5103483fec354b69a4c71bccf8d8fde，main 合并代码 50924650da51d0b17892bb89d23172180094a672；main 新定向 180/180、全量 3898 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage/镜像零连接审计通过，三项产物 SHA 与分支一致。22 份实际记录、109 份 raw 文件及红灯原测试/日志/XML见 [本轮账本](../superpowers/verification/2026-09-30-schema-object-admission.md) 和 [实际结果](../superpowers/verification/2026-09-30-schema-object-admission-results.json)。本轮没有新原生或真库证据：原生等待/重开、完整字段请求/结果动作、Oracle 桌面、OS 多屏/全键盘、正式启动器/安装升级/签名/CI/用户任务/发布仍待验，M8 不称完成。限定本地交付、不自动扩大范围；下方状态为各轮历史，不重复实施已有能力。

**2026-09-30 字段检索关闭后准入（最新）：** 维护者继续推进产品，本轮实际 mock JDBC 复现窗口关闭后重开造成两份读取连接同时占用（PG/Oracle × 读取/取消先结束，四项均峰值 2）。修复后，窗口及时关闭，但读取、取消及 FX 关闭清理全部结束前，两个 Schema 检索入口禁用并显示等待；结束只恢复入口，需明确重试，峰值保持 1。实现 219d3c2，main 合并代码 2f27e2ba1d73cd8c6a2a41b26040cf05fad2cd6e；main 新定向 96/96、全量 3888 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage/镜像零连接审计通过，三项产物 SHA 与分支一致。取消异常、拒绝、fatal Error、后台空闲关闭及既有身份/只读/路由回归通过；初次测试编译错误、实际红灯和忽略日志导致的暂存审计失败均保留。见 [本轮账本](../superpowers/verification/2026-09-30-metadata-search-disposal.md) 和 [实际结果](../superpowers/verification/2026-09-30-metadata-search-disposal-results.json)。本轮没有新原生或真库证据：原生等待/重开、完整请求/结果动作、Oracle 桌面、OS 多屏/全键盘、正式启动器/安装升级/签名/CI/用户任务/发布仍待验，M8 不称完成。本轮限定本地交付，不自动扩大范围；下方各轮状态为对应日期的历史。

**2026-09-30 Schema 直接字段入口（最新）：** 维护者继续推进产品，本轮在 Schema 菜单新增“按字段 / 注释查找…”，与名称查找内原入口共用现有检索和路由；只读连接也可检索，配置变化/改回、节点移除/换根或关闭使旧窗口失效并取消读取。实现 71430dc，main 合并代码 7d5b0434677a2f62ec449e1cea7719dbb517e8bf；main 新定向 84/84、全量 3876 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage 与镜像/零连接审计通过，分支/main 三项产物 SHA 一致。实际 AppShell 合成 FX 取得 PG 双入口、Oracle 直接入口的表/视图三动作、配置 ABA 和在途失效/物理释放证据；新原生取得 PG 菜单直接开单窗口、准确提示、取消和正常关闭（mock 开关 1/1、检索/写/执行 0）。模态索引不可用且聚焦后文字未进入，停止重复输入；完整原生字段请求/结果链、Oracle 桌面、真库、OS 多屏/全键盘、正式启动器/安装升级/签名/CI/用户任务/发布仍待验，M8 不称完成。见 [本轮账本](../superpowers/verification/2026-09-30-schema-metadata-entry.md) 与 [实际结果](../superpowers/verification/2026-09-30-schema-metadata-entry-results.json)。本轮已限定本地交付；旧“Oracle 直接路由待验”仅指此前历史，不能覆盖本轮合成证据，也不能将它升级成真库/桌面验收。

**2026-09-30 字段检索下游 FX 跟进（最新）：** 修复只读表页面被标成“视图”：AppShell 标题改为“数据（只读）”，强制只读提示改为“当前数据页为只读”。新增 8 项实际 schema 菜单/对象查找/字段 JDBC 搜索/结果动作/AppShell 标签/正常关闭的合成 FX 回归，覆盖表/视图 SELECT、只读数据及 DDL，取消和同 id 配置变化拒绝旧选择，所有 mock 开关平衡、写入/执行 0。实现 5083a14，main 合并 b4d5e2450dfafad6f637d0aeac8306bade6a095e；main 新定向 70/70、全量 3843 passed / 3 live skipped、强制 buildSrc 8/8、jpackageImage 与镜像/零连接审计通过。20 份实际记录与失败历史见 [本轮账本](../superpowers/verification/2026-09-30-metadata-shell-routing.md)。程序化 fire/反射注入是 FX 集成，未获得新原生证据；完整检索下游原生、Oracle 本轮直接路由、真库、OS 多屏/键盘、正式启动器/安装升级/签名/CI/用户任务/发布仍待验，M8 保持待外部验收。不重复实现已有功能，旧状态按日期与证据层级解读。

**2026-09-30 连接树修复与小窗口补证：** 继续推进产品时在实际 AppShell 复现连接树永远显示“加载中”：回调错误检查隐藏根的子列表。实现 7eec75c，main 合并 8b890175712835b5b8c2ea0a57f3b069a717873c，保留旧代/脱离拒绝并覆盖失败发布与明确重试；main 新定向 85/85、全量 3835 passed / 3 live skipped、强制 buildSrc 8/8、镜像/零连接审计均通过。另取得 150% 进程比例下 SQL 小窗口原生查找/Esc 后编辑/上下滚动/明暗/尺寸恢复，以及修复后连接/schema/表分组展开、对象查找、表节点生成未执行 SELECT/读取合成 DDL/正常关闭。嵌套模态工具只返回主窗口句柄，明确聚焦后的检索文字仍未落入框，已停止重复输入；字段结果 → SELECT/只读数据/DDL 完整原生链路仍待验，不以表节点入口代替。见 [本轮账本](../superpowers/verification/2026-09-30-shell-workflow-acceptance.md)。M8 仍待外部验收，真库、OS 多屏、正式启动器/安装升级/签名/CI/用户任务/发布未执行；下方旧待验状态按日期解读，不重复实现已有能力。

**2026-09-27 字段检索原生补证：** 本次“继续推进产品”沿用原有边界，优先补齐工具失败导致的桌面缺口。当前 Computer Use 正常，三个独立合成 profile 已取得字段名/字段注释输入、超时恢复、明确取消、双任务相反完成顺序、明确重试、结果预览、条件失效、Ctrl+F/Enter、明暗及正常/在途关闭证据。产品源码未改；基线 a4dc887，新全量 3832 passed / 3 live skipped、buildSrc 8/8、镜像/零连接审计通过。main 验收合并 79923f0 已完成定向 33/33、全量、buildSrc 和镜像复验；精确范围见 [原生补证账本](../superpowers/verification/2026-09-27-discovery-native-acceptance.md)。后续优先验完整 AppShell 检索下游动作与 SQL 极限小窗口原生滚动/键盘；OS 多屏、真库、签名/安装升级/CI/真实用户任务仍待验，M8 不称完成。下方工具失败是历史，不再据此判定当前工具不可用。

**2026-09-26 检索取消状态跟进：** 维护者要求继续推进产品，本轮修复字段/注释检索取消、超时或条件变化后已结束却仍显示等待的问题，继续保留双任务结束前的准入关闭与旧结果丢弃。详见 [取消恢复账本](../superpowers/verification/2026-09-26-metadata-search-cancellation.md)。本地交付完成：实现 7856da2，main 产品代码 65ed18b；新 profile 全量 3832 passed / 3 live skipped，强制 buildSrc 8/8、镜像/零连接审计通过；原生激活/取屏再次失败已停止，真库与外部发布验收仍待验。

**2026-09-25 小窗口跟进：维护者要求继续推进产品。** 从 G8 已记录的 SQL 页底部裁切切入，增加按需滚动，保留文件、草稿、结果与键盘语义；新证据及首次全量失败经过见 [小窗口账本](../superpowers/verification/2026-09-25-sql-small-window.md)。本地交付完成：实现 3440a50，main 产品合并 584699c；main 新 profile 全量 3822 passed / 3 live skipped，强制 buildSrc 8/8、镜像/零连接审计通过。原生工具激活/取屏连续失败后停止，本轮未取得原生滚动/键盘截图，不用旧 G8 证据替代。

**2026-09-25 G8 更新：维护者已明确授权本地桌面验收。** 已取得合成迁移确认/取消/在途关闭、结果切换保留、表格显式保存/只读、收藏离线打开和镜像内空白 AppShell 的原生证据，并修复窄窗分页按钮省略与暗色收藏/检索空提示。本地交付已完成：实现 b1135d9，main 产品代码 4885c40、证据集成 e65edb3；main 全量 3815 passed / 3 live skipped、buildSrc 8/8、镜像及零连接探针通过。最新状态以 [G8 账本](../superpowers/verification/2026-09-25-datacube-g8-local-acceptance.md) 和 [实际结果](../superpowers/verification/2026-09-25-datacube-g8-results.json) 为准。字段检索原生输入工具失败、OS 缩放/多屏、正式启动器/真库/签名/安装升级/CI/真实用户任务仍待验；总体 M8 不称完成。下方“G8 须授权/未启动”是 G7 交付时的历史状态，不再用于重复提问。

**G7 历史交付：G7/M7 已本地工程完成。** 迁移只读预检查、明确确认、整表事务、逐表状态/安全重试/脱敏报告与有限文件对账已提交并合并 main。主体 cc726a2、驱动修复 8b85bd6、载荷修复 ab6b61c，最终 main 代码 552b709；新 profile 全量 3809 passed、3 live skipped，强制 buildSrc 8/8、jpackageImage 和零连接镜像驱动发现通过。首次镜像驱动失败、大文本载荷回归失败及修复经过见 [G7 账本](../superpowers/verification/2026-09-25-datacube-g7-migration-evidence.md) 和 [实际结果](../superpowers/verification/2026-09-25-datacube-g7-results.json)。原生桌面本轮未取得可定位窗口，真库/签名/安装升级/CI/发布仍未验；不称发布验收。下文 G1–G6 为历史，勿重复实施；G8 须明确授权，不自动启动。

**G6 历史交付：维护者单独授权的 G6/M6 已本地工程完成。** 选定 Schema 的字段/注释检索、离线 SQL 收藏与入口整理实现 `d4b02de`，main 合并 `c182128` 已在全新合成 profile 重新通过全量（3753 passed、3 live skips）、强制 buildSrc（8/8）与 jpackageImage。实际 SHA、失败历史、产物摘要及 14 张合成桌面截图见 [G6 账本](../superpowers/verification/2026-09-24-datacube-g6-discovery-library.md) 和 [实际结果](../superpowers/verification/2026-09-24-datacube-g6-results.json)。原生模态输入与完整 AppShell 流程、OS 缩放/多屏、真库、签名/安装升级和发布仍待验，不能称为发布验收。下文首个目标启动内容为历史记录；勿重复实施 G1–G6。当时未自动启动 M7–M8；后续 G7 见上方最新状态。

**G5 历史交付：** 当前语句执行、可靠来源错误定位、作用域补全与计时口径实现 `76dd221`，补全引用来源边界修正 `c62e86c`，main 最终代码合并 `0e7ca79` 的全量为 3724 passed、3 live skips，buildSrc 8/8 与 jpackageImage 通过。实际证据及降级保留在 [G5 账本](../superpowers/verification/2026-09-24-datacube-g5-sql-context.md)，不作为 G6 新验证使用。

**2026-09-24 更新：G1（M0 + M1）已本地工程完成。** 实现提交 `965c3a3`，main 合并代码 `6596c00` 已重新通过全量测试（3514 passed、3 live skips）、buildSrc（8/8）与 jpackageImage。详见 [G1 实施与验收账本](../superpowers/verification/2026-09-23-datacube-g1-write-safety.md) 和 [实际结果](../superpowers/verification/2026-09-24-datacube-g1-results.json)。原生桌面、真库、安装升级与远端 CI/发布仍待验。下文保留最初启动交接的历史现场和指令；新会话应先看当前路线图，避免重新实施 G1。本任务未扩展到 M2–M8，后续 G2 由维护者单独启动。

## 1. 当前用户意图

维护者已认可整体产品审阅，要求制定完整计划、编写交接，并准备自行新开会话以目标模式运行。本轮只生成文档；没有开始修复、创建目标、新会话、commit、push、tag 或 Release。

总体路线见 [产品成熟度推进计划](../superpowers/plans/2026-09-23-product-maturity-roadmap.md)。新会话首个目标推荐 G1=M0+M1：核实基线，统一关系库连接的写入安全规则，完成本地工程验证与合并。

不要回到逐条增加 SQL 美化语法的旧主线，不要把完整路线图自动当成无期限目标。G1 完成后交付结果与下一目标建议，M2–M8 不自动开工。

## 2. 新会话首先核对的现场

| 项目 | 2026-09-23 文档编写时状态 |
| --- | --- |
| 仓库 | `D:\Projects\朝花夕拾` |
| 主分支 | `main` |
| HEAD | `792600c59e49bce3b300a71ccf412ed53d2b42e9` |
| 本地远端跟踪差异 | `main...origin/main [ahead 15]`；未 fetch，不代表已核对 GitHub 实时状态 |
| 原有未跟踪内容 | `.testagent/`，用户所有，禁止读取/修改/暂存/删除 |
| 本轮新增 | 本交接文件和总体计划，尚未提交；新 worktree 默认不会包含未提交文件 |
| 工具链 | Java 25 / JavaFX 25 / Gradle wrapper 9.2.0 / JUnit Jupiter 5.11.3 |
| Java 路径 | 当时 `JAVA_HOME=C:\Program Files\jdk`；新会话重新核实，不永久改系统环境 |
| 源码 / 测试 | `src/com/datacube/` / `test/com/datacube/`；不是 Maven 标准目录 |
| 发行 | Windows x64 安装版与便携版，JavaFX/jlink/jpackage；用户端无需自行装 Java |
| Git 工作树 | 已有多份历史 worktree；`git worktree list` 核对，不清理、不借用旧 formatter 分支 |
| 图标 | 现有雾紫数据折页方案已被认可，不重新设计 |

文档中的状态都可能过期。开始前运行只读检查，不自动 fetch/push、不把先前标签号或构建默认 `3.0.0` 当成本次发布版本。

```powershell
Set-Location -LiteralPath 'D:\Projects\朝花夕拾'
git status --short --branch
git rev-parse HEAD
git worktree list
git log -18 --oneline
```

只针对已知项目目录用 `rg` 检索，排除 `.testagent/`；不要打印所有环境变量、连接配置或真实 SQL 历史。

### 未提交交接文件如何进入新 worktree

先从上述主目录的绝对路径读取两份文档，不因新 worktree 中缺失而重新规划。若新会话在另一个 worktree 开始，明确设置命令 workdir，不误在 main 实施。

G1 启动后可按明确文件清单把这两份已确认内容带入新阶段分支并纳入本地提交；同步回 main 时先核对原副本完全相同。若未跟踪文件导致 merge 拒绝覆盖，停止该覆盖操作，保留副本并先妥善纳入版本控制，不删除原文档或用强制 checkout 绕过。尚未纳入 Git 时切换会话也不会自动丢失本机文件，但不会自动出现在其他机器/云会话。

## 3. 权限与不可变边界

- 实现可用独立 `codex/` worktree，本地提交、审查、验证后合并 main；先确认 main 未被其他任务推进或改动。
- 常规技术/交互选择自主记录决策，无需每一步让维护者确认；新目标指令是实施起点，本交接文档本身不授权现在就开始写功能。
- 禁止顺便 push、打/删 tag、Release、PR、安装更新或修改远端设置；既往某次“推送”不授权本次新阶段发布。
- 禁止使用真实公司数据库、现有连接/凭据/历史 SQL/业务导出做测试。使用 mock、合成 profile、独占临时目录。一次性真库也须先明确授权目标及操作。
- 不重建 `.codegraph/`、不读 `.testagent/`、不修改用户已有 worktree 内容、不清理旧验收目录。
- 不加入遥测，不上传 SQL/Schema/结果，不联系试用用户，不购买证书或创建凭据。
- 不自动生成用户未指定的 token 预算，不切换模型；子代理仅在当前用户/适用指令明确允许时使用。
- skills 按当前可用清单与任务匹配使用；旧计划的 superpowers 模板不是必须安装的依赖。新增测试时遵循当时适用测试指南，保留已有测试工程与断言强度。
- 任何“只读”结论都是客户端防误操作范围，不替代数据库最小权限；数据库函数等隐藏副作用不得承诺已完全阻止。

## 4. 当前产品能力，不要重复建设

- 已有 Oracle/PG 独立 SQL 会话、自动/手动事务、风险确认、超时取消；新操作和关闭生命周期有守卫。
- 已有多语句结果、概览/异常导航/详情、已加载结果搜索/筛选/复制/导出、单元格与行查看、列控制。
- 已有 SQL 文件打开/保存/另存为/外部修改保护、历史、草稿恢复、工作区恢复、离线脚本、明确选择连接。
- 已有当前展开树查找、单 Schema 表/视图查找、生成 SELECT、复制限定名称，以及 SQL 中 Ctrl+点击对象跳转。
- 已有表/序列/对象设计、同 provider Schema Diff 与部署、Oracle→PG 迁移、Redis、自动更新。
- 格式化近期加强了引用/注释/数字/运算符/CASE/数组等边界，仍是词法排版器，不是完整方言解析器。

## 5. 首个目标 G1 的具体任务

按总体计划的 M0 和 M1 实施，以下是最短接手路径。

### 阅读顺序

1. 本文与总体计划的范围、权限、M0、M1、M8 本地门槛。
2. `src/com/datacube/spi/model/ConnectionSafetyOptions.java`、`ConnConfig.java`、`ConnectionEnvironment.java`。
3. `service/ConnectionManager.java`、`service/JdbcEditorSession.java`、`sqleditor/SqlSafetyPolicy.java` 与 `SqlSafetyAnalyzer.java`。
4. `service/DataEditService.java`、`service/TableDesignService.java`、`service/DdlService.java`、`service/SchemaDeploymentService.java` 及相关 admission。
5. `fx/ConnectionTreePane.java`、`fx/AppShell.java`、`fx/DataGridPane.java`、`fx/TableDesignerPane.java`、`fx/ObjectEditorPane.java`、`fx/SequenceDesignerPane.java`、`fx/SchemaDiffPane.java`、`fx/SqlEditorPane.java`。
6. `.github/workflows/verify.yml` 与最近验证记录。只读需要的章节，不把全部历史文档扫入上下文。

第 2–5 项路径前缀均为 `src/com/datacube/`。仓库中的大类已超过千行，优先按明确方法名使用 rg 和局部读取。

### 已确认的第一个切入点

`ConnectionTreePane` 的表节点通过 `openDataGrid(..., false)` 打开；`AppShell` 将该值传给 DataGridPane。DataEditService 的 insert/update/delete 不检查连接只读配置，ConnectionManager.acquire 也不是带安全策略的编辑器会话。

先建立可控 JDBC/服务边界回归，证明只读连接的写入被拒绝且未获取写资源；再实现最小共享策略并扩展到表设计、对象 DDL、序列和部署。不要只禁用按钮，也不要只调用 JDBC setReadOnly 就宣称完成。

### 必须单独作出的安全设计决定

- 当前连接配置和已固定会话快照如何保持一致；只读/生产配置收紧后新写入如何失效。
- 确认与连接 ID、目标配置、SQL/变更集、操作类型绑定的方式；目标或操作变化必须使旧许可失效。
- 既有未提交事务的提交/回滚、取消与关闭如何保留正确语义，不因为新门禁造成隐式提交或资源泄漏。
- 明确哪些入口消费 ConnConfig，哪些是独立迁移/Redis；不能靠静默排除扩大“全产品只读”的宣传范围。

### G1 完成条件

1. 写入口矩阵完整；每个适用入口有服务层只读门禁、生产确认及对应回归；不改变连接身份、事务隔离或恢复语义。
2. Oracle/PG、只读/可写、生产/非生产、取消/旧确认/配置变化/关闭与直接服务调用均有行为证据；只读拒绝的写调用计数为零。
3. 全量单测、buildSrc 测试、jpackageImage 和差异审查通过；既有偶发异常如未复现保留原因未明记录，不伪装修复。
4. 已本地提交、合并 main 并复验，提交与验证 SHA 明确；没有 push/tag/发布。
5. 文档、进度记录及待外部验收清单同步更新。原生桌面、真实数据库、安装升级、远端 CI 如未执行须单列，不算本地工程完成之外的承诺。

出现新的确定性阻断失败、门禁漏接或未通过的必需测试，不得仅以“已记录风险”结束 G1。确需新权限/选择时报告具体问题，并遵循当前目标工具的状态规则，不伪称完成。

## 6. 验证命令与环境注意事项

以下命令在实现 worktree 根运行，不在用户真实数据库连接上运行。

```powershell
.\gradlew.bat clean :buildSrc:test test --no-daemon --console=plain
.\gradlew.bat jpackageImage --no-daemon --console=plain
git diff --check
```

先按实际修改运行定向测试，再运行全量。查看 `build/test-results/test/` 和 buildSrc 报告，汇总实际 counts、skip 原因、首轮失败及复跑；不要硬编码旧测试数作为通过标准。

之前 JavaFX 测试曾使用当前进程范围的 `JAVA_TOOL_OPTIONS=-Djava.awt.headless=false`。先确认本次环境是否需要；如调整须保存并恢复原值，不能覆盖其他必要选项，也不能写入系统设置。镜像构建与启动不得继承测试 profile/headless 参数。

原生界面验证只使用合成 profile/数据，不打开真实保存连接。桌面不可用时记录待验，不反复发送输入；纯逻辑与可运行的 FX 集成测试可继续。不得把旧窗口截图或历史验收冒充当前版本证据。

## 7. 必须保留的旧验证事实

来源：`docs/superpowers/verification/2026-09-23-sql-formatter-array-layout.md`。

- 最近一次记录的最终全量：262 suites、3,484 tests、3,481 passed、0 failures/errors、3 existing live skips；jpackageImage 成功。
- 同轮第一次全量曾失败：`SchemaDiffServiceTest.providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview`，`Schema snapshot failed`。
- 单类 3 项复跑及第二次全量通过；根因未明确，不声称该偶发问题已修复，不用成功复跑抹去首次失败。
- 保留既有 unchecked 编译提示及 JEP 493 jlink 提示；不能报告“零警告”。
- 上述都不是本轮文档工作或新会话的实时验证，不证明真库、原生桌面、安装升级或远端 CI 通过。

## 8. 目标模式启动说明

官方把 `/goal` 用于有可验证结束条件的持续任务，建议目标比开放待办更明确，并给出验证循环和检查点。这里据此把完整路线拆成 G1–G8，而不是把“不断完善产品”写成永不结束的目标。[OpenAI 官方说明](https://learn.chatgpt.com/use-cases/follow-goals)

此会话没有创建运行目标。维护者应在新建的本地项目会话提交下方文本；若客户端将 `/goal` 作为选择式命令，先选中目标模式再粘贴正文。具体界面是否提供该入口以新会话实际为准；不要据此自动修改 Codex 配置或创建定时自动化。

### 可直接复制的推荐启动指令

```text
/goal 完成 DataCube 产品成熟度计划的首个目标 G1（M0+M1）：统一所有消费关系库 ConnConfig 的写入口的只读门禁和生产环境确认，完成本地验证、提交并合并回 main。

项目：D:\Projects\朝花夕拾。
先完整阅读：
1. D:\Projects\朝花夕拾\docs\handoffs\2026-09-23-product-maturity-goal-handoff.md
2. D:\Projects\朝花夕拾\docs\superpowers\plans\2026-09-23-product-maturity-roadmap.md

这是实施目标，不是再次只给建议。先核对当前 main、工作区和证据基线，在独立 codex/ worktree 中按 M0、M1 分步执行。不要重复实现已有功能或恢复 SQL 美化的零散扩展主线。常规方案自主决定并记录，不需要每轮让我“继续”。本目标完成后交付，不自动扩展到 M2–M8。

完成标准：写入口矩阵与服务层门禁齐全；只读明确写操作在获取写资源前拒绝；生产确认绑定目标和请求；保留事务、取消、关闭和配置变化的正确行为；通过定向/全量/buildSrc 测试及 jpackageImage，审查后本地提交、合并 main 并复验，更新实际证据和待验项。

不得读取或修改 .testagent/，不得访问真实连接、公司数据库、凭据、SQL 历史或业务文件；用 mock、合成 profile 和临时目录。不推送、不打/删 tag、不发布、不建 PR、不安装更新、不联系外部人员。真实数据库、签名凭据和其他外部操作另行请求明确授权。

每个检查点记录当前目标、改动、验证、失败/未验、下一步；旧测试通过不充当新证据，跳过不算通过。缺少桌面或真库证据要单列，不能宣称已完成发布验收。必要权限或重大范围变化才向我提问；被阻塞时遵循目标模式的实际状态规则，不自行放宽验收、设置预算或伪报完成。
```

## 9. 给接手代理的首轮输出要求

第一次进度更新只需说明已读计划、实际分支/SHA、是否有额外用户改动、首个实现切口和下一验证动作。无需再次输出整篇产品审阅，也不要立即修改整个 UI。

阶段结束交付：结果与已知限制、提交及 main SHA、测试/构建证据、未执行验收、后续 G2 建议。若仅完成部分，明确剩余任务；不能以“本轮结束”替代目标完成。
