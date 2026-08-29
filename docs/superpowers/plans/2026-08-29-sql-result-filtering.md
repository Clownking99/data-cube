# SQL Result Filtering and Copy Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SQL 编辑器增加当前结果搜索、类型化条件、本地预览、安全数据库筛选、准确截断状态和通用 TSV 复制能力。

**Architecture:** 扩展权威的 `com.datacube.spi.model.QueryResult` 以保留 JDBC 列元数据和截断事实；在 `sqleditor.result` 中实现无 JavaFX 依赖的筛选状态机、本地求值器、SQL 资格分析和复制格式化。Oracle/PostgreSQL provider 负责方言渲染与参数化执行，`SqlEditorPane` 只编排现有独占会话和新的紧凑结果工具栏。

**Tech Stack:** Java 25、JavaFX 25、JDBC、RichTextFX 0.11.6、JUnit 5.11、Gradle 9.2 wrapper。

## 2026-08-30 Final-review correction

This plan is retained as the historical implementation record. Its Task 4 examples and references to a
general “safe single SELECT” are broader than the shipped fail-closed implementation and must not be used
as current capability claims. The final boundary is:

- Local search and typed conditions operate only on retained immutable rows and never access JDBC.
- PostgreSQL database Apply accepts only no-`FROM` native-literal projections with safe grouping/comma;
  projections may be unaliased, and any alias must use explicit `AS`. Oracle database Apply accepts only explicit `SYS.DUAL` wildcard or matching-alias
  wildcard forms. Normal table/view queries retain local filtering but database Apply is unavailable.
- Predicate identifiers come only from immutable result metadata; values are prepared parameters. Provider
  capability uses JDBC type code plus provider type name, PostgreSQL operators are `pg_catalog`-qualified,
  and diagnostics/condition snapshots are redacted.
- Result values are detached and frozen before publication; large binary/provider values are bounded and
  deterministic. Driver row bounds use saturating `maxRows + 1`; retained snapshots carry the authoritative
  truncation fact and retained-row count used by status formatting.
- Apply flushes pending search. Recoverable error/timeout/cancel/rejection preserves the existing table
  presentation, and stale generations cannot replace it. Clipboard success requires the JavaFX write to
  return true and is tested through an injectable seam.
- No PostgreSQL/Oracle live database filtering was executed because no explicitly authorized endpoint was
  available. Final evidence is limited to model, recording-JDBC, provider-renderer and forced non-headless
  JavaFX tests.

## Global Constraints

- 数据库筛选采用上述最终极窄 provider 子集；普通表/视图查询只保留本地筛选。
- 全文搜索只处理当前已加载结果，忽略大小写，永远不访问数据库。
- 结构化条件为平面有序列表，严格从左到右计算 `AND / OR`，不支持嵌套组。
- 用户筛选值必须通过 `PreparedStatement` 参数绑定；SQL、日志和错误摘要不得包含参数值或凭据。
- 数据库筛选失败、超时、取消或迟到完成不得替换当前结果。
- 执行时读取上限来自 `AppSettings.getMaxResultRows()`；达到上限且仍有下一行时记录“已截断”，后续状态文案使用保留结果中的行数和截断事实，不读取当前可变设置，也不得推断数据库总行数。
- 不增加第三方依赖，不改变事务、只读、生产环境确认、取消、关闭和连接所有权语义。
- `com.datacube.spi.model.QueryResult` 是权威结果类型；不扩展未被生产路径使用的 `com.datacube.sqleditor.QueryResult` 副本。
- 每个任务只暂存其列出的文件；不得暂存或修改用户的 `.testagent/`。

---

## File Structure

### New production files

- `src/com/datacube/spi/model/ResultColumn.java`：稳定的零基列位置、显示标签、JDBC 类型和类型名。
- `src/com/datacube/spi/SqlParameter.java`：可安全绑定且 `toString()` 不泄露值的 JDBC 参数。
- `src/com/datacube/sqleditor/result/FilterConnector.java`：`AND / OR`。
- `src/com/datacube/sqleditor/result/FilterOperator.java`：按类型开放的筛选运算符。
- `src/com/datacube/sqleditor/result/FilterCondition.java`：列位置、连接符、运算符和类型化值。
- `src/com/datacube/sqleditor/result/FilterValueParser.java`：把条件输入转换为 JDBC 兼容值。
- `src/com/datacube/sqleditor/result/ResultValueFormatter.java`：唯一的结果显示文本转换边界。
- `src/com/datacube/sqleditor/result/LocalResultFilter.java`：全文搜索和结构化条件的纯内存求值。
- `src/com/datacube/sqleditor/result/ResultFilterState.java`：原始/本地预览/数据库已应用状态机。
- `src/com/datacube/sqleditor/result/TsvClipboardFormatter.java`：单元格区域、行和表头 TSV。
- `src/com/datacube/sqleditor/result/TopLevelSqlTokens.java`：忽略引号、注释和括号内部内容的顶层 token 扫描。
- `src/com/datacube/sqleditor/result/SafeSelectEligibility.java`：数据库筛选资格与明确拒绝原因。
- `src/com/datacube/sqleditor/result/ResultFilterSqlRenderer.java`：provider 方言渲染接口。
- `src/com/datacube/sqleditor/result/RenderedFilterQuery.java`：包装 SQL 和有序参数。
- `src/com/datacube/provider/postgres/PgResultFilterSqlRenderer.java`：PostgreSQL 外层查询与标识符引用。
- `src/com/datacube/provider/oracle/OracleResultFilterSqlRenderer.java`：Oracle 外层查询与标识符引用。
- `src/com/datacube/provider/jdbc/JdbcPreparedQueryExecutor.java`：两个 provider 共用的参数绑定、执行、取消、超时和结果读取。
- `src/com/datacube/fx/SqlResultToolbar.java`：紧凑搜索、条件标签、复制菜单、数据库筛选按钮和摘要。
- `src/com/datacube/fx/FilterConditionDialog.java`：类型感知的单条条件编辑对话框。

### New test files

- `test/com/datacube/spi/model/QueryResultMetadataTest.java`
- `test/com/datacube/sqleditor/result/LocalResultFilterTest.java`
- `test/com/datacube/sqleditor/result/ResultFilterStateTest.java`
- `test/com/datacube/sqleditor/result/TsvClipboardFormatterTest.java`
- `test/com/datacube/sqleditor/result/SafeSelectEligibilityTest.java`
- `test/com/datacube/provider/postgres/PgResultFilterSqlRendererTest.java`
- `test/com/datacube/provider/oracle/OracleResultFilterSqlRendererTest.java`
- `test/com/datacube/provider/jdbc/JdbcPreparedQueryExecutorTest.java`
- `test/com/datacube/fx/SqlResultToolbarTest.java`
- `test/com/datacube/fx/SqlEditorResultFilterContractTest.java`

### Existing files modified

- `src/com/datacube/spi/model/QueryResult.java`
- `src/com/datacube/spi/SqlRunner.java`
- `src/com/datacube/spi/DatabaseProvider.java`
- `src/com/datacube/provider/postgres/PostgresProvider.java`
- `src/com/datacube/provider/oracle/OracleProvider.java`
- `src/com/datacube/provider/postgres/PgSqlRunner.java`
- `src/com/datacube/provider/oracle/OracleSqlRunner.java`
- `src/com/datacube/service/JdbcEditorSession.java`
- `src/com/datacube/fx/SqlEditorPane.java`
- `test/com/datacube/service/JdbcEditorSessionTest.java`
- `test/com/datacube/provider/postgres/PgSqlRunnerExecutionControlTest.java`
- `test/com/datacube/provider/oracle/OracleSqlRunnerExecutionControlTest.java`
- `README.md`

