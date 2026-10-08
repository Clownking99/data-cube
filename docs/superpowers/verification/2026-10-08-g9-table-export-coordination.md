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

## C2.1：P1b 首红与资源初稿预审（未冻结）

当前目标：2026-10-08 14:28 UTC 跟进，main 为 37c16147、范围内干净；开发仍在 b81923f2 的独立分支实施 P1b。已读 worker 第12节设计、真实 PgDumpRunner 最小首红 seam、helper/launcher、命令/退出/日志/XML。设计采用无文本缓存的固定缓冲计数、总期限、物理 pending 保留 BUSY，并在 BestEffortCloseSequence 前等待导出结算；此方向符合 C2，不需维护者额外确认。

独立核验 [p1b-001-baseline-red](evidence/g9-table-export-20261008-coordination/p1b-001-red-review/root-p1b-red-verification.json)：5 份运行冻结源码 SHA/长度全部相符，6 份运行原件记录哈希，TableExporter 与已验收 P1a 相符；实际 `:test`、退出1，XML套件和testcase均为 **4 tests / 4 failures / 0 errors / 0 skipped**。旧进程代码仅添加 starter/期限注入，默认仍保持10分钟，readLine顺序/未排stdout/原异常文本行为未修复。两个超时断言得到 TimeoutException，参数例首先因缺少 format 参数失败，非零退出例证明合成 stderr 标记进入异常。协调会话未运行 Gradle。

证据边界与补验已下发：参数例第一个断言即失败，后续 no-password/JAVA_TOOL_OPTIONS 和 dbname 字面绑定不能据此宣称已分别验证。RED_HELPER 的 alive=false 是夹具 finally 强制终止后的状态，不是旧产品会回收的证明。后续 GREEN 必须用 ready/实际输出计数与可控时序证明进入静默/持续输出场景，避免50ms期限在JVM启动前耗尽而空过排空验证。参数/环境各项独立断言；最终定向加入真实 AppShell/shutdown/事务回归，而非只运行当前导出过滤器。

刚出现的未冻结 PgDumpRunner 初稿预审另下发三项具体修正：

1. supervisor 的停止分支仍直接 `signalHandles(forceSent)`，可能被物理 destroy 调用阻塞，妨碍 observation/pending/force/后代观察。要求监督器只做观察与请求，后台物理控制有明确所有权且不能每轮创建无界worker；可控阻塞/异常用例验证迟到结算。
2. 新 Prepared scratch 清理直接 deleteIfExists，未复核目录/空passfile/service是否已被替换。要求只清理本次且仍匹配的对象，明确缺失与metadata失败分开；目录/文件替换时保留外部项、报告残留，不递归扫删未知内容。继续保留 JDK verify→unlink 竞争窗口限制。
3. prepare 清理失败会覆盖原始 Error。要求沿 P1a 契约保留原Error并附固定suppressed cleanup，检查Session/监督器建立和启动等更早路径；已取得资源仍须结算，不能提前抛出而遗失所有权。

上游静态核对：针对隔离不存在SSL文件是否必然导致连接失败的疑虑，阅读 [PostgreSQL REL_16_STABLE fe-secure-openssl.c](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/interfaces/libpq/fe-secure-openssl.c)，client cert 不存在的分支允许继续连接；未据此要求改变隔离政策。这只是上游源码证据，不是本机 pg_dump/真实SSL兼容通过，不读取用户证书或运行客户端。

失败/未验：只有首红得到独立确认；新代码仍在编写，P1b 无冻结 GREEN、无阶段验收。G9c/P2/P3、真库/原生/完整发布仍未完成。下一步等待开发修正和新冻结，再审核实际源码及物理资源证据；不并发Gradle，不合产品 main，不扩展范围。

## C2.2：整窗链路预审、早期归属与流关闭边界

当前目标：2026-10-08 15:13 UTC 跟进，main e80aa7ee 范围内干净，开发仍在同一 b81923f2 分支。已直接阅读新增 AppShellTableExportShutdownTest、TableExportTasks 和当前 PgDumpRunner 修正。实际 AppShell/原 mandatory workspace decision/主窗 controller 参与测试，覆盖退出取消恢复、冻结准入、真实helper仍活、force-survivor pending、owner隐藏与发布先赢；不是只测试一个登记集合。监督器的后代终止已移入固定数量后台worker，scratch清理和Error保留已有实现。`p1b-005-affected-targeted` exit0 已观察，尚未冻结或独立重算全部 XML/哈希，因此本检查点不新增通过计数。

