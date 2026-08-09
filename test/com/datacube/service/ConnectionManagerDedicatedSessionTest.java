package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionManagerDedicatedSessionTest {

    @Test
    void editorSessionsOwnDistinctConnectionsWhileAcquireRemainsShared() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        RecordingConnectionFactory factory = new RecordingConnectionFactory();
        RecordingRunner runner = new RecordingRunner();
        AtomicInteger resolutions = new AtomicInteger();
        DatabaseProvider provider = provider(factory, runner);
        ConnectionManager manager = new ConnectionManager(cipher, type -> {
            resolutions.incrementAndGet();
            assertEquals(DbType.POSTGRESQL, type);
            return provider;
        });
        manager.register(config(cipher, "secret"));

        JdbcEditorSession first = manager.openEditorSession("conn");
        JdbcEditorSession second = manager.openEditorSession("conn");
        first.executeScript("select 1", null, 10, null, false);
        second.executeScript("select 2", null, 10, null, false);

        assertEquals(2, runner.connections.size());
        assertNotSame(runner.connections.get(0), runner.connections.get(1));
        assertEquals(2, factory.opens.size());
        assertFalse(manager.isConnected("conn"), "dedicated editor connections are not cached in live");

        Connection sharedFirst = manager.acquire("conn");
        Connection sharedSecond = manager.acquire("conn");
        assertSame(sharedFirst, sharedSecond);
        assertEquals(3, factory.opens.size());
        assertEquals(3, resolutions.get(),
                "sessions resolve once at creation; shared acquire resolves once when opening");

        first.close();
        second.close();
        assertEquals(2, factory.closedCount());
        manager.release("conn");
        assertEquals(3, factory.closedCount());
    }

    @Test
    void dedicatedOpenDecryptsIntoTemporaryConfigWithoutMutatingRegisteredConfig() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        RecordingConnectionFactory factory = new RecordingConnectionFactory();
        ConnectionManager manager = new ConnectionManager(
                cipher, type -> provider(factory, new RecordingRunner()));
        ConnConfig original = config(cipher, "secret-value");
        manager.register(original);

        Connection dedicated = manager.openDedicated("conn");

        assertEquals("secret-value", factory.openConfigs.getFirst().props().get("__plainPassword"));
        assertFalse(manager.config("conn").props().containsKey("__plainPassword"));
        assertEquals(original.encryptedPassword(), manager.config("conn").encryptedPassword());
        dedicated.close();
    }

    @Test
    void injectedResolverAlsoSuppliesProviderAndConnectionTestPaths() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        RecordingConnectionFactory factory = new RecordingConnectionFactory();
        RecordingRunner runner = new RecordingRunner();
        DatabaseProvider provider = provider(factory, runner);
        AtomicInteger resolutions = new AtomicInteger();
        ConnectionManager manager = new ConnectionManager(cipher, type -> {
            resolutions.incrementAndGet();
            return provider;
        });
        ConnConfig config = config(cipher, "");
        manager.register(config);

        assertSame(provider, manager.provider("conn"));
        assertNull(manager.test(config));
        Connection dedicated = manager.openDedicated("conn");

        assertEquals(3, resolutions.get());
        assertEquals(1, factory.tests.get());
        dedicated.close();
    }

    @Test
    void editorSessionKeepsItsOriginalConfigProviderRunnerAndSafetyAfterReregister() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        RecordingConnectionFactory originalFactory = new RecordingConnectionFactory();
        RecordingConnectionFactory replacementFactory = new RecordingConnectionFactory();
        RecordingRunner originalRunner = new RecordingRunner();
        RecordingRunner replacementRunner = new RecordingRunner();
        DatabaseProvider originalProvider = provider(
                DbType.POSTGRESQL, originalFactory, originalRunner);
        DatabaseProvider replacementProvider = provider(
                DbType.ORACLE, replacementFactory, replacementRunner);
        List<DbType> resolutions = new ArrayList<>();
        ConnectionManager manager = new ConnectionManager(cipher, type -> {
            resolutions.add(type);
            return type == DbType.POSTGRESQL ? originalProvider : replacementProvider;
        });
        ConnConfig original = jdbcConfig(cipher, DbType.POSTGRESQL, "original-host",
                "original-secret", true, 19);
        manager.register(original);
        JdbcEditorSession session = manager.openEditorSession("conn");

        manager.register(jdbcConfig(cipher, DbType.ORACLE, "replacement-host",
                "replacement-secret", false, 91));
        session.executeScript("select 1", null, 10, null, false);
        session.reconnect();

        assertEquals(List.of(DbType.POSTGRESQL), resolutions);
        assertEquals(2, originalFactory.opens.size());
        assertEquals(0, replacementFactory.opens.size());
        assertEquals(1, originalRunner.connections.size());
        assertEquals(0, replacementRunner.connections.size());
        assertTrue(originalFactory.openConfigs.stream()
                .allMatch(config -> config.type() == DbType.POSTGRESQL
                        && config.host().equals("original-host")
                        && config.props().get("__plainPassword").equals("original-secret")));
        assertTrue(session.snapshot().safety().readOnly());
        assertEquals(19, session.snapshot().safety().queryTimeoutSeconds());
        session.close();
    }

    @Test
    void redisCannotCreateJdbcEditorSessionAndDoesNotConsultProviderResolver() {
        CredentialCipher cipher = new CredentialCipher();
        AtomicInteger resolutions = new AtomicInteger();
        ConnectionManager manager = new ConnectionManager(cipher, type -> {
            resolutions.incrementAndGet();
            throw new AssertionError("Redis must not resolve a JDBC provider");
        });
        manager.register(new ConnConfig("redis", "redis", DbType.REDIS, "localhost", 6379,
                "0", "", "", Map.of()));

        assertThrows(IllegalStateException.class, () -> manager.openDedicated("redis"));
        assertThrows(IllegalStateException.class, () -> manager.openEditorSession("redis"));
        assertEquals(0, resolutions.get());
    }

    private static ConnConfig config(CredentialCipher cipher, String password) {
        return new ConnConfig("conn", "test", DbType.POSTGRESQL, "localhost", 5432,
                "db", "user", cipher.encrypt(password), Map.of(
                "environment", "TEST",
                "readOnly", "true",
                "queryTimeoutSeconds", "31"));
    }

    private static ConnConfig jdbcConfig(
            CredentialCipher cipher,
            DbType type,
            String host,
            String password,
            boolean readOnly,
            int timeout) {
        return new ConnConfig("conn", type.name(), type, host,
                type == DbType.POSTGRESQL ? 5432 : 1521,
                "db", "user", cipher.encrypt(password), Map.of(
                "environment", "TEST",
                "readOnly", Boolean.toString(readOnly),
                "queryTimeoutSeconds", Integer.toString(timeout)));
    }

    private static DatabaseProvider provider(
            RecordingConnectionFactory factory, RecordingRunner runner) {
        return provider(DbType.POSTGRESQL, factory, runner);
    }

    private static DatabaseProvider provider(
            DbType type, RecordingConnectionFactory factory, RecordingRunner runner) {
        return (DatabaseProvider) Proxy.newProxyInstance(
                ConnectionManagerDedicatedSessionTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> type;
                    case "supports" -> true;
                    case "connectionFactory" -> factory;
                    case "sqlRunner" -> runner;
                    default -> null;
                });
    }

    private static final class RecordingConnectionFactory implements ConnectionFactory {
        private final List<Connection> opens = new ArrayList<>();
        private final List<ConnConfig> openConfigs = new ArrayList<>();
        private final List<AtomicInteger> closes = new ArrayList<>();
        private final AtomicInteger tests = new AtomicInteger();

        @Override
        public void ensureDriverLoaded() {
        }

        @Override
        public Connection open(ConnConfig config) {
            AtomicInteger closeCount = new AtomicInteger();
            Connection connection = (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "close" -> {
                                closeCount.compareAndSet(0, 1);
                                yield null;
                            }
                            case "isClosed" -> closeCount.get() > 0;
                            case "isValid" -> closeCount.get() == 0;
                            case "getAutoCommit" -> true;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
            opens.add(connection);
            openConfigs.add(config);
            closes.add(closeCount);
            return connection;
        }

        @Override
        public String test(ConnConfig config) {
            tests.incrementAndGet();
            return null;
        }

        private int closedCount() {
            return closes.stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    private static final class RecordingRunner implements SqlRunner {
        private final List<Connection> connections = new ArrayList<>();

        @Override
        public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            connections.add(connection);
            return QueryResult.update(1, 1);
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            connections.add(connection);
            return List.of(new ScriptOutcome(1, script, QueryResult.update(1, 1)));
        }

        @Override
        public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            connections.add(connection);
            return QueryResult.update(1, 1);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
