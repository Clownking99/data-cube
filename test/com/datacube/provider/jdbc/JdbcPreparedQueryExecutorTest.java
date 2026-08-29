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
        assertEquals(7, jdbc.queryTimeout);
        assertEquals(QueryResult.Kind.QUERY, result.kind);
        assertEquals(2, result.rows.size());
        assertTrue(result.truncated);
        assertEquals(1, jdbc.statementCloses.get());
        assertEquals(1, jdbc.resultSetCloses.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void mapsTimeoutAndCancellationWithoutLeakingResources() throws Exception {
        RecordingPreparedJdbc timeoutJdbc = new RecordingPreparedJdbc();
        timeoutJdbc.executeFailure = new SQLTimeoutException("too slow");
        SqlExecutionControl timeoutControl = new SqlExecutionControl();

        QueryResult timeout = JdbcPreparedQueryExecutor.execute(
                timeoutJdbc.connection(), "select slow(?)",
                List.of(new SqlParameter(Types.INTEGER, 42)),
                new SqlExecutionOptions(0, 3, timeoutControl));

        assertEquals(QueryResult.FailureKind.TIMEOUT, timeout.failureKind);
        assertEquals(1, timeoutJdbc.statementCloses.get());
        assertEquals(0, timeoutJdbc.resultSetCloses.get());
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

        QueryResult cancelled = JdbcPreparedQueryExecutor.execute(
                cancelledJdbc.connection(), "select slow(?)",
                List.of(new SqlParameter(Types.VARCHAR, "secret-value")),
                new SqlExecutionOptions(0, 0, cancelledControl));

        assertEquals(QueryResult.FailureKind.CANCELLED, cancelled.failureKind);
        assertEquals(1, cancelledJdbc.cancelCalls.get());
        assertEquals(1, cancelledJdbc.statementCloses.get());
        assertFalse(cancelledControl.hasActiveStatement());
    }

    private static final class RecordingPreparedJdbc {
        private final List<Integer> boundIndexes = new ArrayList<>();
        private final List<Object> boundValues = new ArrayList<>();
        private final List<Integer> boundTypes = new ArrayList<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger statementCloses = new AtomicInteger();
        private final AtomicInteger resultSetCloses = new AtomicInteger();
        private int queryTimeout = -1;
        private Runnable beforeExecute = () -> {};
        private SQLException executeFailure;

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
                            yield null;
                        }
                        case "setNull" -> {
                            boundIndexes.add((Integer) args[0]);
                            boundValues.add(null);
                            boundTypes.add((Integer) args[1]);
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
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
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
                        case "next" -> row.getAndIncrement() < 3;
                        case "getObject" -> row.get();
                        case "close" -> {
                            resultSetCloses.incrementAndGet();
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
