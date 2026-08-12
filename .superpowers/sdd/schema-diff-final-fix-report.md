# Schema Diff 累计终审统一修复报告

## 结果

- 授权的本地范围已 DONE；六条 cumulative finding 均按 RED → GREEN 实施并通过 fresh gates。
- 实现提交：`42b0ddd74c3799e461912edfa49541a653327f26` (`fix: 修复 Schema Diff 累计终审问题`)。
- 直接在 `main` 上作为独立 follow-up commit；未 amend、push、tag。
- 未连接 PostgreSQL/Oracle/Redis live DB，未读取 saved connection，未输出凭据、endpoint 或 driver 原始错误。
- `.testagent/` 仍为 pre-existing untracked 用户目录；未读取、修改、暂存或提交。

## 实施范围

### Finding 1 — cross-schema identity / rebase / convergence

- 新增窄 SPI：`SchemaComparisonProjector` 与不可变、可逆的 `SchemaComparisonProjection`；`SchemaDiffCapability` 默认使用 identity projector，保持现有/第三方 provider 行为。
- `SchemaDiffEngine` 二参 API 仍为 identity semantics；仅显式三参 overload 与 `SchemaDiffService` capability 路径使用 provider projection。
- PostgreSQL/Oracle projector 对所选 owner 投影 object/nested key、column type/default、constraint/index fragment、sequence、definition、routine self UDT 与 same-schema dependency；外部 owner/type 保持精确。
- engine 比较投影后，将 result object、property value、rename suggestion 与 dependency 精确 rehydrate 回 source/target 原对象；planner 对 matched change 建立 source/target key 别名，保持跨 owner dependency/drop 顺序。
- renderer 仅接收原对象并使用 target owner；comparison placeholder 不进入 SQL、digest、UI 或 test report。
- malformed provider key、projection collision/缺映射、owner 不一致固定文案 fail closed。Oracle reader 对 routine UDT 使用精确 quoted owner/type identity，防止含点 owner/type 歧义。

### Finding 2 — 唯一 plan admission / confirmation authority

- 新增不可变、脱敏 `SchemaDeploymentAdmission`，包含 exact plan digest、confirmation-required、effective destructive、safety/production escalation 和固定 warnings。
- `planAdmission()` 与 `deploy()` 共用同一 `validateForTarget()` 路径；deploy 仍重新验证完整 statement 顺序、内容、分组、dependency 与 digest。
- `SchemaDiffViewModel` 不再重复实现 SQL/destructive 分类，首/二次确认和 token 签发均来自 service admission。
- 真实 PG/Oracle renderer 覆盖 VIEW/FUNCTION/PROCEDURE `CREATE OR REPLACE` 六种组合；正确 typed token 进入 deploy，cancel/missing/stale token 均为零 deploy。

### Finding 3 — PostgreSQL constraint backing indexes

- `INDEXES_SQL` 使用官方 `pg_constraint.conindid = pg_index.indexrelid` 关系的 `NOT EXISTS` 过滤 PK/UQ backing index，没有名称猜测或模型扩展。
- JDBC-proxy SQL/row-shape 与 reader→renderer 闭环证明 PK/UQ 只生成内联约束，普通索引和表达式索引各保留一次。

### Finding 4 — PostgreSQL column comment convergence

- CREATE TABLE 后按稳定列序为每个 non-null comment 输出 target-owner `COMMENT ON COLUMN`。
- whole-column ADD 后在同一连续 change group 输出 comment，保持 change-id/dependencies/destructive/warning 元数据一致。
- `null` 不输出 comment，空字符串输出 `IS ''`，单引号安全逃逸；跨 owner 模拟回读第二次 diff 收敛为零。

### Finding 5 — nullable-column risk direction

- engine 仅将 source-only nullable/no-default column 识别为 LOW / SAFE_AUTOMATIC addition。
- target-only nullable column 是 HIGH / DESTRUCTIVE_OPT_IN removal；engine/planner/renderer 方向一致。

### Finding 6 — object-name search

- Pane 新增可见“搜索对象名称”控件，与 object type/risk/automation/selected-state 同时组合。
- 纯模型使用 `Locale.ROOT`，仅搜索 safe display `QualifiedName.original()` 与 `ObjectType.name()`；空白显示全部，canonical comparison key/routine signature 不可命中。
- FX finalizer 解除 search listener；close/late state 不会复活差异树。

## TDD RED → GREEN 证据

