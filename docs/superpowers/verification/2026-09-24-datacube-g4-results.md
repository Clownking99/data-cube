# G4/M4 本地实施与验收账本

状态：进行中。范围是用户 2026-09-24 明确授权的 M4a–c；M4d 跨批次固定结果按路线图保留为后续增量，不自动进入 M5–M8。设计见 [G4 计划](../plans/2026-09-24-g4-result-continuity.md)。

## 基线与隔离

- main 基线 `42e4aff83c9496cb0dfa768aeba4f4d90cc10e96`；独立 worktree `C:\Users\hetia\.codex\worktrees\datacube-g4-result-continuity\朝花夕拾`，分支 `codex/datacube-g4-result-continuity`。
- JDK `D:\jvms_v2.1.6_amd64\store\jdk-25.0.1+8`，Gradle wrapper 9.2，离线执行。
- 独占原始证据：`C:\Users\hetia\AppData\Local\Temp\datacube-g4-fb23e6cdc9f3490ab76f56b628b72b8e`。`run-check.ps1` 按运行名称归档日志、XML、计数、skip 原因和 SHA-256；后续运行不覆盖前轮。
- init script 只为 Test 配置 `user.home=DATACUBE_G4_PROFILE`、`java.awt.headless=false`，并移除 Test 进程的 `DATACUBE_REDIS_*` / `DATACUBE_SCHEMA_DIFF_*` 环境入口。正式镜像命令不带 init script。没有修改系统环境或 JAVA_TOOL_OPTIONS。
- 没有访问 `.testagent/`、真实连接/SQL 历史/业务文件/凭据；没有网络数据库、推送、tag、PR、安装更新或发布。

## 检查点 1：M4a 状态保留

目标：按 occurrence 保存视图。原实现的 `g4a-red` 为 1 test / 1 failed，复现 A/B/A 丢失筛选。首次关联 `g4a-first` 为 430 tests / 427 passed / 3 failed：一个旧断言期待重置筛选，两个断言暴露替换筛选状态对象的兼容问题。修正为原 ResultFilterState 内的不透明 SavedView，恢复时单调递增请求 generation；旧回调无效。

改动：列用源下标，行用源位置，结果用 Choice 身份。缓存只保存值类型布局和筛选快照，不保留 TableColumn/单元格控件。保存筛选、排序、列顺序/宽度/可见性、选区/焦点和双向滚动；新批次、显式数据库筛选结果和关闭清理缓存。数据库筛选成功时释放旧批次，失败保留旧结果以供恢复。

失败/未验：上述失败保留；原生手动键盘、主题和缩放未验。下一步：执行及保留预算、增量发布。

## 检查点 2：M4b–c 及行为证据

默认每次执行最多保留 **999 条执行结果 + 1 条未执行提示**、**50,000 行**、**250,000 单元格**、**每结果 256 列**、**4,194,304 个 UTF-16 文本单元**。单值文本 4,096 单元，二进制只读取前 64 字节；列名 512、类型名 128、列注释 512、SQL/错误详情 16,384 单元，均计入执行文本预算。概览另有已有的 1,000 项/1 Mi 文本预算。控制提示和固定预览标记为固定上限的额外开销。

- Oracle/PG 的脚本都经同一个串行循环；预算耗尽停止后续语句，提示起始序号和未执行数量。不会为显示结果重跑或并行执行。既有提交、回滚、错误策略和取消控制仍由会话负责。
- LOB/宽文本及列注释用有界流读取；复合值不递归抓取整个对象图，明确显示未保留。预览是独立值类型，SQL 导出拒绝将其当作完整标量。含预算省略的结果不能用截断 SQL 做数据库筛选。
- 不变的完成前缀通过 mailbox 发布，最多一个未消费 FX 回调；已有结果与控件不随每个事件重建。取消、关闭、新批次关闭旧 mailbox。完成时保留用户正在浏览的结果与筛选。
- 保留阶段：每个编辑器只持有当前批次的有界结果，视图缓存引用原数据。FX 表格只复制当前可见结果的引用。显式数据库筛选为失败恢复最多同时持有旧结果/新结果两份预算，成功后释放旧批次；不提供跨批次固定快照。
- 这是应用保留的数据量上限，不是整个 JVM 堆字节上限；驱动内部缓冲、SQL 编辑器原文本、JavaFX 控件开销不作该数值保证。没有增大 JVM 堆。真实驱动抓取/取消仍待真库授权验证。

