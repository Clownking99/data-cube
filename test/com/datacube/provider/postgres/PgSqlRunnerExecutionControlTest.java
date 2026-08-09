package com.datacube.provider.postgres;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.model.QueryResult;
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
