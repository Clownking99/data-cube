# DataCube P0–P2 本轮验收

客户端日期 2026-10-02；执行主机原始 UTC 日期 2026-10-01，日志/XML 时间不改写。工作树 C:\Users\hetia\.codex\worktrees\b07c\朝花夕拾，分支 codex/sol-p0-p2-acceptance，开发基线 6f4ad93e2f6226a00a8df72f31091fc7bcbc41eb，产品历史基线 90413eb。本轮仅增加外部验收夹具、脚本与证据，产品源码、测试源码和构建配置未改；没有合并或修改 main。本地提交后停在 P3 可审核状态。

四份指定协调计划、交接、路线图和 Oracle 账本完整阅读，历史通过不充当本轮证据。不读取、枚举或操作 .testagent/；Git 状态和检索显式排除它。不读取原有真实 profile、连接、凭据、SQL 历史或业务文件。所有 GUI 使用新独占临时 profile 与 mock JDBC，无真库访问。无 push/fetch/tag/PR/发布/安装更新/外部联系，未创建额外线程或子代理。

## 结论与矩阵

本轮工程检查通过，补取原生字段结果转只读数据/DDL、生产确认取消/批准、在途交互关闭拒绝、取消剩余保存与明确重试、强制关闭等待证据。键盘与窗口验收部分完成；完整 M8/发布保持待验。首轮全量的既知间歇失败未修复。

下列证据路径均相对 [本轮证据目录](evidence/sol-p0-p2/)。

| 用例 | 本轮等级与结果 | 原始依据 |
|---|---|---|
| AppShell 请求→选择→SELECT/数据/DDL；PG/Oracle、表/视图、失效/只读/物理准入 | 新 FX + mock 测试通过；SELECT 不自动执行、数据禁止写、DDL 仅预览 | final-directed/ 的 MetadataSearchShellRoutingTest XML |
| 原生字段结果转数据/DDL | PG 表 mock，通过；查询词程序化预填，后续提交/选择/动作原生 | native-recheck/03–10；recheck-shell-runtime.log |
| 精确生产目标、取消不写、批准一次、配置变化迟到批准失效 | 新 FX + 实际 mock 写资源/计数通过 | final-directed/ 的 WriteSafetyIntegrationTest、DataGridSaveFlowTest、RelationalWriteSafetyTest XML |
| 原生生产取消/批准、在途关闭拒绝、部分提交和明确重试 | 通过；修改两行→取消剩余后提交一行→重试确认仅剩一行 | native-recheck/17–25；recheck-grid-runtime.log |
| 强制关闭等待资源/抑制迟到回调 | 新 FX 断言通过；原生夹具入口调实际 guard，及时释放后 APPROVED、资源 2/2、回滚 1 | native-recheck/30–33；recheck-mandatory-fast-runtime.log |
| DDL 查找键盘 | 原生输入、Enter、Tab、Shift+Tab、Esc 通过 | native-recheck/11–15；recheck-shell-runtime.log 的焦点/键事件 |
| 字段对话框完整键盘、query typing、结果键盘导航、缩小窗口 | 未验：两次原生输入无效果后停止；拖动未改变尺寸 | tool-failures.txt；以下未验项 |
| P2 定向/全量/强制 buildSrc/新镜像/镜像与字节审计 | 新执行通过 | final-*、image-audit.json、raw-byte-manifest.json、staged-byte-audit.json |

## P0 检查点：基线、工具与隔离

目标是核对精确基线、现有实现、工具与边界。HEAD 匹配，授权范围 Git 初始干净，建立独立分支；JDK D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8 与离线 Gradle 可用，Computer Use list_windows 成功。两次 rg Windows 文件通配符失败，改用 -g；没有为流程重建 CodeGraph 索引。

改动为本账本、isolation.init.gradle 与归档脚本。Test JVM 的 user.home 指向新临时目录，显式 java.awt.headless=false；剔除 LIVE/ORACLE/PG 前缀/REDIS 与 JVM 注入环境项。命令清除 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS。单 Gradle 进程顺序运行，各轮独立 profile，live 正常跳过。根路径记录 run-root.txt；只复制自己的验收日志，不复制 profile 内容/历史。下一步的定向基线、新镜像与合成 GUI 已完成。

