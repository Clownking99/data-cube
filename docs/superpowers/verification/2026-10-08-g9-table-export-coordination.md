# G9 整表导出：协调与独立验收

## C0：计划与开发任务建立

- 当前目标：按维护者新授权建立 G9，并由新 GPT-6.1-sol 线程实施；本会话负责契约、审查、返工与验收。
- 基线：main 产品 `fccc58ba95bb0deec19463cd75f2bb32a33dbc8a`；计划提交 `b81923f29e0a6b6603710a589504aab490995491`，仅文档与历史原件，没有产品改动。
- 改动：[G9 计划](../plans/2026-10-08-g9-table-export-reliability.md)明确 G9a 文件保护、G9b pg_dump 作业生命周期、G9c 完整一致读取，以及 P0–P3 的分工与验收矩阵。交接和路线图已添加当前任务入口。
- 已核验：范围内 main 干净；23 份历史审阅/上一轮 CI 文件复制前后 SHA 与 Git 暂存字节一致。历史测试与诊断仅为问题基线，G9 尚无新实现或通过结果。
- 下发：create_thread 使用 `model=gpt-6.1-sol`、`thinking=high`、dataCube 项目、从 main 建立独立 worktree，任务标题“DataCube G9 整表导出可靠性”。返回 `client-new-thread:9c801dc8-2200-4bd1-83a5-3702543b3c93`；该值是创建标识，不用作正式 threadId。
- 已观察 worktree：`C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾`，HEAD 为计划提交 b81923f2，初始 detached。首次 list_threads 尚未返回新线程正式 ID；不据此重复创建。
- P0 权限：只读核对、实现设计、状态/资源所有权与验收矩阵，可做独占合成诊断；暂不修改产品或提交/全量/打包。开发是唯一 Gradle 执行者，当前会话未并发运行 Gradle。
- 失败/未验：G9 产品、桌面、真库和最终交付均未验；旧 G8 完整发布待验不变。线程创建已排队，正式 ID 与 P0 结果待取得。
- 下一步：解析正式 ID 后用 wait_threads 紧凑快照跟踪；独立审查 P0，再下发具体 P1。保持旧跟进 PAUSED，不创建额外开发线程或自动扩展范围。

## 当前会话验收关注点

1. 文件安全测试须走整表真实编排/UI 后台路径，不能只对 SafeResultFilePublisher 自证；连接前失败也必须保留原目标。发布与取消的竞争须有实际文件字节断言。
2. 进程期限必须覆盖启动至结束的完整过程；stdout/stderr 及单行都有限额；helper 的实际退出和后代归属须独立观察，不接受只断言 Future cancelled。
3. JDBC 读取不能复用 UI 截断快照或共享连接；不以排序分页替代一致性。验证单次数据读取、独占连接、取消/异常关闭和邻接会话不受影响，真实驱动证据另列。
4. 如果沿用现有通用发布/取消原语，审查其 API 和既有查询导出回归；避免把 G9 状态机扩散到事务与迁移路径。
5. 最终分支与 main 复验各自使用新原件，核对受验源码和实际产物；跳过、原生未验和真实服务未验保持独立。

## C0.1：开发已开始，线程登记仍待工具返回

独立 worktree 已创建 `codex/g9-table-export-reliability-20261008`，HEAD 仍为 b81923f2，范围内干净，说明 P0 已开始准备；设计报告尚未出现。list_threads（20/50 项）仍没有返回正式 ID，不能把创建标识传给 wait_threads/send_message。未重复创建线程。

首次默认沙箱读取新 worktree 返回 Permission denied；随后按既有隔离工作区授权通过提升权限只读核实成功。失败的 Test-Path 结果不用于判断文件不存在。当前没有 G9 产品测试/实现结论。

P0 审查额外关注：Windows 正常 8.3 路径别名不能误判为非法链接；已有短路径兼容回归须保留。只读导出准入不能误用写操作确认导致只读连接无法导出；配置身份校验与导出独占资源必须分别说明。

## C0.2：正式线程已接通

维护者提供 `codex://threads/01a11b86-2026-7ed3-86c5-1ec232640653`；wait_threads 已实际返回 active/inProgress，后续消息工具下发成功。此前列表遗漏不再阻断协调，不需要再次创建或由维护者转述。

