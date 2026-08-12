package com.datacube.fx;

import com.datacube.schemadiff.SchemaChangePlan;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.schemadiff.SchemaDiffResult;
import com.datacube.service.SchemaDeploymentControl;
import com.datacube.service.SchemaDeploymentAdmission;
import com.datacube.service.SchemaDeploymentResult;
import com.datacube.service.SchemaDeploymentService;
import com.datacube.service.SchemaDeploymentState;
import com.datacube.service.SchemaDiffRequest;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderContext;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Linearized, redacted state machine for the JavaFX Schema Diff workflow. */
public final class SchemaDiffViewModel {
    private static final String IDLE_MESSAGE = "请选择源和目标 Schema 后开始对比";
    private static final String LOADING_MESSAGE = "正在读取并对比 Schema";
    private static final String READY_MESSAGE = "Schema 对比已完成";
    private static final String DEPLOYING_MESSAGE = "正在部署已选择的 Schema 变更";
    private static final String CANCELLING_MESSAGE = "正在取消当前任务";
    private static final String COMPLETED_MESSAGE = "Schema 变更部署已完成";
    private static final String FAILED_MESSAGE = "Schema 对比失败，请重试";
    private static final String DEPLOY_FAILED_MESSAGE = "Schema 变更部署失败，请检查诊断信息";
    private static final String DRIFTED_MESSAGE = "目标 Schema 已发生漂移，请重新对比";
    private static final String CANCELLED_MESSAGE = "当前任务已取消";
    private static final String PROVIDER_MISMATCH_MESSAGE = "源和目标必须使用相同的关系型数据库";

    private final CompareGateway compareGateway;
    private final Function<SchemaDiffResult, SchemaChangePlan> planFactory;
    private final SchemaChangePlanner planner;
    private final SchemaChangeRenderer renderer;
    private final ExecutorService workScope;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable strictServiceCleanup;
    private final List<Consumer<Snapshot>> listeners = new ArrayList<>();
    private DeployGateway deployGateway;

    private State state = State.IDLE;
    private String message = IDLE_MESSAGE;
    private long generation;
    private long selectionVersion;
    private long exportGeneration;
    private long uiRevision;
    private boolean closed;
    private boolean providerMismatch;
    private boolean renderUnsupported;
    private boolean executionAuthorityRevoked;
    private ActiveOperation activeOperation;
    private SchemaDiffRequest request;
    private SchemaDiffResult diff;
    private SchemaDiffSelectionModel selection;
    private List<RenderedStatement> renderedStatements = List.of();
    private String renderedPlanDigest = "";
    private SchemaDeploymentAdmission planAdmission;
    private SchemaDeploymentResult deploymentResult;

    SchemaDiffViewModel(
            CompareGateway compareGateway,
            DeployGateway deployGateway,
            Function<SchemaDiffResult, SchemaChangePlan> planFactory,
            SchemaChangePlanner planner,
            SchemaChangeRenderer renderer,
            ExecutorService workScope,
            Consumer<Runnable> uiDispatcher,
            Runnable strictServiceCleanup) {
        this.compareGateway = Objects.requireNonNull(compareGateway, "compareGateway");
        this.deployGateway = Objects.requireNonNull(deployGateway, "deployGateway");
        this.planFactory = Objects.requireNonNull(planFactory, "planFactory");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.workScope = Objects.requireNonNull(workScope, "workScope");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.strictServiceCleanup = Objects.requireNonNull(
                strictServiceCleanup, "strictServiceCleanup");
    }

