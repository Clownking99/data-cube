package com.datacube.fx;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.service.SchemaDeploymentState;
import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ChangeKind;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.ObjectType;
import com.datacube.spi.schemadiff.QualifiedName;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaChange;
import javafx.application.Platform;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffPaneContractTest {

    @BeforeAll
    static void startFxToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @Test
    void targetChoicesContainOnlyTheSourceProviderAndNeverRedis() {
        ConnConfig source = config("source", DbType.POSTGRESQL);

        List<SchemaDiffPane.ConnectionChoice> choices = SchemaDiffPane.targetChoices(
                List.of(source, config("pg", DbType.POSTGRESQL),
                        config("oracle", DbType.ORACLE), config("redis", DbType.REDIS)),
                source);

        assertEquals(List.of("source", "pg"),
                choices.stream().map(SchemaDiffPane.ConnectionChoice::id).toList());
        assertTrue(choices.stream().allMatch(choice -> choice.type() == DbType.POSTGRESQL));
    }

    @Test
    void objectTypeFilterUsesOnlyCapabilitySupportedTypesInEnumOrder() {
        assertEquals(List.of(ObjectType.TABLE, ObjectType.SEQUENCE, ObjectType.VIEW),
                SchemaDiffPane.supportedFilterTypes(java.util.Set.of(
                        ObjectType.VIEW, ObjectType.TABLE, ObjectType.SEQUENCE)));
    }

    @Test
    void rawUiSchemaAdmissionNeverInventsProviderCaseNormalization() {
        QualifiedName unquoted = SchemaDiffPane.rawSchemaName("MiXeD_Owner");
        QualifiedName quoted = SchemaDiffPane.rawSchemaName("\"MiXeD_Owner\"");

        assertEquals("MiXeD_Owner", unquoted.original());
        assertEquals("MiXeD_Owner", unquoted.comparisonKey());
        assertFalse(unquoted.quoted());
        assertEquals("MiXeD_Owner", quoted.original());
        assertEquals("MiXeD_Owner", quoted.comparisonKey());
        assertTrue(quoted.quoted());
    }

    @Test
    void deploymentStepsAreRenderedIndividuallyInIndexOrderIncludingUnknownAfterCancel() {
        String firstId = "chg:" + "a".repeat(64);
        String secondId = "chg:" + "b".repeat(64);
        String text = SchemaDiffPane.deploymentStepText(List.of(
                new SchemaDiffViewModel.DeploymentStepView(
                        2, secondId, "VIEW · mixed_name", SchemaDeploymentState.UNKNOWN_AFTER_CANCEL),
                new SchemaDiffViewModel.DeploymentStepView(
                        1, firstId, "TABLE · audit_log", SchemaDeploymentState.SUCCEEDED)));

        assertTrue(text.indexOf("步骤 1") < text.indexOf("步骤 2"));
        assertTrue(text.contains("TABLE · audit_log"));
        assertTrue(text.contains("VIEW · mixed_name"));
        assertTrue(text.contains("SUCCEEDED"));
        assertTrue(text.contains("UNKNOWN_AFTER_CANCEL"));
        assertFalse(text.contains(firstId));
        assertFalse(text.contains(secondId));
        assertFalse(text.contains("SELECT "));
        assertFalse(text.contains("DROP "));
    }

    @Test
    void realTreeItemsUseCheckboxesOnlyForSelectableDifferences() {
        SchemaChange change = new SchemaChange(
                "chg:" + "e".repeat(64), ChangeKind.CREATE,
                new ObjectKey(ObjectType.TABLE,
                        new QualifiedName("table_name", "table_name", false), ""),
                null, null, null, RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC,
                false, java.util.Set.of(), "fixed");
        SchemaDiffSelectionModel.Entry selectable =
                new SchemaDiffSelectionModel.Entry(change, false, false, true, false);
        SchemaDiffSelectionModel.Entry blocked =
                new SchemaDiffSelectionModel.Entry(change, false, true, false, false);
        SchemaDiffSelectionModel.Entry manual = new SchemaDiffSelectionModel.Entry(
                new SchemaChange(change.id(), ChangeKind.MANUAL, change.object(),
                        null, null, null, RiskLevel.HIGH, AutomationLevel.MANUAL_ONLY,
                        false, java.util.Set.of(), "fixed"),
                false, false, false, false);

        TreeItem<?> selectableItem = SchemaDiffPane.changeTreeItem(selectable);
        TreeItem<?> blockedItem = SchemaDiffPane.changeTreeItem(blocked);
        TreeItem<?> manualItem = SchemaDiffPane.changeTreeItem(manual);
        TreeItem<?> failedReviewItem = SchemaDiffPane.changeTreeItem(selectable, true);

        assertTrue(selectableItem instanceof CheckBoxTreeItem<?>);
        assertFalse(blockedItem instanceof CheckBoxTreeItem<?>);
        assertFalse(manualItem instanceof CheckBoxTreeItem<?>);
        assertFalse(failedReviewItem instanceof CheckBoxTreeItem<?>,
                "terminal deployment review preserves the row without restoring edit authority");
    }

    @Test
    void terminalTreeRefreshRestoresTheSelectedReviewRowForDetailsAndDiagnostics() {
        SchemaChange change = new SchemaChange(
                "chg:" + "f".repeat(64), ChangeKind.CREATE,
                new ObjectKey(ObjectType.TABLE,
                        new QualifiedName("review_table", "review_table", false), ""),
                null, null, null, RiskLevel.LOW, AutomationLevel.SAFE_AUTOMATIC,
                true, java.util.Set.of(), "fixed diagnostic");
        SchemaDiffSelectionModel.Entry entry =
                new SchemaDiffSelectionModel.Entry(change, true, false, true, true);
        SchemaDiffPane.DisplayRow selected = SchemaDiffPane.DisplayRow.change(entry);
        TreeItem<SchemaDiffPane.DisplayRow> oldRoot =
                new TreeItem<>(SchemaDiffPane.DisplayRow.root());
        TreeItem<SchemaDiffPane.DisplayRow> oldItem = new TreeItem<>(selected);
        oldRoot.getChildren().add(oldItem);
        TreeView<SchemaDiffPane.DisplayRow> tree = new TreeView<>(oldRoot);
        tree.getSelectionModel().select(oldItem);
        TreeItem<SchemaDiffPane.DisplayRow> refreshedRoot =
                new TreeItem<>(SchemaDiffPane.DisplayRow.root());
        refreshedRoot.getChildren().add(new TreeItem<>(selected));

        SchemaDiffPane.replaceTreePreservingSelection(tree, refreshedRoot);

        assertEquals(selected, tree.getSelectionModel().getSelectedItem().getValue());
    }

    @Test
    void renameSuggestionProjectionClearsEveryStaleDefinitionSqlAndDiagnosticField() {
        SchemaDiffPane.DetailContent details = SchemaDiffPane.renameSuggestionDetails();

        assertTrue(details.propertyComparison().contains("仅用于展示"));
        assertEquals("", details.sourceDefinition());
        assertEquals("", details.targetDefinition());
        assertEquals("", details.sqlPreview());
        assertEquals("", details.diagnostics());
    }

    @Test
    void exposesFullWorkflowLayoutAndActionsWithoutFxThreadBlocking() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SchemaDiffPane.java"));

        for (String label : new String[]{
                "源连接", "源 Schema", "目标连接", "目标 Schema", "开始对比",
                "对象类型", "风险", "自动化", "选择状态",
                "属性对比", "源定义", "目标定义", "SQL 预览", "诊断",
                "导出所选脚本", "部署所选变更", "取消当前任务"}) {
            assertTrue(source.contains(label), label);
        }
        assertFalse(source.contains(".toCompletableFuture().join()"));
        assertFalse(source.contains("Files.writeString("), "file IO belongs to the owned view-model scope");
        assertFalse(source.contains("getEncryptedPassword"));
        assertFalse(source.contains("error.getMessage()"));
        assertFalse(source.contains("failure.getMessage()"));
        assertTrue(source.contains("viewModel.requiresDestructiveConfirmation"));
        assertTrue(source.contains("SchemaDiffDialogs.confirmDestructiveSelection"));
    }

    @Test
    void exposesDistinctInteractiveMandatoryBlockingAndFxFinalizerContracts() throws Exception {
        Method interactive = SchemaDiffPane.class.getMethod("requestClose");
        Method mandatory = SchemaDiffPane.class.getMethod("requestMandatoryClose");
        Method blocking = SchemaDiffPane.class.getMethod("closeResources");
        Method finalizer = SchemaDiffPane.class.getMethod("finalizeCloseOnFx");

        assertEquals(CompletionStage.class, interactive.getReturnType());
        assertEquals(CompletionStage.class, mandatory.getReturnType());
        assertEquals(void.class, blocking.getReturnType());
        assertEquals(void.class, finalizer.getReturnType());

        String source = Files.readString(Path.of("src/com/datacube/fx/SchemaDiffPane.java"));
        int finalizerStart = source.indexOf("void finalizeCloseOnFx()");
        String finalizerBody = source.substring(finalizerStart,
                source.indexOf("private", finalizerStart));
        assertFalse(finalizerBody.contains("closeResources"));
        assertFalse(finalizerBody.contains("join("));
        assertFalse(finalizerBody.contains("await"));
    }

    @Test
    void confirmationSummaryIsFixedStructuredAndRedactsUnsafeFields() {
        SchemaDiffViewModel.Confirmation confirmation = new SchemaDiffViewModel.Confirmation(
                7, "target-name [target-id]", "TARGET_SCHEMA", "TARGET_SCHEMA", 3,
                true, true, true, "d".repeat(64));

        String summary = SchemaDiffDialogs.confirmationSummary(confirmation);

        assertTrue(summary.contains("target-name [target-id]"));
        assertTrue(summary.contains("TARGET_SCHEMA"));
        assertTrue(summary.contains("3"));
        assertTrue(summary.contains("生产"));
        assertTrue(summary.contains("Oracle DDL 会隐式提交"));
        assertFalse(summary.contains("d".repeat(64)));
        assertFalse(summary.toLowerCase().contains("jdbc:"));
        assertFalse(summary.toLowerCase().contains("password"));
        assertFalse(summary.contains("DROP "));
    }

    @Test
    void modelAndDisplayTypesKeepSafeToStringContracts() throws Exception {
        String pane = Files.readString(Path.of("src/com/datacube/fx/SchemaDiffPane.java"));
        String model = Files.readString(Path.of("src/com/datacube/fx/SchemaDiffViewModel.java"));

        assertTrue(pane.contains("record ConnectionChoice"));
        assertTrue(pane.contains("return name + \" (\" + type.displayName() + \")\""));
        assertFalse(pane.contains("ComboBox<ConnConfig>"));
        assertFalse(model.contains("exception.getMessage()"));
        assertFalse(model.contains("failure.getMessage()"));
    }

    private static ConnConfig config(String id, DbType type) {
        return new ConnConfig(id, id, type, "host", type.defaultPort(),
                "database", "user", "encrypted", Map.of());
    }
}
