package com.datacube.service;

import com.datacube.config.DraftTestCipher;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectCatalogTest {
    @Test void catalogLoadLeavesExistingSharedConnectionAlive() throws Exception {
        var f = new Fixture(); ConnConfig target = config("shared", DbType.POSTGRESQL);
        f.manager.register(target); Connection shared = f.manager.acquire(target.id());
        new SchemaObjectCatalog(f.manager).load(target, "s");
        assertEquals(2, f.opened.size()); assertEquals(1, f.closed.size());
        assertSame(f.opened.getLast(), f.closed.getFirst()); assertNotSame(shared, f.closed.getFirst());
        assertTrue(f.manager.isConnected(target.id())); assertSame(shared, f.manager.acquire(target.id()));
        f.manager.closeAll(); assertEquals(2, f.closed.size());
    }

    @Test void immutableTargetUsesAndClosesDedicatedConnectionWithoutTouchingSharedConnection() throws Exception {
        var f = new Fixture();
        ConnConfig original = config("original", DbType.POSTGRESQL);
        f.manager.register(original);
        Connection shared = f.manager.acquire(original.id());
        f.manager.register(config("replacement", DbType.ORACLE));
        int closedByRegister = f.closed.size();
        var names = new SchemaObjectCatalog(f.manager).load(original, "Exact Schema");
        assertEquals(List.of(new TableInfo("Exact Schema", "name", TableInfo.Kind.VIEW, null)), names);
        assertEquals(List.of(DbType.POSTGRESQL, DbType.POSTGRESQL), f.resolved);
        assertEquals("original", f.openConfigs.getLast().host());
        assertEquals(List.of("Exact Schema", 10001), f.metadataArgs);
        assertEquals(closedByRegister + 1, f.closed.size());
        assertNotSame(shared, f.metadataConnection);
        assertSame(f.opened.getLast(), f.metadataConnection);
        assertSame(f.opened.getLast(), f.closed.getLast());
        assertFalse(f.manager.isConnected(original.id()));
    }

    @ParameterizedTest @ValueSource(strings = {"failure", "cancel-after-open", "cancel-before-open"})
    void failureAndCancellationCloseOnlyAcquiredResources(String state) {
        var f = new Fixture();
        f.fail = state.equals("failure"); f.interruptOnOpen = state.equals("cancel-after-open");
        try {
            if (state.equals("cancel-before-open")) Thread.currentThread().interrupt();
            if (f.fail) assertThrows(SQLException.class, () -> new SchemaObjectCatalog(f.manager).load(config("x", DbType.POSTGRESQL), "s"));
            else assertThrows(CancellationException.class, () -> new SchemaObjectCatalog(f.manager).load(config("x", DbType.POSTGRESQL), "s"));
        } finally { Thread.interrupted(); }
        assertEquals(state.equals("cancel-before-open") ? 0 : 1, f.opened.size());
        assertEquals(f.opened.size(), f.closed.size());
        if (!state.equals("failure")) assertNull(f.metadataConnection);
    }

    @Test void exactCapacityEmptyAndDeduplicationKeepOnlyObjectIdentity() throws Exception {
        assertEquals(List.of(), SchemaObjectCatalog.validate("s", List.of()));
        var full = java.util.stream.IntStream.range(0, 10000)
                .mapToObj(i -> new TableInfo("s", "t" + i, TableInfo.Kind.TABLE, "not retained")).toList();
        var result = SchemaObjectCatalog.validate("s", full);
        assertEquals(10000, result.size()); assertNull(result.getLast().comment());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        var over = new ArrayList<>(full); over.add(new TableInfo("s", "extra", TableInfo.Kind.VIEW, null));
        assertThrows(SchemaObjectCatalog.TooManyObjectsException.class, () -> SchemaObjectCatalog.validate("s", over));
        assertEquals(1, SchemaObjectCatalog.validate("s", List.of(full.getFirst(), full.getFirst())).size());
    }

    @ParameterizedTest @ValueSource(strings = {"null", "wrong-schema", "empty-name", "long-name", "null-kind"})
    void malformedSnapshotsFailAsAWhole(String state) {
        TableInfo name = switch (state) {
            case "null" -> null;
            case "wrong-schema" -> new TableInfo("other", "t", TableInfo.Kind.TABLE, null);
            case "empty-name" -> new TableInfo("s", "", TableInfo.Kind.TABLE, null);
            case "long-name" -> new TableInfo("s", "x".repeat(1025), TableInfo.Kind.TABLE, null);
            default -> new TableInfo("s", "t", null, null);
        };
        assertThrows(SQLException.class, () -> SchemaObjectCatalog.validate("s", java.util.Arrays.asList(
                new TableInfo("s", "valid", TableInfo.Kind.TABLE, null), name)));
    }

    @Test void invalidTargetsNeverResolveOrOpenAProvider() {
        var f = new Fixture(); var service = new SchemaObjectCatalog(f.manager);
        assertThrows(IllegalArgumentException.class, () -> service.load(config("x", DbType.REDIS), "s"));
        assertThrows(IllegalArgumentException.class, () -> service.load(config("x", DbType.POSTGRESQL), ""));
        assertThrows(IllegalArgumentException.class, () -> service.load(config("x", DbType.POSTGRESQL), "x".repeat(1025)));
        assertTrue(f.resolved.isEmpty()); assertTrue(f.opened.isEmpty());
    }

    private static ConnConfig config(String host, DbType type) {
        return new ConnConfig("id", "synthetic", type, host, 1, "db", "", "", Map.of());
    }
    private static final class Fixture {
        final List<DbType> resolved = new ArrayList<>();
        final List<ConnConfig> openConfigs = new ArrayList<>();
        final List<Connection> opened = new ArrayList<>(), closed = new ArrayList<>();
        List<Object> metadataArgs;
        Connection metadataConnection;
        boolean fail, interruptOnOpen;
        final ConnectionManager manager;
        Fixture() {
            ConnectionFactory factory = new ConnectionFactory() {
                public void ensureDriverLoaded() { throw new AssertionError(); }
                public String test(ConnConfig c) { throw new AssertionError(); }
                public Connection open(ConnConfig config) {
                    openConfigs.add(config);
                    Connection conn = proxy(Connection.class, (p, method, args) -> switch (method.getName()) {
                        case "close" -> { closed.add((Connection) p); yield null; }
                        case "isValid" -> true;
                        case "isClosed" -> closed.stream().anyMatch(c -> c == p);
                        default -> throw new AssertionError(method.getName());
                    });
                    opened.add(conn); if (interruptOnOpen) Thread.currentThread().interrupt(); return conn;
                }
            };
            DatabaseProvider provider = proxy(DatabaseProvider.class, (p, method, args) -> switch (method.getName()) {
                case "connectionFactory" -> factory;
                case "metadataReader" -> {
                    metadataConnection = (Connection) args[0];
                    yield proxy(MetadataReader.class, (m, operation, parameters) -> {
                        assertEquals("tableAndViewNames", operation.getName()); metadataArgs = List.of(parameters);
                        if (fail) throw new SQLException("synthetic diagnostic");
                        return List.of(new TableInfo((String) parameters[0], "name", TableInfo.Kind.VIEW, "discard"));
                    });
                }
                default -> throw new AssertionError(method.getName());
            });
            manager = new ConnectionManager(DraftTestCipher.create(), type -> { resolved.add(type); return provider; });
        }
    }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
