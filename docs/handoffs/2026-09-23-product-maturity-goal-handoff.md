# DataCube 新会话交接：产品成熟度与首个目标

编写日期：2026-09-23。此文档旨在让新会话不依赖旧聊天全文即可接手。

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
