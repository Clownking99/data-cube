package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlOverviewFailureFilterTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static void mixed(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        f.show(List.of(new ScriptOutcome(1, "same", QueryResult.query(List.of("n"), List.of(List.of(1)), 1)),
                new ScriptOutcome(2, "same", QueryResult.error("first error", 2)),
                new ScriptOutcome(3, "same", QueryResult.update(3, 9)),
                new ScriptOutcome(4, "same", QueryResult.timeout("timeout", 4)),
                new ScriptOutcome(5, "same", QueryResult.cancelled("cancelled", 5)),
                new ScriptOutcome(6, "same", QueryResult.error("last error", 6))));
        chooser(f).getSelectionModel().select(0);
    }

    @ParameterizedTest @ValueSource(ints = {2, 3})
    void filterKeepsSortAndVisibleIdentityButNeverReassignsHiddenSelection(int selectedIndex) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); assertFalse(filter(f).isSelected()); assertEquals("显示 6 / 6 条已保留结果", count(f).getText());
                var choices = List.copyOf(chooser(f).getItems()); var overview = chooser(f).getValue();
                String summary = ((Label) f.root.lookup("#sql-batch-summary")).getText();
                var column = f.table().getColumns().getFirst(); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
                f.select(6 - selectedIndex); var selected = f.details().selectedEntry();
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(9, 15); String text = f.editor.getText();
                filter(f).fire();
                assertEquals(List.of(6, 5, 4, 2), indices(f)); assertEquals("显示 4 / 6 条已保留结果", count(f).getText());
                assertEquals(selectedIndex == 3, f.button().isDisabled());
                assertEquals(selectedIndex == 3, result(f).isDisabled());
                if (selectedIndex == 2) assertSame(selected, f.details().selectedEntry());
                else { assertNull(f.details().selectedEntry()); assertTrue(f.table().getSelectionModel().isEmpty()); }
                assertSame(overview, chooser(f).getValue()); assertEquals(choices, chooser(f).getItems());
                assertEquals(summary, ((Label) f.root.lookup("#sql-batch-summary")).getText()); assertNull(f.pane.captureResultExportSnapshot());
                filter(f).fire(); assertEquals(List.of(6, 5, 4, 3, 2, 1), indices(f));
                if (selectedIndex == 2) assertSame(selected, f.details().selectedEntry()); else assertNull(f.details().selectedEntry());
                assertEquals(text, f.editor.getText()); assertEquals(9, f.editor.getAnchor()); assertEquals(15, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"result", "details"})
    void filteredSortedRowOpensItsOriginalResultOrDetails(String action) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); mixed(f); filter(f).fire();
                var column = f.table().getColumns().getFirst(); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort(); f.select(0);
                assertEquals(6, f.details().selectedEntry().index());
                if (action.equals("result")) {
                    result(f).fire(); assertEquals(6, chooser(f).getValue().outcome().index());
                    assertEquals("last error", f.table().getItems().getFirst().getFirst());
                    assertFalse(f.details().getNode().isVisible()); assertFalse(filter(f).isSelected());
                    chooser(f).getSelectionModel().select(0); assertEquals(6, f.table().getItems().size());
                } else {
                    f.table().fireEvent(key(KeyCode.ENTER, false)); var dialog = f.dialog(); assertTrue(dialog.isShowing());
                    assertEquals("last error", ((javafx.scene.control.TextArea) dialog.getDialogPane().lookup("#sql-script-detail-error")).getText());
                    filter(f).fire(); assertFalse(dialog.isShowing()); assertNull(f.dialog()); assertEquals(6, f.details().selectedEntry().index());
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 999, 1000, 1001})
    void zeroMatchesDescribeRetainedScopeEvenIfAnOmittedOutcomeFailed(int count) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                var outcomes = new ArrayList<ScriptOutcome>();
                for (int i = 1; i <= count; i++) outcomes.add(new ScriptOutcome(i, "synthetic", i == 1001 ? QueryResult.error("omitted", 1) : QueryResult.update(1, 1)));
                f.show(outcomes); f.select(0); filter(f).fire();
                assertTrue(f.table().getItems().isEmpty()); assertTrue(result(f).isDisabled()); assertTrue(f.button().isDisabled());
                assertFalse(filter(f).isDisabled()); assertTrue(count(f).getText().contains("0 / " + Math.min(count, 1000) + " 条已保留结果"));
                assertTrue(count(f).getText().contains("已保留结果中没有异常"));
                assertEquals(Math.min(count, 1000) + 1, chooser(f).getItems().size());
                if (count == 1001) {
                    assertTrue(((Label) f.root.lookup("#sql-batch-summary")).getText().contains("失败 1"));
                    assertTrue(((Label) f.root.lookup("#sql-script-details-notice")).getText().contains("仅显示前 1000"));
                }
                filter(f).fire(); assertEquals(Math.min(count, 1000), f.table().getItems().size()); assertNull(f.details().selectedEntry());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue"})
    void staleToggleCannotMutateBlockedOverview(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); filter(f).fire();
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
                var rows = f.table().getItems(); boolean before = filter(f).isSelected();
                filter(f).setSelected(!before); filter(f).getOnAction().handle(new ActionEvent());
                assertEquals(before, filter(f).isSelected()); assertSame(rows, f.table().getItems());
                if (blocker.equals("running")) {
                    invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false); assertFalse(filter(f).isDisabled());
                    filter(f).fire(); assertEquals(6, f.table().getItems().size());
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "empty", "error", "plan", "clear", "batch", "close"})
    void replacementResetsFilterAndOldToggleCannotRestoreRows(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); filter(f).fire(); var action = filter(f).getOnAction();
                switch (replacement) {
                    case "query" -> f.show(List.of(new ScriptOutcome(1, "new", QueryResult.query(List.of("v"), List.of(List.of(42)), 1))));
                    case "empty" -> f.show(List.of());
                    case "error" -> invoke(f.pane, "showError", new Class<?>[]{String.class, long.class}, "new error", 1L);
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 1);
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                    case "batch" -> f.show(List.of(new ScriptOutcome(8, "new", QueryResult.update(1, 0)), new ScriptOutcome(9, "new", QueryResult.error("new failure", 1))));
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                assertFalse(filter(f).isSelected()); var rows = List.copyOf(f.table().getItems());
                action.handle(new ActionEvent()); assertEquals(rows, f.table().getItems()); assertFalse(filter(f).isSelected());
                if (replacement.equals("batch")) { filter(f).fire(); assertEquals(List.of(9), indices(f)); }
                else { assertFalse(f.details().getNode().isVisible()); assertEquals("", count(f).getText()); }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark,false", "480,light,false", "880,dark,false", "880,light,false",
            "480,dark,true", "480,light,true", "880,dark,true", "880,light,true"})
    void filterActionsCountsAndNoticeFitBothThemes(double width, String theme, boolean empty) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, width, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(), ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                if (empty) f.show(List.of(new ScriptOutcome(1, "normal", QueryResult.update(1, 1)), new ScriptOutcome(2, "normal", QueryResult.update(1, 1))));
                else mixed(f);
                filter(f).fire(); f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                for (String id : List.of("sql-script-only-failures", "sql-script-filter-count", "sql-script-details-notice", "sql-script-details", "sql-script-result")) {
                    var node = (javafx.scene.layout.Region) f.root.lookup("#" + id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1, id);
                    assertTrue(node.getHeight() + 1 >= node.prefHeight(node.getWidth()), id);
                }
                assertTrue(f.table().getHeight() >= 100); return null;
            });
        }
    }

    @Test void allAbnormalEqualRowsKeepTheExactSelectedSnapshot() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                var same = new ScriptOutcome(7, "same", QueryResult.error("same failure", 1));
                f.show(List.of(same, same)); f.select(1); var second = f.details().selectedEntry();
                filter(f).fire(); assertEquals(2, f.table().getItems().size());
                assertEquals("显示 2 / 2 条已保留结果", count(f).getText());
                assertSame(second, f.details().selectedEntry()); assertEquals(1, f.table().getSelectionModel().getSelectedIndex());
                result(f).fire(); assertSame(second, chooser(f).getValue().detail()); assertEquals(2, chooser(f).getSelectionModel().getSelectedIndex());
                return null;
            });
            f.assertOffline();
        }
    }

    private static List<Object> indices(SqlScriptDetailsIntegrationTest.Fixture f) { return f.table().getItems().stream().map(row -> row.getFirst()).toList(); }
    private static CheckBox filter(SqlScriptDetailsIntegrationTest.Fixture f) {
        var value = (CheckBox) f.root.lookup("#sql-script-only-failures"); assertNotNull(value, "overview needs an abnormal-only filter"); return value;
    }
    private static Label count(SqlScriptDetailsIntegrationTest.Fixture f) { return (Label) f.root.lookup("#sql-script-filter-count"); }
    private static Button result(SqlScriptDetailsIntegrationTest.Fixture f) { return (Button) f.root.lookup("#sql-script-result"); }
    @SuppressWarnings("unchecked") private static ComboBox<SqlBatchResults.Choice> chooser(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<SqlBatchResults.Choice>) f.root.lookup("#sql-batch-choice"); }
}
