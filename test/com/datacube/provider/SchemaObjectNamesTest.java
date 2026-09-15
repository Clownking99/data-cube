package com.datacube.provider;

import com.datacube.provider.oracle.OracleMetadataReader;
import com.datacube.provider.postgres.PgMetadataReader;
import com.datacube.spi.MetadataReader;
import com.datacube.spi.model.TableInfo;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectNamesTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void namesUseBoundSchemaTimeoutAndRowCapWithoutDefinitions(boolean oracle) throws Exception {
        var f = new Jdbc(oracle, List.of(new String[]{"exact\" name", oracle ? "TABLE" : "BASE TABLE"},
                new String[]{"same.name", "VIEW"}, new String[]{"must not read", "VIEW"}));
        String schema = "Exact' schema; --";
        var result = f.reader.tableAndViewNames(schema, 2);
        assertEquals(List.of(new TableInfo(schema, "exact\" name", TableInfo.Kind.TABLE, null),
                new TableInfo(schema, "same.name", TableInfo.Kind.VIEW, null)), result);
        assertEquals(schema, f.boundSchema); assertFalse(f.sql.contains(schema));
        String sql = f.sql.toUpperCase();
        assertTrue(sql.contains(oracle ? "OWNER = ?" : "TABLE_SCHEMA = ?"));
        assertTrue(sql.contains("ORDER BY")); assertFalse(sql.contains("DEFINITION")); assertFalse(sql.contains("TEXT"));
        assertEquals(2, f.maxRows); assertEquals(15, f.timeout); assertEquals(2, f.nextCalls);
        assertEquals(List.of("result", "statement"), f.closed);
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownTypesAndInterruptedReadsNeverReturnPartialResults(boolean oracle) {
        var f = new Jdbc(oracle, List.of(new String[]{"first", "VIEW"}, new String[]{"bad", "SYNONYM"}));
        assertThrows(SQLException.class, () -> f.reader.tableAndViewNames("s", 5));
        assertEquals(List.of("result", "statement"), f.closed);
        var cancelled = new Jdbc(oracle, java.util.Collections.singletonList(new String[]{"name", "VIEW"}));
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.util.concurrent.CancellationException.class, () -> cancelled.reader.tableAndViewNames("s", 5));
            assertEquals(List.of("result", "statement"), cancelled.closed);
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidScopeAndLimitsDoNotPrepareSql(boolean oracle) {
        var f = new Jdbc(oracle, List.of());
        assertThrows(IllegalArgumentException.class, () -> f.reader.tableAndViewNames(null, 2));
        assertThrows(IllegalArgumentException.class, () -> f.reader.tableAndViewNames("", 2));
        assertThrows(IllegalArgumentException.class, () -> f.reader.tableAndViewNames("s", 0));
        assertThrows(IllegalArgumentException.class, () -> f.reader.tableAndViewNames("s", -1));
        assertNull(f.sql);
    }

    @Test void legacyProviderDefaultDoesNotFallBackToUnboundedDefinitionReads() {
        MetadataReader legacy = (MetadataReader) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MetadataReader.class},
                (proxy, method, args) -> {
                    assertTrue(method.isDefault(), "no fallback metadata reads");
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                });
        assertThrows(SQLFeatureNotSupportedException.class, () -> legacy.tableAndViewNames("s", 10));
    }

    private static final class Jdbc {
        String sql, boundSchema;
        int maxRows, timeout, nextCalls, row = -1;
        final List<String> closed = new ArrayList<>();
        final MetadataReader reader;
        Jdbc(boolean oracle, List<String[]> rows) {
            ResultSet result = proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
                case "next" -> { nextCalls++; yield ++row < rows.size(); }
                case "getString" -> { int column = (int) args[0]; assertTrue(column == 1 || column == 2); yield rows.get(row)[column - 1]; }
                case "close" -> { closed.add("result"); yield null; }
                default -> throw new AssertionError(method.getName());
            });
            PreparedStatement statement = proxy(PreparedStatement.class, (p, method, args) -> switch (method.getName()) {
                case "setString" -> { assertEquals(1, args[0]); boundSchema = (String) args[1]; yield null; }
                case "setMaxRows" -> { maxRows = (int) args[0]; yield null; }
                case "setQueryTimeout" -> { timeout = (int) args[0]; yield null; }
                case "executeQuery" -> result;
                case "close" -> { closed.add("statement"); yield null; }
                default -> throw new AssertionError(method.getName());
            });
            Connection conn = proxy(Connection.class, (p, method, args) -> {
                assertEquals("prepareStatement", method.getName()); sql = (String) args[0]; return statement;
            });
            reader = oracle ? new OracleMetadataReader(conn) : new PgMetadataReader(conn);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