开发 P0 发现 Oracle DDL 生成器会将读取异常转成注释，可能导致结构导出假成功。协调会话要求在 P0 提出仅限整表导出的严格、可取消 DDL 路径，保留迁移/Schema Diff 既有语义，并补充短路径兼容与只读导出准入要求。此轮反馈不授权进入 P1；下一步等待 P0 完整设计后审查。

## C1：P0 独立审查通过，按限制进入 P1a

当前目标：审查最小实现契约，先下发目标文件保护，不跳到全量实施或发布验收。开发正式线程 `01a11b86-2026-7ed3-86c5-1ec232640653` 已完成 P0 并停止，HEAD 为 b81923f2，未运行 Gradle、产品测试或打包。

独立核验：完整阅读开发报告、身份与原件回执，并直接阅读 main 的 ExportDialog、TableExporter、SafeResultFilePublisher、ResultExportOperation 和查询导出协调路径。33 份工作字节 SHA / HEAD blob 与开发清单全部相符，9 份冻结文件 SHA / 长度全部相符；已复制为本会话不可覆盖的 [P0 审查快照](evidence/g9-table-export-20261008-coordination/p0-review/root-p0-verification.json)。这只是设计与身份审核，不是工程测试通过。第一次按 Maven 目录猜测读取两个类失败；随即使用已知 `src/com/datacube/` 实际路径核对，失败不计验证。

### 技术裁决

1. 复用安全发布器，删除整表异常路径中的最终目标删除。三个格式均只能取得自有临时路径，文件 capture / 显式覆盖确认必须在资源获取和实际写入前完成。保留真实 SQL/XLSX writer，不以假 writer 的独立测试代替整表编排回归。
2. 同意把 ResultExportOperation 的提交互斥改为非阻塞 CAS：取消先赢则零 move；发布先赢则取消不被接受，等待真实 move 的结果。发布失败不可回到 ACTIVE 或重试同一个 token。必须验证 move 被可控阻塞时 cancel 立即返回、失败不报 published、只有一次提交资格，以及现有查询导出回归。此修改不得扩展到全局事务或 task runner。
3. SafeResultFilePublisher 只做本轮必要的路径身份、临时文件归属及别名并发增强。允许普通 8.3 / 大小写 / 点路径；原始路径中已观察到的链接及父目录变化拒绝。硬链接采用已存在文件身份阻止本应用并发，并实测原子替换命名项不改变邻居链接的字节，不宣称可移植地识别所有硬链接。不能用备份搬移或非原子覆盖作为降级。
4. 文件验收覆盖已观察到的替换和本应用并发。JDK verify→move 之间对任意外部恶意进程的内核 CAS 保证不在本轮承诺，报告必须保留该窗口；普通目标保护、已观察变化拒绝、原子失败保留原件的验收不放宽。暂不引入 Windows native 文件/进程容器。
5. 后续 G9b 必须证明自有 root 与已捕获后代的实际回收；不能把 ProcessHandle 快照说成任意脱离后代的完整隔离。仍在写输出、持有 pipe 或无法结算时不得发布/报取消成功；清理失败后保留物理所有权及 pending 状态，不能关闭 BUSY 就让同目标重试。此项由 P1b 代码和 helper 实证审核。
6. 后续 G9c 采用同一 cfg/provider/version 快照、专用连接和单 cursor；严格 DDL 仅作用于整表，保留旧展示入口。允许只读/生产连接进行读导出。精确类型允许集、SQL/XLSX 边界和用户可见错误须在 P1c 实施前核对现有 writer，不允许借预算之名静默截断或随意拒绝常用合法类型。无需通用 JDBC 动态代理。
7. 驱动 opener/cancel 不返回、任意巨大单行的驱动缓冲、真实 MVCC/网络、原生文件选择器及慢/网络磁盘仍单列限制/待验；有界应用逐行消费不能冒充上述通过。

### P1a 下发范围和退出条件