## P1a 检查点：实际字段链路

本轮实际运行既有 MetadataSearchShellRoutingTest，覆盖 PG 嵌套/直接入口、Oracle 直接入口、TABLE/VIEW × SELECT/DATA/DDL、取消、切换、ABA、删除连接、根配置变化、关闭与物理准入。合成 FX/fire 与原生输入分开，不互相替代。

外部 SolShellDesktopProbe.java 启动时注入 mock provider，实际使用 AppShell；所有写入/执行禁止并计数。SeedQuery 仅在打开对话框时设置 customer 查询词，日志明确 PROGRAMMATIC_QUERY_PREFILL，不调用 submit/fire/选择。后续查找提交、列表选中、查看数据/DDL 通过 Computer Use；不以表菜单代替字段结果动作。

未预填运行 type_text 经一次重新聚焦仍无效，停止重复；该运行正常关闭连接 1/1，其余搜索/页/DDL/写/执行均 0（unseeded-shell-runtime.log）。首个预填夹具把 ColumnInfo 返回到需要 EditableColumn 的接口，引起 cast 错误（seeded-fixture-failure-runtime.log）；只修外部夹具，不是产品修复。核对独占 javaw PID 后终止失败夹具，不能计正常退出。

修正后运行和归档重跑均命中 demo.orders.customer_id，从结果打开只读数据页：写控件禁用，两行合成 101/202；第二次结果打开 DDL 预览。最终 searches=2/pages=1/ddls=1/writeAttempts=0/executionAttempts=0/mockOpens=mockCloses=3，SHUTDOWN_COMPLETED。采纳 native-recheck/03–10 + recheck-shell-runtime.log；第一次成功 corrected-shell-runtime.log 保留。

## P1b 检查点：确认、保存与关闭

新执行既有可控时序 FX 测试，断言目标请求绑定、配置变化后迟到批准不能取得写资源、生产取消/批准、取消只停止剩余行、已提交行不重放、交互关闭拒绝、强制关闭等待、迟到回调抑制。没有复现本轮需要修复的产品缺陷，没有为证据问题改变业务语义。

SolGridDesktopProbe 使用真实 DataGridPane/DataBrowseService/DataEditService/JdbcDataEditor，mock Connection/PreparedStatement 统计 opens/closes/execute/commit/rollback。启动时程序化设置两行草稿，不算原生编辑，不模拟 save/fire。顶端“释放 mock 写屏障”“配置收紧只读”“请求强制关闭”是夹具设施；最后一个调用实际 requestMandatoryClose，不能当普通应用功能或 OS 关闭证明。

原生 Save 显示“写入安全确认”，精确合成目标 synthetic.invalid:1 / synthetic / synthetic.items、生产、修改 2 行。Esc 取消保留两行。首次父窗口坐标取消仅激活父窗口、没有取消；恢复时 list_windows 选择实际确认窗口，激活、取屏后 Esc 生效。再次原生 Save 和明确批准后第一行进入阻塞 mock JDBC；原生标题栏 X 返回 INTERACTIVE_CLOSE REJECTED。原生取消剩余保存并用夹具按钮释放，得到提交 1/2、第二行未执行且保留。原生再次 Save 的范围为修改 1 行，明确批准后第二行提交，正常 X 返回 APPROVED；最终 writes=2/commits=2/rollbacks=0/opens=closes=3。采纳 native-recheck/17–25 + recheck-grid-runtime.log，第一次 production-grid-runtime.log 保留。

独立强制关闭用例明确批准后进入 mock JDBC；原生夹具入口请求强制关闭，日志 MANDATORY_REQUEST done=false、opens2/closes0/writes1/commits0；mock 收到中断仍持有资源。及时原生释放后 guard APPROVED，最终 opens=closes=2、commits=0、rollbacks=1，SHUTDOWN_COMPLETED。采纳 native-recheck/30–33 + recheck-mandatory-fast-runtime.log，第一次成功 mandatory-runtime.log 保留。

