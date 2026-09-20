package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.ResultCellPreview;
import com.datacube.sqleditor.result.ResultRowPreview;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.ResultRowDialogTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ResultRowNullFilterTest {
    @TempDir Path directory;

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"NULL", "null", " ", "0"})
    void hidesOnlyDatabaseNullAndClearRestoresWithoutImplicitSelection(String value) throws Exception {
        FxUiTestSupport.call(() -> {
            var snapshot = ResultRowPreview.capture(QueryResult.query(List.of("value"), List.of(Arrays.asList(value)), 0), 0, List.of(0), 0);
            var dialog = new ResultRowDialog(null, snapshot, 1);
            try {
                dialog.show(); assertFalse(hideNull(dialog).isSelected()); assertTrue(clear(dialog).isDisabled());
                hideNull(dialog).fire();
                assertEquals(value == null ? List.of() : snapshot.fields(), fields(dialog).getItems());
                assertEquals("匹配 " + (value == null ? 0 : 1) + " / 1 个快照字段", label(dialog, "filter-status").getText());
                assertFalse(clear(dialog).isDisabled());
                if (value == null) {
                    assertNull(fields(dialog).getSelectionModel().getSelectedItem());
                    assertEquals("", text(dialog).getText()); assertEquals("", label(dialog, "summary").getText());
                    assertTrue(((Label) fields(dialog).getPlaceholder()).getText().contains("NULL"));
                } else {
                    assertSame(snapshot.fields().getFirst(), fields(dialog).getSelectionModel().getSelectedItem());
                    assertEquals(value, text(dialog).getText());
                }
                clear(dialog).fire(); assertFalse(hideNull(dialog).isSelected()); assertTrue(clear(dialog).isDisabled());
                assertEquals(snapshot.fields(), fields(dialog).getItems());
                if (value == null) assertNull(fields(dialog).getSelectionModel().getSelectedItem());
                assertTrue(snapshot.fields().getFirst().nullValue() == (value == null));
                assertFalse(text(dialog).isEditable());
            } finally { dialog.close(); }
            var reopened = new ResultRowDialog(null, snapshot, 1);
            try { reopened.show(); assertFalse(hideNull(reopened).isSelected()); assertEquals(snapshot.fields(), fields(reopened).getItems()); }
            finally { reopened.close(); }
            return null;
        });
    }

    @Test void combinesNameAndNullFiltersWithoutLosingDuplicateIdentityBodySelectionOrSearch() throws Exception {
        FxUiTestSupport.call(() -> {
            var snapshot = sample(); var dialog = new ResultRowDialog(null, snapshot, 3);
            try {
                dialog.show(); query(dialog).setText(" SAME ");
                ResultRowTextFindTest.query(dialog).setText("target");
                ResultRowTextFindTest.button(dialog, "next").fire();
                var selected = fields(dialog).getSelectionModel().getSelectedItem();
                assertEquals(3, selected.column()); assertEquals("target", text(dialog).getSelectedText());
                int anchor = text(dialog).getAnchor(), caret = text(dialog).getCaretPosition();
                hideNull(dialog).fire();
                assertEquals(List.of(3, 2), fields(dialog).getItems().stream().map(ResultCellPreview::column).toList());
                assertSame(selected, fields(dialog).getSelectionModel().getSelectedItem());
                assertEquals(anchor, text(dialog).getAnchor()); assertEquals(caret, text(dialog).getCaretPosition());
                assertEquals("target", ResultRowTextFindTest.query(dialog).getText());
                assertEquals("匹配 2 / 4 个快照字段", label(dialog, "filter-status").getText());
                clear(dialog).fire();
                assertEquals("清除筛选", clear(dialog).getText()); assertEquals("", query(dialog).getText());
                assertFalse(hideNull(dialog).isSelected()); assertEquals(snapshot.fields(), fields(dialog).getItems());
                assertSame(selected, fields(dialog).getSelectionModel().getSelectedItem());
                assertEquals(anchor, text(dialog).getAnchor()); assertEquals(caret, text(dialog).getCaretPosition());
                assertEquals("target", ResultRowTextFindTest.query(dialog).getText());
                assertTrue(clear(dialog).isDisabled());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void excludingNullClearsDetailAndRestoringNeverSelectsItsDuplicate() throws Exception {
        FxUiTestSupport.call(() -> {
            var snapshot = sample(); var dialog = new ResultRowDialog(null, snapshot, 1);
            try {
                dialog.show(); query(dialog).setText("same"); ResultRowTextFindTest.query(dialog).setText("target");
                assertEquals(1, fields(dialog).getSelectionModel().getSelectedItem().column());
                hideNull(dialog).fire();
                assertNull(fields(dialog).getSelectionModel().getSelectedItem()); assertEquals("", text(dialog).getText());
                assertEquals("", label(dialog, "summary").getText()); assertTrue(ResultRowTextFindTest.button(dialog, "next").isDisabled());
                assertEquals(List.of(3, 2), fields(dialog).getItems().stream().map(ResultCellPreview::column).toList());
                hideNull(dialog).fire();
                assertEquals(List.of(3, 1, 2), fields(dialog).getItems().stream().map(ResultCellPreview::column).toList());
                assertNull(fields(dialog).getSelectionModel().getSelectedItem()); assertEquals("", text(dialog).getText());
                query(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                assertSame(snapshot.fields().getFirst(), fields(dialog).getSelectionModel().getSelectedItem());
                assertEquals("right target", text(dialog).getText()); assertEquals("target", ResultRowTextFindTest.query(dialog).getText());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void zeroMatchesAndOverlongNamesCannotBeBypassedByNullToggle() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = new ResultRowDialog(null, sample(), 3);
            try {
                dialog.show(); hideNull(dialog).fire(); query(dialog).setText("absent");
                assertTrue(fields(dialog).getItems().isEmpty()); assertEquals("匹配 0 / 4 个快照字段", label(dialog, "filter-status").getText());
                query(dialog).setText("x".repeat(257)); hideNull(dialog).fire(); hideNull(dialog).fire();
                assertTrue(fields(dialog).getItems().isEmpty()); assertTrue(label(dialog, "filter-status").getText().contains("最多 256"));
                assertNull(fields(dialog).getSelectionModel().getSelectedItem()); assertEquals("", text(dialog).getText());
                clear(dialog).fire(); assertEquals(4, fields(dialog).getItems().size()); assertFalse(hideNull(dialog).isSelected());
                assertNull(fields(dialog).getSelectionModel().getSelectedItem());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void filteringBoundedSnapshotDoesNotPullInHiddenOrOmittedNonNullFields() throws Exception {
        FxUiTestSupport.call(() -> {
            var names = java.util.stream.IntStream.range(0, 202).mapToObj(i -> "field" + i).toList();
            var row = new java.util.ArrayList<Object>(java.util.Collections.nCopies(202, null));
            row.set(0, "hidden"); row.set(201, "outside snapshot");
            var snapshot = ResultRowPreview.capture(QueryResult.query(names, List.of(row), 0), 0,
                    java.util.stream.IntStream.range(1, 202).boxed().toList(), 0);
            var dialog = new ResultRowDialog(null, snapshot, 2);
            try {
                dialog.show(); hideNull(dialog).fire(); assertTrue(fields(dialog).getItems().isEmpty());
                assertEquals("匹配 0 / 200 个快照字段", label(dialog, "filter-status").getText());
                assertTrue(label(dialog, "identity").getText().contains("前 200")); assertTrue(snapshot.columnsTruncated());
                clear(dialog).fire(); assertEquals(snapshot.fields(), fields(dialog).getItems());
                assertEquals(200, fields(dialog).getItems().size()); assertEquals("", text(dialog).getText());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void actualPaneDialogFilterDoesNotChangeResultRowsColumnsSelectionExportOrSql() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory;
        try (var f = owner.new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.show(List.of(new ScriptOutcome(1, "synthetic", QueryResult.query(List.of("id", "nullable", "empty", "hidden"),
                        List.of(Arrays.asList(7, null, "", "not visible"), Arrays.asList(8, "value", "", "secret")), 1))));
                var table = f.table(); table.getColumns().get(4).setVisible(false);
                table.getSelectionModel().clearAndSelect(0, table.getColumns().get(2)); table.getFocusModel().focus(0, table.getColumns().get(2));
                var rows = table.getItems(); var columns = List.copyOf(table.getColumns());
                var selected = List.copyOf(table.getSelectionModel().getSelectedCells()); var before = f.pane.captureResultExportSnapshot();
                SqlScriptDetailsIntegrationTest.invoke(f.pane, "showResultRow", new Class<?>[]{});
                var dialog = (ResultRowDialog) SqlScriptDetailsIntegrationTest.field(f.pane, "resultRowDialog");
                assertNotNull(dialog); hideNull(dialog).fire(); query(dialog).setText("empty");
                assertEquals(List.of(3), fields(dialog).getItems().stream().map(ResultCellPreview::column).toList());
                clear(dialog).fire(); dialog.close();
                assertSame(rows, table.getItems()); assertEquals(columns, table.getColumns());
                assertEquals(selected, table.getSelectionModel().getSelectedCells()); assertFalse(columns.get(4).isVisible());
                assertEquals(before.columns(), f.pane.captureResultExportSnapshot().columns());
                assertEquals(before.rows(com.datacube.sqleditor.result.ResultExportScope.CURRENT_FILTERED),
                        f.pane.captureResultExportSnapshot().rows(com.datacube.sqleditor.result.ResultExportScope.CURRENT_FILTERED));
                assertEquals(f.original, f.document().physicalText()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "720,dark", "480,light", "720,light"})
    void filtersAndValidationRemainReadableInNarrowThemedDialogs(double width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var owner = new javafx.stage.Stage(); owner.setScene(new javafx.scene.Scene(new javafx.scene.layout.VBox(), 900, 800));
            owner.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            var dialog = new ResultRowDialog(owner, sample(), 1);
            try {
                owner.show(); dialog.show(); dialog.setWidth(width); dialog.setHeight(740);
                hideNull(dialog).fire();
                for (String term : List.of("", "other", "x".repeat(257))) {
                    query(dialog).setText(term);
                    var root = dialog.getDialogPane(); root.applyCss(); root.layout();
                    for (String id : List.of("query", "clear", "hide-null", "filter-status", "fields", "text", "close")) {
                        var node = (javafx.scene.layout.Region) root.lookup("#result-row-" + id);
                        var bounds = node.localToScene(node.getLayoutBounds());
                        assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= root.getWidth() + 1, id);
                        assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= root.getHeight() + 1, id);
                        if (node instanceof Label) assertTrue(node.getHeight() + 1 >= node.prefHeight(node.getWidth()), id);
                    }
                    assertTrue(hideNull(dialog).getWidth() + 1 >= hideNull(dialog).prefWidth(-1));
                    assertTrue(clear(dialog).getWidth() + 1 >= clear(dialog).prefWidth(-1));
                }
                hideNull(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertFalse(dialog.isShowing());
            } finally { dialog.close(); owner.close(); }
            return null;
        });
    }

    private static ResultRowPreview sample() {
        return ResultRowPreview.capture(QueryResult.query(List.of("same", "same", "SAME", "other", "hidden"),
                List.of(Arrays.asList(null, "", "right target", "value", "hidden")), 0), 0, List.of(2, 0, 1, 3), 0);
    }
    static CheckBox hideNull(ResultRowDialog dialog) {
        var box = (CheckBox) dialog.getDialogPane().lookup("#result-row-hide-null");
        assertNotNull(box, "Row details need an explicit hide NULL control"); return box;
    }
    static Button clear(ResultRowDialog dialog) { return (Button) dialog.getDialogPane().lookup("#result-row-clear"); }
}
