# SQL Result Filtering Verification

## Scope

本次最终验收覆盖 SQL 结果元数据与不可变值快照、截断事实、本地全文/类型化筛选、
fail-closed provider 资格与能力、参数化会话执行、JavaFX 紧凑工具栏、非破坏性恢复、TSV
复制和编辑器生命周期。固定 feature base 为
`6962807ad17ec6587541e8fc9f18155a2506e491`，最终实现 baseline 为
`4e24d0661c834c1b64d3c45cfb358166960ad6c0`；审查和 diff 证据不得使用移动的相对基线。

## Commands and observed results

环境：2026-08-30（Asia/Shanghai，UTC+08:00）；Windows 11 10.0 amd64
（NT 10.0.26200.0）；PowerShell 7.6.4；Temurin OpenJDK 25.0.1+8 LTS；Gradle 9.2.0；
工作目录 `D:\Projects\朝花夕拾`。两个测试命令均通过 `JAVA_TOOL_OPTIONS` 强制
`-Djava.awt.headless=false`，累积矩阵额外使用 `--rerun-tasks` 避免复用 Gradle
`UP-TO-DATE` 结果。

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew test --tests "com.datacube.spi.model.QueryResultMetadataTest" --tests "com.datacube.sqleditor.result.LocalResultFilterTest" --tests "com.datacube.sqleditor.result.ResultFilterStateTest" --tests "com.datacube.sqleditor.result.SafeSelectEligibilityTest" --tests "com.datacube.sqleditor.result.TsvClipboardFormatterTest" --tests "com.datacube.provider.ResultFilterCapabilityMatrixTest" --tests "com.datacube.provider.postgres.PgResultFilterSqlRendererTest" --tests "com.datacube.provider.oracle.OracleResultFilterSqlRendererTest" --tests "com.datacube.provider.jdbc.JdbcPreparedQueryExecutorTest" --tests "com.datacube.fx.SqlResultToolbarTest" --tests "com.datacube.fx.SqlEditorResultFilterContractTest" --tests "com.datacube.fx.SqlEditorPaneLifecycleTest" --tests "com.datacube.fx.SqlEditorSessionContractTest" --tests "com.datacube.fx.SqlEditorSnapshotAllocationContractTest" --tests "com.datacube.service.JdbcEditorSessionTest" --no-daemon --console=plain --rerun-tasks

