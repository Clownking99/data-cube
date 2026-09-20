package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.ResultFilterState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultSearchNavigationTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static void query(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        f.show(List.of(new ScriptOutcome(1, "select synthetic", QueryResult.query(List.of("name", "score"),
                List.of(List.of("first", 2), List.of("second", 1), List.of("third", 0)), 1))));
    }
    private static TextField search(SqlScriptDetailsIntegrationTest.Fixture f) { return (TextField) f.root.lookup("#sql-result-search"); }
    private static void focus(Node target) { target.requestFocus(); assertSame(target, target.getScene().getFocusOwner()); }
    private static KeyEvent find() { return key(KeyCode.F, true); }
    private static KeyEvent escape() { return key(KeyCode.ESCAPE, false); }
    private static SqlPanelLayout layout(SqlScriptDetailsIntegrationTest.Fixture f) { return (SqlPanelLayout) field(f.pane, "panelLayout"); }

    @ParameterizedTest @ValueSource(strings = {"table", "header", "toolbar", "search", "results-only"})
    void findFromResultAreaSelectsPendingSearchWithoutChangingResultOrEditor(String source) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f); var table = f.table(); var score = table.getColumns().get(2);
                table.getSortOrder().setAll(List.of(score)); table.sort(); score.setVisible(false);
                var name = table.getColumns().get(1); table.getSelectionModel().clearAndSelect(1, name); table.getFocusModel().focus(1, name);
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(2, 8); String draft = f.editor.getText();
                if (source.equals("results-only")) assertTrue(layout(f).select(SqlPanelLayout.Mode.RESULTS));
                search(f).setText("first"); var rows = List.copyOf(table.getItems());
                var selection = List.copyOf(table.getSelectionModel().getSelectedCells());
                Node target = switch (source) {
                    case "toolbar" -> f.root.lookup("#sql-result-copy");
                    case "search" -> search(f);
                    case "header" -> { f.root.applyCss(); f.root.layout(); yield table.lookup(".column-header-background"); }
                    default -> table;
                };
                assertNotNull(target); target.fireEvent(find());
                assertSame(search(f), f.root.getScene().getFocusOwner()); assertEquals("first", search(f).getSelectedText());
                assertEquals(rows, table.getItems()); assertEquals(3, rows.size(), "focus must not flush pending search");
                assertEquals(selection, table.getSelectionModel().getSelectedCells()); assertEquals(1, table.getFocusModel().getFocusedCell().getRow());
                assertEquals(List.of(score), table.getSortOrder()); assertFalse(score.isVisible());
                assertEquals(source.equals("results-only") ? SqlPanelLayout.Mode.RESULTS : SqlPanelLayout.Mode.SPLIT, layout(f).mode());
                assertFalse(((SqlFindBar) field(f.pane, "findBar")).getNode().isVisible());
                assertEquals(draft, f.editor.getText()); assertEquals(2, f.editor.getAnchor()); assertEquals(8, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"first", "absent", ""})
    void escapeCommitsOnlyLocalSearchAndReturnsWithoutInventingSelection(String text) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f);
                var state = (ResultFilterState) field(f.pane, "resultFilterState");
                state.setConditions(List.of(new com.datacube.sqleditor.result.FilterCondition(1,
                        com.datacube.sqleditor.result.FilterConnector.AND, com.datacube.sqleditor.result.FilterOperator.GTE, "1")));
                invoke(f.pane, "renderResultFilterSnapshot", new Class<?>[]{}); var before = state.snapshot();
                focus(search(f)); search(f).setText(text); search(f).fireEvent(escape());
                assertSame(f.table(), f.root.getScene().getFocusOwner()); assertEquals(text, search(f).getText());
                assertEquals(text.equals("first") ? List.of("first") : text.equals("absent") ? List.of() : List.of("first", "second"),
                        f.table().getItems().stream().map(row -> row.getFirst()).toList());
                assertTrue(f.table().getSelectionModel().isEmpty()); assertEquals(before.conditions(), state.snapshot().conditions());
                assertSame(before.activeResult(), state.snapshot().activeResult());
                assertFalse(((SqlResultToolbar) field(f.pane, "resultToolbar")).flushPendingSearch(), "Esc has committed the pending text");
                f.table().fireEvent(find()); assertSame(search(f), f.root.getScene().getFocusOwner());
                assertEquals(text, search(f).getSelectedText()); assertEquals(f.original, f.document().physicalText()); return null;
            }); f.assertOffline();
        }
    }

    @Test void emptyQueryStillOffersSearchAndSqlEditorAndButtonStillFindSql() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(List.of(new ScriptOutcome(1, "empty", QueryResult.query(List.of("n"), List.of(), 1))));
                f.table().fireEvent(find()); assertSame(search(f), f.root.getScene().getFocusOwner());
                focus(f.editor); f.editor.fireEvent(find());
                assertSame(f.root.lookup("#sql-find-query"), f.root.getScene().getFocusOwner());
                ((SqlFindBar) field(f.pane, "findBar")).hide(false);
                assertTrue(layout(f).select(SqlPanelLayout.Mode.RESULTS));
                ((Button) f.root.lookup("#sql-find")).fire(); assertEquals(SqlPanelLayout.Mode.SPLIT, layout(f).mode());
                assertSame(f.root.lookup("#sql-find-query"), f.root.getScene().getFocusOwner());
                assertTrue(f.table().getItems().isEmpty()); assertEquals(f.original, f.document().physicalText()); return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"overview", "update", "error", "plan"})
    void nonQueryResultsRetainExistingSqlFindRoute(String variant) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f);
                switch (variant) {
                    case "overview" -> f.batch();
                    case "update" -> f.show(List.of(new ScriptOutcome(1, "u", QueryResult.update(1, 1))));
                    case "error" -> f.show(List.of(new ScriptOutcome(1, "e", QueryResult.error("synthetic", 1))));
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "synthetic", 1L, 1);
                }
                Node target = variant.equals("plan") ? (Node) field(f.pane, "planArea") : f.table();
                target.fireEvent(find()); assertSame(f.root.lookup("#sql-find-query"), f.root.getScene().getFocusOwner());
                assertTrue(search(f).isDisabled()); assertEquals(f.original, f.document().physicalText()); return null;
            }); f.assertOffline();
        }
    }

    @Test void liveRebindingWorksAndModifiedEscapeDoesNotSubmitOrLeaveSearch() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f); var shortcuts = (ShortcutSettings) field(f.pane, "shortcuts");
                shortcuts.apply(Map.of(ShortcutAction.SQL_FIND, KeyCombination.keyCombination("Ctrl+J")));
                focus(f.table()); f.table().fireEvent(find()); assertSame(f.table(), f.root.getScene().getFocusOwner());
                f.table().fireEvent(key(KeyCode.J, true)); assertSame(search(f), f.root.getScene().getFocusOwner());
                search(f).setText("absent");
                for (int i = 0; i < 4; i++) search(f).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                        i == 0, i == 1, i == 2, i == 3));
                assertSame(search(f), f.root.getScene().getFocusOwner()); assertEquals(3, f.table().getItems().size());
                shortcuts.apply(Map.of()); search(f).fireEvent(find()); assertEquals("absent", search(f).getSelectedText());
                search(f).fireEvent(escape()); assertSame(f.table(), f.root.getScene().getFocusOwner());
                assertTrue(f.table().getItems().isEmpty()); return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"busy", "running", "resources", "tasks", "finalized", "root", "table", "admission", "queue"})
    void blockedResultShortcutsDoNotCommitOrStealFocus(String blocker) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f); focus(search(f)); search(f).setText("absent");
                switch (blocker) {
                    case "busy" -> invoke(f.pane, "setButtonsRunning", new Class<?>[]{boolean.class}, true);
                    case "running" -> setField(f.pane, "running", true);
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "root" -> f.root.setDisable(true);
                    case "table" -> f.table().setDisable(true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                }
                Node before = f.root.getScene().getFocusOwner(); var rows = List.copyOf(f.table().getItems());
                search(f).fireEvent(escape()); f.table().fireEvent(find());
                assertSame(before, f.root.getScene().getFocusOwner()); assertEquals(rows, f.table().getItems());
                assertFalse(((SqlFindBar) field(f.pane, "findBar")).getNode().isVisible());
                assertEquals(f.original, f.document().physicalText());
                if (blocker.equals("running")) setField(f.pane, "running", false); return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void searchPromptRemainsReadableAcrossFocusAndThemeChanges(String firstTheme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); query(f);
                for (String theme : List.of(firstTheme, firstTheme.equals("dark") ? "light" : "dark")) {
                    f.root.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                            ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                    for (boolean focused : List.of(false, true)) {
                        focus(focused ? search(f) : f.table()); f.root.applyCss(); f.root.layout();
                        assertEquals(Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), promptFill(search(f)));
                        assertFalse(search(f).getPromptText().isBlank()); assertTrue(search(f).getAccessibleHelp().contains("已加载"));
                    }
                }
                assertEquals(3, f.table().getItems().size()); return null;
            }); f.assertOffline();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private static Object promptFill(TextField input) {
        javafx.css.CssMetaData data = input.getCssMetaData().stream().filter(item -> item.getProperty().equals("-fx-prompt-text-fill")).findFirst().orElseThrow();
        return data.getStyleableProperty(input).getValue();
    }
}
