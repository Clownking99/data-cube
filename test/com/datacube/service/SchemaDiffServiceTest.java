package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SchemaDiffServiceTest {

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
        return new QualifiedName(value, value, false);
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
        private final List<ConnConfig> opened = new ArrayList<>();

        @Override
        public void ensureDriverLoaded() {
        }

        @Override
        public Connection open(ConnConfig config) {
            opened.add(config);
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> null;
                        case "isClosed" -> false;
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
