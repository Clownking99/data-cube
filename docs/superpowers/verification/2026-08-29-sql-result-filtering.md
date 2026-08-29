# SQL Result Filtering Verification

## Scope

本次验收覆盖 SQL 结果元数据与原始值保留、截断事实、本地搜索和类型化条件、安全的
provider SQL 渲染、参数化会话执行、JavaFX 紧凑工具栏、非破坏性失败、TSV 复制，以及
编辑器生命周期回归。实现审阅范围固定为
`6962807ad17ec6587541e8fc9f18155a2506e491..e2a07e1b0d904ceae03d63904b463efd36f2a065`；
不使用相对提交范围。验收文档的首个独立提交为
`ac2da54643590d746b18f91f6164632b11c801d3`，不属于上述实现范围。

## Commands and observed results

环境：2026-08-29（Asia/Shanghai，UTC+08:00）；Windows 11 10.0 amd64；PowerShell
7.6.4；Temurin OpenJDK 25.0.1+8 LTS；Gradle 9.2.0；工作目录
`D:\Projects\朝花夕拾`。为实际运行 JavaFX 用例而非 headless 运行，先在同一 PowerShell
会话执行下列可复制命令；Gradle 和 `:test` 输出均确认
`Picked up JAVA_TOOL_OPTIONS: -Djava.awt.headless=false`。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
./gradlew test --tests "com.datacube.spi.model.QueryResultMetadataTest" --tests "com.datacube.sqleditor.result.*" --tests "com.datacube.provider.*.*ResultFilterSqlRendererTest" --tests "com.datacube.provider.jdbc.JdbcPreparedQueryExecutorTest" --tests "com.datacube.fx.SqlResultToolbarTest" --tests "com.datacube.fx.SqlEditorResultFilterContractTest" --no-daemon --console=plain --rerun-tasks

