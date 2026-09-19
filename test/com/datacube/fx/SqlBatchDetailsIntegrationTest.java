package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.SqlScriptExecutionReport;
import com.datacube.sqleditor.result.ResultFilterState;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlBatchDetailsIntegrationTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static List<ScriptOutcome> outcomes() {
        return List.of(
                new ScriptOutcome(7, "select value from sample", QueryResult.query(List.of("value"), List.of(List.of("alpha"), List.of("beta")), 2)),
                new ScriptOutcome(12, "update sample set value='x'", QueryResult.update(3, 4)),
                new ScriptOutcome(14, "select missing from sample", QueryResult.error("<script>literal</script>\nfull error\nSQLState=42000", 5)),
                new ScriptOutcome(19, "select slow from sample", QueryResult.timeout("synthetic timeout", 6)),
                new ScriptOutcome(27, "select cancelled from sample", QueryResult.cancelled("synthetic cancellation", 7)));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void directDetailsUseReturnedOutcomeAndPreserveEditorAndResult(int selection) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(outcomes()); choice(f).getSelectionModel().select(selection);
                if (selection == 1) {
                    ((ResultFilterState) field(f.pane, "resultFilterState")).setSearchText("beta");
                    invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{});
                }
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(9, 15);
                String text = f.editor.getText(); var rows = f.table().getItems(); var columns = List.copyOf(f.table().getColumns());
                var export = f.pane.captureResultExportSnapshot();
                assertFalse(button(f).isDisabled()); button(f).fire(); var dialog = dialog(f);
                assertNotNull(dialog); assertTrue(dialog.isShowing());
                var expected = outcomes().get(selection - 1);
                var sql = (TextArea) dialog.getDialogPane().lookup("#sql-script-detail-sql");
                assertEquals(expected.sql(), sql.getText()); assertFalse(sql.isEditable());
                var identity = ((Label) dialog.getDialogPane().lookup("#sql-script-detail-identity")).getText();
                assertTrue(identity.startsWith("语句 #" + expected.index() + " · "));
                if (selection > 2) {
                    var error = (TextArea) dialog.getDialogPane().lookup("#sql-script-detail-error");
                    assertEquals(expected.result().errorMessage, error.getText()); assertFalse(error.isEditable());
                    assertTrue(identity.contains(switch (selection) { case 3 -> "失败"; case 4 -> "超时"; default -> "取消"; }));
                } else {
                    assertNull(dialog.getDialogPane().lookup("#sql-script-detail-error"));
                    assertTrue(identity.contains(selection == 1 ? "已加载 2 行" : "影响 4 行"));
                }
                SqlScriptDetailFindTest.query(dialog).setText("sample"); SqlScriptDetailFindTest.next(dialog).fire();
                assertEquals("sample", sql.getSelectedText()); assertEquals(expected.sql(), sql.getText());
                button(f).getOnAction().handle(new javafx.event.ActionEvent()); assertSame(dialog, dialog(f));
                dialog.getDialogPane().fireEvent(key(KeyCode.ESCAPE, false)); assertFalse(dialog.isShowing()); assertNull(dialog(f));
                assertSame(rows, f.table().getItems()); assertEquals(columns, f.table().getColumns());
                assertEquals(selection, choice(f).getSelectionModel().getSelectedIndex());
                assertEquals(export == null, f.pane.captureResultExportSnapshot() == null);
                if (selection == 1) {
                    assertEquals(List.of(List.of("beta")), f.table().getItems());
                    assertEquals(expected.sql(), f.pane.captureResultExportSnapshot().originalSql());
                }
                assertEquals(text, f.editor.getText()); assertEquals(9, f.editor.getAnchor()); assertEquals(15, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); assertFalse(f.document().dirty()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"selection", "overview", "single", "empty", "error", "plan", "batch", "busy", "disabled", "close"})
    void changingResultOrLifecycleClosesOldDetails(String change) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(outcomes()); button(f).fire(); var opened = dialog(f); assertNotNull(opened);
                SqlScriptDetailFindTest.query(opened).setText("sample"); SqlScriptDetailFindTest.next(opened).fire();
                var selectedSql = SqlScriptDetailFindTest.sql(opened).getSelection();
                switch (change) {
                    case "selection" -> choice(f).getSelectionModel().select(3);
                    case "overview" -> choice(f).getSelectionModel().select(0);
                    case "single" -> f.show(List.of(outcomes().getFirst()));
                    case "empty" -> f.show(List.of());
                    case "error" -> invoke(f.pane, "showError", new Class<?>[]{String.class, long.class}, "new", 1L);
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 1);
                    case "batch" -> f.show(outcomes());
                    case "busy" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                    case "disabled" -> f.root.setDisable(true);
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                assertFalse(opened.isShowing()); assertNull(dialog(f));
                SqlScriptDetailFindTest.next(opened).getOnAction().handle(new javafx.event.ActionEvent());
                assertTrue(SqlScriptDetailFindTest.next(opened).isDisabled());
                assertEquals(selectedSql, SqlScriptDetailFindTest.sql(opened).getSelection());
                if (change.equals("overview")) { assertTrue(button(f).isDisabled()); assertTrue(f.details().getNode().isVisible()); }
                if (change.equals("selection") || change.equals("batch")) {
                    button(f).fire(); assertNotSame(opened, dialog(f)); assertTrue(dialog(f).isShowing());
                }
                if (change.equals("busy")) {
                    assertTrue(button(f).isDisabled()); invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(button(f).isDisabled()); button(f).fire(); assertNotNull(dialog(f));
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue", "overview"})
    void staleButtonActionCannotBypassPaneGuards(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(outcomes()); var action = button(f).getOnAction();
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((com.datacube.fx.task.FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "tableDisabled" -> f.table().setDisable(true);
                    case "running" -> setField(f.pane, "running", true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                    case "overview" -> choice(f).getSelectionModel().select(0);
                }
                action.handle(new javafx.event.ActionEvent()); assertNull(dialog(f));
                if (blocker.equals("running")) setField(f.pane, "running", false); return null;
            });
            f.assertOffline();
        }
    }

    @Test void boundedSnapshotIsReusedAndOldChoicesCannotSupplyNewDetails() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(List.of(new ScriptOutcome(7, "s".repeat(20_000), QueryResult.error("e".repeat(20_000), 1)), outcomes().getFirst()));
                choice(f).getSelectionModel().select(1); Object stale = choice(f).getValue(); button(f).fire();
                var dialog = dialog(f); assertNotNull(dialog);
                assertEquals("s".repeat(SqlScriptExecutionReport.MAX_FIELD_UNITS), ((TextArea) dialog.getDialogPane().lookup("#sql-script-detail-sql")).getText());
                assertEquals("e".repeat(SqlScriptExecutionReport.MAX_FIELD_UNITS), ((TextArea) dialog.getDialogPane().lookup("#sql-script-detail-error")).getText());
                assertTrue(((Label) dialog.getDialogPane().lookup("#sql-script-detail-sql-label")).getText().contains("显示上限"));
                dialog.close(); f.show(outcomes()); selectForeign(choice(f), stale);
                button(f).fire(); assertEquals(outcomes().getFirst().sql(), ((TextArea) dialog(f).getDialogPane().lookup("#sql-script-detail-sql")).getText());
                return null;
            });
            f.assertOffline();
        }
    }
    private static Button button(SqlScriptDetailsIntegrationTest.Fixture f) {
        var button = (Button) f.root.lookup("#sql-batch-details"); assertNotNull(button, "Current batch outcome needs a direct details entry"); return button;
    }
    private static ComboBox<?> choice(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<?>) f.root.lookup("#sql-batch-choice"); }
    private static SqlScriptDetailsDialog dialog(SqlScriptDetailsIntegrationTest.Fixture f) { return (SqlScriptDetailsDialog) field(field(f.pane, "batchResults"), "dialog"); }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static void selectForeign(ComboBox choice, Object stale) { choice.setValue(stale); }
}