---

### Task 1: Preserve result metadata, raw values, and truncation truth

**Files:**
- Create: `src/com/datacube/spi/model/ResultColumn.java`
- Modify: `src/com/datacube/spi/model/QueryResult.java:21-168`
- Create test: `test/com/datacube/spi/model/QueryResultMetadataTest.java`

**Interfaces:**
- Produces: `ResultColumn(int index, String label, int jdbcType, String jdbcTypeName)`.
- Produces: `QueryResult.resultColumns`, `QueryResult.truncated`, and `QueryResult.queryWithMetadata(List<ResultColumn>, List<List<Object>>, long, boolean)`.
- Preserves: existing `QueryResult.columns`, `rows`, `columnComments`, factory methods and failure kinds.

- [ ] **Step 1: Write failing metadata and boundary tests**

```java
package com.datacube.spi.model;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryResultMetadataTest {
    @Test
    void queryWithMetadataPreservesLabelsTypesNullsAndTruncation() {
        ResultColumn id = new ResultColumn(0, "ID", Types.BIGINT, "BIGINT");
        ResultColumn note = new ResultColumn(1, "NOTE", Types.VARCHAR, "VARCHAR");

        QueryResult result = QueryResult.queryWithMetadata(
                List.of(id, note), List.of(java.util.Arrays.asList(7L, null)), 12, true);

        assertEquals(List.of("ID", "NOTE"), result.columns);
        assertEquals(List.of(id, note), result.resultColumns);
        assertNull(result.rows.getFirst().get(1));
        assertTrue(result.truncated);
        assertEquals(12, result.elapsedMillis);
    }

    @Test
    void legacyFactoryCreatesUnknownMetadataWithoutClaimingTruncation() {
        QueryResult result = QueryResult.query(
                List.of("VALUE"), List.of(List.of("x")), 3);

        assertEquals(Types.OTHER, result.resultColumns.getFirst().jdbcType());
        assertFalse(result.truncated);
    }

    @Test
    void resultSetReaderMarksTruncatedOnlyAfterObservingAnExtraRow() throws Exception {
        QueryResult limited = QueryResult.fromResultSet(resultSetWithRows(3), 1, 2);
        QueryResult exact = QueryResult.fromResultSet(resultSetWithRows(2), 1, 2);

        assertEquals(2, limited.rows.size());
        assertTrue(limited.truncated);
        assertEquals(2, exact.rows.size());
        assertFalse(exact.truncated);
    }
}
```

Use this self-contained JDBC row-set fixture in the test:

```java
private static javax.sql.rowset.CachedRowSet resultSetWithRows(int count)
        throws java.sql.SQLException {
    javax.sql.rowset.RowSetMetaDataImpl metadata = new javax.sql.rowset.RowSetMetaDataImpl();
    metadata.setColumnCount(1);
    metadata.setColumnName(1, "ID");
    metadata.setColumnLabel(1, "ID");
    metadata.setColumnType(1, java.sql.Types.INTEGER);
    metadata.setColumnTypeName(1, "INTEGER");
    javax.sql.rowset.CachedRowSet rows =
            javax.sql.rowset.RowSetProvider.newFactory().createCachedRowSet();
    rows.setMetaData(metadata);
    for (int value = 1; value <= count; value++) {
        rows.moveToInsertRow();
        rows.updateInt(1, value);
        rows.insertRow();
        rows.moveToCurrentRow();
    }
    rows.beforeFirst();
    return rows;
}
```

- [ ] **Step 2: Run the test and verify the new API is absent**

Run: `./gradlew test --tests "com.datacube.spi.model.QueryResultMetadataTest" --no-daemon --console=plain`

Expected: `compileTestJava` fails because `ResultColumn`, `resultColumns`, `truncated`, and `queryWithMetadata` do not exist.

- [ ] **Step 3: Add the metadata record and extend QueryResult immutably**

```java
package com.datacube.spi.model;

import java.sql.Types;

public record ResultColumn(int index, String label, int jdbcType, String jdbcTypeName) {
    public ResultColumn {
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        label = label == null ? "" : label;
        jdbcTypeName = jdbcTypeName == null ? "" : jdbcTypeName;
    }

    public static ResultColumn unknown(int index, String label) {
        return new ResultColumn(index, label, Types.OTHER, "OTHER");
    }
}
```

In `QueryResult`, add immutable `resultColumns` and `truncated` fields, make every existing factory populate them, and add this factory:

```java
public static QueryResult queryWithMetadata(
        List<ResultColumn> columns, List<List<Object>> rows,
        long elapsedMillis, boolean truncated) {
    List<ResultColumn> metadata = List.copyOf(columns);
    List<String> labels = metadata.stream().map(ResultColumn::label).toList();
    return new QueryResult(Kind.QUERY, labels, metadata, null,
            rows, -1, elapsedMillis, null, null, truncated);
}
```

Update `withColumnComments` so it preserves both new fields. Update `fromResultSet` to read column label/type/type name from `ResultSetMetaData`, preserve `Timestamp` and other ordinary JDBC values as raw objects, read at most `maxRows`, and perform one extra `rs.next()` only to set `truncated=true`. Continue materializing/freeing CLOB/BLOB previews before closing the result set.

- [ ] **Step 4: Run metadata tests and the existing provider execution tests**

Run: `./gradlew test --tests "com.datacube.spi.model.QueryResultMetadataTest" --tests "com.datacube.provider.*.*SqlRunnerExecutionControlTest" --no-daemon --console=plain`

Expected: all selected tests pass; existing `columns` and `withColumnComments` callers still compile.

- [ ] **Step 5: Commit the bounded result-model change**

```powershell
git add src/com/datacube/spi/model/ResultColumn.java src/com/datacube/spi/model/QueryResult.java test/com/datacube/spi/model/QueryResultMetadataTest.java
git commit -m "feat: 保留查询结果类型与截断信息"
```

---

### Task 2: Implement typed local filtering

**Files:**
- Create: `src/com/datacube/sqleditor/result/FilterConnector.java`
- Create: `src/com/datacube/sqleditor/result/FilterOperator.java`
- Create: `src/com/datacube/sqleditor/result/FilterCondition.java`
- Create: `src/com/datacube/sqleditor/result/FilterValueParser.java`
- Create: `src/com/datacube/sqleditor/result/ResultValueFormatter.java`
- Create: `src/com/datacube/sqleditor/result/LocalResultFilter.java`
- Create test: `test/com/datacube/sqleditor/result/LocalResultFilterTest.java`

**Interfaces:**
- Consumes: `QueryResult.resultColumns` and raw `QueryResult.rows` from Task 1.
- Produces: `LocalResultFilter.visibleRowIndexes(QueryResult, String, List<FilterCondition>)`.
- Produces: `FilterValueParser.parse(ResultColumn, FilterOperator, String)`.

- [ ] **Step 1: Write failing tests for search, nulls, types, and left-to-right connectors**

