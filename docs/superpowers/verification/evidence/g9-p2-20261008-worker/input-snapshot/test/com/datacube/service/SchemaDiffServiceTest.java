package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.provider.postgres.PgSchemaDiffCapability;
import com.datacube.provider.postgres.PgSchemaIdentifierNormalizer;
import com.datacube.schemadiff.DifferenceKind;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SchemaDiffServiceTest {

    @Test
    void concurrentFactoryRecordsEveryOpenAndPhysicalClose() throws Exception {
        RecordingConnectionFactory factory = new RecordingConnectionFactory();
        int workers = 32;
        int opensPerWorker = 256;
        CyclicBarrier start = new CyclicBarrier(workers);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                int index = worker;
                tasks.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    for (int open = 0; open < opensPerWorker; open++) {
                        ConnConfig config = new ConnConfig(index + "-" + open, "synthetic",
                                DbType.POSTGRESQL, "synthetic-host", 5432, "database", "user",
                                "", Map.of());
                        try (Connection ignored = factory.open(config)) { }
                    }
                    return null;
                }));
            }
            for (var task : tasks) {
                try { task.get(10, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException failure) {
                    failures.add(failure.getCause());
                    failure.getCause().printStackTrace(System.out);
                }
            }
        }
        int expected = workers * opensPerWorker;
        System.out.println("recording diagnostic: expected=" + expected + ", recorded="
                + factory.opened.size() + ", physicallyClosed=" + factory.closes.values().stream()
                .mapToInt(AtomicInteger::get).sum() + ", workerFailures=" + failures.size());
        assertEquals(List.of(), failures);
        assertEquals(expected, factory.opened.size());
        assertEquals(expected, factory.opened.stream().map(ConnConfig::id).distinct().count());
        assertEquals(expected, factory.closes.size());
        factory.closes.values().forEach(closes -> assertEquals(1, closes.get()));
    }

    @Test
    void providerAwareCompareReturnsOtherObjectsWhenOneRoutineRequiresManualReview() {
        CredentialCipher cipher = new CredentialCipher();
        RecordingConnectionFactory factory = new RecordingConnectionFactory();
        SchemaDiffCapability capability = new SchemaDiffCapability() {
            @Override
            public com.datacube.spi.schemadiff.SchemaSnapshotReader snapshotReader(Connection connection) {
                return (connectionId, schema, options) -> {
                    String catalogSchema = connectionId + "_schema";
                    assertEquals(name(catalogSchema), schema);
                    return partialSnapshot(connectionId, catalogSchema);
                };
            }

            @Override
            public SchemaChangeRenderer changeRenderer() {
                return new PgSchemaDiffCapability().changeRenderer();
            }

            @Override
            public com.datacube.spi.schemadiff.SchemaComparisonProjector comparisonProjector() {
                return new PgSchemaDiffCapability().comparisonProjector();
            }

            @Override
            public Set<ObjectType> supportedObjectTypes() {
                return Set.of(ObjectType.FUNCTION, ObjectType.SEQUENCE);
            }
        };
        ConnectionManager manager = new ConnectionManager(cipher,
                type -> provider(type, factory, Optional.of(capability)));
        SchemaDiffService service = new SchemaDiffService(manager);
        ConnConfig source = config(cipher, "source", DbType.POSTGRESQL, "source-host", "source-secret");
        ConnConfig target = config(cipher, "target", DbType.POSTGRESQL, "target-host", "target-secret");

        SchemaDiffResult result = service.compare(request(source, target),
                new SchemaDeploymentControl()).toCompletableFuture().join();

        assertEquals("source", result.source().connectionId());
        assertEquals("target", result.target().connectionId());
        assertEquals(name("source_schema"), result.source().schema());
        assertEquals(name("target_schema"), result.target().schema());
        assertEquals(Set.of("source", "target"), factory.opened.stream()
                .map(ConnConfig::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, factory.opened.size());
        factory.opened.forEach(opened -> {
            assertEquals(opened.id() + "-host", opened.host());
            assertEquals(opened.id() + "-secret", opened.props().get("__plainPassword"));
            assertEquals(1, factory.closes.get(opened.id()).get());
        });

        assertEquals(DifferenceKind.MODIFIED, result.differences().stream()
                .filter(difference -> difference.object().type() == ObjectType.FUNCTION)
                .findFirst().orElseThrow().kind());
        assertEquals(AutomationLevel.MANUAL_ONLY, result.differences().stream()
                .filter(difference -> difference.object().type() == ObjectType.FUNCTION)
                .findFirst().orElseThrow().automation());
        assertEquals(DifferenceKind.EQUIVALENT, result.differences().stream()
                .filter(difference -> difference.object().type() == ObjectType.SEQUENCE)
                .findFirst().orElseThrow().kind());
    }

    @Test
    void redisDifferentTypesAndMissingCapabilityFailBeforeAnyConnectionOpens() {
        CredentialCipher cipher = new CredentialCipher();
        RecordingConnectionFactory factory = new RecordingConnectionFactory();
        ConnectionManager manager = new ConnectionManager(
                cipher, type -> provider(type, factory, Optional.empty()));
        SchemaDiffService service = new SchemaDiffService(manager);
        ConnConfig postgres = config(cipher, "source", DbType.POSTGRESQL, "pg-secret-host", "secret");
        ConnConfig oracle = config(cipher, "target", DbType.ORACLE, "oracle-secret-host", "secret");
        ConnConfig redis = config(cipher, "redis", DbType.REDIS, "redis-secret-host", "secret");

        List<Throwable> failures = List.of(
                failure(service.compare(request(postgres, oracle), new SchemaDeploymentControl())),
                failure(service.compare(request(redis, redis), new SchemaDeploymentControl())),
                failure(service.compare(request(postgres, postgres), new SchemaDeploymentControl())));

        assertEquals(0, factory.opened.size());
        failures.forEach(failure -> {
            assertInstanceOf(IllegalArgumentException.class, failure);
            String summary = failure.toString();
            assertFalse(summary.contains("secret-host"));
            assertFalse(summary.contains("secret"));
            assertFalse(summary.contains("jdbc:"));
        });
    }

    @Test
    void dedicatedOpenUsesOnlySuppliedSnapshotAndNeverRegistryReplacement() throws Exception {
        CredentialCipher cipher = new CredentialCipher();
        RecordingConnectionFactory postgresFactory = new RecordingConnectionFactory();
        RecordingConnectionFactory oracleFactory = new RecordingConnectionFactory();
        List<DbType> resolutions = new ArrayList<>();
        ConnectionManager manager = new ConnectionManager(cipher, type -> {
            resolutions.add(type);
            return provider(type, type == DbType.POSTGRESQL ? postgresFactory : oracleFactory);
        });
        ConnConfig pinned = config(cipher, "same-id", DbType.POSTGRESQL, "source-host", "source-secret");
        manager.register(config(cipher, "same-id", DbType.ORACLE, "replacement-host", "replacement-secret"));

        try (Connection ignored = manager.openDedicated(pinned)) {
            assertEquals(List.of(DbType.POSTGRESQL), resolutions);
            assertEquals(1, postgresFactory.opened.size());
            assertEquals(0, oracleFactory.opened.size());
            ConnConfig opened = postgresFactory.opened.getFirst();
            assertEquals(DbType.POSTGRESQL, opened.type());
            assertEquals("source-host", opened.host());
            assertEquals("source-secret", opened.props().get("__plainPassword"));
            assertEquals("replacement-host", manager.config("same-id").host());
        }
    }

    private static ConnConfig config(
            CredentialCipher cipher, String id, DbType type, String host, String password) {
        return new ConnConfig(id, type.name(), type, host,
                type == DbType.POSTGRESQL ? 5432 : 1521,
                "database", "user", cipher.encrypt(password), Map.of("environment", "TEST"));
    }

    private static DatabaseProvider provider(DbType type, ConnectionFactory factory) {
        return provider(type, factory, Optional.empty());
    }

    private static DatabaseProvider provider(
            DbType type, ConnectionFactory factory, Optional<SchemaDiffCapability> capability) {
        return (DatabaseProvider) Proxy.newProxyInstance(
                SchemaDiffServiceTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> type;
                    case "connectionFactory" -> factory;
                    case "schemaDiffCapability" -> capability;
                    default -> null;
                });
    }

    private static SchemaDiffRequest request(ConnConfig source, ConnConfig target) {
        return new SchemaDiffRequest(source, name("source_schema"), target, name("target_schema"));
    }

    private static QualifiedName name(String value) {
        return PgSchemaIdentifierNormalizer.schema(value);
    }

    private static SchemaSnapshot partialSnapshot(String connectionId, String owner) {
        ObjectKey routineKey = new ObjectKey(ObjectType.FUNCTION,
                PgSchemaIdentifierNormalizer.object(owner, "opaque"), "");
        String definition = "CREATE FUNCTION \"" + owner + "\".\"opaque\"() RETURNS integer "
                + "LANGUAGE python AS $body$ SELECT \"" + owner + "\".value $body$";
        DefinitionObject routine = new DefinitionObject(routineKey, definition, definition,
                Set.of(), DefinitionConfidence.LOW);
        ObjectKey sequenceKey = new ObjectKey(ObjectType.SEQUENCE,
                PgSchemaIdentifierNormalizer.object(owner, "stable"), "");
        SequenceDefinition sequence = new SequenceDefinition(sequenceKey,
                "1", "1", "1", "9", false, 1, Set.of());
        SortedMap<ObjectKey, com.datacube.spi.schemadiff.SchemaObject> objects = new TreeMap<>();
        objects.put(routine.key(), routine);
        objects.put(sequence.key(), sequence);
        return new SchemaSnapshot(DbType.POSTGRESQL, connectionId,
                PgSchemaIdentifierNormalizer.schema(owner), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), objects, connectionId);
    }

    private static Throwable failure(java.util.concurrent.CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError("expected failure");
        } catch (CompletionException failure) {
            return failure.getCause();
        }
    }

    private static final class RecordingConnectionFactory implements ConnectionFactory {
        private final List<ConnConfig> opened = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, AtomicInteger> closes = new ConcurrentHashMap<>();

        @Override
        public void ensureDriverLoaded() {
        }

        @Override
        public Connection open(ConnConfig config) {
            opened.add(config);
            AtomicInteger closeCount = new AtomicInteger();
            closes.put(config.id(), closeCount);
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> { closeCount.incrementAndGet(); yield null; }
                        case "isClosed" -> closeCount.get() > 0;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        @Override
        public String test(ConnConfig config) {
            return null;
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
