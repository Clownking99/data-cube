# G9 worker：P0、P1a、P1b 与 P1c 验证交付

本次 C 日期：2026-10-09（Asia/Shanghai）；旧阶段归档日期与文件名仍为2026-10-08。当前状态：**P1a/G9a 与 P1b/G9b 已获协调独立验收；P1c/G9c 最终定向1104/1104，0 failure/error/skipped，同字节冻结后停写，待协调独立审核。P2/P3 未验，不宣称整体 G9 交付。** 第1–10节为P0，第11节为A历史，第12–13节为B当时设计/实测历史，第14节为C实施前设计，第15节记录本次C结果。

## 1. 本轮身份与授权边界

- 正式线程 ID：`01a11b86-2026-7ed3-86c5-1ec232640653`，本轮从指定的 `CODEX_THREAD_ID` 变量读取；协调线程为任务下发中给出的 `01a0ceef-5ee3-7753-97a6-bdf68a148aae`。
- worktree：`C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾`。初始 detached HEAD；现分支 `codex/g9-table-export-reliability-20261008`。
- 实测 HEAD：`b81923f29e0a6b6603710a589504aab490995491`。与计划提交一致；相对产品基线 `fccc58ba95bb0deec19463cd75f2bb32a33dbc8a` 的 Git diff 仅为既有计划、交接、路线图和归档文档/证据。P0 未改产品、测试、构建文件。
- 初始范围内 `git status --short -- . ':(exclude).testagent' ':(exclude).testagent/**'` 无输出。所有状态检查沿用该排除形式。
- 已完整阅读 [G9 计划](../plans/2026-10-08-g9-table-export-reliability.md)，阅读[交接](../../handoffs/2026-09-23-product-maturity-goal-handoff.md)与[路线图](../plans/2026-09-23-product-maturity-roadmap.md)顶部当前 G9 条目及引用源码。根/目标子目录未找到额外 AGENTS；祖先 `C:/Users/hetia/.codex/AGENTS.md` 与本次输入约定一致。
- 现有 JDK 实测为 Temurin `25.0.1+8-LTS`，路径 `D:/jvms_v2.1.6_amd64/store/jdk-25.0.1+8`；wrapper 配置 Gradle `9.2.0`，工程使用 JDK25、JUnit5、现有本地 PG `42.7.10` / Oracle `23.26.1.0.0` jar。P0 没有运行 Gradle、测试、GUI、真库、真实 pg_dump 或打包，也没有安装工具。
- 未触碰主工作区；未读取既有配置/profile/凭据/SQL 历史/业务文件；未访问受保护目录；未暂存、提交、push/fetch/tag/PR/发布或创建其他线程/代理。只读查阅了官方 JDBC/JDK/格式文档。

本轮原件目录：[evidence/g9-table-export-20261008-worker](evidence/g9-table-export-20261008-worker/)。P0 原件为静态身份/源码摘要和命令回执，不拿旧 XML 或上轮测试结果充当新证据。

## 2. 已核对的调用链与复用边界

| 风险 | 当前真实调用链（HEAD 的行号） | 设计决定 |
| --- | --- | --- |
| 原目标损坏/删除 | `AppShell:847 → ExportDialog.show:98/101 → runExport:104 → TableExporter.export:28`；SQL/XLSX writer 直接打开最终文件；`ExportDialog:124` 在任何异常后 `out.delete()`；PG `-f` 同样指向最终文件 | UI 捕获并确认 `SafeResultFilePublisher.Target`，所有格式只写 publisher 创建的同目录临时文件；删除最终目标的 catch 移除 |
| pg_dump 无真正总期限 | `PgDumpRunner:63 start → :73 readLine/无界 StringBuilder → :78 waitFor(10min)`；stdout 未排空；取消只有 scope 中断，没有完整进程 finally | 启动前建立单调期限，独立字节排空与进程监督；任何分支都完成自有进程、流、工作线程的结算后才可发布 |
| 分页读取不一致/共享事务 | `TableExporter:37/43 conns.acquire → :55 columnsOf/page(0,1) → :61 pagingFeed → :65 page(offset,500,null,null)`；Pg/Oracle accessor 生成多次无排序分页 SELECT | 捕获配置+provider+版本，`openDedicated` 创建独占连接；只执行一次数据 SELECT，同一 ResultSet 提供列与全部行 |

复用点：

1. `SafeResultFilePublisher:64/96` 已有目标快照、同目录独占临时文件、共享 BUSY、原子 move、清理归属和错误阶段。三格式共用它，避免另起发布器。查询结果的 `SqlResultExportCoordinator:97` 已在 capture 后明确覆盖确认，整表沿用此顺序。
2. `ResultExportOperation:21` 已使取消与发布互斥，但同步锁包围实际 move；直接从 FX 调 `cancel()` 可能等待磁盘。拟保留公共 API，改为非阻塞 CAS 发布门禁，见第 4 节；查询导出回归必须保留。
3. `ConnectionManager:197/293` 已支持配置快照和独占连接；现有 `configVersions`、监听通知可捕捉 ABA。`provider(ConnConfig)` 为 service 包内接口，不能在 export 层再按 connId 查询 provider。由新的 service 目标对象封装，同一快照绑定 factory 与 provider。
4. `SqlExecutionControl` 已有 request-only 取消、Activation 身份、受控超时和精确 release；直接复用。`JdbcEditorSession` 的“物理取消结算前不释放所有权”是参考，不复用其 UI 全量结果执行路径，也不改其他 SQL 标签的会话。
5. `MigrationTableExporter` 已演示单 SELECT/独占读取事务，但带迁移专属类型映射、证据/marker 和独立发布契约，不把整表导出接入迁移机制。`MigrationCancellation` 会吞关闭异常，不能用它给 G9 证明严格释放完成。
6. `QueryResult:255/285` 与 `ImmutableResultValue:237` 是受保护 UI 结果/预览转换；默认 10,000 行、长文本和 LOB/数组预览不属于完整导出。G9 行流必须绕过它们。
7. **额外必要边界**：`OracleDdlGenerator:109–124` 把 SQLException 转为 fallback 注释，甚至附原异常消息。整表“仅结构/两者”需要严格的受控 table DDL 入口；失败不能伪装成成功注释。其他元数据展示入口维持原兼容行为。

## 3. 最小拟议文件范围

以下是 P1 设计范围，不是已完成改动。按 G9a → G9b → G9c 逐步实施并记录每步 RED/GREEN；如范围增加，先在本报告记录具体必要性并供协调会话审核。

| 文件（均相对仓库根） | 最小职责 |
| --- | --- |
| `src/com/datacube/fx/ExportDialog.java` | 捕获/确认文件与连接目标；移除最终目标删除；异步取消、等待物理结束的进度反馈；queued/rejected/owner-close/迟到回调结算；保留现有内容/格式选择 |
| `src/com/datacube/export/TableExporter.java` | 不可变请求；统一 publisher 编排；仅把临时路径交 writer/pg_dump；JDBC/进程/文件关闭全部成功后发布 |
| `src/com/datacube/export/ResultExportOperation.java` | 小幅改为 CAS 发布门禁，避免 FX 等待文件 I/O；现有查询导出 API 与取消胜负规则不削弱 |
| `src/com/datacube/export/SafeResultFilePublisher.java` | 复用发布器；补原始路径祖先 NOFOLLOW 检查、别名 BUSY 身份和临时文件身份/清理检查；如需前置有效性检查，用小接口而非第二套发布代码 |
| 新 `src/com/datacube/export/TableExportOperation.java` | 一次任务生命周期、deadline、发布 token、SqlExecutionControl、资源注册与物理结算；toString/异常仅固定阶段 |
| `src/com/datacube/export/PgDumpRunner.java` | 结构化 argv、精确 quoted pattern、总期限、有界排空、进程/后代清理；注入 starter/clock/预算以作本地 helper 验证 |
| `src/com/datacube/service/ConnectionManager.java` | 同步捕获 cfg/provider/version 与监听注册；复用现有专用 opener；不改变 acquire/cache/写门禁 |
| 新 `src/com/datacube/service/TableExportTarget.java` | 封装同一不可变配置、provider、版本与独占 opener；只读目标有效性，无 readOnly 写入门禁；订阅失效/解绑；redacted toString |
| 新 `src/com/datacube/export/JdbcTableExportReader.java` | PG/Oracle 一次 forward-only/read-only cursor；元数据、完整逐行取值和预算；借用任务的 SqlExecutionControl，session owning conn |
| `src/com/datacube/spi/DdlGenerator.java` | 加严格、控制句柄+期限的 table-only 入口；未知 provider 默认明确不支持，不静默退回不可控旧入口 |
| `src/com/datacube/provider/postgres/PgDdlGenerator.java` | 新严格入口控制 columns/PK 查询，空/失败结构明确失败；复用现有拼装与格式，不宣称完整 Schema 快照 |
| `src/com/datacube/provider/oracle/OracleDdlGenerator.java` | 新严格入口控制 DBMS_METADATA 查询、有限完整 DDL 文本读取；不吞异常或泄露消息；不改 view/routine 等旧展示入口 |

测试拟新增/扩充：`TableExporterReliabilityTest`、`TableExportOperationTest`、`PgDumpRunnerTest` 与本地 Java helper、`JdbcTableExportReaderTest`、service 同包 `TableExportTargetTest`、真实链路合成 FX 的 `ExportDialogLifecycleTest`；扩充 `SafeResultFilePublisherTest`、`ResultExportSessionTest`/operation tests 和 provider table DDL 用例。测试确切数量以实际 XML 为准。

无需改 `DataAccessor`、Pg/Oracle UI 分页、`SqlRunner`、Redis、全局 task runner、查询结果取值策略或格式美化。优先用 RowFeed 周围的整表专用校验保持 `SqlScriptExporter` / `XlsxWriter` 的现有合法内容与样式行为；若 writer 关闭注入无法由现有接口验证，再增加最小 package-private seam。

## 4. 目标绑定、状态机与取消/发布顺序

### 文件与连接目标

