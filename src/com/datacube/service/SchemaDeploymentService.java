package com.datacube.service;

import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaSnapshot;

import java.sql.Connection;
import java.sql.SQLException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Drift-gated, sequential schema deployment orchestration. */
public final class SchemaDeploymentService {
    private static final String INVALID_EXPECTED = "Expected target snapshot is invalid";
    private static final String INVALID_TARGET = "Schema deployment target is invalid";
    private static final String SNAPSHOT_FAILED = "Fresh target snapshot failed";
    private static final String INVALID_PLAN = "Rendered schema plan is invalid";
    private static final String INVALID_CONFIRMATION =
            "Destructive schema plan confirmation is invalid";
    static final String SAFETY_ESCALATION_WARNING =
            "CREATE OR REPLACE was safety-escalated and required exact plan confirmation";
    static final String PRODUCTION_CONFIRMATION_WARNING =
            "Production schema deployment required exact plan confirmation";
    private static final String INVALID_PRODUCTION_CONFIRMATION =
            "Production schema deployment confirmation is invalid";
    private static final String SERVICE_CLOSED = "Schema deployment service is closed";
    private static final String CLEANUP_FAILED = "Schema deployment cleanup failed";
    private static final String DIGEST_DOMAIN = "datacube.rendered-schema-plan.v1";
    private static final java.util.regex.Pattern CHANGE_ID =
            java.util.regex.Pattern.compile("chg:[0-9a-f]{64}");

    private final ConnectionManager connections;
    private final Set<JdbcEditorSession> retainedCleanupSessions = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private int activeDeployments;
    private boolean lifecycleClosed;

