package com.datacube.fx;

import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.List;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuButton;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultViewMenuTest {
    @TempDir Path directory;

    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static void query(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        f.show(List.of(new ScriptOutcome(1, "select synthetic", QueryResult.query(List.of("name", "score"),
                List.of(List.of("first", 2), List.of("second", 1)), 1))));
    }
    private static SplitMenuButton menu(SqlScriptDetailsIntegrationTest.Fixture f) {
        return (SplitMenuButton) f.root.lookup("#sql-result-view-cell");
    }
    private static SplitMenuButton prepare(SqlScriptDetailsIntegrationTest.Fixture f) {
        var menu = menu(f); menu.getOnShowing().handle(new Event(MenuButton.ON_SHOWING)); return menu;
    }
    private static void select(SqlScriptDetailsIntegrationTest.Fixture f) {
        var column = f.table().getColumns().get(1);
        f.table().getSelectionModel().clearAndSelect(0, column); f.table().getFocusModel().focus(0, column);
    }
    private static void sort(SqlScriptDetailsIntegrationTest.Fixture f) {
        var score = f.table().getColumns().get(2); score.setSortType(TableColumn.SortType.ASCENDING);
        f.table().getSortOrder().setAll(List.of(score)); f.table().sort();
    }
    private static void search(SqlScriptDetailsIntegrationTest.Fixture f, String text) {
        var search = (TextField) f.root.lookup("#sql-result-search"); search.setText(text); search.fireEvent(new ActionEvent());
    }

    @ParameterizedTest @ValueSource(strings = {"primary", "cell", "row", "locate"})
    void browseEntryUsesFocusedVisibleResultWithoutChangingSqlOrSelection(String action) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f); sort(f); select(f);
                f.table().getColumns().get(2).setVisible(false);
                f.editor.selectRange(2, 9); var selection = List.copyOf(f.table().getSelectionModel().getSelectedCells());
                var rows = List.copyOf(f.table().getItems()); var menu = prepare(f);
                assertEquals(List.of("查看当前单元格", "查看当前行（可见列）", "定位到行…", "恢复原始行序"),
                        menu.getItems().stream().map(i -> i.getText()).toList());
                assertTrue(menu.isFocusTraversable());
                assertFalse(menu.getItems().get(0).isDisable()); assertFalse(menu.getItems().get(1).isDisable());
                assertFalse(menu.getItems().get(2).isDisable()); assertFalse(menu.getItems().get(3).isDisable());
                switch (action) {
                    case "primary" -> menu.fire();
                    case "cell" -> menu.getItems().get(0).fire();
                    case "row" -> menu.getItems().get(1).fire();
                    case "locate" -> menu.getItems().get(2).fire();
                }
                String field = action.equals("row") ? "resultRowDialog" : action.equals("locate") ? "resultRowLocator" : "resultCellDialog";
                var dialog = (Dialog<?>) field(f.pane, field); assertNotNull(dialog); assertTrue(dialog.isShowing());
                if (action.equals("locate")) {
                    var locator = (ResultRowLocateDialog) dialog;
                    ResultRowLocateDialogTest.query(locator).setText("2"); ResultRowLocateDialogTest.confirm(locator).fire();
                    assertEquals(1, f.table().getFocusModel().getFocusedCell().getRow());
                    assertEquals("first", f.pane.captureResultCellPreview().text());
                } else {
                    String id = action.equals("row") ? "#result-row-text" : "#result-cell-text";
                    assertEquals("second", ((TextArea) dialog.getDialogPane().lookup(id)).getText());
                    if (action.equals("row")) assertTrue(((javafx.scene.control.Label) dialog.getDialogPane()
                            .lookup("#result-row-identity")).getText().contains("可见 1 列"));
                    dialog.close(); assertEquals(selection, f.table().getSelectionModel().getSelectedCells());
                }
                assertNull(field(f.pane, field)); assertEquals(rows, f.table().getItems());
                assertFalse(f.table().getColumns().get(2).isVisible()); assertEquals(1, f.table().getSortOrder().size());
                assertEquals(f.original, f.document().physicalText()); assertFalse(f.editor.isUndoAvailable());
                assertEquals(2, f.editor.getAnchor()); assertEquals(9, f.editor.getCaretPosition()); return null;
            }); f.assertOffline();
        }
    }

    @Test void availabilityTracksSelectionEmptyResultsAndResetPreservesFilteredRows() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                assertTrue(menu(f).isDisabled()); query(f); var menu = prepare(f);
                assertTrue(menu.getItems().get(0).isDisable()); assertTrue(menu.getItems().get(1).isDisable());
                assertFalse(menu.getItems().get(2).isDisable()); assertTrue(menu.getItems().get(3).isDisable());
                sort(f); select(f); var selected = f.table().getItems().getFirst(); prepare(f).getItems().get(3).fire();
                assertEquals(List.of("first", "second"), f.table().getItems().stream().map(r -> r.getFirst()).toList());
                assertSame(selected, f.table().getItems().get(1)); assertEquals(1, f.table().getFocusModel().getFocusedCell().getRow());
                assertTrue(prepare(f).getItems().get(3).isDisable());
                search(f, "absent"); var col = f.table().getColumns().get(1); f.table().getSortOrder().setAll(List.of(col));
                menu = prepare(f); assertFalse(menu.isDisabled());
                assertTrue(menu.getItems().get(0).isDisable()); assertTrue(menu.getItems().get(1).isDisable());
                assertTrue(menu.getItems().get(2).isDisable()); assertFalse(menu.getItems().get(3).isDisable());
                menu.getItems().get(3).fire(); assertTrue(f.table().getSortOrder().isEmpty()); assertTrue(f.table().getItems().isEmpty());
                assertEquals("absent", ((TextField) f.root.lookup("#sql-result-search")).getText());
                search(f, ""); assertEquals(List.of("first", "second"), f.table().getItems().stream().map(r -> r.getFirst()).toList());
                f.show(List.of(new ScriptOutcome(1, "empty", QueryResult.query(List.of("n"), List.of(), 1))));
                assertTrue(prepare(f).getItems().stream().allMatch(i -> i.isDisable()));
                f.show(List.of(new ScriptOutcome(1, "update", QueryResult.update(1, 1)))); assertTrue(menu(f).isDisabled());
                return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"replacement", "filter", "plan", "overview", "busy", "resources", "tasks", "finalized", "disabled", "table-disabled", "admission", "queue"})
    void lateMenuActionsRejectChangedOrBlockedView(String change) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f); sort(f); select(f);
                var actions = prepare(f).getItems().stream().map(i -> i.getOnAction()).toList();
                switch (change) {
                    case "replacement" -> { query(f); sort(f); select(f); }
                    case "filter" -> search(f, "first");
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "synthetic", 1L, 1);
                    case "overview" -> f.batch();
                    case "busy" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "table-disabled" -> f.table().setDisable(true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                }
                var rows = f.table().getItems(); var values = List.copyOf(rows); var sorting = List.copyOf(f.table().getSortOrder());
                var selection = List.copyOf(f.table().getSelectionModel().getSelectedCells());
                for (var action : actions) action.handle(new ActionEvent());
                assertNull(field(f.pane, "resultCellDialog")); assertNull(field(f.pane, "resultRowDialog")); assertNull(field(f.pane, "resultRowLocator"));
                assertSame(rows, f.table().getItems()); assertEquals(values, rows); assertEquals(sorting, f.table().getSortOrder());
                assertEquals(selection, f.table().getSelectionModel().getSelectedCells());
                if (change.equals("busy")) {
                    menu(f).getOnAction().handle(new ActionEvent()); assertNull(field(f.pane, "resultCellDialog"));
                    invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false);
                    assertFalse(prepare(f).getItems().get(3).isDisable());
                }
                return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "busy", "root"})
    void openMenuClosesWhenResultChangesOrControlsAreDisabled(String change) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f); var menu = menu(f); menu.show(); assertTrue(menu.isShowing());
                switch (change) {
                    case "query" -> query(f);
                    case "busy" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                    case "root" -> f.root.setDisable(true);
                }
                assertFalse(menu.isShowing()); return null;
            }); f.assertOffline();
        }
    }
}
