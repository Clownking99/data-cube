# GPT-6.1-sol 开发：协调线程审查记录

客户端日期：2026-10-02。计划见 [开发与审查计划](../plans/2026-10-02-sol-development-coordination.md)。本记录仅为开发进行中的预审，不能替代最终 P3 或本轮验收交付。

## R0 — 首次 heartbeat 预审

- 当前目标：解析正式线程 id、检查是否已有完整交付；没有完整交付时只做不干扰开发的预审。
- 基线：main a247b91adb8daf4c204eaa413247715a26c86d76，授权范围干净；开发 worktree b07c / codex/sol-p0-p2-acceptance 仍在 6f4ad93，未提交本轮代码/证据。源码/test/build 与 P0 无差异；没有并发运行该工作树 Gradle 或操作其桌面。
- 改动：仅创建本协调审查记录；没有修改开发线程文件、合并开发分支、访问真库或新建线程。
- 验证：开发已有 P0 矩阵、外置 mock 夹具、6 suites /75 项定向 XML/summary 和原生尝试文件。75 是当前 summary 的数量，尚未完成原日志/最终快照/manifest/暂存字节的最终审查，不能称 P2 通过。开发账本仍明确各项待执行，没有虚假勾选。
- 预审发现：native/13-corrected-native-selection-0.png 与 native/14-readonly-data-stable-0.png 实际均为 AppShell 空白首页，既没有字段结果选择也没有数据页；两图在本次观察的 SHA256 同为 2BF87792D2A30D37DA67CF37EA45F3B5BCD44B5393A18D71373D44E1D985BA3A，对应 JSON 也为首页。若最终仍为这些文件，不能按名称计入结果选择/只读数据通过。应保留尝试失败与实际状态，另取得对应页面/动作证据，或列为未验；不为工具故障修改产品语义。此结论针对当前快照，不预判开发后续结果。
- 边界审查：已读外置 shell 和 launcher 的部分源码，均为合成 mock 目标与独占临时目录；种子检索词的程序化预填已写在夹具说明中，应继续与原生输入分开。没有把种子预填算作原生输入通过。需要最终核对完整 JDBC 边界、write/resource 计数、正常关闭、夹具首轮类型错误和强制停止的未验记录。
- 协调未验：list_threads（有效最大范围 50，26 项）仍未返回该创建任务的正式 id；归档列表也没有匹配目标。不伪造 id，不把 client-new-thread 标识传给 wait_threads/send_message。开发文件仍有新增，不能把 API 可见性问题解释为开发已停止；不创建重复线程。当前还不能通过线程工具投递修正；修正要求暂记于本记录，送达状态为待送达。
- 下一步：下次跟进先解析正式 id，用 wait_threads 快照取得进度/交付。向已授权开发线程发送上述精确证据问题，最终审查代码、全量/buildSrc/镜像/原始归档和待验项；通过后才集成并新 profile 复验。没有完整交付前不合并、不暂停本跟进，不自动扩大范围。

## R1 — 第二次 heartbeat 预审

- 当前目标：检查 R0 证据问题和新增原始结果，继续 P3 独立预审；开发尚未提交完整 P0–P2 交付。
- 基线：main / 协调分支均为 7fd57705bab49fabddfc0a33f848ba77e5c08591，授权范围干净。开发分支仍为 6f4ad93，仅本轮未跟踪账本和证据；产品源码、测试、buildSrc/build.gradle 没有新差异。核验 source-freeze.json 的 774 个明确源码/测试/构建文件，当前 SHA256 和字节数全部匹配；未枚举或读取 .testagent。
- 改动：仅追加本协调记录；未操作开发桌面、运行其 Gradle、修改开发文件、合并开发分支或访问真库。只读取本轮合成夹具和指定原始证据。
- 新验证：独立汇总原始 TEST-*.xml，与摘要一致。full-first：310 suites，3919 总数，3915 passed /1 failed /3 skipped；schema-recheck：3/3；full-recheck 与 final-full：各 3916 passed /3 skipped /0 failed；final-directed：6 suites /75 passed /0 skipped；final-buildsrc：8/8。final-directed、final-full、final-buildsrc 日志明确本轮任务实际执行且 BUILD SUCCESSFUL，跳过的 3 项不计通过。这些仅证明当前归档工程结果，最终镜像、manifest、暂存字节和交付审查仍待完成。
- 首轮失败：SchemaDiffServiceTest.providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview，原始 XML 和 full.log 均为 CompletionException / IllegalStateException: Schema snapshot failed（测试第 76 行）。重跑通过不能证明首次失败根因已修复；最终报告须保留首轮失败和根因未确认状态，不据此自动扩展产品功能或宣称消除不稳定性。
- 截图问题的当前处理：native/02 至 36 共 35 张 PNG 的 SHA256 完全相同，均为 R0 所见首页；16-ddl-preview-stable 也在其中，不能接受为命名步骤证据。开发随后在自己的 tool-failures.txt 明确记录“Archive helper stale state invalidates native/02 through 36 as named-step evidence”，正在用显式 snapshot 参数重验，另保留 launcher 首轮单源编译失败。R0 问题已由开发自行识别，但新截图及最终说明尚未交付，当前不计相应原生通过。
- 新链路观察：corrected-shell-runtime.log 记录 mock 检索 2 次、只读 page 1 次、DDL 预览 1 次、write/execution 均 0，关闭时 mock opens/closes 为 3/3；程序化查询预填仍与后续动作分开。production-grid-runtime.log 记录在途 interactive close REJECTED，释放写屏障后两次写 /两次 commit，最终连接 3/3 关闭；mandatory-runtime.log 记录关闭 future 初始未完成，线程收到中断仍持有资源，释放屏障后 1 次 rollback、0 commit，最终连接 2/2 关闭。这是合成 JDBC /夹具运行日志观察，不能凭这些文本补认已失效的原生截图。
- 夹具源码审查：完整读取本轮 shell/grid、JDBC/save 边界和 launcher。目标为 synthetic/example.invalid，空密码，mock provider 无 DriverManager；行草稿和查询词程序化初始化明确标记，未调用保存 fire。grid 的关闭按钮调用实际 DataGridPane 生命周期；终态资源断言及 stderr、动作映射、确认目标/请求、配置失效和完整 AppShell 关闭仍须最终对应证据审核。
- 协调未验：再次 list_threads（有效范围 50）仍无该任务正式 id，开发证据持续新增，未创建重复线程、未使用 client-new-thread 标识代替 id。已向维护者请求任务链接以取得正式 id，问题仍待答；修正任务尚未通过线程工具送达，不伪报已下发。
- 下一步：取得正式 id 后用 wait_threads 紧凑快照及已授权 send_message_to_thread 跟进必要修正；待完整提交后审核新截图、首轮失败记录、最终镜像/清洁度/manifest，再决定集成与 main 新 profile 复验。P3 未完成，跟进保持 ACTIVE，不自动启动下一轮。