| 行为 | 可复核测试 |
| --- | --- |
| 重复 SQL/列/等值行的独立筛选、布局、排序、选区与新批次释放 | `SqlBatchViewStateTest.duplicateOccurrencesPreserveFiltersColumnsSortAndEqualRowSelection` |
| 双向滚动及关闭缓存释放 | `SqlBatchViewStateTest.scrollPositionsSurviveSwitchAndCloseReleasesCachedViews` |
| 保留原过滤值、拒绝旧数据库回调 | `ResultFilterSavedViewTest.savedViewKeepsRawFiltersButInvalidatesOldDatabaseCompletionTokens` |
| 百万行、十万列、十亿字符合成 LOB，零 getObject 抓取、有限读取和资源关闭 | `SqlResultBudgetTest.wideResultsStopReadingAndStopLaterStatementsWithExplicitEvidence`（Oracle/PG） |
| 预算后不执行隐藏写入，无自动事务收尾 | `SqlResultBudgetTest.resultCountBudgetDoesNotExecuteHiddenWritesAndPublishesEachCompletion`（Oracle/PG） |
| 批次结果上限包含未执行提示，未被 UI 上限隐藏 | `SqlResultBudgetTest.defaultBatchStopsBeforeTheOverviewCouldHideItsBudgetNotice` |
| 注释、数值、Unicode、SQL 摘要及总文本预算 | `SqlResultBudgetTest.optionalColumnCommentsHaveRowAndTextBoundsAndAnOmissionNotice` / `scalarAndAnnotationTextShareAllowanceAndPreviewCannotSplitASurrogate` / `textAllowanceSpansResultsAndSqlSummariesWithUnicodeSafePrefixes` |
| 第二条阻塞时已能浏览第一条；取消/关闭/新执行正确 | `SqlIncrementalResultsTest.firstResultIsBrowsableWhileSecondIsBlockedAndLateResultsRespectLifecycle` |
| 10,000 次生产只排一个 UI 回调、旧批次失效 | `SqlProgressMailboxTest.slowUiHasOnePendingCallbackAndReceivesLatestCompletedPrefix` / `oldBatchCallbacksCannotRenderIntoANewBatch` |

中间结果：`g4ab-first` 为 313 tests / 1 failed，失败是概览 Entry 的“仅标量和有界 Text”约束；将省略提示改为有界 Text 后 `g4abc-compile` 358/358 通过。`g4-budget-first` 12/12、`g4-incremental-first` 12/12、`g4-targeted-review` 1902/1902、`g4-review-fixes` 590/590、`g4-targeted-final` 22/22，均 0 skipped。这些是各自时点证据，最后的注释流读取审查修正后还需最终全量。

审查修正：追加结果保持旧 Choice 和概览 Entry 身份；取消后的摘要清除“执行中”；关闭邮箱使用局部快照避免跨线程空引用；SavedView 不对外暴露原始过滤值；大数值与列注释也计入文本预算；列注释单独显示省略原因。

下一步：通过最终分支全量、fresh buildSrc 和 jpackageImage，审查提交，本地合并 main 并复验。未完成之前不标记本地工程完成。

## 检查点 3：分支最终验证与提交

- 当前目标：完成 G4 分支本地门槛并准备 main 集成。实现提交 `f7fa9bcffae0f72de4a8856b2348cc70298094d0`；代码/测试审查及 `git diff --check` 通过。
- 最后审查修正后的 `g4-bounded-comments`：39/39 通过，0 skipped；默认数量上限、Unicode、数值和注释预算的核心回归 `g4-targeted-final` 22/22 通过。
- `g4-full-final`：`gradlew.bat clean test --offline --no-daemon --console=plain --init-script <scratch>/isolated-tests.gradle`，2m20s，283 suites / 3659 tests / **3656 passed** / 0 failures / 0 errors / **3 existing live skips**。
- `g4-buildSrc-final`：`:buildSrc:test --rerun-tasks` 加相同离线/隔离参数，7s，4 tasks 实际执行，**8/8 passed**，0 skipped。
- `g4-image-final`：`jpackageImage --offline --no-daemon --console=plain`，32s，exit 0。检查 DataCube.cfg 没有合成 profile、headless 或验收入口；EXE/config/modules 摘要归档为 `g4-branch-image-manifest.json`。未启动安装或更新。
- 分支测试发生在尚未提交的修改上，日志里的 HEAD 为基线；随后原样提交为上述实现 SHA，没有在测试后改源码。机器可读结果明确区分这一事实与之后 main 的已提交 SHA 复验。
- 失败/未验：最终分支无失败；保留前述首轮失败。3 skips 分别为 Redis standalone、Oracle Schema Diff live、PostgreSQL Schema Diff live，均因未启用真实连接前提跳过，不能算通过。编译 unchecked/JEP 493 提示保留。
- 下一步：确认 main 仍为 `42e4aff` 且干净，本地合并并在独立 `profile-main` 重新运行全量、fresh buildSrc、镜像。此时尚不宣称 main 复验完成。

## 未执行验收

- 原生桌面肉眼/键盘/明暗主题/缩放验收；自动 FX 测试不替代此证据。
- 真 Oracle/PG 的驱动抓取、LOB、取消、事务和服务器资源行为；既有 Redis/Schema Diff live skips 不算通过。
- 安装/升级/恢复、签名凭据、远端 CI 与发布。
- 历史 SchemaDiff 偶发失败根因仍未明确；未复现不等于修复。保留已有 unchecked 编译提示和 jlink/JEP 493 提示。
