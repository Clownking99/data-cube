package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.ResultCellPreview;
import java.sql.Types;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResultCellDialogTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"NULL"})
    void nullEmptyAndLiteralNullHaveDifferentVisibleStates(String value) throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.query(List.of("value"), List.of(java.util.Arrays.asList(value)), 0);
            var dialog = new ResultCellDialog(null, ResultCellPreview.capture(result, 0, 0, 0));
            var root = dialog.getDialogPane();
            var summary = (Label) root.lookup("#result-cell-summary");
            String prefix = value == null ? "NULL（数据库空值）" : value.isEmpty() ? "空字符串" : "非 NULL 值";
            assertTrue(summary.getText().startsWith(prefix));
            assertEquals(value == null ? "" : value, ((TextArea) root.lookup("#result-cell-text")).getText());
            return null;
        });
    }

    @Test void readOnlyContentAndWrapKeepSnapshotIntactAndEscapeCloses() throws Exception {
        FxUiTestSupport.call(() -> {
            String text = "<html>中😀</html>\n  long line";
            var preview = ResultCellPreview.capture(QueryResult.query(List.of("value"), List.of(List.of(text)), 0), 0, 0, 0);
            var dialog = new ResultCellDialog(null, preview);
            try {
                dialog.show();
                var area = (TextArea) dialog.getDialogPane().lookup("#result-cell-text");
                assertFalse(area.isEditable()); assertEquals(text, area.getText());
                assertTrue(area.isWrapText());
                area.selectRange(2, 8);
                ((CheckBox) dialog.getDialogPane().lookup("#result-cell-wrap")).fire();
                assertFalse(area.isWrapText()); assertEquals(text, area.getText());
                assertEquals(2, area.getAnchor()); assertEquals(8, area.getCaretPosition());
                assertEquals(1, dialog.getDialogPane().getButtonTypes().size(), "no mutation or execution action");
                area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertFalse(dialog.isShowing());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"480, dark", "720, dark", "480, light", "720, light"})
    void shownDialogKeepsLongValueWarningAndControlsVisible(double width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var owner = new Stage();
            owner.setScene(new Scene(new VBox(), 800, 700));
            owner.getScene().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            var result = QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "多行内容", Types.VARCHAR, "VARCHAR")),
                    List.of(List.of("x".repeat(65_537))), 0, false);
            var dialog = new ResultCellDialog(owner, ResultCellPreview.capture(result, 0, 0, 0));
            try {
                owner.show(); dialog.show();
                dialog.setWidth(width); dialog.setHeight(550);
                var root = dialog.getDialogPane(); root.applyCss(); root.layout();
                var query = ResultCellFindTest.query(dialog);
                query.requestFocus();
                query.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
                root.applyCss(); root.layout();
                var prompt = query.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                        .map(javafx.scene.text.Text.class::cast).filter(t -> query.getPromptText().equals(t.getText())).findFirst().orElseThrow();
                assertTrue(prompt.isVisible());
                assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), prompt.getFill());
                query.setText("x"); ResultCellFindTest.button(dialog, "next").fire(); root.applyCss(); root.layout();
                var area = ResultCellFindTest.area(dialog);
                assertSame(query, root.getScene().getFocusOwner()); assertFalse(area.isFocused());
                assertEquals("x", area.getSelectedText());
                assertTrue(area.lookupAll("Path").stream().filter(javafx.scene.shape.Path.class::isInstance)
                        .map(javafx.scene.shape.Path.class::cast).anyMatch(p -> p.isVisible() && !p.getElements().isEmpty()
                                && javafx.scene.paint.Color.web("#6C5CE7").equals(p.getFill())),
                        "unfocused body selection must have a visible contrasting highlight");
                for (String search : List.of("x", "x".repeat(1025), "")) {
                    query.setText(search); root.applyCss(); root.layout();
                    for (String id : List.of("find-query", "find-previous", "find-next", "find-case", "find-status", "wrap", "text", "close")) {
                        Region control = (Region) root.lookup("#result-cell-" + id);
                        var bounds = control.localToScene(control.getLayoutBounds());
                        assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= root.getWidth() + 1, id);
                        assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= root.getHeight() + 1, id);
                        if (control instanceof Label) assertTrue(control.getHeight() + 1 >= control.prefHeight(control.getWidth()), id);
                    }
                    assertTrue(((TextArea) root.lookup("#result-cell-text")).getHeight() >= 100);
                }
                var summary = (Label) root.lookup("#result-cell-summary");
                assertTrue(summary.getText().contains("已截断"));
                assertTrue(summary.getHeight() + 1 >= summary.prefHeight(summary.getWidth()));
                for (String id : List.of("identity", "summary", "boundary", "wrap", "text", "close")) {
                    Region control = (Region) root.lookup("#result-cell-" + id);
                    var bounds = control.localToScene(control.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= root.getWidth() + 1, id);
                    assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= root.getHeight() + 1, id);
                }
                assertTrue(((TextArea) root.lookup("#result-cell-text")).getHeight() >= 100);
                assertEquals(owner.getScene().getStylesheets(), root.getStylesheets());
                ((Button) root.lookup("#result-cell-close")).fire();
                assertFalse(dialog.isShowing());
            } finally { dialog.close(); owner.hide(); }
            return null;
        });
    }

    @Test void pathologicalMetadataScrollsWithoutHidingTruncationWarningOrValue() throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "line\n".repeat(110), Types.VARCHAR, "type\n".repeat(110))),
                    List.of(List.of("x".repeat(65_537))), 0, false);
            var dialog = new ResultCellDialog(null, ResultCellPreview.capture(result, 0, 0, 0));
            try {
                dialog.show(); dialog.setWidth(480); dialog.setHeight(550);
                var root = dialog.getDialogPane(); root.applyCss(); root.layout();
                var metadata = (javafx.scene.control.ScrollPane) root.lookup("#result-cell-metadata");
                assertTrue(metadata.getHeight() <= 100);
                assertTrue(((Label) root.lookup("#result-cell-identity")).getHeight() > metadata.getViewportBounds().getHeight());
                for (String id : List.of("summary", "boundary", "wrap", "text", "close")) {
                    Region control = (Region) root.lookup("#result-cell-" + id);
                    var bounds = control.localToScene(control.getLayoutBounds());
                    assertTrue(bounds.getMaxY() <= root.getHeight() + 1, id);
                }
                assertTrue(dialog.getHeight() <= 550);
                assertTrue(((Label) root.lookup("#result-cell-summary")).getText().contains("已截断"));
            } finally { dialog.close(); }
            return null;
        });
    }
}
