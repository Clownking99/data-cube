package com.datacube.service;

import com.datacube.config.CredentialCipher;
import com.datacube.provider.oracle.OracleSchemaChangeRenderer;
import com.datacube.provider.oracle.OracleSchemaIdentifierNormalizer;
import com.datacube.provider.postgres.PgSchemaChangeRenderer;
import com.datacube.provider.postgres.PgSchemaIdentifierNormalizer;
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
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.DefinitionConfidence;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDeploymentServiceTest {
    private static final String CHANGE_A = "chg:" + "a".repeat(64);
    private static final String CHANGE_B = "chg:" + "b".repeat(64);
    private static final String CHANGE_C = "chg:" + "c".repeat(64);
    private static final String CHANGE_D = "chg:" + "d".repeat(64);
    private static final String CHANGE_E = "chg:" + "e".repeat(64);

    @Test
    void realProviderCreateOrReplaceAdmissionIsTheExactRedactedConfirmationAuthority() {
        for (DbType type : List.of(DbType.POSTGRESQL, DbType.ORACLE)) {
            QualifiedName sourceSchema = type == DbType.POSTGRESQL
                    ? PgSchemaIdentifierNormalizer.schema("source_owner")
                    : OracleSchemaIdentifierNormalizer.schema("SOURCE_OWNER");
            QualifiedName targetSchema = type == DbType.POSTGRESQL
                    ? PgSchemaIdentifierNormalizer.schema("target_owner")
                    : OracleSchemaIdentifierNormalizer.schema("TARGET_OWNER");
            SchemaChangeRenderer renderer = type == DbType.POSTGRESQL
                    ? new PgSchemaChangeRenderer() : new OracleSchemaChangeRenderer();
            ConnConfig target = new ConnConfig("target", "target", type, "host",
                    type.defaultPort(), "database", "user", "encrypted",
                    Map.of("environment", "DEVELOPMENT"));
            for (ObjectType objectType : List.of(
                    ObjectType.VIEW, ObjectType.FUNCTION, ObjectType.PROCEDURE)) {
                String objectName = "current_" + objectType.name().toLowerCase(java.util.Locale.ROOT);
                ObjectKey key = new ObjectKey(objectType,
                        type == DbType.POSTGRESQL
                                ? PgSchemaIdentifierNormalizer.object("source_owner", objectName)
                                : OracleSchemaIdentifierNormalizer.object(
                                        "SOURCE_OWNER", objectName.toUpperCase(java.util.Locale.ROOT)),
                        type == DbType.ORACLE && objectType != ObjectType.VIEW
                                ? "oracle-routine-signature-v1\0" : "");
                String definition = createOrReplaceDefinition(type, objectType, objectName);
                DefinitionObject source = new DefinitionObject(key, definition, definition,
                        Set.of(), DefinitionConfidence.HIGH);
                SchemaChange change = new SchemaChange(CHANGE_E, ChangeKind.CREATE, key,
                        source, null, null, RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC,
                        true, Set.of(), "fixed safe create");
                List<RenderedStatement> rendered = renderer.render(change,
                        new RenderContext(type, sourceSchema, targetSchema, false));

                SchemaDeploymentAdmission admission =
                        SchemaDeploymentService.planAdmission(target, rendered);

                assertTrue(admission.confirmationRequired(), objectType.name());
                assertTrue(admission.effectiveDestructive(), objectType.name());
                assertTrue(admission.safetyEscalated(), objectType.name());
                assertFalse(admission.productionEscalated(), objectType.name());
                assertEquals(SchemaDeploymentService.confirmationToken(rendered),
                        admission.planDigest(), objectType.name());
                assertEquals(List.of(SchemaDeploymentService.SAFETY_ESCALATION_WARNING),
                        admission.warnings(), objectType.name());
                assertFalse(admission.toString().contains("CREATE"));
                assertFalse(admission.toString().contains(objectName));
            }
        }
    }

    private static String createOrReplaceDefinition(
            DbType type, ObjectType objectType, String objectName) {
        if (type == DbType.POSTGRESQL) {
            return switch (objectType) {
                case VIEW -> "CREATE OR REPLACE VIEW \"source_owner\".\"" + objectName
                        + "\" AS SELECT 1";
                case FUNCTION -> "CREATE OR REPLACE FUNCTION \"source_owner\".\"" + objectName
                        + "\"() RETURNS integer LANGUAGE sql AS $$ SELECT 1 $$";
                case PROCEDURE -> "CREATE OR REPLACE PROCEDURE \"source_owner\".\"" + objectName
                        + "\"() LANGUAGE sql AS $$ SELECT 1 $$";
                default -> throw new IllegalArgumentException("unsupported test object");
            };
        }
        String upperName = objectName.toUpperCase(java.util.Locale.ROOT);
        return switch (objectType) {
            case VIEW -> "CREATE OR REPLACE VIEW \"SOURCE_OWNER\".\"" + upperName
                    + "\" AS SELECT 1 FROM DUAL;";
            case FUNCTION -> "CREATE OR REPLACE FUNCTION \"SOURCE_OWNER\".\"" + upperName
                    + "\" RETURN NUMBER AS BEGIN RETURN 1; END;";
            case PROCEDURE -> "CREATE OR REPLACE PROCEDURE \"SOURCE_OWNER\".\"" + upperName
                    + "\" AS BEGIN NULL; END;";
            default -> throw new IllegalArgumentException("unsupported test object");
        };
    }

    @Test
    void deploysStrictlySequentiallyInOneIndependentSessionAndStablePlanOrder() throws Exception {
        Fixture fixture = new Fixture();
        List<RenderedStatement> plan = List.of(
                statement(CHANGE_A, "CREATE TABLE first_table(id int)", Set.of()),
                statement(CHANGE_A, "ALTER TABLE first_table ADD name text", Set.of()),
                statement(CHANGE_B, "CREATE TABLE second_table(id int)", Set.of(CHANGE_A)));

        SchemaDeploymentResult result = fixture.service.deploy(
                fixture.request, fixture.expected, plan, new SchemaDeploymentControl())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.SUCCEEDED, result.state());
        assertEquals(List.of(
                        SchemaDeploymentState.SUCCEEDED,
                        SchemaDeploymentState.SUCCEEDED,
                        SchemaDeploymentState.SUCCEEDED),
                result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(List.of(1, 2, 3),
                result.steps().stream().map(SchemaDeploymentStepResult::index).toList());
        assertEquals(plan.stream().map(RenderedStatement::sql).toList(), fixture.runner.scripts);
        assertEquals(1, fixture.runner.connections.stream().distinct().count());
        assertEquals(2, fixture.factory.connections.size());
        assertNotSame(fixture.factory.connections.get(0), fixture.runner.connections.getFirst());
        assertTrue(fixture.runner.threads.stream().allMatch(Thread::isVirtual));
        assertEquals(2, fixture.factory.closeAttempts());
        assertEquals(SchemaDeploymentService.confirmationToken(plan), result.planDigest());
        assertFalse(result.toString().contains("first_table"));
        assertFalse(result.steps().getFirst().toString().contains(CHANGE_A));
    }

    @Test
    void firstSqlFailureSkipsTransitiveDependentsAndTerminatesUnrelatedSteps() throws Exception {
        String failingSql = "ALTER TABLE first_table ADD broken int";
        Fixture fixture = new Fixture(Map.of(
                failingSql, QueryResult.error("driver-sql-secret", 1)), false);
        List<RenderedStatement> plan = List.of(
                statement(CHANGE_A, "CREATE TABLE first_table(id int)", Set.of()),
                statement(CHANGE_B, failingSql, Set.of(CHANGE_A)),
                statement(CHANGE_C, "CREATE TABLE dependent_one(id int)", Set.of(CHANGE_B)),
                statement(CHANGE_D, "CREATE TABLE dependent_two(id int)", Set.of(CHANGE_C)),
                statement(CHANGE_E, "CREATE TABLE unrelated(id int)", Set.of()));

        SchemaDeploymentResult result = fixture.service.deploy(
                fixture.request, fixture.expected, plan, new SchemaDeploymentControl())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.FAILED_SQL, result.state());
        assertEquals(List.of(
                        SchemaDeploymentState.SUCCEEDED,
                        SchemaDeploymentState.FAILED_SQL,
                        SchemaDeploymentState.SKIPPED_DEPENDENCY,
                        SchemaDeploymentState.SKIPPED_DEPENDENCY,
                        SchemaDeploymentState.SKIPPED_FAIL_FAST),
                result.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(List.of(plan.get(0).sql(), failingSql), fixture.runner.scripts);
        assertFalse(result.toString().contains("driver-sql-secret"));
        assertFalse(result.toString().contains("unrelated"));
    }

    @Test
    void timeoutIsDistinctFromSqlFailureAndStrictCleanupFailureIsPartial() throws Exception {
        String timeoutSql = "ALTER TABLE first_table ADD delayed int";
        Fixture timeoutFixture = new Fixture(Map.of(
                timeoutSql, QueryResult.timeout("driver-timeout-secret", 1)), false);
        List<RenderedStatement> timeoutPlan = List.of(
                statement(CHANGE_A, timeoutSql, Set.of()),
                statement(CHANGE_B, "CREATE TABLE never_started(id int)", Set.of()));

        SchemaDeploymentResult timeout = timeoutFixture.service.deploy(
                timeoutFixture.request, timeoutFixture.expected, timeoutPlan,
                new SchemaDeploymentControl()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.TIMED_OUT, timeout.state());
        assertEquals(List.of(SchemaDeploymentState.TIMED_OUT, SchemaDeploymentState.SKIPPED_FAIL_FAST),
                timeout.steps().stream().map(SchemaDeploymentStepResult::state).toList());
        assertEquals(1, timeoutFixture.runner.calls.get());

        Fixture cleanupFixture = new Fixture(Map.of(), true);
        List<RenderedStatement> cleanupPlan = List.of(
                statement(CHANGE_A, "CREATE TABLE cleanup_table(id int)", Set.of()));
        SchemaDeploymentResult cleanup = cleanupFixture.service.deploy(
                cleanupFixture.request, cleanupFixture.expected, cleanupPlan,
                new SchemaDeploymentControl()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.FAILED_PARTIAL, cleanup.state());
        assertEquals(SchemaDeploymentState.SUCCEEDED, cleanup.steps().getFirst().state());
        assertEquals(3, cleanupFixture.factory.closeAttempts(),
                "drift close plus two strict session close attempts");
    }

    @Test
    void lifecycleCloseRetriesRetainedSessionsStrictlyAndIsIdempotent() throws Exception {
        Fixture recoverable = new Fixture(Map.of(), 2);
        List<RenderedStatement> plan = List.of(
                statement(CHANGE_A, "CREATE TABLE cleanup_table(id int)", Set.of()));
        SchemaDeploymentResult partial = recoverable.service.deploy(
                recoverable.request, recoverable.expected, plan,
                new SchemaDeploymentControl()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(SchemaDeploymentState.FAILED_PARTIAL, partial.state());

        recoverable.service.closeRetainedSessionsStrict();
        int closed = recoverable.factory.closeAttempts();
        recoverable.service.closeRetainedSessionsStrict();

        assertEquals(4, closed, "fresh read plus three strict session close attempts");
        assertEquals(closed, recoverable.factory.closeAttempts(), "successful ownership is removed");

        Fixture persistent = new Fixture(Map.of(), true);
        persistent.service.deploy(persistent.request, persistent.expected, plan,
                new SchemaDeploymentControl()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, persistent.service::closeRetainedSessionsStrict);
        assertEquals("Schema deployment cleanup failed", failure.getMessage());
        assertFalse(failure.toString().contains("driver-close-secret"));
    }

    @Test
    void lifecycleCloseSealsAdmissionAndWaitsForRacingDeploymentOwnership() throws Exception {
        CountDownLatch sqlEntered = new CountDownLatch(1);
        CountDownLatch releaseSql = new CountDownLatch(1);
        RecordingFactory factory = new RecordingFactory(2);
        SqlRunner runner = blockingRunner(sqlEntered, releaseSql);
        CredentialCipher cipher = new CredentialCipher();
        SchemaSnapshot expected = snapshot();
        DatabaseProvider provider = provider(factory, runner, capability(expected));
        ConnectionManager manager = new ConnectionManager(cipher, ignored -> provider);
        SchemaDeploymentService service = new SchemaDeploymentService(manager);
        SchemaDiffRequest request = new SchemaDiffRequest(
                config(cipher, "source"), name("desired"),
                config(cipher, "target"), name("actual"));
        List<RenderedStatement> plan = List.of(
                statement(CHANGE_A, "CREATE TABLE cleanup_table(id int)", Set.of()));

        var deployment = service.deploy(request, expected, plan, new SchemaDeploymentControl());
        assertTrue(sqlEntered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> close = CompletableFuture.runAsync(service::closeRetainedSessionsStrict);
        assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS),
                "close must await the admitted deployment");

        releaseSql.countDown();
        assertEquals(SchemaDeploymentState.FAILED_PARTIAL,
                deployment.toCompletableFuture().get(5, TimeUnit.SECONDS).state());
        close.get(5, TimeUnit.SECONDS);
        assertEquals(4, factory.closeAttempts());

        Throwable rejected = failure(service.deploy(
                request, expected, plan, new SchemaDeploymentControl()));
        assertEquals(IllegalStateException.class, rejected.getClass());
        assertEquals("Schema deployment service is closed", rejected.getMessage());
    }

    @Test
    void destructiveTokenDigestsExactOrderContentDependenciesFlagsAndGroupBoundaries() {
        List<RenderedStatement> plan = destructivePlan();
        String token = SchemaDeploymentService.confirmationToken(plan);

        assertEquals(64, token.length());
        assertTrue(token.matches("[0-9a-f]{64}"));
        assertEquals(token, SchemaDeploymentService.confirmationToken(List.copyOf(plan)));
        assertNotEquals(token, SchemaDeploymentService.confirmationToken(List.of(plan.get(1), plan.get(0))));
        assertNotEquals(token, SchemaDeploymentService.confirmationToken(List.of(
                copy(plan.get(0), "DROP TABLE changed_table", plan.get(0).dependencyIds()), plan.get(1))));
        assertNotEquals(token, SchemaDeploymentService.confirmationToken(List.of(
                plan.get(0), copy(plan.get(1), plan.get(1).sql(), Set.of(CHANGE_A)))));
        RenderedStatement flagged = new RenderedStatement(
                CHANGE_A, "CREATE TABLE flagged_table(id int)", true, Set.of(), "approved warning");
        assertNotEquals(SchemaDeploymentService.confirmationToken(List.of(flagged)),
                SchemaDeploymentService.confirmationToken(List.of(
                        copy(flagged, flagged.sql(), flagged.dependencyIds(), false))));
    }

    @Test
    void missingOrStaleDestructiveTokenFailsBeforeFreshReadOrSql() throws Exception {
        Fixture fixture = new Fixture();
        List<RenderedStatement> plan = destructivePlan();

        for (String token : List.of("", "0".repeat(64))) {
            Throwable failure = failure(fixture.service.deploy(
                    fixture.request, fixture.expected, plan, new SchemaDeploymentControl(token)));
            assertEquals(IllegalArgumentException.class, failure.getClass());
            assertEquals("Destructive schema plan confirmation is invalid", failure.getMessage());
        }

        assertEquals(0, fixture.factory.opens.get());
        assertEquals(0, fixture.runner.calls.get());
    }

    @Test
    void exactDestructiveTokenExecutesWhileChangedPlansRejectTheOriginalTokenBeforeOpen()
            throws Exception {
        List<RenderedStatement> plan = destructivePlan();
        String token = SchemaDeploymentService.confirmationToken(plan);
        Fixture accepted = new Fixture();

        SchemaDeploymentResult result = accepted.service.deploy(
                accepted.request, accepted.expected, plan, new SchemaDeploymentControl(token))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.SUCCEEDED, result.state());
        assertEquals(plan.stream().map(RenderedStatement::sql).toList(), accepted.runner.scripts);

        List<List<RenderedStatement>> changedPlans = List.of(
                List.of(plan.get(1), plan.get(0)),
                List.of(copy(plan.get(0), "DROP TABLE changed_table", Set.of()), plan.get(1)),
                List.of(plan.get(0), copy(plan.get(1), plan.get(1).sql(), Set.of(CHANGE_A))));
        for (List<RenderedStatement> changed : changedPlans) {
            Fixture rejected = new Fixture();
            Throwable failure = failure(rejected.service.deploy(
                    rejected.request, rejected.expected, changed,
                    new SchemaDeploymentControl(token)));
            assertEquals(IllegalArgumentException.class, failure.getClass());
            assertEquals("Destructive schema plan confirmation is invalid", failure.getMessage());
            assertEquals(0, rejected.factory.opens.get());
            assertEquals(0, rejected.runner.calls.get());
        }
    }

    @Test
    void productionSafePlanRequiresExactCurrentDigestBeforeAnySql() throws Exception {
        List<RenderedStatement> plan = List.of(
                statement(CHANGE_A, "CREATE TABLE production_safe(id int)", Set.of()));
        for (SchemaDeploymentControl control : List.of(
                new SchemaDeploymentControl(),
                new SchemaDeploymentControl("0".repeat(64)))) {
            TypedFixture rejected = new TypedFixture(DbType.POSTGRESQL, "PRODUCTION");

            Throwable failure = failure(rejected.service.deploy(
                    rejected.request, rejected.expected, plan, control));

            assertEquals(IllegalArgumentException.class, failure.getClass());
            assertEquals("Production schema deployment confirmation is invalid",
                    failure.getMessage());
            assertEquals(0, rejected.factory.opens.get());
            assertEquals(0, rejected.runner.calls.get());
        }

        String token = SchemaDeploymentService.confirmationToken(plan);
        TypedFixture accepted = new TypedFixture(DbType.POSTGRESQL, "PRODUCTION");
        SchemaDeploymentResult result = accepted.service.deploy(
                accepted.request, accepted.expected, plan, new SchemaDeploymentControl(token))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.SUCCEEDED, result.state());
        assertEquals(token, result.planDigest());
        assertEquals(List.of(SchemaDeploymentService.PRODUCTION_CONFIRMATION_WARNING),
                result.safetyWarnings());
        assertEquals(List.of(plan.getFirst().sql()), accepted.runner.scripts);
    }

    @Test
    void malformedGroupsMetadataAndDependenciesFailClosedBeforeFreshRead() throws Exception {
        List<List<RenderedStatement>> invalidPlans = List.of(
                List.of(new RenderedStatement("", "CREATE TABLE t(id int)", false, Set.of(), null)),
                List.of(new RenderedStatement(CHANGE_A, "   ", false, Set.of(), null)),
                List.of(new RenderedStatement(CHANGE_A, "CREATE TABLE t(id int)", false,
                        Set.of(CHANGE_C), null)),
                List.of(
                        new RenderedStatement(CHANGE_A, "CREATE TABLE a(id int)", false, Set.of(), null),
                        new RenderedStatement(CHANGE_B, "CREATE TABLE b(id int)", false, Set.of(), null),
                        new RenderedStatement(CHANGE_A, "ALTER TABLE a ADD x int", false, Set.of(), null)),
                List.of(
                        new RenderedStatement(CHANGE_A, "CREATE TABLE a(id int)", false, Set.of(), null),
                        new RenderedStatement(CHANGE_A, "ALTER TABLE a ADD x int", true, Set.of(), "warn")),
                List.of(new RenderedStatement(CHANGE_A, "DROP TABLE unmarked", false, Set.of(), null)),
                List.of(new RenderedStatement(CHANGE_A,
                        "CREATE TABLE safe_table(id int); DROP TABLE hidden_drop",
                        false, Set.of(), null)),
                List.of(new RenderedStatement(CHANGE_A,
                        "ALTER TABLE safe_table ADD safe_column int; DROP TABLE hidden_drop",
                        false, Set.of(), null)),
                List.of(new RenderedStatement(CHANGE_A,
                        "ALTER TABLE safe_table ADD safe_column int, DROP COLUMN valuable_data",
                        false, Set.of(), null)),
                List.of(new RenderedStatement(CHANGE_A,
                        "ALTER TABLE unmarked ALTER COLUMN value TYPE bigint",
                        false, Set.of(), null)),
                List.of(new RenderedStatement(CHANGE_A, "DROP TABLE no_warning", true, Set.of(), "")));

        for (List<RenderedStatement> invalid : invalidPlans) {
            Fixture fixture = new Fixture();
            Throwable failure = failure(fixture.service.deploy(
                    fixture.request, fixture.expected, invalid, new SchemaDeploymentControl()));
            assertEquals(IllegalArgumentException.class, failure.getClass());
            assertEquals("Rendered schema plan is invalid", failure.getMessage());
            assertEquals(0, fixture.factory.opens.get());
            assertEquals(0, fixture.runner.calls.get());
            assertFalse(failure.toString().contains("unmarked"));
            assertFalse(failure.toString().contains("no_warning"));
        }
    }

    @Test
    void lexicalAdmissionIgnoresQuotedAndCommentedDecoysButProvesEveryAdditiveAction()
            throws Exception {
        Fixture fixture = new Fixture();
        List<RenderedStatement> plan = List.of(
                statement(CHANGE_A,
                        "CREATE TABLE safe_text(note text DEFAULT 'DROP TABLE victim') "
                                + "/* ; DROP TABLE comment_decoy */",
                        Set.of()),
                statement(CHANGE_B,
                        "ALTER TABLE safe_text ADD note2 text DEFAULT 'DROP COLUMN decoy', "
                                + "ADD note3 text",
                        Set.of(CHANGE_A)));

        SchemaDeploymentResult result = fixture.service.deploy(
                fixture.request, fixture.expected, plan, new SchemaDeploymentControl())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(SchemaDeploymentState.SUCCEEDED, result.state());
        assertEquals(plan.stream().map(RenderedStatement::sql).toList(), fixture.runner.scripts);
    }

    @Test
    void realProviderCreateOrReplaceOutputIsSafetyEscalatedAndRequiresExactToken()
            throws Exception {
        for (DbType type : List.of(DbType.POSTGRESQL, DbType.ORACLE)) {
            RenderedStatement rendered = realCreateOrReplace(type);
            assertFalse(rendered.destructive(), "frozen Task 7 renderer metadata");
            List<RenderedStatement> plan = List.of(rendered);

            TypedFixture missing = new TypedFixture(type);
            Throwable failure = failure(missing.service.deploy(
                    missing.request, missing.expected, plan, new SchemaDeploymentControl()));
            assertEquals(IllegalArgumentException.class, failure.getClass());
            assertEquals("Destructive schema plan confirmation is invalid", failure.getMessage());
            assertEquals(0, missing.factory.opens.get());

            String token = SchemaDeploymentService.confirmationToken(plan);
            TypedFixture accepted = new TypedFixture(type);
            SchemaDeploymentResult result = accepted.service.deploy(
                    accepted.request, accepted.expected, plan, new SchemaDeploymentControl(token))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(SchemaDeploymentState.SUCCEEDED, result.state());
            assertEquals(List.of(SchemaDeploymentService.SAFETY_ESCALATION_WARNING),
                    result.safetyWarnings());
            assertEquals(token, result.planDigest());
            assertEquals(List.of(rendered.sql()), accepted.runner.scripts);
            assertFalse(result.toString().contains("CREATE OR REPLACE"));
        }
    }

    private static List<RenderedStatement> destructivePlan() {
        return List.of(
                new RenderedStatement(CHANGE_A, "DROP TABLE first_table", true, Set.of(), "approved warning"),
                new RenderedStatement(CHANGE_B, "DROP TABLE second_table", true,
                        Set.of(), "approved warning"));
    }

    private static RenderedStatement realCreateOrReplace(DbType type) {
        QualifiedName sourceSchema;
        QualifiedName targetSchema;
        ObjectKey key;
        String definition;
        SchemaChangeRenderer renderer;
        if (type == DbType.POSTGRESQL) {
            sourceSchema = PgSchemaIdentifierNormalizer.schema("Source");
            targetSchema = PgSchemaIdentifierNormalizer.schema("actual");
            key = new ObjectKey(ObjectType.FUNCTION,
                    PgSchemaIdentifierNormalizer.object("Source", "safe_fn"), "");
            definition = "CREATE OR REPLACE FUNCTION \"Source\".\"safe_fn\"() "
                    + "RETURNS integer LANGUAGE sql AS $$SELECT 1$$;";
            renderer = new PgSchemaChangeRenderer();
        } else {
            sourceSchema = OracleSchemaIdentifierNormalizer.schema("Source");
            targetSchema = OracleSchemaIdentifierNormalizer.schema("actual");
            key = new ObjectKey(ObjectType.FUNCTION,
                    OracleSchemaIdentifierNormalizer.object("Source", "SAFE_FN"),
                    "oracle-routine-signature-v1\0");
            definition = "CREATE OR REPLACE FUNCTION \"Source\".\"SAFE_FN\" RETURN NUMBER IS\n"
                    + "BEGIN\n RETURN 1;\nEND;\n/";
            renderer = new OracleSchemaChangeRenderer();
        }
        DefinitionObject source = new DefinitionObject(
                key, definition, definition, Set.of(), DefinitionConfidence.HIGH);
        SchemaChange change = new SchemaChange(
                CHANGE_A, ChangeKind.CREATE, key, source, null, null,
                RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC, true, Set.of(), "safe");
        return renderer.render(change, new RenderContext(
                type, sourceSchema, targetSchema, false)).getFirst();
    }

    private static RenderedStatement statement(
            String changeId, String sql, Set<String> dependencies) {
        return new RenderedStatement(changeId, sql, false, dependencies, null);
    }

    private static RenderedStatement copy(
            RenderedStatement original, String sql, Set<String> dependencies) {
        return copy(original, sql, dependencies, original.destructive());
    }

    private static RenderedStatement copy(
            RenderedStatement original, String sql, Set<String> dependencies, boolean destructive) {
        return new RenderedStatement(original.changeId(), sql, destructive,
                dependencies, original.warning());
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
        private final RecordingFactory factory;
        private final RecordingRunner runner;
        private final SchemaSnapshot expected = snapshot();
        private final SchemaDeploymentService service;
        private final SchemaDiffRequest request;

        private Fixture() {
            this(Map.of(), false);
        }

        private Fixture(Map<String, QueryResult> outcomes, boolean failSessionClose) {
            factory = new RecordingFactory(failSessionClose);
            runner = new RecordingRunner(outcomes);
            CredentialCipher cipher = new CredentialCipher();
            SchemaDiffCapability capability = capability(expected);
            DatabaseProvider provider = provider(factory, runner, capability);
            ConnectionManager manager = new ConnectionManager(cipher, type -> provider);
            request = new SchemaDiffRequest(config(cipher, "source"), name("desired"),
                    config(cipher, "target"), name("actual"));
            service = new SchemaDeploymentService(manager);
        }

        private Fixture(Map<String, QueryResult> outcomes, int sessionCloseFailures) {
            factory = new RecordingFactory(sessionCloseFailures);
            runner = new RecordingRunner(outcomes);
            CredentialCipher cipher = new CredentialCipher();
            SchemaDiffCapability capability = capability(expected);
            DatabaseProvider provider = provider(factory, runner, capability);
            ConnectionManager manager = new ConnectionManager(cipher, type -> provider);
            request = new SchemaDiffRequest(config(cipher, "source"), name("desired"),
                    config(cipher, "target"), name("actual"));
            service = new SchemaDeploymentService(manager);
        }
    }

    private static final class TypedFixture {
        private final RecordingFactory factory;
        private final RecordingRunner runner;
        private final SchemaSnapshot expected;
        private final SchemaDeploymentService service;
        private final SchemaDiffRequest request;

        private TypedFixture(DbType type) {
            this(type, "TEST");
        }

        private TypedFixture(DbType type, String environment) {
            factory = new RecordingFactory(false);
            runner = new RecordingRunner(Map.of());
            expected = snapshot(type);
            CredentialCipher cipher = new CredentialCipher();
            DatabaseProvider provider = provider(factory, runner, capability(expected), type);
            ConnectionManager manager = new ConnectionManager(cipher, ignored -> provider);
            request = new SchemaDiffRequest(
                    config(cipher, "source", type, environment), name("desired"),
                    config(cipher, "target", type, environment), name("actual"));
            service = new SchemaDeploymentService(manager);
        }
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

    private static SchemaDiffCapability capability(SchemaSnapshot snapshot) {
        return new SchemaDiffCapability() {
            @Override public SchemaSnapshotReader snapshotReader(Connection connection) {
                return (connectionId, schema, options) -> snapshot;
            }
            @Override public SchemaChangeRenderer changeRenderer() { return (change, context) -> List.of(); }
            @Override public Set<ObjectType> supportedObjectTypes() { return Set.of(ObjectType.TABLE); }
        };
    }

    private static DatabaseProvider provider(
            RecordingFactory factory, SqlRunner runner, SchemaDiffCapability capability) {
        return provider(factory, runner, capability, DbType.POSTGRESQL);
    }

    private static DatabaseProvider provider(
            RecordingFactory factory, SqlRunner runner,
            SchemaDiffCapability capability, DbType type) {
        return (DatabaseProvider) Proxy.newProxyInstance(
                SchemaDeploymentServiceTest.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "type" -> type;
                    case "connectionFactory" -> factory;
                    case "sqlRunner" -> runner;
                    case "schemaDiffCapability" -> Optional.of(capability);
                    default -> null;
                });
    }

    private static SqlRunner blockingRunner(
            CountDownLatch entered, CountDownLatch release) {
        return new SqlRunner() {
            @Override public QueryResult execute(
                    Connection connection, String sql, String schema, SqlExecutionOptions options) {
                return QueryResult.update(1, 1);
            }

            @Override public List<ScriptOutcome> executeScript(
                    Connection connection, String script, String schema,
                    SqlExecutionOptions options, ScriptErrorPolicy policy) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        return List.of(new ScriptOutcome(1, script,
                                QueryResult.error("fixed timeout", 1)));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return List.of(new ScriptOutcome(1, script,
                            QueryResult.error("fixed interruption", 1)));
                }
                return List.of(new ScriptOutcome(1, script, QueryResult.update(1, 1)));
            }

            @Override public QueryResult explain(
                    Connection connection, String sql, String schema, boolean analyze,
                    SqlExecutionOptions options) {
                return QueryResult.update(1, 1);
            }
        };
    }

    private static ConnConfig config(CredentialCipher cipher, String id) {
        return config(cipher, id, DbType.POSTGRESQL);
    }

    private static ConnConfig config(CredentialCipher cipher, String id, DbType type) {
        return config(cipher, id, type, "TEST");
    }

    private static ConnConfig config(
            CredentialCipher cipher, String id, DbType type, String environment) {
        return new ConnConfig(id, id, type, id + "-host",
                type == DbType.ORACLE ? 1521 : 5432,
                "database", "user", cipher.encrypt("credential-secret"), Map.of(
                "environment", environment, "queryTimeoutSeconds", "17"));
    }

    private static final class RecordingFactory implements ConnectionFactory {
        private final AtomicInteger opens = new AtomicInteger();
        private final List<Connection> connections = new ArrayList<>();
        private final List<AtomicInteger> closes = new ArrayList<>();
        private final AtomicInteger sessionCloseFailures;

        private RecordingFactory(boolean failSessionClose) {
            this(failSessionClose ? Integer.MAX_VALUE : 0);
        }

        private RecordingFactory(int sessionCloseFailures) {
            this.sessionCloseFailures = new AtomicInteger(sessionCloseFailures);
        }

        @Override public void ensureDriverLoaded() { }
        @Override public Connection open(ConnConfig config) {
            int ordinal = opens.incrementAndGet();
            AtomicInteger closeAttempts = new AtomicInteger();
            Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> {
                            closeAttempts.incrementAndGet();
                            if (ordinal == 2 && sessionCloseFailures.getAndUpdate(
                                    remaining -> Math.max(0, remaining - 1)) > 0) {
                                throw new SQLException("driver-close-secret");
                            }
                            yield null;
                        }
                        case "isClosed" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
            connections.add(connection);
            closes.add(closeAttempts);
            return connection;
        }
        @Override public String test(ConnConfig config) { return null; }

        private int closeAttempts() {
            return closes.stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    private static final class RecordingRunner implements SqlRunner {
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, QueryResult> outcomes;
        private final List<String> scripts = new ArrayList<>();
        private final List<Connection> connections = new ArrayList<>();
        private final List<Thread> threads = new ArrayList<>();

        private RecordingRunner(Map<String, QueryResult> outcomes) {
            this.outcomes = Map.copyOf(outcomes);
        }

        @Override public QueryResult execute(
                Connection connection, String sql, String schema, SqlExecutionOptions options) {
            calls.incrementAndGet(); return QueryResult.update(1, 1);
        }
        @Override public List<ScriptOutcome> executeScript(
                Connection connection, String script, String schema,
                SqlExecutionOptions options, ScriptErrorPolicy policy) {
            calls.incrementAndGet();
            scripts.add(script);
            connections.add(connection);
            threads.add(Thread.currentThread());
            return List.of(new ScriptOutcome(1, script,
                    outcomes.getOrDefault(script, QueryResult.update(1, 1))));
        }
        @Override public QueryResult explain(
                Connection connection, String sql, String schema, boolean analyze,
                SqlExecutionOptions options) {
            calls.incrementAndGet(); return QueryResult.update(1, 1);
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
