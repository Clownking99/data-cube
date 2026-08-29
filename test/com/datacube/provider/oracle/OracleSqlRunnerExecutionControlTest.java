package com.datacube.provider.oracle;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlParameter;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void preparedExecutionAppliesSchemaStripsSqlBindsInOrderAndUsesTheSharedControl() {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = runner.executePrepared(
                jdbc.connection(), " select * from things where id > ? and name = ?; ",
                List.of(new SqlParameter(Types.INTEGER, 10),
                        new SqlParameter(Types.VARCHAR, "Ada")), "App",
                new SqlExecutionOptions(25, 11, control));

        assertEquals(QueryResult.Kind.QUERY, result.kind);
        assertEquals(List.of("ALTER SESSION SET CURRENT_SCHEMA = \"APP\""), jdbc.executedSql);
        assertEquals(List.of("select * from things where id > ? and name = ?"), jdbc.preparedSql);
        assertEquals(List.of(10, "Ada"), jdbc.boundValues);
        assertEquals(List.of(11, 11), jdbc.timeouts);
        assertFalse(control.hasActiveStatement());
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

    @Test
    void invalidOracleCommentPrefixProducesAnOutcomeAndReachesJdbc() {
        JdbcScenario jdbc = new JdbcScenario();
        jdbc.failure = sql -> new SQLException("invalid nested comment");
        String invalid = "/* outer /* inner */ tail */ DELETE FROM things;";

        List<ScriptOutcome> outcomes = runner.executeScript(jdbc.connection(), invalid, null,
                new SqlExecutionOptions(0, 0, new SqlExecutionControl()), null);

        assertEquals(1, outcomes.size());
        assertEquals(QueryResult.FailureKind.SQL_ERROR,
                outcomes.getFirst().result().failureKind);
        assertEquals(List.of("/* outer /* inner */ tail */ DELETE FROM things"),
                jdbc.executedSql);
    }

    @Test
    void cancelDuringBothColumnCommentPathsTargetsTheCommentStatement() throws Exception {
        for (boolean metadataPath : List.of(true, false)) {
            BlockingCommentJdbc jdbc = BlockingCommentJdbc.blocking(metadataPath);
            SqlExecutionControl control = new SqlExecutionControl();
            SqlExecutionOptions options = new SqlExecutionOptions(100, 7, control);
            AtomicReference<QueryResult> result = new AtomicReference<>();

            Thread worker = Thread.startVirtualThread(() -> result.set(
                    runner.execute(jdbc.connection(), "select id from t", "App", options)));
            assertTrue(jdbc.commentQueryStarted.await(2, TimeUnit.SECONDS), path(metadataPath));
            assertTrue(control.hasActiveStatement(), "comment statement must own " + path(metadataPath));
            assertTrue(control.cancel(), path(metadataPath));
            worker.join(2_000);
            boolean finishedAfterCancel = !worker.isAlive();
            jdbc.forceRelease.countDown();
            worker.join(2_000);

            assertFalse(worker.isAlive(), "test cleanup must not leave " + path(metadataPath) + " running");
            assertTrue(finishedAfterCancel, "cancel must unblock " + path(metadataPath));
            assertEquals(0, jdbc.mainStatementCancels.get(), path(metadataPath));
            assertEquals(1, jdbc.commentStatementCancels.get(), path(metadataPath));
            assertEquals(7, jdbc.commentQueryTimeout.get(), path(metadataPath));
            assertEquals(QueryResult.FailureKind.CANCELLED,
                    result.get().failureKind, path(metadataPath));
            assertFalse(control.hasActiveStatement(), path(metadataPath));
        }
    }

    @Test
    void timeoutOnBothColumnCommentPathsIsReportedAndReleasesActivation() {
        for (boolean metadataPath : List.of(true, false)) {
            BlockingCommentJdbc jdbc = BlockingCommentJdbc.timingOut(metadataPath);
            SqlExecutionControl control = new SqlExecutionControl();

            QueryResult result = runner.execute(jdbc.connection(), "select id from t", "App",
                    new SqlExecutionOptions(100, 13, control));

            assertEquals(QueryResult.FailureKind.TIMEOUT, result.failureKind, path(metadataPath));
            assertEquals(13, jdbc.commentQueryTimeout.get(), path(metadataPath));
            assertFalse(control.hasActiveStatement(), path(metadataPath));
        }
    }

    @Test
    void ordinarySqlFailureOnBothCommentPathsRemainsBestEffort() {
        for (boolean metadataPath : List.of(true, false)) {
            BlockingCommentJdbc jdbc = BlockingCommentJdbc.failing(metadataPath);
            SqlExecutionControl control = new SqlExecutionControl();

            QueryResult result = runner.execute(jdbc.connection(), "select id from t", "App",
                    new SqlExecutionOptions(100, 17, control));

            assertEquals(QueryResult.Kind.QUERY, result.kind, path(metadataPath));
            assertEquals(List.of("id"), result.columns, path(metadataPath));
            assertTrue(result.columnComments.isEmpty(), path(metadataPath));
            assertEquals(17, jdbc.commentQueryTimeout.get(), path(metadataPath));
            assertFalse(control.hasActiveStatement(), path(metadataPath));
        }
    }

    private static String path(boolean metadataPath) {
        return metadataPath ? "metadata comment path" : "single-table fallback comment path";
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
        private final List<String> preparedSql = new ArrayList<>();
        private final List<Object> boundValues = new ArrayList<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger readOnlyWrites = new AtomicInteger();
        private Consumer<String> beforeExecute = sql -> { };
        private Function<String, SQLException> failure = sql -> null;

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "createStatement" -> statement();
                            case "prepareStatement" -> {
                                preparedSql.add((String) args[0]);
                                yield preparedStatement();
                            }
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

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setQueryTimeout" -> {
                            timeouts.add((Integer) args[0]);
                            yield null;
                        }
                        case "setObject" -> {
                            boundValues.add(args[1]);
                            yield null;
                        }
                        case "setNull" -> {
                            boundValues.add(null);
                            yield null;
                        }
                        case "executeQuery" -> emptyResultSet();
                        case "cancel" -> {
                            cancelCalls.incrementAndGet();
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet emptyResultSet() {
            ResultSetMetaData metadata = metadata("APP", "THINGS");
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> metadata;
                        case "next" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSetMetaData metadata(String schema, String table) {
            return (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getColumnCount" -> 1;
                        case "getColumnLabel", "getColumnName" -> "ID";
                        case "getColumnType" -> Types.INTEGER;
                        case "getColumnTypeName" -> "NUMBER";
                        case "getSchemaName" -> schema;
                        case "getTableName" -> table;
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static final class BlockingCommentJdbc {
        private final CountDownLatch commentQueryStarted = new CountDownLatch(1);
        private final CountDownLatch commentStatementCancelled = new CountDownLatch(1);
        private final CountDownLatch forceRelease = new CountDownLatch(1);
        private final AtomicInteger mainStatementCancels = new AtomicInteger();
        private final AtomicInteger commentStatementCancels = new AtomicInteger();
        private final AtomicInteger commentQueryTimeout = new AtomicInteger(-1);
        private final boolean metadataPath;
        private final SQLException immediateFailure;

        private BlockingCommentJdbc(boolean metadataPath, SQLException immediateFailure) {
            this.metadataPath = metadataPath;
            this.immediateFailure = immediateFailure;
        }

        private static BlockingCommentJdbc blocking(boolean metadataPath) {
            return new BlockingCommentJdbc(metadataPath, null);
        }

        private static BlockingCommentJdbc timingOut(boolean metadataPath) {
            return new BlockingCommentJdbc(
                    metadataPath, new SQLTimeoutException("comment lookup timed out"));
        }

        private static BlockingCommentJdbc failing(boolean metadataPath) {
            return new BlockingCommentJdbc(
                    metadataPath, new SQLException("comment metadata unavailable"));
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "createStatement" -> mainStatement();
                        case "prepareStatement" -> commentStatement();
                        case "getSchema" -> "APP";
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private Statement mainStatement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "execute" -> true;
                        case "getResultSet" -> mainResultSet();
                        case "cancel" -> {
                            mainStatementCancels.incrementAndGet();
                            yield null;
                        }
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement commentStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setQueryTimeout" -> {
                            commentQueryTimeout.set((Integer) args[0]);
                            yield null;
                        }
                        case "executeQuery" -> executeCommentQuery();
                        case "cancel" -> {
                            commentStatementCancels.incrementAndGet();
                            commentStatementCancelled.countDown();
                            yield null;
                        }
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet executeCommentQuery() throws Exception {
            commentQueryStarted.countDown();
            if (immediateFailure != null) throw immediateFailure;
            while (!commentStatementCancelled.await(25, TimeUnit.MILLISECONDS)) {
                if (forceRelease.getCount() == 0) return emptyResultSet();
            }
            throw new SQLException("comment lookup cancelled");
        }

        private ResultSet mainResultSet() {
            AtomicInteger next = new AtomicInteger();
            ResultSetMetaData metadata = metadataPath
                    ? metadata("APP", "T")
                    : metadata("", "");
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> metadata;
                        case "next" -> next.getAndIncrement() == 0;
                        case "getObject" -> 1;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSetMetaData metadata(String schema, String table) {
            return (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getColumnCount" -> 1;
                        case "getColumnLabel", "getColumnName" -> "id";
                        case "getColumnType" -> Types.INTEGER;
                        case "getSchemaName" -> schema;
                        case "getTableName" -> table;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet emptyResultSet() {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) ->
                            method.getName().equals("next") ? false : defaultValue(method.getReturnType()));
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
