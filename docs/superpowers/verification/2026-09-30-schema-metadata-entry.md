# Schema 直接字段/注释检索：本地验收账本

日期：2026-09-30。维护者“继续推进产品”的限定本地增量；沿用原有安全和集成边界。基线 main `4f53e35d06b078f7c2563382a3907ff35c808dcd`，独立分支 `codex/schema-metadata-entry`。实现 `71430dcb2d72a54064e2b6c5aeeece2538ea47d3`，main 合并代码 `7d5b0434677a2f62ec449e1cea7719dbb517e8bf`；main 新定向、全量、强制 buildSrc、jpackageImage 和审计通过，本轮本地工程交付。不称发布验收，M8 仍待外部验收。

## 改动和边界

Schema 菜单新增“按字段 / 注释查找…”，直接打开现有检索；名称查找内的原入口继续使用。两入口共用现有服务 SQL、独占读取、请求/目标校验和实际 AppShell 结果路由，不另造检索。每次只允许一个字段窗口；连接配置变化（包括改回）、节点脱离、树根替换或关闭立即使窗口和旧选择失效，取消自有读取，底层释放仍可晚于窗口关闭。只读连接允许检索，数据结果继续只读；SELECT 仅生成、DDL 仅显示。

后台不访问 FX 树，配置通知使用既有 `whenChanged`，不检查写许可；监听及订阅在 finally 清理。原生观察发现旧“上一级入口”提示不适用于直接入口，改为明确的 Schema 菜单名称并复查显示。README 同步入口和失效规则。

禁止 .testagent、真实配置/连接/凭据/历史/业务文件；本轮只用 mock、固定合成对象和新独占临时 profile。离线 JDK 25；未推送、fetch、PR、tag、发布、安装更新或外部联系。

## 行为矩阵与证据层级

| 路径 | 当前证据 | 断言和限制 |
| --- | --- | --- |
| Schema 菜单显示/过期捕获项 | 程序化 FX | 菜单渲染不获取 provider/连接；移除、值变化、连接变化、换根、关闭后的直接菜单零读取 |
| PG 两入口，Oracle 直接入口，表/视图三动作 | 实际 AppShell 合成 FX：18 项 | 实际固定 JDBC SQL/参数/超时/上限、原连接、SELECT 原文和不执行、只读标签/两行内容/不可编辑/禁用写按钮、正确 TABLE/VIEW DDL；直接窗口无需名称读取 |
| 取消、配置变化/ABA、节点移除、换根、关闭 | 实际 AppShell 合成 FX：14 项 | 窗口失效关闭，迟到选择不能开标签或读取数据/DDL，资源平衡且写入/执行 0 |
| 请求在途时配置/节点/关闭失效 | 实际 JDBC 控制 + AppShell 合成 FX：4 项 | 取消到达，物理连接释放前 closes=0，窗口关闭且无下游；明确释放后 closes=1/cancel=1，正常关闭平衡 |
| PG Schema 直接入口/单窗口/提示/取消/退出 | 新原生 Computer Use + 实际 AppShell mock | 最终原生窗口与目标可见，mockOpens=1/closes=1、searches/pages/ddls/writes/executions=0；读取为明确展开连接，窗口未检索。实际进程比例 1.0，不代表 OS 缩放测试 |
| 完整原生请求和结果动作、Oracle 桌面 | **未验** | 工具只返回主窗口，模态索引不可用；明确聚焦后输入文字仍未进入。停止重复输入；不能用 FX fire 或表节点动作替代 |

反射仅用于启动 mock provider；测试 `.fire()` 是程序化 FX，截图不能把它升级成原生。原生操作均经 Computer Use；没有 UIA、假窗口句柄或脚本触发产品按钮。

## 新运行记录

实际任务、失败/跳过、日志 SHA、XML 全清单、源码 Git blob 和镜像摘要见 [实际结果](2026-09-30-schema-metadata-entry-results.json) 与 [原始证据清单](evidence/schema-metadata-entry/manifest.json)。分支记录的 HEAD 是基线，验证包含当时未提交改动，最终源码由 source-snapshot.json 固定。