- 授权在现有 codex 分支完成 G9a：真实整表 SQL/XLSX/PG_DUMP 的同目录临时写入与原子发布编排、显式覆盖确认、取消/发布门禁和本步必要的局部 UI 生命周期接口。可新增最小 TableExportOperation 骨架，但不提前引入 G9b/G9c 的进程/连接体系或泛化状态框架。
- 允许暂时保留尚未修复的旧进程/分页获取路径，必须在本步报告中明确未完成；P1a 不合 main、不视为可交付产品。最终 G9 仍须消除这些旧路径。
- RED 应先证明旧代码的真实破坏行为；编译/夹具失败另记。GREEN 至少覆盖三格式失败不改变旧目标/邻居、新目标失败不出现，连接前/首行前/中途/关闭/发布失败及取消，目标变化和并发别名，真实 SQL/XLSX 输出，覆盖拒绝无后台作业，以及现有 query 安全发布和取消回归。PG 本步可用注入的本地受控输出，不执行真实 pg_dump。
- UI 测试走实际 ExportDialog 编排或最小生产 seam；不得用只测 publisher 替代调用链。cancel/提交竞争用 latch/barrier，不能增加 sleep 伪造确定性。尚依赖 P1b/P1c 的物理资源取消如实标注未验。
- 开发线程继续独占 Gradle，仅运行本步必要定向；保留命令、退出、当前 XML、首红/工具失败和源码摘要。完成后写报告并停写等待独立审查，不暂存/提交、不跑 clean 全量、buildSrc 或 image。常规实现自主决定，无需维护者再次确认。

失败/未验：P0 没有产品改动或测试通过；G9b/G9c、P2/P3、真库/原生/完整发布仍未验。下一步由 GPT-6.1-sol 实施 P1a，本会话审核其真实调用链和新原始证据后再下发 P1b。

下发回执：send_message_to_thread 已成功，wait_threads 随后返回该线程 active/inProgress，开发明确开始 P1a 的真实旧行为 RED 与安全发布实现。协调文档和审查快照共 12 个暂存文件，11 份原件逐个 `git hash-object --no-filters` 与 index blob 相符。首次默认 `git diff --cached --check` 将保真 CRLF 原件的 CR 识别为尾部空白并 exit2；未改写原件，使用命令级 `core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol` 重新检查 exit0，原有空白检测项仍启用。此检查不计为产品测试。

## C1.1：为 P1b 补充精确连接参数审查

