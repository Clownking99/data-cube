package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffPaneContractTest {

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
        assertTrue(source.contains("if (!viewModel.setSelected(entry.change().id(), selected))"));
        assertTrue(source.contains("item.setSelected(entry.selected())"));
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
}