$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
./gradlew clean test --no-daemon --console=plain
```

| 时间（UTC+08:00） | 实际命令 | 用例结果 | 用时 | 构建结果 |
| --- | --- | --- | --- | --- |
| 2026-08-29 21:23:59–21:24:17 | 上述聚焦命令（含 `--rerun-tasks`） | 142 total；0 failures；0 errors；0 skipped | 18.041 s（Gradle 显示 17 s） | `BUILD SUCCESSFUL` |
| 2026-08-29 21:24:32–21:24:55 | 上述 `clean test` 命令 | 980 total；0 failures；0 errors；3 skipped | 23.422 s（Gradle 显示 23 s） | `BUILD SUCCESSFUL` |

聚焦集由当时 10 个 JUnit XML suite 汇总；总计 142 项，且没有跳过项。随后运行的
`clean test` 是最后一个测试命令，因此当前 `build/test-results/test` 保留的是可复核的
全量证据：126 个 suite、980 项、3 项跳过。聚焦计数以该次已记录的命令输出和即时 XML
汇总为依据，不声称当前 XML 仍保存聚焦集。

完整集的 3 个跳过均为现有显式 assumption，而非失败：

- Redis live smoke：未设置运行真实 Redis smoke 所需的主机与密码环境变量。
- Oracle Schema Diff live smoke：缺少显式写入门禁及完整 provider 环境。
- PostgreSQL Schema Diff live smoke：缺少显式写入门禁及完整 provider 环境。

一次未带 `--rerun-tasks` 的聚焦调用被 Gradle 判为 `UP-TO-DATE`，不作为实际运行证据；
上表使用其后的强制重跑结果。所有表中命令均通过上述 `JAVA_TOOL_OPTIONS` 环境变量强制
非 headless JVM。

## Safety assertions

- 本地全文搜索和类型化条件仅在已加载的结果行上计算；工具栏不会隐式提交数据库筛选。
- 数据库筛选需要用户点击，并仅接受可证明安全包装的单条只读 SELECT。多语句、非 SELECT、
  `WITH`、集合运算、`INTO`、`FOR UPDATE`/`FOR SHARE`、无法安全解析的结构、检测到的
  可能有副作用调用，以及空/重复结果列标签都会被拒绝。
- 渲染器只引用结果元数据中的列标签并进行方言引用；用户筛选值以 JDBC 参数绑定。参数的
  文本表示为脱敏值，审阅、测试输出和本记录均未写入参数实际值。
- 筛选查询通过现有拥有的 `JdbcEditorSession` 和 `SerialSessionOperationQueue` 执行；未建立
  第二个 JDBC 连接。错误、超时、取消或结果列契约不符会保留当前展示结果。
- 截断仅在读取上限后实际观察到额外行时显示；恰好达到上限不会被误报为截断。
- 复制入口覆盖当前单元格、选中区域、选中行、以及含表头的选中行，并生成 TSV；复制不触发
  JDBC 查询。
- 发布前检查确认 `build/`、`build/test-results/` 和 `build/reports/` 均被 Git 忽略，未加入
  生成物、测试日志或报告。对固定范围、README 和本记录的 JDBC URL、密码、API key、token、
  私钥模式扫描未发现凭据；少数 `token` 词法命中经审阅为 SQL 词法/执行控制或测试文字，
  不包含认证材料。

## External integration status

本次没有执行真实 PostgreSQL 或 Oracle 的数据库筛选。测试环境未提供可用于此功能的受控
连接、凭据或可清理的数据库对象；同时完整集中的 relational live smoke 已因缺少显式写入门禁
和完整 provider 环境而跳过。因而，本记录仅将单元/契约测试、基于记录 JDBC 的参数化执行测试、
provider SQL 渲染测试和 JavaFX 实际运行作为本地证据，不把它们表述为真实数据库筛选验证。

## Reviewed commits

实现验收范围：`6962807ad17ec6587541e8fc9f18155a2506e491..e2a07e1b0d904ceae03d63904b463efd36f2a065`。

| Task | 交付重点 | 主提交 | 验收证据 |
| --- | --- | --- | --- |
| 1 | 结果元数据、原始值与观察式截断 | `16e8803b686460aa82201d41462b9bf43010a0bc` | `QueryResultMetadataTest`，聚焦集 7 项 |
| 2 | 类型化本地筛选 | `5b49eb4fda8c2ab6b2d996ef2d557c3cc32815b1` | `LocalResultFilterTest`，聚焦集覆盖 |
| 3 | 筛选状态机与 TSV | `8f1f07d6bed46ae632e8573fab2927ecf763a44c` | `ResultFilterStateTest`、`TsvClipboardFormatterTest` |
| 4 | SELECT 安全资格与方言渲染 | `b07fabeeab57f5f23045a479a0de972cf94a8f6c` | 安全资格与 Oracle/PostgreSQL renderer 测试 |
| 5 | 所有者会话中的参数化执行 | `330265596827c36baa471117bc1d26f6e761f482` | `JdbcPreparedQueryExecutorTest` 及全量回归 |
| 6 | JavaFX 工具栏与条件对话框 | `4a8e0b515a1950aecf2009940753250b5c2a84e8` | 非 headless `SqlResultToolbarTest` |
| 7 | 编辑器接线、复制和安全重查 | `2d5194e7a1fc8dbb7c4a53e30266be069824d008` | 非 headless `SqlEditorResultFilterContractTest` |

实现范围还包含针对上述交付的边界与回归修复，范围终点为
`e2a07e1b0d904ceae03d63904b463efd36f2a065`（`fix: 修复 SQL 结果筛选跨层回归`）。
该显式范围的变更清单为 43 个实现/测试文件、6,241 additions、98 deletions；
`git diff --check 6962807ad17ec6587541e8fc9f18155a2506e491..e2a07e1b0d904ceae03d63904b463efd36f2a065`
未报告空白错误。Task 8 验收文档首个独立提交
`ac2da54643590d746b18f91f6164632b11c801d3` 会与后续文档更正提交单独执行空白检查。
