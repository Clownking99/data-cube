package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.List;
import javafx.event.ActionEvent;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultColumnResetTest {
    @TempDir Path directory;

    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static void query(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        f.show(List.of(new ScriptOutcome(1, "select synthetic", QueryResult.query(List.of("same", "same", "tail"),
                List.of(List.of("Ada", 1, "A"), List.of("Ada", 3, "B"), List.of("Bob", 9, "C")), 1))));
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void restoresColumnsWithoutLosingFilteredSortedRowsSelectionOrExportScope(String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.window(); query(f); f.root.applyCss(); f.root.layout();
                var table = f.table(); var initial = List.copyOf(table.getColumns());
                var widths = initial.stream().map(TableColumn::getPrefWidth).toList();
                assertTrue(reset(f).isDisable());
                var name = initial.get(1); var score = initial.get(2); var tail = initial.get(3);
                table.getColumns().setAll(List.of(initial.get(0), tail, score, name));
                table.resizeColumn(score, 120); name.setVisible(false); tail.setVisible(false);
                score.setSortType(TableColumn.SortType.DESCENDING); table.getSortOrder().setAll(List.of(score)); table.sort();
                var search = (TextField) f.root.lookup("#sql-result-search"); search.setText("Ada"); search.fireEvent(new ActionEvent());
                f.root.applyCss(); f.root.layout();
                assertTrue(score.getWidth() > widths.get(2) + 50);
                assertEquals(List.of(3, 1), table.getItems().stream().map(row -> row.get(1)).toList());
                table.getSelectionModel().clearAndSelect(0, score); table.getSelectionModel().select(1, score);
                table.getFocusModel().focus(1, score);
                var rows = table.getItems(); var row0 = rows.getFirst(); var row1 = rows.getLast();
                assertEquals(List.of("same"), f.pane.captureResultExportSnapshot().columns());
                assertFalse(reset(f).isDisable()); reset(f).fire(); f.root.applyCss(); f.root.layout();
                assertEquals(initial, table.getColumns()); assertTrue(initial.stream().allMatch(TableColumn::isVisible));
                for (int i = 0; i < initial.size(); i++) {
                    assertEquals(widths.get(i), initial.get(i).getPrefWidth(), 0.01);
                    assertEquals(widths.get(i), initial.get(i).getWidth(), 0.01);
                }
                assertSame(rows, table.getItems()); assertSame(row0, rows.getFirst()); assertSame(row1, rows.getLast());
                assertEquals(List.of(score), table.getSortOrder()); assertEquals(TableColumn.SortType.DESCENDING, score.getSortType());
                assertEquals("Ada", search.getText());
                assertTrue(table.getSelectionModel().isSelected(0, score)); assertTrue(table.getSelectionModel().isSelected(1, score));
                assertEquals(2, table.getSelectionModel().getSelectedCells().size());
                assertEquals(1, table.getFocusModel().getFocusedCell().getRow());
                assertSame(score, table.getFocusModel().getFocusedCell().getTableColumn());
                var snapshot = f.pane.captureResultExportSnapshot();
                assertEquals(List.of("same", "same", "tail"), snapshot.columns());
                assertEquals("select synthetic", snapshot.originalSql());
                assertEquals(List.of(List.of("Ada", 3, "B"), List.of("Ada", 1, "A")),
                        snapshot.rows(com.datacube.sqleditor.result.ResultExportScope.CURRENT_FILTERED));
                assertEquals(List.of(List.of("Ada", 1, "A"), List.of("Ada", 3, "B"), List.of("Bob", 9, "C")),
                        snapshot.rows(com.datacube.sqleditor.result.ResultExportScope.ALL_LOADED));
                assertTrue(reset(f).isDisable()); assertEquals("列（3/3）", menu(f).getText());
                assertEquals(f.original, f.document().physicalText()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"width", "order", "visibility"})
    void eachLayoutChangeCanBeRestoredAndMenuRefreshDoesNotRebase(String change) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f); var initial = List.copyOf(f.table().getColumns()); var first = initial.get(1);
                double width = first.getPrefWidth();
                switch (change) {
                    case "width" -> first.setPrefWidth(width + 100);
                    case "order" -> javafx.collections.FXCollections.reverse(f.table().getColumns());
                    case "visibility" -> first.setVisible(false);
                }
                controller(f).refresh(true); controller(f).refresh(true);
                assertFalse(reset(f).isDisable()); reset(f).fire();
                assertEquals(initial, f.table().getColumns()); assertEquals(width, first.getPrefWidth()); assertTrue(first.isVisible());
                assertTrue(reset(f).isDisable());
                var rows = f.table().getItems(); reset(f).getOnAction().handle(new ActionEvent()); assertSame(rows, f.table().getItems());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"busy", "root-disabled", "table-disabled", "menu-disabled", "closed", "unavailable", "removed", "new-query", "clear"})
    void staleOrUnavailableResetCannotChangeCurrentColumns(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f); var column = f.table().getColumns().get(1); column.setPrefWidth(333);
                var action = reset(f).getOnAction();
                switch (blocker) {
                    case "busy" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                    case "root-disabled" -> f.root.setDisable(true);
                    case "table-disabled" -> f.table().setDisable(true);
                    case "menu-disabled" -> menu(f).setDisable(true);
                    case "closed" -> controller(f).close();
                    case "unavailable" -> controller(f).refresh(false);
                    case "removed" -> f.table().getColumns().remove(2);
                    case "new-query" -> { query(f); f.table().getColumns().get(1).setPrefWidth(444); }
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                }
                var before = List.copyOf(f.table().getColumns()); var widths = before.stream().map(TableColumn::getPrefWidth).toList();
                var rows = f.table().getItems(); action.handle(new ActionEvent());
                assertEquals(before, f.table().getColumns()); assertEquals(widths, before.stream().map(TableColumn::getPrefWidth).toList());
                assertSame(rows, f.table().getItems()); assertEquals(333, column.getPrefWidth());
                if (blocker.equals("new-query")) { assertFalse(reset(f).isDisable()); reset(f).fire(); assertNotEquals(444, f.table().getColumns().get(1).getPrefWidth()); }
                if (blocker.equals("busy")) { invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, false); reset(f).fire(); assertNotEquals(333, column.getPrefWidth()); }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void singleColumnAndEmptyRowsStillHaveRestorableWidths(boolean empty) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                assertTrue(menu(f).isDisabled());
                f.show(List.of(new ScriptOutcome(1, "synthetic", QueryResult.query(List.of("one"), empty ? List.of() : List.of(List.of(7)), 1))));
                var col = f.table().getColumns().get(1); double width = col.getPrefWidth(); col.setPrefWidth(350);
                var rows = f.table().getItems(); assertFalse(reset(f).isDisable()); reset(f).fire();
                assertEquals(width, col.getPrefWidth()); assertSame(rows, f.table().getItems()); assertEquals(empty ? 0 : 1, rows.size());
                assertTrue(col.isVisible()); assertTrue(reset(f).isDisable()); return null;
            });
            f.assertOffline();
        }
    }

    @Test void showingAllColumnsDoesNotResetOrderOrWidth() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                query(f); var first = f.table().getColumns().get(1); first.setPrefWidth(345); first.setVisible(false);
                javafx.collections.FXCollections.reverse(f.table().getColumns()); var order = List.copyOf(f.table().getColumns());
                reset(f); menu(f).getItems().stream().filter(item -> "sql-result-columns-show-all".equals(item.getId())).findFirst().orElseThrow().fire();
                assertTrue(first.isVisible()); assertEquals(345, first.getPrefWidth()); assertEquals(order, f.table().getColumns());
                assertFalse(reset(f).isDisable()); return null;
            });
            f.assertOffline();
        }
    }

    @Test void resetWithNoSelectionDoesNotChooseARowAndBatchSwitchGetsNewDefaults() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "one", QueryResult.query(List.of("short"), List.of(List.of(1)), 1)),
                        new ScriptOutcome(2, "two", QueryResult.query(List.of("different wide header"), List.of(List.of("new value")), 2))));
                var oldColumn = f.table().getColumns().get(1); oldColumn.setPrefWidth(333);
                var oldReset = reset(f).getOnAction();
                var choices = (javafx.scene.control.ComboBox<?>) f.root.lookup("#sql-batch-choice");
                choices.getSelectionModel().select(2);
                var current = f.table().getColumns().get(1); double initialWidth = current.getPrefWidth();
                current.setPrefWidth(444); f.table().getSelectionModel().clearSelection(); f.table().getFocusModel().focus(-1);
                oldReset.handle(new ActionEvent()); assertEquals(444, current.getPrefWidth()); assertEquals(333, oldColumn.getPrefWidth());
                reset(f).fire(); assertEquals(initialWidth, current.getPrefWidth());
                assertTrue(f.table().getSelectionModel().isEmpty()); assertEquals(-1, f.table().getFocusModel().getFocusedCell().getRow());
                assertEquals(List.of("new value"), f.table().getItems().getFirst());
                assertEquals("two", f.pane.captureResultExportSnapshot().originalSql()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"shown", "unshown", "detached"})
    void restoredLayoutStaysDisabledAfterNativeLayoutPulses(String state) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-dark.css").toExternalForm());
                var scene = f.root.getScene();
                if (state.equals("shown")) f.window();
                if (state.equals("detached")) scene.setRoot(new javafx.scene.layout.VBox());
                f.show(List.of(new ScriptOutcome(3, "synthetic", QueryResult.query(List.of("status", "message"),
                        List.of(List.of("READY", "second query result"), List.of("WAITING", "another row")), 8))));
                if (state.equals("detached")) scene.setRoot(f.root);
                if (!state.equals("shown")) f.window();
                return null;
            });
            pulse(f);
            var initialWidths = FxUiTestSupport.call(() -> f.table().getColumns().stream().map(TableColumn::getWidth).toList());
            FxUiTestSupport.call(() -> {
                assertTrue(reset(f).isDisable(), widths(f));
                var message = f.table().getColumns().get(2);
                var preferred = message.getPrefWidth();
                f.table().resizeColumn(message, 171);
                assertEquals(preferred, message.getPrefWidth(), "native header resize changes actual width only");
                assertFalse(reset(f).isDisable(), "actual-width-only change must be restorable");
                reset(f).fire(); return null;
            });
            pulse(f);
            FxUiTestSupport.call(() -> {
                assertTrue(reset(f).isDisable(), widths(f));
                assertEquals(initialWidths, f.table().getColumns().stream().map(TableColumn::getWidth).toList());
                f.table().resizeColumn(f.table().getColumns().get(2), 171);
                javafx.collections.FXCollections.reverse(f.table().getColumns());
                var status = f.table().getColumns().get(1); status.setVisible(false);
                reset(f).fire(); return null;
            });
            pulse(f);
            FxUiTestSupport.call(() -> {
                assertTrue(reset(f).isDisable(), widths(f));
                assertEquals(List.of("#", "status", "message"), f.table().getColumns().stream().map(TableColumn::getText).toList());
                assertEquals(initialWidths, f.table().getColumns().stream().map(TableColumn::getWidth).toList());
                assertTrue(f.table().getColumns().stream().allMatch(TableColumn::isVisible));
                return null;
            });
            f.assertOffline();
        }
    }
    private static String widths(SqlScriptDetailsIntegrationTest.Fixture f) {
        return f.table().getColumns().stream().map(c -> c.getText() + " pref=" + c.getPrefWidth() + " actual=" + c.getWidth()).toList().toString();
    }

    @ParameterizedTest @ValueSource(strings = {"new-query", "reattach", "clear", "closed"})
    void obsoleteFirstLayoutCannotReplaceOrCancelCurrentBaseline(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                var scene = f.root.getScene(); scene.setRoot(new javafx.scene.layout.VBox()); query(f);
                scene.setRoot(f.root);
                var stale = (Runnable) field(controller(f), "sizingPulse"); assertNotNull(stale);
                scene.setRoot(new javafx.scene.layout.VBox());
                switch (replacement) {
                    case "new-query" -> query(f);
                    case "clear" -> controller(f).refresh(false);
                    case "closed" -> controller(f).close();
                }
                scene.setRoot(f.root); f.window();
                var pending = field(controller(f), "sizingPulse");
                if (replacement.equals("new-query") || replacement.equals("reattach")) assertNotNull(pending);
                else assertNull(pending);
                stale.run(); assertSame(pending, field(controller(f), "sizingPulse"));
                return null;
            });
            pulse(f);
            FxUiTestSupport.call(() -> {
                assertNull(field(controller(f), "sizingPulse"));
                if (replacement.equals("new-query") || replacement.equals("reattach")) {
                    assertTrue(reset(f).isDisable());
                    var column = f.table().getColumns().get(1); double initial = column.getWidth();
                    f.table().resizeColumn(column, 100); assertFalse(reset(f).isDisable()); reset(f).fire();
                    assertEquals(initial, column.getWidth(), 0.01);
                } else assertTrue(menu(f).isDisabled());
                return null;
            });
            f.assertOffline();
        }
    }
    private static void pulse(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        FxUiTestSupport.call(() -> {
            var scene = f.root.getScene();
            scene.addPostLayoutPulseListener(new Runnable() { public void run() { scene.removePostLayoutPulseListener(this); completed.complete(null); } });
            javafx.application.Platform.requestNextPulse(); return null;
        });
        completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static SqlResultColumnMenu controller(SqlScriptDetailsIntegrationTest.Fixture f) { return (SqlResultColumnMenu) field(f.pane, "resultColumnMenu"); }
    private static MenuButton menu(SqlScriptDetailsIntegrationTest.Fixture f) { return (MenuButton) f.root.lookup("#sql-result-columns"); }
    private static MenuItem reset(SqlScriptDetailsIntegrationTest.Fixture f) {
        menu(f).getOnShowing().handle(new javafx.event.Event(javafx.event.Event.ANY));
        return menu(f).getItems().stream().filter(item -> "sql-result-columns-reset".equals(item.getId())).findFirst().orElseThrow();
    }
}
