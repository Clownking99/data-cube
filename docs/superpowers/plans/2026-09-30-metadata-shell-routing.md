# 字段检索下游 AppShell 路由：检查点

授权：继续推进产品，沿用合成 profile、mock、临时目录和本地提交/合并边界；不读取或修改 .testagent，不访问真实连接、凭据、历史、业务文件，不推送、PR、tag、发布、更新或联系外部人员。

## C0 — 基线与范围

- 当前目标：通过实际 AppShell 处理器验证字段检索结果的 SELECT / 只读数据 / DDL 路由，修复只读表页面被标成视图的问题。
- 基线：main a28eb79d8dbdf29530ea564b24c2294a7c9224fc，授权范围干净；复用干净工作区，新分支 codex/metadata-shell-routing 从 main 创建。
- 改动：本检查点；定向采用既有 Java/JUnit/FX 约定，不增加测试代理目录或子代理。
- 验证：读取实际路由发现 openDataGrid 以 readOnly 判定视图标题，EditableGridModel 强制只读提示同样写死视图；尚待运行时红灯。
- 失败/未验：上一轮原生嵌套模态输入限制仍在，自动化 FX 调用不算原生证据。临时助手复制时曾引用不存在的 driver probe 路径，已定位实际 g7 路径；该失败不算验证通过。
- 下一步：实际模态检索/结果选择/主壳标签的合成 FX 回归，确定红灯后最小修复，定向/全量/buildSrc/镜像验证、审查、本地集成与 main 复验。完整原生流程、真库与发布仍单列待验。

## C1 — 实际 FX 复现与修复

- 当前目标：准确显示只读页面状态，并证明真实下游处理器在表/视图下的行为。
- 改动：AppShell 只读标题由“视图”改为“数据（只读）”；EditableGridModel 强制只读提示改为“当前数据页为只读”，各一行文案，无安全/事务/查询/关闭逻辑变化。新增 MetadataSearchShellRoutingTest，实际 schema 菜单、对象查找、固定 JDBC 字段检索、结果动作、AppShell 标签及关闭；表/视图三动作共 6 例，加取消选择/目标变化 2 例。
- 验证：routing-red-confirmed 6 例仅 DATA 两例标题失败；仅修正标题后，补齐 mock isValid，routing-hint-red 两例只读提示失败；修正提示后 routing-green 8/8，0 skip。SELECT 文本/连接绑定、无自动建连/执行，DATA 两行/不可编辑/写控件禁用，TABLE/VIEW DDL 类型路由，取消与同 id 配置变化阻止旧选择，shutdown COMPLETED/所有 mock 开关平衡/写入与执行 0。
- 失败/未验：最初在 Scene 前 lookup 失败；身份不一致导致超时，持久化补默认 props 后夹具必须共享相同快照，未放宽产品身份检查；首个 SELECT 的 Windows native font DLL 落在 per-case 临时目录使 JUnit 删除失败，改为在隔离 worker profile 提前初始化字体；标题修正后的首次数据验证缺少 mock isValid，补齐后才得到真正提示红灯。所有尝试保留。自动化 fire 与测试内反射仅是 FX 集成，不能当原生证据；schema 节点为合成附着，未冒充懒加载重测。Oracle SQL/真库及完整原生流程仍未验。
- 下一步：加强合成数据值及 DDL 只读断言后跑定向、全量、强制 buildSrc、jpackageImage/审计，完成审查、本地提交合并及新 profile main 复验。

## C2 — 分支验证与审查

- 当前目标：交付两处准确的只读页面说明及实际下游自动化链路，保持原生待验边界。
- 改动：源码两行文案；新增 8 例的单一 AppShell 路由集成测试，ledger/result/原始证据归档。实际源码/测试 SHA 和 Git blob 绑定 source-snapshot，15 个执行记录、76 个归档清单项（不含清单自身）。
- 验证：实际定向 8 suites / 70 passed / 0 skipped；全量 309 suites / 3846 tests / 3843 passed / 3 live skipped / 0 fail/error，3m16s；强制 buildSrc 8/8，7s；jpackageImage 34s，正式镜像无已知测试类/参数泄漏，打包 PG/Oracle 驱动发现 connectCalls=0。
- 失败/未验：9 个失败尝试及夹具错误/真正产品红灯均保留；三项 live skip 不计通过；完整原生、Oracle 本轮直接路由、真库及发布仍待验。镜像模式匹配审计不等于网络捕获或发布验收。
- 审查：表/视图三动作各有真实主壳及服务协作断言，含页面原始值和控件状态；同 id 配置变化仍在结果事件内重新拒绝。没有新连接工厂、外部调用、生产测试入口、存储格式变化或安全放宽。test Window 操作仅自身 owner 链，不关闭其他窗口；每例恢复 user.home。main 仍为基线且授权范围干净。
- 下一步：按 source-snapshot 与 raw manifest 校验暂存内容，提交分支，本地合并 main；新 profile 定向/全量、强制 buildSrc、镜像与审计复验，再更新实际集成 SHA 与待验项。
