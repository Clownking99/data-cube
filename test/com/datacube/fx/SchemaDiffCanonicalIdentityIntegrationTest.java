package com.datacube.fx;

import com.datacube.provider.oracle.OracleSchemaChangeRenderer;
import com.datacube.provider.oracle.OracleSchemaIdentifierNormalizer;
import com.datacube.provider.postgres.PgSchemaChangeRenderer;
import com.datacube.provider.postgres.PgSchemaIdentifierNormalizer;
import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.service.SchemaDeploymentResult;
import com.datacube.service.SchemaDeploymentState;
import com.datacube.service.SchemaDeploymentService;
import com.datacube.service.SchemaDiffRequest;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaSnapshot;
import com.datacube.spi.schemadiff.SequenceDefinition;
import com.datacube.spi.schemadiff.SnapshotCompleteness;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffCanonicalIdentityIntegrationTest {
    private static final String CHANGE_ID = "chg:" + "d".repeat(64);

    @Test
    void postgresRealCreateOrReplaceUsesServiceAdmissionForViewModelConfirmationAndToken()
            throws Exception {
        for (ObjectType objectType : List.of(
                ObjectType.VIEW, ObjectType.FUNCTION, ObjectType.PROCEDURE)) {
            assertCreateOrReplaceAdmissionFlow(DbType.POSTGRESQL, objectType,
                    PgSchemaIdentifierNormalizer::schema,
                    PgSchemaIdentifierNormalizer::object,
                    new PgSchemaChangeRenderer());
        }
    }

    @Test
    void oracleRealCreateOrReplaceUsesServiceAdmissionForViewModelConfirmationAndToken()
            throws Exception {
        for (ObjectType objectType : List.of(
                ObjectType.VIEW, ObjectType.FUNCTION, ObjectType.PROCEDURE)) {
            assertCreateOrReplaceAdmissionFlow(DbType.ORACLE, objectType,
                    OracleSchemaIdentifierNormalizer::schema,
                    OracleSchemaIdentifierNormalizer::object,
                    new OracleSchemaChangeRenderer());
        }
    }

    @Test
    void postgresSnapshotCanonicalIdentityFlowsThroughRealRendererIntoDeploymentRequest()
            throws Exception {
        assertCanonicalFlow(
                DbType.POSTGRESQL,
                PgSchemaIdentifierNormalizer::schema,
                PgSchemaIdentifierNormalizer::object,
                new PgSchemaChangeRenderer());
    }

    @Test
    void oracleSnapshotCanonicalIdentityFlowsThroughRealRendererIntoDeploymentRequest()
            throws Exception {
        assertCanonicalFlow(
                DbType.ORACLE,
                OracleSchemaIdentifierNormalizer::schema,
                OracleSchemaIdentifierNormalizer::object,
                new OracleSchemaChangeRenderer());
    }

    @Test
    void postgresDestructiveConfirmationUsesExactKeyboardSafeSnapshotDisplayToken()
            throws Exception {
        assertDestructiveConfirmationToken(
                DbType.POSTGRESQL,
                PgSchemaIdentifierNormalizer::schema,
                PgSchemaIdentifierNormalizer::object,
                new PgSchemaChangeRenderer());
    }

    @Test
    void oracleDestructiveConfirmationUsesExactKeyboardSafeSnapshotDisplayToken()
            throws Exception {
        assertDestructiveConfirmationToken(
                DbType.ORACLE,
                OracleSchemaIdentifierNormalizer::schema,
                OracleSchemaIdentifierNormalizer::object,
                new OracleSchemaChangeRenderer());
    }

    @Test
    void sameConnectionAndSameProviderCanonicalSchemaCannotBecomeReady() throws Exception {
        ConnConfig same = config("same", DbType.POSTGRESQL);
        QualifiedName canonical = PgSchemaIdentifierNormalizer.schema("SharedOwner");
        SchemaDiffResult diff = new SchemaDiffResult(
                snapshot(DbType.POSTGRESQL, same.id(), canonical, Map.of(), "source-fp"),
                snapshot(DbType.POSTGRESQL, same.id(), canonical, Map.of(), "target-fp"),
                List.of(), List.of());
        SchemaDiffViewModel viewModel = new SchemaDiffViewModel(
                (request, control) -> CompletableFuture.completedFuture(diff),
                (request, expected, statements, control) ->
                        CompletableFuture.failedFuture(new AssertionError("deploy not expected")),
                result -> new SchemaChangePlan(result, List.of(), Set.of(), Set.of(), "plan"),
                new SchemaChangePlanner(), new PgSchemaChangeRenderer(),
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("schema-diff-same-schema-test-", 0).factory()),
                Runnable::run, () -> {});
        try {
            assertTrue(viewModel.compare(new SchemaDiffRequest(
                    same, new QualifiedName("SharedOwner", "ui-source", false),
                    same, new QualifiedName("SharedOwner", "ui-target", false))));
            awaitState(viewModel, SchemaDiffViewModel.State.FAILED);
            assertTrue(viewModel.selectionModel().isEmpty());
            assertTrue(viewModel.confirmationRequest().isEmpty());
        } finally {
            viewModel.closeResources();
        }
    }

    private static void assertCanonicalFlow(
            DbType type,
            Function<String, QualifiedName> schemaNormalizer,
            ObjectNormalizer objectNormalizer,
            SchemaChangeRenderer renderer) throws Exception {
        QualifiedName sourceSchema = schemaNormalizer.apply("SourceOwner");
        QualifiedName targetSchema = schemaNormalizer.apply("TargetOwner");
        ObjectKey sequenceKey = new ObjectKey(
                ObjectType.SEQUENCE, objectNormalizer.normalize("SourceOwner", "OrderSeq"), "");
        SequenceDefinition sequence = type == DbType.ORACLE
                ? new SequenceDefinition(sequenceKey, "7", "3", "1", "999", true, 20,
                        Set.of(), Map.of("oracle.order", "ORDER", "oracle.startValueKnown", "true"))
                : new SequenceDefinition(
                        sequenceKey, "7", "3", "1", "999", true, 20, Set.of());
        SchemaChange change = new SchemaChange(
                CHANGE_ID, ChangeKind.CREATE, sequenceKey, sequence, null, null,
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");
        SchemaSnapshot sourceSnapshot = snapshot(
                type, "source", sourceSchema, Map.of(sequenceKey, sequence), "source-fp");
        SchemaSnapshot targetSnapshot = snapshot(
                type, "target", targetSchema, Map.of(), "target-fp");
        SchemaDiffResult diff = new SchemaDiffResult(
                sourceSnapshot, targetSnapshot, List.of(), List.of());
        AtomicReference<SchemaDiffRequest> deployedRequest = new AtomicReference<>();
        AtomicReference<SchemaSnapshot> deployedExpected = new AtomicReference<>();
        AtomicReference<String> renderedSql = new AtomicReference<>();
        SchemaDiffViewModel viewModel = new SchemaDiffViewModel(
                (request, control) -> CompletableFuture.completedFuture(diff),
                (request, expected, statements, control) -> {
                    deployedRequest.set(request);
                    deployedExpected.set(expected);
                    renderedSql.set(statements.getFirst().sql());
                    return CompletableFuture.completedFuture(new SchemaDeploymentResult(
                            SchemaDeploymentState.SUCCEEDED, List.of(), "done"));
                },
                result -> new SchemaChangePlan(
                        result, List.of(change), Set.of(CHANGE_ID), Set.of(), "plan"),
                new SchemaChangePlanner(), renderer,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("schema-diff-canonical-test-", 0).factory()),
                Runnable::run, () -> {});
        try {
            SchemaDiffRequest rawUiRequest = new SchemaDiffRequest(
                    config("source", type), new QualifiedName("SourceOwner", "ui-guessed-source", false),
                    config("target", type), new QualifiedName("TargetOwner", "ui-guessed-target", false));

            assertTrue(viewModel.compare(rawUiRequest));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            assertTrue(viewModel.snapshot().deployEnabled(),
                    "real renderer must receive provider-domain snapshot identities");
            SchemaDiffViewModel.Confirmation confirmation =
                    viewModel.confirmationRequest().orElseThrow();
            assertEquals(targetSchema.comparisonKey(), confirmation.targetSchemaComparisonKey());
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, null)));
            awaitState(viewModel, SchemaDiffViewModel.State.COMPLETED);

            assertEquals(sourceSchema, deployedRequest.get().sourceSchema());
            assertEquals(targetSchema, deployedRequest.get().targetSchema());
            assertEquals(targetSchema, deployedExpected.get().schema());
            assertTrue(renderedSql.get().contains("TargetOwner"));
            assertTrue(renderedSql.get().contains("OrderSeq"));
        } finally {
            viewModel.closeResources();
        }
    }

    private static void assertCreateOrReplaceAdmissionFlow(
            DbType type,
            ObjectType objectType,
            Function<String, QualifiedName> schemaNormalizer,
            ObjectNormalizer objectNormalizer,
            SchemaChangeRenderer renderer) throws Exception {
        String sourceOwner = type == DbType.ORACLE ? "SOURCE_OWNER" : "source_owner";
        String targetOwner = type == DbType.ORACLE ? "TARGET_OWNER" : "target_owner";
        QualifiedName sourceSchema = schemaNormalizer.apply(sourceOwner);
        QualifiedName targetSchema = schemaNormalizer.apply(targetOwner);
        String objectName = "CURRENT_" + objectType.name();
        ObjectKey objectKey = new ObjectKey(
                objectType, objectNormalizer.normalize(sourceOwner, objectName),
                type == DbType.ORACLE && objectType != ObjectType.VIEW
                        ? "oracle-routine-signature-v1\0" : "");
        String definition = createOrReplaceDefinition(type, objectType, sourceOwner, objectName);
        DefinitionObject definitionObject = new DefinitionObject(objectKey, definition, definition,
                Set.of(), com.datacube.spi.schemadiff.DefinitionConfidence.HIGH);
        SchemaChange change = new SchemaChange(
                CHANGE_ID, ChangeKind.CREATE, objectKey, definitionObject, null, null,
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");
        SchemaDiffResult diff = new SchemaDiffResult(
                snapshot(type, "source", sourceSchema,
                        Map.of(objectKey, definitionObject), "source-fp"),
                snapshot(type, "target", targetSchema, Map.of(), "target-fp"),
                List.of(), List.of());
        AtomicReference<String> issuedToken = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger deployCalls = new java.util.concurrent.atomic.AtomicInteger();
        SchemaDiffViewModel viewModel = new SchemaDiffViewModel(
                (request, control) -> CompletableFuture.completedFuture(diff),
                (request, expected, statements, control) -> {
                    deployCalls.incrementAndGet();
                    issuedToken.set(confirmationToken(control));
                    return CompletableFuture.completedFuture(new SchemaDeploymentResult(
                            SchemaDeploymentState.SUCCEEDED, List.of(),
                            SchemaDeploymentService.confirmationToken(statements)));
                },
                result -> new SchemaChangePlan(
                        result, List.of(change), Set.of(CHANGE_ID), Set.of(), "plan"),
                new SchemaChangePlanner(), renderer,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("schema-diff-admission-test-", 0).factory()),
                Runnable::run, () -> {});
        try {
            assertTrue(viewModel.compare(new SchemaDiffRequest(
                    config("source", type), sourceSchema,
                    config("target", type), targetSchema)));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            SchemaDiffViewModel.Confirmation confirmation =
                    viewModel.confirmationRequest().orElseThrow();
            String expectedDigest = SchemaDeploymentService.confirmationToken(
                    viewModel.renderedStatements());
            assertTrue(confirmation.destructive());
            assertEquals(expectedDigest, confirmation.planDigest());
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, false, confirmation.targetSchemaConfirmationToken())));
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, null)));
            SchemaDiffViewModel.Confirmation stale = new SchemaDiffViewModel.Confirmation(
                    confirmation.selectionVersion(), confirmation.targetIdentity(),
                    confirmation.targetSchema(), confirmation.targetSchemaComparisonKey(),
                    confirmation.selectedChangeCount(), confirmation.production(),
                    confirmation.oracleImplicitCommitWarning(), confirmation.destructive(),
                    "0".repeat(64));
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    stale, true, confirmation.targetSchemaConfirmationToken())));
            assertEquals(0, deployCalls.get());
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, confirmation.targetSchemaConfirmationToken())));
            awaitState(viewModel, SchemaDiffViewModel.State.COMPLETED);
            assertEquals(1, deployCalls.get());
            assertEquals(expectedDigest, issuedToken.get());
        } finally {
            viewModel.closeResources();
        }
    }

    private static String createOrReplaceDefinition(
            DbType type, ObjectType objectType, String owner, String objectName) {
        if (type == DbType.POSTGRESQL) {
            return switch (objectType) {
                case VIEW -> "CREATE OR REPLACE VIEW \"" + owner + "\".\"" + objectName
                        + "\" AS SELECT 1";
                case FUNCTION -> "CREATE OR REPLACE FUNCTION \"" + owner + "\".\"" + objectName
                        + "\"() RETURNS integer LANGUAGE sql AS $$ SELECT 1 $$";
                case PROCEDURE -> "CREATE OR REPLACE PROCEDURE \"" + owner + "\".\"" + objectName
                        + "\"() LANGUAGE sql AS $$ SELECT 1 $$";
                default -> throw new IllegalArgumentException("unsupported test object");
            };
        }
        return switch (objectType) {
            case VIEW -> "CREATE OR REPLACE VIEW \"" + owner + "\".\"" + objectName
                    + "\" AS SELECT 1 FROM DUAL;";
            case FUNCTION -> "CREATE OR REPLACE FUNCTION \"" + owner + "\".\"" + objectName
                    + "\" RETURN NUMBER AS BEGIN RETURN 1; END;";
            case PROCEDURE -> "CREATE OR REPLACE PROCEDURE \"" + owner + "\".\"" + objectName
                    + "\" AS BEGIN NULL; END;";
            default -> throw new IllegalArgumentException("unsupported test object");
        };
    }

    private static String confirmationToken(
            com.datacube.service.SchemaDeploymentControl control) {
        try {
            Method method = com.datacube.service.SchemaDeploymentControl.class
                    .getDeclaredMethod("confirmationToken");
            method.setAccessible(true);
            return (String) method.invoke(control);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertDestructiveConfirmationToken(
            DbType type,
            Function<String, QualifiedName> schemaNormalizer,
            ObjectNormalizer objectNormalizer,
            SchemaChangeRenderer renderer) throws Exception {
        QualifiedName sourceSchema = schemaNormalizer.apply("SourceOwner");
        QualifiedName targetSchema = schemaNormalizer.apply("Target\"Owner");
        ObjectKey sequenceKey = new ObjectKey(
                ObjectType.SEQUENCE, objectNormalizer.normalize("Target\"Owner", "OldSequence"), "");
        SequenceDefinition sequence = type == DbType.ORACLE
                ? new SequenceDefinition(sequenceKey, "1", "1", "1", "999", false, 20,
                        Set.of(), Map.of("oracle.order", "NOORDER", "oracle.startValueKnown", "true"))
                : new SequenceDefinition(
                        sequenceKey, "1", "1", "1", "999", false, 20, Set.of());
        SchemaChange destructive = new SchemaChange(
                CHANGE_ID, ChangeKind.DROP, sequenceKey, null, sequence, null,
                RiskLevel.CRITICAL, AutomationLevel.DESTRUCTIVE_OPT_IN,
                false, Set.of(), "fixed destructive review");
        SchemaDiffResult diff = new SchemaDiffResult(
                snapshot(type, "source", sourceSchema, Map.of(), "source-fp"),
                snapshot(type, "target", targetSchema, Map.of(sequenceKey, sequence), "target-fp"),
                List.of(), List.of());
        AtomicReference<SchemaDiffRequest> deployedRequest = new AtomicReference<>();
        SchemaDiffViewModel viewModel = new SchemaDiffViewModel(
                (request, control) -> CompletableFuture.completedFuture(diff),
                (request, expected, statements, control) -> {
                    deployedRequest.set(request);
                    return CompletableFuture.completedFuture(new SchemaDeploymentResult(
                            SchemaDeploymentState.SUCCEEDED, List.of(), "done"));
                },
                result -> new SchemaChangePlan(
                        result, List.of(destructive), Set.of(), Set.of(), "plan"),
                new SchemaChangePlanner(), renderer,
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("schema-diff-confirmation-token-test-", 0).factory()),
                Runnable::run, () -> {});
        try {
            assertTrue(targetSchema.comparisonKey().indexOf('\0') >= 0);
            assertTrue(viewModel.compare(new SchemaDiffRequest(
                    config("source", type), new QualifiedName("SourceOwner", "ui-source", false),
                    config("target", type), new QualifiedName("TargetOwner", "ui-target", false))));
            awaitState(viewModel, SchemaDiffViewModel.State.READY);
            assertTrue(viewModel.setSelected(CHANGE_ID, true, true));

            SchemaDiffViewModel.Confirmation confirmation =
                    viewModel.confirmationRequest().orElseThrow();
            String prompt = SchemaDiffDialogs.destructiveConfirmationPrompt(confirmation);
            assertEquals(targetSchema.original(), confirmation.targetSchemaConfirmationToken());
            assertEquals(targetSchema.comparisonKey(), confirmation.targetSchemaComparisonKey(),
                    "canonical identity remains bound internally");
            assertTrue(prompt.contains(targetSchema.original()));
            assertFalse(prompt.contains(targetSchema.comparisonKey()));
            assertFalse(prompt.contains("schema-v1"));
            assertFalse(prompt.contains("\0"));
            String summary = SchemaDiffDialogs.confirmationSummary(confirmation);
            assertFalse(summary.contains(targetSchema.comparisonKey()));
            assertFalse(summary.contains("schema-v1"));
            assertFalse(summary.contains("\0"));
            SchemaDiffViewModel.Confirmation wrongCanonicalIdentity =
                    new SchemaDiffViewModel.Confirmation(
                            confirmation.selectionVersion(), confirmation.targetIdentity(),
                            confirmation.targetSchema(), "wrong-canonical-identity",
                            confirmation.selectedChangeCount(), confirmation.production(),
                            confirmation.oracleImplicitCommitWarning(), confirmation.destructive(),
                            confirmation.planDigest());
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    wrongCanonicalIdentity, true, targetSchema.original())));
            SchemaDiffViewModel.Confirmation wrongSelectionDigest =
                    new SchemaDiffViewModel.Confirmation(
                            confirmation.selectionVersion(), confirmation.targetIdentity(),
                            confirmation.targetSchema(), confirmation.targetSchemaComparisonKey(),
                            confirmation.selectedChangeCount(), confirmation.production(),
                            confirmation.oracleImplicitCommitWarning(), confirmation.destructive(),
                            "wrong-selection-digest");
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    wrongSelectionDigest, true, targetSchema.original())));
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, targetSchema.original().toLowerCase())));
            assertFalse(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, "TargetOwner")));
            assertTrue(viewModel.deploy(new SchemaDiffViewModel.Approval(
                    confirmation, true, targetSchema.original())));
            awaitState(viewModel, SchemaDiffViewModel.State.COMPLETED);
            assertEquals(targetSchema, deployedRequest.get().targetSchema());
        } finally {
            viewModel.closeResources();
        }
    }

    private static SchemaSnapshot snapshot(
            DbType type, String connectionId, QualifiedName schema,
            Map<ObjectKey, ? extends com.datacube.spi.schemadiff.SchemaObject> objects,
            String fingerprint) {
        TreeMap<ObjectKey, com.datacube.spi.schemadiff.SchemaObject> sorted = new TreeMap<>();
        sorted.putAll(objects);
        return new SchemaSnapshot(type, connectionId, schema, Instant.EPOCH,
                new SnapshotCompleteness(true, new TreeMap<>()), sorted, fingerprint);
    }

    private static ConnConfig config(String id, DbType type) {
        return new ConnConfig(id, id, type, "host", type.defaultPort(),
                "database", "user", "encrypted", Map.of());
    }

    private static void awaitState(
            SchemaDiffViewModel viewModel, SchemaDiffViewModel.State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (viewModel.snapshot().state() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, viewModel.snapshot().state());
    }

    @FunctionalInterface
    private interface ObjectNormalizer {
        QualifiedName normalize(String schema, String object);
    }
}
