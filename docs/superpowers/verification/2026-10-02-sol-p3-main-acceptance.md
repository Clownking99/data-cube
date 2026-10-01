# DataCube P3：独立审核、main 集成与复验

客户端日期 2026-10-02；工具原始 UTC 日期 2026-10-01，保留原日志时间。本轮独立审查及 main 新 profile 复验通过，最终本地证据提交以 Git 记录为准；完整 M8/发布仍未验。

本轮遵循 [开发与审查计划](../plans/2026-10-02-sol-development-coordination.md)。开发提交 a69f7dbf7a0e0d567f174e7f6ed058ae01d61948；协调分支完成独立源码、图像、日志/XML、权限和原始字节审查后，合并为 669ee536e8d5babba145f1a881d9b6d1a7c0ed99，再本地 fast-forward main。产品源码/测试/构建与原基线不变；没有访问真库或扩大功能范围。详见 [R0–R3 审查记录](2026-10-02-sol-coordination-review.md) 和 [开发交付账本](2026-10-02-sol-p0-p2-acceptance.md)。

## 当前检查点

- 目标：完成原 P3 范围的独立审查、main 集成、新证据和待验交接，不扩展下一轮。
- 改动：本账本、外置复验/审计脚本、原始证据与交接/路线图；产品不改。开发分支经审查本地合并 main，无外部操作。
- 验证：main 新定向 75/75、全量 3916 passed /3 live skipped、强制 buildSrc 8/8、新 jpackageImage /镜像和零连接发现通过。定向/全量各 8 tasks、buildSrc 4 tasks、镜像 14 tasks 均实际执行，未用旧通过或 UP-TO-DATE 替代。
- 失败/未验：开发首轮 SchemaDiffServiceTest 间歇失败保留，根因未定；其后通过不代表修复。协调首轮镜像审计错误地把跨 worktree 的 211 份 Java LF/CRLF 字节差异视为源码变化，已逐份证明仅换行、Git 源码树相同、两边源码工作区干净，保留首轮脚本/日志/JSON。线程工具未返回正式 id，已通过提交/worktree 独立审查；本次不需返工，未伪报消息已下发。发布/M8 未验范围见下方。
- 下一步：完成当前证据/交接的本地提交与 fast-forward main 后按维护者指令暂停 heartbeat；本轮到此交付，后续目标须另行启动。

## 复验方法与证据

本轮 [证据目录](evidence/sol-p3-main/) 的 baseline.json 固定已合并 main 669ee53 和独占 datacube-sol-p3 UUID 临时根。Verify-Main.ps1 在 D:/Projects/朝花夕拾 验证固定 main，设置既有 JDK 25，清除 JVM/Gradle 注入项；使用已审查的 isolation.init.gradle，向 Test JVM 提供每轮新 user.home、显式非 headless 和剔除 live 的环境。单 Gradle 顺序执行，--offline --no-daemon --console=plain --rerun-tasks；定向 6 suites 与开发最终矩阵一致，buildSrc 单独 -p buildSrc clean test。每轮在下一次覆盖前复制全部原 TEST-*.xml，并独立统计。

| main 新执行 | 实际结果 | 原始路径 |
| --- | --- | --- |
| 定向 | 75/75、0 skipped | directed.log / directed-execution.json / directed/ |
| 全量 | 310 suites /3919 总数：3916 passed /3 live skipped /0 failure/error | full.log / full-execution.json / full/ |
| buildSrc | 8/8、0 skipped | buildsrc.log / buildsrc-execution.json / buildsrc/ |
| jpackageImage | SUCCESS，14 tasks 全执行 | image.log / image-execution.json |
| 镜像/零连接发现 | 183 文件；测试/探针/验收 profile/选项泄漏 0；774 源码核验，三项产物 SHA 与开发镜像相同；Oracle/PG driverFor 发现成功，connectCalls=0 | Audit-MainImage.ps1 / image-audit.json / driver-discovery.log |

