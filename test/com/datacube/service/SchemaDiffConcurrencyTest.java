package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SchemaSnapshotReader;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffConcurrencyTest {

    @Test
    void readersOverlapOnOwnedVirtualThreadsConnectionsControlsAndPinnedConfigs() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        CountDownLatch bothReadersEntered = new CountDownLatch(2);
        RecordingFactory factory = new RecordingFactory(false);
        RecordingCapability capability = new RecordingCapability(
                factory, bothReadersEntered, ReadScenario.SUCCESS);
        DatabaseProvider provider = provider(factory, capability);
        AtomicInteger resolutions = new AtomicInteger();
        ConnectionManager manager = new ConnectionManager(cipher, type -> {
            resolutions.incrementAndGet();
            assertEquals(DbType.POSTGRESQL, type);
            return provider;
        });
        ConnConfig source = config(cipher, "source", "source-host", 11);
        ConnConfig target = config(cipher, "target", "target-host", 13);
        manager.register(source);
        manager.register(target);
        SchemaDiffRequest request = new SchemaDiffRequest(
                source, name("desired"), target, name("actual"));

        CompletionStage<SchemaDiffResult> stage =
                new SchemaDiffService(manager).compare(request, new SchemaDeploymentControl());
        manager.register(config(cipher, "source", "mutated-source", 71));
        manager.register(config(cipher, "target", "mutated-target", 73));
        SchemaDiffResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("source", result.source().connectionId());
        assertEquals("target", result.target().connectionId());
        assertEquals("desired", result.source().schema().original());
        assertEquals("actual", result.target().schema().original());
        assertTrue(result.differences().isEmpty());
        assertEquals(List.of("source-host", "target-host"), factory.openedHosts());
        assertEquals(List.of(11, 13), factory.openedTimeouts());
        assertEquals(2, factory.readOnlyAttempts.get());
        assertEquals(2, factory.closeCount());
        assertEquals(2, capability.threads.size());
        assertTrue(capability.threads.stream().allMatch(Thread::isVirtual));
        assertNotSame(capability.connections.get(0), capability.connections.get(1));
        assertNotSame(capability.controls.get(0), capability.controls.get(1));
        assertEquals(1, resolutions.get(), "provider selection is frozen once at admission");
    }

    @Test
    void partialOpenFailureCancelsPeerClosesOpenedConnectionOnceAndRedactsDriverText() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        RecordingFactory factory = new RecordingFactory(true);
        RecordingCapability capability = new RecordingCapability(
                factory, new CountDownLatch(1), ReadScenario.BLOCK_UNTIL_CANCEL);
        ConnectionManager manager = manager(cipher, factory, capability);
        SchemaDeploymentControl control = new SchemaDeploymentControl();

        Throwable failure = failure(new SchemaDiffService(manager).compare(
                request(cipher), control));

        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals("Schema snapshot failed", failure.getMessage());
        assertFalse(failure.toString().contains("driver-open-secret"));
        assertEquals(1, factory.connections.size());
        assertEquals(1, factory.closeCount());
        assertTrue(control.cancellationRequested());
    }

    @Test
    void readerFailureCancelsPeerAndClosesBothConnectionsExactlyOnce() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        CountDownLatch bothReadersEntered = new CountDownLatch(2);
        RecordingFactory factory = new RecordingFactory(false);
        RecordingCapability capability = new RecordingCapability(
                factory, bothReadersEntered, ReadScenario.SOURCE_FAILS);
        ConnectionManager manager = manager(cipher, factory, capability);
        SchemaDeploymentControl control = new SchemaDeploymentControl();

        Throwable failure = failure(new SchemaDiffService(manager).compare(
                request(cipher), control));

        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals("Schema snapshot failed", failure.getMessage());
        assertFalse(failure.toString().contains("reader-driver-secret"));
        assertTrue(control.cancellationRequested());
        assertEquals(2, factory.closeCount());
        assertTrue(capability.controls.stream().allMatch(SqlExecutionControl::cancellationRequested));
    }

    @Test
    void cancellationReachesBothReadersAndLateSnapshotsCannotBecomeSuccess() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        CountDownLatch bothReadersEntered = new CountDownLatch(2);
        RecordingFactory factory = new RecordingFactory(false);
        RecordingCapability capability = new RecordingCapability(
                factory, bothReadersEntered, ReadScenario.BLOCK_UNTIL_CANCEL);
        ConnectionManager manager = manager(cipher, factory, capability);
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        CompletionStage<SchemaDiffResult> stage =
                new SchemaDiffService(manager).compare(request(cipher), control);
        assertTrue(bothReadersEntered.await(2, TimeUnit.SECONDS));

        assertTrue(control.cancel());
        Throwable failure = failure(stage);

        assertInstanceOf(java.util.concurrent.CancellationException.class, failure);
        assertEquals("Schema comparison cancelled", failure.getMessage());
        assertTrue(capability.controls.stream().allMatch(SqlExecutionControl::cancellationRequested));
        assertEquals(2, factory.closeCount());
    }

    private static ConnectionManager manager(
            CredentialCipher cipher, RecordingFactory factory, RecordingCapability capability) {
        DatabaseProvider provider = provider(factory, capability);
        return new ConnectionManager(cipher, type -> provider);
    }

    private static SchemaDiffRequest request(CredentialCipher cipher) {
        return new SchemaDiffRequest(config(cipher, "source", "source-host", 11), name("desired"),
                config(cipher, "target", "target-host", 13), name("actual"));
    }

    private static Throwable failure(CompletionStage<?> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected failure");
        } catch (ExecutionException failure) {
            return rootCause(failure);
        } catch (java.util.concurrent.CancellationException failure) {
            return rootCause(failure);
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static ConnConfig config(
            CredentialCipher cipher, String id, String host, int timeoutSeconds) {
        return new ConnConfig(id, id, DbType.POSTGRESQL, host, 5432,
                "database", "user", cipher.encrypt("credential-secret"), Map.of(
                "environment", "TEST",
                "readOnly", "false",
                "queryTimeoutSeconds", Integer.toString(timeoutSeconds)));
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static DatabaseProvider provider(
            ConnectionFactory factory, SchemaDiffCapability capability) {
        return (DatabaseProvider) Proxy.newProxyInstance(
                SchemaDiffConcurrencyTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> DbType.POSTGRESQL;
                    case "connectionFactory" -> factory;
                    case "schemaDiffCapability" -> Optional.of(capability);
                    default -> null;
                });
    }

    private enum ReadScenario { SUCCESS, SOURCE_FAILS, BLOCK_UNTIL_CANCEL }

    private static final class RecordingCapability implements SchemaDiffCapability {
        private final RecordingFactory factory;
        private final CountDownLatch bothReadersEntered;
        private final ReadScenario scenario;
        private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        private final List<Connection> connections = Collections.synchronizedList(new ArrayList<>());
        private final List<SqlExecutionControl> controls =
                Collections.synchronizedList(new ArrayList<>());

        private RecordingCapability(
                RecordingFactory factory,
                CountDownLatch bothReadersEntered,
                ReadScenario scenario) {
            this.factory = factory;
            this.bothReadersEntered = bothReadersEntered;
            this.scenario = scenario;
        }

        @Override
        public SchemaSnapshotReader snapshotReader(Connection connection) {
            return (connectionId, schema, options) -> {
                threads.add(Thread.currentThread());
                connections.add(connection);
                controls.add(options.control());
                bothReadersEntered.countDown();
                try {
                    assertTrue(bothReadersEntered.await(2, TimeUnit.SECONDS),
                            "source and target reads must overlap");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Snapshot read interrupted");
                }
                ConnConfig config = factory.config(connection);
                if (scenario == ReadScenario.SOURCE_FAILS && config.id().equals("source")) {
                    throw new SQLException("reader-driver-secret");
                }
                if (scenario != ReadScenario.SUCCESS) {
                    awaitCancellation(options.control(), connection);
                }
                return new SchemaSnapshot(config.type(), connectionId, schema, Instant.EPOCH,
                        new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(),
                        connectionId + "-fingerprint");
            };
        }

        @Override
        public SchemaChangeRenderer changeRenderer() {
            return (change, context) -> List.of();
        }

        @Override
        public Set<ObjectType> supportedObjectTypes() {
            return Set.of(ObjectType.TABLE);
        }

        private void awaitCancellation(SqlExecutionControl control, Connection connection)
                throws SQLException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!control.cancellationRequested()
                    && !factory.isClosed(connection)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            if (!control.cancellationRequested() && !factory.isClosed(connection)) {
                throw new SQLException("Cancellation did not reach snapshot reader");
            }
        }
    }

    private static final class RecordingFactory implements ConnectionFactory {
        private final Map<Connection, ConnConfig> configs =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final List<ConnConfig> opened = Collections.synchronizedList(new ArrayList<>());
        private final List<AtomicInteger> closes = Collections.synchronizedList(new ArrayList<>());
        private final List<Connection> connections = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger readOnlyAttempts = new AtomicInteger();
        private final CountDownLatch sourceOpened = new CountDownLatch(1);
        private final boolean failTargetOpen;

        private RecordingFactory(boolean failTargetOpen) {
            this.failTargetOpen = failTargetOpen;
        }

        @Override
        public void ensureDriverLoaded() {
        }

        @Override
        public Connection open(ConnConfig config) throws SQLException {
            if (config.id().equals("target") && failTargetOpen) {
                try {
                    assertTrue(sourceOpened.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Target open interrupted");
                }
                throw new SQLException("driver-open-secret");
            }
            AtomicInteger closeCount = new AtomicInteger();
            Connection connection = (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setReadOnly" -> {
                            readOnlyAttempts.incrementAndGet();
                            yield null;
                        }
                        case "close" -> {
                            closeCount.incrementAndGet();
                            yield null;
                        }
                        case "isClosed" -> closeCount.get() > 0;
                        default -> defaultValue(method.getReturnType());
                    });
            configs.put(connection, config);
            opened.add(config);
            closes.add(closeCount);
            connections.add(connection);
            if (config.id().equals("source")) sourceOpened.countDown();
            return connection;
        }

        @Override
        public String test(ConnConfig config) {
            return null;
        }

        private ConnConfig config(Connection connection) {
            return configs.get(connection);
        }

        private List<String> openedHosts() {
            return opened.stream().map(ConnConfig::host).sorted().toList();
        }

        private List<Integer> openedTimeouts() {
            return opened.stream()
                    .map(config -> Integer.parseInt(config.props().get("queryTimeoutSeconds")))
                    .sorted().toList();
        }

        private int closeCount() {
            return closes.stream().mapToInt(AtomicInteger::get).sum();
        }

        private boolean isClosed(Connection connection) {
            int index = connections.indexOf(connection);
            return index >= 0 && closes.get(index).get() > 0;
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