    public SchemaDeploymentService(ConnectionManager connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public CompletionStage<SchemaDeploymentResult> deploy(
            SchemaDiffRequest request,
            SchemaSnapshot expectedTarget,
            List<RenderedStatement> statements,
            SchemaDeploymentControl control) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(expectedTarget, "expectedTarget");
        Objects.requireNonNull(statements, "statements");
        Objects.requireNonNull(control, "control");
        SchemaDiffRequest admitted = new SchemaDiffRequest(
                request.sourceConfig(), request.sourceSchema(),
                request.targetConfig(), request.targetSchema());
        ConnConfig target = admitted.targetConfig();
        if (target.type() == DbType.REDIS
                || admitted.sourceConfig().type() != target.type()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(INVALID_TARGET));
        }
        if (expectedTarget.databaseType() != target.type()
                || !expectedTarget.schema().equals(admitted.targetSchema())
                || !expectedTarget.completeness().complete()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(INVALID_EXPECTED));
        }
        ValidatedPlan validation;
        try {
            validation = validateForTarget(target, statements);
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(INVALID_PLAN));
        }
        PlanAdmission plan = validation.plan();
        SchemaDeploymentAdmission admission = validation.admission();
        if (admission.confirmationRequired()
                && (control.confirmationToken() == null
                || control.confirmationToken().isBlank()
                || !plan.digest().equals(control.confirmationToken()))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    admission.productionEscalated()
                            ? INVALID_PRODUCTION_CONFIRMATION : INVALID_CONFIRMATION));
        }
        if (control.cancellationRequested()) {
            return CompletableFuture.completedFuture(new SchemaDeploymentResult(
                    SchemaDeploymentState.CANCELLED, List.of(), plan.digest(),
                    plan.safetyWarnings()));
        }
        DatabaseProvider provider = connections.provider(target);
        SchemaDiffCapability capability = provider.schemaDiffCapability().orElse(null);
        if (provider.type() != target.type() || capability == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(INVALID_TARGET));
        }
        if (!beginDeployment()) {
            return CompletableFuture.failedFuture(new IllegalStateException(SERVICE_CLOSED));
        }
        CompletableFuture<SchemaDeploymentResult> settlement = new CompletableFuture<>();
        settlement.whenComplete((ignored, failure) -> {
            if (settlement.isCancelled()) control.cancel();
        });
        Runnable deployment = () -> {
            try {
                retryRetainedCleanup();
                SchemaSnapshot current = readFreshTarget(
                        target, admitted.targetSchema(), provider, capability, control);
                SchemaDeploymentResult result;
                if (control.cancellationRequested()) {
                    result = cancelled(plan);
                } else if (!current.completeness().complete()) {
                    result = new SchemaDeploymentResult(
                            SchemaDeploymentState.BLOCKED_INCOMPLETE, List.of(), plan.digest(),
                            plan.safetyWarnings());
                } else if (current.databaseType() != target.type()
                        || !current.schema().equals(admitted.targetSchema())
                        || !current.fingerprint().equals(expectedTarget.fingerprint())) {
                    result = new SchemaDeploymentResult(
                            SchemaDeploymentState.BLOCKED_DRIFT, List.of(), plan.digest(),
                            plan.safetyWarnings());
                } else {
                    result = executePlan(
                            target, admitted.targetSchema().original(), provider, plan, control);
                }
                control.settle(
                        settlement, result, cancellationAlternative(result, plan));
            } catch (Throwable failure) {
                control.settleExceptionally(
                        settlement, new IllegalStateException(SNAPSHOT_FAILED), cancelled(plan));
            } finally {
                endDeployment();
            }
        };
        try {
            Thread.ofVirtual().name("schema-deployment").start(deployment);
        } catch (Throwable startupFailure) {
            endDeployment();
            control.settleExceptionally(
                    settlement, new IllegalStateException(SNAPSHOT_FAILED), cancelled(plan));
        }
        return settlement;
    }

    /**
     * Seals this service, awaits all admitted deployments, then retries every retained strict
     * session cleanup. This blocking lifecycle boundary must run outside the JavaFX thread.
     */
    public void closeRetainedSessionsStrict() {
        synchronized (lifecycleLock) {
            lifecycleClosed = true;
            while (activeDeployments > 0) {
                try {
                    lifecycleLock.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(CLEANUP_FAILED);
                }
            }
            boolean failed = false;
            for (JdbcEditorSession retained : List.copyOf(retainedCleanupSessions)) {
                try {
                    retained.closeStrict();
                    retainedCleanupSessions.remove(retained);
                } catch (Throwable failure) {
                    failed = true;
                }
            }
            if (failed || !retainedCleanupSessions.isEmpty()) {
                throw new IllegalStateException(CLEANUP_FAILED);
            }
        }
    }

    private boolean beginDeployment() {
        synchronized (lifecycleLock) {
            if (lifecycleClosed) return false;
            activeDeployments++;
            return true;
        }
    }

    private void endDeployment() {
        synchronized (lifecycleLock) {
            activeDeployments--;
            lifecycleLock.notifyAll();
        }
    }

    private static SchemaDeploymentResult cancelled(PlanAdmission plan) {
        return new SchemaDeploymentResult(
                SchemaDeploymentState.CANCELLED, List.of(), plan.digest(),
                plan.safetyWarnings());
    }

    private static SchemaDeploymentResult cancellationAlternative(
            SchemaDeploymentResult result, PlanAdmission plan) {
        if (!result.steps().isEmpty()) return result;
        return switch (result.state()) {
            case CANCELLED, FAILED_PARTIAL, FAILED_SQL, TIMED_OUT -> result;
            default -> cancelled(plan);
        };
    }

    private SchemaDeploymentResult executePlan(
            ConnConfig target,
            String schema,
            DatabaseProvider provider,
            PlanAdmission plan,
            SchemaDeploymentControl control) {
        if (plan.statements().isEmpty()) {
            return new SchemaDeploymentResult(
                    control.cancellationRequested()
                            ? SchemaDeploymentState.CANCELLED
                            : SchemaDeploymentState.SUCCEEDED,
                    List.of(), plan.digest(), plan.safetyWarnings());
        }
        JdbcEditorSession session = connections.openEditorSession(target, provider);
        List<SchemaDeploymentStepResult> steps = new ArrayList<>(plan.statements().size());
        SchemaDeploymentState overall = SchemaDeploymentState.SUCCEEDED;
        String failedChangeId = null;
        try (SchemaDeploymentControl.Registration ignored =
                     control.register(() -> session.cancel())) {
            for (int index = 0; index < plan.statements().size(); index++) {
                RenderedStatement statement = plan.statements().get(index);
                if (failedChangeId != null) {
                    SchemaDeploymentState skipped = overall != SchemaDeploymentState.CANCELLED
                            && overall != SchemaDeploymentState.UNKNOWN_AFTER_CANCEL
                            && dependsTransitively(
                            statement.changeId(), failedChangeId, plan.groupDependencies())
                            ? SchemaDeploymentState.SKIPPED_DEPENDENCY
                            : SchemaDeploymentState.SKIPPED_FAIL_FAST;
                    steps.add(new SchemaDeploymentStepResult(index + 1, statement.changeId(), skipped));
                    continue;
                }
                if (control.cancellationRequested()) {
                    overall = SchemaDeploymentState.CANCELLED;
                    failedChangeId = statement.changeId();
                    steps.add(new SchemaDeploymentStepResult(
                            index + 1, statement.changeId(), SchemaDeploymentState.CANCELLED));
                    continue;
                }
                JdbcEditorSession.ExecutionBatch batch = session.executeScript(
                        statement.sql(), schema, 0, null, target.type() == DbType.ORACLE,
                        control::cancellationRequested);
                SchemaDeploymentState stepState = executionState(batch, control, target.type());
                steps.add(new SchemaDeploymentStepResult(index + 1, statement.changeId(), stepState));
                if (stepState != SchemaDeploymentState.SUCCEEDED) {
                    overall = stepState;
                    failedChangeId = statement.changeId();
                }
            }
        } finally {
            if (!strictCleanup(session)) overall = SchemaDeploymentState.FAILED_PARTIAL;
        }
        return new SchemaDeploymentResult(
                overall, steps, plan.digest(), plan.safetyWarnings());
    }

    private boolean strictCleanup(JdbcEditorSession session) {
        try {
            session.closeStrict();
            retainedCleanupSessions.remove(session);
            return true;
        } catch (JdbcEditorSession.StrictCleanupFailure first) {
            if (!first.retryable()) return false;
            try {
                session.closeStrict();
                retainedCleanupSessions.remove(session);
                return true;
            } catch (JdbcEditorSession.StrictCleanupFailure retry) {
                if (retry.retryable()) retainedCleanupSessions.add(session);
                return false;
            } catch (SQLException retry) {
                return false;
            }
        } catch (SQLException failure) {
            return false;
        }
    }

    private void retryRetainedCleanup() {
        for (JdbcEditorSession retained : List.copyOf(retainedCleanupSessions)) {
            try {
                retained.closeStrict();
                retainedCleanupSessions.remove(retained);
            } catch (SQLException ignored) {
                // Ownership remains retained for the next service operation.
            }
        }
    }

    private static SchemaDeploymentState executionState(
            JdbcEditorSession.ExecutionBatch batch,
            SchemaDeploymentControl control,
            DbType databaseType) {
        if (batch.outcomes().isEmpty()) {
            return control.cancellationRequested()
                    ? cancellationState(databaseType, false)
                    : SchemaDeploymentState.FAILED_SQL;
        }
        boolean timeout = false;
        boolean sqlFailure = false;
        boolean priorOutcome = false;
        for (ScriptOutcome outcome : batch.outcomes()) {
            QueryResult result = outcome.result();
            if (result.kind != QueryResult.Kind.ERROR) {
                priorOutcome = true;
                continue;
            }
            if (result.failureKind == QueryResult.FailureKind.CANCELLED) {
                return cancellationState(databaseType, priorOutcome);
            }
            if (result.failureKind == QueryResult.FailureKind.TIMEOUT) timeout = true;
            else sqlFailure = true;
            priorOutcome = true;
        }
        if (timeout) return SchemaDeploymentState.TIMED_OUT;
        if (sqlFailure) return SchemaDeploymentState.FAILED_SQL;
        return SchemaDeploymentState.SUCCEEDED;
    }

    private static SchemaDeploymentState cancellationState(
            DbType databaseType, boolean priorOutcome) {
        return databaseType == DbType.ORACLE || priorOutcome
                ? SchemaDeploymentState.UNKNOWN_AFTER_CANCEL
                : SchemaDeploymentState.CANCELLED;
    }

    private static boolean dependsTransitively(
            String changeId,
            String failedChangeId,
            Map<String, Set<String>> dependencies) {
        if (changeId.equals(failedChangeId)) return false;
        Set<String> visited = new HashSet<>();
        List<String> pending = new ArrayList<>(
                dependencies.getOrDefault(changeId, Set.of()));
        while (!pending.isEmpty()) {
            String dependency = pending.removeLast();
            if (dependency.equals(failedChangeId)) return true;
            if (visited.add(dependency)) {
                pending.addAll(dependencies.getOrDefault(dependency, Set.of()));
            }
        }
        return false;
    }

    /** Stable confirmation token for one exact, validated rendered plan. */
    public static String confirmationToken(List<RenderedStatement> statements) {
        return validatePlan(statements, null).digest();
    }

    /** Exact service admission used by UI and deploy; contains no SQL or connection secrets. */
    public static SchemaDeploymentAdmission planAdmission(
            ConnConfig target, List<RenderedStatement> statements) {
        Objects.requireNonNull(target, "target");
        if (target.type() == DbType.REDIS) throw new IllegalArgumentException(INVALID_TARGET);
        return validateForTarget(target, statements).admission();
    }

    private static ValidatedPlan validateForTarget(
            ConnConfig target, List<RenderedStatement> statements) {
        PlanAdmission validated = validatePlan(statements, target.type());
        boolean production = ConnectionSafetyOptions.from(target).environment()
                == ConnectionEnvironment.PRODUCTION;
        boolean productionDdl = production && !validated.statements().isEmpty();
        PlanAdmission plan = productionDdl
                ? validated.withSafetyWarning(PRODUCTION_CONFIRMATION_WARNING) : validated;
        boolean safetyEscalated = plan.safetyWarnings().contains(SAFETY_ESCALATION_WARNING);
        SchemaDeploymentAdmission admission = new SchemaDeploymentAdmission(
                plan.digest(), plan.destructive() || productionDdl,
                plan.destructive(), safetyEscalated, productionDdl, plan.safetyWarnings());
        return new ValidatedPlan(plan, admission);
    }

    private static PlanAdmission validatePlan(
            List<RenderedStatement> statements, DbType databaseType) {
        List<RenderedStatement> copied = List.copyOf(Objects.requireNonNull(statements, "statements"));
        Map<String, ChangeGroup> groups = new LinkedHashMap<>();
        List<StatementAdmission> admittedStatements = new ArrayList<>(copied.size());
        Set<String> finishedGroups = new HashSet<>();
        String currentId = null;
        ChangeGroup current = null;
        for (RenderedStatement statement : copied) {
            Objects.requireNonNull(statement, "statement");
            String id = statement.changeId();
            SchemaDeploymentSqlAdmission.Classification sqlClassification = databaseType == null
                    ? null
                    : SchemaDeploymentSqlAdmission.classify(statement.sql(), databaseType);
            boolean safetyEscalated = !statement.destructive()
                    && (sqlClassification
                            == SchemaDeploymentSqlAdmission.Classification.CREATE_OR_REPLACE
                    || databaseType == null
                    && SchemaDeploymentSqlAdmission.isCreateOrReplace(statement.sql()));
            boolean effectiveDestructive = statement.destructive() || safetyEscalated;
            if (!CHANGE_ID.matcher(id).matches()
                    || statement.sql().isBlank()
                    || statement.sql().indexOf('\0') >= 0
                    || statement.dependencyIds().stream().anyMatch(
                            dependency -> !CHANGE_ID.matcher(dependency).matches())
                    || statement.warning() != null && statement.warning().indexOf('\0') >= 0
                    || sqlClassification == SchemaDeploymentSqlAdmission.Classification.DESTRUCTIVE
                    && !statement.destructive()
                    || statement.destructive()
                    && (statement.warning() == null || statement.warning().isBlank())) {
                throw new IllegalArgumentException(INVALID_PLAN);
            }
            admittedStatements.add(new StatementAdmission(
                    effectiveDestructive, safetyEscalated));
            if (!id.equals(currentId)) {
                if (currentId != null) finishedGroups.add(currentId);
                if (finishedGroups.contains(id) || groups.containsKey(id)) {
                    throw new IllegalArgumentException(INVALID_PLAN);
                }
                current = new ChangeGroup(id, statement.destructive(),
                        statement.dependencyIds(), statement.warning(), groups.size());
                groups.put(id, current);
                currentId = id;
            } else if (!current.matches(statement)) {
                throw new IllegalArgumentException(INVALID_PLAN);
            }
        }
        for (ChangeGroup group : groups.values()) {
            for (String dependency : group.dependencies()) {
                ChangeGroup prerequisite = groups.get(dependency);
                if (dependency.equals(group.id())
                        || prerequisite == null
                        || prerequisite.order() >= group.order()) {
                    throw new IllegalArgumentException(INVALID_PLAN);
                }
            }
        }
        Map<String, Set<String>> dependencies = new HashMap<>();
        groups.values().forEach(group -> dependencies.put(group.id(), group.dependencies()));
        boolean destructive = admittedStatements.stream()
                .anyMatch(StatementAdmission::effectiveDestructive);
        List<String> safetyWarnings = admittedStatements.stream()
                .anyMatch(StatementAdmission::safetyEscalated)
                ? List.of(SAFETY_ESCALATION_WARNING)
                : List.of();
        return new PlanAdmission(copied, digest(copied, admittedStatements),
                destructive, Map.copyOf(dependencies), safetyWarnings);
    }

    private static String digest(
            List<RenderedStatement> statements,
            List<StatementAdmission> admittedStatements) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeField(output, DIGEST_DOMAIN);
            output.writeInt(statements.size());
            String priorId = null;
            for (int index = 0; index < statements.size(); index++) {
                RenderedStatement statement = statements.get(index);
                output.writeInt(index);
                output.writeBoolean(!statement.changeId().equals(priorId));
                writeField(output, statement.changeId());
                writeField(output, statement.sql());
                output.writeBoolean(statement.destructive());
                output.writeBoolean(admittedStatements.get(index).effectiveDestructive());
                output.writeBoolean(admittedStatements.get(index).safetyEscalated());
                List<String> dependencies = statement.dependencyIds().stream().sorted().toList();
                output.writeInt(dependencies.size());
                for (String dependency : dependencies) writeField(output, dependency);
                output.writeBoolean(statement.warning() != null);
                if (statement.warning() != null) writeField(output, statement.warning());
                priorId = statement.changeId();
            }
            output.flush();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Rendered schema plan digest is unavailable");
        }
    }

    private static void writeField(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private record ChangeGroup(
            String id,
            boolean destructive,
            Set<String> dependencies,
            String warning,
            int order) {
        private ChangeGroup {
            dependencies = Set.copyOf(dependencies);
        }

        private boolean matches(RenderedStatement statement) {
            return destructive == statement.destructive()
                    && dependencies.equals(statement.dependencyIds())
                    && Objects.equals(warning, statement.warning());
        }
    }

    private record PlanAdmission(
            List<RenderedStatement> statements,
            String digest,
            boolean destructive,
            Map<String, Set<String>> groupDependencies,
            List<String> safetyWarnings) {
        private PlanAdmission withSafetyWarning(String warning) {
            if (safetyWarnings.contains(warning)) return this;
            List<String> combined = new ArrayList<>(safetyWarnings);
            combined.add(warning);
            return new PlanAdmission(
                    statements, digest, destructive, groupDependencies, List.copyOf(combined));
        }
    }

    private record StatementAdmission(
            boolean effectiveDestructive,
            boolean safetyEscalated) {
    }

    private record ValidatedPlan(
            PlanAdmission plan,
            SchemaDeploymentAdmission admission) {
    }

    private SchemaSnapshot readFreshTarget(
            ConnConfig target,
            com.datacube.spi.schemadiff.QualifiedName schema,
            DatabaseProvider provider,
            SchemaDiffCapability capability,
            SchemaDeploymentControl control) throws SQLException {
        SqlExecutionControl sqlControl = new SqlExecutionControl();
        FreshConnectionOwner owned = new FreshConnectionOwner();
        try (SchemaDeploymentControl.Registration ignored = control.register(() -> {
            cancelFreshRead(sqlControl, owned);
        })) {
            if (control.cancellationRequested()) throw new SQLException("Schema deployment cancelled");
            Connection opened = connections.openDedicated(target, provider);
            owned.publish(opened);
            if (control.cancellationRequested()) {
                owned.close();
                throw new SQLException("Schema deployment cancelled");
            }
            try {
                opened.setReadOnly(true);
            } catch (SQLException unsupported) {
                // Best effort only.
            }
            int timeout = ConnectionSafetyOptions.from(target).queryTimeoutSeconds();
            return capability.snapshotReader(opened).read(
                    target.id(), schema, new SqlExecutionOptions(0, timeout, sqlControl));
        } finally {
            owned.close();
        }
    }

    private static void cancelFreshRead(
            SqlExecutionControl sqlControl, FreshConnectionOwner owned) throws SQLException {
        Throwable failure = null;
        try {
            sqlControl.cancel();
        } catch (Throwable cancellationFailure) {
            failure = cancellationFailure;
        }
        try {
            owned.close();
        } catch (Throwable closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        rethrowJdbcCleanupFailure(failure);
    }

    private static void rethrowJdbcCleanupFailure(Throwable failure) throws SQLException {
        if (failure == null) return;
        if (failure instanceof SQLException sqlFailure) throw sqlFailure;
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        throw new SQLException("JDBC cleanup failed", failure);
    }

    /** Coordinates one fresh-read connection and makes cleanup completion observable to all callers. */
    private static final class FreshConnectionOwner {
        private Connection connection;
        private boolean closeRequested;
        private boolean closing;
        private boolean closed;
        private Throwable closeFailure;

        private void publish(Connection opened) throws SQLException {
            boolean closeAfterPublish;
            synchronized (this) {
                if (connection != null || closing || closed) {
                    throw new IllegalStateException("Fresh connection ownership already published");
                }
                connection = Objects.requireNonNull(opened, "opened");
                closeAfterPublish = closeRequested;
            }
            if (closeAfterPublish) close();
        }

        private void close() throws SQLException {
            Connection current;
            boolean interrupted = false;
            synchronized (this) {
                closeRequested = true;
                while (closing) {
                    try {
                        wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                if (closed) {
                    rethrowJdbcCleanupFailure(closeFailure);
                    return;
                }
                if (connection == null) return;
                current = connection;
                connection = null;
                closing = true;
            }

            Throwable failure = null;
            try {
                current.close();
            } catch (Throwable closeProblem) {
                failure = closeProblem;
            } finally {
                synchronized (this) {
                    closeFailure = failure;
                    closed = true;
                    closing = false;
                    notifyAll();
                }
            }
            rethrowJdbcCleanupFailure(failure);
        }
    }
}
