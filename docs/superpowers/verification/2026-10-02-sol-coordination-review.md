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

## R2 — 第三次 heartbeat 预审

- 当前目标：独立核对重采桌面证据、夹具关闭修正和最终镜像，等待完整 P0–P2 提交后执行集成审查。
- 基线与改动：main / 协调分支为 f4d0e4de90d5629f9009a0fa31b71a925bba28dd；开发分支仍为 6f4ad93，仅未跟踪账本/证据，无产品源码或测试改动。本检查点只追加协调记录，没有操作开发桌面、运行其 Gradle、修改开发文件、访问真库或合并开发分支。
- 重采图像验证：直接查看 native-recheck 的 05-result-selected-1、07-data-stable-0、10-ddl-stable-0，分别实际显示 demo.orders/customer_id 命中、含合成 101/202 两行的“数据（只读）”及禁用写按钮、实际只读 DDL 内容。它们与对应 accessibility / recheck-shell-runtime.log 一致，已解决 R0/R1 的命名与首页不符问题；旧 native/02–36 继续保留为失效尝试，不能补算通过。检索词仍由夹具程序化预填，不能把结果按钮的原生证据升级为原生查询输入通过。
- 保存链路验证：直接查看新 17-production-confirm-2-rows、18-esc-cancel-drafts-retained、23-one-committed-one-pending、25-retry-completed；确认内容含 synthetic.invalid:1、生产环境、synthetic.items、修改 2/删除 0、逐行独立提交。取消后保留两行；取消剩余保存后第一行已提交、第二行未执行；24-retry-one-row-confirm 的 accessibility 仅含剩余修改 1 行。重采运行日志最终 2 writes /2 commits /3 opens /3 closes。以上为合成 DataGridPane / mock JDBC 链路，不代表 Oracle 真库生产写验收或完整 AppShell 在途退出。
- 关闭失败与修正：recheck-mandatory-runtime.log 因 mock 写屏障持有超过实际 SaveAttempt.awaitClose 的 15 秒期限，返回 FAILED_PARTIAL（当时 2 opens /0 closes /0 commit）；随后原夹具不检查 outcome 就 finish 并释放资源，这不能计为产品批准关闭。开发已在 tool-failures.txt 明确拒绝计入，并把外置夹具改为 error == null 且 outcome == APPROVED 才 finish，产品源码未改。新 recheck-mandatory-fast 日志为初始 done=false、中断后仍持有资源、释放后 APPROVED /1 rollback /0 commit、最终 2/2；33-fast-mandatory-waiting 图与在途状态相符。三个有效重采 shell/grid/mandatory-fast 的专用 launcher.stderr.log 均 0 字节；只读取本轮明确临时输出，没有读取 profile/SQL 历史。
- 键盘证据范围：直接查看 12-ddl-enter 和 15-ddl-escape，可见 customer 查找 1/1 与 Esc 隐藏查找栏；recheck-shell 日志记录 Tab 到区分大小写、Shift+Tab 返回输入、Esc 到 DDL 文本。accessibility 的 focused_element 仍报告树查找字段，与实际 FX 焦点日志不符，最终说明应以截图/FX 日志明确限定，不宣称整个窗口键盘或辅助功能完全通过。旧小窗口图已失效，新小窗口、原生字段输入/完整查询结果键盘及 OS 缩放/多屏仍不能认定通过。
- 源码与用例审查：读取实际 WriteSafetyDialog / WriteOperation / WriteTarget、DataEditService 保存请求、DataGridPane 保存/关闭、AppShell 数据/DDL 路由及既有定向用例。生产确认直接展示不可变请求，服务再次校验目标及一次性确认；保存取消不撤回已提交行，超时关闭不批准。现有测试覆盖迟到批准后配置收紧、拒绝获取写资源、明确重试不重放已提交行、PG/Oracle 表/视图被动 SELECT/只读数据/DDL 及失效/物理释放。程序化 FX fire 明确属于合成证据；当前未发现需扩大范围的产品修复。
- 镜像验证：final-image.log 的 jpackageImage /jlink 本轮实际执行，BUILD SUCCESSFUL /14 executed。独立 jimage 索引与 355 个当前测试源码类型逐项比对，未发现测试/探针/JUnit/Mockito/TestFX 类；DataCube.cfg 未带验收 profile/headless/sol/live 选项。modules SHA256 为 242CE98352FD2F1AE829C3F41CC3B078E05ECAD2AB43ED2B2F84C526AA28AD0E，cfg 为 E53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D，exe 为 6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F，与开发 image-audit.json 相符。开发零连接驱动探针仅反射 driverFor，未调用 connect/open；本次未自行执行该探针。当前证据目录文本扫描未发现指定真实目标字面值；最终归档/暂存字节扫描仍待交付。
- 审查工具误报：协调线程最初不区分大小写的 Test 子串匹配误报 10 个生产类（含 SqlFavoriteStore、ConnectionTestController）；已改为精确测试类型路径和区分大小写的依赖/探针路径，重新审计为 0，不是产品泄漏。开发独立审计也保留了同类首轮误报文件与说明，不删除失败历史。
- 未验与下一步：开发账本仍停 P0/待执行，最终 manifest、动作/状态矩阵、提交 SHA 及停工交付尚未给出，不能把本预审替代 P2/P3 完成。list_threads 仍无正式 id，已有任务链接问题待答，不重复提问、不创建线程、不伪报修正已下发。待完整提交后审核最终字节和精确待验项，再本地集成 main、新 profile 复验、更新交接与路线图；P3 完成前 heartbeat 保持 ACTIVE。