```java
package com.datacube.sqleditor.result;

import static org.junit.jupiter.api.Assertions.*;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalResultFilterTest {
    private final QueryResult result = QueryResult.queryWithMetadata(
            List.of(new ResultColumn(0, "NAME", Types.VARCHAR, "VARCHAR"),
                    new ResultColumn(1, "SCORE", Types.INTEGER, "INTEGER"),
                    new ResultColumn(2, "NOTE", Types.VARCHAR, "VARCHAR")),
            List.of(java.util.Arrays.asList("Ada", 90, null),
                    List.of("Lin", 70, "ok"),
                    List.of("Bo", 40, "Ada fan")), 1, false);

    @Test
    void globalSearchIgnoresCaseAndSearchesFormattedCells() {
        assertEquals(List.of(0, 2),
                LocalResultFilter.visibleRowIndexes(result, "ada", List.of()));
    }

    @Test
    void conditionsEvaluateStrictlyFromLeftToRight() {
        List<FilterCondition> conditions = List.of(
                new FilterCondition(1, FilterConnector.AND, FilterOperator.GT, 80),
                new FilterCondition(0, FilterConnector.OR, FilterOperator.EQ, "Lin"),
                new FilterCondition(2, FilterConnector.AND, FilterOperator.IS_NOT_NULL, null));
        assertEquals(List.of(1),
                LocalResultFilter.visibleRowIndexes(result, "", conditions));
    }

    @Test
    void nullIsDifferentFromEmptyString() {
        FilterCondition condition = new FilterCondition(
                2, FilterConnector.AND, FilterOperator.IS_NULL, null);
        assertEquals(List.of(0),
                LocalResultFilter.visibleRowIndexes(result, "", List.of(condition)));
    }
}
```

- [ ] **Step 2: Run the test and verify the package is absent**

Run: `./gradlew test --tests "com.datacube.sqleditor.result.LocalResultFilterTest" --no-daemon --console=plain`

Expected: `compileTestJava` fails on the new filter types.

- [ ] **Step 3: Implement the filter domain and evaluator**

Use these exact enums and record signature:

```java
public enum FilterConnector { AND, OR }

public enum FilterOperator {
    EQ(true), NE(true), CONTAINS(true), STARTS_WITH(true), ENDS_WITH(true),
    GT(true), GTE(true), LT(true), LTE(true), IS_NULL(false), IS_NOT_NULL(false);
    private final boolean valueRequired;
    FilterOperator(boolean valueRequired) { this.valueRequired = valueRequired; }
    public boolean valueRequired() { return valueRequired; }
    public static java.util.List<FilterOperator> allowedFor(
            com.datacube.spi.model.ResultColumn column) {
        return switch (column.jdbcType()) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR,
                 java.sql.Types.LONGVARCHAR, java.sql.Types.NCHAR,
                 java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR ->
                    java.util.List.of(EQ, NE, CONTAINS, STARTS_WITH, ENDS_WITH,
                            IS_NULL, IS_NOT_NULL);
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT,
                 java.sql.Types.INTEGER, java.sql.Types.BIGINT,
                 java.sql.Types.REAL, java.sql.Types.FLOAT,
                 java.sql.Types.DOUBLE, java.sql.Types.NUMERIC,
                 java.sql.Types.DECIMAL, java.sql.Types.DATE,
                 java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE,
                 java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE ->
                    java.util.List.of(EQ, NE, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL);
            default -> java.util.List.of(EQ, NE, IS_NULL, IS_NOT_NULL);
        };
    }
}

public record FilterCondition(
        int columnIndex, FilterConnector connector,
        FilterOperator operator, Object value) {
    public FilterCondition {
        if (columnIndex < 0) throw new IllegalArgumentException("columnIndex must be non-negative");
        connector = java.util.Objects.requireNonNull(connector, "connector");
        operator = java.util.Objects.requireNonNull(operator, "operator");
        if (operator.valueRequired() && value == null) {
            throw new IllegalArgumentException("operator requires a value");
        }
        if (!operator.valueRequired()) value = null;
    }
}
```

`LocalResultFilter` must first apply case-insensitive formatted global search, then fold condition booleans in list order:

```java
boolean accepted = matches(row, conditions.getFirst());
for (int index = 1; index < conditions.size(); index++) {
    FilterCondition next = conditions.get(index);
    accepted = next.connector() == FilterConnector.AND
            ? accepted && matches(row, next)
            : accepted || matches(row, next);
}
```

Use `BigDecimal` comparison for numeric values, temporal `Comparable` values for dates/times, exact case-sensitive string comparison for structured text, and explicit `IS_NULL / IS_NOT_NULL`. `ResultValueFormatter.format(null)` returns `""`; it formats temporal values for display without mutating raw rows. `FilterValueParser` maps JDBC numeric, boolean, date, time and timestamp types to typed values and throws `IllegalArgumentException` with a user-readable Chinese message for invalid input.

- [ ] **Step 4: Run the complete local-filter test class**

Run: `./gradlew test --tests "com.datacube.sqleditor.result.LocalResultFilterTest" --no-daemon --console=plain`

Expected: all tests pass, including left-to-right `((A OR B) AND C)` behavior.

- [ ] **Step 5: Commit the pure local-filter core**

```powershell
git add src/com/datacube/sqleditor/result/FilterConnector.java src/com/datacube/sqleditor/result/FilterOperator.java src/com/datacube/sqleditor/result/FilterCondition.java src/com/datacube/sqleditor/result/FilterValueParser.java src/com/datacube/sqleditor/result/ResultValueFormatter.java src/com/datacube/sqleditor/result/LocalResultFilter.java test/com/datacube/sqleditor/result/LocalResultFilterTest.java
git commit -m "feat: 增加类型化本地结果筛选"
```

---

### Task 3: Add the result state machine and deterministic TSV formatting

**Files:**
- Create: `src/com/datacube/sqleditor/result/ResultFilterState.java`
- Create: `src/com/datacube/sqleditor/result/TsvClipboardFormatter.java`
- Create test: `test/com/datacube/sqleditor/result/ResultFilterStateTest.java`
- Create test: `test/com/datacube/sqleditor/result/TsvClipboardFormatterTest.java`

**Interfaces:**
- Consumes: filter types and evaluator from Task 2.
- Produces: `ResultFilterState.Snapshot`, `DatabaseStatus`, and `DatabaseFilterRequest`.
- Produces: `TsvClipboardFormatter.rectangle(List<String>, List<List<String>>, Set<CellRef>, boolean)` and `rows(List<String>, List<List<String>>, Set<Integer>, boolean)`.

- [ ] **Step 1: Write failing state-transition and TSV tests**

```java
private static final QueryResult result = QueryResult.query(
        List.of("ID"), List.of(List.of(1), List.of(2)), 1);
private static final QueryResult filteredResult = QueryResult.query(
        List.of("ID"), List.of(List.of(2)), 1);
private static final FilterCondition condition = new FilterCondition(
        0, FilterConnector.AND, FilterOperator.GT, 1);

@Test
void databaseFailurePreservesActiveRowsAndConditions() {
    ResultFilterState state = new ResultFilterState();
    state.showOriginal(result, "select ID from USERS", null);
    state.setConditions(List.of(condition));
    state.databaseApplied(filteredResult);
    QueryResult before = state.snapshot().activeResult();

    state.databaseFailed("timeout");

    assertSame(before, state.snapshot().activeResult());
    assertEquals(ResultFilterState.DatabaseStatus.APPLIED,
            state.snapshot().databaseStatus());
    assertEquals("timeout", state.snapshot().recoverableError());
}

@Test
void clearAfterDatabaseApplyRestoresCachedOriginalWithoutExecution() {
    ResultFilterState state = new ResultFilterState();
    state.showOriginal(result, "select ID from USERS", null);
    state.setConditions(List.of(condition));
    state.databaseApplied(filteredResult);

    state.clearFilters();

    assertSame(result, state.snapshot().activeResult());
    assertEquals(ResultFilterState.DatabaseStatus.ORIGINAL,
            state.snapshot().databaseStatus());
}
```

