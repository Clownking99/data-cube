# 导出查询结果 / 导出单表（SQL / Excel / pg_dump 备份）

日期：2026-07-08
状态：已确认，待实现

## 目标

- **导出查询结果**：SQL 编辑器的查询结果一键导出为 Excel（.xlsx）。
- **导出单表**：对象树表节点右键导出，可选内容（结构 / 数据 / 结构+数据）与格式：
  - SQL 脚本（DDL + INSERT）
  - Excel（.xlsx，仅数据）
  - pg_dump 备份（调用外部 pg_dump）

## 背景与约束

- SPI 已具备：`DdlGenerator.tableDdl(TableRef)` 取建表语句；`DataAccessor.page(t, offset,
  limit, sorts, filter)` / `count(t, filter)` 分页读取表数据（见 `spi` 包）。
- `ConnectionManager`：`config(connId)` 给出 host/port/database/username；
  `cipher().decrypt(cfg.encryptedPassword())` 得明文密码（供 pg_dump 用）；
  `provider(connId).ddlGenerator(conn)` / `.dataAccessor(conn)`；`acquire(connId)` 取连接。
- `SqlEditorPane` 已缓存 `lastQueryResult`（QUERY 时含 columns + rows）。
- `ConnectionTreePane` 的 TABLE 右键菜单现有“查看数据 / 查看 DDL / 打开 SQL 编辑器”；
  菜单动作经 `Actions` 接口回调，`AppShell.TreeActions` 实现之。
- 项目风格：零重量级第三方依赖（手写 JSON、本地 jsqlparser）。**Excel 用手写最简
  .xlsx（`java.util.zip`），不引入 Apache POI**（POI 与 jlink 模块化打包冲突，缓一步）。
- 目前仅 PostgreSQL provider；pg_dump 为 PG 专属外部工具。

## 设计

### 1. 业务层新包 `com.datacube.export`（不依赖 JavaFX，可被 CLI 复用）

- **`ExportFormat`** 枚举：`SQL` / `XLSX` / `PG_DUMP`。
- **`ExportContent`** 枚举：`STRUCTURE` / `DATA` / `BOTH`。
- **`RowSink`**（函数式）：`void row(List<Object> values) throws Exception`。
- **`RowFeed`**（函数式）：`void forEach(RowSink sink) throws Exception`——统一“喂行”抽象。
  - 查询结果：遍历内存中的 `rows`。
  - 单表：内部 `DataAccessor.page(t, offset, PAGE, null, null)` 循环直到 `hasMore=false`。
- **`XlsxWriter`**：`write(File out, List<String> columns, RowFeed feed)`。
  - 用 `ZipOutputStream` 手写最简 OOXML：`[Content_Types].xml`、`_rels/.rels`、
    `xl/workbook.xml`、`xl/_rels/workbook.xml.rels`、`xl/worksheets/sheet1.xml`。
  - 单元格：`Number`→数值单元格；`Boolean`→0/1 数值（或字符串，取简单）；null→空；
    其它→inline string（`<is><t>…</t></is>`，XML 转义 `& < > "`）。
  - 首行写列名（inline string）。流式写行，不全量驻留内存。
- **`SqlScriptExporter`**：`write(File out, TableRef t, ExportContent content, String ddl,
  List<String> columns, RowFeed feed, SqlDialect dialect)`。
  - STRUCTURE：写传入的 `ddl`（末尾加 `;` 若无）。
  - DATA：批量 `INSERT INTO <quoted schema.table> (<quoted cols>) VALUES (...), (...);`
    （每 N 行一条 INSERT，N=100）。
  - BOTH：先 DDL 再 INSERT。
  - 值字面量：null→`NULL`；`Number`→原样；`Boolean`→`TRUE`/`FALSE`；`byte[]`→`'\\x<hex>'`；
    其它→`'` + 转义（`'`→`''`）+ `'`。标识符用 `dialect.quoteIdentifier`。
  - 文件头写注释（表名、时间、来源）。UTF-8。
