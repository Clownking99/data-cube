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