当前目标/发现：在开发 P1a 期间独立阅读 PgDumpRunner、PG/Oracle ConnectionFactory、SqlDialect、writer 与任务执行器，为后续接口验收做准备。发现旧 pg_dump 将 `cfg.database()` 原样放入 `-d`；[pg_dump 官方文档](https://www.postgresql.org/docs/current/app-pgdump.html)明确该值可以是 connection string，且其中的参数优先于冲突的命令行选项。因此“结构化 argv”本身不足以证明数据库名仍是字面量，数据库名中的 `=` 或 URI 前缀可能改变连接目标。这是源码与文档支持的推断，未访问真库或运行 pg_dump。

下发给 P1b 的具体要求：将 host/port/user/database 绑定为明确的字面参数，按 [libpq 字符串规则](https://www.postgresql.org/docs/current/libpq-connect.html#LIBPQ-CONNSTRING)正确转义，或对无法证明的形式明确拒绝；不可把数据库名作为任意 conninfo。补合成 `=`、URI 前缀、引号、反斜线、空格及空配置的 argv/环境断言；密码继续禁止进入 argv/日志。`--no-password` 只禁止交互提示，不等于禁止读取默认密码文件；受控环境/临时 passfile 策略须防止未指定凭据时静默借用用户文件，测试只用独占合成材料。

状态：这是既有“绑定目标/受控子进程环境”契约的细化，不新增功能或外部操作；P1b 尚未授权实现，不打断 P1a。工程/进程/真库行为仍待新证据验证。

## C1.2：独立核验 P1a 首红

当前目标：核验开发首红是否走真实产品代码，而不是以夹具行为或编译失败代替旧问题证据。已读取 `p1a-003-red` 的 command/exit/stdout/stderr、2 份 XML、冻结测试和 mock；夹具只提供合成 JDBC/DataAccessor 返回值和 SQLException，不直接写目标文件。实际 `:test` 执行，退出 1，4 tests / 4 failures / 0 errors / 0 skipped。

- SQL/XLSX 的真实 TableExporter→writer 中途失败后，原 9 字节目标分别变为 144 / 1464 字节。
- 门禁测试以已进入发布动作的 latch 固定顺序；旧 cancel 等待 move 放行，get 超时。另一个测试确认失败发布后旧 token 仍能取消，尚未封闭一次提交资格。
- 6 份运行原件与 8 份冻结源码的 SHA/长度已记入本会话 [独立首红回执](evidence/g9-table-export-20261008-coordination/p1a-red-review/root-red-verification.json)。4 份产品源经仓库换行规范化后均匹配 b819 基线及当前 main，未运行协调方 Gradle。

失败/限制：前两次 Gradle 权限/参数启动失败单列，不算这 4 个产品失败。冻结的 pre-red 启动脚本早于调用修正，第三次实际 command/log 单独识别。协调方第一次比对跨 worktree 原始 SHA 时因 ExportDialog 换行差异停止；进一步对照 Git blob 后确认规范化源码一致，保留原 SHA 差异，不改写原件。此检查点只确认旧故障，不能当作 GREEN 或本步验收。

下一步：开发已报告接入三格式临时文件写入及移除最终目标删除，正在跑定向；等待冻结结果后检查真实 UI/编排与发布器改动，再决定 P1a 返工或 P1b 下发。

## C1.3：P1a 初稿预审返工

当前目标：开发定向运行期间只读预审真实编排、共享发布器及 UI 生命周期；这是未冻结初稿审查，不是验收通过。实际改动保持在 6 个生产文件（包含为关闭失败测试所需的两个 writer OutputStream 重载），尚未进入 B/C。已向开发线程成功下发三项具体修正：

1. `capture` 在检查链接前调用 `normalize()`，会消去原始路径中的 `link/..`。应保留并检查原路径，再规范化；普通点路径、8.3 仍兼容，链接后接 `..` 必须有独占合成用例或明确平台未验。
2. cleanup 使用 `Files.exists` 将无法判断也当作不存在。应只把明确 NoSuchFileException 当已消失，读取身份失败应报告 CLEANUP；不得删替换项或静默遗漏残留。
3. 第二次取消返回 false 后，UI 会把已接受取消的等待状态显示为“正在发布”。应区分既有取消意图和提交已赢，重复取消反馈保持幂等，实际任务返回前不得假报完成。

验证/失败：结论来自当前源码控制流和 API 行为，要求开发补可控回归验证；本会话未运行测试、未接受 GREEN、未合 main。下一步等待修正后的冻结源码和新定向原件，再独立审核。

## C1.4：Windows 身份能力失败后的兼容裁决

开发定向发现本机 JDK25 的普通文件 BasicFileAttributes.fileKey 为 null，初稿对 fileKey 的强制检查误拒绝正常 Windows 文件；开发已保留失败回执及源码。当前只读预审可见改为 `isSameFile` 检查活动目标的硬链接别名，并在缺少 fileKey 时建立同目录独占硬链接见证验证本次临时文件。此项尚待最终原件核验和通过，不能把设计写成实测通过。

协调裁决已成功下发：允许继续最小纯 Java 见证方案，不引入 FFM 文件框架或 Job Object。由于产生额外自有文件，发布后见证清理失败必须如实呈现“文件已发布，但辅助文件清理失败”与残留路径，不能误报未发布，也不能仅记录日志而给 UI 普通成功。授权必要的最小警告接口及查询导出协调器局部接线，保持原取值、格式、会话规则。真实 JDBC/进程尚未停止仍然禁止发布，这一裁决只涉及完成发布后的辅助身份文件残留。

缺少 fileKey 且不支持同目录硬链接是新的文件系统能力限制，需明确失败、保留原目标，资源/实际 writer 尚未启动，已创建 temp 的归属和残留需可见。开发必须补见证创建失败、见证替换/移动、父目录变化与多个残留错误的断言；不能删替换项，或用后一个见证清理异常遮住先前 temp 的残留信息。NTFS 定向不能替代其他文件系统或原生慢盘验收。

下一步：开发修正后再次冻结定向证据，本会话继续审查；P1a 未验收，P1b/G9c 仍未启动。相关 Java API 边界参见 [Files.exists](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#exists(java.nio.file.Path,java.nio.file.LinkOption...))、[Path.normalize](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Path.html#normalize())。

## C1.5：错误传播与后续整窗关闭审查点

P1a 追加返工已下发：ExportDialog 从 FxTaskScope.submit 改用 runner.submit 并忽略 Future 后，局部 catch(Error) 的 rethrow 会被 Future 捕获，绕过原 ScopedFuture.done 的 fatalErrorHandler。要求在局部执行/结算路径保留一次严重异常报告，完成自有资源结算和固定 UI 反馈；补实际 submit 路径的 Error 用例，不改全局 task runner。

后续 P1b/G9c 审查准备（尚未下发实现）：已核对 AppShell.shutdownRemaining → tasks.close 的全窗退出路径。FxTaskRunner.close 等待约 3 秒后 shutdownNow 并返回，没有证明自有导出进程/专用连接已实际结束；仅在进度窗口 onHidden 请求取消可能晚于主窗的成功退出判定。因此后续设计必须说明导出任务如何接入既有异步整窗关闭结算：在后台请求停止、等待自有物理资源/发布胜负结算；仍 pending 时不能由 runner.close 返回就宣称退出完成。可审查最小 AppShell/导出注册接线，不扩大全局 shutdown 重构，保持现有 SQL 标签、取消关闭及 FAILED_PARTIAL 隔离规则。尚无新运行结论。

## C1.6：持续跟进已建立

为落实维护者要求的持续下发、审核、修正推进，已通过应用工具创建 `datacube-g9`（DataCube G9 审核推进），ACTIVE、每 15 分钟返回当前协调会话；create 返回成功，随后 view 成功。只跟进同一 GPT-6.1-sol 开发线程，不创建额外线程，不自动扩展功能，P3 实际交付后暂停。旧 `datacube` 的 automation.toml 已只读核实为 PAUSED，未更新它。无新变化时保持安静，仅在实质进展、阻断或需维护者决定时通知。

采用 OpenAI Docs 技能核对方式；[官方定时任务说明](https://learn.chatgpt.com/docs/automations?surface=app)确认当前聊天跟进可使用分钟间隔，本地任务需要电脑和应用运行。跟进本身不构成产品通过，不赋予真库、发布、tag 或其他外部新权限。

当前交付给后续协调的状态：P1a 修正进行中，开发最近自报曾有 357/357 定向，但其后源码已修改，又用 362 项中的两例失败复现 UI 隐去清理残留；这些均不充当最终通过。待最终冻结后重新独立核对源码和当次 XML/命令/哈希，不能复用旧绿色。本会话尚未接管 Gradle；先完成 P1a 审核，再下发 P1b，沿计划到 P2/P3。

## C1.7：009 冻结证据独立核验与最后的流接管修正

当前目标：审核 P1a 冻结实现及原始证据。直接审阅 TableExporter、ExportDialog 的实际生产调用、两个真实 writer、共享 publisher/operation 与查询导出反馈，以及失败矩阵、身份变化、取消竞争、fatal Error 和残留警告断言。发布后辅助文件残留与未发布失败已分开；Error 在实际 runner.submit 路径中保留一次严重异常报告。

独立运行本会话 [verify-p1a.py](evidence/g9-table-export-20261008-coordination/verify-p1a.py)，重算 `p1a-final-freeze-009` 的 17 份冻结文件、当时清单的 256 份证据文件和 009 运行时的 15 份源码，全部 SHA/长度相符，15 份运行源码与最终冻结对应。20 份 XML 的 suite 数值与实际 testcase 子项分别重算，确认 **373 tests / 0 failures / 0 errors / 0 skipped**；实际 `:test` 执行、退出 0。详见[根会话回执](evidence/g9-table-export-20261008-coordination/p1a-009-review/root-p1a-verification.json)。本会话未运行 Gradle，此为开发原件的独立审核，不冒充 main 新测试。

发现并成功下发一个剩余资源顺序修正：SQL 分支在 `output.open(temporary)` 之后求值 `provider.dialect()`；后者异常时 writer 尚未接管流。要求把可能失败的参数准备前移，补真实 TableExporter 注入断言，证明异常时打开输出流为零、无发布、旧目标/邻居和临时清理正确。009 冻结不覆盖，新增 RED/GREEN 与冻结后再验收。本次哈希检查时工作目录两个测试文件已经开始该修正，与 009 不同；回执明确记录，不把 009 通过外推到后续工作字节。

失败/未验：001/002 启动失败、004 的 Windows 身份假设失败、007 残留反馈 RED、008 严重异常及夹具路径失败全部保留；373 仅为 009 的通过。P1a 尚待新修正验收；P1b/P1c、全量、buildSrc:test、image、main 复验均未完成。既有真库/原生/外部文件竞争和完整发布边界不变。

下一步：核验新冻结；通过后下发 P1b 进程所有权与整窗退出接线。后续 P1c 严格 PG DDL 查询另需检查完整表身份：当前 `primaryKeyClause` 的 join 只绑定 constraint_name/table_schema，新的整表严格路径应避免同 schema 不同表同名约束串入；先核对真实查询契约再做最小修正，不扩张 Schema Diff 或全库 DDL 功能。

## C2：P1a 阶段验收通过，P1b 已下发

当前目标/改动：最后的 SQL 方言和 SQL/XLSX 行源准备均移至开流之前；保持开流前取消检查及原 writer 格式。真实 TableExporter 的新异常测试不代替产品关闭流：RED 中仅在断言记录后收尾夹具持有的流，GREEN 则断言根本没有打开流。

本会话直接审核新源码/测试与原件，并执行新 [012 独立回执](evidence/g9-table-export-20261008-coordination/p1a-012-review/root-p1a-verification.json)：**17 份冻结文件、375 份证据文件、15 份运行时源码** SHA/长度全部匹配，当前工作字节与冻结也全部一致；20 份 XML 的套件与 testcase 双重计数确认 **375 tests / 0 failures / 0 errors / 0 skipped**，实际 `:test`、退出 0。这里 375 份文件与 375 个测试是两个不同计数。009 全部原件保留，源码新测试结果不借用旧绿灯。

新增失败证据：010 为夹具泛型编译失败、无测试；011 实际 375 tests 中两条新回归失败，错误均为期望 open=0、实际1。其 TableExporter 与 009 旧产品 SHA 相同，两份夹具与 012 相同，已在[独立 RED 回执](evidence/g9-table-export-20261008-coordination/p1a-012-review/root-dialect-red-verification.json)核实。012 对两例修正后通过，Error 对象保留、0 move、旧目标/邻居不变且无临时残留。

裁决：**P1a/G9a 阶段通过**。不等于 G9 完成或产品可合 main；P1b 的旧进程路径和 P1c 的共享 acquire/OFFSET、配置快照、严格 DDL、完整值约束仍未完成。无全量/buildSrc:test/image/main 新复验，无真库/原生桌面证据。本会话一直未运行 Gradle，开发继续独占。

### P1b 已授权的实施与退出条件

send_message_to_thread 已成功向同一正式线程下发 P1b；先在 worker 报告补充短的资源及整窗退出接线设计，然后自主实现，不要求维护者再次确认。不启动 P1c/P2、不新增线程。实施采用 P0 第6节、C1.1 的精确连接参数与受控凭据来源、C1.5 的整窗关闭约束：

1. 从 start 前建立默认10分钟单调总期限，覆盖启动、所有输出形态、drain/wait及迟到 exit0。固定缓冲/有界统计，不输出原 stdout/stderr/敏感 cause；精确 table pattern 和连接字面参数，显式 format/no-password，隔离默认 pgpass/profile 等来源。
2. 所有权包括返回 root、已捕获后代、三路流、drainers/watchdog 与迟到 start。取消只发意图，后台物理结算；超回收预算仍在写或持有资源时维持 BUSY/pending，不发布或提前清理。只操作自有 handles，真实受控 helper 验证邻居不受影响；任意快速脱离后代仍不作强保证。
3. 允许最小 AppShell 导出任务注册/结算接线。关闭冻结后拒绝新导出；既有 SQL mandatory guard 尚未同意时不提前破坏导出，取消关闭应恢复准入。真正退出需等待导出物理结算或明确 FAILED_PARTIAL/pending，不以 tasks.close 的三秒返回/owner隐藏作为完成证据。保持 BestEffortCloseSequence、事务与终态隔离规则，不重构全局 runner/关闭框架。
4. 保留 B1/B2 的真实 RED 和 GREEN：静默/超量/不换行输出、启动失败/迟到、非零/drain失败、期限边界、取消双序、父退出子持pipe、已捕获子孙与独立邻居、force仍活fake。补实际 AppShell→导出→runner→helper/mock 的取消关闭恢复、pending/一次结算/迟到回调验收，不能只测注册器。
5. 开发只运行 P1b 定向及受影响的 P1a/query/task/shutdown/事务回归，冻结代码/命令/退出/XML/helper源码和物理状态后停写。旧原件不覆盖，无 stage/commit/全量/buildSrc:test/image/main/外部操作。P1c 的接口需求可以保留，实际专用 JDBC/单cursor/严格DDL实现另行下发。

下一步：跟进 P1b 设计及真实实现，独立审核源码和原始资源结算证据；发现问题直接下发具体修正。datacube-g9 保持 ACTIVE，交付 P3 后暂停；不自动扩展下一目标。

下发后的 wait_threads 回执：正式线程 active/inProgress，新 turn `01a11bda-327f-79b0-9dc3-d0e33042dd9a` 已确认开始 P1b，先核对进程与主窗关闭调用链、补设计，再实施；G9c 明确未启动。协调方仅提交文档和独立审核回执，不把此 dispatch 当 P1b 产品验证。