    public synchronized boolean compare(SchemaDiffRequest candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (closed || activeOperation != null) return false;
        exportGeneration++;
        if (candidate.sourceConfig().type() == DbType.REDIS
                || candidate.targetConfig().type() == DbType.REDIS
                || candidate.sourceConfig().type() != candidate.targetConfig().type()) {
            providerMismatch = true;
            state = State.FAILED;
            message = PROVIDER_MISMATCH_MESSAGE;
            publishLocked();
            return false;
        }

        providerMismatch = false;
        generation++;
        long operationGeneration = generation;
        SchemaDeploymentControl control = new SchemaDeploymentControl();
        activeOperation = new ActiveOperation(OperationKind.COMPARE, operationGeneration, control);
        request = candidate;
        diff = null;
        selection = null;
        renderedStatements = List.of();
        renderedPlanDigest = "";
        planAdmission = null;
        deploymentResult = null;
        renderUnsupported = false;
        executionAuthorityRevoked = false;
        selectionVersion++;
        state = State.LOADING;
        message = LOADING_MESSAGE;
        publishLocked();
        try {
            workScope.submit(() -> runCompare(candidate, control, operationGeneration));
            return true;
        } catch (RejectedExecutionException rejected) {
            activeOperation = null;
            if (!closed) {
                state = State.FAILED;
                message = FAILED_MESSAGE;
                publishLocked();
            }
            return false;
        }
    }

    public synchronized boolean setSelected(String changeId, boolean selectedValue) {
        return setSelected(changeId, selectedValue, false);
    }

    public synchronized boolean setSelected(
            String changeId, boolean selectedValue, boolean destructiveRiskAccepted) {
        if (closed || activeOperation != null || selection == null
                || state != State.READY || executionAuthorityRevoked) return false;
        boolean changed = selection.setSelected(
                changeId, selectedValue, destructiveRiskAccepted);
        if (!changed) return false;
        selectionVersion++;
        renderSelectionLocked();
        state = State.READY;
        message = READY_MESSAGE;
        deploymentResult = null;
        publishLocked();
        return true;
    }

    public synchronized boolean requiresDestructiveConfirmation(
            String changeId, boolean selectedValue) {
        return !closed && activeOperation == null && selection != null
                && state == State.READY && !executionAuthorityRevoked
                && selection.requiresDestructiveConfirmation(changeId, selectedValue);
    }

    public synchronized Optional<Confirmation> confirmationRequest() {
        if (deployBlockReasonLocked() != DeployBlockReason.NONE || request == null
                || selection == null || diff == null) {
            return Optional.empty();
        }
        if (planAdmission == null) return Optional.empty();
        boolean production = planAdmission.productionEscalated();
        boolean destructive = planAdmission.effectiveDestructive();
        String targetSchemaToken = schemaConfirmationToken(request.targetSchema());
        if (targetSchemaToken == null) return Optional.empty();
        return Optional.of(new Confirmation(
                selectionVersion,
                request.targetConfig().name() + " [" + request.targetConfig().id() + "]",
                targetSchemaToken,
                request.targetSchema().comparisonKey(),
                selection.selectedChangeIds().size(),
                production,
                request.targetConfig().type() == DbType.ORACLE,
                destructive,
                renderedPlanDigest));
    }

    public synchronized boolean deploy(Approval approval) {
        Objects.requireNonNull(approval, "approval");
        if (closed || activeOperation != null || !approval.firstConfirmationAccepted()) return false;
        Confirmation current = confirmationRequest().orElse(null);
        if (current == null || !current.equals(approval.confirmation())) return false;
        if (current.destructive()
                && !current.targetSchemaConfirmationToken().equals(
                        approval.typedSchemaConfirmationToken())) {
            return false;
        }
        exportGeneration++;
        String token = planAdmission.confirmationRequired() ? current.planDigest() : null;
        selection.markConfirmed(current.planDigest());
        SchemaDeploymentControl control = new SchemaDeploymentControl(token);
        generation++;
        long operationGeneration = generation;
        activeOperation = new ActiveOperation(OperationKind.DEPLOY, operationGeneration, control);
        state = State.DEPLOYING;
        message = DEPLOYING_MESSAGE;
        publishLocked();
        SchemaDiffRequest admittedRequest = request;
        SchemaDiffResult admittedDiff = diff;
        List<RenderedStatement> admittedStatements = renderedStatements;
        try {
            workScope.submit(() -> runDeploy(admittedRequest, admittedDiff, admittedStatements,
                    control, operationGeneration));
            return true;
        } catch (RejectedExecutionException rejected) {
            activeOperation = null;
            if (!closed) {
                state = State.FAILED;
                message = DEPLOY_FAILED_MESSAGE;
                revokeExecutionAuthorityLocked();
                publishLocked();
            }
            return false;
        }
    }