[实际结果](evidence/sol-p3-main/results.json) 独立重算所有 XML 和 skip 原因。三项产物 SHA：modules 242CE98352FD2F1AE829C3F41CC3B078E05ECAD2AB43ED2B2F84C526AA28AD0E，cfg E53F0D480A7462920E5D0B6DF5E12BB24BBAA011298317A090CA174FBCC6153D，exe 6C32DDB83447C5754B5484B7D0C0F501CF48AD515993F96143388D2B4A32074F。零连接探针没有调用 connect/open，不是正式启动器或真库验收。

## 原始字节与首次审计失败

开发交付 1184 份文件的实际暂存字节已独立逐份比对，提交后 1183 个审计 OID 和 audit 自身均进入精确 HEAD。main 的开发证据 Git 树与 a69f7db 完全相同；1182 项原始 manifest 中 1181 份证据磁盘字节不变，仅目录外 Markdown 账本因默认 checkout 转为 CRLF，其 Git blob 的原 LF SHA/长度仍与 manifest 相同，记录于 delivery-byte-comparison.json。未改写旧 manifest、旧原始证据或用户源码。

镜像首轮原始跨 worktree 比较报 211 份 Java 字节差异，Audit-MainImage-first.ps1 / image-audit-first-failure.json/log 保留。源码 Git 树无差异；使用严格 UTF-8 逐份比对，只替换实际 CRLF/LF 后内容完全相同，开发原始字节仍匹配冻结 SHA。最终审计同时校验两边源码清洁、Git 树相同、每份换行差异及产物 SHA，并另冻结 main 自己的 774 份原始源码字节。没有因误报修改产品/测试/构建或跳过无法证明的差异；仅修外置审计，不重复已通过的工程测试。

本轮 raw-byte-manifest.json 记录 P3 原始文件 SHA/长度，排除自身和 staged-byte-audit 自引用。Audit-P3Staged.ps1 读取实际 Git blob 字节，核对本轮证据及五份更新文档；audit 自身另外用无过滤 hash-object 对照提交对象。文本敏感目标扫描仅针对本轮文件，没有读取或复制原 profile/凭据/SQL 历史。沿用本地用户路径和日志/XML 原始时间，未制造时间一致性。

暂存字节首轮在已编辑的交接 Markdown 因物理换行与 Git 自动规范化不同而中止。staged-byte-audit-first-failure.json 保留异常及五份编辑文档的原始/暂存/规范化 SHA；写回前逐份证明仅 CRLF→LF 后字节与已审阅 Git blob 完全相同，才将这五份已编辑文档统一为 LF，并重做字节核验。不修改产品、旧原始证据或仓库通用属性，不把首轮拒绝当通过。

新原生证据来自已审核开发提交；本 P3 不把 main FX/mock 重跑叫作新原生操作。有效范围为 PG mock 表的字段结果转只读数据/DDL、生产取消/批准、取消剩余/明确重试、在途交互关闭拒绝及夹具入口调用实际 mandatory guard。程序化查询预填/行草稿不是原生输入/编辑；没有新增产品代码来满足工具或归档问题。旧 native/02–36、超时 FAILED_PARTIAL 后旧夹具自行 finish 和首轮镜像误报均继续作为失败历史保留。

## 精确未验

- 字段模态原生输入、完整键盘及结果方向键；Oracle/view/SELECT 原生本轮重跑、原生配置失效/迟到批准。
- 完整 AppShell 在途退出、超时后呈现/恢复、OS/session 强退；mandatory 证据仅来自夹具调用实际 guard。
- 缩小窗口、OS 缩放/多屏、正式启动器、真实安装升级/签名、远端 CI、真实用户任务及发布；M8 不称完成。
- PG/Redis/原 SchemaDiff live、本轮 Oracle 未执行；3 个 live skip 不计通过。既有指定 Oracle 专用表和数据未访问或清理。
- SchemaDiffServiceTest 首次间歇失败根因未定。本轮全部使用 mock/合成 profile/专用临时输出，不读取 .testagent、既有凭据/profile/SQL 历史/业务文件；无 push/fetch/tag/PR/发布/更新安装/外部联系。
