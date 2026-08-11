package com.datacube.provider.oracle;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaSnapshotCancellationTest {
    private static final List<String> QUERY_ORDER = List.of(
            "tables", "columns", "constraints", "indexes", "sequences",
            "definitions", "arguments", "ddl", "dependencies");

    @Test
    void activatesBindsDrainsAndReleasesCatalogAndDdlStatementsInSerialOrder() throws Exception {
        SqlExecutionControl control = new SqlExecutionControl();
        List<Trace> traces = new ArrayList<>();
        Connection connection = connection(sql -> {
            assertFalse(control.hasActiveStatement(), "prior owner must be released before prepare");
            Trace trace = new Trace(tag(sql));
            traces.add(trace);
            return statement(trace, () -> switch (trace.tag) {
                case "definitions" -> resultSet(trace, List.of(row(
                        "object_name", "ORDERS_V", "object_type", "VIEW",
                        "object_id", 10L, "subprogram_id", 0, "base_object_name", null)));
                case "ddl" -> resultSet(trace, List.of(row("ddl", "CREATE VIEW ORDERS_V AS SELECT 1")));
                default -> resultSet(trace, List.of());
            }, () -> { });
        });

        new OracleSchemaSnapshotReader(connection).read("connection",
                OracleSchemaIdentifierNormalizer.schema("Sales"),
                new SqlExecutionOptions(0, 13, control));

        assertEquals(QUERY_ORDER, traces.stream().map(trace -> trace.tag).toList());
        for (Trace trace : traces) {
            List<String> expected = trace.tag.equals("ddl")
                    ? List.of("timeout:13", "bind:1:VIEW", "bind:2:ORDERS_V", "bind:3:Sales",
                    "execute", "result-close", "statement-close")
                    : List.of("timeout:13", "bind:1:Sales", "execute",
                    "result-close", "statement-close");
            assertEquals(expected, trace.events, trace.tag);
        }
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void cancellationTargetsCurrentlyBlockingCatalogStatementAndReleasesOwnership() throws Exception {
        assertBlockingCancellation("definitions", false);
    }

    @Test
    void cancellationTargetsCurrentlyBlockingGetDdlStatementAndReleasesOwnership() throws Exception {
        assertBlockingCancellation("ddl", true);
    }

    @Test
    void separateReadersSerializeWholeSnapshotOnSameConnection() throws Exception {
        SerialConnectionJdbc jdbc = new SerialConnectionJdbc();
        Connection connection = jdbc.connection();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = Thread.startVirtualThread(() -> read(connection, firstFailure));
        assertTrue(jdbc.firstQueryStarted.await(2, TimeUnit.SECONDS));
        Thread second = Thread.startVirtualThread(() -> read(connection, secondFailure));
        boolean preparedConcurrently;
        try {
            preparedConcurrently = jdbc.secondPrepare.await(250, TimeUnit.MILLISECONDS);
        } finally {
            jdbc.allowFirstToFinish.countDown();
        }
        first.join(2_000);
        second.join(2_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertFalse(preparedConcurrently);
        assertEquals(null, firstFailure.get());
        assertEquals(null, secondFailure.get());
    }

    @Test
    void cancellationDuringFinalResultCloseIsStillTerminal() throws Exception {
        SqlExecutionControl control = new SqlExecutionControl();
        Connection connection = connection(sql -> {
            Trace trace = new Trace(tag(sql));
            return statement(trace, () -> resultSet(trace, List.of(),
                    trace.tag.equals("dependencies") ? control::cancel : () -> { }), () -> { });
        });

        SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> new OracleSchemaSnapshotReader(connection).read("connection",
                        OracleSchemaIdentifierNormalizer.schema("Sales"),
                        new SqlExecutionOptions(0, 5, control)));

        assertEquals("57014", failure.getSQLState());
        assertEquals("Snapshot metadata cancelled", failure.getMessage());
        assertFalse(control.hasActiveStatement());
    }

    private static void assertBlockingCancellation(String blockingTag, boolean withDefinition)
            throws Exception {
        SqlExecutionControl control = new SqlExecutionControl();
        BlockingJdbc jdbc = new BlockingJdbc(blockingTag, withDefinition);
        AtomicReference<SQLException> failure = new AtomicReference<>();
        Thread worker = Thread.startVirtualThread(() -> {
            try {
                new OracleSchemaSnapshotReader(jdbc.connection()).read("connection",
                        OracleSchemaIdentifierNormalizer.schema("Sales"),
                        new SqlExecutionOptions(0, 17, control));
            } catch (SQLException exception) {
                failure.set(exception);
            }
        });
        assertTrue(jdbc.blockingQueryStarted.await(2, TimeUnit.SECONDS));
        assertTrue(control.hasActiveStatement());
        assertTrue(control.cancel());
        worker.join(2_000);

        assertFalse(worker.isAlive());
        assertNotNull(failure.get());
        assertEquals("57014", failure.get().getSQLState());
        assertEquals("Snapshot metadata cancelled", failure.get().getMessage());
        assertEquals(blockingTag, jdbc.cancelledTag.get());
        assertEquals(1, jdbc.cancelCalls.get());
        assertFalse(control.hasActiveStatement());
    }

    private static void read(Connection connection, AtomicReference<Throwable> failure) {
        try {
            new OracleSchemaSnapshotReader(connection).read("connection",
                    OracleSchemaIdentifierNormalizer.schema("Sales"),
                    new SqlExecutionOptions(0, 3, new SqlExecutionControl()));
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static final class BlockingJdbc {
        private final String blockingTag;
        private final boolean withDefinition;
        private final CountDownLatch blockingQueryStarted = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicReference<String> cancelledTag = new AtomicReference<>();

        private BlockingJdbc(String blockingTag, boolean withDefinition) {
            this.blockingTag = blockingTag;
            this.withDefinition = withDefinition;
        }

        private Connection connection() {
            return OracleSchemaSnapshotCancellationTest.connection(sql -> {
                Trace trace = new Trace(tag(sql));
                if (trace.tag.equals(blockingTag)) {
                    return statement(trace, () -> {
                        blockingQueryStarted.countDown();
                        if (!cancelled.await(2, TimeUnit.SECONDS)) {
                            throw new SQLException("test cancellation was not delivered");
                        }
                        throw new SQLException("driver cancellation detail", "57014");
                    }, () -> {
                        cancelledTag.set(trace.tag);
                        cancelCalls.incrementAndGet();
                        cancelled.countDown();
                    });
                }
                return statement(trace, () -> {
                    if (withDefinition && trace.tag.equals("definitions")) {
                        return resultSet(trace, List.of(row(
                                "object_name", "ORDERS_V", "object_type", "VIEW",
                                "object_id", 10L, "subprogram_id", 0,
                                "base_object_name", null)));
                    }
                    return resultSet(trace, List.of());
                }, () -> { });
            });
        }
    }

    private static final class SerialConnectionJdbc {
        private final CountDownLatch firstQueryStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        private final CountDownLatch secondPrepare = new CountDownLatch(1);
        private final AtomicInteger prepares = new AtomicInteger();

        private Connection connection() {
            return OracleSchemaSnapshotCancellationTest.connection(sql -> {
                int number = prepares.incrementAndGet();
                if (number == 2) secondPrepare.countDown();
                Trace trace = new Trace(tag(sql));
                return statement(trace, () -> {
                    if (number == 1) {
                        firstQueryStarted.countDown();
                        if (!allowFirstToFinish.await(2, TimeUnit.SECONDS)) {
                            throw new SQLException("test release timed out");
                        }
                    }
                    return resultSet(trace, List.of());
                }, () -> { });
            });
        }
    }

    private static Connection connection(StatementFactory factory) {
        return (Connection) Proxy.newProxyInstance(
                OracleSchemaSnapshotCancellationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> factory.create((String) args[0]);
                    case "getAutoCommit" -> true;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement statement(Trace trace, Query query, Cancel cancel) {
        return (PreparedStatement) Proxy.newProxyInstance(
                OracleSchemaSnapshotCancellationTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setQueryTimeout" -> {
                        trace.events.add("timeout:" + args[0]);
                        yield null;
                    }
                    case "setString" -> {
                        trace.events.add("bind:" + args[0] + ":" + args[1]);
                        yield null;
                    }
                    case "executeQuery" -> {
                        trace.events.add("execute");
                        yield query.execute();
                    }
                    case "cancel" -> {
                        cancel.cancel();
                        yield null;
                    }
                    case "close" -> {
                        trace.events.add("statement-close");
                        yield null;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ResultSet resultSet(Trace trace, List<Map<String, Object>> rows) {
        return resultSet(trace, rows, () -> { });
    }

    private static ResultSet resultSet(
            Trace trace, List<Map<String, Object>> rows, Close close) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                OracleSchemaSnapshotCancellationTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++cursor[0] < rows.size();
                    case "getString" -> value(rows, cursor[0], args[0]);
                    case "getLong" -> ((Number) value(rows, cursor[0], args[0])).longValue();
                    case "getInt" -> ((Number) value(rows, cursor[0], args[0])).intValue();
                    case "close" -> {
                        trace.events.add("result-close");
                        close.close();
                        yield null;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object value(List<Map<String, Object>> rows, int cursor, Object label) {
        Object value = rows.get(cursor).get(label);
        return value == null ? null : value;
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put((String) values[index], values[index + 1]);
        }
        return row;
    }

    private static String tag(String sql) {
        int start = sql.indexOf("snapshot:") + "snapshot:".length();
        return sql.substring(start, sql.indexOf("*/", start)).trim();
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static final class Trace {
        private final String tag;
        private final List<String> events = new ArrayList<>();

        private Trace(String tag) {
            this.tag = tag;
        }
    }

    @FunctionalInterface
    private interface StatementFactory {
        PreparedStatement create(String sql) throws Exception;
    }

    @FunctionalInterface
    private interface Query {
        ResultSet execute() throws Exception;
    }

    @FunctionalInterface
    private interface Cancel {
        void cancel() throws Exception;
    }

    @FunctionalInterface
    private interface Close {
        void close() throws Exception;
    }
}
