package com.datacube.provider.postgres;

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

class PgSqlRunnerExecutionControlTest {
    private final PgSqlRunner runner = new PgSqlRunner(new PgSqlDialect());

    @Test
    void appliesTimeoutToSchemaUserSqlAndExplainWithoutChangingReadOnlyState() {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();
        SqlExecutionOptions options = new SqlExecutionOptions(20, 7, control);

        QueryResult update = runner.execute(jdbc.connection(), "UPDATE things SET active = true", "App", options);
        QueryResult explain = runner.explain(jdbc.connection(), "SELECT 1", "App", false, options);

        assertEquals(QueryResult.Kind.UPDATE, update.kind);
        assertEquals(QueryResult.Kind.UPDATE, explain.kind);
        assertEquals(List.of(
                "SET search_path TO \"app\"",
                "UPDATE things SET active = true",
                "SET search_path TO \"app\"",
                "EXPLAIN SELECT 1"), jdbc.executedSql);
        assertEquals(List.of(7, 7, 7, 7), jdbc.timeouts);
        assertEquals(List.of(21, 21), jdbc.maxRows);
        assertTrue(jdbc.events.indexOf("setMaxRows:21")
                        < jdbc.events.indexOf("execute:UPDATE things SET active = true"),
                "row bound must be configured before the user statement executes");
        assertEquals(0, jdbc.readOnlyWrites.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void regularExecutionSkipsUnlimitedBoundAndSaturatesOverflow() {
        JdbcScenario unlimited = new JdbcScenario();
        QueryResult unlimitedResult = runner.execute(unlimited.connection(), "UPDATE things SET active = true",
                null, SqlExecutionOptions.defaults(0));
        assertEquals(QueryResult.Kind.UPDATE, unlimitedResult.kind);
        assertTrue(unlimited.maxRows.isEmpty());

        JdbcScenario overflow = new JdbcScenario();
        QueryResult overflowResult = runner.execute(overflow.connection(), "UPDATE things SET active = true",
                null, SqlExecutionOptions.defaults(Integer.MAX_VALUE));
        assertEquals(QueryResult.Kind.UPDATE, overflowResult.kind);
        assertEquals(List.of(Integer.MAX_VALUE), overflow.maxRows);
    }

    @Test
    void regularQueryDistinguishesExactCapFromCapPlusOne() {
        JdbcScenario exact = new JdbcScenario();
        exact.queryResult = true;
        exact.availableRows = 2;
        QueryResult exactResult = runner.execute(
                exact.connection(), "SELECT 1", null, SqlExecutionOptions.defaults(2));
        assertEquals(3, exact.maxRows.getFirst());
        assertEquals(2, exactResult.rows.size());
        assertFalse(exactResult.truncated);

        JdbcScenario overflow = new JdbcScenario();
        overflow.queryResult = true;
        overflow.availableRows = 3;
        QueryResult overflowResult = runner.execute(
                overflow.connection(), "SELECT 1", null, SqlExecutionOptions.defaults(2));
        assertEquals(3, overflow.maxRows.getFirst());
        assertEquals(2, overflowResult.rows.size());
        assertTrue(overflowResult.truncated);
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
    void preparedExecutionAppliesSchemaBindsInOrderAndUsesTheSharedControl() {
        JdbcScenario jdbc = new JdbcScenario();
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = runner.executePrepared(
                jdbc.connection(), "select * from things where id > ? and name = ?",
                List.of(new SqlParameter(Types.INTEGER, 10),
                        new SqlParameter(Types.VARCHAR, "Ada")), "App",
                new SqlExecutionOptions(25, 7, control));

        assertEquals(QueryResult.Kind.QUERY, result.kind);
        assertEquals(List.of("SET search_path TO \"app\""), jdbc.executedSql);
        assertEquals(List.of("select * from things where id > ? and name = ?"), jdbc.preparedSql);
        assertEquals(List.of(10, "Ada"), jdbc.boundValues);
        assertEquals(List.of(7, 7), jdbc.timeouts);
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
    void cancelDuringColumnCommentsTargetsTheCommentStatement() throws Exception {
        BlockingCommentJdbc jdbc = BlockingCommentJdbc.blocking();
        SqlExecutionControl control = new SqlExecutionControl();
        SqlExecutionOptions options = new SqlExecutionOptions(100, 7, control);
        AtomicReference<QueryResult> result = new AtomicReference<>();

        Thread worker = Thread.startVirtualThread(
                () -> result.set(runner.execute(jdbc.connection(), "select id from t", null, options)));
        assertTrue(jdbc.commentQueryStarted.await(2, TimeUnit.SECONDS));
        assertTrue(control.hasActiveStatement(), "comment statement must own the active activation");
        assertTrue(control.cancel());
        worker.join(2_000);
        boolean finishedAfterCancel = !worker.isAlive();
        jdbc.forceRelease.countDown();
        worker.join(2_000);

        assertFalse(worker.isAlive(), "test cleanup must not leave the virtual thread running");
        assertTrue(finishedAfterCancel, "cancelling the comment statement must unblock the runner");
        assertEquals(0, jdbc.mainStatementCancels.get());
        assertEquals(1, jdbc.commentStatementCancels.get());
        assertEquals(7, jdbc.commentQueryTimeout.get());
        assertEquals(QueryResult.FailureKind.CANCELLED, result.get().failureKind);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void commentTimeoutIsReportedAndReleasesItsActivation() {
        BlockingCommentJdbc jdbc = BlockingCommentJdbc.timingOut();
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = runner.execute(jdbc.connection(), "select id from t", null,
                new SqlExecutionOptions(100, 9, control));

        assertEquals(QueryResult.FailureKind.TIMEOUT, result.failureKind);
        assertEquals(9, jdbc.commentQueryTimeout.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void ordinaryCommentSqlFailureRemainsBestEffort() {
        BlockingCommentJdbc jdbc = BlockingCommentJdbc.failing();
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = runner.execute(jdbc.connection(), "select id from t", null,
                new SqlExecutionOptions(100, 5, control));

        assertEquals(QueryResult.Kind.QUERY, result.kind);
        assertEquals(List.of("id"), result.columns);
        assertTrue(result.columnComments.isEmpty());
        assertEquals(5, jdbc.commentQueryTimeout.get());
        assertFalse(control.hasActiveStatement());
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
        private final List<Integer> maxRows = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final List<String> preparedSql = new ArrayList<>();
        private final List<Object> boundValues = new ArrayList<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger readOnlyWrites = new AtomicInteger();
        private Consumer<String> beforeExecute = sql -> { };
        private Function<String, SQLException> failure = sql -> null;
        private boolean queryResult;
        private int availableRows;

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
                            case "setMaxRows" -> {
                                maxRows.add((Integer) args[0]);
                                events.add("setMaxRows:" + args[0]);
                                yield null;
                            }
                            case "execute" -> {
                                String sql = (String) args[0];
                                events.add("execute:" + sql);
                                executedSql.add(sql);
                                beforeExecute.accept(sql);
                                SQLException error = failure.apply(sql);
                                if (error != null) throw error;
                                yield queryResult;
                            }
                            case "getResultSet" -> queryResultSet();
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
            ResultSetMetaData metadata = metadata("public", "things");
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> metadata;
                        case "next" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet queryResultSet() {
            AtomicInteger row = new AtomicInteger();
            ResultSetMetaData metadata = metadata("", "");
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> metadata;
                        case "next" -> row.getAndIncrement() < availableRows;
                        case "getObject" -> row.get();
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSetMetaData metadata(String schema, String table) {
            return (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getColumnCount" -> 1;
                        case "getColumnLabel", "getColumnName" -> "id";
                        case "getColumnType" -> Types.INTEGER;
                        case "getColumnTypeName" -> "integer";
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
        private final SQLException immediateFailure;

        private BlockingCommentJdbc(SQLException immediateFailure) {
            this.immediateFailure = immediateFailure;
        }

        private static BlockingCommentJdbc blocking() {
            return new BlockingCommentJdbc(null);
        }

        private static BlockingCommentJdbc timingOut() {
            return new BlockingCommentJdbc(new SQLTimeoutException("comment lookup timed out"));
        }

        private static BlockingCommentJdbc failing() {
            return new BlockingCommentJdbc(new SQLException("comment metadata unavailable"));
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "createStatement" -> mainStatement();
                        case "prepareStatement" -> commentStatement();
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
            ResultSetMetaData metadata = metadata("public", "t");
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
