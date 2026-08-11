package com.datacube.fx;

import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.service.SchemaDeploymentControl;
import com.datacube.service.SchemaDeploymentResult;
import com.datacube.service.SchemaDeploymentState;
import com.datacube.service.SchemaDeploymentStepResult;
import com.datacube.service.SchemaDiffRequest;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffViewModelTest {
    private static final String SAFE_ID = "chg:" + "a".repeat(64);
    private static final String DESTRUCTIVE_ID = "chg:" + "b".repeat(64);

    @Test
    void transitionsLinearlyFromIdleThroughCompareAndDeploymentCompletion() throws Exception {
        CompletableFuture<SchemaDiffResult> compared = new CompletableFuture<>();
        CompletableFuture<SchemaDeploymentResult> deployed = new CompletableFuture<>();
        AtomicBoolean compareVirtual = new AtomicBoolean();
        AtomicBoolean deployVirtual = new AtomicBoolean();
        SchemaDiffViewModel viewModel = viewModel(
                (request, control) -> {
                    compareVirtual.set(Thread.currentThread().isVirtual());
                    return compared;
                },
                (request, expected, statements, control) -> {
                    deployVirtual.set(Thread.currentThread().isVirtual());
                    return deployed;
                }, safePlan(false), false);
        try {
            assertEquals(SchemaDiffViewModel.State.IDLE, viewModel.snapshot().state());

            assertTrue(viewModel.compare(request(DbType.POSTGRESQL, false)));
            assertEquals(SchemaDiffViewModel.State.LOADING, viewModel.snapshot().state());
            compared.complete(diff(DbType.POSTGRESQL, true));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            assertTrue(viewModel.snapshot().deployEnabled());

            SchemaDiffViewModel.Confirmation confirmation =
                    viewModel.confirmationRequest().orElseThrow();
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, null)));
            assertEquals(SchemaDiffViewModel.State.DEPLOYING, viewModel.snapshot().state());
            deployed.complete(new SchemaDeploymentResult(
                    SchemaDeploymentState.SUCCEEDED, List.of(), confirmation.planDigest()));
            awaitState(viewModel, SchemaDiffViewModel.State.COMPLETED);

            assertTrue(compareVirtual.get());
            assertTrue(deployVirtual.get());
            assertFalse(viewModel.snapshot().deployEnabled());
        } finally {
            viewModel.closeResources();
        }
    }

    @Test
    void cancellationAndCloseRejectLateCompareCompletionWithoutRevivingActions() throws Exception {
        CompletableFuture<SchemaDiffResult> compared = new CompletableFuture<>();
        AtomicReference<SchemaDeploymentControl> control = new AtomicReference<>();
        SchemaDiffViewModel viewModel = viewModel(
                (request, candidate) -> {
                    control.set(candidate);
                    return compared;
                }, neverDeploy(), safePlan(false), false);
        try {
            viewModel.compare(request(DbType.POSTGRESQL, false));
            await(() -> control.get() != null);

            assertTrue(viewModel.cancel());
            assertEquals(SchemaDiffViewModel.State.CANCELLING, viewModel.snapshot().state());
            await(() -> control.get().cancellationRequested());
            compared.complete(diff(DbType.POSTGRESQL, true));
            awaitState(viewModel, SchemaDiffViewModel.State.IDLE);
            assertFalse(viewModel.snapshot().deployEnabled());
        } finally {
            viewModel.closeResources();
        }

        CompletableFuture<SchemaDiffResult> late = new CompletableFuture<>();
        SchemaDiffViewModel closing = viewModel(
                (request, candidate) -> late, neverDeploy(), safePlan(false), false);
        closing.compare(request(DbType.POSTGRESQL, false));
        Thread closer = Thread.startVirtualThread(closing::closeResources);
        await(() -> closing.snapshot().closed());
        late.complete(diff(DbType.POSTGRESQL, true));
        closer.join();
        assertTrue(closing.snapshot().closed());
        assertEquals(SchemaDiffViewModel.DeployBlockReason.CLOSED,
                closing.snapshot().deployBlockReason());
        assertFalse(closing.snapshot().deployEnabled());
    }

    @Test
    void fixedFailureIsRedactedAndCompareCanRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SchemaDiffViewModel viewModel = viewModel(
                (request, control) -> attempts.getAndIncrement() == 0
                        ? CompletableFuture.failedFuture(
                                new IllegalStateException("jdbc:secret://user:password@host"))
                        : CompletableFuture.completedFuture(diff(DbType.POSTGRESQL, true)),
                neverDeploy(), safePlan(false), false);
        try {
            assertTrue(viewModel.compare(request(DbType.POSTGRESQL, false)));
            awaitState(viewModel, SchemaDiffViewModel.State.FAILED);
            assertEquals("Schema 对比失败，请重试", viewModel.snapshot().message());
            assertFalse(viewModel.snapshot().toString().contains("password"));

            assertTrue(viewModel.compare(request(DbType.POSTGRESQL, false)));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
        } finally {
            viewModel.closeResources();
        }
    }

    @Test
    void deployReasonsCoverIncompleteNoSelectionUnsupportedActiveDriftAndClosed() throws Exception {
        SchemaDiffViewModel incomplete = viewModel(
                completedCompare(DbType.POSTGRESQL, false), neverDeploy(), safePlan(false), false);
        incomplete.compare(request(DbType.POSTGRESQL, false));
        awaitState(incomplete, SchemaDiffViewModel.State.READY);
        assertEquals(SchemaDiffViewModel.DeployBlockReason.INCOMPLETE_SNAPSHOT,
                incomplete.snapshot().deployBlockReason());
        incomplete.closeResources();

        SchemaDiffViewModel empty = viewModel(
                completedCompare(DbType.POSTGRESQL, true), neverDeploy(), emptyPlan(), false);
        empty.compare(request(DbType.POSTGRESQL, false));
        awaitState(empty, SchemaDiffViewModel.State.READY);
        assertEquals(SchemaDiffViewModel.DeployBlockReason.NO_EXECUTABLE_SELECTION,
                empty.snapshot().deployBlockReason());
        empty.closeResources();

        SchemaDiffViewModel unsupported = viewModel(
                completedCompare(DbType.POSTGRESQL, true), neverDeploy(), safePlan(false), true);
        unsupported.compare(request(DbType.POSTGRESQL, false));
        awaitState(unsupported, SchemaDiffViewModel.State.READY);
        assertEquals(SchemaDiffViewModel.DeployBlockReason.MANUAL_OR_UNSUPPORTED,
                unsupported.snapshot().deployBlockReason());
        unsupported.closeResources();

        CompletableFuture<SchemaDiffResult> activeCompare = new CompletableFuture<>();
        CompletableFuture<SchemaDeploymentResult> drift = new CompletableFuture<>();
        SchemaDiffViewModel active = viewModel(
                (request, control) -> activeCompare,
                (request, expected, statements, control) -> drift,
                safePlan(false), false);
        active.compare(request(DbType.POSTGRESQL, false));
        assertEquals(SchemaDiffViewModel.DeployBlockReason.ACTIVE_WORK,
                active.snapshot().deployBlockReason());
        activeCompare.complete(diff(DbType.POSTGRESQL, true));
        awaitState(active, SchemaDiffViewModel.State.READY);
        SchemaDiffViewModel.Confirmation confirmation = active.confirmationRequest().orElseThrow();
        active.deploy(new SchemaDiffViewModel.Approval(confirmation, true, null));
        drift.complete(new SchemaDeploymentResult(
                SchemaDeploymentState.BLOCKED_DRIFT, List.of(), confirmation.planDigest()));
        awaitState(active, SchemaDiffViewModel.State.DRIFTED);
        assertEquals(SchemaDiffViewModel.DeployBlockReason.DRIFT,
                active.snapshot().deployBlockReason());
        active.closeResources();
        assertEquals(SchemaDiffViewModel.DeployBlockReason.CLOSED,
                active.snapshot().deployBlockReason());
    }

    @Test
    void destructiveProductionOracleRequiresBothConfirmationsAndExactCurrentSchemaKey()
            throws Exception {
        AtomicInteger deployCalls = new AtomicInteger();
        AtomicReference<SchemaDeploymentControl> deliveredControl = new AtomicReference<>();
        SchemaDiffViewModel viewModel = viewModel(
                completedCompare(DbType.ORACLE, true),
                (request, expected, statements, control) -> {
                    deployCalls.incrementAndGet();
                    deliveredControl.set(control);
                    return CompletableFuture.completedFuture(new SchemaDeploymentResult(
                            SchemaDeploymentState.SUCCEEDED, List.of(),
                            com.datacube.service.SchemaDeploymentService.confirmationToken(statements)));
                }, destructivePlan(), false);
        try {
            viewModel.compare(request(DbType.ORACLE, true));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            assertFalse(viewModel.setSelected(DESTRUCTIVE_ID, true));
            assertTrue(viewModel.requiresDestructiveConfirmation(DESTRUCTIVE_ID, true));
            assertTrue(viewModel.setSelected(DESTRUCTIVE_ID, true, true));
            SchemaDiffViewModel.Confirmation first =
                    viewModel.confirmationRequest().orElseThrow();

            assertTrue(first.production());
            assertTrue(first.oracleImplicitCommitWarning());
            assertTrue(first.destructive());
            assertEquals("TARGET_SCHEMA", first.targetSchemaComparisonKey());
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(first, false, null)));
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    first, true, "target_schema")));
            assertEquals(0, deployCalls.get());

            assertTrue(viewModel.setSelected(DESTRUCTIVE_ID, false));
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    first, true, "TARGET_SCHEMA")), "selection version invalidates old approval");
            assertTrue(viewModel.setSelected(DESTRUCTIVE_ID, true));
            SchemaDiffViewModel.Confirmation current =
                    viewModel.confirmationRequest().orElseThrow();
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    current, true, "TARGET_SCHEMA")));
            awaitState(viewModel, SchemaDiffViewModel.State.COMPLETED);

            assertEquals(1, deployCalls.get());
            assertEquals(current.planDigest(), confirmationToken(deliveredControl.get()));
        } finally {
            viewModel.closeResources();
        }
    }

    @Test
    void destructiveConfirmationComesFromSelectedDifferenceNotOnlyRendererMetadata()
            throws Exception {
        ExecutorService scope = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("schema-diff-difference-risk-test-", 0).factory());
        SchemaChangePlan plan = destructivePlan();
        SchemaDiffViewModel viewModel = new SchemaDiffViewModel(
                completedCompare(DbType.ORACLE, true), neverDeploy(), ignored -> plan,
                new SchemaChangePlanner(),
                (change, context) -> List.of(new RenderedStatement(
                        change.id(), "DROP VIEW old_view", false, Set.of(), null)),
                scope, Runnable::run, () -> {});
        try {
            viewModel.compare(request(DbType.ORACLE, false));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            assertTrue(viewModel.setSelected(DESTRUCTIVE_ID, true, true));

            assertTrue(viewModel.confirmationRequest().orElseThrow().destructive());
        } finally {
            viewModel.closeResources();
        }
    }

    @Test
    void exportKeepsRenderedOrderWithoutWrapperAndDoesNotInvalidateApproval() throws Exception {
        AtomicInteger deployCalls = new AtomicInteger();
        SchemaDiffViewModel viewModel = viewModel(
                completedCompare(DbType.POSTGRESQL, true),
                (request, expected, statements, control) -> {
                    deployCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(new SchemaDeploymentResult(
                            SchemaDeploymentState.SUCCEEDED, List.of(),
                            com.datacube.service.SchemaDeploymentService.confirmationToken(statements)));
                }, safePlan(true), false);
        try {
            viewModel.compare(request(DbType.POSTGRESQL, false));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            SchemaDiffViewModel.Confirmation before =
                    viewModel.confirmationRequest().orElseThrow();

            String script = viewModel.exportSelectedScript();

            assertEquals("CREATE TABLE first_table(id int)\n\n"
                    + "ALTER TABLE first_table ADD name text", script);
            assertFalse(script.contains("BEGIN"));
            assertFalse(script.contains("COMMIT"));
            assertEquals(before, viewModel.confirmationRequest().orElseThrow());
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(before, true, null)));
            awaitState(viewModel, SchemaDiffViewModel.State.COMPLETED);
            assertEquals(1, deployCalls.get());
        } finally {
            viewModel.closeResources();
        }
    }

    @Test
    void lateExportCompletionIsStaleAfterANewCompareStarts() throws Exception {
        GateExecutor scope = new GateExecutor();
        SchemaChangePlan plan = safePlan(false);
        SchemaDiffViewModel viewModel = new SchemaDiffViewModel(
                completedCompare(DbType.POSTGRESQL, true), neverDeploy(), ignored -> plan,
                new SchemaChangePlanner(), (change, context) -> List.of(new RenderedStatement(
                        change.id(), "CREATE TABLE safe_table(id int)", false, Set.of(), null)),
                scope, Runnable::run, () -> {});
        Path target = Files.createTempFile("schema-diff-export-generation-", ".sql");
        try {
            viewModel.compare(request(DbType.POSTGRESQL, false));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            scope.blockNext();

            CompletionStage<SchemaDiffViewModel.ExportResult> oldExport =
                    viewModel.exportSelectedScript(target);
            assertTrue(viewModel.compare(request(DbType.POSTGRESQL, false)));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            scope.releaseBlocked();

            SchemaDiffViewModel.ExportResult completed =
                    oldExport.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(completed.written());
            assertFalse(viewModel.isCurrentExport(completed));
        } finally {
            scope.releaseBlocked();
            viewModel.closeResources();
            Files.deleteIfExists(target);
        }
    }

    @Test
    void lateExportCompletionIsStaleAfterDeploymentStarts() throws Exception {
        GateExecutor scope = new GateExecutor();
        SchemaChangePlan plan = safePlan(false);
        CompletableFuture<SchemaDeploymentResult> deployed = new CompletableFuture<>();
        SchemaDiffViewModel viewModel = new SchemaDiffViewModel(
                completedCompare(DbType.POSTGRESQL, true),
                (request, expected, statements, control) -> deployed,
                ignored -> plan, new SchemaChangePlanner(),
                (change, context) -> List.of(new RenderedStatement(
                        change.id(), "CREATE TABLE safe_table(id int)", false, Set.of(), null)),
                scope, Runnable::run, () -> {});
        Path target = Files.createTempFile("schema-diff-export-deploy-", ".sql");
        try {
            viewModel.compare(request(DbType.POSTGRESQL, false));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            scope.blockNext();
            CompletionStage<SchemaDiffViewModel.ExportResult> oldExport =
                    viewModel.exportSelectedScript(target);

            SchemaDiffViewModel.Confirmation confirmation =
                    viewModel.confirmationRequest().orElseThrow();
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, null)));
            scope.releaseBlocked();
            SchemaDiffViewModel.ExportResult completed =
                    oldExport.toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertFalse(viewModel.isCurrentExport(completed));
            deployed.complete(new SchemaDeploymentResult(
                    SchemaDeploymentState.SUCCEEDED, List.of(), confirmation.planDigest()));
            awaitState(viewModel, SchemaDiffViewModel.State.COMPLETED);
        } finally {
            deployed.complete(new SchemaDeploymentResult(
                    SchemaDeploymentState.CANCELLED, List.of(), "cancelled"));
            scope.releaseBlocked();
            viewModel.closeResources();
            Files.deleteIfExists(target);
        }
    }

    @Test
    void cancelledUnknownFailedAndDriftedDeploymentsRetainReadOnlyReviewUntilFreshCompare()
            throws Exception {
        for (SchemaDeploymentState terminal : List.of(
                SchemaDeploymentState.CANCELLED,
                SchemaDeploymentState.UNKNOWN_AFTER_CANCEL,
                SchemaDeploymentState.FAILED_SQL,
                SchemaDeploymentState.BLOCKED_DRIFT)) {
            CompletableFuture<SchemaDeploymentResult> deployed = new CompletableFuture<>();
            SchemaDiffViewModel viewModel = viewModel(
                    completedCompare(DbType.POSTGRESQL, true),
                    (request, expected, statements, control) -> deployed,
                    safePlan(false), false);
            try {
                viewModel.compare(request(DbType.POSTGRESQL, false));
                awaitState(viewModel, SchemaDiffViewModel.State.READY);
                SchemaDiffViewModel.Confirmation confirmation =
                        viewModel.confirmationRequest().orElseThrow();
                SchemaDiffResult presentedDiff = viewModel.currentDiff().orElseThrow();
                List<RenderedStatement> presentedSql = viewModel.renderedStatements();
                assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                        confirmation, true, null)));
                deployed.complete(new SchemaDeploymentResult(
                        terminal,
                        List.of(new SchemaDeploymentStepResult(1, SAFE_ID, terminal)),
                        confirmation.planDigest()));

                awaitState(viewModel, terminal == SchemaDeploymentState.BLOCKED_DRIFT
                        ? SchemaDiffViewModel.State.DRIFTED : SchemaDiffViewModel.State.FAILED);
                assertEquals(List.of(new SchemaDiffViewModel.DeploymentStepView(
                                1, SAFE_ID, "TABLE · table", terminal)),
                        viewModel.deploymentSteps());
                assertEquals(presentedDiff, viewModel.currentDiff().orElseThrow(), terminal.name());
                assertEquals(presentedSql, viewModel.renderedStatements(), terminal.name());
                assertTrue(viewModel.selectionModel().orElseThrow().entry(SAFE_ID).selected(),
                        terminal.name());
                assertTrue(viewModel.selectionModel().orElseThrow().confirmationToken().isEmpty(),
                        terminal.name());
                assertTrue(viewModel.confirmationRequest().isEmpty(), terminal.name());
                assertFalse(viewModel.snapshot().deployEnabled(), terminal.name());
                assertTrue(viewModel.snapshot().selectionReadOnly(), terminal.name());
                assertFalse(viewModel.setSelected(SAFE_ID, false), terminal.name());
                assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                        confirmation, true, null)), terminal.name());

                assertTrue(viewModel.compare(request(DbType.POSTGRESQL, false)), terminal.name());
                awaitState(viewModel, SchemaDiffViewModel.State.READY);
                assertFalse(viewModel.snapshot().selectionReadOnly(), terminal.name());
                assertTrue(viewModel.snapshot().deployEnabled(), terminal.name());
            } finally {
                viewModel.closeResources();
            }
        }
    }

    @Test
    void exceptionallyCancelledDeploymentRetainsReadOnlyReviewContext() throws Exception {
        CompletableFuture<SchemaDeploymentResult> deployed = new CompletableFuture<>();
        AtomicReference<SchemaDeploymentControl> control = new AtomicReference<>();
        SchemaDiffViewModel viewModel = viewModel(
                completedCompare(DbType.POSTGRESQL, true),
                (request, expected, statements, candidate) -> {
                    control.set(candidate);
                    return deployed;
                }, safePlan(false), false);
        try {
            viewModel.compare(request(DbType.POSTGRESQL, false));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            SchemaDiffViewModel.Confirmation confirmation =
                    viewModel.confirmationRequest().orElseThrow();
            SchemaDiffResult presentedDiff = viewModel.currentDiff().orElseThrow();
            List<RenderedStatement> presentedSql = viewModel.renderedStatements();
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, null)));
            await(() -> control.get() != null);
            assertTrue(viewModel.cancel());
            await(() -> control.get().cancellationRequested());
            deployed.completeExceptionally(new CancellationException("provider-secret"));

            awaitState(viewModel, SchemaDiffViewModel.State.FAILED);
            assertEquals("当前任务已取消", viewModel.snapshot().message());
            assertEquals(presentedDiff, viewModel.currentDiff().orElseThrow());
            assertEquals(presentedSql, viewModel.renderedStatements());
            assertTrue(viewModel.selectionModel().orElseThrow().entry(SAFE_ID).selected());
            assertTrue(viewModel.selectionModel().orElseThrow().confirmationToken().isEmpty());
            assertTrue(viewModel.confirmationRequest().isEmpty());
            assertFalse(viewModel.snapshot().deployEnabled());
            assertTrue(viewModel.snapshot().selectionReadOnly());
            assertFalse(viewModel.setSelected(SAFE_ID, false));
        } finally {
            deployed.completeExceptionally(new CancellationException());
            viewModel.closeResources();
        }
    }

    private static SchemaDiffViewModel viewModel(
            SchemaDiffViewModel.CompareGateway compare,
            SchemaDiffViewModel.DeployGateway deploy,
            SchemaChangePlan plan,
            boolean rendererFails) {
        ExecutorService scope = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("schema-diff-view-model-test-", 0).factory());
        return new SchemaDiffViewModel(compare, deploy, ignored -> plan,
                new SchemaChangePlanner(),
                (change, context) -> {
                    if (rendererFails) throw new IllegalArgumentException("raw-ddl-secret");
                    if (change.id().equals(SAFE_ID) && plan.changes().size() > 1) {
                        return List.of(
                                new RenderedStatement(SAFE_ID,
                                        "CREATE TABLE first_table(id int)", false, Set.of(), null),
                                new RenderedStatement(SAFE_ID,
                                        "ALTER TABLE first_table ADD name text", false, Set.of(), null));
                    }
                    return List.of(new RenderedStatement(change.id(),
                            change.automation() == AutomationLevel.DESTRUCTIVE_OPT_IN
                                    ? "DROP VIEW old_view" : "CREATE TABLE safe_table(id int)",
                            change.automation() == AutomationLevel.DESTRUCTIVE_OPT_IN,
                            change.dependencyChangeIds(),
                            change.automation() == AutomationLevel.DESTRUCTIVE_OPT_IN
                                    ? "Destructive change requires approval" : null));
                }, scope, Runnable::run, () -> {});
    }

    private static SchemaDiffViewModel.CompareGateway completedCompare(DbType type, boolean complete) {
        return (request, control) -> CompletableFuture.completedFuture(diff(type, complete));
    }

    private static SchemaDiffViewModel.DeployGateway neverDeploy() {
        return (request, expected, statements, control) ->
                CompletableFuture.failedFuture(new AssertionError("deployment was not expected"));
    }

    private static SchemaChangePlan safePlan(boolean twoStatements) {
        SchemaDiffResult diff = diff(DbType.POSTGRESQL, true);
        SchemaChange safe = change(SAFE_ID, ChangeKind.CREATE, ObjectType.TABLE,
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, true);
        if (!twoStatements) {
            return new SchemaChangePlan(diff, List.of(safe), Set.of(SAFE_ID), Set.of(), "initial");
        }
        SchemaChange ignoredSecondMarker = change("chg:" + "c".repeat(64), ChangeKind.MANUAL,
                ObjectType.FUNCTION, RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY, false);
        return new SchemaChangePlan(diff, List.of(safe, ignoredSecondMarker),
                Set.of(SAFE_ID), Set.of(), "initial");
    }

    private static SchemaChangePlan destructivePlan() {
        SchemaDiffResult diff = diff(DbType.ORACLE, true);
        SchemaChange destructive = change(DESTRUCTIVE_ID, ChangeKind.DROP, ObjectType.VIEW,
                RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN, false);
        return new SchemaChangePlan(diff, List.of(destructive), Set.of(), Set.of(), "initial");
    }

    private static SchemaChangePlan emptyPlan() {
        return new SchemaChangePlan(diff(DbType.POSTGRESQL, true),
                List.of(), Set.of(), Set.of(), "initial");
    }

    private static SchemaChange change(
            String id, ChangeKind kind, ObjectType type, RiskLevel risk,
            AutomationLevel automation, boolean selected) {
        String name = type.name().toLowerCase();
        return new SchemaChange(id, kind,
                new ObjectKey(type, new QualifiedName(name, name, false), ""),
                null, null, null, risk, automation, selected, Set.of(), "fixed explanation");
    }

    private static SchemaDiffRequest request(DbType type, boolean production) {
        Map<String, String> props = production ? Map.of("environment", "PRODUCTION") : Map.of();
        return new SchemaDiffRequest(
                config("source", type, props), schema(type, "SOURCE_SCHEMA"),
                config("target", type, props), schema(type, "TARGET_SCHEMA"));
    }

    private static ConnConfig config(String id, DbType type, Map<String, String> props) {
        return new ConnConfig(id, id + "-name", type, "secret-host", 1234,
                "secret-database", "secret-user", "secret-password", props);
    }

    private static SchemaDiffResult diff(DbType type, boolean complete) {
        SchemaSnapshot source = snapshot(type, "source", "SOURCE_SCHEMA", complete, "source-fp");
        SchemaSnapshot target = snapshot(type, "target", "TARGET_SCHEMA", complete, "target-fp");
        return new SchemaDiffResult(source, target, List.of(), List.of());
    }

    private static SchemaSnapshot snapshot(
            DbType type, String connectionId, String schema,
            boolean complete, String fingerprint) {
        TreeMap<ObjectType, String> unavailable = new TreeMap<>();
        if (!complete) unavailable.put(ObjectType.TABLE, SnapshotCompleteness.METADATA_UNAVAILABLE);
        return new SchemaSnapshot(type, connectionId, schema(type, schema), Instant.EPOCH,
                new SnapshotCompleteness(complete, unavailable), new TreeMap<>(), fingerprint);
    }

    private static QualifiedName schema(DbType type, String value) {
        return new QualifiedName(value,
                type == DbType.ORACLE ? value.toUpperCase() : value.toLowerCase(), false);
    }

    private static String confirmationToken(SchemaDeploymentControl control) throws Exception {
        assertNotNull(control);
        Method method = SchemaDeploymentControl.class.getDeclaredMethod("confirmationToken");
        method.setAccessible(true);
        return (String) method.invoke(control);
    }

    private static void awaitState(
            SchemaDiffViewModel viewModel, SchemaDiffViewModel.State state) throws Exception {
        await(() -> viewModel.snapshot().state() == state);
        assertEquals(state, viewModel.snapshot().state());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(condition.getAsBoolean(), "condition did not settle");
    }

    private static final class GateExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("schema-diff-export-gate-", 0).factory());
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final CountDownLatch release = new CountDownLatch(1);

        void blockNext() {
            blockNext.set(true);
        }

        void releaseBlocked() {
            release.countDown();
        }

        @Override
        public void execute(Runnable command) {
            boolean blocked = blockNext.compareAndSet(true, false);
            delegate.execute(() -> {
                if (blocked) {
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                command.run();
            });
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
