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
        assertEquals(QueryResult.Kind.QUERY, result.kind);
        assertEquals(2, result.rows.size());
        assertTrue(result.truncated);
        assertEquals(1, jdbc.statementCloses.get());
        assertEquals(1, jdbc.resultSetCloses.get());
        assertFalse(control.hasActiveStatement());
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
        jdbc.bindFailure = new SQLException("bind failed");
        jdbc.statementCloseFailure = new SQLException("statement close also failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q where id = ? and name = ?",
                List.of(new SqlParameter(Types.INTEGER, 42),
                        new SqlParameter(Types.VARCHAR, "Ada")),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR, "bind failed");
        assertEquals(1, jdbc.statementCloses.get());
        assertEquals(0, jdbc.resultSetCloses.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void ordinaryExecuteFailureClosesStatementAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.executeFailure = new SQLException("execute failed");
        jdbc.statementCloseFailure = new SQLException("statement close also failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select broken(?)",
                List.of(new SqlParameter(Types.INTEGER, 42)),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR, "execute failed");
        assertClosed(jdbc, 0);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void resultSetNextFailureClosesBothResourcesAndKeepsPrimaryError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.nextFailure = new SQLException("next failed");
        jdbc.resultSetCloseFailure = new SQLException("result close also failed");
        jdbc.statementCloseFailure = new SQLException("statement close also failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR, "next failed");
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void resultSetGetObjectFailureClosesBothResourcesAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.getObjectFailure = new SQLException("getObject failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(0, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR, "getObject failed");
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void resultSetCloseFailureStillClosesStatementAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.resultSetCloseFailure = new SQLException("result close failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(1, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR, "result close failed");
        assertClosed(jdbc, 1);
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void statementCloseFailureOccursAfterActivationReleaseAndIsSqlError() {
        RecordingPreparedJdbc jdbc = new RecordingPreparedJdbc();
        jdbc.statementCloseFailure = new SQLException("statement close failed");
        SqlExecutionControl control = new SqlExecutionControl();

        QueryResult result = JdbcPreparedQueryExecutor.execute(
                jdbc.connection(), "select * from q", List.of(),
                new SqlExecutionOptions(1, 0, control));

        assertFailure(result, QueryResult.FailureKind.SQL_ERROR, "statement close failed");
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
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger statementCloses = new AtomicInteger();
        private final AtomicInteger resultSetCloses = new AtomicInteger();
        private int queryTimeout = -1;
        private Runnable beforeExecute = () -> {};
        private int bindFailureIndex = -1;
        private SQLException bindFailure;
        private SQLException executeFailure;
        private SQLException nextFailure;
        private SQLException getObjectFailure;
        private SQLException resultSetCloseFailure;
        private SQLException statementCloseFailure;

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) ->
                            method.getName().equals("prepareStatement")
                                    ? preparedStatement()
                                    : defaultValue(method.getReturnType()));
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setQueryTimeout" -> {
                            queryTimeout = (Integer) args[0];
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
                            yield row.getAndIncrement() < 3;
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
