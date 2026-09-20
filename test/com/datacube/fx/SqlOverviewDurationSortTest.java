package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlOverviewDurationSortTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }
    private static void mixed(SqlScriptDetailsIntegrationTest.Fixture f) throws Exception {
        f.show(List.of(new ScriptOutcome(1, "short error", QueryResult.error("short", 9)),
                new ScriptOutcome(2, "update sample", QueryResult.update(80, 2)),
                new ScriptOutcome(3, "slow query", QueryResult.timeout("original timeout", 1000)),
                new ScriptOutcome(4, "zero update", QueryResult.update(0, 0)),
                new ScriptOutcome(5, "select sample", QueryResult.query(List.of("n"), List.of(List.of(1)), 10)),
                new ScriptOutcome(6, "cancel query", QueryResult.cancelled("cancelled", 9)),
                new ScriptOutcome(7, "large update", QueryResult.update(2147483648L, 1)),
                new ScriptOutcome(8, "long error", QueryResult.error("long", Long.MAX_VALUE - 1)),
                new ScriptOutcome(9, "max update", QueryResult.update(Long.MAX_VALUE, 1))));
        chooser(f).getSelectionModel().select(0);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sortsByNumericMillisWithoutChangingLabelsSnapshotsOrDefaultOrder(boolean descending) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), indices(f));
                assertEquals(List.of("9ms", "80ms", "1000ms", "0ms", "10ms", "9ms", "2147483648ms", "9223372036854775806ms", "9223372036854775807ms"), labels(f));
                var choices = List.copyOf(chooser(f).getItems()); var overview = chooser(f).getValue();
                String summary = ((Label) f.root.lookup("#sql-batch-summary")).getText();
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(2, 8); String draft = f.editor.getText();
                sort(f, descending);
                assertEquals(descending ? List.of(9, 8, 7, 3, 2, 5, 1, 6, 4) : List.of(4, 1, 6, 5, 2, 3, 7, 8, 9), indices(f));
                assertSame(overview, chooser(f).getValue()); assertEquals(choices, chooser(f).getItems());
                assertEquals(summary, ((Label) f.root.lookup("#sql-batch-summary")).getText());
                assertNull(f.details().selectedEntry()); assertNull(f.pane.captureResultExportSnapshot());
                for (int row = 0; row < f.table().getItems().size(); row++) {
                    f.select(row); assertEquals(f.details().selectedEntry().elapsedMillis() + "ms", String.valueOf(duration(f).getCellData(row)));
                }
                assertEquals(draft, f.editor.getText()); assertEquals(2, f.editor.getAnchor()); assertEquals(8, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); assertFalse(f.document().dirty());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void equalDurationsAllowSecondaryNumericIndexSort() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(10, "same", QueryResult.update(9, 1)),
                        new ScriptOutcome(2, "same", QueryResult.update(9, 1)), new ScriptOutcome(1, "slower", QueryResult.update(80, 1))));
                sort(f, false); assertEquals(List.of(10, 2, 1), indices(f));
                var index = f.table().getColumns().getFirst(); index.setSortType(TableColumn.SortType.ASCENDING);
                f.table().getSortOrder().setAll(List.of(duration(f), index)); f.table().sort();
                assertEquals(List.of(2, 10, 1), indices(f));
                index.setSortType(TableColumn.SortType.DESCENDING); f.table().sort();
                assertEquals(List.of(10, 2, 1), indices(f)); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"result", "details"})
    void filterAndSortKeepSelectedOutcomeAndOriginalDetails(String action) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); mixed(f); f.select(2); var selected = f.details().selectedEntry();
                sort(f, true); assertSame(selected, f.details().selectedEntry());
                ((CheckBox) f.root.lookup("#sql-script-only-failures")).fire();
                assertEquals(List.of(8, 3, 1, 6), indices(f)); assertSame(selected, f.details().selectedEntry());
                ((CheckBox) f.root.lookup("#sql-script-only-failures")).fire();
                assertEquals(List.of(9, 8, 7, 3, 2, 5, 1, 6, 4), indices(f)); assertSame(selected, f.details().selectedEntry());
                if (action.equals("result")) {
                    ((Button) f.root.lookup("#sql-script-result")).fire();
                    assertSame(selected, chooser(f).getValue().detail());
                    assertEquals("original timeout", f.table().getItems().getFirst().getFirst());
                } else {
                    f.table().fireEvent(key(KeyCode.ENTER, false)); assertTrue(f.dialog().isShowing());
                    assertEquals("slow query", ((TextArea) f.dialog().getDialogPane().lookup("#sql-script-detail-sql")).getText());
                    assertEquals("original timeout", ((TextArea) f.dialog().getDialogPane().lookup("#sql-script-detail-error")).getText());
                    assertTrue(((Label) f.dialog().getDialogPane().lookup("#sql-script-detail-identity")).getText().contains("1000ms"));
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void switchingToQueryDoesNotChangeUserDataOrItsComparator() throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                mixed(f); sort(f, false);
                f.show(List.of(new ScriptOutcome(1, "fresh", QueryResult.query(List.of("耗时"), List.of(List.of("9ms"), List.of("1000ms")), 1))));
                var column = f.table().getColumns().get(1); // Skip the non-sortable row-number column.
                column.setSortType(TableColumn.SortType.ASCENDING); f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
                assertEquals(List.of("1000ms", "9ms"), f.table().getItems().stream().map(row -> row.getFirst()).toList());
                assertFalse(f.details().getNode().isVisible());
                assertNotNull(f.pane.captureResultExportSnapshot());
                mixed(f); assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), indices(f));
                sort(f, true); assertEquals(9, f.table().getItems().getFirst().getFirst()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void defaultCellsStillRenderMillisTextInBothThemes(String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, 640, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(), ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.show(List.of(new ScriptOutcome(1, "first", QueryResult.update(1000, 1)), new ScriptOutcome(2, "second", QueryResult.update(9, 1))));
                sort(f, false); f.root.resize(640, 850); f.root.applyCss(); f.root.layout();
                var texts = f.table().lookupAll(".table-cell").stream().filter(TableCell.class::isInstance).map(TableCell.class::cast)
                        .filter(cell -> cell.getTableColumn() == duration(f) && !cell.isEmpty()).map(TableCell::getText).toList();
                assertEquals(List.of("1000ms", "9ms"), texts.stream().sorted().toList());
                assertEquals("耗时", duration(f).getText()); return null;
            });
        }
    }

    private static List<Object> indices(SqlScriptDetailsIntegrationTest.Fixture f) { return f.table().getItems().stream().map(row -> row.getFirst()).toList(); }
    private static List<String> labels(SqlScriptDetailsIntegrationTest.Fixture f) { return f.table().getItems().stream().map(row -> String.valueOf(row.get(2))).toList(); }
    private static TableColumn<javafx.collections.ObservableList<Object>, ?> duration(SqlScriptDetailsIntegrationTest.Fixture f) { return f.table().getColumns().get(2); }
    private static void sort(SqlScriptDetailsIntegrationTest.Fixture f, boolean descending) {
        var column = duration(f); column.setSortType(descending ? TableColumn.SortType.DESCENDING : TableColumn.SortType.ASCENDING);
        f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
    }
    @SuppressWarnings("unchecked") private static ComboBox<SqlBatchResults.Choice> chooser(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<SqlBatchResults.Choice>) f.root.lookup("#sql-batch-choice"); }
}
