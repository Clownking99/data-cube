# 连接树懒加载修复与小窗口原生补证

维护者要求继续推进产品。本轮从 main 12e927af6fdc67bf039b3d4121d13fb5b21fc8c6，在 codex/shell-workflow-acceptance 独立工作树复现并修复连接树一直显示“加载中”的缺陷。实际 AppShell 的完成回调把节点追到隐藏根后错误地检查根的子列表，成功/失败均被丢弃。现在要求所属根与当前隐藏根相同且代数未变；脱离、替换、旧代回调仍拒绝。没有修改连接身份、安全策略、事务、SQL 执行或持久化格式。

检查点见 [实施记录](../plans/2026-09-30-shell-workflow-acceptance.md)。本增量本地工程完成：实现与分支证据提交 7eec75c21fc8ae60bdb4d3c5a87c82d8ce62937f，本地 main 合并 8b890175712835b5b8c2ea0a57f3b069a717873c，新定向/全量/buildSrc/镜像复验通过。集成后源码 Git blob 与分支测试内容一致；最后跟进只更新文档与证据，不冒充另一套测试执行。M8 仍待外部验收。

## 原生证据与边界

三个正常桌面进程均使用全新独占 profile，Computer Use node_repl / sky 输入；两个 AppShell 检索进程仅在启动时反射注入 mock provider，所有实际动作由原生 UI 输入触发，没有以反射或 fire 代替用户点击。夹具与原始截图/状态/日志见 [归档](evidence/shell-workflow-acceptance/manifest.json)，完整参数/计数/产物摘要见 [本轮结果](2026-09-30-shell-workflow-acceptance-results.json)。实际计数见 stdout，不能把未用到的能力当作验收通过。

| 范围 | 实际结论 | 状态编号 |
| --- | --- | --- |
| SQL 小窗口 | 实际 AppShell 空 profile，输出比例 1.5；原生新建脚本、合成 SQL、Ctrl+F 输入并匹配一处、滚到底部，草稿控件及说明可达；Esc 后追加文本确认焦点返回，明暗切换、拖动滑块回顶部、调整并恢复尺寸后 SQL 保留 | 01–20 |
| 缺陷复现 | 原始产品代码下 mock 元数据读取结束，连接仍显示加载中；正常关闭 mock 1 开/1 关，无执行/写入 | 21–24；before-fix stdout |
| 修复后的连接树 | 展开连接得到 demo，schema 展开得到分组，表分组得到 orders；经真实 schema 菜单打开对象查找并读取 1 条名称、打开字段/注释检索 | 25–29、37–38 |
| 嵌套模态输入 | 可访问性 index 未进入输入缓存；工具只列出主窗口句柄。首次输入未出现，重新明确聚焦后重试仍未出现；停止重复输入，正常取消两个对话框 | 30–36 |
| 表节点 SELECT | 经表节点已有菜单进入实际 AppShell，生成 SELECT * FROM "demo"."orders";，绑定合成连接且尚未连接/执行 | 39–41 |
| 表节点 DDL | 经表节点已有菜单进入实际 AppShell，显示合成 DDL；随后正常关闭 | 42–45；after-fix stdout |

最后 AppShell 进程计数：mockOpens=2、mockCloses=2、ddls=1、searches=0、pages=0、writeAttempts=0、executionAttempts=0；退出为 SHUTDOWN_COMPLETED。所有连接工厂为 mock，不含 DriverManager 路径，固定元数据 SQL 以外的 JDBC 调用拒绝。源路径约束不是网络抓包证据。首次 Java 参数拆行造成启动失败也保留，不能算第四个成功桌面用例。

输出比例 1.5 是进程参数/输出，并非 OS 缩放设置。大窗口超出当前工作区，截图不证明其底部完全可见。滚动条不支持 set_value，改用可见滑块；一次尺寸变化后输入被工具拒绝，重新观察后成功。焦点可访问性信息有滞后，SQL 输入结论以实际文字/截图为准。未点击草稿保护或清空动作、未运行 SQL、未打开真实 profile。小窗口原生截图在修复连接树前取得（只改连接树），不冒充 main 修复后的桌面重测。

**字段/注释结果 → SELECT、只读数据、DDL 的完整原生链路仍待验。** 表节点 SELECT/DDL 是独立入口证据；没有字段搜索结果，也没有数据页，本轮不称完整 AppShell 检索验收完成。OS 多屏、完整键盘遍历、真库权限/事务/取消、正式启动器/安装升级/签名/远端 CI/目标用户任务与发布仍待授权或人工验收。

## 回归与本轮验证

新增 ConnectionTreeLazyLoadTest；复现测试先在旧实现上运行失败，随后修复通过。第一次使用不存在的 NodeData.label() 导致编译失败，另存记录，不算行为红灯。定向使用不存在的 SqlEditorPaneLayoutTest / SqlEditorPaneTest 的过滤项不计通过；实际 XML 分别为 6 类/45 项与 2 类/40 项，布局只计 SqlPanelLayoutIntegrationTest 和 SqlResultToolbarLayoutTest。