- **`PgDumpRunner`**：`run(ConnConfig cfg, String plainPassword, TableRef t,
  ExportContent content, File out)`。
  - 命令：`pg_dump -h <host> -p <port> -U <user> -d <database> -t <schema.table>
    [--schema-only|--data-only] -f <out>`；BOTH 不加内容标志。
  - `ProcessBuilder`，`environment().put("PGPASSWORD", plainPassword)`，
    `redirectErrorStream(false)`，读取 stderr；等待退出码。
  - 找不到 pg_dump（`IOException`）→抛带“请确认 pg_dump 已安装并在 PATH 中”的异常；
    非零退出→抛含 stderr 末尾内容的异常。
- **`TableExporter`**：编排单表导出。入参 `ConnectionManager, connId, TableRef,
  ExportContent, ExportFormat, File`。
  - SQL：`acquire` 连接 → `ddlGenerator(conn).tableDdl(t)`（需结构时）→
    `dataAccessor(conn)` 构造分页 `RowFeed` → `SqlScriptExporter.write`。
  - XLSX：分页 `RowFeed` → `XlsxWriter.write`（内容强制为数据）。
  - PG_DUMP：`config` + 解密密码 → `PgDumpRunner.run`。

### 2. UI 入口

- **查询结果导出**（`fx.SqlEditorPane`）：
  - 工具栏加“导出结果”按钮；仅当 `lastQueryResult != null && kind==QUERY && rows 非空`
    时可用（随结果刷新联动 enable/disable）。
  - 点击 → `FileChooser`（默认 `.xlsx`）→ 后台线程 `XlsxWriter.write`（feed 遍历
    `lastQueryResult.rows`）→ 状态栏反馈成功/失败。
- **单表导出**（`fx.ConnectionTreePane` + 新建 `fx.ExportDialog`）：
  - TABLE 右键菜单加“导出...”；`Actions` 接口加 `exportTable(String connId, TableRef t)`；
    `AppShell.TreeActions` 实现之，弹 `ExportDialog`。
  - `ExportDialog`：内容单选（结构 / 数据 / 结构+数据）、格式单选（SQL 脚本 / Excel /
    pg_dump 备份）、`FileChooser` 选输出路径（扩展名随格式：.sql/.xlsx/.sql）。
  - 约束：选 Excel 时置灰“结构/结构+数据”并强制“数据”，附提示“Excel 仅导出数据”；
    pg_dump 按内容映射标志。
  - 后台线程执行，期间禁用确定按钮；完成 `Platform.runLater` 提示成功/失败。

### 3. 错误处理与线程

- 全部导出在后台线程，UI 不冻结；`Platform.runLater` 回填。
- 失败弹错误对话框（含消息）；输出文件写入失败 best-effort 删除半成品。
- pg_dump 缺失/失败给可读指引。
- v1 不实现取消（YAGNI）。

### 4. 影响面

- 新增：`com/datacube/export/`（`ExportFormat`、`ExportContent`、`RowSink`、`RowFeed`、
  `XlsxWriter`、`SqlScriptExporter`、`PgDumpRunner`、`TableExporter`）+ `fx/ExportDialog.java`。
- 改动：`fx/SqlEditorPane`（导出按钮）、`fx/ConnectionTreePane`（菜单项 + `Actions.exportTable`）、
  `fx/AppShell`（`TreeActions.exportTable` 接线）。
- 构建：`build.sh` 需加 `com/datacube/export/*.java`（Gradle `srcDirs=['src']` 自动覆盖，
  `fx/*.java` 已覆盖 ExportDialog）。
- 无 SPI 接口破坏、无模型变更、无第三方依赖。

## 测试计划

- 编译验证：`gradlew compileJava` 通过。
- 手动验证（PostgreSQL）：
  - 查询结果导出 .xlsx，Excel 打开列/行正确、数字为数值、中文正常。
  - 单表导出 SQL（结构+数据），生成脚本可在空库回灌；仅结构=只有 DDL；仅数据=只有 INSERT。
  - 单表导出 Excel（结构选项已置灰）。
  - pg_dump 在 PATH 时生成备份文件；不在 PATH 时给出可读错误。
  - 大表分页导出不 OOM（流式写）。

## 假设

- 手写最简 .xlsx 能被 Excel/WPS/LibreOffice 正常打开（inline string + 数值单元格足够）。
- `DataAccessor.page` 的列顺序稳定，可作为 INSERT/Excel 的列序。
- pg_dump 版本与目标库兼容由使用者保证；本工具只负责拼命令与传密码。