冻结前另已下发两项具体边界：

1. scratch 根身份和 owned 文件到两次创建之后才统一捕获；rootCreated为空时清理允许跳过身份比较。要求在取得每项后及时登记，无法证明归属时不授权删除；补首次属性失败、部分创建失败以及根变为空替换目录的用例，明确根 NoSuchFileException 与未知状态的区别。
2. 流 close 抛 IOException 目前可能只更新 reason，结算仅检查进程/后代/线程退出。要求区分“底层确实关闭后抛错”和“关闭失败且仍打开”，不能以线程退出证明流已关闭；仍未解决时保持 pending/BUSY/主窗等待，迟到真实结算才释放，后台资源数量有界。需要其他技术处理时先提出明确证据与限制，不自行放宽契约。

客户端兼容裁决：新 `require_auth='!gss,!sspi'` 与 `gssencmode=disable` 避免静默使用操作系统环境认证。[PostgreSQL 16 发布说明](https://www.postgresql.org/docs/release/16.0/)明确 require_auth 为16新增，[参数文档](https://www.postgresql.org/docs/16/libpq-connect.html)说明否定认证方法的语义。原 `2026-07-08-data-export-design.md:115` 仅要求使用者保证客户端与目标库兼容，未发现承诺旧客户端版本。协调允许将此路径能力门槛明确为 pg_dump/libpq 16+，最小导出说明/README和固定错误指引需可见；禁止静默移除隔离作为旧版回退。无需新增安装、升级、通用客户端管理或版本探测进程，不运行真实pg_dump；合成argv与上游文档不当作客户端实测。

验证/失败/未验：这是未冻结源码与实际调用链预审，两个边界尚待新测试确认；P1b 不称验收通过。开发继续唯一 Gradle 执行权，P1c/P2/P3未启动。下一步等待修正与新原件，独立审核后再决定阶段准入；main只保存本会话审查记录。

## C3：P1b 冻结独立验收通过，已下发 P1c

当前目标：2026-10-08 15:43 UTC 跟进收到 P1b 停写交付，核对 pg_dump 物理所有权与最小整窗接线，决定完整流式读取阶段准入。审查时 main 为 `60cbe57858cf4a068b22ee9998c8af038583b284`、范围内干净；worker 仍为 b81923f2，既有开发 turn 已完成。未运行根会话 Gradle，未集成产品。

### 独立证据核验

本会话执行 [verify-p1b.py](evidence/g9-table-export-20261008-coordination/verify-p1b.py)，只读明确的 worker 证据路径并产生 [P1b 独立回执](evidence/g9-table-export-20261008-coordination/p1b-010-review/root-p1b-verification.json)：

- 最终 `p1b-final-freeze-010` 的 **29 份文件**、P1b **355 份 artifact**、**28 份运行前快照**及原 P1a **375 份 artifact** 均逐项重算 SHA-256/长度一致。审查时当前 29 份文件与冻结相同。28 份运行前快照中，25 份产品/测试及 README、launcher 共27份匹配最终冻结；报告在运行后填写结果，其改变明确保留，不能把结果 prose 当运行前字节。
- `p1b-010-affected-targeted` 的 command/head、exit、stdout 与 XML 对应；实际 `:cleanTest`、`:compileJava`、`:compileTestJava`、`:test` 执行，exit0。**35 份 XML，560 tests / 0 failures / 0 errors / 0 skipped**，suite 计数和实际 testcase 子项分别一致。worker 当时 `build/test-results/test` 的同名35份 XML 与归档 SHA 全部一致，见 [当前 XML 绑定](evidence/g9-table-export-20261008-coordination/p1b-010-review/root-current-xml-binding.json)。buildSrc 配置编译 UP-TO-DATE 不算 buildSrc:test 通过。
- 所有10轮原 XML 独立重算：001为4/4失败、002为4/1失败、003为4/0、004为40/2失败、005为545/0、006为1/1失败、007为46/2失败、008为49/5失败、009为53/0、010为560/0；各轮均0 error/skip，失败轮 exit1、绿色 exit0。旧绿色不覆盖后续失败。004缺完整运行前源码，仍只作为失败日志，不能绑定最终源码。
- 010 的物理摘要逐项等于原 XML system-out；另直接阅读 helper/TrackingProcess/HeldProcess 和实际断言，确认进程/读流/关闭线程的状态是在 fixture finally 强杀或放行之前验证。有限 flood 两路各8,388,608 bytes；持续输出用 ready/两路至少64KiB 后推进可控时钟；parent/tree 等指定PID被捕获才放父进程退出，邻居在产品结算后仍 alive。无真实 pg_dump/数据库运行。

源码审核包括 PgDumpRunner 完整控制流、相对 P1a 的 publisher/operation/TableExporter 变化、ExportDialog 生产入口与 physicalCompletion、TableExportTasks、AppShell 最小 diff，以及参数/资源/真实主窗断言。C2.1/C2.2 的阻断已关闭：立即登记 scratch 根/每文件归属，无法取得身份不删替换空根；只认可 NoSuch；流 close 抛错不等于已关闭，单次最终确认仍受所有权约束；控制调用不占监督器；内层 scratch 残留传到真实 UI；严重 Error 保留原对象。

006首红冻结 AppShell 确保留旧 BestEffort→tasks.close，仅加导出入口 seam；原 XML 在 fake 根仍活、physical pending 时记录 shutdownDone=true。010真实主窗链覆盖退出取消恢复、仍活/隐藏 owner、root退出但stream关闭未解决、发布先赢、queued取消和早期UI Error；资源 pending 时先于 BestEffort 等待，不能靠runner.close返回完成。原 SQL事务/FAILED_PARTIAL/关闭回归包含于本次定向。

裁决：**P1b/G9b 阶段通过**。pg_dump/libpq16+隔离能力提示已进入选项、README及固定错误反馈；源码/合成参数测试不能证明真实客户端或SSL兼容。已观察的自有资源未停止仍保留 pending，不能保证任意脱离子孙或永不返回的驱动/系统调用最终退出。属性回退与 verify→move/unlink 竞争窗口、原生chooser/桌面、真实DB/pg_dump/慢盘仍单列。G9c、P2/P3/main新验证未完成，G9不称交付完成。

本次检查诊断：最初按旧阶段目录名读取 source-snapshot/source-at-run 失败，随后核对实际 `sources-before-run.json` / `source-before-run` 并成功验证；比较跨换行源码时改为忽略行末空白的 diff，原 SHA 不改写。两项只是审查工具路径/呈现修正，不计产品测试失败或通过。

### P1c 下发与验收约束

已通过 `send_message_to_thread` 向同一正式线程成功下发 P1c，先在 worker 报告细化类型/精度/字节预算与资源结算设计，然后按既有授权自主实现；不等待维护者逐步确认，不建新线程/代理。根会话核对现有 SqlScriptExporter 逐行调用 dialect.sqlLiteral、BATCH仅计数，以及 XlsxWriter Number/Boolean/其他值toString 的实际路径，要求整表适配层严格完整取值，不把未知对象交给该fallback。

1. 格式选择开始绑定同一 cfg/provider/version/TableRef；原子订阅并复验，ABA、移除重建、provider改变均失效。三个格式统一目标快照，pg_dump无需JDBC但不能分别读取新旧配置/密码。监听仅发意图，manager锁内无阻塞I/O；取消chooser/确认、queued拒绝与所有终态释放监听。失效与发布延续CAS胜负，不持manager锁move；readOnly/production允许读导出。
2. SQL/XLSX专用连接，一次带schema引用SELECT/单cursor及其metadata，空表表头、>500行、无主键逐行读取，无OFFSET/LIMIT/COUNT/额外探测或静默maxFieldSize。PG事务与fetch16满足既定cursor条件，Oracle按P0策略；不动共享acquire/其他会话，SQL100行批次不累积100行对象。
3. 每条DDL/数据Statement使用独立SqlExecutionControl Activation；FX请求与实际cancel/owned fallback分离。迟到opener、execute/next/getter/LOB/关闭/rollback均有所有权，正常资源结算先于发布；关闭失败阻止提交，实际未释放或仍阻塞保持BUSY/主窗pending。保留原Error和固定不泄密反馈，不引入全局JDBC代理或关闭框架。
4. table-only严格DDL不把Oracle异常变成功注释，空/不完整结构明确失败；PG严格PK查询先核实并绑定完整表身份，避免跨表同名constraint串入；旧展示/迁移/Schema Diff行为不重写，不宣称DDL和数据共同Schema原子快照。
5. 明确格式可表示的null/文本/boolean/有限常用数值/已有日期时间与SQL二进制允许集；LOB/SQLXML完整有界读取与free/close。未知对象、unsupported类型、非有限数值和XLSX不能保真的数值/二进制明确失败。P0的SQL单值1MiB级/行4MiB级/有限DDL预算落实为精确定义与边界断言；XLSX行列/UTF16上限适用于整表，保留既有查询保真，不截断/偷转显示文本。驱动内部任意大单行缓冲不宣称硬堆上限。
6. 真旧路径RED与真实TableExporter→writer/publisher及AppShell→mock GREEN，覆盖目标变化、只读/production、配置ABA、迟到资源、各阶段异常和邻居/其他会话不受影响。复验A/B、query保真、dedicated session、控制/事务、DDL/provider/migration及task/shutdown回归；每次新UUID离线隔离环境。开发仍唯一Gradle执行者，新建C证据根，全部A/B原件不覆盖。

下一步：等待开发的 P1c 设计/实施与冻结定向，根会话继续只读审查、必要返工。没有授权开发自行进入全量/buildSrc:test/image/P2、stage/commit/main/push；完整工程及P3仍按冻结准入后分步执行。datacube-g9继续ACTIVE，旧datacube保持PAUSED，v3.2.9不动。

下发后的紧凑快照确认同一线程 active，新 turn `01a11c38-8cb6-75a0-b0a8-f6e256b9f28f` 已开始 P1c，先核对快照、专用JDBC、DDL及writer表示能力并补设计；游标 `431e0858-03dc-4a74-84cd-3c2876284d87:45`。这是实施接收回执，不是P1c通过证据。

## C3.1：P1c 首红独立确认与资源顺序预审

当前目标：2026-10-08 16:13 UTC（本地已10月9日）跟进；main为b4040415且范围内干净，worker同一P1c turn仍在实施。读取新增第14节设计，方向为绑定选择、专用单cursor、完整值预算及任务内资源结算，未完成冻结。根会话保持不运行Gradle。

已直接阅读 `TableExportJdbcBaselineRedTest`、合成 `TableExportJdbcMocks`、真实旧TableExporter/writer及Oracle DDL fallback。通过 [verify-p1c-red.py](evidence/g9-table-export-20261008-coordination/verify-p1c-red.py) 独立重算 `p1c-001-baseline-red`：**30份运行前快照 SHA/长度匹配，6份command/exit/清单/log/XML原件哈希记录，6 tests / 6 failures / 0 errors / 0 skipped，exit1**；实际`:test`执行。与已接受P1b清单共有的产品/测试字节均相同，只有worker设计报告改变。详见[独立首红回执](evidence/g9-table-export-20261008-coordination/p1c-001-red-review/root-p1c-red-verification.json)。

SQL/XLSX的501行用例均实际只开1条共享连接、调用3次分页accessor、0次专用数据SELECT；assertAll报告上述三项差异，共享事务未回滚/关闭断言未失败。未知对象两格式、16位XLSX数值与Oracle DDL异常各因“应抛异常却正常返回”失败。后四条首个assertThrows失败后，后续toString计数/旧字节断言未执行，不能把其未执行内容记为实测结论；源码可见风险与实际RED范围分开。没有新GREEN或P1c通过。

已向同一开发线程成功下发三项具体预审纠正：

1. `TableExportValues.row` 在getClob/getNClob/getSQLXML/getBlob返回后先check、再ownValue登记。取消/配置失效期间迟到返回的非null对象可能在登记前抛出而漏free；要求先归属再进行可失败检查，补getter进入→取消/失效→对象返回及关闭阻塞的可控时序，独立Reader/stream/free不能由connection.close替代证明。
2. text/binary/LOB的finally直接closeValue，后者会抛清理或取消异常，可能覆盖正文原Error，使外层来不及记录其身份。要求保留主Error对象、固定suppressed清理信息并完成物理结算；补读取Error叠加close/free失败及并发取消。此为未冻结源码控制流发现，待新回归确认。
3. ConnectionManager初稿新增configMutation并在锁外处理旧close/Redis更新，会先公布新configs。SQL→Redis且旧JDBC close阻塞时，另一acquireRedis可看到REDIS配置但Redis内部尚未注册。要求优先保留原register/unregister/shared acquire/Redis的同步行为，只添加导出快照/版本/通知必要接线；原限制是**监听callback**只发意图，不能推导为本阶段必须重构所有配置变更的I/O锁。允许在旧资源关闭前发出失效意图并以可控回归证明；若坚持全局锁改动，应先说明必要性、锁序与状态可见性，不扩展Redis功能。

另核对Oracle readOnly能力疑虑：[Oracle21 JDBC coding tips](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/JDBC-coding-tips.html)说明驱动支持该标志而服务器连接模式不同；未以旧版本错误码资料要求删去当前设计。具体SET TRANSACTION READ ONLY顺序及真实驱动能力仍待源码/合成测试和外部验收分别核对，网页不作真库通过。

失败/未验：本轮只接受旧缺陷RED，所有新资源/快照/值语义仍实施中。既有P1a/P1b通过不替代C；全量/buildSrc:test/image、P2/P3和main产品集成未开始。下一步由开发修正以上边界并继续定向，根会话等待冻结或可行动新证据；本检查点只本地提交审核文档/原件哈希，不推送或扩大范围。

## C3.2：P1c 011 原件通过核验，严格列定义退回修正

当前目标：2026-10-08 16:58 UTC 心跳（本地10月9日）收到P1c冻结。审查开始main为b18a1355、范围内干净；worker仍b81923f2，原P1c turn已完成。本会话只读审查开发源码及证据，未运行Gradle、未合并产品。

### 已独立核实的证据

本会话运行[verify-p1c.py](evidence/g9-table-export-20261008-coordination/verify-p1c.py)，生成[011独立核验回执](evidence/g9-table-export-20261008-coordination/p1c-011-review/root-p1c-verification.json)：49份冻结文件、1033份C artifacts、48份运行前快照、旧A375份/旧B355份artifacts和29份B冻结文件，逐项长度与SHA全部一致。审查时当前49文件匹配冻结；45份src/test加README/launcher共47份匹配运行前字节，worker报告运行后记录结果另记，不当运行源码。最终冻结脚本亦另记。

011 command/head、exit0、原始stdout与84份XML一致：实际cleanTest、compileJava、compileTestJava、test执行，1104 tests / 0 failures / 0 errors / 0 skipped；suite属性与实际testcase双计数一致。[当时当前XML](evidence/g9-table-export-20261008-coordination/p1c-011-review/root-current-xml-binding.json)逐项与归档匹配。物理摘要逐行与XML system-out一致；不把buildSrc配置编译的UP-TO-DATE算作buildSrc:test。

全部11轮XML独立重算：001为6/6失败，002为6/0，003为18/1，004为57/3，005为1043/0，006为86/0，007为1076/7，008为1090/6，009为1094/0，010为111/9，011为1104/0；各轮0 error/skip，失败exit1、通过exit0。直接读取003/004/007/008/010原failure节点：003原setup Error被close Error替换；007queued订阅残留为产品缺陷；010的迟到execute/next/getter SQLException分别将先赢cancel/timeout/config原因误报为SOURCE，九例现已回归。其余夹具修正按worker第15.1节单列，不把失败抹掉或用009旧绿证明011。

源码审核覆盖TableExporter/Selection/ExportTarget、ConnectionManager最小diff、TableExportJdbcJob全部资源结算、TableExportValues完整读取/精度预算、StrictTableDdl、writer接线、ExportDialog真实选择与提交/取消链、AppShell JDBC关闭测试及A/B相关差异。已读实际断言，确认late LOB先归属再检查、Error优先级、held opener/getter/rollback/close/free/cancel worker保持BUSY、共享事务不受影响、主窗在物理结算前pending以及queued订阅释放。ConnectionManager恢复原同步顺序；其合成测试证明通知先于共享close和capture受锁保护，并非真实Redis/网络测试。PgDumpRunner相对B冻结无产品变化。

### 本轮未通过的审查项与已下发任务

新StrictTableDdl的PG COLUMNS仍只读原7字段，任意普通data_type直接拼接。它不观察identity/generated/domain，也不处理非默认collation、时间/interval修饰；bit(n)因类型不含char/varying而丢掉长度。依据[PostgreSQL columns定义](https://www.postgresql.org/docs/current/infoschema-columns.html)，domain的data_type是底层类型，identity/generated另有标志，字符最大长度也适用于bit字符串。本会话从源码推断上述列会成功输出降级结构；本轮未运行新增复现测试，不冒称已经取得新RED。

这违反C3第4项“不完整结构明确失败”；限制为列+PK、无索引/FK承诺不免除列本身的丢失。已向同一开发线程下发最小纠正：只改新StrictTableDdl的元数据判别与允许类型契约；不能准确表示的修饰用固定STRUCTURE失败，简单现有类型修饰可准确保留。未知类型/缺失必要标志/元数据异常不能静默退回普通列。不新增identity或完整Schema生成器、不改原PgDdlGenerator/迁移/SchemaDiff，DATA-only/XLSX/pg_dump不扩大。

要求先在011产品字节上保存identity/generated/domain/bit(8)/timestamp(3)/非默认collation等真实TableExporter→strict reader→writer/publisher RED，再补STRUCTURE/BOTH的旧目标/邻居、0 move、output未打开、取消与owned结算断言；修正后新受影响完整定向、新编号/冻结/清单，011和旧A/B全部保留。同一worker已active接收，新turn为01a11c7b-8eab-7831-a29f-daaaa795aecc，紧凑游标431e0858-03dc-4a74-84cd-3c2876284d87:51。

裁决：**011证据真实性通过，P1c阶段暂不通过；已退回具体修正。** 仍由开发唯一运行Gradle，尚不允许P2/full/buildSrc:test/image/stage/commit/main/push。P0/P1a/P1b通过维持；G9未完成。下一步审查新RED、修正源码与新冻结，通过后再下发P2。真实DB/pg_dump、原生chooser/桌面、驱动内部缓冲、永久阻塞、慢盘及文件属性竞争窗口继续单列；datacube-g9保持ACTIVE，旧datacube保持PAUSED，v3.2.9不动。

本会话归档15份独立回执/worker清单与冻结报告，均以raw hash-object和暂存blob逐项复核相同，检查器实际exit0，显式文档范围diff-check通过。本地提交仅保存计划/交接/审查证据，不代表worker产品提交或新工程测试通过。

## C4：P1c 修正独立验收通过，P2 已下发

当前目标：2026-10-08 17:13 UTC心跳跟进严格列定义返工；审查开始main为3674cd98且范围内干净，worker仍b81923f2，完成后停写停测。本会话未运行Gradle，没有产品合并。

### 新RED、冻结和运行身份

通过[专用只读核验器](evidence/g9-table-export-20261008-coordination/verify-p1c-ddl-red.py)核对012：49份运行前快照长度/SHA匹配，其中19份src产品与011冻结逐项相同；真实test exit1，12 tests / 12 failures / 0 errors / 0 skipped。原XML的十二例均在assertThrows因正常返回而失败；其后的move、旧字节及cleanup断言未执行，不能把后置断言当旧源码实测。见[012独立原件与回执](evidence/g9-table-export-20261008-coordination/p1c-012-ddl-red-review-002/root-p1c-ddl-red-verification.json)。核验器首次把length字段误读为bytes而KeyError退出，未生成验收结果；首次捕获保留在p1c-012-ddl-red-review，修正后新目录执行成功。这是协调工具诊断，不是产品测试失败。

014完成后执行[verify-p1c.py --revision 014](evidence/g9-table-export-20261008-coordination/verify-p1c.py)。[独立回执](evidence/g9-table-export-20261008-coordination/p1c-014-review/root-p1c-verification.json)确认：50份冻结、1348份C artifacts、49份运行前快照、旧A375/B355/Bfreeze29以及旧C011的1033份artifacts逐项SHA/长度一致，当前50文件与冻结相同。46份src/test、README和新launcher共48份匹配运行前字节；报告运行后更新、新冻结脚本分别记录。所有前14轮XML仍独立重算并保留失败；新增013为188 tests/9 suites/0 failure,error,skip，exit0；最终014为**1181 tests / 85 suites / 0 failures / 0 errors / 0 skipped**，exit0。

014真实cleanTest、compileJava、compileTestJava、test执行；85份归档XML与[当时当前XML](evidence/g9-table-export-20261008-coordination/p1c-014-review/root-current-xml-binding.json)逐项相同，suite与testcase计数一致，物理摘要逐行等于XML system-out。buildSrc编译UP-TO-DATE仍不算buildSrc:test。原011绿色只对应旧字节；最终014包含原1104及新77条StrictTableDdlMetadataTest。历轮失败/通过与原件保留，不用013代替最后一项RuntimeException修正后的014。

### 源码裁决与限制

本会话已阅读StrictTableDdl相对011的完整diff、新77例的实际断言以及fixture必要字段。产品改动仅此strict reader；读取identity/generated/domain/collation及修饰字段，使用明确基础类型允许集；缺失或未知必要标志、矛盾元数据和不可表示类型在打开输出前固定STRUCTURE失败，保留原Error/先赢取消。字符长度、numeric精度/scale明确保留；基础列、PK、BOTH成功路径仍有真实writer结果断言。失败路径检查outputOpen=0、move=0、旧目标/邻居不变、无临时残留、连接/语句关闭及listeners=0；受控晚返回metadata getter保留BUSY/物理pending直到结算。DATA-only SQL/XLSX/pg_dump不被新增catalog准入扩大，旧DDL展示/迁移/SchemaDiff未改。

按C3.2允许的保守方案，本轮SQL结构明确拒绝identity/generated/domain、非默认collation、bit/bit varying、time/timestamp（含时区）/interval及未知类型；简单字符/numeric与允许的基础类型保留，README已准确注明，不宣称通用恢复或完整Schema。后续扩展这些类型另立范围。该限制已审核，不再为新增格式兼容扩大G9。本地mock不能证明真实库权限、driver缓冲/取消、MVCC/undo或跨版本SQL恢复。

裁决：**P1c/G9c阶段通过，P0/P1a/P1b/P1c均通过独立审查。** G9仍未交付，P2/P3尚未完成，main产品未集成。driver永久阻塞/final-close失败保持pending、已捕获进程家族范围、属性回退及verify→move/unlink窗口、原生chooser/桌面、真实DB/pg_dump/慢盘等限制继续保留。

### P2实施授权

已通过send_message_to_thread向同一GPT-6.1-sol正式线程下发完整P2。开发继续唯一Gradle执行者，在新P2证据根保留全部A/B/C原件；对最终固定源码新跑完整G9定向、真正clean全量、强制buildSrc:test和jpackageImage，逐项核对command/exit/log/XML/产物SHA，live跳过单列。发生失败保留原件、定位最小修正并重验，不放宽断言或加长等待掩盖问题。

新镜像检查不得混入测试类/profile/验收JVM选项；记录exe/cfg/modules身份，外置合成linked-runtime探针做零真实连接验证，可复制已审核XML/XLSX探针并记录来源，旧结果不算新证据。补最小真实TableExporter→SQL/XLSX→publisher mock正常及失败文件保护；进程仅用受控helper。不得启动原生UI、联网更新、真实pg_dump/--version或数据库。

全部通过后允许开发在独立分支明确暂存实现/测试/README/worker报告/本轮证据，逐文件raw与索引/commit blob核验（含明确force-add的ignored日志），本地提交后停写停测，回报精确SHA/未暂存状态/统计/跳过/证据/镜像身份并交回Gradle所有权。开发不合main或push；P3由根会话独立审核、合main、新profile复验，再按已有授权推main及核对精确SHA CI。保护目录及其他安全边界不变，不创建更多线程/代理、不扩展功能或外部验收。datacube-g9仍ACTIVE，完成P3才PAUSED，旧datacube及v3.2.9不动。

接收回执确认同一线程active，P2 turn为01a11c8a-3ecd-7e02-ae50-b661829dac24，游标431e0858-03dc-4a74-84cd-3c2876284d87:61；这是实施接收，不是P2通过。根会话只保存本轮审核文档和原件。归档时两份stdout受*.log忽略；多次add/force返回0仍未入索引，原因未确定。临时目录级忽略例外亦未解决，已删除；最终使用仅允许这两份已知路径、明确拒绝.testagent的raw blob/cacheinfo暂存并逐项读回相同SHA，不改变仓库忽略规则，未将未入库日志误报归档成功。

最终32份本轮原件/回执raw hash与索引blob全部相同；19份产品冻结对比确认只有StrictTableDdl改变，见root-product-change-binding.json。原始Gradle stdout的第2行自带行尾空格，归档diff-check因此报错，原件保持不修剪；本次编写的文档与核验器单独diff-check通过，原件用逐文件字节校验。这不是产品测试失败或跳过。

## C5：P2独立验收通过，接管P3

当前目标：2026-10-09本地时间，main审查基线a32a2272、范围内干净；同一开发线程完成P2后idle、停写停测并交回Gradle所有权。开发产品提交e7950123ed052b9c370ed355496f3333b35ad11c，原件/报告提交b76b75c9106709121fd542108cb3bbde944cfe15，开发工作区范围内干净。根会话尚未运行Gradle。

本会话以[verify-p2.py](evidence/g9-table-export-20261008-coordination/verify-p2.py)只读独立核对，见[P2回执](evidence/g9-table-export-20261008-coordination/p2-review-001/root-p2-verification.json)：3516份raw清单项目加2份自排除清单、47份代码/测试/README，共3565份文件与HEAD Git blob原始字节相符；47份代码均等于已接受C014。844份输入的当前、输入快照及四次运行前后身份相符；运行发生于产品提交前，command的b819基线与提交后e795/b76身份由内容哈希绑定，不误要求历史HEAD等于最终HEAD。

本次真实P2定向85 suites/1181 passed，clean全量345 suites/4666 tests，其中4663 passed、3 live skipped，强制buildSrc 1 suite/8 passed，均0 failure/error、exit0；四轮实际任务执行而非缓存通过。当前根项目345份XML及buildSrc XML与归档相同。三项跳过为Redis、Oracle SchemaDiff、PostgreSQL SchemaDiff live，清空外部环境后明确缺少门禁，不算通过，也未访问真实库。每轮均独占UUID home/temp。

本会话读过launcher、XML计数器、镜像审计/修正版wrapper、外置TableExporter探针及全部合成JDBC factory。SQL/XLSX各501行成功与中途失败原目标保护、共享事务未动、独占连接回滚释放等实际断言可见；actualDriverConnectCalls=0来自审查过的synthetic factory和driver discovery路径，不冒充网络拦截计数。7个镜像审计子命令均exit0；独立重算183个当前镜像文件，exe/cfg/modules哈希匹配报告。测试类索引、验收配置污染检查通过，XML 7组、XLSX20包40单元格、无效UTF16 6项及G9 2成功/2失败保护在新linked runtime通过。

失败/诊断原件保留：Run-P2便捷PowerShell汇总将空skipped节点判为false，full便捷skipped=0不采用；独立ElementTree同时核对suite属性和实际节点，确认3跳过。首次005镜像wrapper清空TEMP后落入C:/WINDOWS，目录拒绝、exit1且尚未运行探针；006先建立独占wrapper临时环境，再完整成功，005不覆写。jpackageImage stderr中的12条javac failed与12条java failed伴随Picked up JAVA_TOOL_OPTIONS保留；不按文字误判整个任务失败，也不抹去，实际任务exit0且产物新审计/linked子进程成功。首次Git ls-tree不支持exclude命令诊断保留，改精确cat-file字节核验后成功。原stdout尾空格不修剪。

裁决：P2通过，根会话接管P3及唯一Gradle执行权；下一步本地合并b76b75c，再用新隔离环境定向、clean全量、强制buildSrc/image和新镜像外置探针复验；通过后更新交接、推main并核对精确SHA CI。此时G9尚未交付、没有新main测试。原生chooser/桌面、真库/pg_dump、MVCC/undo、真实driver取消/内部分配、慢/网络磁盘、文件身份校验至move/unlink竞争窗口、永久阻塞资源pending和已捕获进程家族边界继续保留。无tag/发布/新目标，datacube-g9保持ACTIVE至P3实际交付。
