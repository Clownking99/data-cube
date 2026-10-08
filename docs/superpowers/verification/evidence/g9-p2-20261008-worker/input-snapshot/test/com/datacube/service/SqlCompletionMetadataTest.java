package com.datacube.service;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlCompletionMetadataTest {
    static ConnConfig config(DbType type) { return new ConnConfig("synthetic", "mock", type, "invalid.example", 1, "mock", "APP", "", Map.of()); }
    @Test void exactQuotedNamesAreBoundValuesAndDedicatedResourcesCloseOnSuccess() throws Exception {
        var jdbc = new Catalog(List.of("Mixed", "normal"));
        var cfg = config(DbType.POSTGRESQL);
        var control = new SqlExecutionControl();
        var result = SqlCompletionMetadata.load(new SqlCompletionMetadata.Target(cfg, "Schema'quoted", "Table.Name"), snapshot -> {
            assertSame(cfg, snapshot); return jdbc.connection();
        }, control);
        assertEquals(List.of("Schema'quoted", "Table.Name"), jdbc.parameters);
        assertFalse(jdbc.sql.contains("Schema'quoted"));
        assertTrue(jdbc.sql.contains("table_schema = ? AND table_name = ?"));
        assertEquals(List.of("\"Mixed\"", "\"normal\""), result.values());
        assertEquals(5, jdbc.timeout); assertEquals(257, jdbc.maxRows);
        assertEquals(List.of("result", "statement", "connection"), jdbc.closed);
        assertFalse(control.hasActiveStatement());
    }
    @Test void schemaListingIsBoundedAndNeverEnumeratesOtherSchemas() throws Exception {
        var jdbc = new Catalog(Collections.nCopies(501, "ORDERS"));
        var result = SqlCompletionMetadata.load(new SqlCompletionMetadata.Target(config(DbType.ORACLE), "APP", null), ignored -> jdbc.connection(), new SqlExecutionControl());
        assertEquals(List.of("APP"), jdbc.parameters);
        assertTrue(jdbc.sql.contains("OWNER = ?")); assertEquals(501, jdbc.maxRows);
        assertEquals(500, result.values().size()); assertTrue(result.notice().contains("前 500"));
        assertEquals(3, jdbc.closed.size());
    }
    @Test void cancellationBeforeOpenAndDuringReadCannotPublishNamesAndClosesEverything() throws Exception {
        var control = new SqlExecutionControl(); control.requestCancellation();
        assertThrows(SQLException.class, () -> SqlCompletionMetadata.load(new SqlCompletionMetadata.Target(config(DbType.POSTGRESQL), "app", "t"), ignored -> { fail("must not open"); return null; }, control));
        var jdbc = new Catalog(List.of("id")); var reading = new SqlExecutionControl();
        jdbc.nextHook = reading::requestCancellation;
        assertThrows(SQLException.class, () -> SqlCompletionMetadata.load(new SqlCompletionMetadata.Target(config(DbType.POSTGRESQL), "app", "t"), ignored -> jdbc.connection(), reading));
        assertEquals(List.of("result", "statement", "connection"), jdbc.closed);
        assertFalse(reading.hasActiveStatement());
    }
    @Test void timeoutFailureAndCancellationDuringConnectCloseOwnedResources() throws Exception {
        var jdbc = new Catalog(List.of()); jdbc.failure = new SQLTimeoutException("synthetic timeout");
        var control = new SqlExecutionControl();
        assertThrows(SQLTimeoutException.class, () -> SqlCompletionMetadata.load(new SqlCompletionMetadata.Target(config(DbType.ORACLE), "APP", "T"), ignored -> jdbc.connection(), control));
        assertEquals(List.of("statement", "connection"), jdbc.closed); assertFalse(control.hasActiveStatement());
        jdbc.closed.clear();
        assertThrows(SQLException.class, () -> SqlCompletionMetadata.load(new SqlCompletionMetadata.Target(config(DbType.ORACLE), "APP", "T"), ignored -> { control.requestCancellation(); return jdbc.connection(); }, control));
        assertEquals(List.of("connection"), jdbc.closed);
    }
    private static final class Catalog {
        final List<String> names; final List<String> parameters = new ArrayList<>(), closed = new ArrayList<>();
        int timeout, maxRows; String sql; Runnable nextHook = () -> {}; SQLException failure;
        Catalog(List<String> names) { this.names = names; }
        Connection connection() {
            AtomicInteger row = new AtomicInteger(-1);
            ResultSet rs = proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
                case "next" -> { nextHook.run(); yield row.incrementAndGet() < names.size(); }
                case "getString" -> names.get(row.get());
                case "close" -> { closed.add("result"); yield null; }
                default -> null;
            });
            PreparedStatement stmt = proxy(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
                case "setQueryTimeout" -> { timeout = (int) a[0]; yield null; }
                case "setMaxRows" -> { maxRows = (int) a[0]; yield null; }
                case "setString" -> { parameters.add((String) a[1]); yield null; }
                case "executeQuery" -> { if (failure != null) throw failure; yield rs; }
                case "close" -> { closed.add("statement"); yield null; }
                default -> null;
            });
            return proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
                case "prepareStatement" -> { sql = (String) a[0]; yield stmt; }
                case "close" -> { closed.add("connection"); yield null; }
                default -> null;
            });
        }
        private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