## R3 — 开发交付审查通过，准备 main 集成复验

- 当前目标：审核已提交开发交付，按原 P3 范围本地合并并取得 main 新证据；尚未宣称 P3 完成。
- 开发提交：a69f7dbf7a0e0d567f174e7f6ed058ae01d61948，codex/sol-p0-p2-acceptance，授权范围干净。共 1184 份本轮账本/证据/外置夹具文件；src/test/buildSrc/build.gradle/settings.gradle 与 6f4ad93 无差异，产品没有新修复或功能扩展。开发账本已更新完整矩阵、检查点、工具失败、精确未验和提交后停在 P3 审核的约定；R2 早期“账本停 P0”是当时快照，已被这次实际交付更新。
- 原始字节核验：raw-byte-manifest 的 1182 个文件逐份 SHA256/长度匹配（共 36339036 字节，排除 manifest/audit 自引用）；独立 git cat-file --batch 对当时全部 1184 份实际暂存 blob 与磁盘原字节比对，0 差异。提交后核验开发 staged-byte-audit 的 1183 个 blob OID 均进入 HEAD，audit 自身无过滤 hash-object 也与 HEAD 相同；未修改开发索引/文件。证据局部 .gitattributes 的 -text 保留 XML/log/PNG 原始字节，不改变产品属性。
- 行为与证据判定：R0–R2 所列截图、失败退出误计和镜像类筛选误报均已由开发修正，并在最终账本明确排除旧失败。新图/日志、现有实际 FX/mock 用例、最终工程执行及镜像审计互相对应；程序化查询词/两行草稿、夹具强制关闭按钮、真实服务 guard 与原生动作分开。未发现需要通过线程投递的剩余修正；不伪报曾下发返工。
- 未验保留：字段原生输入/完整键盘/结果方向键，Oracle/view/SELECT 原生本轮重跑、原生配置收紧/迟到批准、完整 AppShell 在途退出/超时恢复、缩小窗口/OS 缩放/多屏、正式启动器/安装升级/签名/CI/用户任务、PG/Redis/原 SchemaDiff live、本轮 Oracle 复验均未完成；SchemaDiff 首轮间歇失败根因未定。M8/发布不称完成。3 项 live skip 的原始 assumption 原因已核对，未计通过。
- 协调与工具失败：交付后再次 list_threads 仍无正式 id，不能调用 wait_threads/send_message；没有绕过工具限制或创建线程。当前改用已提交 worktree/原始证据审核，任务链接信息请求仍待答；因本次审核不需返工，该工具缺口不妨碍已授权本地集成。另一次按推测包路径读取两份 live XML 失败，已用 rg --files 定位 com.datacube.redis / com.datacube.schemadiff 的真实 XML并完成读取，不影响测试结果。
- 下一步：在协调隔离分支合并精确开发提交，再 fast-forward main；单 Gradle 顺序、离线、清除 live/JVM 注入环境、独占临时 profile 执行新定向/全量/buildSrc/jpackageImage 和零连接镜像审计。保留首次失败，更新交接/路线图及 P3 实际结果后交付并暂停本 heartbeat，不扩展下一轮。
