package com.datacube.provider.oracle;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class OracleSqlRunnerExecutionControlTest {
    private final OracleSqlRunner runner = new OracleSqlRunner(new OracleSqlDialect());

    @Test
    void appliesTimeoutToSchemaUserSqlAndBothExplainPathsWithoutChangingReadOnlyState() {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();
        SqlExecutionOptions options = new SqlExecutionOptions(20, 11, control);

        QueryResult update = runner.execute(jdbc.connection(), "UPDATE things SET active = 1;", "App", options);
        QueryResult estimated = runner.explain(jdbc.connection(), "SELECT 1;", "App", false, options);
        QueryResult analyzed = runner.explain(jdbc.connection(), "SELECT 2;", "App", true, options);

        assertEquals(QueryResult.Kind.UPDATE, update.kind);
        assertEquals(QueryResult.Kind.UPDATE, estimated.kind);
        assertEquals(QueryResult.Kind.UPDATE, analyzed.kind);
        assertEquals(List.of(
                "ALTER SESSION SET CURRENT_SCHEMA = \"APP\"",
                "UPDATE things SET active = 1",
                "ALTER SESSION SET CURRENT_SCHEMA = \"APP\"",
                "EXPLAIN PLAN FOR SELECT 1",
                "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())",
                "ALTER SESSION SET CURRENT_SCHEMA = \"APP\"",
                "ALTER SESSION SET STATISTICS_LEVEL = ALL",
                "SELECT 2",
                "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))"),
                jdbc.executedSql);
        assertEquals(List.of(11, 11, 11, 11, 11, 11, 11, 11, 11), jdbc.timeouts);
        assertEquals(0, jdbc.readOnlyWrites.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void mapsJdbcTimeoutAndCancellationAndReleasesTheStatement() {
        JdbcScenario timeoutJdbc = new JdbcScenario();
        timeoutJdbc.failure = sql -> new SQLTimeoutException("too slow");
        SqlExecutionControl timeoutControl = new SqlExecutionControl();

        QueryResult timeout = runner.execute(timeoutJdbc.connection(), "SELECT slow()", null,
                new SqlExecutionOptions(0, 3, timeoutControl));

        assertEquals(QueryResult.FailureKind.TIMEOUT, timeout.failureKind);
        assertFalse(timeoutControl.hasActiveStatement());

        JdbcScenario cancelJdbc = new JdbcScenario();
        SqlExecutionControl cancelControl = new SqlExecutionControl();
        cancelJdbc.beforeExecute = sql -> {
            try {
                assertTrue(cancelControl.cancel());
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        };
        cancelJdbc.failure = sql -> new SQLException("cancelled by driver");

        QueryResult cancelled = runner.execute(cancelJdbc.connection(), "DELETE FROM things", null,
                new SqlExecutionOptions(0, 0, cancelControl));

        assertEquals(QueryResult.FailureKind.CANCELLED, cancelled.failureKind);
        assertEquals(1, cancelJdbc.cancelCalls.get());
        assertFalse(cancelControl.hasActiveStatement());
    }

    @Test
    void cancelBeforeStatementPublicationReturnsCancelledWithoutExecutingSql() throws Exception {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();
        assertFalse(control.cancel());

        QueryResult result = runner.execute(jdbc.connection(), "DELETE FROM things", null,
                new SqlExecutionOptions(0, 0, control));

        assertEquals(QueryResult.FailureKind.CANCELLED, result.failureKind);
        assertTrue(jdbc.executedSql.isEmpty());
        assertEquals(1, jdbc.cancelCalls.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void cancellationDuringFirstAnalyzeStageSkipsUserSqlAndDisplayCursor() {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();
        jdbc.beforeExecute = sql -> {
            if (sql.equals("ALTER SESSION SET STATISTICS_LEVEL = ALL")) cancel(control);
        };

        QueryResult result = runner.explain(jdbc.connection(), "SELECT 1", null, true,
                new SqlExecutionOptions(0, 0, control));

        assertEquals(QueryResult.FailureKind.CANCELLED, result.failureKind);
        assertEquals(List.of("ALTER SESSION SET STATISTICS_LEVEL = ALL"), jdbc.executedSql);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void cancelledScriptStopsWithoutConsultingContinueAll() {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();
        AtomicInteger policyCalls = new AtomicInteger();
        jdbc.beforeExecute = sql -> cancel(control);
        jdbc.failure = sql -> new SQLException("cancelled by driver");

        List<ScriptOutcome> outcomes = runner.executeScript(jdbc.connection(),
                "UPDATE first_table SET value = 1; UPDATE second_table SET value = 2;", null,
                new SqlExecutionOptions(0, 0, control), (index, sql, message) -> {
                    policyCalls.incrementAndGet();
                    return com.datacube.spi.ScriptErrorPolicy.Decision.CONTINUE_ALL;
                });

        assertEquals(1, outcomes.size());
        assertEquals(QueryResult.FailureKind.CANCELLED, outcomes.getFirst().result().failureKind);
        assertEquals(List.of("UPDATE first_table SET value = 1"), jdbc.executedSql);
        assertEquals(0, policyCalls.get());
    }

    @Test
    void terminalCancellationBetweenScriptStatementsPreventsTheNextSql() {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();
        jdbc.beforeExecute = sql -> cancel(control);

        List<ScriptOutcome> outcomes = runner.executeScript(jdbc.connection(),
                "UPDATE first_table SET value = 1; UPDATE second_table SET value = 2;", null,
                new SqlExecutionOptions(0, 0, control), (index, sql, message) ->
                        com.datacube.spi.ScriptErrorPolicy.Decision.CONTINUE_ALL);

        assertEquals(1, outcomes.size());
        assertEquals(QueryResult.Kind.UPDATE, outcomes.getFirst().result().kind);
        assertEquals(List.of("UPDATE first_table SET value = 1"), jdbc.executedSql);
    }

    private static void cancel(SqlExecutionControl control) {
        try {
            assertTrue(control.cancel());
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static final class JdbcScenario {
        private final List<String> executedSql = new ArrayList<>();
        private final List<Integer> timeouts = new ArrayList<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger readOnlyWrites = new AtomicInteger();
        private Consumer<String> beforeExecute = sql -> { };
        private Function<String, SQLException> failure = sql -> null;

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "createStatement" -> statement();
                            case "isReadOnly" -> true;
                            case "setReadOnly" -> {
                                readOnlyWrites.incrementAndGet();
                                yield null;
                            }
                            case "isClosed" -> false;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        private Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "setQueryTimeout" -> {
                                timeouts.add((Integer) args[0]);
                                yield null;
                            }
                            case "execute" -> {
                                String sql = (String) args[0];
                                executedSql.add(sql);
                                beforeExecute.accept(sql);
                                SQLException error = failure.apply(sql);
                                if (error != null) throw error;
                                yield false;
                            }
                            case "cancel" -> {
                                cancelCalls.incrementAndGet();
                                yield null;
                            }
                            case "getUpdateCount" -> 1;
                            case "isClosed" -> false;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