```java
@Test
void rectangleIncludesGapsAndEscapesSpreadsheetSensitiveText() {
    String tsv = TsvClipboardFormatter.rectangle(
            List.of("A", "B", "C"),
            List.of(List.of("x", "a\tb", "z"), List.of("q", "line\n2", "w")),
            java.util.Set.of(new TsvClipboardFormatter.CellRef(0, 0),
                    new TsvClipboardFormatter.CellRef(0, 2),
                    new TsvClipboardFormatter.CellRef(1, 1)),
            true);
    assertEquals("A\tB\tC\nx\t\tz\n\t\"line\n2\"\t", tsv);
}
```

- [ ] **Step 2: Run both tests and verify the state/formatter APIs are absent**

Run: `./gradlew test --tests "com.datacube.sqleditor.result.ResultFilterStateTest" --tests "com.datacube.sqleditor.result.TsvClipboardFormatterTest" --no-daemon --console=plain`

Expected: test compilation fails on `ResultFilterState`, `CellRef`, and `TsvClipboardFormatter`.

- [ ] **Step 3: Implement explicit state transitions**

Use these public shapes:

```java
public final class ResultFilterState {
    public enum DatabaseStatus { ORIGINAL, LOCAL_PREVIEW, APPLIED, DIRTY_AFTER_APPLY }

    public record DatabaseFilterRequest(
            String originalSql, QueryResult originalResult,
            List<FilterCondition> conditions) {}

    public record Snapshot(
            QueryResult originalResult, QueryResult activeResult, String originalSql,
            String searchText, List<FilterCondition> conditions,
            List<Integer> visibleRowIndexes, DatabaseStatus databaseStatus,
            String databaseUnavailableReason, String recoverableError) {}

    private QueryResult originalResult;
    private QueryResult activeResult;
    private String originalSql;
    private String searchText = "";
    private List<FilterCondition> conditions = List.of();
    private List<Integer> visibleRowIndexes = List.of();
    private DatabaseStatus databaseStatus = DatabaseStatus.ORIGINAL;
    private String databaseUnavailableReason;
    private String recoverableError;

    public void showOriginal(QueryResult result, String sql, String unavailableReason) {
        originalResult = java.util.Objects.requireNonNull(result, "result");
        activeResult = result;
        originalSql = java.util.Objects.requireNonNull(sql, "sql");
        searchText = "";
        conditions = List.of();
        databaseStatus = DatabaseStatus.ORIGINAL;
        databaseUnavailableReason = unavailableReason;
        recoverableError = null;
        recompute();
    }

    public void setSearchText(String value) {
        searchText = value == null ? "" : value;
        markPreview();
        recompute();
    }

    public void setConditions(List<FilterCondition> value) {
        conditions = List.copyOf(value);
        markPreview();
        recompute();
    }

    public DatabaseFilterRequest databaseRequest() {
        if (originalResult == null || conditions.isEmpty()) {
            throw new IllegalStateException("没有可应用的数据库筛选条件");
        }
        if (databaseUnavailableReason != null) {
            throw new IllegalStateException(databaseUnavailableReason);
        }
        return new DatabaseFilterRequest(originalSql, originalResult, conditions);
    }

    public void databaseApplied(QueryResult result) {
        activeResult = java.util.Objects.requireNonNull(result, "result");
        databaseStatus = DatabaseStatus.APPLIED;
        recoverableError = null;
        recompute();
    }

    public void databaseFailed(String message) {
        recoverableError = message == null ? "数据库筛选失败" : message;
    }

    public void clearFilters() {
        activeResult = originalResult;
        searchText = "";
        conditions = List.of();
        databaseStatus = DatabaseStatus.ORIGINAL;
        recoverableError = null;
        recompute();
    }

    public void clearAll() {
        originalResult = null;
        activeResult = null;
        originalSql = null;
        searchText = "";
        conditions = List.of();
        visibleRowIndexes = List.of();
        databaseStatus = DatabaseStatus.ORIGINAL;
        databaseUnavailableReason = null;
        recoverableError = null;
    }

    public Snapshot snapshot() {
        return new Snapshot(originalResult, activeResult, originalSql, searchText,
                conditions, visibleRowIndexes, databaseStatus,
                databaseUnavailableReason, recoverableError);
    }

    private void markPreview() {
        boolean empty = searchText.isBlank() && conditions.isEmpty();
        if (empty && activeResult == originalResult) databaseStatus = DatabaseStatus.ORIGINAL;
        else if (databaseStatus == DatabaseStatus.APPLIED
                || databaseStatus == DatabaseStatus.DIRTY_AFTER_APPLY) {
            databaseStatus = DatabaseStatus.DIRTY_AFTER_APPLY;
        } else databaseStatus = DatabaseStatus.LOCAL_PREVIEW;
        recoverableError = null;
    }

    private void recompute() {
        visibleRowIndexes = activeResult == null ? List.of()
                : LocalResultFilter.visibleRowIndexes(activeResult, searchText, conditions);
    }
}
```

Every mutator recomputes visible indexes with `LocalResultFilter`; snapshots use `List.copyOf`. `databaseRequest()` rejects empty conditions or a non-null unavailable reason. `databaseFailed` changes only `recoverableError`; it never replaces results or status.

Implement `CellRef(int row, int column)` as a nested record in `TsvClipboardFormatter`, quote values containing tab/newline/double quote, double embedded quotes, and preserve the minimum selected rectangle including unselected gaps.

- [ ] **Step 4: Run state and TSV tests**

Run: `./gradlew test --tests "com.datacube.sqleditor.result.ResultFilterStateTest" --tests "com.datacube.sqleditor.result.TsvClipboardFormatterTest" --no-daemon --console=plain`

Expected: all tests pass; failure and clear behavior preserve the required snapshots.

- [ ] **Step 5: Commit the state and copy core**

```powershell
git add src/com/datacube/sqleditor/result/ResultFilterState.java src/com/datacube/sqleditor/result/TsvClipboardFormatter.java test/com/datacube/sqleditor/result/ResultFilterStateTest.java test/com/datacube/sqleditor/result/TsvClipboardFormatterTest.java
git commit -m "feat: 增加结果筛选状态与 TSV 复制模型"
```

---

### Task 4: Prove safe SELECT eligibility and render provider SQL

**Files:**
- Create: `src/com/datacube/spi/SqlParameter.java`
- Create: `src/com/datacube/sqleditor/result/TopLevelSqlTokens.java`
- Create: `src/com/datacube/sqleditor/result/SafeSelectEligibility.java`
- Create: `src/com/datacube/sqleditor/result/ResultFilterSqlRenderer.java`
- Create: `src/com/datacube/sqleditor/result/RenderedFilterQuery.java`
- Create: `src/com/datacube/provider/postgres/PgResultFilterSqlRenderer.java`
- Create: `src/com/datacube/provider/oracle/OracleResultFilterSqlRenderer.java`
- Modify: `src/com/datacube/spi/DatabaseProvider.java:19-57`
- Modify: `src/com/datacube/provider/postgres/PostgresProvider.java:27-90`
- Modify: `src/com/datacube/provider/oracle/OracleProvider.java:27-90`
- Create tests: `test/com/datacube/sqleditor/result/SafeSelectEligibilityTest.java`
- Create tests: `test/com/datacube/provider/postgres/PgResultFilterSqlRendererTest.java`
- Create tests: `test/com/datacube/provider/oracle/OracleResultFilterSqlRendererTest.java`