1. Finding 1 initial RED：`SchemaCrossOwnerComparisonTest` 在 `compileTestJava` 因缺少 `comparisonProjector()` 失败；中间 RED 暴露 PG renderer owner shape 与 Oracle quote identity。GREEN：PG/Oracle 跨 owner compare→planner→renderer→模拟回读 2/2 通过。回审 RED：target dependency drop 被误改 MANUAL；修复 source/target key alias 后与 `SchemaDependencyPlannerTest` 同时 GREEN。Oracle external dotted UDT RED 先在精确 identity 断言失败，quoted structural identity 后 GREEN。
2. Finding 2 RED：service test 因缺少 `SchemaDeploymentAdmission/planAdmission` 编译失败；PG/Oracle ViewModel 用例均在 `confirmation.destructive()` 为 false 失败。GREEN：service admission 与 ViewModel exact-token 闭环通过，后扩展为 PG/Oracle × VIEW/FUNCTION/PROCEDURE 六种组合。
3. Finding 3 RED：JDBC proxy 在 index SQL 缺失 `conindid` 断言处失败。GREEN：新闭环及原 reader 用例 2/2 通过。
4. Finding 4 RED：CREATE/ADD 两个用例均因缺少 COMMENT 失败。GREEN：2/2 通过，并由跨 owner convergence 用例覆盖模拟回读。
5. Finding 5 RED：source-only/target-only 方向两个用例均失败。GREEN：2/2 与全部 `SchemaChangePlannerTest` 通过。
6. Finding 6 RED：新五参 Filter 用例因缺少构造器产生 5 个编译错误。GREEN：纯 model 2 tests + Pane contract 1 test 通过。

所有中间回归都先保留失败证据再修复：首次 12-suite focused 暴露 5 个 PG renderer 旧 fixture/owner gate 回归，恢复 destructive gate 顺序与 canonical fixture 后 184/184；首次 Task 1–10 matrix 暴露 1 个过时的 ViewModel misclassified-destructive 期望，改为证明 service admission fail closed 后 GREEN。

## Fresh verification

### Focused six-finding gate

Command 覆盖 12 suites：cross-owner/engine/planner/dependency、PG/Oracle reader+renderer、deployment admission、canonical ViewModel、selection/search 与 Pane contract。

- 结果：12 suites / 184 tests / 0 failures / 0 errors / 0 skips。

### Task 1–10 Schema Diff matrix

Command 覆盖 SPI snapshot/capability、所有 `schemadiff`、PG/Oracle `*Schema*`、Schema services、SchemaDiff FX/lifecycle、connection-tree entry 与 workflow contract。

- 结果：33 suites / 294 tests / 0 failures / 0 errors / 2 skips。
- 两个 skip 仅为 PG/Oracle relational live write tests。

### Explicit no-credential live gate

在子进程显式移除 write gate 与 PG/Oracle 全部 11 个 provider 环境变量后运行 `SchemaDiffLiveIntegrationTest`。

- 结果：1 suite / 6 tests / 0 failures / 0 errors / 2 skips。
- 4 个 gate/randomness/cleanup 纯测试通过。
- 精确 skip：`postgresqlSafeDeploymentConvergesInDisposableSchemas()` 与 `oracleSafeDeploymentConvergesInDisposableSchemas()`。
- 未尝试 live relational 连接或写入。

### Full + image

```powershell
.\gradlew.bat clean test jlink --warning-mode fail --rerun-tasks --no-daemon --console=plain
```

- `BUILD SUCCESSFUL`；111 suites / 721 tests / 0 failures / 0 errors / 3 documented skips。
- 第三个 skip 是既有 Redis live test。
- `build/image/bin/DataCube.bat` 存在，含 `-Xms16m`、`-Xmx256m`、`-XX:+UseG1GC`。

### Static/repository gates

- `codegraph sync`：`Already up to date`；`codegraph status`：`[OK] Index is up to date`（369 files / 10,059 nodes / 31,561 edges）。
- `git diff --check` 与 pre-commit staged `git diff --cached --check` 通过。
- `git ls-files -s gradlew`：`100755`。
- changed/staged diff JDBC URL、URI credential、password/credential assignment 扫描：0 matches。
- cross-owner XML placeholder/JDBC/credential 扫描：0 matches。
- 实现提交精确包含 29 个 source/test 文件；不包含 `.testagent/`。

## Residuals / handoff

- PostgreSQL/Oracle 真实 server 执行仍未验证；本次未提供且未授权 disposable endpoint。
- GitHub manual workflow 未触发。
- 未进行 push/tag/release publication。
- 本报告不自行将 cumulative review 标记为 Ready；控制器仍需派发新的累计 reviewer。
