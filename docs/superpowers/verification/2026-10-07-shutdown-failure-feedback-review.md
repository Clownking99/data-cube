# 退出部分失败可见反馈：独立审查

## S0 当前目标/基线

main/worktree 5fd42ce02a621a4b8070447ebe8defc36d15b11e、授权范围干净；此前 Verify 37596231516 四任务成功回执已读，仅作基线。memory相关检索无命中。复用既有 worktree 的 codex/shutdown-failure-feedback-20261007；不创建侧栏线程，不恢复 datacube 自动跟进。

root独立阅读DataCubeFx、ShutdownQuarantine、AsyncShutdownCoordinator、AppShell关闭、旧启动器契约和真实AppShellGridShutdownTest。源码确认FAILED_PARTIAL时主体保持禁用、只写stderr，普通桌面没有原因说明；原启动器契约只查字符串，不能证明可见行为。本轮补可见反馈，不改变终态不可重试隔离，也不宣称已完成终态进程内恢复。

sol建议小型生产WindowShutdownController承接原handler，Scene外层BorderPane在body之外显示持久提示；root审核接受，要求双主题真实shown主窗口最小900×600及1200×800边界、旧取消/完成/异常、重复关闭和既有真实mock15秒终态。提示不含原始异常/SQL/路径，不保证已保存或回滚，不自动kill/重启。详见本轮计划。

当前只有源码风险与明确缺少可见说明，尚无本轮行为红灯或新测试。准备了复用且改本轮独占prefix的根验证/审计脚本，PowerShell/Node语法检查通过；这不算产品测试。worker独占Gradle，root等待首红/定向再独立审核。所有历史原件保留，真实连接/原配置/历史/业务文件/.testagent未访问，原生/安装签名/完整M8仍未验。

## S1 生产处理器提取后的实际首红

root独立读取001-red-feedback command、exit1、实际:test日志与XML：编译成功，双主题×900×600/1200×800共4tests/4failed/0skip。实际scene885.33×562.67和1185.33×762.67，bodyDisabled=true、窗口继续显示，但shutdown-failure-notice不存在。不是缺类/编译错误或跳过。

root核对归档red-extraction-source三个文件：DataCubeFx已直接接WindowShutdownController，测试触发其Stage关闭handler及真实future；原quarantine/迁移确认/CANCELLED恢复/COMPLETED关闭分支被提取保留。确认弹窗补owner；新增StackPane外层供反馈与body分离，需在最终实现检查普通布局与主题继承。提取快照/patch保留，首红表述限定为旧语义提取后的行为复现，不声称测试调用未提取的Application.start。

下一步只补固定可见说明并扩展真实handler分支、现有mock JDBC15秒超时接入；root继续审核最终差异和原件。当前未有绿灯/完整工程/main新证据，未修改状态机/物理等待常数。

## S1 首轮扩展夹具诊断

002-green-handler实际12项/4失败/0跳过，exit1；root读取全部failure message，失败均为新夹具要求长说明固定多行。900/1200窗口下Label可合法单行，不能以行数推断裁切；最终应检查实际Text内容、布局边界和对比度。旧quarantine3项与pending/completed/cancelled/异步异常/null/迁移拒绝分支通过，但整轮仍失败，不当最终定向证据。

worker准备限制反馈阅读宽度760，并补普通body原场景占满、真实Text边界和合成Scene snapshot；宽度限制用于阅读行长，不能作为放松完整显示断言的理由。根等待003新证据与真实AppShell超时接入，原002原件保留。生产仍只反馈层/启动器接入，不动状态机及等待常数。

## S2 最终定向与独立源码审查通过

003-green-targeted为5套32项全通过/0skip，实际mockPG/Oracle物理等待15214/15131ms；root阅读完整XML并查看两张纯合成Scene PNG，固定提示全文可读，body禁用后说明保持正常明暗主题，不代表原生桌面。root检查生产helper、DataCubeFx接入及全部新增/变更测试，要求修正后台complete未确认就按3帧断言、Grid future已结算但提示runLater尚未执行的测试竞态，不能盲增全局timeout。

004-final-targeted-reviewed采用后台finished latch与FX屏障后新执行5套32项（controller9、quarantine3、真实grid6、shell恢复9、shutdown coordinator5），0失败/错误/跳过，exit0。实际PG15341ms/Oracle15262ms；新banner前后仍验证资源被持有、重复close supplier请求1、迟到数据库结算不开始全局teardown、不恢复UI，最终fixture清理另算。旧5秒提示与15秒等待不改。

最终生产仅DataCubeFx提取接入与WindowShutdownController：StackPane外层不缩放/重排body，终态后body外新增max760宽的固定说明；深浅主题、900×600/1200×800、真实Text内容/边界/颜色对比度、普通body占满和反馈不改变body几何均实测。无自动kill/restart/retry、无异常/SQL/路径拼入提示，原状态机/资源所有权保留。原只查src字符串的1项契约升级为9项实际handler行为，原AppShellGrid的6项保留并在两种timeout分支接真正controller。

源码审查通过，根独立冻结4文件SHA到reviewed-source.json；批准worker串行新全量/root buildSrc强制/image，仍由worker独占Gradle。002错误多行假设与root时序修正均记入原件，003图片只绑定未改变的生产反馈实现。完整工程/main/远端及本轮原生尚待，不预报通过。

## S3 分支完整验证与源码提交

root独立Audit-Worker核对实际Task/command/exit、新鲜XML与冻结4文件摘要：最终定向32/32、全量314套/4008总数/4005执行通过/3live跳过、root buildSrc强制8/8，全部无失败/错误；image实际jpackageImage强制执行14任务、exit0。3跳过为Redis standalone和Oracle/PG SchemaDiff live，原因原文归档，不计通过。root审计脚本最初控制台对OrderedDictionary用Select-Object显示null，已确认保存JSON/校验计数完整正确并只修控制台投影；非测试失败，不改原结果。

源码仅4文件提交33e7773bd3bfbe28f2d7404792a0a05c8ed561d2。冻结4文件、完整patch与runner摘要独立实算匹配；373份worker原件按原manifest独立校验长度/摘要。branch-image-audit通过：183文件、模块类与cfg无测试/探针/profile/测试JVM选项混入；外置driverFor发现Oracle/PG，connectCalls=0。新runtime/modules为102404721字节、SHA7FC8A42EE0A67DB2FE84D24682D4465A84D5F9FBE143043DB70F57393CEF8095；完整exe/cfg/modules身份归档。

本阶段分支工程与独立审查通过，尚未main复验/最终CI。新镜像不复用旧原生证据；真实Application.start/原生迁移确认/OS缩放/终态进程内恢复/安装签名及完整M8仍未验。下一步冻结证据Git原字节、提交、合并main并新跑完整矩阵。

S3归档诊断：首轮Git字节审计拒绝worker首红XML，原因是worker证据目录缺少局部-text属性，暂存发生换行规范化。未修改原始日志/XML；保留首次manifest副本，补该独占证据目录.gitattributes后重新冻结/暂存和审计。此前373原件摘要再次核对；不是产品或测试失败，不以规范化后字节冒充原件。