**Interfaces:**
- Consumes: `ResultFilterState.DatabaseFilterRequest` and filter types.
- Produces: `SafeSelectEligibility.Result(boolean eligible, String normalizedSql, String reason)`.
- Produces: `ResultFilterSqlRenderer.render(String, List<ResultColumn>, List<FilterCondition>)`.
- Produces: `RenderedFilterQuery(String sql, List<SqlParameter> parameters)`.
- Produces: `DatabaseProvider.resultFilterSqlRenderer()` as an optional provider capability.

- [ ] **Step 1: Write failing eligibility and renderer tests**

```java
@ParameterizedTest
@ValueSource(strings = {
        "update users set active = false",
        "select 1; select 2",
        "with q as (select 1) select * from q",
        "select id from a union select id from b",
        "select * from jobs for update"
})
void rejectsSqlThatCannotBeProvedSafeToWrap(String sql) {
    SafeSelectEligibility.Result result = SafeSelectEligibility.check(sql, false, uniqueResult);
    assertFalse(result.eligible());
    assertFalse(result.reason().isBlank());
}
```

```java
@Test
void postgresRendererBindsValuesAndPreservesLeftToRightParentheses() {
    RenderedFilterQuery query = new PgResultFilterSqlRenderer().render(
            "select id, name from users",
            columns,
            List.of(new FilterCondition(0, AND, GT, 10),
                    new FilterCondition(1, OR, CONTAINS, "a%_")));
    assertEquals("SELECT * FROM (select id, name from users) AS \"dc_filter\" "
            + "WHERE (\"dc_filter\".\"id\" > ? OR \"dc_filter\".\"name\" LIKE ? ESCAPE '\\\\')",
            query.sql());
    assertEquals(2, query.parameters().size());
    assertFalse(query.toString().contains("a%_"));
}
```

Add the equivalent Oracle assertion without `AS` before the generated table alias.

- [ ] **Step 2: Run the three test classes and verify the safety API is absent**

Run: `./gradlew test --tests "com.datacube.sqleditor.result.SafeSelectEligibilityTest" --tests "com.datacube.provider.postgres.PgResultFilterSqlRendererTest" --tests "com.datacube.provider.oracle.OracleResultFilterSqlRendererTest" --no-daemon --console=plain`

Expected: test compilation fails on the new eligibility, renderer, and parameter types.

- [ ] **Step 3: Implement conservative token scanning and eligibility**

`TopLevelSqlTokens.scan(String)` must emit only depth-zero, unquoted tokens while correctly skipping single quotes, quoted identifiers, line comments, block comments, PostgreSQL dollar-quoted bodies, and parentheses. `SafeSelectEligibility.check` must:

```java
List<String> statements = SqlScriptSplitter.split(sql, oracleMode);
if (statements.size() != 1) return Result.rejected("仅支持单条 SELECT");
String normalized = stripSingleTerminalSemicolon(statements.getFirst());
List<String> tokens = TopLevelSqlTokens.scan(normalized);
if (tokens.isEmpty() || !tokens.getFirst().equals("SELECT")) {
    return Result.rejected("仅支持只读 SELECT");
}
if (tokens.stream().anyMatch(Set.of("WITH", "UNION", "INTERSECT", "EXCEPT", "MINUS")::contains)
        || containsSequence(tokens, "FOR", "UPDATE")) {
    return Result.rejected("该 SELECT 结构不能安全包装");
}
if (hasDuplicateOrBlankLabels(result.resultColumns)) {
    return Result.rejected("结果列名必须唯一，请在原 SQL 中添加别名");
}
return Result.allowed(normalized);
```

The scanner, not a raw regex, determines top-level tokens.

- [ ] **Step 4: Implement redacted parameters and both renderers**

```java
public record SqlParameter(int jdbcType, Object value) {
    public void bind(java.sql.PreparedStatement statement, int index)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, jdbcType);
        else statement.setObject(index, value, jdbcType);
    }

    @Override public String toString() {
        return "SqlParameter[jdbcType=" + jdbcType + ", value=<redacted>]";
    }
}

public record RenderedFilterQuery(String sql, List<SqlParameter> parameters) {
    public RenderedFilterQuery {
        sql = java.util.Objects.requireNonNull(sql, "sql");
        parameters = List.copyOf(parameters);
    }
}
```

