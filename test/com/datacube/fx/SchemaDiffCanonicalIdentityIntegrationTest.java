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
import com.datacube.service.SchemaDiffRequest;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffCanonicalIdentityIntegrationTest {
    private static final String CHANGE_ID = "chg:" + "d".repeat(64);

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
