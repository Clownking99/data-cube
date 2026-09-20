package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.fx.task.FxTaskScope;
import java.nio.file.Path;
import java.util.List;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlOverviewResetOrderTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void restoresSnapshotOrderRatherThanIndexAndKeepsOutcomeIdentity(boolean selected) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); mixed(f); var originalRows = List.copyOf(f.table().getItems());
                var choices = List.copyOf(chooser(f).getItems()); var overview = chooser(f).getValue();
                String summary = ((Label) f.root.lookup("#sql-batch-summary")).getText();
                assertTrue(reset(f).isDisabled());
                if (selected) f.select(2); var entry = f.details().selectedEntry();
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(2, 8); String draft = f.editor.getText();
                sort(f); assertEquals(List.of(5, 2, 9, 7, 7), indices(f)); assertFalse(reset(f).isDisabled());
                if (selected) { f.button().fire(); assertNotNull(f.dialog()); }
                reset(f).fire();
                assertEquals(List.of(9, 7, 7, 2, 5), indices(f)); assertTrue(f.table().getSortOrder().isEmpty());
                for (int i = 0; i < originalRows.size(); i++) assertSame(originalRows.get(i), f.table().getItems().get(i));
                assertSame(entry, f.details().selectedEntry()); assertNull(f.dialog()); assertTrue(reset(f).isDisabled());
                assertSame(overview, chooser(f).getValue()); assertEquals(choices, chooser(f).getItems());
                assertEquals(summary, ((Label) f.root.lookup("#sql-batch-summary")).getText());
                assertNull(f.pane.captureResultExportSnapshot());
                assertEquals(draft, f.editor.getText()); assertEquals(2, f.editor.getAnchor()); assertEquals(8, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); assertFalse(f.document().dirty());
                var rows = f.table().getItems(); reset(f).getOnAction().handle(new ActionEvent()); assertSame(rows, f.table().getItems());
                if (selected) {
                    ((Button) f.root.lookup("#sql-script-result")).fire(); assertSame(entry, chooser(f).getValue().detail());
                    assertEquals("duplicate failure", f.table().getItems().getFirst().getFirst());
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void preservesBothFiltersSelectionAndRejectedQueryNoticeWhileClearingAllSortColumns() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); f.select(4); var selected = f.details().selectedEntry(); sort(f);
                query(f).setText(" KEEP "); failures(f).fire();
                assertEquals(List.of(5, 7, 7), indices(f)); assertSame(selected, f.details().selectedEntry());
                query(f).setText("x".repeat(257)); assertTrue(count(f).getText().contains("超长输入未应用"));
                reset(f).fire(); assertEquals(List.of(7, 7, 5), indices(f)); assertEquals(" KEEP ", query(f).getText());
                assertTrue(failures(f).isSelected()); assertTrue(count(f).getText().startsWith("显示 3 / 5"));
                assertTrue(count(f).getText().contains("超长输入未应用")); assertSame(selected, f.details().selectedEntry());
                assertTrue(f.table().getSortOrder().isEmpty()); assertTrue(reset(f).isDisabled());
                failures(f).fire(); assertEquals(List.of(9, 7, 7, 5), indices(f));
                ((Button) f.root.lookup("#sql-script-clear-filter")).fire(); assertEquals(List.of(9, 7, 7, 2, 5), indices(f));
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void removingHeaderSortStillAllowsRestorationOfReorderedRows() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); sort(f); f.table().getSortOrder().clear();
                assertEquals(List.of(5, 2, 9, 7, 7), indices(f));
                assertFalse(reset(f).isDisabled()); reset(f).fire();
                assertEquals(List.of(9, 7, 7, 2, 5), indices(f)); assertTrue(reset(f).isDisabled());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void emptyFilteredOverviewCanResetSortWithoutRestoringHiddenSelection() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); f.select(1); sort(f); query(f).setText("absent");
                assertTrue(f.table().getItems().isEmpty()); assertNull(f.details().selectedEntry()); assertFalse(reset(f).isDisabled());
                reset(f).fire(); assertTrue(f.table().getItems().isEmpty()); assertTrue(f.table().getSortOrder().isEmpty());
                assertEquals("absent", query(f).getText()); assertTrue(reset(f).isDisabled());
                query(f).clear(); assertEquals(List.of(9, 7, 7, 2, 5), indices(f)); assertNull(f.details().selectedEntry());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void resetCannotAddOmittedOutcomesBeyondRetainedLimit() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                var outcomes = java.util.stream.IntStream.rangeClosed(1, 1001)
                        .mapToObj(i -> new ScriptOutcome(i, "keep " + i, QueryResult.update(i, 1))).toList();
                f.show(outcomes); sort(f); assertEquals(1000, f.table().getItems().getFirst().getFirst());
                reset(f).fire(); assertEquals(java.util.stream.IntStream.rangeClosed(1, 1000).boxed().toList(), indices(f));
                assertTrue(count(f).getText().contains("1000 / 1000")); assertEquals(1001, chooser(f).getItems().size());
                assertTrue(((Label) f.root.lookup("#sql-script-details-notice")).getText().contains("仅显示前 1000"));
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue"})
    void staleResetCannotChangeBlockedOverview(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); sort(f); var action = reset(f).getOnAction();
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
                var rows = f.table().getItems(); var order = List.copyOf(f.table().getSortOrder()); var values = List.copyOf(rows);
                action.handle(new ActionEvent()); assertSame(rows, f.table().getItems()); assertEquals(values, rows);
                assertEquals(order, f.table().getSortOrder());
                if (blocker.equals("running")) {
                    assertTrue(reset(f).isDisabled()); invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(reset(f).isDisabled()); reset(f).fire(); assertEquals(List.of(9, 7, 7, 2, 5), indices(f));
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "empty", "plan", "batch", "close"})
    void replacementRejectsOldResetAndNeverRestoresPreviousBatch(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); sort(f); var action = reset(f).getOnAction();
                switch (replacement) {
                    case "query" -> {
                        f.show(List.of(new ScriptOutcome(1, "query", QueryResult.query(List.of("n"), List.of(List.of(2), List.of(1)), 1))));
                        var column = f.table().getColumns().get(1); column.setSortType(TableColumn.SortType.ASCENDING);
                        f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
                    }
                    case "empty" -> f.show(List.of());
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 1);
                    case "batch" -> f.show(List.of(new ScriptOutcome(8, "new", QueryResult.update(1, 0)), new ScriptOutcome(3, "new", QueryResult.error("new failure", 1))));
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                var rows = f.table().getItems(); var order = List.copyOf(f.table().getSortOrder());
                action.handle(new ActionEvent()); assertSame(rows, f.table().getItems()); assertEquals(order, f.table().getSortOrder());
                assertTrue(reset(f).isDisabled());
                if (replacement.equals("batch")) { sort(f); reset(f).fire(); assertEquals(List.of(8, 3), indices(f)); }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "480,light", "880,dark", "880,light"})
    void resetActionAndExistingControlsFitNarrowAndWideThemes(double width, String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, width, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                mixed(f); sort(f); f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                var bar = f.details().getNode();
                for (String id : List.of("reset-order", "result", "details", "only-failures", "sql-filter", "clear-filter", "filter-count")) {
                    var node = (Region) f.root.lookup("#sql-script-" + id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= width + 1, id);
                    assertTrue(bounds.getMaxY() <= bar.localToScene(bar.getLayoutBounds()).getMaxY() + 1, id);
                }
                assertTrue(reset(f).getWidth() + 1 >= reset(f).prefWidth(-1)); assertTrue(f.table().getHeight() >= 100);
                return null;
            });
        }
    }

    private static void mixed(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        var duplicate = new ScriptOutcome(7, "keep same", QueryResult.error("duplicate failure", 9));
        f.show(List.of(new ScriptOutcome(9, "keep update", QueryResult.update(50, 1)), duplicate, duplicate,
                new ScriptOutcome(2, "other", QueryResult.update(80, 1)), new ScriptOutcome(5, "keep slow", QueryResult.timeout("slow", 1000))));
    }
    private static void sort(SqlScriptDetailsIntegrationTest.Fixture f) {
        var elapsed = f.table().getColumns().get(2); elapsed.setSortType(TableColumn.SortType.DESCENDING);
        var index = f.table().getColumns().getFirst(); index.setSortType(TableColumn.SortType.ASCENDING);
        f.table().getSortOrder().setAll(List.of(elapsed, index)); f.table().sort();
    }
    private static Button reset(SqlScriptDetailsIntegrationTest.Fixture f) {
        var value = (Button) f.root.lookup("#sql-script-reset-order"); assertNotNull(value, "Overview needs an explicit reset order action"); return value;
    }
    private static TextField query(SqlScriptDetailsIntegrationTest.Fixture f) { return (TextField) f.root.lookup("#sql-script-sql-filter"); }
    private static CheckBox failures(SqlScriptDetailsIntegrationTest.Fixture f) { return (CheckBox) f.root.lookup("#sql-script-only-failures"); }
    private static Label count(SqlScriptDetailsIntegrationTest.Fixture f) { return (Label) f.root.lookup("#sql-script-filter-count"); }
    private static List<Object> indices(SqlScriptDetailsIntegrationTest.Fixture f) { return f.table().getItems().stream().map(row -> row.getFirst()).toList(); }
    @SuppressWarnings("unchecked") private static ComboBox<SqlBatchResults.Choice> chooser(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<SqlBatchResults.Choice>) f.root.lookup("#sql-batch-choice"); }
}