$env:JAVA_TOOL_OPTIONS='-Djava.awt.headless=false'
.\gradlew clean test --no-daemon --console=plain
```

| 时间（UTC+08:00） | 实际范围 | JUnit suites/tests/pass/skip/failure/error | 墙钟用时 | 构建结果 |
| --- | --- | --- | --- | --- |
| 2026-08-30 04:10:01.213–04:10:23.902 | forced non-headless 累积 result-filter matrix | 15 suites / 378 tests / 378 passed / 0 skipped / 0 failures / 0 errors | 22.689 s（Gradle 22 s） | `BUILD SUCCESSFUL` |
| 2026-08-30 04:10:44.453–04:11:09.765 | fresh forced non-headless `clean test` | 128 suites / 1,159 tests / 1,156 passed / 3 skipped / 0 failures / 0 errors | 25.312 s（Gradle 25 s） | `BUILD SUCCESSFUL` |

矩阵计数在第二条 `clean` 命令前由当时的 15 个 `TEST-*.xml` 即时汇总；`clean test` 随后
覆盖该目录，当前 XML 对应 128-suite 全量结果。未把 Gradle 的 `UP-TO-DATE` 结果当作测试
证据。

审查修复后的第一次矩阵（04:06:58.570–04:07:19.327）曾以 378 tests / 1 failure 退出：
`postgresJsonResultSetEntersPaneAsStableText` 的测试代理仍只实现旧 `getString` 路径，而生产代码
已改用有界 `getCharacterStream`。提交 `4e24d0661c834c1b64d3c45cfb358166960ad6c0`
仅为该代理补齐 reader；原失败用例 forced 单独重跑通过。失败运行不计入上表最终证据。

其余门禁使用以下当前可执行的固定命令。最终文档提交后的完整终点 SHA 和输出另记在未提交的
remediation report，以免在提交自身内容中制造循环 SHA：

```powershell
git diff --check
git diff --cached --check
git diff --check 6962807ad17ec6587541e8fc9f18155a2506e491..4e24d0661c834c1b64d3c45cfb358166960ad6c0
git diff --name-status 6962807ad17ec6587541e8fc9f18155a2506e491..4e24d0661c834c1b64d3c45cfb358166960ad6c0
git diff --stat 6962807ad17ec6587541e8fc9f18155a2506e491..4e24d0661c834c1b64d3c45cfb358166960ad6c0
git diff -- README.md docs/superpowers/specs/2026-08-29-sql-result-filtering-design.md docs/superpowers/plans/2026-08-29-sql-result-filtering.md docs/superpowers/verification/2026-08-29-sql-result-filtering.md .superpowers/sdd/progress.md
codegraph sync
codegraph status
```

实现范围检查为 exit 0，列出 51 个文件、10,636 additions / 130 deletions；当前文档命令只
列出 README、spec、plan、verification 和 progress 五个目标文件。2026-08-30
04:12:38.245–04:12:39.214 的提交前检查中，working-tree/index、固定范围、变更清单与五文件
文档 diff 命令均为 exit 0
（仅 Git 的 LF/CRLF working-copy 提示）；`codegraph sync` 报告 `Already up to date`，`codegraph status` 报告
`[OK] Index is up to date`（414 files / 12,043 nodes / 40,490 edges），两者均为 exit 0。

## Safety and interaction assertions

- 本地全文搜索与类型化条件只计算保留行，不访问数据库；Apply 会先同步提交仍处于防抖等待
  的搜索文字。
- PostgreSQL 原 SQL 仅在无 `FROM`、原生字面量和安全括号/逗号投影时允许数据库 Apply；
  投影可不带别名，如带别名则只允许显式 `AS`。Oracle 原 SQL 仅在显式 `SYS.DUAL` 的 `*` 或 matching-alias `alias.*`
  形式时允许。普通表/视图查询因此只保留本地筛选；这是 fail-closed 安全边界。
- 生成谓词标识符来自不可变结果元数据，值为 prepared parameters。provider 能力以 JDBC
  类型码和 provider 类型名的组合判定；PostgreSQL 运算符限定在 `pg_catalog`。条件快照、
  可访问文本与 JDBC 诊断均不包含筛选值。
- JDBC 值在发布前脱离 driver 对象并冻结；provider 文本保留最多 500 个 UTF-16 字符，
  aggregate 保留最多 128 项，同时记录总长度/项数和 SHA-256，因此同前缀不同尾部仍可区分。
  `Struct.getAttributes()` 受 JDBC API 限制可能由 driver 瞬时物化，但保留快照仍有界。
  statement 行界限为饱和的 `maxRows + 1`，只保留上限内的行；截断状态使用保留结果快照。
- 错误、超时、取消、拒绝及迟到 generation 不替换或重建当前表格，选择、焦点、排序、列顺序
  与列宽保持。复制只有在 JavaFX clipboard 写入返回 true 时报告成功，测试通过注入 seam
  确定性覆盖成功和失败。

## External integration status

本轮没有执行真实 PostgreSQL 或 Oracle 数据库筛选：环境中没有明确授权的 endpoint、凭据与
清理范围。记录型 JDBC、provider renderer/capability 和 forced non-headless JavaFX 契约不能
替代 live database 证据，本文不作该声明。全量测试的 3 项 skip 均为显式 assumption：Redis
live smoke 缺少 `DATACUBE_REDIS_HOST` 与 `DATACUBE_REDIS_PASSWORD`；Oracle 和
PostgreSQL Schema Diff live smoke 均缺少显式写入门禁及完整 provider 环境。上述原因来自
本轮 JUnit XML，不包含连接值或凭据。

## Reviewed range

固定范围从 `6962807ad17ec6587541e8fc9f18155a2506e491` 开始；实现 baseline 为
`4e24d0661c834c1b64d3c45cfb358166960ad6c0`。最终文档提交 SHA、固定范围审查结果与
CodeGraph 状态记录在未提交的 `.superpowers/sdd/remediation-5-report.md`，避免在提交自身
内容中制造循环 SHA 声明。
