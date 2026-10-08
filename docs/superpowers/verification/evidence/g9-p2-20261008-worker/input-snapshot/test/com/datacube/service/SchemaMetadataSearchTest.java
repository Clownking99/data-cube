package com.datacube.service;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.datacube.service.SchemaMetadataSearch.*;

class SchemaMetadataSearchTest {
    private ConnConfig config(DbType type) { return new ConnConfig("mock", "synthetic", type, "invalid.example", 1, "db", "u", "", Map.of()); }
    @Test void allModesBindExactSchemaAndLiteralTermAndReturnExplicitProvenance() throws Exception {
        for (DbType type : List.of(DbType.ORACLE, DbType.POSTGRESQL)) for (Mode mode : Mode.values()) {
            var jdbc = new Catalog(1); var target = config(type);
            var result = search(new Request(target, "Mixed.Schema'", mode, "%_'😀"), supplied -> {
                assertSame(target, supplied); return jdbc.connection();
            }, new SqlExecutionControl());
            assertEquals(List.of("Mixed.Schema'", "%_'😀"), jdbc.parameters);
            assertFalse(jdbc.sql.contains("Mixed.Schema'")); assertFalse(jdbc.sql.contains("%_'😀"));
            assertFalse(jdbc.sql.toUpperCase(Locale.ROOT).contains(" LIKE "), "percent and underscore must be literal");
            assertTrue(jdbc.sql.contains(type == DbType.ORACLE ? "t.OWNER = ?" : "t.table_schema = ?"));
            assertEquals(new TableRef("Mixed.Schema'", "Order\" Name"), result.hits().getFirst().object().ref());
            assertEquals(mode, result.hits().getFirst().mode());
            assertEquals(mode == Mode.OBJECT_COMMENT ? null : "customer_id", result.hits().getFirst().column());
            assertEquals("preview", result.hits().getFirst().excerpt()); assertFalse(result.truncated());
            assertEquals(10, jdbc.timeout); assertEquals(201, jdbc.limit);
            assertEquals(List.of("result", "statement", "connection"), jdbc.closed);
        }
    }
    @Test void exactCapacityAndOneExtraAreDistinguishedAndNeverReadUnboundedMatches() throws Exception {
        for (int count : List.of(0, 200, 201, 10000)) {
            var jdbc = new Catalog(count);
            var result = search(new Request(config(DbType.POSTGRESQL), "s", Mode.COLUMN_NAME, "id"), ignored -> jdbc.connection(), new SqlExecutionControl());
            assertEquals(Math.min(count, 200), result.hits().size()); assertEquals(count > 200, result.truncated());
            assertEquals(Math.min(count + 1, 201), jdbc.next.get());
            assertTrue(result.notice().contains("不代表对象不存在"));
        }
    }
    @Test void cancelBeforeOpenAndLateConnectAndDuringReadReleaseOwnedResources() throws Exception {
        var request = new Request(config(DbType.ORACLE), "s", Mode.OBJECT_COMMENT, "term");
        var cancelled = new SqlExecutionControl(); cancelled.requestCancellation();
        assertThrows(SQLException.class, () -> search(request, ignored -> { fail("no connection after cancellation"); return null; }, cancelled));
        var jdbc = new Catalog(1); var late = new SqlExecutionControl();
        assertThrows(SQLException.class, () -> search(request, ignored -> { late.requestCancellation(); return jdbc.connection(); }, late));
        assertEquals(List.of("connection"), jdbc.closed);
        jdbc.closed.clear(); var reading = new SqlExecutionControl(); jdbc.onNext = reading::requestCancellation;
        assertThrows(SQLException.class, () -> search(request, ignored -> jdbc.connection(), reading));
        assertEquals(List.of("result", "statement", "connection"), jdbc.closed); assertFalse(reading.hasActiveStatement());
    }
    @Test void timeoutAndInvalidRowsFailWithoutPublishingPartialResults() throws Exception {
        var request = new Request(config(DbType.POSTGRESQL), "s", Mode.COLUMN_COMMENT, "term");
        var jdbc = new Catalog(1); jdbc.failure = new SQLTimeoutException("synthetic");
        var control = new SqlExecutionControl();
        assertThrows(SQLTimeoutException.class, () -> search(request, ignored -> jdbc.connection(), control));
        assertEquals(List.of("statement", "connection"), jdbc.closed); assertFalse(control.hasActiveStatement());
        jdbc.failure = null; jdbc.closed.clear(); jdbc.kind = null;
        assertThrows(SQLException.class, () -> search(request, ignored -> jdbc.connection(), new SqlExecutionControl()));
        assertEquals(List.of("result", "statement", "connection"), jdbc.closed);
    }
    @Test void invalidTargetAndQueryAreRejectedWithoutAnOpener() {
        for (String term : List.of("", " ", "x".repeat(257)))
            assertThrows(IllegalArgumentException.class, () -> new Request(config(DbType.ORACLE), "s", Mode.COLUMN_NAME, term));
        assertThrows(IllegalArgumentException.class, () -> new Request(config(DbType.REDIS), "s", Mode.COLUMN_NAME, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Request(config(DbType.POSTGRESQL), "", Mode.COLUMN_NAME, "x"));
        assertEquals("Schema metadata search request", new Request(config(DbType.POSTGRESQL), "private", Mode.COLUMN_NAME, "private").toString());
    }
    private static final class Catalog {
        final int count; final AtomicInteger next = new AtomicInteger(); final List<String> parameters = new ArrayList<>(), closed = new ArrayList<>();
        String sql, kind = "VIEW"; int timeout, limit; Runnable onNext = () -> {}; SQLException failure;
        Catalog(int count) { this.count = count; }
        Connection connection() {
            ResultSet rs = proxy(ResultSet.class, (p, m, a) -> switch(m.getName()) {
                case "next" -> { onNext.run(); yield next.incrementAndGet() <= count; }
                case "getString" -> switch ((int)a[0]) { case 1 -> "Order\" Name"; case 2 -> kind; case 3 -> "customer_id"; default -> "preview"; };
                case "close" -> { closed.add("result"); yield null; } default -> null;
            });
            PreparedStatement statement = proxy(PreparedStatement.class, (p, m, a) -> switch(m.getName()) {
                case "setString" -> { parameters.add((String)a[1]); yield null; }
                case "setMaxRows" -> { limit=(int)a[0]; yield null; }
                case "setQueryTimeout" -> { timeout=(int)a[0]; yield null; }
                case "executeQuery" -> { if (failure != null) throw failure; yield rs; }
                case "close" -> { closed.add("statement"); yield null; } default -> null;
            });
            return proxy(Connection.class, (p, m, a) -> switch(m.getName()) {
                case "prepareStatement" -> { sql=(String)a[0]; yield statement; }
                case "close" -> { closed.add("connection"); yield null; } default -> null;
            });
        }
        private <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    }
}
