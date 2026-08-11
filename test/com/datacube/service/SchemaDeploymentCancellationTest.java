package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.spi.ConnectionFactory;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDeploymentCancellationTest {
    private static final String CHANGE_A = "chg:" + "a".repeat(64);
    private static final String CHANGE_B = "chg:" + "b".repeat(64);

    @Test
    void cancellationBeforeAdmissionReturnsDistinctTerminalStateWithoutOpening() throws Exception {
        Fixture fixture = new Fixture();
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        control.cancel();

        SchemaDeploymentResult result = fixture.service.deploy(
                fixture.request, fixture.expected, plan(), control)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(List.of(), result.steps());
        assertEquals(0, fixture.factory.opens.get());
        assertEquals(0, fixture.runner.calls.get());
    }

    @Test
    void cancellationTargetsCurrentStatementPreventsNextAndLateSuccessCannotWin() throws Exception {
        Fixture fixture = new Fixture();
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        var stage = fixture.service.deploy(fixture.request, fixture.expected, plan(), control);
        assertTrue(fixture.runner.started.await(2, TimeUnit.SECONDS));

        assertTrue(control.cancel());
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(List.of(
                        SchemaDeploymentState.CANCELLED,
                        SchemaDeploymentState.SKIPPED_FAIL_FAST),
                result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(1, fixture.runner.calls.get());
        assertEquals(1, fixture.runner.cancelCalls.get());
        assertTrue(fixture.runner.cancelled.await(1, TimeUnit.SECONDS));
        assertEquals(2, fixture.factory.closes.get());
    }

    @Test
    void cancellationDuringFreshReadRemainsCancellationWhenReaderThrows() throws Exception {
        DriftFixture fixture = new DriftFixture();
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        var stage = fixture.service.deploy(fixture.request, fixture.expected, plan(), control);
        assertTrue(fixture.capability.started.await(2, TimeUnit.SECONDS));

        assertTrue(control.cancel());
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(List.of(), result.steps());
        assertEquals(1, fixture.capability.cancelCalls.get());
        assertEquals(1, fixture.factory.closes.get());
        assertEquals(0, fixture.runner.calls.get());
    }

    @Test
    void cancellationWhileOpeningExecutionConnectionPreventsSqlFromStarting() throws Exception {
        Fixture fixture = new Fixture();
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        fixture.factory.cancelOnSecondOpen = control;

        SchemaDeploymentResult result = fixture.service.deploy(
                fixture.request, fixture.expected, plan(), control)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(0, fixture.runner.calls.get());
        assertEquals(2, fixture.factory.closes.get());
    }

    @Test
    void parentCancellationInOperationPublicationGapPreventsRunnerInvocation() throws Exception {
        SchemaDeploymentControl parent = new SchemaDeploymentControl();
        AtomicInteger runnerCalls = new AtomicInteger();
        SqlRunner runner = new SqlRunner() {
            @Override public QueryResult execute(
                    Connection connection, String sql, String schema, SqlExecutionOptions options) {
                runnerCalls.incrementAndGet();
                return QueryResult.update(1, 1);
            }
            @Override public List<ScriptOutcome> executeScript(
                    Connection connection, String script, String schema,
                    SqlExecutionOptions options, ScriptErrorPolicy policy) {
                runnerCalls.incrementAndGet();
                return List.of(new ScriptOutcome(1, script, QueryResult.update(1, 1)));
            }
            @Override public QueryResult explain(
                    Connection connection, String sql, String schema, boolean analyze,
                    SqlExecutionOptions options) {
                runnerCalls.incrementAndGet();
                return QueryResult.update(1, 1);
            }
        };
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) ->
                        defaultValue(method.getReturnType()));
        JdbcEditorSession session = new JdbcEditorSession(
                "target", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 17),
                () -> connection, runner, parent::cancel);

        JdbcEditorSession.ExecutionBatch batch;
        try (SchemaDeploymentControl.Registration ignored = parent.register(session::cancel)) {
            batch = session.executeScript(
                    "CREATE TABLE must_not_start(id int)", "actual", 0, null, false,
                    parent::cancellationRequested);
        } finally {
            session.closeStrict();
        }

        assertEquals(0, runnerCalls.get());
        assertEquals(QueryResult.FailureKind.CANCELLED,
                batch.outcomes().getFirst().result().failureKind);
    }

    @Test
    void cancellationAtFreshReadCompletionWinsForEmptyPlan() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        BlockingRunner runner = new BlockingRunner();
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        SchemaSnapshot expected = snapshot();
        CredentialCipher cipher = new CredentialCipher();
        SchemaDiffCapability capability = capabilityCancellingBeforeReturn(expected, control);
        DatabaseProvider provider = provider(factory, runner, capability);
        ConnectionManager manager = new ConnectionManager(cipher, type -> provider);
        SchemaDiffRequest request = new SchemaDiffRequest(config(cipher, "source"), name("desired"),
                config(cipher, "target"), name("actual"));

        SchemaDeploymentResult result = new SchemaDeploymentService(manager).deploy(
                request, expected, List.of(), control).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(0, runner.calls.get());
        assertEquals(1, factory.closes.get());
    }

    @Test
    void cancellingReturnedFuturePropagatesToControlAndClosesCurrentSession() throws Exception {
        Fixture fixture = new Fixture();
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        var future = fixture.service.deploy(fixture.request, fixture.expected, plan(), control)
                .toCompletableFuture();
        assertTrue(fixture.runner.started.await(2, TimeUnit.SECONDS));

        assertTrue(future.cancel(true));

        assertTrue(future.isCancelled());
        assertTrue(control.cancellationRequested());
        assertTrue(fixture.runner.cancelled.await(1, TimeUnit.SECONDS));
        assertTrue(fixture.factory.sessionClosed.await(2, TimeUnit.SECONDS));
        assertEquals(1, fixture.runner.calls.get());
        assertEquals(1, fixture.runner.cancelCalls.get());
    }

    @Test
    void cancellationDuringFinalStrictCleanupWinsBeforeSettlement() throws Exception {
        RecordingFactory factory = new RecordingFactory(true);
        ImmediateRunner runner = new ImmediateRunner();
        SchemaSnapshot expected = snapshot();
        CredentialCipher cipher = new CredentialCipher();
        DatabaseProvider provider = provider(factory, runner, capability(expected));
        ConnectionManager manager = new ConnectionManager(cipher, type -> provider);
        SchemaDiffRequest request = new SchemaDiffRequest(config(cipher, "source"), name("desired"),
                config(cipher, "target"), name("actual"));
        SchemaDeploymentControl control = new SchemaDeploymentControl();

        var stage = new SchemaDeploymentService(manager).deploy(
                request, expected, List.of(plan().getFirst()), control);
        assertTrue(factory.sessionCloseStarted.await(2, TimeUnit.SECONDS));

        assertTrue(control.cancel());
        factory.allowSessionClose.countDown();
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(List.of(SchemaDeploymentState.SUCCEEDED),
                result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(1, runner.calls.get());
        assertEquals(2, factory.closes.get());
    }

    private static List<RenderedStatement> plan() {
        return List.of(
                new RenderedStatement(CHANGE_A, "CREATE TABLE first_table(id int)",
                        false, Set.of(), null),
                new RenderedStatement(CHANGE_B, "CREATE TABLE never_started(id int)",
                        false, Set.of(CHANGE_A), null));
    }

    private static final class Fixture {
        private final RecordingFactory factory = new RecordingFactory();
        private final BlockingRunner runner = new BlockingRunner();
        private final SchemaSnapshot expected = snapshot();
        private final SchemaDeploymentService service;
        private final SchemaDiffRequest request;

        private Fixture() {
            CredentialCipher cipher = new CredentialCipher();
            SchemaDiffCapability capability = capability(expected);
            DatabaseProvider provider = provider(factory, runner, capability);
            ConnectionManager manager = new ConnectionManager(cipher, type -> provider);
            request = new SchemaDiffRequest(config(cipher, "source"), name("desired"),
                    config(cipher, "target"), name("actual"));
            service = new SchemaDeploymentService(manager);
        }
    }

    private static final class DriftFixture {
        private final RecordingFactory factory = new RecordingFactory();
        private final BlockingRunner runner = new BlockingRunner();
        private final SchemaSnapshot expected = snapshot();
        private final CancellingDriftCapability capability = new CancellingDriftCapability();
        private final SchemaDeploymentService service;
        private final SchemaDiffRequest request;

        private DriftFixture() {
            CredentialCipher cipher = new CredentialCipher();
            DatabaseProvider provider = provider(factory, runner, capability);
            ConnectionManager manager = new ConnectionManager(cipher, type -> provider);
            request = new SchemaDiffRequest(config(cipher, "source"), name("desired"),
                    config(cipher, "target"), name("actual"));
            service = new SchemaDeploymentService(manager);
        }
    }

    private static final class CancellingDriftCapability implements SchemaDiffCapability {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public SchemaSnapshotReader snapshotReader(Connection connection) {
            return (connectionId, schema, options) -> {
                Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "cancel" -> {
                                cancelCalls.incrementAndGet();
                                cancelled.countDown();
                                yield null;
                            }
                            case "setQueryTimeout", "close" -> null;
                            default -> defaultValue(method.getReturnType());
                        });
                SqlExecutionControl.Activation activation =
                        options.control().activate(statement, options.queryTimeoutSeconds());
                try {
                    started.countDown();
                    if (!cancelled.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("Fresh read cancellation was not delivered");
                    }
                    throw new SQLException("driver-fresh-cancel-secret");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Fresh read interrupted");
                } finally {
                    options.control().release(activation);
                }
            };
        }

        @Override public SchemaChangeRenderer changeRenderer() { return (change, context) -> List.of(); }
        @Override public Set<ObjectType> supportedObjectTypes() { return Set.of(ObjectType.TABLE); }
    }

    private static SchemaSnapshot snapshot() {
        return new SchemaSnapshot(DbType.POSTGRESQL, "target", name("actual"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(), "expected");
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static ConnConfig config(CredentialCipher cipher, String id) {
        return new ConnConfig(id, id, DbType.POSTGRESQL, id + "-host", 5432,
                "database", "user", cipher.encrypt("credential-secret"), Map.of(
                "environment", "TEST", "queryTimeoutSeconds", "17"));
    }

    private static SchemaDiffCapability capability(SchemaSnapshot snapshot) {
        return new SchemaDiffCapability() {
            @Override public SchemaSnapshotReader snapshotReader(Connection connection) {
                return (connectionId, schema, options) -> snapshot;
            }
            @Override public SchemaChangeRenderer changeRenderer() { return (change, context) -> List.of(); }
            @Override public Set<ObjectType> supportedObjectTypes() { return Set.of(ObjectType.TABLE); }
        };
    }

    private static SchemaDiffCapability capabilityCancellingBeforeReturn(
            SchemaSnapshot snapshot, SchemaDeploymentControl control) {
        return new SchemaDiffCapability() {
            @Override public SchemaSnapshotReader snapshotReader(Connection connection) {
                return (connectionId, schema, options) -> {
                    control.cancel();
                    return snapshot;
                };
            }
            @Override public SchemaChangeRenderer changeRenderer() { return (change, context) -> List.of(); }
            @Override public Set<ObjectType> supportedObjectTypes() { return Set.of(ObjectType.TABLE); }
        };
    }

    private static DatabaseProvider provider(
            RecordingFactory factory, SqlRunner runner, SchemaDiffCapability capability) {
        return (DatabaseProvider) Proxy.newProxyInstance(
                SchemaDeploymentCancellationTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> DbType.POSTGRESQL;
                    case "connectionFactory" -> factory;
                    case "sqlRunner" -> runner;
                    case "schemaDiffCapability" -> Optional.of(capability);
                    default -> null;
                });
    }

    private static final class RecordingFactory implements ConnectionFactory {
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch sessionClosed = new CountDownLatch(1);
        private final CountDownLatch sessionCloseStarted = new CountDownLatch(1);
        private final CountDownLatch allowSessionClose = new CountDownLatch(1);
        private final boolean blockSessionClose;
        private volatile SchemaDeploymentControl cancelOnSecondOpen;

        private RecordingFactory() {
            this(false);
        }

        private RecordingFactory(boolean blockSessionClose) {
            this.blockSessionClose = blockSessionClose;
        }

        @Override public void ensureDriverLoaded() { }
        @Override public Connection open(ConnConfig config) {
            int ordinal = opens.incrementAndGet();
            if (ordinal == 2 && cancelOnSecondOpen != null) cancelOnSecondOpen.cancel();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> {
                            if (ordinal == 2 && blockSessionClose) {
                                sessionCloseStarted.countDown();
                                try {
                                    if (!allowSessionClose.await(2, TimeUnit.SECONDS)) {
                                        throw new SQLException("session close was not released");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new SQLException("session close interrupted");
                                }
                            }
                            if (closes.incrementAndGet() >= 2) sessionClosed.countDown();
                            yield null;
                        }
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }
        @Override public String test(ConnConfig config) { return null; }
    }

    private static final class BlockingRunner implements SqlRunner {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }

        @Override
        public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            calls.incrementAndGet();
            Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "cancel" -> {
                            cancelCalls.incrementAndGet();
                            cancelled.countDown();
                            yield null;
                        }
                        case "setQueryTimeout", "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
            SqlExecutionControl.Activation activation;
            try {
                activation = options.control().activate(statement, options.queryTimeoutSeconds());
                started.countDown();
                assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            } catch (Exception failure) {
                return List.of(new ScriptOutcome(1, script,
                        QueryResult.error("fixed fake failure", 1)));
            } finally {
                options.control().release(statement);
            }
            options.control().release(activation);
            return List.of(new ScriptOutcome(1, script, QueryResult.update(1, 1)));
        }

        @Override public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            return QueryResult.update(1, 1);
        }
    }

    private static final class ImmediateRunner implements SqlRunner {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            calls.incrementAndGet();
            return QueryResult.update(1, 1);
        }

        @Override public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            calls.incrementAndGet();
            return List.of(new ScriptOutcome(1, script, QueryResult.update(1, 1)));
        }

        @Override public QueryResult explain(
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