归档重跑另一次检查截图超过产品既有 15 秒 settled 期限，guard 返回 FAILED_PARTIAL；旧夹具无条件 finish 后自行收尾（recheck-mandatory-runtime.log），该收尾不能算产品批准关闭。修正外部夹具为仅在无异常且 APPROVED 时 finish，新 profile 再跑并及时释放取得以上成功。不改产品超时/fatal-partial 语义；超时后完整 shell 的呈现/恢复未验。

## P1c 检查点：键盘与窗口

DDL 原生点击查找、输入 customer、Enter 得到 1/1 与字段高亮；Tab 到 sql-find-match-case，Shift+Tab 回 sql-find-query，Esc 收起且焦点回 ddl-text。native-recheck/11–15 与 JavaFX 真实键/焦点日志共同支持。部分 accessibility focused_element 滞留旧树查询，不能单独据此判焦点。indexed action 使用最新绑定窗口的文本+截图；无截图状态导致过 geometry/index 错误，已记录。

AppShell 捕获窗口 1103×751；grid Scene 初始化 900×680，含边框捕获 903×711，主要动作可见。仅证明当前进程尺寸和可见性。outputScale=1.5 是进程报告，不是 OS 缩放/多屏验收。旧 native/22 名字称 small-window，但拖动无效果，不计缩小通过。

## P2 检查点：工程结果

source-freeze.json 记录 774 个跟踪 src/test/build.gradle/settings.gradle/buildSrc 文件的 SHA-256/字节数。冻结时 sourceUnchanged=true，最终打包审计逐文件复核均未改。所有夹具在 docs 下，外部编译到临时 classes，不进入 src/test/镜像。最后外部夹具修正经新镜像 javac 与实际 native guard 重跑验证；产品没改，不为文档/外部夹具再次重复全套产品测试。

| 实际执行 | 结果 | 日志 / 全部原 XML |
|---|---|---|
| 初始定向 6 suites | 75/75，0 skipped | directed.log / directed/ |
| 首轮全量 310 suites | 3915 passed、1 failed、3 live skipped；总 3919 | full.log / full-first/ |
| 独立 SchemaDiffServiceTest 重查 | 3/3 | schema-recheck.log / schema-recheck/ |
| 第二次全量新 profile | 3916 passed、3 skipped | full-recheck.log / full-recheck/ |
| 初次强制 buildSrc clean test | 8/8 | buildsrc.log / buildsrc/ |
| 冻结后最终定向 | 75/75；8 tasks 全执行 | final-directed.log / final-directed/ |
| 冻结后最终全量 | 3916 passed、3 live skipped；8 tasks 全执行 | final-full.log / final-full/ |
| 冻结后 buildSrc clean test | 8/8；4 tasks 全执行 | final-buildsrc.log / final-buildsrc/ |
| 冻结后 jpackageImage | SUCCESS；14 tasks 全执行 | final-image.log |
| 新镜像审计/零连接 driver discovery | 183 文件；无 test/夹具/profile/验证选项；774 冻结文件不变；Oracle/Postgres 驱动发现成功、connectCalls=0 | image-audit.json/log、image-module-list.log、image-file-list.log、image-DataCube.cfg、driver-discovery.log |

首轮失败 SchemaDiffServiceTest.providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview，CompletionException → IllegalStateException “Schema snapshot failed”。完整可取得栈保留在首轮 XML，没有更深 cause。只做有界源检查和独立重查；根因未定、未修复，后续通过不能抹掉首轮红灯。

补充原始 stderr：ResultColumnFindDialogTest 与 ResultRowLocateDialogTest 在测试自身 Collections.swap 重排列时产生 JavaFX Duplicate TableColumns 异步异常；各 suite 仍为 JUnit 0 failures/errors。原 XML 原样保留，不宣称没有异步异常，也没有在本轮修这些既有测试。

3 live skip 为 RedisLiveIntegrationTest.standaloneRedisSupportsFiveTypesScanTtlAndLifecycle、SchemaDiffLiveIntegrationTest.oracleSafeDeploymentConvergesInDisposableSchemas 与 postgresqlSafeDeploymentConvergesInDisposableSchemas。PG/Redis/live SchemaDiff 未授权，本轮未做真 Oracle；产品未改，无需开发线程真库复验。协调线程既有专用表未访问。

