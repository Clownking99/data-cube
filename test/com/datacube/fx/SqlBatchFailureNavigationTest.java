package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    @ParameterizedTest @CsvSource({
            "next,0,3,5", "next,1,3,5", "next,2,3,5", "next,3,5,6", "next,4,5,6", "next,5,6,3", "next,6,3,5",
            "previous,0,6,5", "previous,1,6,5", "previous,2,6,5", "previous,3,6,5", "previous,4,3,6", "previous,5,3,6", "previous,6,5,3"})
    void failureNavigationUsesExecutionOrderAndWrapsWithoutExecuting(String direction, int start, int target, int following) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(outcomes()); choice(f).getSelectionModel().select(start);
                f.editor.selectRange(5, 9); String sql = f.editor.getText();
                assertFalse(navigation(f, direction).isDisabled()); navigation(f, direction).fire();
                assertEquals(target, choice(f).getSelectionModel().getSelectedIndex());
                assertEquals(List.of(outcomes().get(target - 1).result().errorMessage), f.table().getItems().getFirst());
                assertNull(f.pane.captureResultExportSnapshot()); assertNull(dialog(f), "navigation must not pop a dialog");
                assertTrue(((Label) f.root.lookup("#sql-batch-summary")).getText().contains("失败 1 · 超时 1 · 取消 1"));
                ((Button) f.root.lookup("#sql-batch-details")).fire(); var shown = dialog(f); assertNotNull(shown);
                assertTrue(((Label) shown.getDialogPane().lookup("#sql-script-detail-identity")).getText().startsWith("语句 #" + outcomes().get(target - 1).index()));
                assertEquals(outcomes().get(target - 1).result().errorMessage, ((TextArea) shown.getDialogPane().lookup("#sql-script-detail-error")).getText());
                navigation(f, direction).fire(); assertFalse(shown.isShowing()); assertNull(dialog(f));
                assertEquals(following, choice(f).getSelectionModel().getSelectedIndex());
                assertEquals(sql, f.editor.getText()); assertEquals(5, f.editor.getAnchor()); assertEquals(9, f.editor.getCaretPosition());
                assertFalse(f.editor.isUndoAvailable()); assertFalse(f.document().dirty()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"next", "previous"})
    void uniqueFailureIsReachableOnceAndDoesNotResetCurrentDetails(String direction) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(List.of(outcomes().getFirst(), outcomes().get(2)));
                navigation(f, direction).fire(); assertEquals(2, choice(f).getSelectionModel().getSelectedIndex());
                assertTrue(navigation(f, "next").isDisabled()); assertTrue(navigation(f, "previous").isDisabled());
                ((Button) f.root.lookup("#sql-batch-details")).fire(); var shown = dialog(f); var rows = f.table().getItems();
                navigation(f, direction).getOnAction().handle(new javafx.event.ActionEvent()); assertSame(shown, dialog(f)); assertTrue(shown.isShowing());
                assertSame(rows, f.table().getItems()); shown.close();
                choice(f).getSelectionModel().select(0); assertFalse(navigation(f, direction).isDisabled()); navigation(f, direction).fire();
                assertEquals(2, choice(f).getSelectionModel().getSelectedIndex()); return null;
            });
            f.assertOffline();
        }
    }

    private static Stream<Arguments> replacements() {
        return directions("empty", "single", "normal", "error", "plan", "clear", "close");
    }
    private static Stream<Arguments> blockers() {
        return directions("resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue", "busy");
    }
    private static Stream<Arguments> directions(String... states) {
        return Stream.of("next", "previous").flatMap(direction -> Stream.of(states).map(state -> Arguments.of(direction, state)));
    }

    @ParameterizedTest @MethodSource("replacements")
    void replacingBatchDisablesOldNavigationAndPreservesNewResult(String direction, String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(outcomes()); var action = navigation(f, direction).getOnAction();
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
                assertTrue(navigation(f, direction).isDisabled()); action.handle(new javafx.event.ActionEvent());
                assertSame(rows, f.table().getItems()); assertSame(selected, choice(f).getValue()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @MethodSource("blockers")
    void staleActionsCannotNavigateBlockedPane(String direction, String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(outcomes()); var action = navigation(f, direction).getOnAction(); var rows = f.table().getItems();
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
                    assertTrue(navigation(f, direction).isDisabled()); invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(navigation(f, direction).isDisabled()); navigation(f, direction).fire();
                    assertEquals(direction.equals("next") ? 3 : 6, choice(f).getSelectionModel().getSelectedIndex());
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"next", "previous"})
    void actionRecomputesTargetFromNewBatchInsteadOfCapturedIndex(String direction) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(outcomes()); var action = navigation(f, direction).getOnAction();
                f.show(List.of(new ScriptOutcome(101, "new sql", QueryResult.error("new failure", 7)), outcomes().getFirst()));
                assertEquals(2, choice(f).getSelectionModel().getSelectedIndex()); action.handle(new javafx.event.ActionEvent());
                assertEquals(1, choice(f).getSelectionModel().getSelectedIndex()); assertEquals(List.of("new failure"), f.table().getItems().getFirst());
                assertTrue(navigation(f, direction).isDisabled()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"next", "previous"})
    void overviewSortingAndFilteringDoNotChangeNavigationOrder(String direction) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(outcomes()); choice(f).getSelectionModel().select(0);
                var column = f.table().getColumns().getFirst(); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
                ((CheckBox) f.root.lookup("#sql-script-only-failures")).fire();
                assertEquals(List.of(60, 50, 30), f.table().getItems().stream().map(row -> row.getFirst()).toList());
                f.select(1); navigation(f, direction).fire();
                int target = direction.equals("next") ? 3 : 6;
                assertEquals(target, choice(f).getSelectionModel().getSelectedIndex());
                assertEquals(List.of(outcomes().get(target - 1).result().errorMessage), f.table().getItems().getFirst());
                assertNull(dialog(f)); assertEquals(f.original, f.document().physicalText()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "480,light", "640,dark", "640,light"})
    void bothDirectionsAndDetailsFitWithoutShrinkingResultSelector(double width, String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new javafx.scene.Scene(f.root, width, 850).getStylesheets().addAll(
                        ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.show(outcomes()); f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                var previous = navigation(f, "previous"); var next = navigation(f, "next");
                var details = (Button) f.root.lookup("#sql-batch-details");
                var row = (javafx.scene.layout.HBox) previous.getParent();
                var rootBounds = f.root.localToScene(f.root.getLayoutBounds());
                var rowBounds = row.localToScene(row.getLayoutBounds());
                assertTrue(rowBounds.getMinX() >= rootBounds.getMinX() - 1);
                assertTrue(rowBounds.getMaxX() <= rootBounds.getMaxX() + 1, "bar must fit the editor width");
                double right = 0;
                for (var child : row.getChildren()) {
                    var bounds = child.getBoundsInParent();
                    assertTrue(bounds.getMinX() >= right - 1, "controls must not overlap");
                    assertTrue(bounds.getMaxX() <= row.getWidth() + 1, "controls must fit in the result bar");
                    right = bounds.getMaxX();
                }
                assertTrue(choice(f).getWidth() >= 100);
                assertEquals("上一异常", previous.getText()); assertEquals("下一异常", next.getText());
                for (var button : List.of(previous, next, details)) {
                    assertTrue(button.isVisible()); assertTrue(button.isManaged()); assertTrue(button.isFocusTraversable());
                    assertTrue(button.getWidth() >= button.prefWidth(-1) - 1, "button labels must fit");
                }
                assertTrue(previous.getBoundsInParent().getMaxX() < next.getBoundsInParent().getMinX());
                assertTrue(next.getBoundsInParent().getMaxX() < details.getBoundsInParent().getMinX());
                return null;
            });
            f.assertOffline();
        }
    }
    private static Button navigation(SqlScriptDetailsIntegrationTest.Fixture f, String direction) {
        var button = (Button) f.root.lookup("#sql-batch-" + direction + "-failure");
        assertNotNull(button, "Batch failure summary needs " + direction + " navigation"); return button;
    }
    private static ComboBox<?> choice(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<?>) f.root.lookup("#sql-batch-choice"); }
    private static SqlScriptDetailsDialog dialog(SqlScriptDetailsIntegrationTest.Fixture f) { return (SqlScriptDetailsDialog) field(field(f.pane, "batchResults"), "dialog"); }
}
