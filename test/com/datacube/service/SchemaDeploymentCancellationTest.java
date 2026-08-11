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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void successfulExecutionBatchRemainsAuthoritativeAndCancellationPreventsNext()
            throws Exception {
        Fixture fixture = new Fixture();
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        var stage = fixture.service.deploy(fixture.request, fixture.expected, plan(), control);
        assertTrue(fixture.runner.started.await(2, TimeUnit.SECONDS));

        assertTrue(control.cancel());
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(List.of(
                        SchemaDeploymentState.SUCCEEDED,
                        SchemaDeploymentState.CANCELLED),
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
    void freshReadCancelStillClosesPhysicalConnectionWhenStatementCancelThrows() throws Exception {
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        SchemaSnapshot expected = snapshot();
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        AtomicBoolean closeObservedInsideReader = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger statementCancels = new AtomicInteger();
        Connection connection = connection((proxy, method, args) -> switch (method.getName()) {
            case "close" -> {
                closes.incrementAndGet();
                connectionClosed.countDown();
                yield null;
            }
            case "isClosed" -> false;
            default -> defaultValue(method.getReturnType());
        });
        ConnectionFactory factory = factory(connection);
        SchemaDiffCapability capability = capability((opened, options) -> {
            Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "cancel" -> {
                            statementCancels.incrementAndGet();
                            throw new IllegalStateException("driver-cancel-secret");
                        }
                        case "setQueryTimeout", "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
            SqlExecutionControl.Activation activation =
                    options.control().activate(statement, options.queryTimeoutSeconds());
            try {
                readerStarted.countDown();
                try {
                    closeObservedInsideReader.set(connectionClosed.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("fresh read interrupted");
                }
                throw new SQLException("fresh read stopped");
            } finally {
                options.control().release(activation);
            }
        });
        Harness harness = harness(factory, new ImmediateRunner(), capability, DbType.POSTGRESQL);

        var stage = harness.service.deploy(harness.request, expected, plan(), control);
        assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
        control.cancel();
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertTrue(closeObservedInsideReader.get());
        assertEquals(1, statementCancels.get());
        assertEquals(1, closes.get());
    }

    @Test
    void cancellationWhileFreshConnectionOpenIsBlockedIsConsumedAfterLatePublication()
            throws Exception {
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        SchemaSnapshot expected = snapshot();
        CountDownLatch openStarted = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger readerCalls = new AtomicInteger();
        Connection connection = connection((proxy, method, args) -> switch (method.getName()) {
            case "close" -> { closes.incrementAndGet(); yield null; }
            case "isClosed" -> false;
            default -> defaultValue(method.getReturnType());
        });
        ConnectionFactory factory = new ConnectionFactory() {
            @Override public void ensureDriverLoaded() { }
            @Override public Connection open(ConnConfig config) throws SQLException {
                openStarted.countDown();
                try {
                    if (!releaseOpen.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("late open was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("late open interrupted");
                }
                return connection;
            }
            @Override public String test(ConnConfig config) { return null; }
        };
        SchemaDiffCapability capability = capability((opened, options) -> {
            readerCalls.incrementAndGet();
            return expected;
        });
        Harness harness = harness(factory, new ImmediateRunner(), capability, DbType.POSTGRESQL);

        var stage = harness.service.deploy(harness.request, expected, plan(), control);
        assertTrue(openStarted.await(2, TimeUnit.SECONDS));
        control.cancel();
        releaseOpen.countDown();
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(0, readerCalls.get());
        assertEquals(1, closes.get());
    }

    @Test
    void cancellationClosesFreshConnectionWhilePrepareStatementIsBlocked() throws Exception {
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        SchemaSnapshot expected = snapshot();
        CountDownLatch prepareStarted = new CountDownLatch(1);
        CountDownLatch connectionClosed = new CountDownLatch(1);
        CountDownLatch readerExited = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        Connection connection = connection((proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                prepareStarted.countDown();
                try {
                    if (!connectionClosed.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("prepare was not unblocked by close");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("prepare interrupted");
                }
                throw new SQLException("connection closed during prepare");
            }
            case "close" -> {
                closes.incrementAndGet();
                connectionClosed.countDown();
                yield null;
            }
            case "isClosed" -> false;
            default -> defaultValue(method.getReturnType());
        });
        SchemaDiffCapability capability = capability((opened, options) -> {
            try {
                opened.prepareStatement("SELECT 1");
                throw new SQLException("prepare unexpectedly returned");
            } finally {
                readerExited.countDown();
            }
        });
        Harness harness = harness(
                factory(connection), new ImmediateRunner(), capability, DbType.POSTGRESQL);

        var stage = harness.service.deploy(harness.request, expected, plan(), control);
        assertTrue(prepareStarted.await(2, TimeUnit.SECONDS));
        control.cancel();
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertTrue(readerExited.await(1, TimeUnit.SECONDS));
        assertEquals(1, closes.get());
    }

    @Test
    void freshReadSettlementWaitsForWinningPhysicalCloseAttempt() throws Exception {
        assertFreshReadSettlementWaitsForWinningCloseFailure(
                new IllegalStateException("driver close failure"));
    }

    @Test
    void freshReadSettlementStillCompletesWhenWinningPhysicalCloseThrowsError()
            throws Exception {
        assertFreshReadSettlementWaitsForWinningCloseFailure(
                new AssertionError("driver close error"));
    }

    private void assertFreshReadSettlementWaitsForWinningCloseFailure(Throwable closeFailure)
            throws Exception {
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        SchemaSnapshot expected = snapshot();
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch readerReleased = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch stageSettled = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        Connection connection = connection((proxy, method, args) -> switch (method.getName()) {
            case "close" -> {
                closeStarted.countDown();
                try {
                    if (!allowClose.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("fresh connection close was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("fresh connection close interrupted");
                }
                closes.incrementAndGet();
                throw closeFailure;
            }
            case "isClosed" -> false;
            default -> defaultValue(method.getReturnType());
        });
        SchemaDiffCapability capability = capability((opened, options) -> {
            Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "cancel" -> { readerReleased.countDown(); yield null; }
                        case "setQueryTimeout", "close" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
            SqlExecutionControl.Activation activation =
                    options.control().activate(statement, options.queryTimeoutSeconds());
            try {
                readerStarted.countDown();
                try {
                    if (!readerReleased.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("fresh reader was not cancelled");
                    }
                    if (!closeStarted.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("fresh close did not start");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("fresh reader interrupted");
                }
                throw new SQLException("fresh reader stopped");
            } finally {
                options.control().release(activation);
            }
        });
        Harness harness = harness(
                factory(connection), new ImmediateRunner(), capability, DbType.POSTGRESQL);

        var stage = harness.service.deploy(harness.request, expected, plan(), control);
        stage.whenComplete((ignored, failure) -> stageSettled.countDown());
        assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
        Thread cancelThread = Thread.ofVirtual().start(control::cancel);
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
        try {
            assertFalse(stageSettled.await(1, TimeUnit.SECONDS),
                    "deployment settled before the winning physical close attempt completed");
        } finally {
            allowClose.countDown();
        }

        cancelThread.join(2_000);
        SchemaDeploymentResult result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(SchemaDeploymentState.CANCELLED, result.state());
        assertEquals(1, closes.get());
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
    void cancellationDuringFinalStrictCleanupCannotRewriteSuccessfulBatch() throws Exception {
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

        assertEquals(SchemaDeploymentState.SUCCEEDED, result.state());
        assertEquals(List.of(SchemaDeploymentState.SUCCEEDED),
                result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(1, runner.calls.get());
        assertEquals(2, factory.closes.get());
    }

    @Test
    void returnedSqlErrorAndTimeoutRemainAuthoritativeWhenCancellationIsAlreadySet()
            throws Exception {
        for (QueryResult outcome : List.of(
                QueryResult.error("fixed sql failure", 1),
                QueryResult.timeout("fixed timeout", 1))) {
            SchemaDeploymentControl control = new SchemaDeploymentControl();
            OutcomeAfterCancelRunner runner = new OutcomeAfterCancelRunner(control, outcome);
            SchemaSnapshot expected = snapshot();
            Harness harness = harness(
                    new RecordingFactory(), runner, capability(expected), DbType.POSTGRESQL);

            SchemaDeploymentResult result = harness.service.deploy(
                    harness.request, expected, List.of(plan().getFirst()), control)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            SchemaDeploymentState expectedState = outcome.failureKind
                    == QueryResult.FailureKind.TIMEOUT
                    ? SchemaDeploymentState.TIMED_OUT
                    : SchemaDeploymentState.FAILED_SQL;
            assertEquals(expectedState, result.state());
            assertEquals(List.of(expectedState),
                    result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
            assertEquals(1, runner.calls.get());
        }
    }

    @Test
    void oracleCancelledOutcomeIsUnknownAfterCancelAndPreservesPlanEvidence()
            throws Exception {
        List<RenderedStatement> selected = List.of(new RenderedStatement(
                CHANGE_A, "CREATE OR REPLACE VIEW safe_view AS SELECT 1 FROM DUAL",
                false, Set.of(), null));
        String token = SchemaDeploymentService.confirmationToken(selected);
        SchemaDeploymentControl control = new SchemaDeploymentControl(token);
        OutcomeAfterCancelRunner runner = new OutcomeAfterCancelRunner(
                control, QueryResult.cancelled("fixed cancellation", 1));
        SchemaSnapshot expected = snapshot(DbType.ORACLE);
        Harness harness = harness(
                new RecordingFactory(), runner, capability(expected), DbType.ORACLE);

        SchemaDeploymentResult result = harness.service.deploy(
                harness.request, expected, selected, control)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.UNKNOWN_AFTER_CANCEL, result.state());
        assertEquals(List.of(SchemaDeploymentState.UNKNOWN_AFTER_CANCEL),
                result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(token, result.planDigest());
        assertEquals(List.of(SchemaDeploymentService.SAFETY_ESCALATION_WARNING),
                result.safetyWarnings());
        assertEquals(1, runner.calls.get());
    }

    @Test
    void postgresqlCancellationAfterPriorOutcomeIsUnknownAndRemainingStepsFailFast()
            throws Exception {
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        MixedCancellationRunner runner = new MixedCancellationRunner(control);
        SchemaSnapshot expected = snapshot();
        Harness harness = harness(
                new RecordingFactory(), runner, capability(expected), DbType.POSTGRESQL);
        List<RenderedStatement> selected = plan();
        String digest = SchemaDeploymentService.confirmationToken(selected);

        SchemaDeploymentResult result = harness.service.deploy(
                harness.request, expected, selected, control)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.UNKNOWN_AFTER_CANCEL, result.state());
        assertEquals(List.of(
                        SchemaDeploymentState.UNKNOWN_AFTER_CANCEL,
                        SchemaDeploymentState.SKIPPED_FAIL_FAST),
                result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(digest, result.planDigest());
        assertEquals(List.of(), result.safetyWarnings());
        assertEquals(1, runner.calls.get());
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
        return snapshot(DbType.POSTGRESQL);
    }

    private static SchemaSnapshot snapshot(DbType type) {
        return new SchemaSnapshot(type, "target", name("actual"), Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), new TreeMap<>(), "expected");
    }

    private static QualifiedName name(String value) {
        return new QualifiedName(value, value, false);
    }

    private static ConnConfig config(CredentialCipher cipher, String id) {
        return config(cipher, id, DbType.POSTGRESQL);
    }

    private static ConnConfig config(CredentialCipher cipher, String id, DbType type) {
        return new ConnConfig(id, id, type, id + "-host", type == DbType.ORACLE ? 1521 : 5432,
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

    private static SchemaDiffCapability capability(FreshReader reader) {
        return new SchemaDiffCapability() {
            @Override public SchemaSnapshotReader snapshotReader(Connection connection) {
                return (connectionId, schema, options) -> reader.read(connection, options);
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
            ConnectionFactory factory, SqlRunner runner, SchemaDiffCapability capability) {
        return provider(factory, runner, capability, DbType.POSTGRESQL);
    }

    private static DatabaseProvider provider(
            ConnectionFactory factory, SqlRunner runner,
            SchemaDiffCapability capability, DbType type) {
        return (DatabaseProvider) Proxy.newProxyInstance(
                SchemaDeploymentCancellationTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> type;
                    case "connectionFactory" -> factory;
                    case "sqlRunner" -> runner;
                    case "schemaDiffCapability" -> Optional.of(capability);
                    default -> null;
                });
    }

    private static Harness harness(
            ConnectionFactory factory, SqlRunner runner,
            SchemaDiffCapability capability, DbType type) {
        CredentialCipher cipher = new CredentialCipher();
        DatabaseProvider provider = provider(factory, runner, capability, type);
        ConnectionManager manager = new ConnectionManager(cipher, ignored -> provider);
        SchemaDiffRequest request = new SchemaDiffRequest(
                config(cipher, "source", type), name("desired"),
                config(cipher, "target", type), name("actual"));
        return new Harness(new SchemaDeploymentService(manager), request);
    }

    private static ConnectionFactory factory(Connection connection) {
        return new ConnectionFactory() {
            @Override public void ensureDriverLoaded() { }
            @Override public Connection open(ConnConfig config) { return connection; }
            @Override public String test(ConnConfig config) { return null; }
        };
    }

    private static Connection connection(java.lang.reflect.InvocationHandler handler) {
        return (Connection) Proxy.newProxyInstance(
                SchemaDeploymentCancellationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private record Harness(SchemaDeploymentService service, SchemaDiffRequest request) {
    }

    @FunctionalInterface
    private interface FreshReader {
        SchemaSnapshot read(Connection connection, SqlExecutionOptions options) throws SQLException;
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

    private static final class OutcomeAfterCancelRunner implements SqlRunner {
        private final SchemaDeploymentControl parent;
        private final QueryResult outcome;
        private final AtomicInteger calls = new AtomicInteger();

        private OutcomeAfterCancelRunner(
                SchemaDeploymentControl parent, QueryResult outcome) {
            this.parent = parent;
            this.outcome = outcome;
        }

        @Override public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            calls.incrementAndGet();
            parent.cancel();
            return outcome;
        }

        @Override public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            calls.incrementAndGet();
            parent.cancel();
            return List.of(new ScriptOutcome(1, script, outcome));
        }

        @Override public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            calls.incrementAndGet();
            parent.cancel();
            return outcome;
        }
    }

    private static final class MixedCancellationRunner implements SqlRunner {
        private final SchemaDeploymentControl parent;
        private final AtomicInteger calls = new AtomicInteger();

        private MixedCancellationRunner(SchemaDeploymentControl parent) {
            this.parent = parent;
        }

        @Override public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            calls.incrementAndGet();
            parent.cancel();
            return List.of(
                    new ScriptOutcome(1, "CREATE TABLE applied(id int)", QueryResult.update(1, 1)),
                    new ScriptOutcome(2, "CREATE TABLE uncertain(id int)",
                            QueryResult.cancelled("fixed cancellation", 1)));
        }

        @Override public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            throw new UnsupportedOperationException();
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
