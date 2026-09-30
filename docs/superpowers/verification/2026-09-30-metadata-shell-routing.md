# 字段检索下游路由与只读页面说明

维护者继续推进产品，沿用本地和合成数据边界。本增量从 main a28eb79d8dbdf29530ea564b24c2294a7c9224fc，在 codex/metadata-shell-routing 独立工作区实现。字段检索可以对表打开只读数据页，原来的 AppShell 标题与强制只读提示却把只读一概叫作“视图”。现在分别显示“数据（只读）”与“当前数据页为只读”。产品源码仅两行文字修改，没有改变对象身份、查询、写门禁、事务、取消、关闭或配置格式。

## 实际 FX 链路证据

新增 MetadataSearchShellRoutingTest 从真实 schema 菜单进入对象查找，再打开字段检索；真实 SchemaObjectCatalog 与 SchemaMetadataSearch 使用严格 mock provider/固定 JDBC 查询，真实结果按钮通过 ConnectionTreePane 路由至 AppShell 的 SQL、DataGrid 与 DDL 标签处理器。合成连接固定 example.invalid:1、空凭据，工厂没有 DriverManager 路径；未知操作拒绝。每例在全新 @TempDir 中构造 AppShell 所有存储，退出等待 shutdown COMPLETED 后恢复 worker profile。原生字体在隔离 worker profile 提前初始化，避免 Windows DLL 被 per-case 清理误当泄漏。

节点为合成附着的连接/schema，不扩展懒加载；配置与 manager 必须同一快照，未放宽身份检查。测试通过 Window 监听、控制事件 fire 与启动时反射注入 mock，这些是**程序化 FX 集成测试，不是原生桌面验收**。本轮没有新 Computer Use 输入或截图，上一轮的嵌套模态输入限制不重复冒充完成。

| 要求 | 精确证据 |
| --- | --- |
| 表/视图结果 → 绑定连接的未执行 SELECT | metadataResultOpensBoundPassiveOrReadOnlyShellTab(TABLE/VIEW, SELECT)：完整 SQL 与连接徽章，目录/搜索两条独占连接外没有建连，pages/ddls/writes/executions=0 |
| 表/视图结果 → 准确只读数据页 | 同名参数例 TABLE/VIEW, DATA：标题和提示准确，两行原始值 101/202、grid 不可编辑、新增/删除/保存控件禁用，pages=1、写入/执行=0 |
| 表/视图结果 → 正确 DDL | 同名参数例 TABLE/VIEW, DDL：tableDdl/viewDdl 身份断言、实际合成文本、只读 CodeArea，ddls=1，数据/写入/执行=0 |
| 取消结果或配置变化不进入下游 | cancelledOrChangedTargetCannotOpenDownstreamShellTab(cancel/change)：实际检索后取消或同 id 改配置，再点旧 DATA 仍不路由，无标签/数据/DDL/写入/执行 |
| 正常关闭释放连接 | 每例 AutoCloseable 等待 COMPLETED；SELECT/取消/变化为 2 开/2 关，DATA/DDL 为 3 开/3 关；最终 writes/executions=0 |

行为 assertions 和 XML 中 ROUTING/CLOSED 计数相互核对。输入本身不会发远程元数据读取，只有明确查找执行固定查询。测试同样不代表网络抓包证据，不证明真库驱动、权限、事务或取消兼容性。PG SQL 路由为本轮范围；Oracle 路由未新增本轮直接证据。

## 验证和失败历史

本轮实际记录见 [结果](2026-09-30-metadata-shell-routing-results.json)，[原始归档](evidence/metadata-shell-routing/manifest.json)，[检查点](../plans/2026-09-30-metadata-shell-routing.md)。分支验证 HEAD 仍为起点，实际未提交代码 SHA/Git blob 由 source-snapshot 绑定后续实现提交；不能把起点 HEAD 当已经包含改动。

- 初始 lookup 在 Scene 前失败；其后三轮模态超时来自手工节点配置与持久化补齐安全 props 后的 manager 配置不一致。修复夹具快照后才获得真正产品红灯。
- Windows 字体 DLL 被解压进第一例 @TempDir，测试行为通过但 JUnit 删除失败；提前在隔离 worker profile 初始化字体解决。未删除真实缓存或放宽 JUnit 清理。
- routing-red-confirmed：6 例、4 passed、2 DATA 标题失败。只修标题后 routing-reason-red 由于 mock 缺少 isValid 而未加载数据；补齐后 routing-hint-red 才确认为只读提示失败。所有失败都归档，不以夹具失败代替产品红灯。
- routing-green：新增 8/8。随后加强实际行值与 DDL 只读断言，branch-targeted 实际 8 suites / 70 passed / 0 skipped；branch-full clean test 309 suites / 3846 tests / 3843 passed / 0 failures/errors / 3 live skipped，3m16s；强制 buildSrc 8/8，7s。

分支 jpackageImage 34s 成功；正式镜像无已知测试类或测试参数泄漏，PG/Oracle 驱动发现 connectCalls=0。按两行文案、目标快照保护、测试隔离和关闭平衡完成差异审查；15 个执行记录与 76 个归档清单项（不含清单自身）。main 集成复验待执行，当前不宣称交付结束。Redis live 及 Oracle/PG Schema Diff live 共三项缺少外部授权/环境而跳过，均不算通过。已有 JavaFX unnamed-module、unchecked、JEP493 等提示按原始日志保留；旧 SchemaDiff 偶发失败原因未明，不称本轮修复。

## 单列待验

完整字段/注释结果 → SELECT/只读 DATA/DDL **原生**链路、完整键盘遍历、OS 缩放/多屏、Oracle 直接路由、真实数据库权限/事务/取消、正式启动器/安装升级/生产签名/远端 CI/真实用户任务/发布仍未完成。M8 仍待外部验收；本增量交付不意味着发布验收完成，也不自动扩展产品范围或请求外部操作。
