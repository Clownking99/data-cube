package com.datacube.fx;

import com.datacube.schemadiff.PropertyDifference;
import com.datacube.schemadiff.RenameSuggestion;
import com.datacube.schemadiff.SchemaChangePlanner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.SchemaDeploymentService;
import com.datacube.service.SchemaDiffRequest;
import com.datacube.service.SchemaDiffService;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.DefinitionObject;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RenderedStatement;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChangeRenderer;
import com.datacube.spi.schemadiff.SchemaDiffCapability;
import com.datacube.spi.schemadiff.SchemaObject;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** JavaFX Schema Diff workflow with owned virtual-thread work and managed-tab lifecycle hooks. */
public final class SchemaDiffPane implements SchemaDiffManagedTabFactory.ManagedContent {
    private final BorderPane root = new BorderPane();
    private final TextField sourceConnectionField = new TextField();
    private final TextField sourceSchemaField = new TextField();
    private final ComboBox<ConnectionChoice> targetConnection = new ComboBox<>();
    private final TextField targetSchemaField = new TextField();
    private final Button compareButton = new Button("开始对比");
    private final Button exportButton = new Button("导出所选脚本");
    private final Button deployButton = new Button("部署所选变更");
    private final Button cancelButton = new Button("取消当前任务");
    private final ComboBox<ObjectType> objectTypeFilter = new ComboBox<>();
    private final ComboBox<RiskLevel> riskFilter = new ComboBox<>();
    private final ComboBox<AutomationLevel> automationFilter = new ComboBox<>();
    private final ComboBox<SchemaDiffSelectionModel.SelectedState> selectedFilter = new ComboBox<>();
    private final TreeView<DisplayRow> differenceTree = new TreeView<>();
    private final TextArea propertyComparison = detailsArea();
    private final TextArea sourceDefinition = detailsArea();
    private final TextArea targetDefinition = detailsArea();
    private final TextArea sqlPreview = detailsArea();
    private final TextArea diagnostics = detailsArea();
    private final TextArea deploymentSteps = detailsArea();
    private final Label status = new Label();
    private final Map<String, ConnConfig> connectionsById = new LinkedHashMap<>();
    private final Consumer<SchemaDiffViewModel.Snapshot> viewListener = this::renderSnapshot;
    private final SchemaDiffViewModel viewModel;
    private final CloseFlow closeFlow;
    private final ConnConfig source;
    private final List<ObjectType> supportedObjectTypes;
    private boolean refreshingTree;

