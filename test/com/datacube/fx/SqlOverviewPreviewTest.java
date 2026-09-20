package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.datacube.fx.SqlScriptDetailsIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlOverviewPreviewTest {
    @TempDir Path directory;
    private SqlScriptDetailsIntegrationTest.Fixture fixture() throws Exception {
        var owner = new SqlScriptDetailsIntegrationTest(); owner.directory = directory; return owner.new Fixture();
    }

    @ParameterizedTest @ValueSource(strings = {"result", "details"})
    void previewSortAndFailureFilterKeepOriginalSqlAndResultIdentity(String action) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.window();
                String sql = "  select\r\n\t'Z  <b>literal</b>' from sample;\n-- original snapshot  ";
                f.show(List.of(new ScriptOutcome(7, "select A", QueryResult.update(1, 2)),
                        new ScriptOutcome(7, sql, QueryResult.error("original error", 2)),
                        new ScriptOutcome(7, "select B", QueryResult.timeout("timeout", 3))));
                assertEquals(List.of("#", "类型", "耗时", "SQL 摘要", "结果"), f.table().getColumns().stream().map(TableColumn::getText).toList());
                assertEquals("select  'Z  <b>literal</b>' from sample; -- original snapshot", preview(f).getCellData(1));
                var column = preview(f); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
                // ASCII space sorts before A/B, so the multiline statement is last in descending order.
                f.select(2); var originalEntry = f.details().selectedEntry(); assertEquals(sql, originalEntry.sql().value());
                f.editor.insertText(0, "-- changed after execution\n"); f.editor.selectRange(2, 8); String draft = f.editor.getText();
                ((CheckBox) f.root.lookup("#sql-script-only-failures")).fire();
                assertEquals(2, f.table().getItems().size()); assertSame(originalEntry, f.details().selectedEntry());
                assertEquals("select B", preview(f).getCellData(0));
                if (action.equals("result")) {
                    ((Button) f.root.lookup("#sql-script-result")).fire();
                    assertSame(originalEntry, chooser(f).getValue().detail());
                    assertEquals("original error", f.table().getItems().getFirst().getFirst());
                    assertFalse(f.table().getColumns().stream().anyMatch(c -> c.getText().equals("SQL 摘要")));
                } else {
                    f.table().fireEvent(key(KeyCode.ENTER, false));
                    assertTrue(f.dialog().isShowing());
                    assertEquals(sql.replace("\r\n", "\n"), ((TextArea) f.dialog().getDialogPane().lookup("#sql-script-detail-sql")).getText());
                    assertEquals("original error", ((TextArea) f.dialog().getDialogPane().lookup("#sql-script-detail-error")).getText());
                }
                assertEquals(draft, f.editor.getText()); assertEquals(2, f.editor.getAnchor()); assertEquals(8, f.editor.getCaretPosition());
                f.editor.undo(); assertEquals(f.original, f.document().physicalText()); assertFalse(f.document().dirty());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "batch", "clear", "close"})
    void replacementDoesNotReuseOldPreview(String replacement) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.batch(); assertEquals("select 1", preview(f).getCellData(0));
                switch (replacement) {
                    case "query" -> f.show(List.of(new ScriptOutcome(1, "fresh query", QueryResult.query(List.of("v"), List.of(List.of(42)), 1))));
                    case "batch" -> f.show(List.of(new ScriptOutcome(8, "fresh update", QueryResult.update(1, 0)), new ScriptOutcome(9, "fresh error", QueryResult.error("new", 1))));
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                assertNull(f.details().selectedEntry());
                if (replacement.equals("batch")) {
                    assertEquals("fresh update", preview(f).getCellData(0)); assertEquals("fresh error", preview(f).getCellData(1));
                } else {
                    assertFalse(f.details().getNode().isVisible());
                    if (replacement.equals("query")) {
                        assertEquals(42, f.table().getItems().getFirst().getFirst());
                        assertFalse(f.table().getColumns().stream().anyMatch(c -> c.getText().equals("SQL 摘要")));
                    }
                }
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "480,light", "980,dark", "980,light"})
    void previewColumnStaysBoundedAndDoesNotExpandThePanel(double width, String theme) throws Exception {
        try (var f = fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, width, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(), ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.show(List.of(new ScriptOutcome(1, "x".repeat(20000), QueryResult.update(1, 1)), new ScriptOutcome(2, "select 2", QueryResult.update(1, 1))));
                f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                assertEquals("x".repeat(120) + "…", preview(f).getCellData(0));
                assertTrue(preview(f).getWidth() >= 120); assertTrue(preview(f).getWidth() <= 360);
                assertTrue(f.table().getWidth() <= width); assertTrue(f.table().getHeight() >= 100);
                for (String id : List.of("sql-script-details", "sql-script-result", "sql-script-only-failures")) {
                    var node = f.root.lookup("#" + id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1, id);
                }
                return null;
            });
        }
    }

    private static TableColumn<javafx.collections.ObservableList<Object>, ?> preview(SqlScriptDetailsIntegrationTest.Fixture f) {
        return f.table().getColumns().stream().filter(column -> column.getText().equals("SQL 摘要")).findFirst().orElseThrow(() -> new AssertionError("overview needs a SQL preview column"));
    }
    @SuppressWarnings("unchecked") private static ComboBox<SqlBatchResults.Choice> chooser(SqlScriptDetailsIntegrationTest.Fixture f) { return (ComboBox<SqlBatchResults.Choice>) f.root.lookup("#sql-batch-choice"); }
}
