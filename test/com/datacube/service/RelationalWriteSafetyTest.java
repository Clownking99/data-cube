package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.datacube.spi.*;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RelationalWriteSafetyTest {
    static final TableRef TABLE = new TableRef("synthetic", "items");
    static final RowKey KEY = new RowKey(List.of("id"), List.of(1));
    @ParameterizedTest
    @EnumSource(value = DbType.class, names = {"ORACLE", "POSTGRESQL"})
    void readOnlyDataWritesRejectBeforeProviderOrConnectionAcquisition(DbType type) {
        AtomicInteger resolutions = new AtomicInteger();
        ConnectionManager connections = new ConnectionManager(new CredentialCipher(), ignored -> {
            resolutions.incrementAndGet();
            throw new AssertionError("read-only write acquired provider");
        });
        connections.register(new ConnConfig("synthetic", "synthetic", type, "invalid", 1,
                "synthetic", "synthetic", "", Map.of("readOnly", "true")));
        DataEditService service = new DataEditService(connections);
        TableRef table = new TableRef("synthetic", "items");
        LinkedHashMap<String, String> values = new LinkedHashMap<>(Map.of("id", "1"));
        RowKey key = new RowKey(List.of("id"), List.of(1));
        for (org.junit.jupiter.api.function.Executable write : List.<org.junit.jupiter.api.function.Executable>of(
                () -> service.insert("synthetic", table, values),
                () -> service.update("synthetic", table, values, key),
                () -> service.delete("synthetic", table, key))) {
            Exception rejected = assertThrows(Exception.class, write);
            assertTrue(rejected.getMessage().contains("只读"));
        }
        assertEquals(0, resolutions.get());
    }

    @ParameterizedTest
    @EnumSource(value = DbType.class, names = {"ORACLE", "POSTGRESQL"})
    void everyServiceWriteRejectsReadOnlyAndMissingProductionConfirmationBeforeOpen(DbType type) throws Exception {
        for (boolean readOnly : List.of(true, false)) {
            Fixture f = new Fixture(type, readOnly, "PRODUCTION");
            var edits = new DataEditService(f.manager);
            var tables = new TableDesignService(f.manager);
            var ddl = new DdlService(f.manager);
            for (org.junit.jupiter.api.function.Executable call : List.<org.junit.jupiter.api.function.Executable>of(
                    () -> edits.insert("target", TABLE, values()),
                    () -> edits.update("target", TABLE, values(), KEY),
                    () -> edits.delete("target", TABLE, KEY),
                    () -> tables.execute("target", "CREATE TABLE items(id int)", null),
                    () -> ddl.executeDdl("target", "CREATE VIEW v AS SELECT 1"),
                    () -> ddl.executeDdl("target", "ALTER SEQUENCE seq INCREMENT BY 2"))) {
                IllegalStateException failure = assertThrows(IllegalStateException.class, call);
                assertTrue(failure.getMessage().contains(readOnly ? "只读" : "确认"));
            }
            assertEquals(0, f.opens.get());
            assertEquals(0, f.writes.get());
            assertEquals(0, f.resolutions.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = DbType.class, names = {"ORACLE", "POSTGRESQL"})
    void eachPreparedWriteRunsOnceOnDedicatedConnectionWithExactConfirmation(DbType type) throws Exception {
        for (String environment : List.of("DEVELOPMENT", "TEST", "PRODUCTION")) {
            Fixture f = new Fixture(type, false, environment);
            Connection browse = f.manager.acquire("target");
            for (WriteOperation<?> request : requests(f)) {
                assertEquals(environment.equals("PRODUCTION"), request.confirmationRequired());
                var confirmation = request.confirm();
                assertNotNull(request.execute(environment.equals("PRODUCTION") ? confirmation : null));
                assertThrows(IllegalStateException.class, () -> request.execute(confirmation));
            }
            assertEquals(6, f.writes.get());
            assertEquals(7, f.opens.get());
            assertEquals(6, f.closes.get());
            assertSame(browse, f.manager.acquire("target"));
            assertTrue(f.writeConnections.stream().noneMatch(c -> c == browse));
            f.manager.closeAll();
            assertEquals(7, f.closes.get());
        }
    }

    @Test
    void confirmationCannotMoveAcrossOperationsRequestsOrTargets() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "PRODUCTION");
        var requests = requests(f);
        var confirmation = requests.getFirst().confirm();
        for (var other : requests.subList(1, requests.size())) {
            assertThrows(IllegalStateException.class, () -> other.execute(confirmation));
        }
        Fixture otherTarget = new Fixture(DbType.POSTGRESQL, false, "PRODUCTION");
        assertThrows(IllegalStateException.class, () -> requests(otherTarget).getFirst().execute(confirmation));
        assertEquals(0, f.opens.get());
        assertEquals(0, otherTarget.opens.get());
        assertEquals(1, requests.getFirst().execute(confirmation));
        assertEquals(1, f.writes.get());
    }

    @Test
    void configDeletionRenameProviderSafetyAndAbaChangesInvalidateEveryPreparedWrite() {
        for (String change : List.of("delete", "rename", "provider", "readOnly", "production", "host", "aba")) {
            Fixture f = new Fixture(DbType.POSTGRESQL, false, "TEST");
            var requests = requests(f);
            var confirmations = requests.stream().map(WriteOperation::confirm).toList();
            if (change.equals("delete") || change.equals("aba")) f.manager.unregister("target");
            if (change.equals("aba")) f.manager.register(f.config);
            else if (!change.equals("delete")) {
                var c = f.config;
                f.manager.register(new ConnConfig(c.id(), change.equals("rename") ? "renamed" : c.name(),
                        change.equals("provider") ? DbType.ORACLE : c.type(),
                        change.equals("host") ? "replacement.invalid" : c.host(), c.port(),
                        c.database(), c.username(), c.encryptedPassword(),
                        change.equals("readOnly") ? Map.of("readOnly", "true")
                                : change.equals("production") ? Map.of("environment", "PRODUCTION") : c.props()));
            }
            for (int i = 0; i < requests.size(); i++) {
                var request = requests.get(i);
                var permit = confirmations.get(i);
                assertThrows(IllegalStateException.class, () -> request.execute(permit), change);
            }
            assertEquals(0, f.opens.get(), change);
            assertEquals(0, f.writes.get(), change);
        }
    }

    @Test
    void rowValuesAndMutableKeysAreFrozenBeforeConfirmation() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "PRODUCTION");
        var values = values();
        var timestamp = java.sql.Timestamp.valueOf("2020-01-01 00:00:00");
        RowKey key = new RowKey(List.of("stamp"), List.of(timestamp));
        var request = new DataEditService(f.manager).prepareUpdate(f.manager.writeTarget("target"), TABLE, values, key);
        var confirmation = request.confirm();
        values.put("id", "changed");
        timestamp.setTime(0);
        request.execute(confirmation);
        assertEquals("1", f.lastValues.get("id"));
        assertEquals(java.sql.Timestamp.valueOf("2020-01-01 00:00:00"), f.lastKey.values().getFirst());
        assertFalse(request.toString().contains("synthetic"));
        assertFalse(confirmation.toString().contains("target"));
    }

    @Test
    void mutableTemporalParameterCannotChangeTheConfirmedRequest() {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "PRODUCTION");
        var mutable = new java.time.temporal.TemporalAccessor() {
            long value;
            public boolean isSupported(java.time.temporal.TemporalField field) { return true; }
            public long getLong(java.time.temporal.TemporalField field) { return value++; }
        };
        assertThrows(IllegalArgumentException.class, () -> new DataEditService(f.manager).prepareDelete(
                f.manager.writeTarget("target"), TABLE, new RowKey(List.of("stamp"), List.of(mutable))));
        assertEquals(0, f.opens.get());
        assertEquals(0, f.writes.get());
    }

    @Test
    void configChangeDuringOpenClosesDedicatedConnectionWithoutWriting() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "TEST");
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        f.onOpen = () -> { entered.countDown(); await(resume); };
        var request = requests(f).getFirst();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> result = executor.submit(() -> request.execute(null));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                f.manager.unregister("target");
            } finally { resume.countDown(); }
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
        }
        assertEquals(1, f.opens.get());
        assertEquals(1, f.closes.get());
        assertEquals(0, f.writes.get());
    }

    @Test
    void cancelledRequestAndDriverFailureDoNotLeakOrWriteAfterClose() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, false, "TEST");
        Thread.currentThread().interrupt();
        try { assertThrows(IllegalStateException.class, () -> requests(f).getFirst().execute(null)); }
        finally { Thread.interrupted(); }
        assertEquals(0, f.opens.get());
        f.failWrite = true;
        assertThrows(SQLException.class, () -> requests(f).getFirst().execute(null));
        assertEquals(1, f.closes.get());
    }

    @Test
    void readOnlyMetadataAndDdlPreviewRemainAvailableWithoutWriteDispatch() throws Exception {
        Fixture f = new Fixture(DbType.POSTGRESQL, true, "PRODUCTION");
        assertEquals("id", new DataEditService(f.manager).columns("target", TABLE).getFirst().name());
        assertEquals("CREATE TABLE preview(id int)", new DdlService(f.manager).tableDdl("target", TABLE));
        assertEquals(0, f.writes.get());
        f.manager.closeAll();
    }

    static LinkedHashMap<String, String> values() { return new LinkedHashMap<>(Map.of("id", "1")); }

    @Test
    void foreignManagerTargetCannotBypassTheServiceRegistry() {
        Fixture readonly = new Fixture(DbType.POSTGRESQL, true, "TEST");
        Fixture writable = new Fixture(DbType.POSTGRESQL, false, "TEST");
        var target = writable.manager.writeTarget("target");
        assertThrows(IllegalArgumentException.class,
                () -> new DataEditService(readonly.manager).prepareInsert(target, TABLE, values()));
        assertThrows(IllegalArgumentException.class,
                () -> new TableDesignService(readonly.manager).prepareExecute(target, "CREATE TABLE t(id int)", null));
        assertThrows(IllegalArgumentException.class,
                () -> new DdlService(readonly.manager).prepareExecute(target, "DROP VIEW v"));
        assertEquals(0, readonly.opens.get());
        assertEquals(0, writable.opens.get());
    }
    static List<WriteOperation<?>> requests(Fixture f) {
        DataEditService edits = new DataEditService(f.manager);
        var target = f.manager.writeTarget("target");
        DdlService ddl = new DdlService(f.manager);
        return List.of(edits.prepareInsert(target, TABLE, values()),
                edits.prepareUpdate(target, TABLE, values(), KEY), edits.prepareDelete(target, TABLE, KEY),
                new TableDesignService(f.manager).prepareExecute(target, "CREATE TABLE items(id int)", null),
                ddl.prepareExecute(target, "CREATE VIEW v AS SELECT 1"),
                ddl.prepareExecute(target, "ALTER SEQUENCE seq INCREMENT BY 2"));
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    static final class Fixture {
        final AtomicInteger resolutions = new AtomicInteger(), opens = new AtomicInteger(),
                closes = new AtomicInteger(), writes = new AtomicInteger(), commits = new AtomicInteger(),
                rollbacks = new AtomicInteger();
        final List<Connection> writeConnections = new ArrayList<>();
        final List<String> scripts = new ArrayList<>();
        final ConnConfig config;
        final ConnectionManager manager;
        Runnable onOpen = () -> {};
        boolean failWrite;
        Map<String, String> lastValues;
        RowKey lastKey;
        List<SqlParameter> lastParameters;

        Fixture(DbType type, boolean readOnly, String environment) {
            config = new ConnConfig("target", "synthetic", type, "synthetic.invalid", 1,
                    "synthetic", "synthetic", "", Map.of("readOnly", "" + readOnly, "environment", environment));
            ConnectionFactory factory = new ConnectionFactory() {
                public void ensureDriverLoaded() {}
                public String test(ConnConfig ignored) { throw new AssertionError("no live probes"); }
                public Connection open(ConnConfig cfg) {
                    opens.incrementAndGet();
                    onOpen.run();
                    boolean[] state = {true, false};
                    return proxy(Connection.class, (p, m, args) -> switch (m.getName()) {
                        case "getAutoCommit" -> state[0];
                        case "setAutoCommit" -> { state[0] = (boolean) args[0]; yield null; }
                        case "isClosed" -> state[1];
                        case "isValid" -> !state[1];
                        case "close" -> { if (!state[1]) closes.incrementAndGet(); state[1] = true; yield null; }
                        case "commit" -> { commits.incrementAndGet(); yield null; }
                        case "rollback" -> { rollbacks.incrementAndGet(); yield null; }
                        case "setReadOnly" -> null;
                        default -> throw new AssertionError(m.getName());
                    });
                }
            };
            SqlRunner runner = proxy(SqlRunner.class, (p, m, args) -> {
                writes.incrementAndGet(); writeConnections.add((Connection) args[0]); scripts.add((String) args[1]);
                if (m.getName().equals("executePrepared")) {
                    @SuppressWarnings("unchecked") var parameters = (List<SqlParameter>) args[2];
                    lastParameters = parameters;
                }
                if (failWrite) throw new SQLException("synthetic driver failure");
                QueryResult result = QueryResult.update(1, 0);
                return m.getName().equals("executeScript") ? List.of(new ScriptOutcome(1, (String) args[1], result)) : result;
            });
            DatabaseProvider provider = proxy(DatabaseProvider.class, (p, m, args) -> switch (m.getName()) {
                case "type" -> type;
                case "connectionFactory" -> factory;
                case "sqlRunner" -> runner;
                case "dataAccessor" -> proxy(DataAccessor.class, (ap, am, aa) ->
                        new PagedResult(List.of("id"), List.of(List.of(1)), false));
                case "metadataReader" -> proxy(MetadataReader.class, (mp, mm, ma) ->
                        mm.getName().equals("sequence")
                                ? new SequenceDraft("synthetic", "seq", "1", "999", "1", "1", 0, false, false)
                                : List.of());
                case "tableDdlBuilder" -> proxy(TableDdlBuilder.class, (tp, tm, ta) -> "CREATE TABLE items(id int)");
                case "sequenceDdlBuilder" -> proxy(SequenceDdlBuilder.class, (sp, sm, sa) -> "ALTER SEQUENCE seq INCREMENT BY 2");
                case "ddlGenerator" -> proxy(DdlGenerator.class, (dp, dm, da) -> "CREATE TABLE preview(id int)");
                case "dataEditor" -> proxy(DataEditor.class, (ep, em, ea) -> {
                    if (em.getName().equals("columns")) return List.of(new EditableColumn(
                            "id", java.sql.Types.INTEGER, "int", false, true, false, true, null));
                    writes.incrementAndGet(); writeConnections.add((Connection) args[0]);
                    if (failWrite) throw new SQLException("synthetic driver failure");
                    if (!em.getName().equals("delete")) {
                        @SuppressWarnings("unchecked") var values = (Map<String, String>) ea[1];
                        lastValues = values;
                    }
                    if (em.getName().equals("update")) lastKey = (RowKey) ea[2];
                    if (em.getName().equals("delete")) lastKey = (RowKey) ea[1];
                    return 1;
                });
                default -> throw new AssertionError(m.getName());
            });
            manager = new ConnectionManager(new CredentialCipher(), ignored -> { resolutions.incrementAndGet(); return provider; });
            manager.register(config);
        }
    }

    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
