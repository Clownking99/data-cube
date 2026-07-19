# SQL 查询结果表头显示字段注释（可配置）

日期：2026-07-08
状态：已确认，待实现

## 目标

在 SQL 编辑器的查询结果表格中，除字段名外还能展示该字段的数据库注释（comment），
并通过全局设置在三种模式间切换：不显示 / 悬停显示 / 固定显示。

## 背景与约束

- `QueryResult`（`spi.model`）当前只持有列名 `List<String> columns`，无注释信息。
- JDBC `ResultSetMetaData` **不提供列注释**；注释需按每列的底层 schema/table/column
  再查数据库（PostgreSQL 存于 `pg_description`）。
- 仅**直接映射到真实表列**的结果字段能取到注释；表达式、别名、聚合列取不到。
- 目前仅 PostgreSQL provider（`PgSqlRunner`）。
- 现有配置持久化风格：`~/.datacube/connections.json`，手写、无第三方依赖。
  应用级设置尚无存储。

## 设计

### 1. 设置模型与持久化（`config/AppSettings`）
- 持有可观察属性 `ObjectProperty<CommentMode>`，枚举 `CommentMode { OFF, HOVER, INLINE }`。
- 读写 `~/.datacube/settings.properties`（`java.util.Properties`，无第三方依赖）。
  构造时加载（缺省 `HOVER`；文件缺失或损坏则回退缺省，不阻断启动）；属性变更时写回。
- `AppShell` 创建唯一实例并注入需要的面板。

### 2. 设置入口（`fx/SettingsDialog`）
- `AppShell` 顶栏新增“⚙ 设置”按钮，点击打开模态对话框。
- 对话框内一组单选：不显示 / 悬停显示 / 固定显示，初始选中当前模式。
- 确定：写入 `AppSettings`（触发持久化与属性通知）；取消：不改动。

### 3. 注释来源（`provider/postgres/PgColumnComments` + `PgSqlRunner`）
- `PgSqlRunner.execute()` 对 QUERY 结果**始终 best-effort 解析注释**（与当前模式无关，
  以支持“随时切模式无需重跑查询”）。
- `PgColumnComments.resolve(conn, md)`：遍历 `ResultSetMetaData`，取每列
  `getSchemaName/getTableName/getColumnName`；对底层表列非空者收集去重三元组，
  用**一条** `pg_description`（联 `pg_class`/`pg_namespace`/`pg_attribute`）查询批量取回，
  回填为与列平行的 `List<String>`（取不到的位置为 null）。
- 全程 try/catch 静默兜底：任何异常返回空注释，不影响结果展示。

### 4. 数据模型（`spi.model.QueryResult`）
- 新增不可变字段 `List<String> columnComments`（与 `columns` 平行，可含 null）。
- 私有构造接纳该字段；`query(columns, rows, elapsed)` 等既有工厂注释置空（规范化为空列表）。
- 新增拷贝方法 `QueryResult withColumnComments(List<String> comments)` 返回带注释的副本，
  供 `PgSqlRunner` 在不重建行数据的前提下附加注释。
- CLI 与其它路径不设置注释，行为零变化。

### 5. UI 渲染与联动（`fx/SqlEditorPane`）
- 构造接收 `AppSettings`；缓存“最近一次单查询结果”引用（用于模式切换即时重渲染）。
- 按当前 `CommentMode` 渲染表头：
  - **OFF**：仅字段名（现状）。
  - **HOVER**：`TableColumn` 表头挂 `Tooltip`，悬停显示“字段名 / 注释”。
  - **INLINE**：表头 `setGraphic` 两行——字段名在上、灰色小字注释在下。
- 监听 `AppSettings` 的 `CommentMode` 属性变化 → 对缓存的当前结果**即时重渲染表头**，
  不重跑 SQL。无注释的列在各模式下都仅显示字段名。
- 多语句汇总视图（多结果集）保持不变。

## 影响面

- 改动：`QueryResult`、`PgSqlRunner`、`SqlEditorPane`、`AppShell`。
- 新增：`AppSettings`、`SettingsDialog`、`PgColumnComments`。
- 不影响：CLI、迁移功能、其它 provider（SPI 层无签名变更）。
- 构建：新增源文件需同步加入 `build.sh` 的 `javac` 源清单；Gradle 走 `srcDirs=['src']`
  自动包含。

## 测试计划

- 编译验证：`gradlew compileJava` 与 `bash build.sh` 均通过。
- 手动验证（PostgreSQL）：
  - 对含 `COMMENT ON COLUMN` 的表 `SELECT *`：HOVER 悬停可见注释、INLINE 表头两行、
    OFF 仅列名。
  - 含表达式/别名/聚合的查询：对应列无注释、仅列名，且不报错。
  - 设置对话框切换三种模式后当前结果表头即时更新；重启应用后模式被记住。
  - `settings.properties` 缺失/损坏时回退缺省且不影响启动。

## 假设

- pgjdbc 42.7 在结果列映射到真实表列时会填充 `getSchemaName/getTableName/getColumnName`；
  未填充或跨库场景视为“取不到注释”，按 best-effort 忽略。
- 注释查询开销可忽略（单条索引查询），故查询时总是执行，模式仅控制渲染。
