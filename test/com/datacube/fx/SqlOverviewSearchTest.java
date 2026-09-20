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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlOverviewSearchTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static void mixed(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        f.show(List.of(
                new ScriptOutcome(1, "select\nOrders", QueryResult.query(List.of("n"), List.of(List.of(1)), 9)),
                new ScriptOutcome(2, "update ORDERS set n=1", QueryResult.error("first failure", 1000)),
                new ScriptOutcome(3, "delete customers", QueryResult.timeout("slow", 10)),
                new ScriptOutcome(4, "select '[_%].*' from orders", QueryResult.cancelled("cancelled", 80)),
                new ScriptOutcome(5, "select 中文", QueryResult.update(1, 0))));
        chooser(f).getSelectionModel().select(0);
    }

    @ParameterizedTest @CsvSource(value = {"orders|1,2,4", "  ORDERS  |1,2,4", "select orders|1", "[_%].*|4", "中文|5", "no-match|", "   |1,2,3,4,5"}, delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    void matchesDisplayedPreviewLiterallyAndCaseInsensitively(String term, String expected) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); var choices = List.copyOf(chooser(f).getItems()); var overview = chooser(f).getValue();
                String summary = ((Label) f.root.lookup("#sql-batch-summary")).getText();
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(9, 15); String edited = f.editor.getText();
                query(f).setText(term);
                assertEquals(expected == null ? "" : expected, indices(f));
                assertTrue(count(f).getText().startsWith("显示 " + f.table().getItems().size() + " / 5 条已保留结果"));
                if (expected == null) assertTrue(count(f).getText().contains("已保留的 SQL 摘要中没有匹配"));
                assertEquals(choices, chooser(f).getItems()); assertSame(overview, chooser(f).getValue());
                assertEquals(summary, ((Label) f.root.lookup("#sql-batch-summary")).getText());
                assertNull(f.pane.captureResultExportSnapshot()); assertNull(f.details().selectedEntry());
                assertEquals(edited, f.editor.getText()); assertEquals(9, f.editor.getAnchor()); assertEquals(15, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); assertFalse(f.editor.isUndoAvailable()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 3})
    void combinesWithFailureFilterAndPreservesSortAndOnlyVisibleSelection(int selectedIndex) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); failures(f).fire();
                var column = f.table().getColumns().get(2); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
                assertEquals("2,4,3", indices(f)); f.select(selectedIndex == 2 ? 0 : 2);
                var selected = f.details().selectedEntry(); query(f).setText("orders");
                assertEquals("2,4", indices(f)); assertEquals(List.of(column), f.table().getSortOrder());
                if (selectedIndex == 2) assertSame(selected, f.details().selectedEntry());
                else { assertNull(f.details().selectedEntry()); assertTrue(f.table().getSelectionModel().isEmpty()); }
                assertEquals(selectedIndex == 3, result(f).isDisabled()); assertEquals(selectedIndex == 3, f.button().isDisabled());
                clear(f).fire(); assertEquals("", query(f).getText()); assertTrue(failures(f).isSelected());
                assertEquals("2,4,3", indices(f)); assertTrue(clear(f).isDisabled());
                if (selectedIndex == 2) assertSame(selected, f.details().selectedEntry()); else assertNull(f.details().selectedEntry());
                query(f).setText("中文"); assertEquals("", indices(f));
                assertTrue(count(f).getText().contains("已保留的异常结果中没有匹配的 SQL 摘要"));
                failures(f).fire(); assertEquals("5", indices(f)); assertEquals("中文", query(f).getText()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"result", "details"})
    void filteredRowsOpenOriginalResultOrDetailsAndChangingQueryClosesDialog(String action) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); mixed(f); query(f).setText("orders"); failures(f).fire();
                var column = f.table().getColumns().getFirst(); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort(); f.select(0);
                assertEquals(4, f.details().selectedEntry().index());
                if (action.equals("result")) {
                    result(f).fire(); assertEquals(4, chooser(f).getValue().outcome().index());
                    assertEquals(List.of("cancelled"), f.table().getItems().getFirst());
                    assertFalse(f.details().getNode().isVisible()); assertEquals("", query(f).getText());
                    chooser(f).getSelectionModel().select(0); assertEquals("1,2,3,4,5", indices(f));
                } else {
                    f.table().fireEvent(key(KeyCode.ENTER, false)); var dialog = f.dialog(); assertTrue(dialog.isShowing());
                    assertEquals("select '[_%].*' from orders", ((TextArea) dialog.getDialogPane().lookup("#sql-script-detail-sql")).getText());
                    assertEquals("cancelled", ((TextArea) dialog.getDialogPane().lookup("#sql-script-detail-error")).getText());
                    query(f).setText("customers"); assertFalse(dialog.isShowing()); assertNull(f.dialog());
                    assertEquals("3", indices(f)); assertNull(f.details().selectedEntry());
                }
                assertEquals(f.original, f.document().physicalText()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"hidden-tail", "only-error", "only-result", "only-editor"})
    void doesNotSearchBeyondDisplayedSqlPreview(String term) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "x".repeat(120) + " hidden-tail", QueryResult.error("only-error", 1)),
                        new ScriptOutcome(2, "select visible", QueryResult.query(List.of("v"), List.of(List.of("only-result")), 2))));
                chooser(f).getSelectionModel().select(0); f.editor.replaceText("only-editor");
                query(f).setText(term); assertTrue(f.table().getItems().isEmpty());
                assertTrue(count(f).getText().contains("已保留的 SQL 摘要中没有匹配"));
                query(f).setText("…"); assertEquals("1", indices(f));
                query(f).setText("visible"); assertEquals("2", indices(f));
                assertTrue(query(f).getTooltip().getText().contains("不搜索完整 SQL")); return null;
            });
            f.assertOffline();
        }
    }

    @Test void rejectsOverlongInputWithoutLosingQueryRowsSelectionOrRecovery() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); query(f).setText(" ".repeat(249) + "Orders"); assertEquals(255, query(f).getText().length());
                query(f).appendText(" "); assertEquals(256, query(f).getText().length()); assertEquals("1,2,4", indices(f));
                f.select(1); var selected = f.details().selectedEntry(); var rows = f.table().getItems();
                query(f).appendText("x"); assertEquals(256, query(f).getText().length());
                assertSame(rows, f.table().getItems()); assertSame(selected, f.details().selectedEntry());
                assertTrue(count(f).getText().contains("超长输入未应用"));
                query(f).setText("orders"); assertFalse(count(f).getText().contains("超长输入未应用"));
                query(f).setText("😀".repeat(129)); assertEquals("orders", query(f).getText());
                clear(f).fire(); assertEquals("", query(f).getText()); assertEquals("1,2,3,4,5", indices(f));
                assertFalse(count(f).getText().contains("超长输入未应用")); assertSame(selected, f.details().selectedEntry());
                query(f).setText("x".repeat(257)); assertEquals("", query(f).getText()); assertFalse(clear(f).isDisabled());
                clear(f).fire(); assertTrue(clear(f).isDisabled()); assertFalse(count(f).getText().contains("超长输入未应用")); return null;
            });
            f.assertOffline();
        }
    }

    @Test void displayedMissingSqlNoticeIsSearchableAndNullQueryRestoresAllRows() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "", QueryResult.error("missing sql", 1)),
                        new ScriptOutcome(2, "select visible", QueryResult.update(1, 1))));
                query(f).setText("未提供 SQL"); assertEquals("1", indices(f));
                query(f).setText(null); assertEquals("1,2", indices(f));
                assertEquals("显示 2 / 2 条已保留结果", count(f).getText()); assertTrue(clear(f).isDisabled());
                query(f).setText("visible"); assertEquals("2", indices(f)); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(ints = {999, 1000, 1001})
    void searchOnlySeesRetainedEntriesAndCountsOmittedOutcomesSeparately(int count) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                var outcomes = new ArrayList<ScriptOutcome>();
                for (int i = 1; i <= count; i++) outcomes.add(new ScriptOutcome(i, i == count ? "needle" : "synthetic", QueryResult.update(1, 1)));
                f.show(outcomes); query(f).setText("needle");
                assertEquals(count > 1000 ? "" : Integer.toString(count), indices(f));
                assertTrue(count(f).getText().startsWith("显示 " + (count > 1000 ? 0 : 1) + " / " + Math.min(count, 1000)));
                assertTrue(((Label) f.root.lookup("#sql-batch-summary")).getText().contains("已返回 " + count));
                assertEquals(Math.min(count, 1000) + 1, chooser(f).getItems().size());
                if (count > 1000) assertTrue(((Label) f.root.lookup("#sql-script-details-notice")).getText().contains("仅显示前 1000"));
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue"})
    void blockedPaneRejectsTextChangesAndStaleClearActions(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); query(f).setText("orders"); var action = clear(f).getOnAction();
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
                String before = query(f).getText(); var rows = f.table().getItems(); String counter = count(f).getText();
                query(f).setText("customers"); action.handle(new ActionEvent());
                assertEquals(before, query(f).getText()); assertSame(rows, f.table().getItems()); assertEquals(counter, count(f).getText());
                if (blocker.equals("running")) {
                    assertTrue(query(f).isDisabled()); assertTrue(clear(f).isDisabled());
                    invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(query(f).isDisabled()); clear(f).fire(); assertEquals("1,2,3,4,5", indices(f));
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "empty", "error", "plan", "clear", "batch", "close"})
    void replacementClearsQueryAndOldActionsCannotRestoreOldRows(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); query(f).setText("orders"); query(f).setText("x".repeat(257)); var action = clear(f).getOnAction();
                switch (replacement) {
                    case "query" -> f.show(List.of(new ScriptOutcome(1, "new", QueryResult.query(List.of("v"), List.of(List.of(42)), 1))));
                    case "empty" -> f.show(List.of());
                    case "error" -> invoke(f.pane, "showError", new Class<?>[]{String.class, long.class}, "new", 1L);
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 1);
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                    case "batch" -> f.show(List.of(new ScriptOutcome(8, "new", QueryResult.update(1, 0)), new ScriptOutcome(9, "new", QueryResult.error("new failure", 1))));
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                assertEquals("", query(f).getText()); assertFalse(count(f).getText().contains("超长输入未应用"));
                var rows = List.copyOf(f.table().getItems()); action.handle(new ActionEvent()); assertEquals(rows, f.table().getItems());
                if (replacement.equals("batch")) { query(f).setText("new"); assertEquals("8,9", indices(f)); }
                else { query(f).setText("orders"); assertEquals("", query(f).getText()); assertEquals(rows, f.table().getItems()); }
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void equalVisibleRowsKeepOriginalIdentityAfterKeywordChanges() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                var same = new ScriptOutcome(7, "same sql", QueryResult.error("same failure", 1));
                f.show(List.of(same, same)); f.select(1); var second = f.details().selectedEntry();
                query(f).setText("same"); failures(f).fire(); assertSame(second, f.details().selectedEntry());
                assertEquals(1, f.table().getSelectionModel().getSelectedIndex()); result(f).fire();
                assertSame(second, chooser(f).getValue().detail()); assertEquals(2, chooser(f).getSelectionModel().getSelectedIndex()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "480,light", "640,dark", "640,light"})
    void queryClearCountsAndActionsFitBothThemesAndNarrowPanels(double width, String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, width, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                mixed(f); query(f).setText("no match"); failures(f).fire(); query(f).setText("x".repeat(257));
                f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                for (String id : List.of("sql-script-sql-filter", "sql-script-clear-filter", "sql-script-only-failures", "sql-script-filter-count", "sql-script-details-notice", "sql-script-details", "sql-script-result")) {
                    var node = (javafx.scene.layout.Region) f.root.lookup("#" + id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1,
                            id + " bounds=" + bounds + " bar=" + f.details().getNode().getWidth()
                                    + " search=" + query(f).getParent().getLayoutBounds());
                    assertTrue(node.getHeight() + 1 >= node.prefHeight(node.getWidth()), id);
                }
                assertTrue(query(f).getWidth() >= 140); assertTrue(clear(f).getWidth() + 1 >= clear(f).prefWidth(-1));
                assertTrue(query(f).isFocusTraversable()); assertTrue(clear(f).isFocusTraversable());
                assertTrue(f.table().getHeight() >= 100); return null;
            });
        }
    }

    private static TextField query(SqlScriptDetailsIntegrationTest.Fixture f) {
        var value = (TextField) f.root.lookup("#sql-script-sql-filter"); assertNotNull(value, "overview needs a SQL preview filter"); return value;
    }
    private static Button clear(SqlScriptDetailsIntegrationTest.Fixture f) { return (Button) f.root.lookup("#sql-script-clear-filter"); }
    private static Button result(SqlScriptDetailsIntegrationTest.Fixture f) { return (Button) f.root.lookup("#sql-script-result"); }
    private static CheckBox failures(SqlScriptDetailsIntegrationTest.Fixture f) { return (CheckBox) f.root.lookup("#sql-script-only-failures"); }
    private static Label count(SqlScriptDetailsIntegrationTest.Fixture f) { return (Label) f.root.lookup("#sql-script-filter-count"); }
    private static String indices(SqlScriptDetailsIntegrationTest.Fixture f) {
        return String.join(",", f.table().getItems().stream().map(row -> row.getFirst().toString()).toList());
    }
    @SuppressWarnings("unchecked") private static ComboBox<SqlBatchResults.Choice> chooser(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<SqlBatchResults.Choice>) f.root.lookup("#sql-batch-choice"); }
}
