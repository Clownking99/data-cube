# SQL取消请求的执行身份

2026-10-08，基线main eaa59237a565c1278f7701eae0b44daf074bf08b，检查范围内干净。维护者继续推进产品，沿用GPT-6.1-sol开发、root任务下发/审核/修正、独立codex分支、本地合并main及推送授权。复用现有worker及工作树，不新增线程。

## S0 目标与范围

上一轮错误询问等待已修复，但明确未覆盖无错误/近结束执行的异步取消。静态风险：pane将session.cancel排到独立后台任务，旧执行若先自然结束、UI恢复并启动下一次执行，迟到取消是否错误命中新activeControl/Connection。目前只是待证风险，不称已复现。

先只读梳理pane、FxTaskScope、SerialSessionOperationQueue、JdbcEditorSession、SqlExecutionControl的身份/资源所有权，设计有界且保留真实调用链的首红。最小修复须让请求绑定发起时的执行，禁止旧请求取消/关闭/污染新执行；保持正常取消、连接fallback、事务、关闭、队列物理完成顺序和旧询问门禁。不得靠放宽超时或阻塞FX解决。若相邻EXPLAIN/参数化执行可共用服务层机制，由实际调用链和首红决定，不随意重写执行器。

## 顺序及检查点

S0 root审阅诊断和复现方案；S1保存首红命令/退出码/XML/源码，再批准最小实现；S2定向及源冻结；S3独立审查、全量/强制buildSrc/jpackageImage/镜像隔离；S4提交、main合并并新隔离目录复验；S5更新交接及待验，推送main并核对精确SHA CI。旧通过不得充当新证据，失败原件保留，跳过不记通过；任何未运行或外部异常明确记录。

## 边界

.testagent禁读/改/枚举/暂存/清理；不访问原配置/连接/凭据/历史/业务文件，不做真库/原生输入/安装更新/外部联系。仅mock、合成profile、独占UUID临时目录，使用实际8.3临时路径。root独占Git集成及7897代理main推送，不fetch/强推/PR/新增或移动tag/Release；v3.2.9保持，datacube跟进维持PAUSED，不自动下轮。开发代理不做Git写入或网络请求，先无Gradle许可。

上一轮main本地工程通过仅为历史基线，精确SHA CI再次查询仍0记录、状态未验，原回执已保存防clean覆盖。新一轮不得将它记为通过。真实驱动/原生桌面/OS缩放、多屏、终态恢复、无Gate启动、安装升级签名及完整M8继续待验。

## S1 首红后实现约束

001实际PG/Oracle×A/B四例均产品红灯：旧延迟任务取消新Statement，旧物理取消晚异常关闭新执行连接。根已独立审raw和源；不再只是风险推测。修复须同时绑定请求身份和物理取消所有权，并处理pane已接受/服务尚未发布的取消空隙，保留正常fallback/事务/关闭/error gate。允许必要小型执行句柄，不改队列/关闭状态机/timeout，不重写WriteOperation安全门禁。新服务级用例按具体风险最少添加，原4例契约不降低。当前待具体设计确认和实现，尚无新通过结果。

## S2–S4 实际实施进度

最终实现仍限pane与JdbcEditorSession：执行身份在接受时建立，FX捕获取消意图，后台物理取消/fallback在自身资源结算后才释放singleFlight；未启动的queue内部拒绝和queued取消也终结句柄，已启动操作不被提前清空。原4PG/Oracle首红不改，新增10服务例及1真实queue拒绝例；旧源码契约同步更新。首红/失败/更正及每次检查点见独立审核记录。

源f656be08、分支证据dcdfa288；分支最终定向357/357、clean全量4075通过/3明确live跳过、强制buildSrc8/8、强制image14任务及183文件隔离/驱动发现审计均完成，566份原件Git字节相同。已本地合并main fd5159ce，当前main新隔离profile复验进行中；发布/完整M8未完成，最终main证据和精确SHA CI仍以新回执为准。没有真库/原生输入或新tag操作。

## S4–S5 本地交付结果

main fd5159ce完成新定向357/357、clean全量4075通过/3明确live跳过、强制buildSrc8/8、强制image14任务及183文件审计，三产物SHA与分支相同。实际源树/checkout、失败原件、每轮验证及待验详见[独立审核记录](../verification/2026-10-08-sql-cancel-identity-review.md)。最终证据提交后仅推送main，精确SHA CI状态以delivery-intent指向的回执为准；当前不称CI或完整发布验收通过。本轮完成本地产品增量交付，不自动进入后续功能。
