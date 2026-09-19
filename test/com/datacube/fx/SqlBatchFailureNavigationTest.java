package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlBatchFailureNavigationTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static List<ScriptOutcome> outcomes() {
        return List.of(
                new ScriptOutcome(10, "select a", QueryResult.query(List.of("a"), List.of(List.of(1)), 1)),
                new ScriptOutcome(20, "update a", QueryResult.update(2, 5)),
                new ScriptOutcome(30, "same sql", QueryResult.error("first failure", 3)),
                new ScriptOutcome(40, "select b", QueryResult.query(List.of("b"), List.of(List.of(2)), 4)),
                new ScriptOutcome(50, "same sql", QueryResult.timeout("timed out", 5)),
                new ScriptOutcome(60, "same sql", QueryResult.cancelled("cancelled", 6)));
    }

    @ParameterizedTest @CsvSource({"0,3", "1,3", "2,3", "3,5", "4,5", "5,6", "6,3"})
    void nextFailureUsesExecutionOrderAndWrapsWithoutExecuting(int start, int target) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(outcomes()); choice(f).getSelectionModel().select(start);
                f.editor.selectRange(5, 9); String sql = f.editor.getText();
                assertFalse(next(f).isDisabled()); next(f).fire();
                assertEquals(target, choice(f).getSelectionModel().getSelectedIndex());
                assertEquals(List.of(outcomes().get(target - 1).result().errorMessage), f.table().getItems().getFirst());
                assertNull(f.pane.captureResultExportSnapshot()); assertNull(dialog(f), "navigation must not pop a dialog");
                assertTrue(((Label) f.root.lookup("#sql-batch-summary")).getText().contains("失败 1 · 超时 1 · 取消 1"));
                ((Button) f.root.lookup("#sql-batch-details")).fire(); var shown = dialog(f); assertNotNull(shown);
                assertTrue(((Label) shown.getDialogPane().lookup("#sql-script-detail-identity")).getText().startsWith("语句 #" + outcomes().get(target - 1).index()));
                assertEquals(outcomes().get(target - 1).result().errorMessage, ((TextArea) shown.getDialogPane().lookup("#sql-script-detail-error")).getText());
                next(f).fire(); assertFalse(shown.isShowing()); assertNull(dialog(f));
                assertEquals(target == 3 ? 5 : target == 5 ? 6 : 3, choice(f).getSelectionModel().getSelectedIndex());
                assertEquals(sql, f.editor.getText()); assertEquals(5, f.editor.getAnchor()); assertEquals(9, f.editor.getCaretPosition());
                assertFalse(f.editor.isUndoAvailable()); assertFalse(f.document().dirty()); return null;
            });
            f.assertOffline();
        }
    }

    @Test void uniqueFailureIsReachableOnceAndDoesNotResetCurrentDetails() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(List.of(outcomes().getFirst(), outcomes().get(2)));
                next(f).fire(); assertEquals(2, choice(f).getSelectionModel().getSelectedIndex()); assertTrue(next(f).isDisabled());
                ((Button) f.root.lookup("#sql-batch-details")).fire(); var shown = dialog(f); var rows = f.table().getItems();
                next(f).getOnAction().handle(new javafx.event.ActionEvent()); assertSame(shown, dialog(f)); assertTrue(shown.isShowing());
                assertSame(rows, f.table().getItems()); shown.close();
                choice(f).getSelectionModel().select(0); assertFalse(next(f).isDisabled()); next(f).fire();
                assertEquals(2, choice(f).getSelectionModel().getSelectedIndex()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "single", "normal", "error", "plan", "clear", "close"})
    void replacingBatchDisablesOldNavigationAndPreservesNewResult(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(outcomes()); var action = next(f).getOnAction();
                switch (replacement) {
                    case "empty" -> f.show(List.of());
                    case "single" -> f.show(List.of(outcomes().getFirst()));
                    case "normal" -> f.show(List.of(outcomes().getFirst(), outcomes().get(1)));
                    case "error" -> invoke(f.pane, "showError", new Class<?>[]{String.class, long.class}, "new", 1L);
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 1);
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                var rows = f.table().getItems(); Object selected = choice(f).getValue();
                assertTrue(next(f).isDisabled()); action.handle(new javafx.event.ActionEvent());
                assertSame(rows, f.table().getItems()); assertSame(selected, choice(f).getValue()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue", "busy"})
    void staleActionsCannotNavigateBlockedPane(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(outcomes()); var action = next(f).getOnAction(); var rows = f.table().getItems();
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((com.datacube.fx.task.FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "tableDisabled" -> f.table().setDisable(true);
                    case "running" -> setField(f.pane, "running", true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                    case "busy" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                }
                Object before = choice(f).getValue(); action.handle(new javafx.event.ActionEvent());
                assertSame(before, choice(f).getValue()); assertSame(rows, f.table().getItems()); assertEquals(List.of(1), rows.getFirst());
                if (blocker.equals("running")) setField(f.pane, "running", false);
                if (blocker.equals("busy")) {
                    assertTrue(next(f).isDisabled()); invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(next(f).isDisabled()); next(f).fire(); assertEquals(3, choice(f).getSelectionModel().getSelectedIndex());
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void actionRecomputesTargetFromNewBatchInsteadOfCapturedIndex() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(outcomes()); var action = next(f).getOnAction();
                f.show(List.of(new ScriptOutcome(101, "new sql", QueryResult.error("new failure", 7)), outcomes().getFirst()));
                assertEquals(2, choice(f).getSelectionModel().getSelectedIndex()); action.handle(new javafx.event.ActionEvent());
                assertEquals(1, choice(f).getSelectionModel().getSelectedIndex()); assertEquals(List.of("new failure"), f.table().getItems().getFirst());
                assertTrue(next(f).isDisabled()); return null;
            });
            f.assertOffline();
        }
    }
    private static Button next(SqlScriptDetailsIntegrationTest.Fixture f) {
        var button = (Button) f.root.lookup("#sql-batch-next-failure"); assertNotNull(button, "Batch failure summary needs a navigation action"); return button;
    }
    private static ComboBox<?> choice(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<?>) f.root.lookup("#sql-batch-choice"); }
    private static SqlScriptDetailsDialog dialog(SqlScriptDetailsIntegrationTest.Fixture f) { return (SqlScriptDetailsDialog) field(field(f.pane, "batchResults"), "dialog"); }
}
