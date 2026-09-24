# G7 / M7 迁移工程验证账本

状态：分支最终验证通过，待本地合并与 main 独立复验。只记录实际运行；跳过不算通过。本地工程通过不等于发布验收。

## 范围和环境

- 授权：维护者在 G7/M7 范围确认后要求继续，沿用原安全/本地操作边界，不进入 G8。
- main 基线 `0fb3583a1f0a22640e1f86d721852633d472fce7`；独立分支 `codex/datacube-g7-migration-evidence`。
- worktree `C:\Users\hetia\.codex\worktrees\datacube-g7-migration-evidence\朝花夕拾`。
- 本轮日志/XML/JSON：`C:\Users\hetia\AppData\Local\Temp\datacube-g7-75f4f39caf5d46e4b1c30922a0804c7b`。机器记录见 [实际结果](2026-09-25-datacube-g7-results.json)。
- JDK 25.0.1+8 / Gradle 9.2.0，offline；Test 子进程独立临时 user.home，去除 Redis/SchemaDiff live 环境参数。镜像不带测试 init/profile/headless 参数。
- 只使用 mock JDBC、合成数据、临时目录；未访问真实连接/数据库/凭据/历史/业务文件，未读写 `.testagent/`。无子代理、新依赖、push/tag/PR/安装更新/发布/外部联系。
- 可重现脚本：[run-check.ps1](evidence/g7/run-check.ps1)、[isolated-tests.gradle](evidence/g7/isolated-tests.gradle)。先复制两文件到独占临时证据目录，在当前进程指定 JAVA_HOME，再传 Repository、Name、GradleArgs；不要把临时 profile 放到真实配置目录。ImageBuild 不注入测试设置。

## 实现与行为证据

| 能力 | 实际行为 | 回归 |
| --- | --- | --- |
| 目标端统计 | 参数化 schema、long 计数；n_live_tup 仅估算，NULL 为未知；只读读取/超时/资源关闭，取消不发成功结论 | PgVerifierTest |
| 只读预检查 | 版本/权限/编码时区/可见依赖、字段与主键状态；已有数据模式；目标触发器/规则/生成与身份列/RLS/继承；完整解析数据与源 proof | MigrationPreflightTest |
| 确认与身份 | 不可变源/目标/owner/schema/目录/模式，具体计划实例、单次 10 分钟确认；文件摘要、文件身份、源身份、目标进程/库/用户与 Schema/表 OID；写前和锁后复查 | PgImporterTest、MigrationPreflightTest |
| 数据路径 | 受限 INSERT 字面值解析为绑定参数；不执行文件 SQL、COMMIT 或参考 DDL；拒绝跨目标/表达式/注入/错类型/歧义转义/无效 UTF-8/超限 | MigrationDataFileTest |
| 事务与故障 | 每表一事务；500 行批写不提前提交；后批失败整表回滚；提交回复失败即使服务端已提交也记未知；检查点失败停止后续表，保留已提交事实 | PgImporterTest |
| 有限对账 | 提交前稳定表锁下读回全部目标行；精确行数、逐列 NULL、源主键存在时唯一/非空、数字规范化/文本原样多重集合摘要；不匹配回滚 | MigrationDataFileTest、PgImporterTest |
| 源窗口 | 每表独立 Oracle 只读事务；明确列顺序、精确数值/UTF-8、空表也发布；源身份/列签名/行数/窗口/文件摘要绑定 proof | MigrationTableExporterTest |
| 文件发布故障 | 读失败保留旧数据；数据和 proof 分别原子发布；pending 标记阻断部分发布，禁止自动覆盖中断项 | MigrationTableExporterTest |
| 逐表报告与重试 | 状态/阶段/原因码、文件摘要和源/目标时间；未知项不可重试；已回滚/未写项 fresh preflight + 再次确认；重试报告关联父运行与表序号 | PgImporterTest、MigrationReportTest |
| 入口与生命周期 | GUI/CLI 共用服务；测试/导出不创建目标；导出并预检查不导入；模态中配置变更失效、迟到回调失效、关闭释放资源、失败不显示完成 | MigrationOperationsTest、MigrationWorkflowTest、MigrationConsoleTest、既有 MigrationPane/Cancellation/Coordinator 测试 |

参考 DDL 仍是人工转换素材，写入独占 `reference-ddl/<UUID>`，有未完成转换声明，不是已迁移对象；输出异常会失败。普通日志可能含对象名和路径，不能称脱敏；持久化报告只含运行/表序号等受限字段，不含端点/用户名/密码/SQL/原始异常/数据值。恢复报告没有批准或重放 API。

## 范围限制