Each renderer validates column indexes against metadata, quotes only metadata-derived labels, escapes `%`, `_`, and `\` for text pattern parameters, folds predicates with explicit parentheses, and never includes parameter values in `toString()` or exceptions. Add this compatible provider extension:

```java
default java.util.Optional<ResultFilterSqlRenderer> resultFilterSqlRenderer() {
    return java.util.Optional.empty();
}
```

Oracle and PostgreSQL providers return their renderer instances.

- [ ] **Step 5: Run safety/renderer tests and SPI compatibility tests**

Run: `./gradlew test --tests "com.datacube.sqleditor.result.SafeSelectEligibilityTest" --tests "com.datacube.provider.*.*ResultFilterSqlRendererTest" --tests "com.datacube.spi.SchemaDiffCapabilityContractTest" --no-daemon --console=plain`

Expected: all selected tests pass; existing providers compile because the new capability has a default.

- [ ] **Step 6: Commit the safe rendering capability**

```powershell
git add src/com/datacube/spi/SqlParameter.java src/com/datacube/sqleditor/result/TopLevelSqlTokens.java src/com/datacube/sqleditor/result/SafeSelectEligibility.java src/com/datacube/sqleditor/result/ResultFilterSqlRenderer.java src/com/datacube/sqleditor/result/RenderedFilterQuery.java src/com/datacube/provider/postgres/PgResultFilterSqlRenderer.java src/com/datacube/provider/oracle/OracleResultFilterSqlRenderer.java src/com/datacube/spi/DatabaseProvider.java src/com/datacube/provider/postgres/PostgresProvider.java src/com/datacube/provider/oracle/OracleProvider.java test/com/datacube/sqleditor/result/SafeSelectEligibilityTest.java test/com/datacube/provider/postgres/PgResultFilterSqlRendererTest.java test/com/datacube/provider/oracle/OracleResultFilterSqlRendererTest.java
git commit -m "feat: 增加安全查询筛选渲染器"
```

---

### Task 5: Execute parameterized filters through the owned editor session

**Files:**
- Create: `src/com/datacube/provider/jdbc/JdbcPreparedQueryExecutor.java`
- Modify: `src/com/datacube/spi/SqlRunner.java:15-63`
- Modify: `src/com/datacube/provider/postgres/PgSqlRunner.java:30-66`
- Modify: `src/com/datacube/provider/oracle/OracleSqlRunner.java:36-72`
- Modify: `src/com/datacube/service/JdbcEditorSession.java:118-195`
- Create test: `test/com/datacube/provider/jdbc/JdbcPreparedQueryExecutorTest.java`
- Modify test: `test/com/datacube/provider/postgres/PgSqlRunnerExecutionControlTest.java`
- Modify test: `test/com/datacube/provider/oracle/OracleSqlRunnerExecutionControlTest.java`
- Modify test: `test/com/datacube/service/JdbcEditorSessionTest.java`

**Interfaces:**
- Consumes: `SqlParameter` and `RenderedFilterQuery` from Task 4.
- Produces: `SqlRunner.executePrepared(Connection, String, List<SqlParameter>, String, SqlExecutionOptions)`.
- Produces: `JdbcEditorSession.executePrepared(String, List<SqlParameter>, String, int)`.

- [ ] **Step 1: Write failing binding and session ownership tests**

```java
@Test
void bindsInOrderActivatesControlAndReadsBoundedResult() {
    RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
    SqlExecutionOptions options = new SqlExecutionOptions(100, 7, new SqlExecutionControl());

    QueryResult result = JdbcPreparedQueryExecutor.execute(
            jdbc.connection(), "select * from q where id > ? and name = ?",
            List.of(new SqlParameter(Types.INTEGER, 10),
                    new SqlParameter(Types.VARCHAR, "Ada")), options);

    assertEquals(List.of(10, "Ada"), jdbc.boundValues());
    assertEquals(7, jdbc.queryTimeout());
    assertEquals(QueryResult.Kind.QUERY, result.kind);
}
```

In `JdbcEditorSessionTest`, add a runner that records `executePrepared` calls and assert the same session connection, timeout, max rows, cancellation control, and transaction-state update are used.

- [ ] **Step 2: Run the prepared-execution tests and verify the methods are absent**

Run: `./gradlew test --tests "com.datacube.provider.jdbc.JdbcPreparedQueryExecutorTest" --tests "com.datacube.service.JdbcEditorSessionTest" --no-daemon --console=plain`

Expected: test compilation fails on `JdbcPreparedQueryExecutor` and `executePrepared`.

- [ ] **Step 3: Implement one shared PreparedStatement executor**

```java
public static QueryResult execute(
        Connection connection, String sql, List<SqlParameter> parameters,
        SqlExecutionOptions options) {
    long started = System.currentTimeMillis();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        SqlExecutionControl.Activation activation =
                options.control().activate(statement, options.queryTimeoutSeconds());
        try {
            for (int i = 0; i < parameters.size(); i++) {
                parameters.get(i).bind(statement, i + 1);
            }
            options.control().ensureNotCancelled(activation);
            try (ResultSet rows = statement.executeQuery()) {
                return QueryResult.fromResultSet(
                        rows, System.currentTimeMillis() - started, options.maxRows());
            }
        } finally {
            options.control().release(activation);
        }
    } catch (SQLTimeoutException timeout) {
        return QueryResult.timeout(timeout.getMessage(), System.currentTimeMillis() - started);
    } catch (SQLException failure) {
        long elapsed = System.currentTimeMillis() - started;
        return options.control().cancellationRequested()
                ? QueryResult.cancelled(failure.getMessage(), elapsed)
                : QueryResult.error(failure.getMessage(), elapsed);
    }
}
```

No SQL or parameter values are logged.

- [ ] **Step 4: Add compatible runner and session APIs**

Add a default `SqlRunner.executePrepared` that returns an unsupported `QueryResult.error` for third-party providers. Override it in Oracle and PostgreSQL runners: apply schema using the existing provider method, then call `JdbcPreparedQueryExecutor.execute`.

Add this public session entry point using the same `singleFlight`, `beginOperation`, `connection(control)`, `SqlExecutionOptions`, `updateTransactionState`, `finishOperation`, and `executionFailure` sequence as `explain`:

```java
public QueryResult executePrepared(
        String sql, List<SqlParameter> parameters, String schema, int maxRows) {
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(parameters, "parameters");
    singleFlight.lock();
    SqlExecutionControl control = null;
    long startedAt = System.currentTimeMillis();
    try {
        ensureOpen();
        control = beginOperation();
        ensureOpen();
        SqlExecutionOptions options = new SqlExecutionOptions(
                maxRows, safety.queryTimeoutSeconds(), control);
        QueryResult result = runner.executePrepared(
                connection(control), sql, List.copyOf(parameters), schema, options);
        updateTransactionState(List.of(new ScriptOutcome(1, sql, result)));
        return result;
    } catch (SQLException failure) {
        QueryResult result = executionFailure(failure, startedAt, control);
        updateTransactionState(List.of(new ScriptOutcome(1, sql, result)));
        return result;
    } finally {
        finishOperation(control);
        singleFlight.unlock();
    }
}
```

The method must not call `executeScript`, must not open a second connection, and must remain cancellable through the existing active control.

- [ ] **Step 5: Run provider and session cancellation tests**

Run: `./gradlew test --tests "com.datacube.provider.jdbc.JdbcPreparedQueryExecutorTest" --tests "com.datacube.provider.postgres.PgSqlRunnerExecutionControlTest" --tests "com.datacube.provider.oracle.OracleSqlRunnerExecutionControlTest" --tests "com.datacube.service.JdbcEditorSessionTest" --no-daemon --console=plain`

Expected: all selected tests pass; timeout/cancel classifications and strict close tests remain green.

- [ ] **Step 6: Commit parameterized session execution**

```powershell
git add src/com/datacube/provider/jdbc/JdbcPreparedQueryExecutor.java src/com/datacube/spi/SqlRunner.java src/com/datacube/provider/postgres/PgSqlRunner.java src/com/datacube/provider/oracle/OracleSqlRunner.java src/com/datacube/service/JdbcEditorSession.java test/com/datacube/provider/jdbc/JdbcPreparedQueryExecutorTest.java test/com/datacube/provider/postgres/PgSqlRunnerExecutionControlTest.java test/com/datacube/provider/oracle/OracleSqlRunnerExecutionControlTest.java test/com/datacube/service/JdbcEditorSessionTest.java
git commit -m "feat: 支持会话内参数化结果筛选"
```

---

### Task 6: Build the compact JavaFX result toolbar and condition dialog

**Files:**
- Create: `src/com/datacube/fx/SqlResultToolbar.java`
- Create: `src/com/datacube/fx/FilterConditionDialog.java`
- Create test: `test/com/datacube/fx/SqlResultToolbarTest.java`

**Interfaces:**
- Consumes: `ResultFilterState.Snapshot`, `ResultColumn`, filter enums and parser.
- Produces: `SqlResultToolbar.Actions` callbacks without direct JDBC access.
- Produces stable test IDs: `sql-result-search`, `sql-result-add-filter`, `sql-result-apply-database`, `sql-result-copy`, `sql-result-clear-filter`, `sql-result-summary`.

- [ ] **Step 1: Write failing JavaFX tests for layout, state, and callbacks**

```java
@Test
void compactToolbarReflectsLocalPreviewAndDoesNotApplyDatabaseImplicitly() throws Exception {
    AtomicInteger databaseRequests = new AtomicInteger();
    FxUiTestSupport.call(() -> {
        SqlResultToolbar toolbar = new SqlResultToolbar(new SqlResultToolbar.Actions(
                text -> {}, () -> {}, index -> {},
                databaseRequests::incrementAndGet, () -> {}, mode -> {}));
        toolbar.render(localPreviewSnapshot);
        assertEquals("本地预览：显示 12 / 186 行 · 6 列",
                ((Label) toolbar.getNode().lookup("#sql-result-summary")).getText());
        assertEquals(0, databaseRequests.get());
        assertFalse(toolbar.getNode().lookup("#sql-result-apply-database").isDisabled());
        return null;
    });
}
```

Add assertions for disabled-reason tooltip, applied/dirty labels, clear, copy menu entries, condition chips, and accessible text.

- [ ] **Step 2: Run the toolbar test and verify the controls are absent**

Run: `./gradlew test --tests "com.datacube.fx.SqlResultToolbarTest" --no-daemon --console=plain`

Expected: `compileTestJava` fails because `SqlResultToolbar` does not exist.

- [ ] **Step 3: Implement a view-only toolbar**

Use this callback boundary:

```java
public record Actions(
        java.util.function.Consumer<String> searchChanged,
        Runnable addCondition,
        java.util.function.IntConsumer removeCondition,
        Runnable applyDatabaseFilter,
        Runnable clearFilters,
        java.util.function.Consumer<CopyMode> copyRequested) {}