命令均离线、单进程、--no-daemon --console=plain --rerun-tasks。产品 test 另加 -I isolation.init.gradle 和 -Ddatacube.acceptance.root=<run-root>/<轮次>；定向 --tests 的 6 suites 为 com.datacube.fx.MetadataSearchShellRoutingTest、WriteSafetyIntegrationTest、DataGridSaveFlowTest、DataGridPaneLifecycleTest、ObjectEditorPaneLifecycleTest 与 com.datacube.service.RelationalWriteSafetyTest（前五均 fx 包）。buildSrc 使用 gradlew.bat -p buildSrc clean test、绝对 init 路径；镜像使用 gradlew.bat jpackageImage，无验证 profile/JVM 参数。逐轮在下一次覆盖 XML 前执行 Archive-TestResults.ps1，Copy-Item 保留原字节并统计。

Launch-NativeProbe.ps1 -Kind shell -SeedQuery / -Kind grid -BlockWrite 外部编译并启动新 profile；启动/预填本身不是原生动作。Audit-Image.ps1 实际 jimage list、镜像文件扫描、cfg检查、artifact哈希、冻结文件复核及 driverFor 零连接发现。驱动探针不调用 connect/open、不载入用户配置，只用 example.invalid URLs；不等于真库或正式启动器通过。

## 归档错误、原始字节与敏感信息

**native/02–36 全部因辅助函数错误引用早期同一快照而无效，不能作为其文件名所指步骤的证据。** 原样保留审计失败，native/01 仅证明初始窗口。修为 saveSnapshot(name, returnedSnapshot)，新 profile 重跑，最终只采纳 native-recheck/。新档案逐次保存当时窗口/a11y/截图元数据和图片；跨 profile 相同确认内容可产生相同 PNG，须按窗口和步骤核对。

tool-failures.txt 保留 typing 无效、geometry/index缓存、错误对话框取屏不匹配、超界点、隐藏启动无窗口、API误用、旧引用归档、拖动无效、确认父窗口无效、超时与镜像筛选误报。native-compile.log 保留误用 app/mods，native-compile-system.log 为改 --system 成功；grid-compile.log/grid-compile-relocated.log 为 split-package/package-private 失败，grid-compile-reflection.log 成功；archive-shell-compile-first.log 为单源字符串被 splat 成字符，修脚本外层数组后 shell 实际成功。首版镜像 case-insensitive Test 子串误报 SqlFavoriteStore/ConnectionTestController 等，image-audit-first-false-positive.* 保留；最终规则使用精确跟踪 test 类路径及 case-sensitive 后缀。没有产品红绿修复声明。

采纳截图仅含独占合成 GUI，不匹配错误对话框和无关应用未归档。文本 private-key/password/bearer/JDBC 扫描只命中本轮合成 URL；保留本机用户名/路径和 XML 主机名，未复制真实凭据/profile/业务数据，无需替换合成字段。raw-byte-manifest.json 记录原 SHA-256/长度；目录局部 .gitattributes 禁用转换；staged-byte-audit.json 实际读取每个 Git blob 原字节核对 SHA-256/长度，排除它自身防自引用，其自身另用无过滤 hash-object 对照暂存 blob。

## 精确未验与 P3

- 字段对话框原生 query typing、完整键盘及结果方向键导航；预填不算通过。原生 Oracle/VIEW/SELECT 未新跑，仅有本轮 FX/mock 矩阵。
- 原生配置收紧/删除迟到批准、只读切换未跑；本轮 FX 实际资源/写计数已通过，不提升为原生。
- 超时后完整 shell 呈现/恢复、完整应用在途退出、OS/session强退未验；mandatory 成功仅是夹具入口调用真实 guard。
- 缩小窗口、OS 缩放变化、多屏、正式 DataCube.exe 启动、真实安装/升级/签名/CI/用户任务未验；进程尺寸不等于这些通过。
- PG/Redis/SchemaDiff live、本轮 Oracle 服务未执行；M8 未完成。
- 首轮 SchemaDiffServiceTest 间歇失败根因未定，风险保留。

下一步由协调线程 P3 审核差异、原始字节、证据等级和未验项，决定返工或本地集成。开发线程不自行合并 main/扩大权限。