    /** Requests cancellation on an owned virtual thread; this method itself never blocks the FX thread. */
    public synchronized boolean cancel() {
        if (closed || activeOperation == null || state == State.CANCELLING) return false;
        SchemaDeploymentControl control = activeOperation.control();
        state = State.CANCELLING;
        message = CANCELLING_MESSAGE;
        publishLocked();
        try {
            workScope.submit(control::cancel);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    public synchronized Snapshot snapshot() {
        DeployBlockReason reason = deployBlockReasonLocked();
        int selectedCount = selection == null ? 0 : selection.selectedChangeIds().size();
        return new Snapshot(state, message, reason == DeployBlockReason.NONE, reason,
                selectedCount, renderedStatements.size(), activeOperation != null, closed,
                selection != null && (closed || state != State.READY
                        || executionAuthorityRevoked));
    }

    public synchronized Optional<SchemaDiffSelectionModel> selectionModel() {
        return Optional.ofNullable(selection);
    }

    public synchronized Optional<SchemaDiffRequest> currentRequest() {
        return Optional.ofNullable(request);
    }

    public synchronized Optional<SchemaDiffResult> currentDiff() {
        return Optional.ofNullable(diff);
    }

    public synchronized Optional<SchemaDeploymentResult> deploymentResult() {
        return Optional.ofNullable(deploymentResult);
    }

    public synchronized List<DeploymentStepView> deploymentSteps() {
        if (deploymentResult == null) return List.of();
        return deploymentResult.steps().stream()
                .map(step -> new DeploymentStepView(
                        step.index(), step.changeId(), deploymentStepSummary(step.changeId()),
                        step.state()))
                .toList();
    }

    public synchronized List<RenderedStatement> renderedStatements() {
        return renderedStatements;
    }

    public synchronized String exportSelectedScript() {
        return renderedStatements.stream()
                .map(RenderedStatement::sql)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    /** Writes the already-rendered preview on the owned virtual-thread scope without changing state. */
    public synchronized CompletionStage<ExportResult> exportSelectedScript(Path target) {
        Objects.requireNonNull(target, "target");
        long exportToken = ++exportGeneration;
        if (closed || activeOperation != null || renderedStatements.isEmpty()) {
            return CompletableFuture.completedFuture(new ExportResult(exportToken, false));
        }
        String script = exportSelectedScript();
        CompletableFuture<ExportResult> result = new CompletableFuture<>();
        try {
            workScope.submit(() -> {
                try {
                    Files.writeString(target, script, StandardCharsets.UTF_8);
                    result.complete(new ExportResult(exportToken, true));
                } catch (IOException failure) {
                    result.complete(new ExportResult(exportToken, false));
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.complete(new ExportResult(exportToken, false));
        }
        return result;
    }

    public synchronized boolean isCurrentExport(ExportResult result) {
        return result != null && !closed && result.generation() == exportGeneration;
    }

    public synchronized boolean requiresCloseConfirmation() {
        return !closed && (activeOperation != null
                || selection != null && !selection.selectedChangeIds().isEmpty());
    }

    public synchronized boolean hasActiveWork() {
        return activeOperation != null;
    }

    public synchronized void addListener(Consumer<Snapshot> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        publishLocked();
    }

    public synchronized void removeListener(Consumer<Snapshot> listener) {
        listeners.remove(listener);
    }

    /** Blocking cleanup: seal admission, cancel, await owned work, then close service ownership. */
    public void closeResources() {
        SchemaDeploymentControl control;
        synchronized (this) {
            if (closed) return;
            closed = true;
            generation++;
            control = activeOperation == null ? null : activeOperation.control();
            if (activeOperation != null) {
                state = State.CANCELLING;
                message = CANCELLING_MESSAGE;
            }
            uiRevision++;
        }
        if (control != null) control.cancel();
        workScope.close();
        strictServiceCleanup.run();
    }

    private void runCompare(
            SchemaDiffRequest admittedRequest,
            SchemaDeploymentControl control,
            long operationGeneration) {
        try {
            SchemaDiffResult result = compareGateway.compare(admittedRequest, control)
                    .toCompletableFuture().join();
            synchronized (this) {
                if (!isCurrentLocked(OperationKind.COMPARE, operationGeneration, control)) return;
                activeOperation = null;
                if (control.cancellationRequested() || state == State.CANCELLING) {
                    state = State.IDLE;
                    message = CANCELLED_MESSAGE;
                    request = null;
                    diff = null;
                    selection = null;
                    renderedStatements = List.of();
                    renderedPlanDigest = "";
                    planAdmission = null;
                } else {
                    applyCompareResultLocked(admittedRequest, result);
                }
                publishLocked();
            }
        } catch (Throwable failure) {
            synchronized (this) {
                if (!isCurrentLocked(OperationKind.COMPARE, operationGeneration, control)) return;
                activeOperation = null;
                if (control.cancellationRequested() || state == State.CANCELLING) {
                    state = State.IDLE;
                    message = CANCELLED_MESSAGE;
                } else {
                    state = State.FAILED;
                    message = FAILED_MESSAGE;
                }
                publishLocked();
            }
        }
    }

    private void applyCompareResultLocked(
            SchemaDiffRequest admittedRequest, SchemaDiffResult result) {
        try {
            SchemaDiffResult compared = Objects.requireNonNull(result, "result");
            SchemaDiffRequest canonicalRequest = canonicalRequest(admittedRequest, compared);
            SchemaChangePlan plan = planFactory.apply(compared);
            request = canonicalRequest;
            diff = result;
            selection = new SchemaDiffSelectionModel(plan, planner);
            selectionVersion++;
            renderSelectionLocked();
            state = State.READY;
            message = READY_MESSAGE;
        } catch (RuntimeException invalid) {
            diff = null;
            selection = null;
            renderedStatements = List.of();
            renderedPlanDigest = "";
            planAdmission = null;
            state = State.FAILED;
            message = FAILED_MESSAGE;
        }
    }

    private static SchemaDiffRequest canonicalRequest(
            SchemaDiffRequest admittedRequest, SchemaDiffResult result) {
        if (result.source().databaseType() != admittedRequest.sourceConfig().type()
                || result.target().databaseType() != admittedRequest.targetConfig().type()
                || !Objects.equals(
                        result.source().connectionId(), admittedRequest.sourceConfig().id())
                || !Objects.equals(
                        result.target().connectionId(), admittedRequest.targetConfig().id())) {
            throw new IllegalArgumentException("Schema comparison identity is invalid");
        }
        if (admittedRequest.sourceConfig().id().equals(admittedRequest.targetConfig().id())
                && result.source().schema().equals(result.target().schema())) {
            throw new IllegalArgumentException("Schema comparison endpoints are identical");
        }
        return new SchemaDiffRequest(
                admittedRequest.sourceConfig(), result.source().schema(),
                admittedRequest.targetConfig(), result.target().schema());
    }

    private static String schemaConfirmationToken(QualifiedName schema) {
        String token = Objects.requireNonNull(schema, "schema").original();
        if (token.isBlank()) return null;
        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            if (Character.isISOControl(codePoint)) return null;
            offset += Character.charCount(codePoint);
        }
        return token;
    }

    private void runDeploy(
            SchemaDiffRequest admittedRequest,
            SchemaDiffResult admittedDiff,
            List<RenderedStatement> admittedStatements,
            SchemaDeploymentControl control,
            long operationGeneration) {
        try {
            SchemaDeploymentResult result = deployGateway.deploy(
                    admittedRequest, admittedDiff.target(), admittedStatements, control)
                    .toCompletableFuture().join();
            synchronized (this) {
                if (!isCurrentLocked(OperationKind.DEPLOY, operationGeneration, control)) return;
                activeOperation = null;
                deploymentResult = result;
                applyDeploymentResultLocked(result);
                publishLocked();
            }
        } catch (Throwable failure) {
            synchronized (this) {
                if (!isCurrentLocked(OperationKind.DEPLOY, operationGeneration, control)) return;
                activeOperation = null;
                boolean cancelled = control.cancellationRequested() || state == State.CANCELLING;
                state = State.FAILED;
                message = cancelled ? CANCELLED_MESSAGE : DEPLOY_FAILED_MESSAGE;
                revokeExecutionAuthorityLocked();
                publishLocked();
            }
        }
    }

    private void applyDeploymentResultLocked(SchemaDeploymentResult result) {
        revokeExecutionAuthorityLocked();
        switch (result.state()) {
            case SUCCEEDED -> {
                state = State.COMPLETED;
                message = COMPLETED_MESSAGE;
            }
            case BLOCKED_DRIFT -> {
                state = State.DRIFTED;
                message = DRIFTED_MESSAGE;
            }
            case CANCELLED -> {
                state = State.FAILED;
                message = CANCELLED_MESSAGE;
            }
            default -> {
                state = State.FAILED;
                message = DEPLOY_FAILED_MESSAGE;
            }
        }
    }

    private void revokeExecutionAuthorityLocked() {
        executionAuthorityRevoked = true;
        if (selection != null) selection.invalidateConfirmation();
    }

    private String deploymentStepSummary(String changeId) {
        if (selection == null) return "变更步骤";
        try {
            SchemaDiffSelectionModel.Entry entry = selection.entry(changeId);
            return entry.change().object().type().name() + " · "
                    + safeReviewLabel(entry.change().object().name().original());
        } catch (IllegalArgumentException unknownChange) {
            return "变更步骤";
        }
    }

    private static String safeReviewLabel(String value) {
        StringBuilder safe = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < value.length() && count < 120;) {
            int codePoint = value.codePointAt(offset);
            safe.appendCodePoint(Character.isISOControl(codePoint) ? 0xfffd : codePoint);
            offset += Character.charCount(codePoint);
            count++;
        }
        if (count == 120 && safe.length() < value.length()) safe.append('…');
        return safe.toString();
    }

    private void renderSelectionLocked() {
        renderUnsupported = false;
        List<SchemaDiffSelectionModel.Entry> selectedEntries = selection.entries().stream()
                .filter(SchemaDiffSelectionModel.Entry::selected)
                .toList();
        boolean destructive = selection.hasDestructiveSelection();
        List<RenderedStatement> statements = new ArrayList<>();
        try {
            RenderContext context = new RenderContext(
                    request.sourceConfig().type(), request.sourceSchema(), request.targetSchema(),
                    destructive);
            for (SchemaDiffSelectionModel.Entry entry : selectedEntries) {
                if (!entry.executable()) {
                    renderUnsupported = true;
                    continue;
                }
                List<RenderedStatement> rendered = renderer.render(entry.change(), context);
                if (rendered == null || rendered.isEmpty()) {
                    renderUnsupported = true;
                    continue;
                }
                statements.addAll(rendered);
            }
            renderedStatements = List.copyOf(statements);
            if (renderedStatements.isEmpty()) {
                planAdmission = null;
                renderedPlanDigest = "";
            } else {
                planAdmission = Objects.requireNonNull(
                        deployGateway.admission(request, renderedStatements), "planAdmission");
                renderedPlanDigest = planAdmission.planDigest();
            }
        } catch (RuntimeException invalid) {
            renderUnsupported = true;
            renderedStatements = List.of();
            renderedPlanDigest = "";
            planAdmission = null;
        }
    }

    private DeployBlockReason deployBlockReasonLocked() {
        if (closed) return DeployBlockReason.CLOSED;
        if (activeOperation != null || state == State.LOADING || state == State.DEPLOYING
                || state == State.CANCELLING) {
            return DeployBlockReason.ACTIVE_WORK;
        }
        if (providerMismatch) return DeployBlockReason.PROVIDER_MISMATCH;
        if (state == State.DRIFTED) return DeployBlockReason.DRIFT;
        if (selection == null || diff == null || request == null || state != State.READY) {
            return DeployBlockReason.NOT_READY;
        }
        if (!diff.source().completeness().complete() || !diff.target().completeness().complete()) {
            return DeployBlockReason.INCOMPLETE_SNAPSHOT;
        }
        if (renderUnsupported || selection.entries().stream().anyMatch(
                entry -> entry.blocked() || entry.selected()
                        && entry.change().automation() == AutomationLevel.MANUAL_ONLY)) {
            return DeployBlockReason.MANUAL_OR_UNSUPPORTED;
        }
        if (selection.selectedChangeIds().isEmpty() || renderedStatements.isEmpty()) {
            return DeployBlockReason.NO_EXECUTABLE_SELECTION;
        }
        return DeployBlockReason.NONE;
    }

    private boolean isCurrentLocked(
            OperationKind kind, long operationGeneration, SchemaDeploymentControl control) {
        return !closed && activeOperation != null
                && activeOperation.kind() == kind
                && activeOperation.generation() == operationGeneration
                && activeOperation.control() == control;
    }

    private void publishLocked() {
        Snapshot published = snapshot();
        List<Consumer<Snapshot>> currentListeners = List.copyOf(listeners);
        long revision = ++uiRevision;
        uiDispatcher.accept(() -> {
            synchronized (SchemaDiffViewModel.this) {
                if (closed || revision != uiRevision) return;
            }
            for (Consumer<Snapshot> listener : currentListeners) {
                try {
                    listener.accept(published);
                } catch (RuntimeException ignored) {
                    // Listener failures cannot change lifecycle ownership or expose operation details.
                }
            }
        });
    }

    @Override
    public synchronized String toString() {
        return "SchemaDiffViewModel[state=" + state
                + ", selectedCount=" + (selection == null ? 0 : selection.selectedChangeIds().size())
                + ", statementCount=" + renderedStatements.size()
                + ", active=" + (activeOperation != null)
                + ", closed=" + closed + "]";
    }

    @FunctionalInterface
    interface CompareGateway {
        CompletionStage<SchemaDiffResult> compare(
                SchemaDiffRequest request, SchemaDeploymentControl control);
    }

    @FunctionalInterface
    interface DeployGateway {
        CompletionStage<SchemaDeploymentResult> deploy(
                SchemaDiffRequest request,
                com.datacube.spi.schemadiff.SchemaSnapshot expectedTarget,
                List<RenderedStatement> statements,
                SchemaDeploymentControl control);

        default SchemaDeploymentAdmission admission(
                SchemaDiffRequest request, List<RenderedStatement> statements) {
            return SchemaDeploymentService.planAdmission(request.targetConfig(), statements);
        }
    }

    public enum State {
        IDLE, LOADING, READY, DEPLOYING, CANCELLING, COMPLETED, FAILED, DRIFTED
    }

    public enum DeployBlockReason {
        NONE(""),
        NOT_READY("请先完成 Schema 对比"),
        INCOMPLETE_SNAPSHOT("快照不完整，不能部署"),
        DRIFT("目标 Schema 已漂移，请重新对比"),
        PROVIDER_MISMATCH("源和目标必须使用相同的关系型数据库"),
        MANUAL_OR_UNSUPPORTED("选择包含手动、阻塞或不支持的变更"),
        NO_EXECUTABLE_SELECTION("请选择至少一个可执行变更"),
        ACTIVE_WORK("当前任务尚未完成"),
        CLOSED("Schema 对比页已关闭");

        private final String message;

        DeployBlockReason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public record Snapshot(
            State state,
            String message,
            boolean deployEnabled,
            DeployBlockReason deployBlockReason,
            int selectedChangeCount,
            int statementCount,
            boolean activeWork,
            boolean closed,
            boolean selectionReadOnly) {
        public Snapshot {
            state = Objects.requireNonNull(state, "state");
            message = Objects.requireNonNull(message, "message");
            deployBlockReason = Objects.requireNonNull(deployBlockReason, "deployBlockReason");
        }

        @Override
        public String toString() {
            return "Snapshot[state=" + state + ", deployEnabled=" + deployEnabled
                    + ", selectedChangeCount=" + selectedChangeCount
                    + ", statementCount=" + statementCount
                    + ", activeWork=" + activeWork + ", closed=" + closed
                    + ", selectionReadOnly=" + selectionReadOnly + "]";
        }
    }

    public record Confirmation(
            long selectionVersion,
            String targetIdentity,
            String targetSchema,
            String targetSchemaComparisonKey,
            int selectedChangeCount,
            boolean production,
            boolean oracleImplicitCommitWarning,
            boolean destructive,
            String planDigest) {
        public Confirmation {
            targetIdentity = Objects.requireNonNull(targetIdentity, "targetIdentity");
            targetSchema = Objects.requireNonNull(targetSchema, "targetSchema");
            targetSchemaComparisonKey = Objects.requireNonNull(
                    targetSchemaComparisonKey, "targetSchemaComparisonKey");
            planDigest = Objects.requireNonNull(planDigest, "planDigest");
        }

        /** Exact, keyboard-safe snapshot display name used only for the human confirmation step. */
        public String targetSchemaConfirmationToken() {
            return targetSchema;
        }

        @Override
        public String toString() {
            return "Confirmation[selectedChangeCount=" + selectedChangeCount
                    + ", production=" + production + ", oracle=" + oracleImplicitCommitWarning
                    + ", destructive=" + destructive + "]";
        }
    }

    public record ExportResult(long generation, boolean written) {
        @Override
        public String toString() {
            return "ExportResult[written=" + written + "]";
        }
    }

    public record DeploymentStepView(
            int index, String changeId, String changeSummary, SchemaDeploymentState state) {
        public DeploymentStepView {
            if (index < 1) throw new IllegalArgumentException("Deployment step index is invalid");
            changeId = Objects.requireNonNull(changeId, "changeId");
            changeSummary = Objects.requireNonNull(changeSummary, "changeSummary");
            state = Objects.requireNonNull(state, "state");
        }

        @Override
        public String toString() {
            return "DeploymentStepView[index=" + index + ", state=" + state + "]";
        }
    }

    public record Approval(
            Confirmation confirmation,
            boolean firstConfirmationAccepted,
            String typedSchemaConfirmationToken) {
        public Approval {
            confirmation = Objects.requireNonNull(confirmation, "confirmation");
        }

        @Override
        public String toString() {
            return "Approval[firstConfirmationAccepted=" + firstConfirmationAccepted
                    + ", typedSchemaKeyPresent="
                    + (typedSchemaConfirmationToken != null
                    && !typedSchemaConfirmationToken.isBlank()) + "]";
        }
    }

    private enum OperationKind { COMPARE, DEPLOY }

    private record ActiveOperation(
            OperationKind kind, long generation, SchemaDeploymentControl control) {}
}