1. 在打开格式选择时捕获 `TableRef(schema,name)` 与连接 export target；格式能力从同一 cfg 的 DbType 判断。目标不存在、删除/重建、ABA 或格式选择后变更均拒绝，不能在 worker 重新取当前 connId 而悄悄导出另一个数据库。
2. 文件选择器返回后 capture 原始路径，确认普通文件/父目录与身份；capture 后对旧目标明确覆盖确认，不依赖原生 chooser 的隐含确认。确认前没有建连、进程启动或临时写入。prepare 和 publish 前再次核对。
3. 现有文件 stamp 包含 fileKey/size/mtime/ctime；增补父目录身份及原始路径 NOFOLLOW 链，不能先解析路径隐藏 symlink/junction。正常 8.3、大小写、`.` 别名规范到同一目标；并发 alias 至少通过规范路径及已存在 fileKey/同文件比较拒绝。不通过扫描目录搜寻其他别名。
4. 源 cfg/provider/version 一起 capture，订阅与版本校验在 manager 内同步安装，堵住 capture/subscribe 窗口。监听只发布失效/停止意图，不在 register 的锁中做 JDBC、进程或文件 I/O。readOnly/production 来源允许读，不接入写入确认。
5. 配置改变的胜负点与发布门禁相同：失效先取得取消权则零发布；门禁已经进入 PUBLISHING 时，文件提交按原请求快照完成或失败，UI 不把请求说成取消成功。publish 前最后校验不持 manager 锁执行 move。

### 任务与提交门禁

生命周期：`PREPARED → QUEUED → RUNNING → READY → PUBLISHING → SUCCEEDED`。未提交时任一阶段可进入 `STOP_REQUESTED(reason) → CLEANING → CANCELLED/TIMED_OUT/FAILED`。物理资源尚未停止时保留 pending；资源释放或临时清理失败结算为 `CLEANUP_FAILED` 并保留可审计所有权，不能报“已取消”。终态 future 只结算一次。

复用的 ResultExportOperation 门禁拟为 `ACTIVE → CANCELLED` 或 `ACTIVE → PUBLISHING → PUBLISHED/FAILED` 的单次 CAS；`publish(Action)` 只负责领取提交权与实际 move，不持同步锁阻塞 `cancel()`。行动失败后不恢复 ACTIVE，同一请求不能重复提交。已有 `check/cancel/published` 调用保持语义；worker 不在已经 PUBLISHING 后再调用 ACTIVE 检查。

- FX 取消立即发布意图，然后在受管后台调物理 cancel/close/进程终止。FX 不调用 JDBC、await、join、Files.move 或等待门禁锁。
- 取消先赢：feed/严格 DDL/启动后的资源注册检查 token；准备和关闭期间也再次检查；零 move。读取迟到返回、exit0 迟到或旧 UI 回调不能改变已定胜负。
- 发布先赢：取消返回未接受，不打断提交、不清理刚发布文件；继续等待真实 move 结果。成功仅在 move 成功且此前所有 I/O/连接/进程已结算后产生。
- 正常结束先关闭 writer/cursor/statement、rollback/close 独占连接、join 取消工作和进程排空，再 READY；关闭错误阻止发布。不存在“发布后连接关闭失败却报未发布”的模糊状态。
- queued 未启动即取消/runner 拒绝：任务 handle 在 begin CAS 前结算，无资源需要释放；不依赖 Callable 的 finally，因为 FutureTask 可能根本不运行它。worker begin 与 cancel 有受控双序测试。
- progress 的取消按钮/X 先显示“正在取消，等待资源停止”；实际结束前不宣称完成。owner 被强制隐藏时只抑制 UI，不结束物理所有权。completion future 与 scope 回调存活分开，scope.close/Future.cancel 不是资源停止证明。
- retry 必须新请求、新操作 token、新文件 capture/确认、新连接 snapshot；旧 finalizer 不得清空/取消后继资源。

## 5. 进程/JDBC/文件所有权

| 资源 | 获得、释放与失败契约 |
| --- | --- |
| 临时文件 | publisher 独占创建，同目录记录 fileKey；writer/pg_dump 仅借用其路径。不得删最终目标或邻居。失败只删除身份仍匹配的本次临时文件；路径被他人替换则安全失败并报告残留。成功 move 后不再把目标当 temp 清理 |
| 发布 BUSY/监听 | 从工作开始保留到 writer 与物理资源停止、临时清理/发布结束；finally 解绑配置监听与释放 BUSY。清理未结束时不能放行同一请求的重试 |
| 进程/后代 | task 拥有由 starter 返回的 Process 与捕获的 descendant handles；不按名字、全系统 PID 或命令行搜索/杀进程。注册与停止竞态中，late Process 返回马上交给 cleanup，不能遗留 |
| stdin/stdout/stderr | 启动后关闭 stdin；stdout/stderr 合并为一个管道并独立 drain，或各独立 drain，二选一以测试覆盖为准；读固定 byte[]，不使用 readLine。EOF/读取失败/流关闭/线程终止均有回执 |
| watchdog/drainer/cancel worker | 每任务拥有明确句柄/完成 future；deadline 事件取消发布资格；后台等待和回收，结算包含 worker/drainer 完成而非 Future.isCancelled。清理阶段使用单独有限预算，不能因主期限已过而跳过回收 |
| JDBC opener/Connection | manager 从捕获 cfg/provider 建立独占、零 live cache 的连接；连接晚返回时先检查 stop/invalid 再注册或立即关闭。取消无法强行证明尚未返回的驱动 opener 已停止，保持 pending/报告未释放，不报取消完成 |
| Statement/ResultSet | 单数据 cursor；DDL/setup sequential statements 使用同一任务 SqlExecutionControl 的 Activation，finally 精确 release。result → statement → rollback → connection 关闭由 worker 结算；cancel 与 fallback 只访问本任务捕获的资源 |
| 单元格流/LOB | 完整读取预算内的文本/二进制；Reader/InputStream/free 有明确 finally；不保存驱动活对象或 UI preview。不能可靠表示的类型拒绝，关闭失败阻止发布 |

JDBC 取消优先 `requestCancellation()` + 后台 `control.cancel()`；驱动不支持/报错/执行仍在途时，只对独占 Connection 采用 close/abort 回退。回退与最终关闭由同一 ownership handle 协调，不重复关闭或误伤别的会话。后台物理调用挂起时 UI 保留 pending，预算耗尽仅报清理未完成与资源状态，不虚报停止。

## 6. G9b：pg_dump 具体方案

