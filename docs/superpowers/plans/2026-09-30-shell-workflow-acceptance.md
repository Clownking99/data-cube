# AppShell 检索流程与 SQL 小窗口：实施检查点

授权：维护者继续推进产品，沿用本地与合成数据边界。范围止于 SQL 小窗口滚动/键盘与真实 AppShell 处理器的字段/注释检索下游流程；发现可复现缺陷才修复。不得读取或修改 .testagent，不访问真实 profile、连接、凭据、SQL 历史或业务文件，不推送/PR/tag/发布/安装更新/联系外部人员。

## C0 — 基线与范围

- 当前目标：补齐上一轮明确待验的两个本地流程，不重复已有实现。
- 基线：main 与复用工作树均为 12e927af6fdc67bf039b3d4121d13fb5b21fc8c6；授权范围干净，未获取远端状态。复用工作树 C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾，新分支 codex/shell-workflow-acceptance。
- 改动：本检查点；独占临时目录 datacube-shell-workflow-7e3c9d0a6c8840a5a18b151793569f29。既有小窗口探针原样编译，desktop classpath 任务本轮成功；此项不是测试通过证据。
- 验证：实际 AppShell 空 profile，输出比例 1.5；原生新建 SQL、合成输入、Ctrl+F 输入并匹配一处、滚至底部、Esc 后追加文本、切换明暗、拖动外层滑块回顶部、扩大/恢复窗口。文本保留，底部草稿控件及说明可达。Alt+F4 正常 shutdown COMPLETED，PID 退出。未执行 SQL、未修改草稿保护或点击清空。
- 失败/未验：可访问性焦点树滞后；原生滚动条不支持 set_value，改用可见滑块；调整尺寸后一次操作被工具拒绝，重新观察后成功。大窗口超过当前桌面工作区，不证明其底部在该尺寸全部可见。完整检索下游尚待执行，真库/多屏/签名与发布仍待外部验收。
- 下一步：仅启动时注入拒绝真实连接和写入的 mock provider；实际 AppShell 的连接树、检索、生成 SELECT、只读数据及 DDL 由原生 UI 操作触发。完成定向、全量、buildSrc、镜像检查后审查、本地提交、合并 main 并复验，更新实际结果。

## C1 — 原生复现与最小修复

- 当前目标：消除实际连接树永远停在加载状态的确定性阻断。
- 改动：ConnectionTreePane 回调归属改为追溯节点所属根与当前隐藏根的身份相同；仍要求相同代数，排除隐藏根自身与脱离节点。新增三项回归：连接/schema/分组接受当代完成、脱离/替换/旧代拒绝、实际异步失败发布及收起再展开重试。
- 验证：修复前实际 AppShell mock 读取完成后仍显示加载中；运行时回归 1/1 失败（回调未发布）。修复后 branch-targeted 实际 6 suites / 45 passed / 0 skipped。原生展开连接得到 demo，展开 schema 得到分组，表分组得到 orders；对象查找得到 1 条名字并打开字段/注释检索。经表节点已有菜单生成绑定合成连接的未执行 SELECT、打开合成 DDL，正常关闭且 mockOpens=2 / mockCloses=2 / ddls=1 / writes=0 / executions=0。
- 失败/未验：初次回归使用不存在的 NodeData.label()，编译失败后改为既有字段；不算运行时红灯，另保留真正运行时失败。初次夹具 Java 参数拆行导致启动失败，已修正保留日志。误写 SqlEditorPaneLayoutTest 未匹配，不计入 45 项。模态控件 index 不可用于缓存输入；嵌套模态仅返回主窗口句柄，两次合成输入未落入检索框，停止重复尝试。searches=0/pages=0，不能宣称字段/注释结果 → SELECT/只读数据/DDL 完整链路完成；表节点 SELECT/DDL 是独立已有入口的证据。启动反射仅注入 mock provider，未用反射/fire 触发原生动作。三个正常桌面进程均退出；首次失败启动无窗口。
- 下一步：核对实际存在的布局测试后重新定向；全量、强制 buildSrc、jpackageImage 与审计，审查后本地集成。完整检索下游原生、真库与发布待验，不扩大为门槛通过。

## C2 — 分支验证与审查

- 当前目标：交付已复现的连接树修复和准确的局部原生证据。
- 改动：源码仅 ConnectionTreePane 7 加/3 删；新增三项回归；保留原始截图、观察树、探针、启动参数、首次失败、实际 XML 摘要及 source-snapshot。没有外部连接或新产品能力。
- 验证：布局定向实际 SqlPanelLayoutIntegrationTest、SqlResultToolbarLayoutTest 两类 40/40；附带误写 SqlEditorPaneTest 未匹配，未计通过。分支 clean test 308 suites / 3838 tests / 3835 passed / 0 failures/errors / 3 live skipped，3m39s；强制 buildSrc 8/8，8s；jpackageImage 39s，镜像驱动发现/零连接审计通过，未发现已知测试参数或新夹具泄漏。
- 失败/未验：红灯和工具限制保持 C1 的原记录；Redis、PG/Oracle Schema Diff live skip 不计通过；完整字段检索下游原生未验。旧偶发 SchemaDiff 原因未明不称修复。JavaFX unnamed-module、unchecked、JEP493 提示保留。
- 审查：根身份覆盖当前根的所有后代，隐藏根自身不加载；移除/重挂替换节点与代数变更仍丢弃迟到回调；失败显示及明确收起重试有实际 FX 集成回归。没有触及写门禁、事务、取消/关闭生命周期或配置格式。main 仍为基线且授权范围干净；源 SHA/blob 绑定提交前实际内容，证据归档按原字节校验。
- 下一步：本地提交并合并 main，新 profile 定向/全量、强制 buildSrc、镜像/审计复验后补实际集成 SHA、最终证据与待验项。

首次暂存空白检查指出原始 ShellDiscoveryDesktopProbe.java 尾部空行；仅为该证据源文件添加局部 blank-at-eof 例外，保留已编译/运行的原始字节，产品源码不适用例外。随后重做归档清单、暂存和空白检查；首次失败未抹去。
