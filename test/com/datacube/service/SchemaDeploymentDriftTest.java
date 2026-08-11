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
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SchemaSnapshotReader;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SchemaDeploymentDriftTest {
    private static final String CHANGE_ID = "chg:" + "a".repeat(64);

    @Test
    void driftAndIncompleteFreshSnapshotsBlockBeforeFirstSql() throws Exception {
        for (SchemaSnapshot current : List.of(
                snapshot(DbType.POSTGRESQL, true, "changed"),
                snapshot(DbType.POSTGRESQL, false, "expected"),
                snapshot(DbType.ORACLE, true, "expected"))) {
            Fixture fixture = new Fixture(current);

            SchemaDeploymentResult result = fixture.service.deploy(
                    fixture.request, snapshot(DbType.POSTGRESQL, true, "expected"),
                    statements(), new SchemaDeploymentControl()).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(current.completeness().complete()
                            ? SchemaDeploymentState.BLOCKED_DRIFT
                            : SchemaDeploymentState.BLOCKED_INCOMPLETE,
                    result.state());
            assertEquals(List.of(), result.steps());
            assertEquals(SchemaDeploymentService.confirmationToken(statements()),
                    result.planDigest());
            assertEquals(1, fixture.factory.opens.get(), "only the fresh drift read may open");
            assertEquals(1, fixture.factory.closes.get());
            assertEquals(0, fixture.runner.calls.get());
        }
    }

    @Test
    void invalidExpectedTargetFailsBeforeFreshReadOrSql() throws Exception {
        for (SchemaSnapshot invalidExpected : List.of(
                snapshot(DbType.ORACLE, true, "expected"),
                snapshot(DbType.POSTGRESQL, false, "expected"))) {
            Fixture fixture = new Fixture(snapshot(DbType.POSTGRESQL, true, "expected"));

            Throwable failure = failure(fixture.service.deploy(
                    fixture.request, invalidExpected, statements(), new SchemaDeploymentControl()));

            assertInstanceOf(IllegalArgumentException.class, failure);
            assertEquals("Expected target snapshot is invalid", failure.getMessage());
            assertEquals(0, fixture.factory.opens.get());
            assertEquals(0, fixture.runner.calls.get());
        }
    }

    private static List<RenderedStatement> statements() {
        return List.of(new RenderedStatement(CHANGE_ID, "CREATE TABLE safe_table(id int)",
                false, Set.of(), null));
    }

    private static SchemaSnapshot snapshot(DbType type, boolean complete, String fingerprint) {
        SnapshotCompleteness completeness = complete
                ? new SnapshotCompleteness(true, new TreeMap<>())
                : new SnapshotCompleteness(false,
                        new TreeMap<>(Map.of(ObjectType.TABLE, SnapshotCompleteness.PERMISSION_DENIED)));
        return new SchemaSnapshot(type, "target", name("actual"), Instant.EPOCH,
                completeness, new TreeMap<>(), fingerprint);
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static Throwable failure(java.util.concurrent.CompletionStage<?> stage) throws Exception {
        try {
            stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected failure");
        } catch (ExecutionException failure) {
            Throwable current = failure;
            while (current.getCause() != null) current = current.getCause();
            return current;
        }
    }

    private static final class Fixture {
        private final RecordingFactory factory = new RecordingFactory();
        private final RecordingRunner runner = new RecordingRunner();
        private final SchemaDeploymentService service;
        private final SchemaDiffRequest request;

        private Fixture(SchemaSnapshot current) {
            CredentialCipher cipher = new CredentialCipher();
            SchemaDiffCapability capability = capability(current);
            DatabaseProvider provider = provider(factory, runner, capability);
            ConnectionManager manager = new ConnectionManager(cipher, type -> provider);
            ConnConfig source = config(cipher, "source");
            ConnConfig target = config(cipher, "target");
            request = new SchemaDiffRequest(source, name("desired"), target, name("actual"));
            service = new SchemaDeploymentService(manager);
        }
    }

    private static SchemaDiffCapability capability(SchemaSnapshot current) {
        return new SchemaDiffCapability() {
            @Override
            public SchemaSnapshotReader snapshotReader(Connection connection) {
                return (connectionId, schema, options) -> current;
            }

            @Override
            public SchemaChangeRenderer changeRenderer() {
                return (change, context) -> List.of();
            }

            @Override
            public Set<ObjectType> supportedObjectTypes() {
                return Set.of(ObjectType.TABLE);
            }
        };
    }

    private static DatabaseProvider provider(
            RecordingFactory factory, RecordingRunner runner, SchemaDiffCapability capability) {
        return (DatabaseProvider) Proxy.newProxyInstance(
                SchemaDeploymentDriftTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> DbType.POSTGRESQL;
                    case "connectionFactory" -> factory;
                    case "sqlRunner" -> runner;
                    case "schemaDiffCapability" -> Optional.of(capability);
                    default -> null;
                });
    }

    private static ConnConfig config(CredentialCipher cipher, String id) {
        return new ConnConfig(id, id, DbType.POSTGRESQL, id + "-host", 5432,
                "database", "user", cipher.encrypt("credential-secret"), Map.of(
                "environment", "TEST", "queryTimeoutSeconds", "17"));
    }

    private static final class RecordingFactory implements ConnectionFactory {
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override public void ensureDriverLoaded() { }

        @Override
        public Connection open(ConnConfig config) {
            opens.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> { closes.incrementAndGet(); yield null; }
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        @Override public String test(ConnConfig config) { return null; }
    }

    private static final class RecordingRunner implements SqlRunner {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            calls.incrementAndGet();
            return QueryResult.update(1, 1);
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            calls.incrementAndGet();
            return List.of(new ScriptOutcome(1, script, QueryResult.update(1, 1)));
        }

        @Override
        public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            calls.incrementAndGet();
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