public enum CopyMode { CURRENT_CELL, SELECTION, SELECTED_ROWS, SELECTED_ROWS_WITH_HEADERS }
```

The toolbar owns a `TextField`, `FlowPane` for chips, `Button` for adding conditions, database button, `MenuButton` for copy, clear button, and summary label. The add button invokes `addCondition`; every chip has an accessible remove action that passes its stable condition index to `removeCondition`. A 120 ms `PauseTransition` invokes `searchChanged`; no other input automatically invokes `applyDatabaseFilter`. `render(snapshot)` is the only state-to-control mapping and always sets button disabled state plus tooltip reason together.

- [ ] **Step 4: Implement the type-aware condition dialog**

```java
static java.util.Optional<FilterCondition> show(
        Window owner, List<ResultColumn> columns,
        int conditionIndex, FilterConnector initialConnector) {
    Dialog<FilterCondition> dialog = new Dialog<>();
    if (owner != null) dialog.initOwner(owner);
    dialog.setTitle("添加筛选条件");
    ComboBox<ResultColumn> column = new ComboBox<>(
            FXCollections.observableArrayList(columns));
    ComboBox<FilterConnector> connector = new ComboBox<>(
            FXCollections.observableArrayList(FilterConnector.values()));
    ComboBox<FilterOperator> operator = new ComboBox<>();
    TextField value = new TextField();
    Label error = new Label();
    connector.setValue(initialConnector);
    connector.setVisible(conditionIndex > 0);
    connector.setManaged(conditionIndex > 0);
    column.getSelectionModel().selectFirst();
    Runnable refreshOperators = () -> {
        operator.setItems(FXCollections.observableArrayList(
                FilterOperator.allowedFor(column.getValue())));
        operator.getSelectionModel().selectFirst();
    };
    refreshOperators.run();
    column.valueProperty().addListener((ignored, oldValue, newValue) -> refreshOperators.run());
    GridPane form = new GridPane();
    form.setHgap(8);
    form.setVgap(8);
    form.addRow(0, new Label("连接:"), connector);
    form.addRow(1, new Label("列:"), column);
    form.addRow(2, new Label("运算符:"), operator);
    form.addRow(3, new Label("值:"), value);
    form.add(error, 1, 4);
    ButtonType ok = new ButtonType("添加", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
    dialog.getDialogPane().setContent(form);
    Node okButton = dialog.getDialogPane().lookupButton(ok);
    Runnable validate = () -> {
        try {
            boolean required = operator.getValue() != null && operator.getValue().valueRequired();
            value.setDisable(!required);
            if (required) FilterValueParser.parse(column.getValue(), operator.getValue(), value.getText());
            error.setText("");
            okButton.setDisable(column.getValue() == null || operator.getValue() == null);
        } catch (IllegalArgumentException invalid) {
            error.setText(invalid.getMessage());
            okButton.setDisable(true);
        }
    };
    value.textProperty().addListener((ignored, oldValue, newValue) -> validate.run());
    operator.valueProperty().addListener((ignored, oldValue, newValue) -> validate.run());
    validate.run();
    dialog.setResultConverter(button -> {
        if (button != ok) return null;
        Object parsed = operator.getValue().valueRequired()
                ? FilterValueParser.parse(column.getValue(), operator.getValue(), value.getText())
                : null;
        return new FilterCondition(column.getValue().index(),
                conditionIndex == 0 ? FilterConnector.AND : connector.getValue(),
                operator.getValue(), parsed);
    });
    return dialog.showAndWait();
}
```

Hide the connector for index zero. Refresh allowed operators when the column changes. Disable the value field for NULL operators. Use `FilterValueParser.parse` before enabling the OK result; invalid input remains in the dialog with a Chinese inline message.

- [ ] **Step 5: Run JavaFX toolbar tests**

Run: `./gradlew test --tests "com.datacube.fx.SqlResultToolbarTest" --no-daemon --console=plain`

Expected: all toolbar and dialog tests pass on `FxUiTestSupport`; creating and rendering controls performs no database work.

- [ ] **Step 6: Commit the isolated JavaFX components**

```powershell
git add src/com/datacube/fx/SqlResultToolbar.java src/com/datacube/fx/FilterConditionDialog.java test/com/datacube/fx/SqlResultToolbarTest.java
git commit -m "feat: 增加查询结果筛选工具栏"
```

---

### Task 7: Wire filtering, copying, and safe re-query into SqlEditorPane

**Files:**
- Modify: `src/com/datacube/fx/SqlEditorPane.java:139-170, 617-708, 877-917, 960-1050, 1390-1715`
- Create test: `test/com/datacube/fx/SqlEditorResultFilterContractTest.java`
- Modify test: `test/com/datacube/fx/SqlEditorPaneLifecycleTest.java`
- Modify test: `test/com/datacube/fx/SqlEditorSessionContractTest.java`

**Interfaces:**
- Consumes all earlier tasks.
- Produces the complete product flow while preserving `SerialSessionOperationQueue.OperationKind.EXECUTE` and existing close guards.

- [ ] **Step 1: Write failing editor contract tests**

Add tests that verify `SqlEditorPane`:

```java
@Test
void databaseFilterUsesOwnedSessionAndPreservesResultOnFailure() throws Exception {
    String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
    assertTrue(source.contains("SafeSelectEligibility.check"));
    assertTrue(source.contains("resultFilterRenderer"));
    assertTrue(source.contains("executePrepared"));
    assertTrue(source.contains("databaseFailed"));
    assertFalse(source.contains("DriverManager.getConnection"));
}

@Test
void resultToolbarIsInsideTheResultContainerAndSearchNeverSubmitsSessionWork() throws Exception {
    String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
    assertTrue(source.contains("new SqlResultToolbar"));
    assertTrue(source.contains("resultFilterState.setSearchText"));
    assertTrue(source.contains("TsvClipboardFormatter"));
}
```

Extend lifecycle contracts to assert filter execution uses the existing session queue and is covered by the same cancellation/close path.

- [ ] **Step 2: Run the editor contract tests and verify wiring is absent**

Run: `./gradlew test --tests "com.datacube.fx.SqlEditorResultFilterContractTest" --tests "com.datacube.fx.SqlEditorPaneLifecycleTest" --tests "com.datacube.fx.SqlEditorSessionContractTest" --no-daemon --console=plain`

Expected: the new contract test fails because the toolbar/state/renderer wiring is absent.

- [ ] **Step 3: Embed toolbar and render visible rows from raw results**

Add fields:

```java
private final ResultFilterState resultFilterState = new ResultFilterState();
private SqlResultToolbar resultToolbar;
private TableView<ObservableList<Object>> resultTable;
```

Build `new VBox(resultToolbar.getNode(), resultPane)` in `resultContainer`. `showQueryResult` stores the original result and SQL snapshot, computes `SafeSelectEligibility`, calls `resultFilterState.showOriginal`, and renders `snapshot.visibleRowIndexes()` by indexing raw `QueryResult.rows`. Column cell factories call `ResultValueFormatter.format`; they must not replace raw values with strings. Comment-mode changes rerender headers without resetting state.

Wire `addCondition` to `FilterConditionDialog.show`, append the returned condition to a copied list, and call `resultFilterState.setConditions`. Wire `removeCondition(index)` to remove exactly that index from a copied list. Both operations rerender only the current in-memory result until the user clicks the database button.

The existing top-level export and copy-INSERT actions use `snapshot.activeResult()`. Local search changes only `ResultFilterState` and table items; they do not call `submitSessionOperation`.

- [ ] **Step 4: Replace copy extraction with deterministic formatter calls**

Map JavaFX selected cells to `TsvClipboardFormatter.CellRef`, collect visible formatted rows in current table order, and dispatch the selected `CopyMode` to `rectangle` or `rows`. Preserve `Ctrl+C` as `SELECTION`. Update the status label with copied cell/row counts. Do not access JDBC or the original unfiltered ordering when copying.

- [ ] **Step 5: Wire explicit safe database filtering through the current session**

```java
private void onApplyDatabaseFilter() {
    ResultFilterState.DatabaseFilterRequest request = resultFilterState.databaseRequest();
    ConnConfig connection = admission.requireOpenPinned();
    SafeSelectEligibility.Result eligibility = SafeSelectEligibility.check(
            request.originalSql(), connection.type() == DbType.ORACLE,
            request.originalResult());
    if (!eligibility.eligible()) {
        resultFilterState.databaseFailed(eligibility.reason());
        renderResultFilterSnapshot();
        return;
    }
    ResultFilterSqlRenderer renderer = connections.provider(connection.id())
            .resultFilterSqlRenderer()
            .orElseThrow(() -> new IllegalStateException("当前数据库不支持结果筛选"));
    RenderedFilterQuery query = renderer.render(
            eligibility.normalizedSql(), request.originalResult().resultColumns,
            request.conditions());
    String schema = schemaField.getText().trim();
    submitSessionOperation(SerialSessionOperationQueue.OperationKind.EXECUTE,
            () -> ensureEditorSession().executePrepared(
                    query.sql(), query.parameters(),
                    schema.isEmpty() ? null : schema,
                    settings.getMaxResultRows()),
            this::onDatabaseFilterSucceeded,
            failure -> onDatabaseFilterFailed(message(failure)));
}
```

`onDatabaseFilterSucceeded` applies only `QUERY`; `ERROR`, `TIMEOUT`, and `CANCELLED` call `databaseFailed` and keep the previous table. While running, disable database reapply, add-filter, clear and normal execute controls consistently with the existing session snapshot. A callback after close or after a newer operation is already suppressed by `SerialSessionOperationQueue`; do not add a second executor.

When the filtered result has the same ordered labels as the original, carry forward the original `columnComments` with `withColumnComments` before `databaseApplied`; otherwise reject the result as an internal contract failure and preserve the previous table.

- [ ] **Step 6: Make reset and status behavior exact**

- A new user SQL result calls `showOriginal` and clears old filters.
- “清除筛选” restores the cached original result without JDBC.
- `QueryResult.truncated` and the retained snapshot's `rows.size()` format the status, for example `10,000+，当前结果已截断`; changing `settings.getMaxResultRows()` after execution does not rewrite that retained fact, and `rows.size() == cap` without `truncated` does not claim truncation.
- Normal user SQL errors keep their existing presentation; database-filter errors use the non-destructive inline toolbar/status path.
- Plan view, multi-statement summary, clear editor, tab close, and application exit call `resultFilterState.clearAll()` and disable the result toolbar.

- [ ] **Step 7: Run editor, session, and JavaFX tests**

Run: `./gradlew test --tests "com.datacube.fx.SqlResultToolbarTest" --tests "com.datacube.fx.SqlEditorResultFilterContractTest" --tests "com.datacube.fx.SqlEditorPaneLifecycleTest" --tests "com.datacube.fx.SqlEditorSessionContractTest" --tests "com.datacube.service.JdbcEditorSessionTest" --no-daemon --console=plain`

Expected: all selected tests pass; no JavaFX thread violation, timeout, or leaked task is reported.

- [ ] **Step 8: Commit the complete editor workflow**

```powershell
git add src/com/datacube/fx/SqlEditorPane.java test/com/datacube/fx/SqlEditorResultFilterContractTest.java test/com/datacube/fx/SqlEditorPaneLifecycleTest.java test/com/datacube/fx/SqlEditorSessionContractTest.java
git commit -m "feat: 接入 SQL 查询结果筛选工作流"
```

---

### Task 8: Document, verify, and review the finished slice

**Files:**
- Modify: `README.md:12-19, 89-97, 163-170`
- Create: `docs/superpowers/verification/2026-08-29-sql-result-filtering.md`

**Interfaces:**
- Consumes the completed implementation.
- Produces user-facing behavior documentation and an auditable verification record.

- [ ] **Step 1: Update README with exact product and safety behavior**

Add to the SQL editor feature and safe-session sections:

```markdown
- 查询结果支持当前已加载行的即时搜索、类型化条件、本地预览、单元格/行 TSV 复制，以及用户明确触发的数据库筛选。
- 数据库筛选只对可安全包装的单条只读 SELECT 开放；列名来自结果元数据，值使用 JDBC 参数绑定。全文搜索不会访问数据库。
- 达到结果读取上限时明确显示截断状态；筛选失败、超时或取消不会清空当前结果。
```

- [ ] **Step 2: Run focused result-filter tests**

Run: `./gradlew test --tests "com.datacube.spi.model.QueryResultMetadataTest" --tests "com.datacube.sqleditor.result.*" --tests "com.datacube.provider.*.*ResultFilterSqlRendererTest" --tests "com.datacube.provider.jdbc.JdbcPreparedQueryExecutorTest" --tests "com.datacube.fx.SqlResultToolbarTest" --tests "com.datacube.fx.SqlEditorResultFilterContractTest" --no-daemon --console=plain`

Expected: `BUILD SUCCESSFUL`; every new result-filter test passes.

- [ ] **Step 3: Run the full clean verification**

Run: `./gradlew clean test --no-daemon --console=plain`

Expected: `BUILD SUCCESSFUL`; no failed tests. External Oracle/PostgreSQL/Redis tests may report their existing explicit skips when credentials or services are absent.

- [ ] **Step 4: Write the verification record using the observed command output**

The record must contain these completed sections with the actual test counts, duration, skip reasons and commit IDs from Steps 2-3:

```markdown
# SQL Result Filtering Verification

## Scope

SQL result metadata, local filtering, safe provider rendering, parameterized session execution, JavaFX toolbar, non-destructive failures, copy behavior, and lifecycle regression.

## Commands and observed results

## Safety assertions

## External integration status

## Reviewed commits
```

Do not write expected counts as observed facts and do not include JDBC URLs, credentials, parameter values or saved connection properties.

- [ ] **Step 5: Inspect the final diff and working tree**

Run: `git diff --check HEAD~8..HEAD`

Expected: no whitespace errors.

Run: `git status --short --branch`

Expected: only the intended README and verification files are uncommitted; `.testagent/` may remain untracked and must not be staged.

- [ ] **Step 6: Commit documentation and verification**

```powershell
git add README.md docs/superpowers/verification/2026-08-29-sql-result-filtering.md
git commit -m "docs: 补齐查询结果筛选验收记录"
```

- [ ] **Step 7: Request final code review before push or tag**

Run: `git log --oneline --decorate -10`

Expected: one focused commit per task, ending with the verification commit. Review the eight-task range for security, lifecycle and product-spec compliance before any push or tag.

---

## Plan Completion Criteria

- All eight task commits exist and contain only their declared files.
- Local search, structured conditions, database filtering and copy follow the approved compact-toolbar design.
- Every database value is parameter-bound; eligibility failures are explicit and non-destructive.
- The editor continues using one owned `JdbcEditorSession` and one `SerialSessionOperationQueue`.
- Truncation is based on an observed extra row, not `rows.size() >= cap`.
- Focused and full Gradle test runs succeed.
- README and the verification record describe observed behavior without secrets or inflated result counts.