    public SchemaDiffPane(
            ConnectionManager connections,
            List<ConnConfig> availableConnections,
            ConnConfig source,
            String sourceSchema,
            SchemaDiffCapability capability) {
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(availableConnections, "availableConnections");
        this.source = requireRelational(Objects.requireNonNull(source, "source"));
        SchemaDiffCapability requiredCapability = Objects.requireNonNull(
                capability, "capability");
        SchemaChangeRenderer renderer = Objects.requireNonNull(
                requiredCapability.changeRenderer(), "renderer");
        supportedObjectTypes = supportedFilterTypes(
                requiredCapability.supportedObjectTypes());
        ConstructionOwner construction = new ConstructionOwner(
                ignored -> reportFixedCleanupFailure());
        try {
            SchemaDiffService compareService = new SchemaDiffService(connections);
            SchemaDeploymentService deploymentService = new SchemaDeploymentService(connections);
            ExecutorService workScope = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("schema-diff-ui-work-", 0).factory());
            construction.ownBlocking(workScope::close);
            SchemaChangePlanner planner = new SchemaChangePlanner();
            viewModel = new SchemaDiffViewModel(
                    compareService::compare, deploymentService::deploy, planner::plan, planner,
                    renderer, workScope, Platform::runLater,
                    deploymentService::closeRetainedSessionsStrict);
            construction.ownBlocking(viewModel::closeResources);
            AsyncTabCloseGuard cleanupGuard = AsyncTabCloseGuards.blocking(
                    viewModel::closeResources, ignored -> reportFixedCleanupFailure());
            closeFlow = new CloseFlow(cleanupGuard, cleanupGuard);
            build(availableConnections, sourceSchema);
            construction.commit();
        } catch (Throwable failure) {
            throw construction.close(failure).failure();
        }
    }

    public Node getNode() {
        return root;
    }

    @Override
    public Node content() {
        return root;
    }

    @Override
    public CompletionStage<CloseGuardOutcome> requestClose() {
        return closeFlow.requestInteractive(
                viewModel.requiresCloseConfirmation(),
                () -> SchemaDiffDialogs.confirmClose(owner()));
    }

    /** Mandatory shutdown guard: never opens a dialog and cannot be rejected by the user. */
    @Override
    public CompletionStage<CloseGuardOutcome> requestMandatoryClose() {
        return closeFlow.requestMandatory();
    }

    /** Blocking cleanup for ConstructionOwner and mandatory-abort ownership. */
    @Override
    public void closeResources() {
        viewModel.closeResources();
    }

    /** FX-only lightweight finalizer; no JDBC, file IO, waits or service cleanup are allowed here. */
    @Override
    public void finalizeCloseOnFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Schema Diff FX finalizer requires the FX thread");
        }
        viewModel.removeListener(viewListener);
        compareButton.setOnAction(null);
        exportButton.setOnAction(null);
        deployButton.setOnAction(null);
        cancelButton.setOnAction(null);
        root.setDisable(true);
        root.setVisible(false);
    }

    private void build(List<ConnConfig> availableConnections, String initialSourceSchema) {
        root.setPadding(new Insets(10));
        root.setTop(buildHeader(availableConnections, initialSourceSchema));
        root.setCenter(buildCenter());
        root.setBottom(buildActions());
        viewModel.addListener(viewListener);
    }

    private Node buildHeader(List<ConnConfig> availableConnections, String initialSourceSchema) {
        sourceConnectionField.setText(source.name() + " (" + source.type().displayName() + ")");
        sourceConnectionField.setEditable(false);
        sourceSchemaField.setText(initialSourceSchema == null ? "" : initialSourceSchema);

        List<ConnectionChoice> choices = targetChoices(availableConnections, source);
        for (ConnConfig config : availableConnections) {
            if (config.type() != source.type() || config.type() == DbType.REDIS) continue;
            connectionsById.put(config.id(), config);
        }
        connectionsById.putIfAbsent(source.id(), source);
        targetConnection.setItems(FXCollections.observableArrayList(choices));
        targetConnection.getSelectionModel().select(choices.stream()
                .filter(choice -> choice.type() == source.type() && !choice.id().equals(source.id()))
                .findFirst()
                .orElseGet(() -> choices.stream()
                        .filter(choice -> choice.id().equals(source.id()))
                        .findFirst().orElse(null)));
        targetSchemaField.setText(initialSourceSchema == null ? "" : initialSourceSchema);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("源连接"), sourceConnectionField,
                new Label("源 Schema"), sourceSchemaField);
        grid.addRow(1, new Label("目标连接"), targetConnection,
                new Label("目标 Schema"), targetSchemaField, compareButton);
        GridPane.setHgrow(sourceConnectionField, Priority.ALWAYS);
        GridPane.setHgrow(sourceSchemaField, Priority.ALWAYS);
        GridPane.setHgrow(targetConnection, Priority.ALWAYS);
        GridPane.setHgrow(targetSchemaField, Priority.ALWAYS);
        compareButton.setOnAction(ignored -> compare());
        return grid;
    }

    private Node buildCenter() {
        objectTypeFilter.setPromptText("对象类型");
        objectTypeFilter.setItems(FXCollections.observableArrayList(supportedObjectTypes));
        riskFilter.setPromptText("风险");
        riskFilter.setItems(FXCollections.observableArrayList(RiskLevel.values()));
        automationFilter.setPromptText("自动化");
        automationFilter.setItems(FXCollections.observableArrayList(AutomationLevel.values()));
        selectedFilter.setPromptText("选择状态");
        selectedFilter.setItems(FXCollections.observableArrayList(
                SchemaDiffSelectionModel.SelectedState.values()));
        selectedFilter.getSelectionModel().select(SchemaDiffSelectionModel.SelectedState.ALL);
        objectTypeFilter.setOnAction(ignored -> refreshTree());
        riskFilter.setOnAction(ignored -> refreshTree());
        automationFilter.setOnAction(ignored -> refreshTree());
        selectedFilter.setOnAction(ignored -> refreshTree());

        HBox filters = new HBox(8,
                new Label("对象类型"), objectTypeFilter,
                new Label("风险"), riskFilter,
                new Label("自动化"), automationFilter,
                new Label("选择状态"), selectedFilter);
        differenceTree.setShowRoot(false);
        differenceTree.getSelectionModel().selectedItemProperty().addListener(
                (ignored, before, selected) -> showDetails(selected == null ? null : selected.getValue()));

        VBox left = new VBox(8, filters, differenceTree);
        VBox.setVgrow(differenceTree, Priority.ALWAYS);

        TabPane details = new TabPane(
                fixedTab("属性对比", propertyComparison),
                fixedTab("源定义", sourceDefinition),
                fixedTab("目标定义", targetDefinition),
                fixedTab("SQL 预览", sqlPreview),
                fixedTab("诊断", diagnostics),
                fixedTab("部署结果", deploymentSteps));
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(left, details);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.56);
        return split;
    }

    private Node buildActions() {
        exportButton.setOnAction(ignored -> exportScript());
        deployButton.setOnAction(ignored -> deploy());
        cancelButton.setOnAction(ignored -> viewModel.cancel());
        HBox actions = new HBox(8, status, exportButton, deployButton, cancelButton);
        HBox.setHgrow(status, Priority.ALWAYS);
        actions.setPadding(new Insets(8, 0, 0, 0));
        return actions;
    }

    private void compare() {
        ConnectionChoice targetChoice = targetConnection.getValue();
        ConnConfig target = targetChoice == null ? null : connectionsById.get(targetChoice.id());
        if (target == null) {
            status.setText("请选择目标连接");
            return;
        }
        try {
            QualifiedName sourceName = rawSchemaName(sourceSchemaField.getText());
            QualifiedName targetName = rawSchemaName(targetSchemaField.getText());
            viewModel.compare(new SchemaDiffRequest(source, sourceName, target, targetName));
        } catch (IllegalArgumentException invalid) {
            status.setText("Schema 名称无效");
        }
    }

    private void deploy() {
        Optional<SchemaDiffViewModel.Confirmation> candidate = viewModel.confirmationRequest();
        if (candidate.isEmpty()) return;
        SchemaDiffViewModel.Confirmation confirmation = candidate.orElseThrow();
        if (!SchemaDiffDialogs.confirmDeployment(owner(), confirmation)) return;
        String typedKey = null;
        if (confirmation.destructive()) {
            Optional<String> typed = SchemaDiffDialogs.confirmDestructive(owner(), confirmation);
            if (typed.isEmpty()) return;
            typedKey = typed.orElseThrow();
        }
        viewModel.deploy(new SchemaDiffViewModel.Approval(confirmation, true, typedKey));
    }

    private void exportScript() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出所选 Schema 变更脚本");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL 文件", "*.sql"));
        File selected = chooser.showSaveDialog(owner());
        if (selected == null) return;
        viewModel.exportSelectedScript(selected.toPath()).whenComplete((result, failure) ->
                Platform.runLater(() -> {
                    if (failure != null || !viewModel.isCurrentExport(result)) return;
                    status.setText(result.written()
                            ? "脚本已导出" : "脚本导出失败");
                }));
    }

    private void renderSnapshot(SchemaDiffViewModel.Snapshot snapshot) {
        status.setText(snapshot.deployBlockReason() == SchemaDiffViewModel.DeployBlockReason.NONE
                ? snapshot.message() : snapshot.message() + " · " + snapshot.deployBlockReason().message());
        compareButton.setDisable(snapshot.activeWork() || snapshot.closed());
        deployButton.setDisable(!snapshot.deployEnabled());
        exportButton.setDisable(snapshot.activeWork() || snapshot.statementCount() == 0 || snapshot.closed());
        cancelButton.setDisable(!snapshot.activeWork() || snapshot.closed());
        refreshTree();
        sqlPreview.setText(viewModel.exportSelectedScript());
        deploymentSteps.setText(deploymentStepText(viewModel.deploymentSteps()));
    }

    private void refreshTree() {
        if (refreshingTree) return;
        refreshingTree = true;
        try {
            TreeItem<DisplayRow> rootItem = new TreeItem<>(DisplayRow.root());
            SchemaDiffSelectionModel.Filter filter = new SchemaDiffSelectionModel.Filter(
                    singleton(objectTypeFilter.getValue()), singleton(riskFilter.getValue()),
                    singleton(automationFilter.getValue()),
                    selectedFilter.getValue() == null
                            ? SchemaDiffSelectionModel.SelectedState.ALL : selectedFilter.getValue());
            viewModel.selectionModel().ifPresent(model -> {
                for (SchemaDiffSelectionModel.Group group : model.groups(filter)) {
                    TreeItem<DisplayRow> groupItem = new TreeItem<>(DisplayRow.group(group.objectType()));
                    groupItem.setExpanded(true);
                    for (SchemaDiffSelectionModel.Entry entry : group.entries()) {
                        TreeItem<DisplayRow> item = changeTreeItem(entry);
                        if (item instanceof CheckBoxTreeItem<?> selectableItem) {
                            @SuppressWarnings("unchecked")
                            CheckBoxTreeItem<DisplayRow> checkBox =
                                    (CheckBoxTreeItem<DisplayRow>) selectableItem;
                            checkBox.selectedProperty().addListener((ignored, before, selected) -> {
                                if (refreshingTree) return;
                                boolean riskAccepted = true;
                                if (viewModel.requiresDestructiveConfirmation(
                                        entry.change().id(), selected)) {
                                    riskAccepted = SchemaDiffDialogs.confirmDestructiveSelection(
                                            owner(), entry);
                                }
                                if (!viewModel.setSelected(
                                        entry.change().id(), selected, riskAccepted)) {
                                    refreshingTree = true;
                                    try {
                                        checkBox.setSelected(entry.selected());
                                    } finally {
                                        refreshingTree = false;
                                    }
                                }
                            });
                        }
                        groupItem.getChildren().add(item);
                    }
                    rootItem.getChildren().add(groupItem);
                }
                if (!model.renameSuggestions().isEmpty()) {
                    TreeItem<DisplayRow> renameGroup = new TreeItem<>(DisplayRow.renameGroup());
                    renameGroup.setExpanded(true);
                    for (RenameSuggestion suggestion : model.renameSuggestions()) {
                        renameGroup.getChildren().add(new TreeItem<>(DisplayRow.rename(suggestion)));
                    }
                    rootItem.getChildren().add(renameGroup);
                }
            });
            differenceTree.setRoot(rootItem);
        } finally {
            refreshingTree = false;
        }
    }

    private void showDetails(DisplayRow row) {
        if (row == null || row.entry() == null) {
            if (row != null && row.renameSuggestion() != null) {
                viewModel.selectionModel().ifPresent(model ->
                        model.focusRenameSuggestion(row.renameSuggestion()));
                applyDetails(renameSuggestionDetails());
            } else {
                applyDetails(DetailContent.empty());
            }
            return;
        }
        var change = row.entry().change();
        PropertyDifference property = change.property();
        propertyComparison.setText(property == null
                ? "无结构化属性差异"
                : "路径: " + property.path() + "\n源: " + structuredValue(property.sourceValue())
                + "\n目标: " + structuredValue(property.targetValue()));
        sourceDefinition.setText(definition(change.source()));
        targetDefinition.setText(definition(change.target()));
        sqlPreview.setText(viewModel.renderedStatements().stream()
                .filter(statement -> statement.changeId().equals(change.id()))
                .map(RenderedStatement::sql)
                .reduce((left, right) -> left + "\n\n" + right).orElse(""));
        List<String> fixedDiagnostics = new ArrayList<>();
        fixedDiagnostics.add(change.explanation());
        if (row.entry().blocked()) fixedDiagnostics.add("依赖未选择，当前变更不可执行");
        viewModel.renderedStatements().stream()
                .filter(statement -> statement.changeId().equals(change.id()))
                .map(RenderedStatement::warning)
                .filter(Objects::nonNull)
                .filter(warning -> !warning.isBlank())
                .forEach(fixedDiagnostics::add);
        diagnostics.setText(String.join("\n", fixedDiagnostics));
    }

    private void applyDetails(DetailContent content) {
        propertyComparison.setText(content.propertyComparison());
        sourceDefinition.setText(content.sourceDefinition());
        targetDefinition.setText(content.targetDefinition());
        sqlPreview.setText(content.sqlPreview());
        diagnostics.setText(content.diagnostics());
    }

    private Window owner() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static Tab fixedTab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private static TextArea detailsArea() {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        return area;
    }

    private static <E extends Enum<E>> Set<E> singleton(E value) {
        return value == null ? Set.of() : EnumSet.of(value);
    }

    static QualifiedName rawSchemaName(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.indexOf('\0') >= 0 || text.indexOf('\r') >= 0
                || text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Schema name is invalid");
        }
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            String original = text.substring(1, text.length() - 1).replace("\"\"", "\"");
            if (original.isEmpty()) throw new IllegalArgumentException("Schema name is invalid");
            return new QualifiedName(original, original, true);
        }
        return new QualifiedName(text, text, false);
    }

    private static ConnConfig requireRelational(ConnConfig config) {
        if (config.type() == DbType.REDIS) {
            throw new IllegalArgumentException("Redis does not support Schema Diff");
        }
        return config;
    }

    static List<ConnectionChoice> targetChoices(
            List<ConnConfig> availableConnections, ConnConfig source) {
        Objects.requireNonNull(availableConnections, "availableConnections");
        requireRelational(Objects.requireNonNull(source, "source"));
        Map<String, ConnectionChoice> choices = new LinkedHashMap<>();
        for (ConnConfig config : availableConnections) {
            if (config.type() != source.type() || config.type() == DbType.REDIS) continue;
            choices.putIfAbsent(config.id(),
                    new ConnectionChoice(config.id(), config.name(), config.type()));
        }
        choices.putIfAbsent(source.id(),
                new ConnectionChoice(source.id(), source.name(), source.type()));
        return List.copyOf(choices.values());
    }

    static List<ObjectType> supportedFilterTypes(Set<ObjectType> supportedTypes) {
        Set<ObjectType> supported = Set.copyOf(
                Objects.requireNonNull(supportedTypes, "supportedTypes"));
        List<ObjectType> ordered = new ArrayList<>();
        for (ObjectType type : ObjectType.values()) {
            if (supported.contains(type)) ordered.add(type);
        }
        return List.copyOf(ordered);
    }

    static String deploymentStepText(List<SchemaDiffViewModel.DeploymentStepView> steps) {
        return steps.stream()
                .sorted(Comparator.comparingInt(SchemaDiffViewModel.DeploymentStepView::index))
                .map(step -> "步骤 " + step.index() + " · " + step.state()
                        + " · " + step.changeId())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    static TreeItem<DisplayRow> changeTreeItem(SchemaDiffSelectionModel.Entry entry) {
        DisplayRow row = DisplayRow.change(Objects.requireNonNull(entry, "entry"));
        if (!entry.selectable()) return new TreeItem<>(row);
        CheckBoxTreeItem<DisplayRow> item = new CheckBoxTreeItem<>(row);
        item.setSelected(entry.selected());
        return item;
    }

    static DetailContent renameSuggestionDetails() {
        return new DetailContent(
                "重命名建议仅用于展示，不会生成可执行重命名。", "", "", "", "");
    }

    private static String definition(SchemaObject object) {
        if (object == null) return "—";
        if (object instanceof DefinitionObject definition) {
            return definition.originalDefinition() == null
                    ? "定义不可用" : definition.originalDefinition();
        }
        return object.toString();
    }

    private static String structuredValue(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private static void reportFixedCleanupFailure() {
        System.err.println("[DataCube] Schema Diff cleanup failed");
    }

    record ConnectionChoice(String id, String name, DbType type) {
        ConnectionChoice {
            id = Objects.requireNonNull(id, "id");
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
        }

        @Override
        public String toString() {
            return name + " (" + type.displayName() + ")";
        }
    }

    record DetailContent(
            String propertyComparison, String sourceDefinition,
            String targetDefinition, String sqlPreview, String diagnostics) {
        DetailContent {
            propertyComparison = Objects.requireNonNull(
                    propertyComparison, "propertyComparison");
            sourceDefinition = Objects.requireNonNull(sourceDefinition, "sourceDefinition");
            targetDefinition = Objects.requireNonNull(targetDefinition, "targetDefinition");
            sqlPreview = Objects.requireNonNull(sqlPreview, "sqlPreview");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }

        static DetailContent empty() {
            return new DetailContent("", "", "", "", "");
        }
    }

    record DisplayRow(
            String label,
            SchemaDiffSelectionModel.Entry entry,
            RenameSuggestion renameSuggestion) {
        static DisplayRow root() { return new DisplayRow("Schema 差异", null, null); }
        static DisplayRow group(ObjectType type) {
            return new DisplayRow(type.name(), null, null);
        }
        static DisplayRow renameGroup() { return new DisplayRow("重命名建议（仅展示）", null, null); }
        static DisplayRow change(SchemaDiffSelectionModel.Entry entry) {
            var change = entry.change();
            String suffix = entry.blocked() ? " · 已阻塞" : entry.executable() ? " · 可执行" : " · 不可执行";
            return new DisplayRow(change.object().name().original() + " · " + change.kind()
                    + " · " + change.risk() + " · " + change.automation() + suffix, entry, null);
        }
        static DisplayRow rename(RenameSuggestion suggestion) {
            return new DisplayRow(suggestion.sourceObject().name().original() + " → "
                    + suggestion.targetObject().name().original() + " · 仅展示", null, suggestion);
        }
        @Override public String toString() { return label; }
    }

    static final class CloseFlow {
        private final AsyncTabCloseGuard interactiveGuard;
        private final AsyncTabCloseGuard mandatoryGuard;

        CloseFlow(AsyncTabCloseGuard interactiveGuard, AsyncTabCloseGuard mandatoryGuard) {
            this.interactiveGuard = Objects.requireNonNull(interactiveGuard, "interactiveGuard");
            this.mandatoryGuard = Objects.requireNonNull(mandatoryGuard, "mandatoryGuard");
        }

        CompletionStage<CloseGuardOutcome> requestInteractive(
                boolean confirmationRequired, BooleanSupplier confirmation) {
            Objects.requireNonNull(confirmation, "confirmation");
            if (confirmationRequired && !confirmation.getAsBoolean()) {
                return CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED);
            }
            return interactiveGuard.requestClose();
        }

        CompletionStage<CloseGuardOutcome> requestMandatory() {
            return mandatoryGuard.requestClose();
        }
    }
}
