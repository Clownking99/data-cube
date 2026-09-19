package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.ResultFilterState;
import java.nio.file.Path;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlOverviewResultNavigationTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }

    @ParameterizedTest @ValueSource(strings = {"query", "empty", "update", "unknownCount", "error", "timeout", "cancelled"})
    void sortedOverviewOpensExactResultAndPreservesEditorAndSource(String kind) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                QueryResult target = switch (kind) {
                    case "query" -> QueryResult.query(List.of("value"), List.of(List.of("target")), 3);
                    case "empty" -> QueryResult.query(List.of("value"), List.of(), 3);
                    case "update" -> QueryResult.update(3, 7);
                    case "unknownCount" -> QueryResult.update(3, -1);
                    case "timeout" -> QueryResult.timeout("synthetic timeout", 3);
                    case "cancelled" -> QueryResult.cancelled("synthetic cancellation", 3);
                    default -> QueryResult.error("synthetic error", 3);
                };
                invoke(f.pane, "showScriptResults", new Class<?>[]{List.class, long.class, String.class}, List.of(
                        new ScriptOutcome(4, "same SQL", QueryResult.query(List.of("value"), List.of(List.of("first")), 1)),
                        new ScriptOutcome(90, "same SQL", target),
                        new ScriptOutcome(7, "last SQL", QueryResult.update(1, 0))), 5L, "ORIGINAL_SCHEMA");
                var state = (ResultFilterState) field(f.pane, "resultFilterState");
                state.setSearchText("first"); invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{});
                chooser(f).getSelectionModel().select(0);
                assertTrue(button(f).isDisabled());
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(9, 15); String text = f.editor.getText();
                var column = f.table().getColumns().getFirst(); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort(); f.select(0);
                assertEquals(90, f.table().getItems().getFirst().getFirst()); assertFalse(button(f).isDisabled());
                button(f).fire();
                assertSame(target, chooser(f).getValue().outcome().result());
                assertEquals(2, chooser(f).getSelectionModel().getSelectedIndex());
                assertFalse(f.details().getNode().isVisible()); assertNull(f.dialog());
                if (target.kind == QueryResult.Kind.QUERY) {
                    assertEquals(target.rows, f.table().getItems());
                    assertSame(target, state.snapshot().originalResult()); assertEquals("", state.snapshot().searchText());
                    assertEquals("ORIGINAL_SCHEMA", state.snapshot().effectiveSchema());
                    assertEquals("same SQL", f.pane.captureResultExportSnapshot().originalSql());
                    assertEquals(List.of("value"), f.pane.captureResultExportSnapshot().columns());
                } else {
                    assertNull(f.pane.captureResultExportSnapshot());
                    String value = f.table().getItems().getFirst().getFirst().toString();
                    if (target.kind == QueryResult.Kind.ERROR) assertTrue(value.contains(target.errorMessage));
                    else assertEquals(target.updateCount < 0 ? "影响行数未提供" : "影响 7 行", value);
                }
                assertEquals(text, f.editor.getText()); assertEquals(9, f.editor.getAnchor()); assertEquals(15, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); assertFalse(f.document().dirty());
                assertFalse(f.editor.isUndoAvailable()); return null;
            });
            f.assertOffline();
        }
    }

    @Test void noSelectionAndForeignRowCannotNavigateAndEnterStillShowsDetails() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.batch(); var overview = chooser(f).getValue(); var rows = f.table().getItems();
                assertTrue(button(f).isDisabled()); button(f).getOnAction().handle(new ActionEvent());
                assertSame(overview, chooser(f).getValue()); assertSame(rows, f.table().getItems());
                f.select(1); f.table().fireEvent(key(KeyCode.ENTER, false));
                assertNotNull(f.dialog()); assertSame(overview, chooser(f).getValue()); f.dialog().close();
                var clone = FXCollections.observableArrayList(rows.get(1));
                f.table().setItems(FXCollections.observableArrayList(List.of(clone))); f.select(0);
                assertTrue(button(f).isDisabled()); button(f).getOnAction().handle(new ActionEvent());
                assertSame(overview, chooser(f).getValue()); assertEquals(List.of(clone), f.table().getItems());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue"})
    void actionTimeGuardsPreventNavigationWhileBlocked(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.batch(); f.select(1); var action = button(f).getOnAction();
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "tableDisabled" -> f.table().setDisable(true);
                    case "running" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                }
                var before = chooser(f).getValue(); var rows = f.table().getItems();
                action.handle(new ActionEvent()); assertSame(before, chooser(f).getValue()); assertSame(rows, f.table().getItems());
                assertNull(f.dialog());
                if (blocker.equals("running")) {
                    invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(button(f).isDisabled()); button(f).fire();
                    assertEquals(2, chooser(f).getValue().outcome().index());
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "empty", "error", "plan", "clear", "batch", "close"})
    void oldActionCannotRestoreReplacedResult(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.batch(); f.select(1); var action = button(f).getOnAction();
                switch (replacement) {
                    case "query" -> f.show(List.of(new ScriptOutcome(1, "fresh", QueryResult.query(List.of("n"), List.of(List.of(42)), 1))));
                    case "empty" -> f.show(List.of());
                    case "error" -> invoke(f.pane, "showError", new Class<?>[]{String.class, long.class}, "new error", 1L);
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 1);
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                    case "batch" -> f.show(List.of(new ScriptOutcome(1, "new 1", QueryResult.update(1, 6)), new ScriptOutcome(2, "new 2", QueryResult.update(1, 8))));
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                var before = chooser(f).getValue(); var rows = f.table().getItems();
                action.handle(new ActionEvent()); assertSame(before, chooser(f).getValue()); assertSame(rows, f.table().getItems());
                assertTrue(button(f).isDisabled());
                if (replacement.equals("batch")) {
                    f.select(1); action.handle(new ActionEvent());
                    assertEquals("new 2", chooser(f).getValue().outcome().sql());
                    assertEquals("影响 8 行", f.table().getItems().getFirst().getFirst().toString());
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "480,light", "880,dark", "880,light"})
    void overviewActionsFitNarrowAndWideThemes(double width, String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, width, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.batch(); f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                for (var node : List.of(button(f), f.button())) {
                    var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1);
                    assertTrue(node.getWidth() + 1 >= node.prefWidth(-1));
                }
                assertTrue(f.details().getNode().getHeight() >= button(f).getHeight());
                assertTrue(f.table().getHeight() >= 100); return null;
            });
        }
    }

    private static Button button(SqlScriptDetailsIntegrationTest.Fixture f) {
        var button = (Button) f.root.lookup("#sql-script-result"); assertNotNull(button, "overview needs direct result navigation"); return button;
    }

    @Test void identicalEntriesAreMatchedByIdentityAndStaleOrForeignEntriesCannotNavigate() throws Exception {
        FxUiTestSupport.call(() -> {
            var chosen = new java.util.concurrent.atomic.AtomicReference<SqlBatchResults.Choice>();
            var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            try (var batch = new SqlBatchResults(allowed::get, chosen::set)) {
                new Scene(batch.getNode(), 640, 160); batch.getNode().applyCss(); batch.getNode().layout();
                var first = QueryResult.query(List.of("v"), List.of(List.of("one")), 1);
                var second = QueryResult.query(List.of("v"), List.of(List.of("two")), 1);
                var outcomes = List.of(new ScriptOutcome(7, "same", first), new ScriptOutcome(7, "same", second));
                batch.display(outcomes, 2, "s");
                var choices = (ComboBox<?>) batch.getNode().lookup("#sql-batch-choice");
                var entries = batch.report().entries(); assertEquals(entries.get(0), entries.get(1)); assertNotSame(entries.get(0), entries.get(1));
                choices.getSelectionModel().select(0); var overview = chosen.get();
                var foreign = com.datacube.sqleditor.SqlScriptExecutionReport.capture(outcomes, 2).entries().get(1);
                assertEquals(entries.get(1), foreign);
                batch.selectResult(foreign); batch.selectResult(null); assertSame(overview, chosen.get());
                allowed.set(false); batch.selectResult(entries.get(1)); assertSame(overview, chosen.get()); allowed.set(true);
                batch.getNode().setDisable(true); batch.selectResult(entries.get(1)); assertSame(overview, chosen.get()); batch.getNode().setDisable(false);
                batch.selectResult(entries.get(1)); assertSame(second, chosen.get().outcome().result());
                batch.selectResult(entries.get(0)); assertSame(second, chosen.get().outcome().result(), "only the overview may navigate by entry");
                choices.getSelectionModel().select(0); batch.selectResult(entries.get(0)); assertSame(first, chosen.get().outcome().result());
                batch.display(outcomes, 2, "new"); choices.getSelectionModel().select(0); var fresh = chosen.get();
                batch.selectResult(entries.get(1)); assertSame(fresh, chosen.get());
                var current = batch.report().entries().get(1); batch.clear(); var before = chosen.get();
                batch.selectResult(current); assertSame(before, chosen.get()); assertTrue(choices.getItems().isEmpty());
                batch.close(); batch.selectResult(current); assertSame(before, chosen.get());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {999, 1000, 1001})
    void navigationUsesOnlyRetainedEntries(int count) throws Exception {
        FxUiTestSupport.call(() -> {
            var chosen = new java.util.concurrent.atomic.AtomicReference<SqlBatchResults.Choice>();
            try (var batch = new SqlBatchResults(() -> true, chosen::set)) {
                new Scene(batch.getNode(), 640, 160); batch.getNode().applyCss(); batch.getNode().layout();
                var outcomes = new java.util.ArrayList<ScriptOutcome>();
                for (int i = 1; i <= count; i++) outcomes.add(new ScriptOutcome(i, "synthetic", QueryResult.update(1, i)));
                batch.display(outcomes, count, "s");
                var last = batch.report().entries().getLast(); batch.selectResult(last);
                assertSame(outcomes.get(Math.min(count, 1000) - 1), chosen.get().outcome());
                assertSame(last, chosen.get().detail()); assertEquals(Math.min(count, 1000), last.index());
                if (count > 1000) {
                    var choices = (ComboBox<?>) batch.getNode().lookup("#sql-batch-choice"); choices.getSelectionModel().select(0); var before = chosen.get();
                    var omitted = com.datacube.sqleditor.SqlScriptExecutionReport.capture(List.of(outcomes.getLast()), 1).entries().getFirst();
                    batch.selectResult(omitted); assertSame(before, chosen.get());
                }
            }
            return null;
        });
    }

    @Test void equalChoicesStillNavigateToTheSelectedEntryIdentity() throws Exception {
        FxUiTestSupport.call(() -> {
            var chosen = new java.util.concurrent.atomic.AtomicReference<SqlBatchResults.Choice>();
            try (var batch = new SqlBatchResults(() -> true, chosen::set)) {
                new Scene(batch.getNode(), 640, 160); batch.getNode().applyCss(); batch.getNode().layout();
                var repeated = new ScriptOutcome(7, "same", QueryResult.update(1, 2));
                batch.display(List.of(repeated, repeated), 2, "s");
                var second = batch.report().entries().get(1); batch.selectResult(second);
                assertSame(second, chosen.get().detail());
                assertEquals(2, ((ComboBox<?>) batch.getNode().lookup("#sql-batch-choice")).getSelectionModel().getSelectedIndex());
            }
            return null;
        });
    }
    @SuppressWarnings("unchecked") private static ComboBox<SqlBatchResults.Choice> chooser(SqlScriptDetailsIntegrationTest.Fixture f) {
        return (ComboBox<SqlBatchResults.Choice>) f.root.lookup("#sql-batch-choice");
    }
}