- 自动映射仅简单名称、精确数字和字符；创建 NUMERIC/TEXT 表、主键和非空约束。已有相容精确整数类型允许写入，任何舍入/截断须在提交前对账时拒绝。浮点、时间/时区、LOB、二进制、自定义类型及布尔推断阻断，未承诺语义等价转换。
- 源 catalog 仅当前账号可见对象。默认值、序列水位、索引、其他约束和程序对象须另行审阅；没有清空覆盖、CDC、任意 PL/SQL 转换、跨库统一事务或全迁移回滚。
- 源单表只读窗口与目标锁内对账时点分别记录，没有跨表共同 SCN 或跨库同一快照；旧文件无 proof 时仅比较指定文件，不能证明当前源库一致。摘要为概率性校验，数字按精确值归一，文本不修剪/不做 Unicode 归一。
- 1000 表/20000 字段、1600 字段/表、1 GiB/文件、1 Mi 字符/行、500 行批写。导出可并发，导入串行整表事务。长事务成本、驱动实际取消与锁语义留待授权环境验证。
- 数据/proof/pending 是分别持久化的文件，不承诺多文件或断电原子事务、磁盘目录 fsync 或对恶意并发文件替换的完备防护。校验和证明内容绑定，不提供签名真实性。pending 留存时选新目录导出，不自动恢复重放。
- 客户端门禁不是数据库最小权限替代；数据库函数和恶意服务器隐藏副作用不在防护承诺内。

## 实际运行与失败历史

baseline-full：297 suites / 3756 tests / 3753 passed / 3 live skipped，4m36s。基线编译产物早于本轮 M7a 源码变更，已核对原 void verify 签名；不是新实现通过。

| 运行 | 结果 |
| --- | --- |
| m7a-targeted | 14/14 passed |
| m7b-preflight-data | testCompile 失败：lambda 捕获重新赋值变量；没有运行测试、没有引用旧 XML |
| m7b-preflight-data-2 | 修正后 24/24 passed |
| m7c-compile、m7-ui-compile、m7-desktop-compile | 编译成功，不算测试通过 |
| m7c-import-report | 37/37 passed |
| m7d-export-evidence | 42/42 passed |
| m7-entry-workflows、m7-review-targeted | 各 53/53 passed |
| m7-review-guards | 59/59 passed |
| m7-shared-operations | 61/61 passed |
| m7-final-targeted | 62/62 passed，0 skipped；之后重写规则/字段冲突检查纳入最终全量 |

初始 apply_patch 同文件 delete/add 被拒绝后改用有效编辑，没有测试运行。审查过程补正旧 DDL 自动执行、逐批 commit + 自动整文件重放、空表遗留旧文件、旧一键导入、不准确成功标签和全量/增量宣传。没有借成功复跑抹除失败。

## 桌面与外部待验

G7MigrationDesktopFixture 使用真实迁移面板、合成 backend、专用空 profile，JDBC factory 遇调用立即失败；它只在 test 源集中。新编译并启动，stdout 为 outputScale=1.0x1.0，工具两次窗口枚举均无可定位的 G7 窗口，未截图、未发送输入，测试进程已停止。此项 **未验**；没有引用 G6 截图，也没有将其他应用窗口作为证据。

待明确授权：一次性 Oracle/PostgreSQL 实例验证 catalog/权限/类型、只读事务/快照/锁、断线提交不确定性、源持续变化/并发 DDL/大表性能；原生桌面布局/模态/键盘/100%–150%/多屏；打包应用真实启动、签名/安装升级、远端 CI 与最终发布。既有 SchemaDiffServiceTest 历史偶发 `Schema snapshot failed` 根因未明，保留历史记录，不能因本轮未复现声称修复。

## 最终分支与 main

当前源码/测试/资源/构建清单 768 项，manifest SHA256 `59A5BB92BB234DB7D0983E9C923DAB13DE4371A3B4D475A8940C8FB829482C80`。`git diff --check` 已通过；存在 Git CRLF/LF 提示和既有 unchecked 编译提示，不称零警告。

- branch-full：306 suites / 3809 tests / **3806 passed / 0 failures/errors / 3 live skipped**，3m11s。日志 SHA256 `861078BC3CC181CAE996152F127ACD45564365C51EA757911FAF1CF5A834539B`；XML manifest `157A36ECCFA293B98592DE52F0AA87FB787C2D6CF98FE2567300983BB8C075E0`。
- 三项 skip：Redis live、Oracle SchemaDiff live、PG SchemaDiff live，缺少授权和必要环境，不计为通过。本轮旧 SchemaDiff 偶发未复现，不能声称根因已修复。
- branch-buildSrc：强制 `:buildSrc:test --rerun-tasks`，8/8 passed、0 skipped，7s；日志 SHA256 `4D9F1E86B34203F09C5B919B7043A984A390540711FADFE0820A785DB76B57B6`。
- branch-image：jpackageImage 成功，33s；日志 SHA256 `2FE9525F7800359BCA56F42BFD2FF2DA71AC4C4A230136C1818974F48FC1A147`。cfg 无测试 profile/headless 参数；runtime jar 无 G7 合成夹具和测试类。exe/cfg/modules 摘要见 JSON；保留 JEP 493 提示。
- 审查：768 项源码清单与测试时完全匹配；新复制的证据脚本首次 staged diff 检查发现 EOF 空行，已修正，不涉及产品代码。完整 staged diff 再检查通过后才提交。没有新增驱动、依赖或 SQL 格式化扩展。
