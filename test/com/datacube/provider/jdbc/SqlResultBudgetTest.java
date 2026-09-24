package com.datacube.provider.jdbc;

import com.datacube.provider.postgres.*;
import com.datacube.provider.oracle.*;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.result.ResultExportValuePolicy;
import java.io.Reader;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultBudgetTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wideResultsStopReadingAndStopLaterStatementsWithExplicitEvidence(boolean oracle) {
        var jdbc = new Rows(1_000_000, 100_000, Types.CLOB, 1_000_000_000);
        var budget = new SqlResultBudget(new SqlResultBudget.Limits(9, 3, 6, 2, 300, 12));
        var events = new ArrayList<SqlScriptProgress>();
        var options = new SqlExecutionOptions(0, 0, new SqlExecutionControl(), budget, events::add);
        var outcomes = runner(oracle).executeScript(jdbc.connection(true), "select 1; select 2; select 3", null, options, null);
        assertEquals(1, jdbc.executions.get()); assertEquals(2, outcomes.size());
        var first = outcomes.getFirst().result();
        assertEquals(3, first.rows.size()); assertEquals(2, first.columns.size());
        assertTrue(first.truncated); assertFalse(first.retentionNotice.isEmpty());
        assertEquals(0, jdbc.objects.get(), "LOB must use a bounded reader, never materialize getObject");
        assertEquals(6 * 13, jdbc.characters.get()); assertEquals(6, jdbc.readerCloses.get());
        assertEquals(1, jdbc.resultCloses.get()); assertEquals(1, jdbc.statementCloses.get());
        assertTrue(outcomes.getLast().result().errorMessage.contains("2 条语句未执行"));
        assertEquals(3, budget.retainedRows()); assertEquals(6, budget.retainedCells());
        assertTrue(budget.retainedTextUnits() <= 300);
        assertEquals(2, events.size()); assertEquals(1, events.getLast().completed());
        assertEquals(3, events.getLast().total());
        assertFalse(ResultExportValuePolicy.assess(first.rows).sqlAllowed());
        assertThrows(UnsupportedOperationException.class, () -> events.getFirst().outcomes().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.rows.getFirst().clear());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resultCountBudgetDoesNotExecuteHiddenWritesAndPublishesEachCompletion(boolean oracle) {
        var jdbc = new Rows(0, 0, Types.INTEGER, 0);
        var budget = new SqlResultBudget(new SqlResultBudget.Limits(2, 10, 10, 4, 500, 30));
        var events = new ArrayList<SqlScriptProgress>();
        var options = new SqlExecutionOptions(0, 0, new SqlExecutionControl(), budget, event -> {
            assertEquals(jdbc.executions.get(), event.completed()); events.add(event);
        });
        var results = runner(oracle).executeScript(jdbc.connection(false),
                "update demo set n=1 where id=1; update demo set n=2 where id=2; update demo set n=3 where id=3", null, options, null);
        assertEquals(2, jdbc.executions.get()); assertEquals(3, results.size());
        assertEquals(List.of(1, 2, 2), events.stream().map(SqlScriptProgress::completed).toList());
        assertTrue(results.getLast().result().errorMessage.contains("第 3 条"));
        assertEquals(0, jdbc.transactionCalls.get(), "budget stop cannot commit or rollback");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completionIsVisibleBeforeNextExecuteAndCancellationPreventsTheNextStatement(boolean oracle) {
        var jdbc = new Rows(2, 1, Types.INTEGER, 0);
        var control = new SqlExecutionControl();
        var events = new ArrayList<SqlScriptProgress>();
        var options = new SqlExecutionOptions(10, 0, control, new SqlResultBudget(), event -> {
            assertEquals(1, jdbc.executions.get()); assertEquals(List.of(7), event.outcomes().getFirst().result().rows.getFirst());
            events.add(event);
            try { control.cancel(); } catch (SQLException e) { throw new AssertionError(e); }
        });
        var results = runner(oracle).executeScript(jdbc.connection(true), "select 1; select 2", null, options, null);
        assertEquals(1, results.size()); assertEquals(1, jdbc.executions.get()); assertEquals(1, events.size());
        assertFalse(control.hasActiveStatement());
    }

    @Test void textAllowanceSpansResultsAndSqlSummariesWithUnicodeSafePrefixes() throws Exception {
        var budget = new SqlResultBudget(new SqlResultBudget.Limits(4, 10, 10, 2, 36, 8));
        var first = new Rows(1, 1, Types.VARCHAR, 1_000_000);
        QueryResult a = QueryResult.fromResultSet(first.resultSet(), 0, 10, budget, new SqlExecutionControl());
        ScriptOutcome retained = budget.retain(1, "select '😀' " + "x".repeat(200), a);
        assertEquals(36, budget.retainedTextUnits()); assertTrue(budget.exhausted());
        assertTrue(retained.result().retentionNotice.contains("SQL 仅保留前缀"));
        assertFalse(Character.isHighSurrogate(retained.sql().charAt(retained.sql().length() - 1)));
        var second = new Rows(1_000_000, 1, Types.INTEGER, 0);
        QueryResult b = QueryResult.fromResultSet(second.resultSet(), 0, 10, budget, new SqlExecutionControl());
        assertEquals(0, second.objects.get()); assertTrue(b.rows.isEmpty()); assertTrue(b.truncated);
        assertEquals(36, budget.retainedTextUnits());
    }

    @Test void compositeTypesAreExplicitlyOmittedWithoutFetchingUnboundedObjects() throws Exception {
        var jdbc = new Rows(1, 1, Types.ARRAY, 0);
        var result = QueryResult.fromResultSet(jdbc.resultSet(), 0, 10, new SqlResultBudget(), new SqlExecutionControl());
        assertEquals(0, jdbc.objects.get());
        assertInstanceOf(ResultValuePreview.class, result.rows.getFirst().getFirst());
        assertFalse(result.retentionNotice.isEmpty());
        assertFalse(ResultExportValuePolicy.assess(result.rows).sqlAllowed());
    }

    @Test void cancellationDuringLobReadClosesReaderResultAndStatement() {
        var jdbc = new Rows(1, 1, Types.CLOB, 1_000_000);
        var control = new SqlExecutionControl();
        jdbc.onRead = () -> { try { control.cancel(); } catch (SQLException e) { throw new AssertionError(e); } };
        var result = runner(false).execute(jdbc.connection(true), "select 1", null, new SqlExecutionOptions(0, 0, control));
        assertEquals(QueryResult.FailureKind.CANCELLED, result.failureKind);
        assertEquals(1, jdbc.readerCloses.get()); assertEquals(1, jdbc.resultCloses.get());
        assertEquals(1, jdbc.statementCloses.get()); assertFalse(control.hasActiveStatement());
    }

    @Test void defaultBatchStopsBeforeTheOverviewCouldHideItsBudgetNotice() {
        var jdbc = new Rows(0, 0, Types.INTEGER, 0);
        var results = runner(false).executeScript(jdbc.connection(false),
                "update demo set n=1 where id=1;".repeat(1100), null, SqlExecutionOptions.defaults(0), null);
        assertEquals(999, jdbc.executions.get()); assertEquals(1000, results.size());
        var report = com.datacube.sqleditor.SqlScriptExecutionReport.capture(results, 0);
        assertEquals(1000, report.entries().size());
        assertTrue(report.entries().getLast().resultDescription().contains("101 条语句未执行"));
    }

    @Test void scalarAndAnnotationTextShareAllowanceAndPreviewCannotSplitASurrogate() throws Exception {
        var text = new Rows(1, 1, Types.VARCHAR, 4); text.payload = "A😀Z";
        var budget = new SqlResultBudget(new SqlResultBudget.Limits(5, 10, 10, 2, 30, 2));
        var result = QueryResult.fromResultSet(text.resultSet(), 0, 10, budget, new SqlExecutionControl());
        assertEquals("A", ((ResultValuePreview) result.rows.getFirst().getFirst()).text());
        var number = new Rows(1, 1, Types.NUMERIC, 0); number.scalar = new java.math.BigDecimal("123456789012345678901234567890");
        var numeric = QueryResult.fromResultSet(number.resultSet(), 0, 10, budget, new SqlExecutionControl());
        assertInstanceOf(ResultValuePreview.class, numeric.rows.getFirst().getFirst());
        assertTrue(budget.retainedTextUnits() <= 30);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void optionalColumnCommentsHaveRowAndTextBoundsAndAnOmissionNotice(boolean oracle) {
        var jdbc = new Rows(1, 1, Types.INTEGER, 0); jdbc.comments = true;
        var budget = new SqlResultBudget(new SqlResultBudget.Limits(5, 10, 10, 2, 600, 32));
        var result = runner(oracle).execute(jdbc.connection(true), "select 1", null,
                new SqlExecutionOptions(10, 0, new SqlExecutionControl(), budget, ignored -> {}));
        assertEquals(QueryResult.Kind.QUERY, result.kind); assertEquals(1, result.rows.size());
        assertEquals(2, jdbc.commentsRead.get()); assertTrue(budget.retainedTextUnits() <= 600);
        assertTrue(result.retentionNotice.contains("列注释"));
        assertTrue(result.columnComments.getFirst().length() <= 512);
    }

    private static SqlRunner runner(boolean oracle) {
        return oracle ? new OracleSqlRunner(new OracleSqlDialect()) : new PgSqlRunner(new PgSqlDialect());
    }

    static final class Rows {
        final int rowCount, columnCount, type, width;
        final AtomicInteger executions = new AtomicInteger(), objects = new AtomicInteger(), characters = new AtomicInteger();
        final AtomicInteger readerCloses = new AtomicInteger(), resultCloses = new AtomicInteger(), statementCloses = new AtomicInteger();
        final AtomicInteger transactionCalls = new AtomicInteger();
        final AtomicInteger commentsRead = new AtomicInteger();
        boolean comments;
        String payload;
        Object scalar = 7;
        Runnable onRead = () -> {};
        Rows(int rows, int columns, int type, int width) { rowCount = rows; columnCount = columns; this.type = type; this.width = width; }
        Connection connection(boolean query) {
            return proxy(Connection.class, (p, m, args) -> switch (m.getName()) {
                case "prepareStatement" -> proxy(PreparedStatement.class, (s, method, a) -> switch (method.getName()) {
                    case "executeQuery" -> proxy(ResultSet.class, (r, rm, ra) -> switch (rm.getName()) {
                        case "next" -> { commentsRead.incrementAndGet(); yield true; }
                        case "getCharacterStream" -> new java.io.StringReader("x".repeat(5000));
                        case "getString" -> switch ((String) ra[0]) {
                            case "descr", "COMMENTS" -> "x".repeat(5000);
                            case "s", "OWNER" -> "s";
                            case "t", "TABLE_NAME" -> "t";
                            default -> "c";
                        };
                        default -> zero(rm.getReturnType());
                    });
                    default -> zero(method.getReturnType());
                });
                case "createStatement" -> proxy(Statement.class, (s, method, a) -> switch (method.getName()) {
                    case "execute" -> { executions.incrementAndGet(); yield query; }
                    case "getResultSet" -> resultSet();
                    case "getUpdateCount" -> 1;
                    case "close" -> { statementCloses.incrementAndGet(); yield null; }
                    default -> zero(method.getReturnType());
                });
                case "commit", "rollback" -> { transactionCalls.incrementAndGet(); yield null; }
                default -> zero(m.getReturnType());
            });
        }
        ResultSet resultSet() {
            var position = new AtomicInteger();
            var metadata = proxy(ResultSetMetaData.class, (p, m, a) -> switch (m.getName()) {
                case "getColumnCount" -> columnCount;
                case "getColumnType" -> type;
                case "getColumnTypeName" -> "v";
                case "getColumnLabel", "getColumnName" -> "c";
                case "getTableName" -> comments ? "t" : "";
                case "getSchemaName" -> comments ? "s" : "";
                default -> zero(m.getReturnType());
            });
            return proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
                case "getMetaData" -> metadata;
                case "next" -> position.getAndIncrement() < rowCount;
                case "getObject" -> { objects.incrementAndGet(); yield scalar; }
                case "getCharacterStream" -> payload != null ? new java.io.StringReader(payload) : new Reader() {
                    int left = width;
                    @Override public int read(char[] target, int offset, int length) {
                        onRead.run(); if (left == 0) return -1;
                        int count = Math.min(length, left); java.util.Arrays.fill(target, offset, offset + count, 'x');
                        left -= count; characters.addAndGet(count); return count;
                    }
                    @Override public void close() { readerCloses.incrementAndGet(); }
                };
                case "close" -> { resultCloses.incrementAndGet(); yield null; }
                default -> zero(m.getReturnType());
            });
        }
    }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object zero(Class<?> type) {
        if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return null;
    }
}