- 默认主期限保持 10 分钟；从 job 开始/ProcessBuilder.start 之前的单调时钟建立绝对 deadline。包含启动、排空、等待、退出判定与发布资格检查，不因有输出重置。测试注入小预算/clock，边界以可控事件推进。
- watchdog 与 start 并行保持停止意图；start 尚未返回时期限也生效，late Process 必须立即回收。无法中断的平台 start 不会因此被伪报物理结束。
- 使用结构化 argv，保留 content 对 `--schema-only/--data-only` 的映射；显式 `--format=plain`、`--no-password`，避免无人值守 prompt。`-f` 仅为本次临时路径。
- `-t` 是 pattern；对 schema 与 table 每段双引号引用、双引号倍增，保护大小写、空格、点、`*`/`?`。无 schema 的表无法证明唯一身份时明确拒绝，不按 search_path 匹配多 schema。argv 测试不运行 pg_dump；官方说明见 [pg_dump table pattern](https://www.postgresql.org/docs/current/app-pgdump.html)。
- 子进程只继承运行所需的受控环境；清除外部 PG 连接/服务/启动参数，密码只通过临时环境提供，测试全用合成值。请求/toString/argv 回执与错误不输出密码、cfg、原 stderr/stdout 或 JDBC cause 文本。
- drain 用固定 8KiB buffer，至多保留 16KiB ring/tail 或只存计数；持续/无换行输出内存不随累计量增长。输出读取失败也是失败，不能以 exit0 覆盖。尾部即使已限长也可能包含秘密，UI/日志只输出固定阶段、退出码与有界计数。
- cancel/timeout/nonzero/read failure/interruption 进入同一个 cleanup：记录停止原因；捕获并保留自有后代 handles；先正常终止、短 grace 后 force；确认 root 与已捕获后代退出；关闭所有流、join drainer、结束 watchdog。成功路径也不能遗留存活后代或仍开 pipe。
- 建议 cleanup grace 1 秒、总回收观察预算 5 秒，可注入测试预算。force 后仍存活/线程未退出不能报 CANCELLED 或 SUCCEEDED，产生 cleanup failure/pending 证据；不继续发布、不能通过无限 join 卡住 FX。
- **平台风险**：JDK 的 descendants 是时间点快照，父进程结束后通常无后代可查询；纯 ProcessHandle 不能保证发现快速派生/脱离后代。这是实际边界，见 [JDK25 ProcessHandle](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessHandle.html)。P1 必须对受控子孙、父退出后 pipe 持有者保留/结算 handles；不能用这些测试声称任意脱离子孙已保证回收。若协调会话要求该强保证，需要单独审查 OS 进程容器实现（本轮不自行扩张到 native 工具/全局杀进程）。

## 7. G9c：一致完整的流式读取

统一数据 SQL：`SELECT * FROM <dialect.quoteIdentifier(schema)>.<dialect.quoteIdentifier(name)>`，一个 statement、一个 cursor，无 LIMIT/OFFSET/COUNT/主键排序/额外探测行查询。schema 缺失不能证明目标身份时拒绝。列名/类型来自这个 ResultSet 的 metadata，读取首行前传给 writer；空表仍保留表头，SQL 不生成 INSERT。

| provider | 独占连接与 cursor 策略 | 可证明范围 |
| --- | --- | --- |
| PostgreSQL | readOnly、autocommit=false、READ_COMMITTED（单数据 SELECT 即 statement snapshot）；明确 TYPE_FORWARD_ONLY/CONCUR_READ_ONLY、正 fetch size（初定 16）、单 SELECT，成功/失败 rollback 后 close | 满足 [pgJDBC cursor 条件](https://jdbc.postgresql.org/documentation/query/)；不要仅调用 setFetchSize 却保留 autocommit=true。不会声称 DDL 与数据具有同一个整体 Schema 快照 |
| Oracle | 专用 readOnly 连接、autocommit=false；forward-only/read-only cursor、执行前 fetch size（初定 16），一次 SELECT；rollback/close | [Oracle 单语句读取一致性](https://docs.oracle.com/en/database/oracle/oracle-database/26/cncpt/data-concurrency-and-consistency.html)与 [JDBC fetch](https://docs.oracle.com/en/database/oracle/oracle-database/26/jjdbc/resultset.html)；长期读的 undo/驱动/网络失败明确失败，不截短导出 |

不启用 scrollable/updatable/refetch，不使用 UI SqlRunner 的 maxRows/display values。数据无主键不影响单 cursor 一致性；不对行顺序作未授权的保证。mock 的“读取期间改源集合”只验证 cursor 一次捕获、一次消费和无重查询，不能证明真库 MVCC。

每一行由完整取值器立即交 RowFeed sink，sink 完成后再 `rs.next()`；不累计 List<List<...>>。SQL 原 BATCH=100 只是输出语句计数，不能把它实现为全量行缓冲。单元格/row/getter/serialization 前后检查取消/有效性；release/control.cancel 时序用 latch/barrier 验证。

拟议边界（P1 固化为常量并提供刚好边界/越界断言）：SQL 单文本/二进制值 1MiB 级预算、单行聚合 4MiB 级预算，DDL 完整文本同类有限预算；超出立即失败，不能读前缀发布。XLSX 单 sheet 至多 1,048,575 数据行（另有表头）、16,384 列、文本 32,767 字符；依据 [Excel 限制](https://support.microsoft.com/en-us/excel/excel-specifications-and-limits)，在整表适配层检查，不全局改变 query writer。不同 Unicode 长度计数采用保守且明确的 UTF16 上限，不能切开代理项。

允许格式已有表示契约的 null、标量文本、布尔、有限数值和已有日期/时间路径；完整二进制仅 SQL 原 dialect 可表示时允许。LOB 文本/SQLXML 可完整有限读取后作为文本，但 binary XLSX、ARRAY/STRUCT/REF/BFILE、无法确定的 provider 对象、非有限数值、不可表示/不完整预览值明确失败。未知对象不得 toString 写入；不得将 `ResultValuePreview` / `ImmutableResultValue` 的显示文本当全量内容。XLSX 现有 numeric 模式不能可靠保留的值也拒绝，不自行新增格式语义或把值偷偷转成截断显示。精确允许集需以源码实际 writer 路径和新断言审核，不扩大 SQL 方言兼容声明。

**内存声明限度**：正 fetch size 控制 driver 批次行数，不是单行字节上限；尤其 PG 字符流 API 不自动意味着服务端逐字符传输。应用取值预算与拒绝大对象不会证明驱动接收任意巨大单行时的硬堆上限；这项真实驱动/超大行行为单列待验，不把 mock/低 fetch 值当硬内存证明。P1 先做有界逐行消费与完整/明确失败，不能用 maxFieldSize 静默截断掩盖问题。

严格 DDL 入口只为本次整表：PG columns/PK 和 Oracle table GET_DDL 每个 Statement activate/release 并限制剩余 timeout；空 DDL/缺失结构/读取失败不退回成功注释。未改其他 metadata 展示行为。连接 setup、DDL、数据、关闭任何阶段失败都止于临时文件。

## 8. 验收矩阵与证据

全部用新 UUID 根、合成 profile/文件/mock JDBC、本地 Java helper；无真库/真实 pg_dump。每例以实际 bytes/hash、事件顺序、资源计数与一次终态作断言，sleep 不作为同步条件。

| 组 | 必须实际覆盖 | 核心断言/原件 |
| --- | --- | --- |
| A1 三格式文件保护 | 旧目标/新目标/邻居 × 连接前、首行前、中途取值/编码、writer close、DDL、发布/取消失败；PG helper 部分写后失败 | 旧字节/邻居不变；新目标不出现；发布0；仅自有 temp 清理；真实 SQL/XLSX 输出回读 |
| A2 文件身份 | confirm 后替换、删重建、原目标消失/新目标出现、symlink/junction/正常8.3、大小写/点别名、硬链接与同目标并发、atomic mover拒绝、temp被替换、cleanup失败 | 安全拒绝/明确平台未验；无第二writer/非原子回退；不删替换temp；BUSY 与重试时序、残留身份有证据 |
| B1 期限/输出 | 无输出挂起、stdout/stderr大输出/单个巨大无换行流、start延迟/失败、非零退出、drain失败、deadline边界 exit0 | deadline从 start 前生效；计数增长而保存buffer有界；失败零发布；不暴露合成敏感token；helper源/日志/PID状态 |
| B2 取消/回收 | before start、late Process、执行中取消、正常退出与cancel双序、父退出子仍持pipe、已捕获子孙、force后不退出的fake、UI关闭后迟到 | root/已拥有后代/流/drainer/watchdog实际结束；不能回收时报pending/cleanup失败；不触碰独立neighbor helper；终态1 |
| C1 独占/绑定 | 存在共享连接与其他editor事务时导出；cfg变更/删除重建/ABA；provider/dbtype切换；open迟到/异常 | openDedicated snapshot1，acquire0；旧cfg/provider精确绑定；shared cancel/rollback/close0；监听解绑；无新目标暗换 |
| C2 cursor完整性 | 空表、501/1201/更大合成行数、无PK、引号/中文/点表名、读中可控源变化、SQL structure/data/both与XLSX | 一次数据SELECT/metadata；OFFSET/LIMIT/COUNT0；逐行sink-before-next；完整列/值/行数；setup/fetch调用顺序 |
| C3 边界/取消 | 非截断长文本、各预算边界/越界、无法表示类型、文本非法字符、首execute/next/getter/DDL挂起或失败、cancel失败回退、rs/stmt/rollback/conn关闭异常 | 超界/类型明确失败而非preview；activation精确release；资源结束计数/顺序；其他会话未影响；关闭失败不发布 |
| UI | 真实 ExportDialog 链的合成FX、覆盖拒绝、queued未开始、runner拒绝、正在取消反馈、取消先赢/发布先赢、关闭owner、重试/旧回调 | 不在FX阻塞；只一个实际终态；cancelled抑制回调不充当停止；新目标确认；固定redacted消息 |
| 回归 | SafeResultFilePublisher/ResultExportSession、QueryResultFileWriter/QueryXlsxExport、XML/XLSX fidelity、SqlResultExportCoordinator、FxTaskScope/Runner、ConnectionManagerDedicatedSession、SqlExecutionControl/JdbcEditorSession及DDL/provider/migration相关 | 本次新 XML、stdout/exit、任务执行身份；查询导出原保护/格式/其他事务保持 |

P1 每步先保留真实产品行为 RED，编译/夹具错误另记，后运行定向 GREEN；原链无法直接可靠注入时用必要最小 seam，不用反射存在测试冒充生命周期断言。P2 仅在收到授权且源码冻结后进行完整定向、clean全量、强制buildSrc:test、强制jpackageImage/镜像隔离/linked runtime探针。

将来 Gradle 命令用现有 JDK25、`--offline --no-daemon`、独占新 profile/temp、现有8.3方式；运行前从源码已知live入口排除/清除live gate与数据库环境，不输出继承环境值。保留实际command/exit/log/XML/sha与test task执行情况；UP-TO-DATE/旧XML不算本次通过。P0 没有创建或运行 profile/temp，因为没有应用进程/Gradle需要隔离。

## 9. 需协调技术审查的风险与 P0 结论

1. **文件契约的强度**：现有 publisher 在 verify 与 move 间有外部文件系统 TOCTOU；其 stamp 也不是内容hash/内核CAS。JDK 明确 `ATOMIC_MOVE` 不支持时失败，且存在目标的替换行为依实现，见 [Files.move](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html)。复用能解决本任务失败破坏旧文件及同应用并发，不能据此声称抵抗同用户恶意进程在最终系统调用前抢换路径。建议维持协作式本地文件系统范围、严格拒绝已观察变化/链接并记录剩余窗口；如计划要求绝对跨进程CAS，应在P1前调整实现/验收边界，不自行用备份搬走旧文件或非原子回退。
2. **链接识别**：原始路径symlink/junction/设备可NOFOLLOW拒绝，正常8.3/case规范并发身份可校验；portable BasicFileAttributes无法可靠提供所有平台hardlink计数。硬链接仅原子替换命名目录项可保护另一个链接字节，但不等于“全面识别并拒绝所有hardlink”。在新合成目录实际验证可用识别，无法证明的分支必须记为限制/拒绝，不把正常alias误判成链接。需要更强平台识别时交技术审核决定。
3. **进程所有权**：纯ProcessHandle只能证明返回Process和已捕获后代；任意脱离后代/极快派生需OS约束才有更强保证。增加原生进程容器不是P0已经获准的实现决策，需协调会话先审范围。
4. **JDBC能力与预算**：SqlExecutionControl不保证驱动cancel一定停止；factory现有open未提供本任务connect timeout。P1必须显示物理pending/失败、late opener关闭与专用资源状态，不把逻辑取消说成物理停止。真实驱动MVCC、fetch/网络/超大单行/undo、取消及DDL/数据共同快照仍未验。
5. **共享门禁兼容**：ResultExportOperation改CAS及publisher最小增强涉及既有query export；必须保留相关回归和取消/发布双序，不扩大UI/global transaction修改。

上述为明确技术审查项，不向维护者重复申请常规确认。P0 完成后停止实现，等待协调会话的 P1 下发/具体纠正。

## 10. 实际检查点

| 检查点 | 目标/改动 | 实际验证与失败 | 下一步 |
| --- | --- | --- | --- |
| P0.0 身份 | 核对worktree/HEAD/状态/JDK；建立codex分支 | HEAD/clean范围/JDK符合；首次 `git switch -c` 因共享 `.git` 超出sandbox写范围报permission denied，同批末尾命令exit0，不能冒充独立git退出码。已授权同一命令提权后exit0成功；不是产品/测试失败 | 静态核对现有机制 |
| P0.1 调用链 | 完整计划、当前G9条目与源码核对；无产品变动 | 三风险确认；追加Oracle DDL吞异常、UI预览、发布锁、path/process限制。曾尝试读取不存在 `src/com/datacube/fx/ResultExportDialog.java`，随后rg找到实际SqlResultExportCoordinator；这是检索诊断，未改文件、未运行测试 | 写本设计/矩阵与源码身份原件 |
| P0.2 交付 | 本报告与p0原件；不stage/commit | 33份实际源码/构建/既有测试文件的工作字节SHA与HEAD Git blob已归档；产品/测试/构建diff为空。最终状态与diff-check见原件；工程测试/真库/原生/打包均 **未运行/未验**，通过数不填历史值 | 等待协调P1；Gradle开发阶段执行权仍在本线程 |

协调追加已纳入：正式线程ID已确认；结构失败与成功分开、table-only严格DDL路径；8.3兼容与只读读门禁；OS/后代局限只提请技术审查，不自行加入Job Object、通用JDBC动态代理或扩大状态机范围。协调记录main新提交不合入，开发基线保持b81923f2。本报告是可审核设计，未决项由协调会话裁决，不进入P1。

收尾诊断：tracked `git diff --check` exit0；未跟踪新报告的 `git diff --no-index --check -- NUL ...` exit1且仅提示LF/CRLF规范化，不计作exit0。首次回执把PowerShell stderr ErrorRecord按对象序列化而出现depth=8警告；原JSON保留为 `p0-checks-first.json`，警告另存。最终回执输出转为字符串，另对报告逐行验证无行尾空白；不据此增加工程测试通过数。

## 11. P1a / G9a 实施与可审核交付

### 授权与实际范围

协调 C1 已批准 P0 并仅授权 P1a；C1.1 的 pg_dump dbname/conninfo 与空密码默认pgpass风险已登记为后续B阶段事项，本步没有提前实现。HEAD、分支和worktree保持第1节身份，未合入main的协调记录。原P0报告已原样保存在 `evidence/g9-table-export-20261008-worker/p0-worker-report-before-p1a.md`；P0冻结清单不改写为新的产品证据。

实际修改 **7份产品文件**（最后一份查询协调器局部接线由追加裁决授权）：

- `TableExporter`：新增不可变文件请求，capture目标身份；所有格式统一调用现有publisher，只传自有temp给真实SQL/XLSX writer或pg_dump。保留旧File入口作兼容适配，实际UI用带捕获Target与operation的入口。异常/取消/关闭失败不发布。
- `ExportDialog`：native chooser返回后capture，再显式覆盖确认，然后才显示进度/提交工作；移除删除最终目标的catch。局部生产`startExport` seam与实际show共同使用，任务completion独立于scope UI回调；queued取消与runner拒绝能结算，运行取消等待Callable返回，关闭owner抑制迟到UI。重复取消反馈保持“正在取消”，提交先赢则反馈等待发布。取消状态在本步表示**发布资格取消并且worker已经返回**，不冒充未实施B/C的物理进程/驱动回收证明。
- `ResultExportOperation`：ACTIVE→CANCELLED 或 ACTIVE→PUBLISHING→PUBLISHED/FAILED的CAS；取消不等待磁盘move，发布失败token不恢复ACTIVE。查询结果继续使用同一门禁与API。
- `SafeResultFilePublisher`：原始未normalize路径先检查各祖先，保留原路径用于复核；普通8.3、大小写、点别名兼容。父目录snapshot检查、同应用活动文件的isSameFile互斥；只有ATOMIC_MOVE，没有备份搬走/非原子降级。temp身份读失败报CLEANUP，仅明确NoSuchFileException视为缺失；替换temp/父目录时不删除对方文件。
- `SqlScriptExporter` / `XlsxWriter`：仅加package-private OutputStream入口，File入口及合法内容/样式原逻辑不变；写入器拥有并关闭流。这个最小seam让真实编排+真实序列化的流close故障可注入，不用假writer替代内容测试。
- `SqlResultExportCoordinator`：只接通共享publisher的发布后辅助文件清理警告与全部残留路径，不改变查询取值、格式、会话或状态归属规则。

新增5份行为测试类（90例）与2份合成fixture/helper，既有查询协调器测试新增1例；没有新增全局task runner/事务/provider/read-target体系或通用JDBC代理。

### Windows身份诊断与修正

第一次实现错误地假设BasicFileAttributes总有fileKey，本机JDK25在普通Windows文件/目录返回null，导致PREPARE拒绝。`p1a-004-targeted` 的156失败和当时源码原件完整保留；这是新实现缺陷，不是旧行为RED，也没有放宽测试绕过。

现实现使用portable `Files.isSameFile` 拒绝本应用已存在硬链接的并发；当temp没有fileKey时在**确认后、资源获取前**建立同目录独占UUID硬链接身份见证。写入/发布/清理检查temp与见证仍是同一文件，替换temp路径时保留替换项并报CLEANUP。见证是临时身份凭证，不复制文件字节，不依赖hardlink count，不借用业务文件。文件系统不能建立所需见证时安全失败，不降级直接写最终目标。

临时见证成功后清理；若已经原子发布，随后仅见证清理失败，记录固定阶段/path诊断并通过operation不可变残留列表传给整表及查询UI，显示“已发布，但辅助临时文件清理失败”与全部路径。整表使用WARNING Alert，查询状态使用警告标记；不伪报“未发布”或再动最终目标。实际断言绑定published=true、新目标字节、残留见证身份与两个真实协调路径的反馈。这是发布后的清理警告，不算完整资源回收通过。目录在null-key平台用原始路径检查/real parent与creation-time stamp复核，不能宣称native目录handle/CAS。

**文件系统兼容边界**：fileKey缺失时，本实现要求同目录支持独占硬链接创建。只证明本机受控Windows文件系统可用，不声称所有本地文件系统可用。创建失败发生在writer/连接/外部进程开始前；因没有可复核的temp身份见证，已建空temp保留并通过CLEANUP列出路径。碰撞的候选见证名未被本任务成功创建时，不删除该外部对象。无非原子降级。

单独合成属性probe在沙箱内建立UUID目录并观察key=null；硬链接创建被沙箱AccessDenied拒绝，原stdout与stderr/退出回执保留。该helper未加载DataCube或读profile；本次实际定向均用新UUID合成profile/temp、真实8.3别名与清空后的子进程环境，现有JDK25/Gradle缓存离线使用。

### 实际运行、失败与结果

每次原件均包含command/exit/stdout/stderr；只有日志证明`:test`实际执行时复制该次XML。每轮先cleanTest，未把旧XML或UP-TO-DATE测试算通过。buildSrc只有Gradle必需的compile/classes/jar依赖，没有执行buildSrc:test；没有full/image。

| run | exit | 实际测试 | 分类 |
| --- | --- | --- | --- |
| `p1a-001-red` | 1 | 未执行 | 默认沙箱不能写现有Gradle wrapper缓存锁；原件保留 |
| `p1a-002-red` | 1 | 未执行 | 隔离launcher把cleanTest的引号传成字面字符；修正为受控PowerShell调用argv，环境仍清空 |
| `p1a-003-red` | 1 | 4 tests / 4 failures / 0 error/skip | 真实旧行为：SQL/XLSX中途失败把9字节旧目标改为144/1464字节；阻塞发布下cancel等待；发布失败token仍可cancel |
| `p1a-004-targeted` | 1 | 357 tests / 156 failures / 0 error/skip | 新实现fileKey非空假设导致WindowsPREPARE回归；修正前源码冻结 |
| `p1a-005-targeted` | 0 | 357 passed / 0 failure/error/skip | Windows身份见证与实际writer/FX/query回归首次绿色；源码快照保留 |
| `p1a-006-targeted` | 0 | 360 passed / 0 failure/error/skip，20 suites | 原始路径/metadata/重复取消三修正后的绿色；随后追加裁决见下列新run |
| `p1a-007-warning-red` | 1 | 362 tests / 2 failures / 0 error/skip | 实际发布后留下见证，但整表/查询UI仍普通成功且没有残留警告的真实RED |
| `p1a-008-fatal-red` | 1 | 363 tests / 3 failures / 0 error/skip | 实际submit路径吞fatal Error的真实RED；另外两例警告已出现，但断言用8.3路径与UI的canonical路径作字符串比较，夹具断言错误，不能算新产品缺陷 |
| `p1a-009-targeted` | 0 | 373 passed / 0 failure/error/skip，20 suites | 见证/fatal追加裁决后的绿色；原冻结及全部证据保持不变 |
| `p1a-010-dialect-red` | 1 | 未执行 | 新夹具条件Class参数造成assertThrows泛型推断编译错误；原件/当时源码保留，不能计为产品RED |
| `p1a-011-dialect-red` | 1 | 375 tests / 2 failures / 0 error/skip | 真实TableExporter的dialect RuntimeException/Error两条路径均在writer接管前已open1；其余373例通过 |
| `p1a-012-targeted` | 0 | **375 passed / 0 failure/error/skip，20 suites** | 参数准备前移后的最新绿色；当前源码绑定本轮 |

最新command argv由 `Run-P1a.ps1 -Name p1a-012-targeted -Mode green` 生成：`cleanTest test --offline --no-daemon --console=plain -Dorg.gradle.java.home=<现有JDK25>`，过滤`com.datacube.export.*`、`com.datacube.fx.ExportDialog*Test`、`com.datacube.fx.SqlResult*Export*Test`、`FxTaskRunnerTest`和`FxTaskScopeTest`。完整参数/实际新profile与temp在该run的command.json；launcher只按名称继承OS运行必需环境，未枚举或保存凭据/live环境。

机器汇总：[p1a-run-summary-012.json](evidence/g9-table-export-20261008-worker/p1a-run-summary-012.json)，当前20套件身份：[p1a-012-suites.json](evidence/g9-table-export-20261008-worker/p1a-012-suites.json)。原009汇总/套件清单不覆盖。截至009没有编译失败，010的新增夹具编译失败另记；工具阻断、产品断言失败与夹具错误分别登记，不把工具exit1称为产品RED。

### 已有实际断言的契约与剩余边界

- 42例三格式×旧/新目标×连接前、首行前、中途、编码、close、publish、cancel故障矩阵；旧目标/邻居字节不变，新目标不出现，正常失败自有temp/见证清理。SQL/XLSX都使用真实writer，close在实际流上抛错。PG本步仅替换外部调用为合成部分输出/失败，不运行真实pg_dump或冒称PG流/进程生命周期已验。
- 三格式成功真实发布，SQL正文/标识符与XLSX ZIP/sheet实际回读；取消先赢0发布，取消在真实行源阻塞时future仍pending，release后旧字节不变。move在门禁内受控阻塞时FX cancel即时返回false，随后只成功一次；失败token不重用。
- capture/确认后目标替换拒绝且0建连；覆盖拒绝三格式0工作/连接/temp。实际ExportDialog.startExport→TableExporter链路的连接失败/PG合成失败保持旧目标，UI fixed message不含合成敏感文本；queued取消、runner拒绝、取消后重新capture/确认重试、owner关闭抑制迟到回调和单终态均已断言。
- 实际hardlink同文件并发拒绝；命名项原子替换后另一个链接仍保持旧字节，锁释放后可对alias独立导出。普通真实8.3/case/dot成功；symlink祖先、原始link/../路径拒绝，父目录替换/temp替换保留对方文件。新增link/..测试可用owned junction回退，本次全部相关用例执行、0skip；未操作用户ACL或目录。
- metadata读取AccessDenied的可控seam导致CLEANUP并保留temp；明确NoSuchFileException不删除该缺失temp，但fileKey缺失且见证仍存在又无法证明身份时保留见证并报告CLEANUP。重复取消保持取消反馈，直到被阻塞worker真的返回；未来物理JDBC/进程回收仍不由该断言代替。
- 查询结果publisher/session/coordinator、XML/XLSX保真与task runner相关定向全部在本次XML中运行并通过。

### 追加协调裁决、清理顺序与 fatal Error

在原360绿色后，协调补授权：1）发布后辅助见证清理警告及残留路径必须进入实际整表/查询反馈；2）硬链接不支持时在writer前失败且空temp残留可见；3）见证替换/移动/父目录变化不能删除已观察的外部对象，temp与见证清理错误不能相互覆盖。随后追加修复实际runner.submit捕获Error而绕过旧scope fatal处理的回归。上述范围已实施，未引入native框架、Job Object或全局runner变更。

清理先复核父目录与temp/见证同文件关系，成功发布则用发布后的目标项复核；保留见证到temp身份检查与清理结束。分别积累temp、witness的未解决路径，Failure.temporaryPath保持首个temp兼容，新增residualPaths保留所有项；诊断逐项输出，后一个异常不覆盖前一个路径。见证已移动则不搜索其他目录；见证名明确缺失只说明该路径无对象，不能证明未知移动位置已回收。temp名明确缺失且无fileKey时，仍存在但无法复核的见证保留并报告，不能仅凭timestamp删它。

实际新增断言：三格式受控见证创建失败0连接/页/writer/进程与旧目标/邻居不变，三格式UI收到实际temp残留路径；候选名碰撞对象保留；见证移动/替换后不发布、不删temp/替换对象；父目录换名后原目录owned文件和新目录替换文件都保留；temp删除失败后witness读取再失败，残留列表保持两项及原顺序。

fatal Error在publisher清理后继续保持原对象；同时清理失败以固定stage与残留列表作为suppressed Failure附上，不能覆盖fatal。ExportTask的实际submit路径在结算固定UI失败后，显式调用当前worker的UncaughtExceptionHandler，语义对应既有默认FxTaskRunner.reportFatal，报告一次。用真实TableExporter行源抛Error，经真实publisher/finally与runner.submit执行，实际handler校验旧文件与temp清理、对象身份和一次报告；不是只验证completion。另有publisher双清理失败时保留fatal对象/两残留的用例。

夹具路径纠正：p1a-008中UI已传canonical路径，而Files.list的测试root是8.3别名；两例失败只纠正比较到toRealPath并验证isSameFile，没有改产品放宽路径反馈。

原009源码/测试/launcher/报告冻结、汇总和SHA清单保持原样：`p1a-final-freeze-009/manifest.json`、`p1a-artifact-manifest.json`、`p1a-final-checks.json`。最新冻结见 `p1a-final-freeze-012/manifest.json`；新增原始run日志/XML及所有快照SHA清单见 `p1a-artifact-manifest-012.json`，最终HEAD/分支/范围状态与diff-check回执见 `p1a-final-checks-012.json`。p1a-007/008/010/011的修正前源码原件保留，原P0及其冻结清单不改写。

### 009独立复核后的参数准备顺序修正

协调仅追加授权修正SQL writer接管前的求值顺序，不启动B/C。旧表达式先调用output.open，再调用provider.dialect；后者抛RuntimeException/Error时，writer尚未执行且不持有该流。本次仅在TableExporter中将dialect与SQL/XLSX的RowFeed准备放到output.open之前，并保留准备后、开流前的operation.check。provider、DDL、列名准备原本已在open前；本轮没有发现其他外部getter在这些调用参数中于open后执行。writer内部现有try-with-resources保持，未改格式或引入额外连接/取消框架。

新增两条真实TableExporter故障注入回归：合成provider的dialect分别抛RuntimeException/Error，传入实际Files.newOutputStream的OutputFactory计数，publisher使用实际原子move/清理。修正前两个分支均open1，RED失败后测试只关闭该次夹具持有的真实流，避免泄漏影响随后测试；不把这种收尾当产品已关流证明。修正后两个分支open0、move0、published=false、旧目标与邻居字节不变、自有temp/identity无残留；fatal保持原Error对象。这是资源边界断言，不是镜像getter实现。

010在compileTestJava失败，没有:test/XML；011的cleanTest因010已清除输出而显示UP-TO-DATE，但:test实际执行并保存375例新XML。012的cleanTest、compileJava、:test实际执行，compileTestJava因已编译的同一夹具为UP-TO-DATE；375例XML都是012本次生成，0failure/error/skip，不能拿编译或旧测试UP-TO-DATE替代本轮执行。运行仍为新UUID合成profile/temp、现有8.3路径、离线环境；未运行full/buildSrc:test/image。

**仍未完成/未验**：旧PgDumpRunner的readLine/期限/输出/进程回收、conninfo/dbname身份与默认pgpass；共享acquire与多次OFFSET分页、配置/provider版本快照、严格DDL及完整值边界，均留待B/C授权，不称通过。实际Oracle DDL吞异常仍存在。verify→move和清理身份检查→unlink的任意外部恶意竞争窗口保留；不作native CAS/任意脱离后代保证。原生chooser/桌面、真库/网络/慢盘、full/buildSrc:test/image、P2/P3及main集成均未验。

P1a退出：当前代码/测试/报告与原件冻结后停写，交协调审查；不暂存、不提交、不合main。Gradle开发阶段唯一执行者仍是本线程；冻结后没有再运行Gradle或进入B/C。

## 12. P1b / G9b 资源与整窗退出接线设计（授权后）

协调已独立验收012并授权P1b；P1a只是阶段通过，不合main。本步实施PgDumpRunner及TableExporter/ExportDialog必要局部生命周期接线，新增AppShell-owned导出登记；G9c、P2未启动。009/012与所有既有原件只读保留，后续运行/冻结/清单另建。

pg_dump任务在start前建立默认10分钟单调截止；starter和监督器独立，迟到Process仍由同一任务接收并回收。两路输出用固定8KiB byte[]排空，只保存饱和字节计数，不存文本/尾部；stdin关闭、drainers、停止请求线程、监督器、root与已捕获descendants都有明确所有权。取消/期限/启动或排空失败先撤销发布资格，再后台正常终止→grace后force；5秒回收观察预算后仍未结束则通知固定pending反馈并继续保留ownership、publisher BUSY与temp，直到实际结算。worker不因Future.cancel或预算到期返回，让publisher finally误删还在写的文件。正常exit0同样检查截止、流与捕获后代，不能放过持pipe子进程。

argv使用唯一conninfo：host/port/dbname/user按libpq单引号与反斜线规则逐项引用，拒绝空字段、NUL及可改成多host/本地socket的host形式；dbname中的=、URI前缀仍是引用的字段值，不被任意conninfo扩展。schema/table各自双引号并倍增内嵌引号，保留-t的精确pattern语义；明确--format=plain、--no-password，-f只有publisher自有temp。依据[libpq连接参数规则](https://www.postgresql.org/docs/current/libpq-connect.html#LIBPQ-CONNSTRING)与[pg_dump参数说明](https://www.postgresql.org/docs/current/app-pgdump.html)。

每次创建自有隔离目录和空passfile/service文件，显式passfile/PGPASSFILE，HOME/USERPROFILE/APPDATA等指向隔离目录；子环境清空，仅按名称保留OS/PATH必需项和本次临时密码。默认SSL文件路径显式指向隔离目录中的不存在路径，禁止借用用户证书；不将原props/PGSERVICE/JAVA_TOOL_OPTIONS等环境带入。请求/异常/进度/收尾只含固定阶段、计数与自有路径，密码/输出/原异常不进入日志。准备的环境在start后删除密码引用，实际子进程按启动时复制的环境运行。平台和libpq兼容限度由真实合成argv/环境测试与报告说明，不执行真实pg_dump/数据库。

AppShell在shutdownAsync冻结导出准入；SQL mandatory guards仍决定是否允许破坏性退出。CANCELLED或破坏性阶段前异常恢复准入，不提前取消导出。仅进入shutdownRemaining后请求所有已登记导出停止并等待物理结算；此等待放在BestEffortCloseSequence之前，防止其继续tasks.close/connMgr.closeAll造成假结束。等待时窗口保持原有pending；任务queued取消/拒绝/发布先赢/正常或fatal返回均以一次物理completion注销。进度owner隐藏只抑制UI并发取消，不能代替AppShell登记结算。仍无法停止则继续pending并保留资源，不返回COMPLETED或隐藏主窗；无需改全局runner/关窗框架。未来G9c专用JDBC接入同一任务结算接口，本步不实现。

测试先用真实旧readLine/未排stdout/参数环境行为RED（必要最小starter/短期限seam，默认行为保持）；随后用可控本地Java helper和fake Process验证B1/B2、原件/邻居/PID/流与线程实际状态。实际AppShell→ExportDialog→runner→TableExporter→helper/mock加整窗取消恢复、物理pending和最终settled断言，沿既有真实ShutdownController/SQL guards测试，不另造关闭链。纯ProcessHandle快照不能保证任意快速派生/脱离子孙，仍保留[ProcessHandle边界](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessHandle.html)，不加native Job Object/FFM。
## 13. P1b / G9b 实测交付与冻结

本步按C2及后续资源边界修正授权完成。最终运行 `p1b-010-affected-targeted` 实际执行 `:cleanTest`、`:compileJava`、`:compileTestJava`、`:test`，退出0；35份新XML的suite与testcase双重计数均为 **560 tests / 0 failures / 0 errors / 0 skipped**。其中PgDump参数27例、进程基线4例、资源23例、实际AppShell整窗9例、原关闭链回归1例；其余为受影响的P1a/query/task/shutdown/SQL事务回归。不是全量测试，未运行buildSrc:test/image；buildSrc编译与jar仅为wrapper的既有配置依赖，本轮UP-TO-DATE。

原件另建于 [g9-pgdump-20261008-worker](evidence/g9-pgdump-20261008-worker/)。每次离线运行使用新UUID合成profile/temp和实测8.3路径，子环境清空后按名称补OS/JDK/缓存必需项；helper使用已有JDK运行合成Java代码，不调用pg_dump或数据库。005起在启动Gradle前冻结src/test、launcher与报告；010还包含README。最终运行前28份快照中25份为产品/测试运行源码；冻结时这25份、README、launcher共27份逐项与本次运行前SHA绑定，最终报告在运行后记录结果并另冻结，不冒称结果 prose 是运行前字节。

### 13.1 本次真实RED、修正与GREEN

| 原件运行 | 实际测试结果 | 含义与证据边界 |
| --- | --- | --- |
| p1b-001-baseline-red | 4 tests，4 failures，exit1 | 原PgDumpRunner仅加starter/短期限注入；静默/未换行输出在readLine前无总期限，参数例先在缺plain失败，stderr合成敏感标记进入异常。后续参数断言未执行；RED_HELPER alive=false是夹具finally强杀，不能称旧产品已回收。5份当时源码原件保留。 |
| p1b-002-baseline-targeted | 4 tests，1 failure，exit1 | 第一轮实现的empty helper因scratch清理失败而失败；当时原件保留。禁用合成JVM的UsePerfData后003通过；perfdata是当时推测，并无002目录内容清单证明唯一原因，不能推广为真实pg_dump行为。 |
| p1b-003-baseline-targeted | 4 tests，0 failures，exit0 | 仅基线绿色，当时8份源码快照保留；不代替最终资源/整窗验证。 |
| p1b-004-process-targeted | 40 tests，2 failures，exit1 | 有限8MiB输出可能在50ms内合法结束，却被旧fixture要求必超时；Windows控制台辅助进程先触发任一descendant latch，真实Java child尚未被捕获。004没有完整运行前源码冻结，且结果取回前已有后续修改，故仅保留原日志/XML失败记录，不绑定当前源码或冒充最终通过。 |
| p1b-005-affected-targeted | 545 tests，0 failures，exit0 | 已改为指定PID捕获等待与持续输出deadline协议，并首次验证实际主窗/事务回归；随后追加发现的资源边界不能借用该旧绿色。 |
| p1b-006-window-baseline-red | 1 test，1 failure，exit1 | 单独恢复HEAD AppShell，仅加入本地导出入口seam，保持原BestEffort→tasks.close路径。真实WindowShutdownController/SQL关闭链在fake根仍活、流仍held、physicalCompletion未完成时返回COMPLETED；回执shutdownDone=true。断言记录后fixture释放fake，收尾不能当旧代码正确结算。随后从005快照按SHA恢复P1b AppShell，未改变HEAD。 |
| p1b-007-cleanup-feedback-red | 46 tests，2 failures，exit1 | 正常/原Error启动失败叠加scratch残留，经真实publisher后operation未保留UI需要的路径；原件及运行前源码保留。 |
| p1b-008-resource-boundary-red | 49 tests，5 failures，exit1 | 初始root属性失败后空的外部替换目录被删；明确root NoSuch仍报cleanup；close抛错但底层未关时未保留pending；以及007两条残留回归。不是编译失败。 |
| p1b-009-resource-targeted | 53 tests，0 failures，exit0 | 立即登记scratch归属、关闭状态与固定残留列表修正后的进程定向；随后补主窗/用户提示等验收。 |
| p1b-010-affected-targeted | 560 tests，0 failures，exit0 | 最终同源码定向：实际总期限/进程家族/流/发布/UI/主窗与事务；35份本轮新XML。以这一轮绑定最终产品/测试字节。 |

001的原有限flood fixture和50ms时序不足以证明排空已经进入；最终Runtime测试先等待helper ready，持续输出另等待两路各至少64KiB才推进可控单调钟。有限flood有独立成功例，两路各准确8,388,608 bytes、固定8KiB read buffer、真实目标发布、发布后取消被拒绝。当前基线50ms只保留短期限回归，不作进入输出阶段的证明。捕获协议等待指定child PID；tree例另外等待grandchild被同一任务捕获，再放父进程退出。010实测parent/tree均将已捕获家族归零，独立邻居仍alive；测试才在finally停止自己的邻居，未通过全局PID搜索控制任何对象。

### 13.2 产品变化与物理资源边界

PgDumpRunner在prepare/start前建立默认10分钟单调总期限；监督器在starter前运行，迟到start仍属于同一任务。截止覆盖静默、持续/不换行双输出、drain/wait、exit0和发布资格；操作门禁内与实际move前再次核对截止，截止或取消先赢不发布，move已先赢则只结算成功一次。两个输出各用8KiB byte[]，只保存饱和字节计数；无readLine、无原输出/尾部缓存、无原异常cause转发。

根停止与已捕获handle的normal/force调用在固定数量owned worker执行，监督器不直接调用可能阻塞的destroy。每个phase一个后代队列，exact ProcessHandle保留；被阻塞/抛错的控制线程仍纳入所有权。root、捕获家族、starter、stdin/stdout/stderr、drainers、close/控制worker及监督器实际结束后producer才返回。5秒是回收观察预算，超预算只进入cleanupPending；不通过Future.cancel或线程完成标志伪造资源释放。迟到starter、force仍活fake、阻塞handle、持pipe子孙、IO/fatal失败均保留BUSY/temp到资源结算。

三路流分别有open/closing/closed/failed状态。只有close正常返回才确认closed；close抛IOException时即使worker已退出也保持unresolved，且原因固定为OUTPUT。首次close串行化，根与已捕获家族退出后，只允许每路**一次**最终close确认，使用固定owned worker，不按监督轮次重复调用。依据[Closeable.close契约](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/Closeable.html)：已关闭时再次close无效果；异常前先释放仅是建议，不能当关闭证明。因此测试分别覆盖“抛错时仍开着”和“已经释放后再抛错”，前者在迟到成功close前保持pending/BUSY，后者由幂等确认结算；EOF和取消后两路输出close失败都有独立用例。最终close若持续阻塞或再次失败，则保持未解决/主窗pending，不会盲目第三次close或假称COMPLETED；本步没有增加通用重试/强制退出功能。receipt的physicallySettled要求unresolvedStreams=0，除进程存活及线程数之外另校验该项。

scratch根创建后立即取得并登记根身份，每个空文件创建后立即登记该文件，再开始下一步；初始身份无法取得不获准删空根。root NoSuch只视为该命名路径明确缺失，不追踪未知移动目录；AccessDenied/其他错误不能当缺失。关闭只删除已记录且复核成功的文件与根，未知对象、替换对象及父目录换名均保留，不递归清除。部分创建失败例分别证明自有已知空passfile可安全清理、空的外部替换root与移走的owned文件保持不变。null fileKey仍使用creationTime/尺寸等回退比较；外部恶意伪造属性以及验证到unlink间竞争仍不是native CAS保证。

准备/启动/监督器launch的Error保留原对象；已启动监督器实际join后才传播。若scratch清理也失败，固定CLEANUP Failure作为suppressed并登记残留；共享publisher合并内层scratch及本地temp/witness残留，不能被后续清理覆盖。没有新建资源时的队列取消/runner拒绝/早期UI Error同样完成physical promise并注销一次；不修改全局FxTaskRunner或shutdown框架。

AppShell在shutdownAsync入口冻结tableExports；原SQL mandatory guards未同意前不破坏正在运行的导出，取消/破坏性阶段前失败恢复准入。shutdownRemaining先请求停止并等待physicalCompletion，再进入原BestEffortCloseSequence。真实AppShell→ExportDialog→runner→TableExporter→PgDumpRunner→helper/fake测试证实：workspace production Alert取消退出时原helper仍活、随后实际第二导出可准入且其结算不注销原任务；force仍活/owner隐藏/root已退出但stdin关闭未解决都保持实际主窗可见pending，SQL dispatcher和task runner尚未拆除；late close/release后才注销并完成窗口关闭。发布先赢、queued取消、early UI Error、客户端失败固定反馈分别一条或参数化验证。已有SQL事务、FAILED_PARTIAL与终态隔离用例全部包含在560定向中。

### 13.3 参数、凭据来源与客户端能力

每个host/port/dbname/user按libpq字段单引号/反斜线规则绑定；数据库名中的=、URI前缀、引号、反斜线、空格、中文都独立解析断言为单个字段值，禁止空字段/NUL及multi-host/socket形式。schema/table构造逐段完整双引号pattern。显式plain/no-password/content flags，-f仅指向publisher临时文件；密码仅在本次ProcessBuilder环境，start后删除父对象引用，既不进argv也不进异常/UI输出。

清空子环境，只按名称复制OS/PATH必需项；PGPASSFILE/PGSERVICEFILE指向owned空文件，HOME/USERPROFILE/APPDATA/TEMP等指向owned根，SSL证书/key/rootcert/crl显式指向owned不存在路径。保留 `gssencmode=disable` 与 `require_auth='!gss,!sspi'`，避免借用GSS/SSPI缓存身份；不为旧客户端静默去掉隔离。

协调客户端裁决明确允许此路径必要能力为**pg_dump/libpq 16+**：原[导出设计](../specs/2026-07-08-data-export-design.md)仅要求使用者保证版本与目标库兼容，未承诺旧客户端支持；[PostgreSQL 16 release notes](https://www.postgresql.org/docs/release/16.0/#RELEASE-16-LIBPQ)确认require_auth为16新增，[libpq16参数](https://www.postgresql.org/docs/16/libpq-connect.html)说明其认证约束。已在导出选项和README加入16+提示。actual UI的合成缺失starter与模拟非零客户端输出均收到固定可读16+说明，不包含合成stderr标记或目标host；真实旧客户端行为只是依据参数能力推断，不是运行证据。本步未安装/升级工具，未运行真实pg_dump/--version/网络/SSL握手；合成argv解析不证明全部真实客户端/服务器兼容。

### 13.4 冻结、剩余限制与退出

最终冻结 [p1b-final-freeze-010/manifest.json](evidence/g9-pgdump-20261008-worker/p1b-final-freeze-010/manifest.json) 包含本次完整产品/测试25份、README、报告、launcher及冻结脚本；运行前字节绑定见 [p1b-final-checks-010.json](evidence/g9-pgdump-20261008-worker/p1b-final-checks-010.json)。所有运行suite/case双重计数见 [p1b-run-summary-010.json](evidence/g9-pgdump-20261008-worker/p1b-run-summary-010.json)，本次物理回执由010实际XML system-out抽取到 [p1b-physical-receipts-010.json](evidence/g9-pgdump-20261008-worker/p1b-physical-receipts-010.json)，完整原日志/XML仍是依据。SHA/长度清单覆盖新证据根全部文件，包括ignored日志；manifest自身不递归加入自身。tracked patch另存，未跟踪新增源文件以完整freeze字节为准。旧P1a012清单的375份artifact逐项复核无变化，009/012及其原manifest一概未覆盖。

最终HEAD仍为b81923f29e0a6b6603710a589504aab490995491，分支codex/g9-table-export-reliability-20261008；diff-check通过。未访问保护目录，未stage/commit/push/fetch/tag/PR，未改main或其他会话状态、未创建线程/代理、未更新automation。本线程始终为唯一Gradle执行者，冻结后停止运行测试及写源码。

**仍未完成/未验**：G9c的专用JDBC/单cursor/配置快照/严格DDL/完整值边界未实施；原Oracle DDL吞异常仍存在。本步没有任意脱离/快速派生子孙的强保证，仅控制已捕获自有handles；JDK进程/流调用永久不返回或最终close仍抛错时保持pending，不承诺最终总能退出。scratch与publisher的属性回退及verify→move/unlink外部竞争仍保留。没有真库/真实pg_dump/客户端版本探测/SSL/原生chooser/原生桌面输入/慢盘/全量/buildSrc:test/image/P2/P3/main集成证据。560只是worker本阶段同字节通过，**P1b独立验收与整体G9交付尚待协调裁决**。

退出状态：P1b代码、报告与原件冻结后停写，交协调只读审核；不进入G9c/P2，不做提交或合并。
## 14. P1c / G9c 具体实施设计（新授权，未验收）

协调已独立验收B的29/355/560及旧A375原件，授权仅本worktree继续C；本节在改产品前记录。A/B证据根保持只读，C另建。无新增线程/代理、main/提交/全量/buildSrc:test/image/真库/真实pg_dump。

连接目标使用manager同步捕获的cfg/provider/version及同锁订阅+版本核验；source选择开始时绑定TableRef，格式能力只看该snapshot。订阅callback只将operation失效/取消，禁止锁内JDBC、文件或进程动作。旧Request构造保持兼容，但真实UI在格式对话框前创建绑定选择，chooser/覆盖拒绝、queued取消、拒绝提交、正常/错误均负责解绑；每次重试重新捕获。ABA/unregister/re-register使用单调版本，provider身份也在capture/validate核验；发布CAS先赢时不因晚通知破坏原move，校验不持manager锁移动文件。

SQL/XLSX用已有openDedicated(cfg,provider)，零acquire/live；prepare/setup先于writer开流。PG设置readOnly、autoCommit=false、READ_COMMITTED；Oracle独占autoCommit=false、readOnly提示并核对显式只读事务能力。数据一个forward-only/read-only Statement、fetch16、一个完整schema引用SELECT *；同一rs metadata供header/类型，空表不另探测，逐行完整取值→sink完成→next。无OFFSET/LIMIT/COUNT/maxRows/maxFieldSize。SQL BATCH100只为INSERT语句行计数，无行集合预取缓存。

沿SqlExecutionControl为每个DDL/setup/data Statement独立activate/execute/release。任务单调总期限从opener前起计，watchdog只发停止意图；固定受管后台cancel与独占connection fallback覆盖迟到open/execute/next/getter/LOB/close/rollback。主worker先结算writer→rs→stmt→rollback→connection并等待cancel/fallback workers；JDBC close正常返回可确认该对象释放，Statement.close可确认其当前rs，Connection.close可确认该连接的JDBC资源，但不能凭此确认独立文件/Reader/free或仍阻塞的取消调用。close/rollback异常永远阻止发布；异常后实际isClosed/父级释放只能确认物理释放，不能改写为成功。有限一次安全最终close确认，未证明释放或调用仍阻塞保持pending/BUSY/AppShell登记，不无限盲重试，不改全局JDBC框架。

完整值允许集只包含确定表示的null、String/Character、boolean、Byte/Short/Integer/Long/BigInteger/BigDecimal及有限Float/Double、已有java.sql Date/Time/Timestamp与明确的java.time日期时间；未知自定义子类、provider对象/preview、ARRAY/STRUCT/REF/BFILE明确失败，禁止未知toString。Oracle日期优先既有TO_DATE/TO_TIMESTAMP可表达路径，不扩张无法确定的时区字面量。SQL二进制只在同一方言声明真实支持时允许；Pg bytea预算1MiB，Oracle既有HEXTORAW采用保守2000 bytes单字面量能力，不冒称大BLOB脚本可执行。

常量：SQL单值原内容最多1,048,576 UTF8 bytes（binary按原字节），行聚合最多4,194,304 bytes，DDL最多4,194,304 UTF8 bytes且非空/非成功注释；数字计数使用确定数值表示，并在巨大precision/scale展开前拒绝。XLSX最多1,048,575数据行+header、16,384列、每个header/文本32,767 UTF16 code units且代理项完整；数字至多15有效十进制位、有限且在Excel非公式范围，按现有numeric单元格的十进制文本与double往返核验，不偷偷转文本；binary拒绝。row聚合预算同4MiB。Reader/InputStream用固定buffer增量计数并完整读取，预算超出失败而非截前缀；CLOB/BLOB/SQLXML的Reader/stream关闭及free均属于任务，失败不发布。普通getObject可能由driver先缓冲巨大单值，fetch16和mock不证明驱动硬内存上限。

严格DDL实现仅整表：Oracle DBMS_METADATA.GET_DDL完整有限CLOB、空值/SQLException失败，无原cause/成功注释。PG列与PK每个查询独立control，完整绑定table_schema/table_name以及constraint catalog/schema/name与kcu表身份，防同schema同名约束串表；旧展示/迁移/SchemaDiff DdlGenerator语义保持。DDL与数据不宣称整体Schema原子快照。

先跑真实旧TableExporter/shared分页/writer与Oracle fallback的RED；随后生产TableExporter→真实writer/publisher和实际UI/AppShell+mock JDBC验证单cursor/空表/500+行/源变动、配置ABA/provider窗口、全失败矩阵、完整值预算及迟到物理资源结算。用户固定反馈分别指目标变化、超时/取消、类型/额度/结构读取/资源释放失败，不输出值、凭据或原SQLException。最终A/B/query、ConnectionManagerDedicatedSession/SqlExecutionControl/JdbcEditorSession、provider/DDL/migration与task/shutdown/事务定向后同字节冻结停写，等待协调；本节只是设计，不是通过证据。

## 15. P1c / G9c 实施、实测与冻结交付

A/B独立验收后按本worktree继续C，首次产品改动前已写第14节具体设计。全部新原件位于 [g9-jdbc-export-20261008-worker](evidence/g9-jdbc-export-20261008-worker/)，每次新UUID合成profile/temp、清空子环境、实测8.3路径，离线执行既有JDK25与Gradle缓存；没有真实JDBC/pg_dump/--version/安装/原生桌面输入。每次运行前冻结当时src/test、README、报告与launcher，不拿后改源码解释前轮通过。C阶段没有创建新聊天/代理；任务自有virtual watchdog/cancel/fallback/final-close是产品生命周期资源。

### 15.1 真正失败记录与夹具修正

| 新原件运行 | 测试/失败/错误/跳过 | 实际含义 |
| --- | --- | --- |
| p1c-001-baseline-red | 6/6/0/0，exit1 | 未改C产品时实际调用原TableExporter/真实writer/publisher。SQL/XLSX共享连接与分页而无单SELECT；未知对象被toString；16位整数XLSX被发布；Oracle GET_DDL异常被旧generator转为成功注释。独立assertAll记录共享/页数/执行数，不是编译失败。 |
| p1c-002-jdbc | 6/0/0/0，exit0 | 首红六例转绿，只证明此基线，不借作后续资源或主窗通过。 |
| p1c-003-ownership | 18/1/0/0，exit1 | 6种late getter和5种read Error/close/free失败已通过；真实新增失败是Oracle setup execute原Error被Statement.close Error替换。原Error优先级随后修正。 |
| p1c-004-integrity | 57/3/0/0，exit1 | 三项均为新fixture错误：execute hook变更rows/value发生在fixture快照取值之前（两例），BigDecimal scale断言1000001未超过1048576。按SQL执行时取快照和明确常量边界修正；不称产品RED。 |
| p1c-005-targeted | 1043/0/0/0，exit0 | A/B/query、dedicated/editor/control、provider/DDL、迁移与关闭定向；尚未加入C真实主窗等后续边界。 |
| p1c-006-cancellation | 86/0/0/0，exit0 | opener/setup/execute/next/getter/rollback/关闭、writer关闭及取消worker受控阻塞，期限和单次最终close确认。 |
| p1c-007-window | 1076/7/0/0，exit1 | queued取消未释放selection订阅是产品RED；一例FX FutureTask包裹Error断言错误，五例controlled clock只推进一次且早于stopAt记录，不能据此说产品不进入pending。先确认stop后持续小步推进测试钟，保留真实UI/AppShell断言。 |
| p1c-008-window-values | 1090/6/0/0，exit1 | queued订阅问题修复并通过；一例误期待Pg现有方言生成decode而实际为hex bytea字面量，五例新主窗fixture未播种原assertCompleted要求的SQL草稿。加入真实open/checkpoint/seed，保留原草稿发布断言，非产品RED。 |
| p1c-009-affected-targeted | 1094/0/0/0，exit0 | late LOB含取消/配置失效、Error+free/close失败、实际主窗含LOB free/Reader.close及完整值限额；随后代码复核发现迟到SQLException优先级，不能把009绑定后续修正。 |
| p1c-010-late-driver-red | 111/9/0/0，exit1 | execute/next/getter的迟到真实合成SQLException分别覆盖先赢的cancel/deadline/config失效，被错报SOURCE。原件完整保存；随后在资源实际结算后保留先赢停止原因，原Error/固定cleanup失败仍有优先级。 |
| p1c-011-affected-targeted | 1104/0/0/0，exit0 | 最终同字节运行：84份新XML，suite/testcase计数均1104；迟到SQLException九例和late output ownership回归均通过，实际:cleanTest/:compileJava/:compileTestJava/:test。 |

旧A/B的TableExportMocks原来只实现分页DataAccessor；C为其接入合成JDBC owner/cursor，原公开pages字段现在仅计cursor读取checkpoint，生产不调用任何DataAccessor。原MIDDLE例在真实writer开始后next失败，旧目标/邻居断言保留；老CLOSE夹具改为首次关闭抛错、最终确认成功，断言明确两次close。没有把真实首次close失败改成成功发布，也没有删除原回归。

### 15.2 最终产品与资源边界

ExportTarget持有不可变cfg/provider/version/authority，Selection在真实ExportDialog格式对话框前捕获；table参数本身为不可变TableRef，能力、请求、密码、factory/dialect均来自同一source。capture与subscribe/validate在manager同步边界关闭窗口；版本识别ABA和unregister/re-register，provider身份核验不重解为另一factory。拒绝chooser/覆盖、准入冻结、stale、queued取消、runner拒绝、早期UI Error、成功及后台失败路径均释放selection订阅；后台physicalCompletion前已解绑。producer自己的订阅finally关闭。源失效/总期限/用户取消与发布CAS争夺ACTIVE：先赢则不发布；发布已赢后的源通知不撤销原快照move，实际move不持manager锁。

**按协调范围预审撤销configMutation重构**：register/unregister/shared acquire/release/Redis更新保持原synchronized行为与原关闭/注册顺序；没有把新cfg公布后再在锁外关闭旧JDBC或迟做redis.register。独立exportListeners只在旧共享close之前发CAS失效意图；既有write-target通知保留其变更后位置。fixture验证锁内intent先到、共享close受控阻塞时新的capture仍被原锁挡住，不能看到部分公布状态。通知内不调用JDBC/file/process；不声称旧全局manager锁不含I/O，没有扩大Redis功能或新增锁序框架。

SQL/XLSX通过openDedicated(cfg,provider)独占新连接，不用acquire/live，其他共享/editor事务不rollback/close。PG设置readOnly、autoCommit=false、READ_COMMITTED；Oracle设置driver hint、autoCommit=false并用受控Statement执行SET TRANSACTION READ ONLY作为首条SQL。每表数据只有一次完整schema/name引用SELECT *，TYPE_FORWARD_ONLY/CONCUR_READ_ONLY、fetch16；metadata来自同一rs，空表XLSX有header，501行与无PK例完整输出，无LIMIT/OFFSET/COUNT/一行探测/maxRows/maxFieldSize。每行完整适配后先交sink再next；SQL BATCH100只是INSERT终止计数，未缓存100行。PG dump同样source绑定，零JDBC。

JDBC总期限默认10分钟，从dedicated opener之前起计，截止覆盖打开、设置、DDL、execute、next/getter/LOB、输出、关闭与rollback及最终发布资格。每个setup/DDL/data Statement使用既有SqlExecutionControl Activation，lease随真实close/父资源确认后精确release；cancel只是意图，实际cancel与必要connection fallback由owned virtual worker执行。writer/value Reader/InputStream/free、rs、stmt、rollback、connection、cancel/fallback/final-close和watchdog实际结算后producer才返回。观察预算5秒只发cleanupPending，仍持BUSY/temp/AppShell登记，绝不以Future取消或线程done替代资源释放。

close/rollback失败无论是否随后证明已关闭都禁止发布。JDBC isClosed或成功父close仅确认JDBC child释放，不能确认独立Reader/InputStream/file/free或仍阻塞的cancel调用；每个failed lease只给一次owned最终close确认。最终确认仍失败/阻塞就持续pending，不第三次盲close、无有界等待后假退出。原Error对象保留并加固定无值CLEANUP suppressed；特别覆盖setup execute Error+stmt close Error，Reader/InputStream read Error+close失败、LOB stream getter Error+free失败及同时取消。迟到SQLException不能覆盖已先赢的停止原因。

### 15.3 DDL与完整值

StrictTableDdl只服务单表导出，旧PgDdlGenerator/OracleDdlGenerator和展示/迁移/SchemaDiff语义未修改。Oracle受控GET_DDL绑定TABLE/name/schema，完整有限CLOB，空/错误/成功注释拒绝；reader关闭和free均归任务。PG列与PK查询均受control，PK JOIN覆盖constraint_catalog/schema/name及table_catalog/schema/name；同schema同约束名不会仅靠名字拼接其他表。DDL最多4194304 UTF-8 bytes，非空；不支持的USER-DEFINED/ARRAY catalog类型明确失败。仅列定义与PK，无索引/触发器/外键等全库完整DDL承诺；DDL与数据并不宣称共同Schema原子快照。

TableExportValues允许exact known类的null、文本/字符、boolean、有限常见数字及已明确日期时间；未知对象/子类不调用toString，ARRAY/STRUCT/REF/ROWID/JAVA_OBJECT/BFILE拒绝。SQL原文本最多1048576 UTF-8 bytes，raw binary同上（Oracle HEXTORAW保守2000 bytes），原内容行聚合4194304 bytes；转义后的SQL文件可更大，不把这些常量说成文件总限额。数字precision/scale和known文本有预算，完整LOB固定buffer增量计数，超额立即失败，不先无限materialize再截断。XLSX最多1048575数据行加header、16384列、header/文本32767 UTF-16 code units，代理项必须完整；有限15位有效数字、Excel非公式范围及decimal文本/double往返核验，拒绝二进制和不能保持精度的数值，不偷偷转文本。

Late CLOB/NCLOB/SQLXML/BLOB和Reader/InputStream getter返回后，必须先登记lease，再检查失效；测试将getter与close/free分别受控hold，取消和配置变化后仍释放恰好一次，保持BUSY直到实际返回。Reader/InputStream和free不是Connection.close可冒称释放的资源。普通getObject/getString/metadata可能由真实driver先缓冲大值，fetch16/mock并不构成硬内存保证。

### 15.4 实际主窗、官方依据及未验边界

实际AppShell→ExportDialog→runner→TableExporter→real writer/publisher＋mock JDBC验证opener、getter、rollback、conn close、Reader.close、LOB.free held时：真实WindowShutdownController主窗可见pending、task physical promise未完成、table owner仍登记、准入冻结，BestEffort SQL dispatcher/runner/其他连接未拆除。释放后own resources归零、listeners=0、一次终态再允许COMPLETED；hidden owner只抑制自己的UI完成回调。原SQL mandatory guard取消退出时专用导出继续存活，准入恢复，随后XLSX成功。不是另造shutdown框架。

PG cursor设置依据[pgJDBC cursor说明](https://jdbc.postgresql.org/documentation/query/)；Oracle driver hint与server事务区分依据[Oracle JDBC访问模式](https://docs.oracle.com/en/database/oracle/oracle-database/26/jjdbc/JDBC-coding-tips.html)和[SET TRANSACTION](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/SET-TRANSACTION.html)。另用现有JDK javap只读检查仓库Oracle23.26.1.0.0 jar：PhysicalConnection.setReadOnly在requireOpen后仅设置readOnly字段，isReadOnly读取该字段；[静态回执](evidence/g9-jdbc-export-20261008-worker/p1c-driver-static-inspection.json)记录命令/两jar SHA与excerpt，无DriverManager建连。不能拿hint证明server只读，显式事务必须成功才继续。Excel常量依据[Excel规格](https://support.microsoft.com/en-us/excel/excel-specifications-and-limits)；父资源确认参考[JDK Connection.close](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Connection.html)与[Statement.close](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Statement.html)。

仍未验真实PG/Oracle权限、驱动fetch/LOB内存、MVCC/undo、网络或慢盘、服务器DDL权限、实际pg_dump/libpq兼容、原生chooser或安装包。部分DDL类型明确不支持，SQL数值/时区在目标真实库的执行能力仍需授权环境验证。驱动/文件/control永久不返回或最终确认失败时继续pending，不承诺总能退出。A/B已有进程家族、属性回退、verify→move/unlink竞争边界保持。P2/full/buildSrc:test/image/P3/main集成、提交/推送均未执行；本轮同字节绿色只待协调独立验收，不宣称整体G9完成。

### 15.5 最终冻结与退出

最终运行[p1c-011-affected-targeted](evidence/g9-jdbc-export-20261008-worker/p1c-011-affected-targeted/)耗时2m4s，实际:test exit0；84份本次新XML suite/case双重计数均1104 tests，0 failures/errors/skipped。范围为A/B/C export/query、UI/task/AppShell/shutdown/事务及dedicated/editor/control、provider/DDL/migration；不是全量，未运行buildSrc:test/image。buildSrc编译/jar是wrapper既有配置依赖，UP-TO-DATE。

[最终源码manifest](evidence/g9-jdbc-export-20261008-worker/p1c-final-freeze-011/manifest.json)冻结49文件，其中45份src/test运行源码；45份runtime、README、launcher共47份逐项匹配011启动前SHA。最终报告记录结果后另冻结，Freeze-P1c脚本也另冻结，不把这两者冒称测试启动前同字节。[最终检查](evidence/g9-jdbc-export-20261008-worker/p1c-final-checks-011.json)记录身份、bindings、状态与diff-check；[每轮汇总](evidence/g9-jdbc-export-20261008-worker/p1c-run-summary-011.json)记录所有实际run的suite/case计数和真实:test任务；[物理回执](evidence/g9-jdbc-export-20261008-worker/p1c-physical-receipts-011.json)只从011的XML system-out提取，完整log/XML仍是依据；[全部C原件SHA/长度清单](evidence/g9-jdbc-export-20261008-worker/p1c-artifact-manifest-011.json)覆盖ignored日志、每轮源码、静态driver回执、冻结与patch，manifest自身不递归加入自身。

冻结时重新逐项核对A012的375份原件、B010的355份原件及29份B冻结源码SHA，均保持原字节；没有覆盖A/B run/清单或协调审核回执。最终HEAD仍b81923f29e0a6b6603710a589504aab490995491，branch仍codex/g9-table-export-reliability-20261008；diff-check通过。未stage/commit/push/fetch/tag/PR/main，未变更其他聊天或automation、未访问保护目录与真实profile/业务数据。本线程一直为唯一Gradle执行者。

退出状态：P1c产品、测试、README、报告和C原件冻结后停写、停测；等待协调独立审核再决定P2。没有将009旧绿色用于011字节，没有未结束Gradle或自有测试worker作为完成状态。
