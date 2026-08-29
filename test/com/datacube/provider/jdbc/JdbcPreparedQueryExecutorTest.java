package com.datacube.provider.jdbc;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlParameter;
import com.datacube.spi.model.QueryResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JdbcPreparedQueryExecutorTest {

    @Test
    void bindsInOrderActivatesControlAndReadsBoundedResult() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        SqlExecutionControl control = new SqlExecutionControl();
        SqlExecutionOptions options = new SqlExecutionOptions(2, 7, control);

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q where id > ? and name = ?",
                List.of(new SqlParameter(Types.INTEGER, 10),
                        new SqlParameter(Types.VARCHAR, "Ada")), options);

        assertEquals(List.of(1, 2), jdbc.boundIndexes);
        assertEquals(List.of(10, "Ada"), jdbc.boundValues);
        assertEquals(List.of(Types.INTEGER, Types.VARCHAR), jdbc.boundTypes);
        assertEquals(List.of("setObject", "setObject"), jdbc.boundMethods);
        assertEquals(7, jdbc.queryTimeout);
        assertEquals(3, jdbc.maxRows);
        assertTrue(jdbc.events.indexOf("setMaxRows:3") < jdbc.events.indexOf("executeQuery"),
                "driver row bound must be configured before execution");
        assertEquals(QueryResult.Kind.QUERY, result.kind);
        assertEquals(2, result.rows.size());
        assertTrue(result.truncated);
        assertEquals(1, jdbc.statementCloses.get());
        assertEquals(1, jdbc.resultSetCloses.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void driverRowBoundPreservesExactUnlimitedAndOverflowCases() {
        RecordingPreparedJdbc exact = new RecordingPreparedJdbc();
        exact.availableRows = 2;
        QueryResult exactResult = JdbcPreparedQueryExecutor.execute(
                exact.connection(), "select * from q", List.of(),
                SqlExecutionOptions.defaults(2));
        assertEquals(3, exact.maxRows);
        assertEquals(2, exactResult.rows.size());
        assertFalse(exactResult.truncated, "an exact cap must not be reported as truncated");

        RecordingPreparedJdbc unlimited = new RecordingPreparedJdbc();
        QueryResult unlimitedResult = JdbcPreparedQueryExecutor.execute(
                unlimited.connection(), "select * from q", List.of(),
                SqlExecutionOptions.defaults(0));
        assertEquals(-1, unlimited.maxRows, "unlimited execution must not configure a JDBC cap");
        assertEquals(3, unlimitedResult.rows.size());
        assertFalse(unlimitedResult.truncated);

        RecordingPreparedJdbc overflow = new RecordingPreparedJdbc();
        overflow.availableRows = 0;
        QueryResult overflowResult = JdbcPreparedQueryExecutor.execute(
                overflow.connection(), "select * from q", List.of(),
                SqlExecutionOptions.defaults(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, overflow.maxRows,
                "maxRows + 1 must saturate instead of overflowing");
        assertEquals(QueryResult.Kind.QUERY, overflowResult.kind);
    }

    @Test
    void bindsNullWithSetNullAndNonNullWithSetObject() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q where optional_name = ? and id = ?",
                List.of(new SqlParameter(Types.VARCHAR, null),
                        new SqlParameter(Types.INTEGER, 42)),
                new SqlExecutionOptions(0, 0, control));

        assertEquals(QueryResult.Kind.QUERY, result.kind);
        assertEquals(List.of(1, 2), jdbc.boundIndexes);
        assertEquals(java.util.Arrays.asList(null, 42), jdbc.boundValues);
        assertEquals(List.of(Types.VARCHAR, Types.INTEGER), jdbc.boundTypes);
        assertEquals(List.of("setNull", "setObject"), jdbc.boundMethods);
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void bindingFailureClosesStatementReleasesActivationAndKeepsPrimaryError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.bindFailureIndex = 2;
        jdbc.bindFailure = new SQLException("bind failed", "22000", 1);
        jdbc.statementCloseFailure = new SQLException("statement close also failed", "HY000", 99);
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q where id = ? and name = ?",
                List.of(new SqlParameter(Types.INTEGER, 42),
                        new SqlParameter(Types.VARCHAR, "Ada")),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR,
                "数据库查询失败 (SQLState=22000, vendorCode=1)");
        assertEquals(1, jdbc.statementCloses.get());
        assertEquals(0, jdbc.resultSetCloses.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void ordinaryExecuteFailureClosesStatementAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.executeFailure = new SQLException("execute failed", "42000", 2);
        jdbc.statementCloseFailure = new SQLException("statement close also failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select broken(?)",
                List.of(new SqlParameter(Types.INTEGER, 42)),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR,
                "数据库查询失败 (SQLState=42000, vendorCode=2)");
        assertClosed(jdbc, 0);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void resultSetNextFailureClosesBothResourcesAndKeepsPrimaryError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.nextFailure = new SQLException("next failed", "HY000", 3);
        jdbc.resultSetCloseFailure = new SQLException("result close also failed");
        jdbc.statementCloseFailure = new SQLException("statement close also failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR,
                "数据库查询失败 (SQLState=HY000, vendorCode=3)");
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void resultSetGetObjectFailureClosesBothResourcesAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.getObjectFailure = new SQLException("getObject failed", "HY000", 4);
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR,
                "数据库查询失败 (SQLState=HY000, vendorCode=4)");
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void resultSetCloseFailureStillClosesStatementAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.resultSetCloseFailure = new SQLException("result close failed", "HY000", 5);
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(1, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR,
                "数据库查询失败 (SQLState=HY000, vendorCode=5)");
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void statementCloseFailureOccursAfterActivationReleaseAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.statementCloseFailure = new SQLException("statement close failed", "HY000", 6);
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(1, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR,
                "数据库查询失败 (SQLState=HY000, vendorCode=6)");
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void mapsTimeoutAndCancellationWithoutLeakingResources() throws Exception {
        RecordingPreparedJdbc timeoutJdbc = new RecordingPreparedJdbc();
        timeoutJdbc.executeFailure = new SQLTimeoutException("too slow");
        timeoutJdbc.statementCloseFailure = new SQLException("statement close also failed");
        SqlExecutionControl timeoutControl = new SqlExecutionControl();

        QueryResult timeout = JdbcPreparedQueryExecutor.execute(
                timeoutJdbc.connection(), "select slow(?)",
                List.of(new SqlParameter(Types.INTEGER, 42)),
                new SqlExecutionOptions(0, 3, timeoutControl));

        assertEquals(QueryResult.FailureKind.TIMEOUT, timeout.failureKind);
        assertClosed(timeoutJdbc, 0);
        assertFalse(timeoutControl.hasActiveStatement());

        RecordingPreparedJdbc cancelledJdbc = new RecordingPreparedJdbc();
        SqlExecutionControl cancelledControl = new SqlExecutionControl();
        cancelledJdbc.beforeExecute = () -> {
            try {
                assertTrue(cancelledControl.cancel());
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        };
        cancelledJdbc.executeFailure = new SQLException("cancelled by driver");
        cancelledJdbc.statementCloseFailure = new SQLException("statement close also failed");

        QueryResult cancelled = JdbcPreparedQueryExecutor.execute(
                cancelledJdbc.connection(), "select slow(?)",
                List.of(new SqlParameter(Types.VARCHAR, "secret-value")),
                new SqlExecutionOptions(0, 0, cancelledControl));

        assertEquals(QueryResult.FailureKind.CANCELLED, cancelled.failureKind);
        assertEquals(1, cancelledJdbc.cancelCalls.get());
        assertClosed(cancelledJdbc, 0);
        assertFalse(cancelledControl.hasActiveStatement());
    }

    @Test
    void bindingExecutionAndReadDiagnosticsRedactSqlAndParameterValuesButKeepCodes() {
        String sentinel = "sentinel-jdbc-secret-7f3a";
        for (String phase : List.of("bind", "execute", "read")) {
            RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
            SQLException unsafe = new SQLException(
                    phase + " failed for select " + sentinel + " with value " + sentinel,
                    "42000", 942);
            switch (phase) {
                case "bind" -> {
                    jdbc.bindFailureIndex = 1;
                    jdbc.bindFailure = unsafe;
                }
                case "execute" -> jdbc.executeFailure = unsafe;
                case "read" -> jdbc.getObjectFailure = unsafe;
                default -> throw new AssertionError(phase);
            }

            QueryResult result = JdbcPreparedQueryExecutor.execute(
                    jdbc.connection(), "select " + sentinel + " where value = ?",
                    List.of(new SqlParameter(Types.VARCHAR, sentinel)),
                    SqlExecutionOptions.defaults(10));

            assertEquals(QueryResult.FailureKind.SQL_ERROR, result.failureKind, phase);
            assertFalse(result.errorMessage.contains(sentinel), phase);
            assertFalse(result.toString().contains(sentinel), phase);
            assertTrue(result.errorMessage.contains("SQLState=42000"), phase);
            assertTrue(result.errorMessage.contains("vendorCode=942"), phase);
        }
    }

    @Test
    void prepareStatementFailureIsClassifiedSanitizedAndLeavesNoResourcesActive() {
        String sentinel = "sentinel-prepare-secret-92bd";
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.prepareFailure = new SQLException(
                "prepare failed for " + sentinel, "42000", 942);
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select " + sentinel + " where value = ?",
                List.of(new SqlParameter(Types.VARCHAR, sentinel)),
                new SqlExecutionOptions(10, 3, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR,
                "数据库查询失败 (SQLState=42000, vendorCode=942)");
        assertFalse(result.errorMessage.contains(sentinel));
        assertFalse(result.toString().contains(sentinel));
        assertEquals(1, jdbc.prepareCalls.get());
        assertEquals(0, jdbc.statementCloses.get(),
                "no statement exists when Connection.prepareStatement fails");
        assertEquals(0, jdbc.resultSetCloses.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void timeoutAndCancellationDiagnosticsAreTypedAndRedacted() throws Exception {
        String sentinel = "sentinel-control-secret-7f3a";
        RecordingPreparedJdbc timeoutJdbc = new RecordingPreparedJdbc();
        timeoutJdbc.executeFailure = new SQLTimeoutException(
                "timed out for " + sentinel, "57014", 7);
        QueryResult timeout = JdbcPreparedQueryExecutor.execute(
                timeoutJdbc.connection(), "select " + sentinel + "(?)",
                List.of(new SqlParameter(Types.VARCHAR, sentinel)),
                new SqlExecutionOptions(0, 3, new SqlExecutionControl()));
        assertEquals(QueryResult.FailureKind.TIMEOUT, timeout.failureKind);
        assertFalse(timeout.errorMessage.contains(sentinel));
        assertTrue(timeout.errorMessage.contains("SQLState=57014"));
        assertTrue(timeout.errorMessage.contains("vendorCode=7"));

        RecordingPreparedJdbc cancelledJdbc = new RecordingPreparedJdbc();
        SqlExecutionControl cancelledControl = new SqlExecutionControl();
        cancelledJdbc.beforeExecute = () -> {
            try {
                assertTrue(cancelledControl.cancel());
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        };
        cancelledJdbc.executeFailure = new SQLException(
                "cancelled for " + sentinel, "57014", 8);
        QueryResult cancelled = JdbcPreparedQueryExecutor.execute(
                cancelledJdbc.connection(), "select " + sentinel + "(?)",
                List.of(new SqlParameter(Types.VARCHAR, sentinel)),
                new SqlExecutionOptions(0, 0, cancelledControl));
        assertEquals(QueryResult.FailureKind.CANCELLED, cancelled.failureKind);
        assertFalse(cancelled.errorMessage.contains(sentinel));
        assertTrue(cancelled.errorMessage.contains("SQLState=57014"));
        assertTrue(cancelled.errorMessage.contains("vendorCode=8"));
    }

    private static void assertFailure(
            QueryResult result, QueryResult.FailureKind expectedKind, String expectedMessage) {
        assertEquals(QueryResult.Kind.ERROR, result.kind);
        assertEquals(expectedKind, result.failureKind);
        assertEquals(expectedMessage, result.errorMessage);
    }

    private static void assertClosed(RecordingPreparedJdbc jdbc, int expectedResultSetCloses) {
        assertEquals(1, jdbc.statementCloses.get(), "statement close must be attempted");
        assertEquals(expectedResultSetCloses, jdbc.resultSetCloses.get(),
                "result-set close attempts must match resource acquisition");
    }

    private static final class RecordingPreparedJdbc {
        private final List<Integer> boundIndexes = new ArrayList<>();
        private final List<Object> boundValues = new ArrayList<>();
        private final List<Integer> boundTypes = new ArrayList<>();
        private final List<String> boundMethods = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger statementCloses = new AtomicInteger();
        private final AtomicInteger resultSetCloses = new AtomicInteger();
        private int queryTimeout = -1;
        private int maxRows = -1;
        private int availableRows = 3;
        private Runnable beforeExecute = () -> {};
        private int bindFailureIndex = -1;
        private SQLException bindFailure;
        private SQLException executeFailure;
        private SQLException nextFailure;
        private SQLException getObjectFailure;
        private SQLException resultSetCloseFailure;
        private SQLException statementCloseFailure;
        private SQLException prepareFailure;

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) ->
                            {
                                if (method.getName().equals("prepareStatement")) {
                                    prepareCalls.incrementAndGet();
                                    if (prepareFailure != null) throw prepareFailure;
                                    return preparedStatement();
                                }
                                return defaultValue(method.getReturnType());
                            });
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setQueryTimeout" -> {
                            queryTimeout = (Integer) args[0];
                            yield null;
                        }
                        case "setMaxRows" -> {
                            maxRows = (Integer) args[0];
                            events.add("setMaxRows:" + maxRows);
                            yield null;
                        }
                        case "setObject" -> {
                            boundIndexes.add((Integer) args[0]);
                            boundValues.add(args[1]);
                            boundTypes.add((Integer) args[2]);
                            boundMethods.add("setObject");
                            failBindingIfConfigured((Integer) args[0]);
                            yield null;
                        }
                        case "setNull" -> {
                            boundIndexes.add((Integer) args[0]);
                            boundValues.add(null);
                            boundTypes.add((Integer) args[1]);
                            boundMethods.add("setNull");
                            failBindingIfConfigured((Integer) args[0]);
                            yield null;
                        }
                        case "executeQuery" -> {
                            events.add("executeQuery");
                            beforeExecute.run();
                            if (executeFailure != null) throw executeFailure;
                            yield resultSet();
                        }
                        case "cancel" -> {
                            cancelCalls.incrementAndGet();
                            yield null;
                        }
                        case "close" -> {
                            statementCloses.incrementAndGet();
                            if (statementCloseFailure != null) throw statementCloseFailure;
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private void failBindingIfConfigured(int index) throws SQLException {
            if (index == bindFailureIndex && bindFailure != null) throw bindFailure;
        }

        private ResultSet resultSet() {
            AtomicInteger row = new AtomicInteger();
            ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getColumnCount" -> 1;
                        case "getColumnLabel" -> "id";
                        case "getColumnType" -> Types.INTEGER;
                        case "getColumnTypeName" -> "integer";
                        default -> defaultValue(method.getReturnType());
                    });
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> metadata;
                        case "next" -> {
                            if (nextFailure != null) throw nextFailure;
                            yield row.getAndIncrement() < availableRows;
                        }
                        case "getObject" -> {
                            if (getObjectFailure != null) throw getObjectFailure;
                            yield row.get();
                        }
                        case "close" -> {
                            resultSetCloses.incrementAndGet();
                            if (resultSetCloseFailure != null) throw resultSetCloseFailure;
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
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
