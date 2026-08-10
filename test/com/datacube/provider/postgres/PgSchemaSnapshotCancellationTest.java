package com.datacube.provider.postgres;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgSchemaSnapshotCancellationTest {
    private static final List<String> QUERY_ORDER = List.of(
            "tables", "columns", "constraints", "indexes", "sequences", "views",
            "routines", "triggers", "enums", "composites", "domains", "dependencies");

    @Test
    void activatesBindsExecutesAndReleasesEveryPreparedStatementInSerialOrder() throws Exception {
        SqlExecutionControl control = new SqlExecutionControl();
        LifecycleJdbc jdbc = new LifecycleJdbc(control);

        new PgSchemaSnapshotReader(jdbc.connection()).read("connection",
                PgSchemaIdentifierNormalizer.schema("app"),
                new SqlExecutionOptions(0, 13, control));

        assertEquals(QUERY_ORDER, jdbc.executedTags);
        assertEquals(QUERY_ORDER.size(), jdbc.traces.size());
        for (Trace trace : jdbc.traces) {
            assertEquals(List.of("timeout:13", "bind:app", "execute", "result-close", "statement-close"),
                    trace.events, trace.tag);
        }
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void cancellationTargetsTheCurrentlyBlockingMetadataStatementAndReleasesOwnership() throws Exception {
        SqlExecutionControl control = new SqlExecutionControl();
        BlockingJdbc jdbc = new BlockingJdbc();
        AtomicReference<SQLException> failure = new AtomicReference<>();

        Thread worker = Thread.startVirtualThread(() -> {
            try {
                new PgSchemaSnapshotReader(jdbc.connection()).read("connection",
                        PgSchemaIdentifierNormalizer.schema("app"),
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
        assertEquals("views", jdbc.cancelledTag.get());
        assertEquals(1, jdbc.cancelCalls.get());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void separateReadersSerializeTheWholeSnapshotOnTheSameConnection() throws Exception {
        SerialConnectionJdbc jdbc = new SerialConnectionJdbc();
        Connection connection = jdbc.connection();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = Thread.startVirtualThread(() -> read(connection, firstFailure));
        assertTrue(jdbc.firstQueryStarted.await(2, TimeUnit.SECONDS));
        Thread second = Thread.startVirtualThread(() -> read(connection, secondFailure));
        boolean secondPreparedBeforeFirstFinished;
        try {
            secondPreparedBeforeFirstFinished = jdbc.secondPrepare.await(250, TimeUnit.MILLISECONDS);
        } finally {
            jdbc.allowFirstToFinish.countDown();
        }
        first.join(2_000);
        second.join(2_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertFalse(secondPreparedBeforeFirstFinished,
                "the second reader must not prepare a statement while the first owns the connection");
        assertEquals(null, firstFailure.get());
        assertEquals(null, secondFailure.get());
    }

    @Test
    void cancellationDuringTheFinalResultCloseIsStillTerminal() throws Exception {
        SqlExecutionControl control = new SqlExecutionControl();
        Connection connection = PgSchemaSnapshotCancellationTest.connection(sql -> {
            Trace trace = new Trace(tag(sql));
            return statement(trace, () -> "dependencies".equals(trace.tag)
                    ? emptyResultSet(trace.events, () -> control.cancel())
                    : emptyResultSet(trace.events), () -> { });
        });

        SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> new PgSchemaSnapshotReader(connection).read("connection",
                        PgSchemaIdentifierNormalizer.schema("app"),
                        new SqlExecutionOptions(0, 5, control)));

        assertEquals("57014", failure.getSQLState());
        assertFalse(control.hasActiveStatement());
    }

    @Test
    void cancellationDuringBindingIsCheckedBeforeJdbcExecuteAndReleasesOwnership() {
        SqlExecutionControl control = new SqlExecutionControl();
        AtomicBoolean executed = new AtomicBoolean();
        AtomicInteger cancelCalls = new AtomicInteger();
        Connection connection = PgSchemaSnapshotCancellationTest.connection(sql ->
                (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "setQueryTimeout" -> null;
                            case "setString" -> {
                                control.cancel();
                                yield null;
                            }
                            case "cancel" -> {
                                cancelCalls.incrementAndGet();
                                yield null;
                            }
                            case "executeQuery" -> {
                                executed.set(true);
                                yield emptyResultSet(new ArrayList<>());
                            }
                            case "isClosed" -> false;
                            default -> defaultValue(method.getReturnType());
                        }));

        SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> new PgSchemaSnapshotReader(connection).read("connection",
                        PgSchemaIdentifierNormalizer.schema("app"),
                        new SqlExecutionOptions(0, 5, control)));

        assertEquals("57014", failure.getSQLState());
        assertFalse(executed.get());
        assertEquals(1, cancelCalls.get());
        assertFalse(control.hasActiveStatement());
    }

    private static void read(Connection connection, AtomicReference<Throwable> failure) {
        try {
            new PgSchemaSnapshotReader(connection).read("connection",
                    PgSchemaIdentifierNormalizer.schema("app"),
                    new SqlExecutionOptions(0, 3, new SqlExecutionControl()));
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static final class LifecycleJdbc {
        private final SqlExecutionControl control;
        private final List<Trace> traces = new ArrayList<>();
        private final List<String> executedTags = new ArrayList<>();

        private LifecycleJdbc(SqlExecutionControl control) {
            this.control = control;
        }

        private Connection connection() {
            return PgSchemaSnapshotCancellationTest.connection(sql -> {
                assertFalse(control.hasActiveStatement(), "prior statement owner must be released before prepare");
                Trace trace = new Trace(tag(sql));
                traces.add(trace);
                return statement(trace, () -> {
                    executedTags.add(trace.tag);
                    return emptyResultSet(trace.events);
                }, () -> { });
            });
        }
    }

    private static final class BlockingJdbc {
        private final CountDownLatch blockingQueryStarted = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicReference<String> cancelledTag = new AtomicReference<>();

        private Connection connection() {
            return PgSchemaSnapshotCancellationTest.connection(sql -> {
                Trace trace = new Trace(tag(sql));
                if (!"views".equals(trace.tag)) {
                    return statement(trace, () -> emptyResultSet(trace.events), () -> { });
                }
                return statement(trace, () -> {
                    blockingQueryStarted.countDown();
                    if (!cancelled.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("blocking metadata did not receive cancellation");
                    }
                    throw new SQLException("driver cancellation detail", "57014");
                }, () -> {
                    cancelledTag.set(trace.tag);
                    cancelCalls.incrementAndGet();
                    cancelled.countDown();
                });
            });
        }
    }

    private static final class SerialConnectionJdbc {
        private final CountDownLatch firstQueryStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstToFinish = new CountDownLatch(1);
        private final CountDownLatch secondPrepare = new CountDownLatch(1);
        private final AtomicInteger prepares = new AtomicInteger();

        private Connection connection() {
            return PgSchemaSnapshotCancellationTest.connection(sql -> {
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
                    return emptyResultSet(trace.events);
                }, () -> { });
            });
        }
    }

    private static Connection connection(StatementFactory factory) {
        return (Connection) Proxy.newProxyInstance(PgSchemaSnapshotCancellationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> factory.create((String) args[0]);
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement statement(Trace trace, Query query, Cancel cancel) {
        Map<Integer, Object> bindings = new LinkedHashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(PgSchemaSnapshotCancellationTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setQueryTimeout" -> {
                        trace.events.add("timeout:" + args[0]);
                        yield null;
                    }
                    case "setString" -> {
                        bindings.put((Integer) args[0], args[1]);
                        trace.events.add("bind:" + args[1]);
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

    private static ResultSet emptyResultSet(List<String> events) {
        return emptyResultSet(events, () -> { });
    }

    private static ResultSet emptyResultSet(List<String> events, Close close) {
        return (ResultSet) Proxy.newProxyInstance(PgSchemaSnapshotCancellationTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> {
                        events.add("result-close");
                        close.close();
                        yield null;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
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