| 运行 | 实际结果 | 说明 |
| --- | --- | --- |
| entry-red | 1 failed | 实际运行证实直接菜单缺失；不是仅编译失败 |
| entry-green | 26 passed / 1 failed | 旧测试要求配置变化后窗口保持打开，与新立即关闭行为冲突；更新为关闭且无结果路由 |
| entry-routing | compileTestJava failed | 构造参数和 lambda 参数重名；没有 test 任务，不能计通过 |
| entry-routing-corrected | 56/56 | 运行修正后的双入口/Oracle/失效矩阵 |
| entry-lifecycle | 79/79 | 增加真实在途取消/物理释放检查及旧生命周期回归 |
| branch-directed-final | 84/84，0 skipped | 最终提示修正后的 5 个定向 suite |
| branch-full | 309 suites，3,879 tests：3,876 passed / 3 skipped | cleanTest 后实际 test；0 failure/error，3 live 不计通过 |
| branch-buildsrc | 8/8，0 skipped | --rerun-tasks，实际 buildSrc:test |
| branch-image / branch-image-audit | BUILD SUCCESSFUL，泄漏 0，connectCalls=0 | 实际 jpackageImage；打包运行时发现 PG/Oracle 驱动 |
| main-directed | 84/84，0 skipped | 合并代码的新 profile，实际 test |
| main-full | 309 suites，3,879 tests：3,876 passed / 3 skipped | 新 profile、cleanTest；0 failure/error；不把 live 跳过计通过 |
| main-buildsrc | 8/8，0 skipped | 新 profile、--rerun-tasks，实际 buildSrc:test |
| main-image / main-image-audit | BUILD SUCCESSFUL，泄漏 0，connectCalls=0 | 合并代码重新构建；PG/Oracle 驱动发现，未载入凭据/用户 profile |
| image-comparison | 三项 SHA 全部一致 | DataCube.exe、DataCube.cfg、runtime/lib/modules；不是正式启动器/安装验收 |

保留的桌面失败：第一次 argfile 的 Windows 反斜杠被解释导致 JavaFX 类缺失；改为正斜杠。隐藏启动无可操作窗口，在任何读取前停止自有进程。第一次可见启动无控制台输出，只有入口/输入失败截图，关闭/资源计数未证实。随后最终 fixture 显式记录合成运行日志，原生打开/取消/正常退出计数通过。首份 fixture 按其记录 SHA 恢复存档，最终 fixture 与最终运行日志另存，不把失败记为成功。

全量三项 live 跳过：Redis 缺显式环境；Oracle/PostgreSQL Schema Diff 缺完整 provider 环境和写门禁。环境在测试进程中剔除，不访问真库。JavaFX unnamed-module/native-access、unchecked 和 jlink JEP 493 等警告单列在原始日志；没有证明历史 SchemaDiffServiceTest 偶发失败的根因已关闭。

## 集成与待验

实现和 main 合并已完成，合并前主工作区干净且仍在基线；源码与实现分支一致，四个定向产品/测试文件的 canonical Git blob 与分支验证快照逐个匹配。main 的 CRLF/LF 差异另记文件 SHA，不混同原始字节与内容等价。审查关注单窗口、身份快照、取消所有权、监听解除、只读/写安全、FX 线程边界和实际断言；没有新增事务/SQL 执行路径。后续证据/交接提交不修改产品源码或测试。

本轮限定增量交付，后续证据及交接提交不改产品源码/测试，main 和独立工作区按授权范围核对干净；不重复运行已通过检查，除非新增变更或失败。完整原生字段请求 → SELECT/只读数据/DDL、Oracle 桌面、真 PostgreSQL/Oracle/Redis 权限/事务/取消、OS 缩放/多屏/全键盘、正式启动器/安装升级/生产签名/远端 CI/真实用户任务/发布仍待验。M8 不称完成，不自动开展外部操作或扩大范围。