| 行为 | 回归证据 |
| --- | --- |
| 当前连接/schema/分组接受完成；隐藏根/null 拒绝 | attachedConnectionAndNestedObjectsAcceptCurrentCompletion |
| 脱离节点、被替换根、旧代完成拒绝 | detachedReplacedAndStaleNodesRejectCompletion |
| 实际挂载节点显示异步失败，收起再展开只重试一次，零网络工厂调用 | attachedFailureIsPublishedAndCollapseReexpandRetries |

测试采用 JDK 25.0.1+8 / JavaFX 25 / Gradle 9.2.0、offline、独占 user.home、headless=false，移除 DATACUBE_REDIS_* / DATACUBE_SCHEMA_DIFF_*。测试日志中的源 HEAD 为提交前基线，source-snapshot.json 绑定本轮实际修改文件的 SHA-256/Git blob；最终集成会核对源码完全一致。跳过不计通过，编译与 classpath 任务不计测试。

| 执行 | 本轮结果 |
| --- | --- |
| branch-regression-red | 编译失败，未执行测试；已纠正测试字段访问 |
| branch-regression-red-runtime | 1 项执行 / 1 failure，附着节点回调未发布，预期复现 |
| branch-targeted | 6 suites / 45 passed / 0 skipped，14s |
| branch-layout | 2 suites / 40 passed / 0 skipped，14s |
| branch-full | clean test：308 suites / 3838 tests / 3835 passed / 0 failures/errors / 3 live skipped，3m39s |
| branch-buildSrc | --rerun-tasks：8/8 passed / 0 skipped，8s |
| branch-image / runtime | jpackageImage 成功，39s；cfg/modules 无已知测试参数或夹具泄漏，Oracle/PG 驱动可发现，connectCalls=0 |
| main-targeted | 合并 8b89017 后的新 profile：8 suites / 85 passed / 0 skipped，20s；全部过滤项匹配实际存在的类 |
| main-full | 8b89017 新 profile clean test：308 suites / 3838 tests / 3835 passed / 0 failures/errors / 3 live skipped，3m34s |
| main-buildSrc | 8b89017 的 --rerun-tasks：8/8 passed / 0 skipped，8s |
| main-image / runtime | 8b89017 的 jpackageImage 成功，31s；cfg/modules 无已知测试参数或夹具泄漏，Oracle/PG 驱动可发现、connectCalls=0；exe/cfg/modules 三项 SHA-256 与分支相同 |

审查范围包括隐藏根归属、代数/脱离拒绝、失败可重试、mock 边界、原始证据与结论一致、测试/合成入口不泄漏到正式镜像；常规修复自主决定，不引入新产品能力或 SQL 美化主线。最终以本轮实际日志/结果 JSON 和源文件身份核对交付。不推送、不建 PR、不改 tag、不发布、不安装更新、不联系外部人员，不称发布验收完成。

三个 live skips 为 Redis、Oracle Schema Diff、PostgreSQL Schema Diff，缺少明确授权环境和写入门禁，因此跳过，不计通过。全部 XML 留存在独占临时目录 C:/Users/hetia/AppData/Local/Temp/datacube-shell-workflow-7e3c9d0a6c8840a5a18b151793569f29；仓库保留完整 XML 摘要清单与相关 XML。JavaFX unnamed-module、既有 unchecked 与 jlink JEP 493 提示均保留，不称零警告。

复现夹具：先将 helper/Java 源复制到新的独占临时目录，用 desktop.gradle 的 g8DesktopClasspath 输出测试 classpath，javac 编译探针。小窗口探针要求全新的 sql-small-window-profile；主壳探针要求全新的 shell-discovery-profile（复跑放在新的父目录），均在初始化 AppShell 前拒绝已有 .datacube。Java @args 的路径使用正斜杠，user.home 参数必须在同一行。运行 ShellDiscoveryDesktopProbe$Launcher，Startup mock 注入只提供合成 target；全部 UI 动作另行通过原生工具。不得以测试回调/fire/反射冒充原生操作。正式镜像不加载夹具或合成 profile。

审查结论：实现只修复回调归属，新增三项回归；不存在需求外的安全策略或 SQL 执行改动。main 与分支源码/测试/资源/构建目录 Git 内容相同，绑定见 checks/integration-binding.json。首次证据空白检查因夹具原始尾部空行失败，仅给该证据源文件设置局部属性后重新通过，原始字节未改；完整经过保留在检查点。没有删除旧失败记录，也没有将跳过或无法输入的模态用例称为通过。

最终证据：13 个执行记录、198 个归档清单项（不含清单自身）、59 张原始截图 / 45 份桌面状态确认；SHA-256 与原始暂存 blob 逐项核对。最后跟进没有改产品代码，故不为文档修改重复执行或虚构测试；本轮实际验收源码为 main 合并 8b89017。
