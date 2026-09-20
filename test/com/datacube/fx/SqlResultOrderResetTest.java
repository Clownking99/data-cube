package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.ResultExportScope;
import java.nio.file.Path;
import java.util.List;
import javafx.event.ActionEvent;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultOrderResetTest {
    @TempDir Path directory;

    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static void query(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        f.show(List.of(new ScriptOutcome(1, "select synthetic", QueryResult.query(List.of("same", "same", "tail"),
                List.of(List.of("Ada", 3, "A"), List.of("Ada", 1, "B"), List.of("Bob", 0, "C"), List.of("Ada", 1, "B")), 1))));
    }
    private static void sort(SqlScriptDetailsIntegrationTest.Fixture f) {
        var score = f.table().getColumns().stream().filter(c -> Integer.valueOf(1).equals(c.getUserData())).findFirst().orElseThrow();
        score.setSortType(TableColumn.SortType.ASCENDING);
        var tail = f.table().getColumns().stream().filter(c -> Integer.valueOf(2).equals(c.getUserData())).findFirst().orElseThrow();
        tail.setSortType(TableColumn.SortType.DESCENDING);
        f.table().getSortOrder().setAll(List.of(score, tail)); f.table().sort();
    }
    private static MenuItem reset(SqlScriptDetailsIntegrationTest.Fixture f) {
        var menu = f.table().getContextMenu();
        if (menu.getOnShowing() != null) menu.getOnShowing().handle(new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
        return menu.getItems().stream().filter(i -> "sql-result-reset-order-menu".equals(i.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Query results need an explicit original-order action"));
    }
    private static void search(SqlScriptDetailsIntegrationTest.Fixture f, String value) {
        var search = (TextField) f.root.lookup("#sql-result-search"); search.setText(value); search.fireEvent(new ActionEvent());
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void restoresSourceIdentityAndMultiCellFocusWhilePreservingFiltersColumnsAndExport(String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.window(); query(f); assertTrue(reset(f).isDisable());
                search(f, "Ada"); var table = f.table(); var rows = table.getItems(); var original = List.copyOf(rows);
                var name = table.getColumns().get(1); var score = table.getColumns().get(2); var tail = table.getColumns().get(3);
                name.setVisible(false); score.setPrefWidth(333); table.getColumns().setAll(List.of(table.getColumns().getFirst(), tail, score, name));
                var columns = List.copyOf(table.getColumns()); sort(f);
                assertEquals(List.of(1, 1, 3), rows.stream().map(r -> r.get(1)).toList());
                table.getSelectionModel().clearAndSelect(0, score); table.getSelectionModel().select(1, tail);
                table.getSelectionModel().select(2, score); table.getFocusModel().focus(1, tail);
                assertEquals(3, table.getSelectionModel().getSelectedCells().size());
                var selectedDuplicate = rows.get(1);
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(2, 8); String draft = f.editor.getText();
                assertFalse(reset(f).isDisable()); reset(f).fire();
                assertSame(rows, table.getItems()); assertTrue(table.getSortOrder().isEmpty());
                for (int i = 0; i < original.size(); i++) assertSame(original.get(i), rows.get(i));
                assertSame(selectedDuplicate, rows.get(2));
                assertEquals(3, table.getSelectionModel().getSelectedCells().size());
                assertTrue(table.getSelectionModel().isSelected(0, score)); assertTrue(table.getSelectionModel().isSelected(1, score));
                assertTrue(table.getSelectionModel().isSelected(2, tail));
                assertEquals(2, table.getFocusModel().getFocusedCell().getRow()); assertSame(tail, table.getFocusModel().getFocusedCell().getTableColumn());
                assertEquals(columns, table.getColumns()); assertFalse(name.isVisible()); assertEquals(333, score.getPrefWidth());
                assertEquals("Ada", ((TextField) f.root.lookup("#sql-result-search")).getText());
                var exported = f.pane.captureResultExportSnapshot(); assertEquals(List.of("tail", "same"), exported.columns());
                assertEquals(List.of(List.of("A", 3), List.of("B", 1), List.of("B", 1)), exported.rows(ResultExportScope.CURRENT_FILTERED));
                assertEquals(List.of(List.of("A", 3), List.of("B", 1), List.of("C", 0), List.of("B", 1)), exported.rows(ResultExportScope.ALL_LOADED));
                assertEquals("select synthetic", exported.originalSql()); assertTrue(reset(f).isDisable());
                assertEquals(draft, f.editor.getText()); assertEquals(2, f.editor.getAnchor()); assertEquals(8, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText());
                return null;
            }); f.assertOffline();
        }
    }

    @Test void keepsTextAndColumnConditionIntersectionAndDatabaseState() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "synthetic", QueryResult.query(List.of("name", "tag", "score"),
                        List.of(List.of("keep", "B", 2), List.of("keep", "A", 1), List.of("other", "B", 0), List.of("keep", "Z", 0)), 1))));
                var state = (com.datacube.sqleditor.result.ResultFilterState) field(f.pane, "resultFilterState");
                state.setConditions(List.of(new com.datacube.sqleditor.result.FilterCondition(1,
                        com.datacube.sqleditor.result.FilterConnector.AND, com.datacube.sqleditor.result.FilterOperator.NE, "Z")));
                search(f, "keep"); var before = state.snapshot();
                var col = f.table().getColumns().get(3); f.table().getSortOrder().setAll(List.of(col)); f.table().sort();
                assertEquals(List.of("A", "B"), f.table().getItems().stream().map(r -> r.get(1)).toList());
                reset(f).fire();
                assertEquals(List.of("B", "A"), f.table().getItems().stream().map(r -> r.get(1)).toList());
                var after = state.snapshot(); assertSame(before.activeResult(), after.activeResult());
                assertEquals(before.conditions(), after.conditions()); assertEquals(before.searchText(), after.searchText());
                assertEquals(before.databaseStatus(), after.databaseStatus()); assertEquals(before.visibleRowIndexes(), after.visibleRowIndexes());
                search(f, ""); assertEquals(List.of("B", "A", "B"), f.table().getItems().stream().map(r -> r.get(1)).toList());
                return null;
            }); f.assertOffline();
        }
    }

    @Test void cancelledSortArrowStillRestoresOrderWithoutInventingSelection() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f); var original = List.copyOf(f.table().getItems()); sort(f); f.table().getSortOrder().clear();
                assertEquals(List.of(0, 1, 1, 3), f.table().getItems().stream().map(r -> r.get(1)).toList());
                f.table().getSelectionModel().clearSelection(); f.table().getFocusModel().focus(-1);
                assertFalse(reset(f).isDisable()); reset(f).fire();
                for (int i = 0; i < original.size(); i++) assertSame(original.get(i), f.table().getItems().get(i));
                assertTrue(f.table().getSelectionModel().isEmpty()); assertEquals(-1, f.table().getFocusModel().getFocusedCell().getRow());
                assertTrue(reset(f).isDisable()); var rows = f.table().getItems(); reset(f).getOnAction().handle(new ActionEvent());
                assertSame(rows, f.table().getItems()); return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "single", "zero-match"})
    void clearsSortForEmptyOrSingleRowsWithoutRestoringHiddenRows(String variant) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f);
                if (!variant.equals("zero-match")) f.show(List.of(new ScriptOutcome(1, "synthetic", QueryResult.query(List.of("n"),
                        variant.equals("empty") ? List.of() : List.of(List.of(4)), 0))));
                var col = f.table().getColumns().get(1); f.table().getSortOrder().setAll(List.of(col)); f.table().sort();
                if (variant.equals("zero-match")) search(f, "absent");
                assertFalse(reset(f).isDisable()); reset(f).fire(); assertTrue(f.table().getSortOrder().isEmpty());
                assertEquals(variant.equals("single") ? 1 : 0, f.table().getItems().size()); assertTrue(reset(f).isDisable());
                if (variant.equals("zero-match")) {
                    assertEquals("absent", ((TextField) f.root.lookup("#sql-result-search")).getText()); search(f, "");
                    assertEquals(List.of(3, 1, 0, 1), f.table().getItems().stream().map(r -> r.get(1)).toList());
                    assertTrue(f.table().getSelectionModel().isEmpty());
                }
                return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "root-disabled", "table-disabled", "running", "admission", "queue"})
    void lateResetCannotMutateBlockedQuery(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f); sort(f); var action = reset(f).getOnAction();
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "root-disabled" -> f.root.setDisable(true);
                    case "table-disabled" -> f.table().setDisable(true);
                    case "running" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                }
                var rows = f.table().getItems(); var values = List.copyOf(rows); var sorting = List.copyOf(f.table().getSortOrder());
                action.handle(new ActionEvent()); assertSame(rows, f.table().getItems()); assertEquals(values, rows);
                assertEquals(sorting, f.table().getSortOrder()); assertTrue(reset(f).isDisable());
                if (blocker.equals("running")) {
                    invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(reset(f).isDisable()); reset(f).fire(); assertEquals(List.of(3, 1, 0, 1), rows.stream().map(r -> r.get(1)).toList());
                }
                return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "filter", "empty", "plan", "overview", "update", "error", "clear"})
    void oldMenuActionCannotChangeReplacementOrNonQueryResults(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f); sort(f); var action = reset(f).getOnAction();
                switch (replacement) {
                    case "query" -> { query(f); sort(f); }
                    case "filter" -> search(f, "Ada");
                    case "empty" -> f.show(List.of());
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "synthetic scan", 1L, 1);
                    case "overview" -> f.batch();
                    case "update" -> f.show(List.of(new ScriptOutcome(1, "synthetic", QueryResult.update(1, 3))));
                    case "error" -> f.show(List.of(new ScriptOutcome(1, "synthetic", QueryResult.error("synthetic failure", 1))));
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                }
                var rows = f.table().getItems(); var values = List.copyOf(rows); var sorting = List.copyOf(f.table().getSortOrder());
                action.handle(new ActionEvent()); assertSame(rows, f.table().getItems()); assertEquals(values, rows); assertEquals(sorting, f.table().getSortOrder());
                if (replacement.equals("query") || replacement.equals("filter")) {
                    assertFalse(reset(f).isDisable()); reset(f).fire();
                    assertEquals(replacement.equals("filter") ? List.of(3, 1, 1) : List.of(3, 1, 0, 1), rows.stream().map(r -> r.get(1)).toList());
                } else assertTrue(reset(f).isDisable());
                return null;
            }); f.assertOffline();
        }
    }
}
