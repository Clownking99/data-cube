package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.ResultCellPreview;
import com.datacube.sqleditor.result.ResultRowPreview;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
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

class ResultRowDialogTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"NULL"})
    void nullEmptyAndLiteralNullStayDistinctInListAndDetail(String value) throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.query(List.of("value"), List.of(Arrays.asList(value)), 0);
            var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(0), 0), 1);
            try {
                dialog.show(); var list = fields(dialog);
                String kind = value == null ? "NULL（数据库空值）" : value.isEmpty() ? "空字符串" : "非 NULL 值";
                assertTrue(label(dialog, "summary").getText().startsWith(kind));
                assertEquals(value == null || value.isEmpty() ? kind : "\"NULL\"（文字）", list.getColumns().get(1).getCellData(0));
                assertEquals(value == null ? "" : value, text(dialog).getText()); assertFalse(text(dialog).isEditable());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void selectingFieldsAndTogglingWrapKeepOnlySnapshotTextAndOneCloseAction() throws Exception {
        FxUiTestSupport.call(() -> {
            String original = "<b>literal</b>\n  中😀\tend";
            var result = QueryResult.query(List.of("same", "same"), List.of(List.of(original, "second")), 0);
            var snapshot = ResultRowPreview.capture(result, 0, List.of(1, 0), 2);
            var dialog = new ResultRowDialog(null, snapshot, 1);
            try {
                dialog.show(); var table = fields(dialog); var area = text(dialog);
                assertEquals(1, table.getSelectionModel().getSelectedIndex()); assertEquals(original, area.getText());
                assertEquals(List.of(2, 1), table.getItems().stream().map(ResultCellPreview::column).toList());
                assertEquals("原列 2 · same", table.getColumns().getFirst().getCellData(0));
                assertEquals("<b>literal</b> ↵ …", table.getColumns().get(1).getCellData(1));
                assertTrue(label(dialog, "identity").getText().contains("当前显示第 3 行 · 结果第 1 行"));
                assertFalse(table.isEditable()); assertTrue(table.getColumns().stream().noneMatch(c -> c.isSortable() || c.isReorderable()));
                area.selectRange(2, 8); ((CheckBox) dialog.getDialogPane().lookup("#result-row-wrap")).fire();
                assertFalse(area.isWrapText()); assertEquals(original, area.getText()); assertEquals(2, area.getAnchor()); assertEquals(8, area.getCaretPosition());
                table.getSelectionModel().selectFirst(); assertEquals("second", area.getText()); assertEquals(0, area.getCaretPosition());
                table.getSelectionModel().clearSelection(); assertEquals("", area.getText()); assertEquals("", label(dialog, "summary").getText());
                assertEquals("请选择字段查看正文。", label(dialog, "detail").getText());
                table.getSelectionModel().selectLast(); assertEquals(original, area.getText());
                assertEquals(original, snapshot.fields().getLast().text()); assertEquals(1, dialog.getDialogPane().getButtonTypes().size());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"fields", "text"})
    void escapeFromEitherReadingAreaCloses(String control) throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.query(List.of("value"), List.of(List.of("unchanged")), 0);
            var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(0), 0), 1);
            try {
                dialog.show(); dialog.getDialogPane().lookup("#result-row-" + control)
                        .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertFalse(dialog.isShowing()); assertEquals("unchanged", text(dialog).getText());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"480,dark", "720,dark", "480,light", "720,light"})
    void boundedWarningsAndControlsRemainReadableWithPathologicalMetadata(double width, String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            var owner = new Stage(); owner.setScene(new Scene(new VBox(), 900, 800));
            owner.getScene().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                    ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
            var result = QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "名称\n".repeat(200), Types.VARCHAR, "类型\r".repeat(200))),
                    List.of(List.of("x".repeat(4097))), 0, false);
            var dialog = new ResultRowDialog(owner, ResultRowPreview.capture(result, 0, List.of(0), 0), 1);
            try {
                owner.show(); dialog.show(); dialog.setWidth(width); dialog.setHeight(700);
                var root = dialog.getDialogPane(); root.applyCss(); root.layout();
                assertEquals(owner.getScene().getStylesheets(), root.getStylesheets());
                assertEquals(4096, text(dialog).getText().length());
                assertTrue(label(dialog, "summary").getText().contains("正文已截断"));
                assertTrue(label(dialog, "detail").getText().contains("列名或类型名过长"));
                assertFalse(fields(dialog).getColumns().getFirst().getCellData(0).toString().contains("\n"));
                for (String id : List.of("identity", "fields", "metadata", "summary", "boundary", "wrap", "text", "close")) {
                    Region node = (Region) root.lookup("#result-row-" + id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= root.getWidth() + 1, id);
                    assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= root.getHeight() + 1, id);
                }
                for (String id : List.of("identity", "summary", "boundary")) {
                    var label = label(dialog, id); assertTrue(label.getHeight() + 1 >= label.prefHeight(label.getWidth()), id);
                }
                assertTrue(text(dialog).getHeight() >= 90);
                var close = (Button) root.lookup("#result-row-close");
                assertEquals("关闭", ((javafx.scene.text.Text) close.lookup(".text")).getText());
                close.fire(); assertFalse(dialog.isShowing());
            } finally { dialog.close(); owner.hide(); }
            return null;
        });
    }

    @Test void omittedFocusedFieldFallsBackExplicitlyToFirstIncludedField() throws Exception {
        FxUiTestSupport.call(() -> {
            var names = java.util.stream.IntStream.range(0, 201).mapToObj(i -> "f" + i).toList();
            var row = names.stream().map(name -> (Object) name).toList();
            var columns = java.util.stream.IntStream.range(0, 201).boxed().toList();
            var dialog = new ResultRowDialog(null, ResultRowPreview.capture(QueryResult.query(names, List.of(row), 0), 0, columns, 0), 201);
            try {
                dialog.show(); assertEquals(200, fields(dialog).getItems().size()); assertEquals("f0", text(dialog).getText());
                assertTrue(label(dialog, "identity").getText().contains("可见 201 列\n仅列出前 200"));
                fields(dialog).getSelectionModel().selectLast(); assertEquals("f199", text(dialog).getText());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void specialRepresentationWarningIsVisibleOutsideMetadataScroll() throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "bin", Types.BINARY, "BINARY")), List.of(List.of(new byte[]{1, 2})), 0, false);
            var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(0), 0), 1);
            try {
                dialog.show(); assertTrue(label(dialog, "summary").getText().contains("不代表完整原值"));
                assertTrue(text(dialog).getText().contains("0102"));
                assertSame(dialog.getDialogPane().getContent(), label(dialog, "summary").getParent());
            } finally { dialog.close(); }
            return null;
        });
    }

    @SuppressWarnings("unchecked") static TableView<ResultCellPreview> fields(ResultRowDialog dialog) { return (TableView<ResultCellPreview>) dialog.getDialogPane().lookup("#result-row-fields"); }
    static TextArea text(ResultRowDialog dialog) { return (TextArea) dialog.getDialogPane().lookup("#result-row-text"); }
    static Label label(ResultRowDialog dialog, String suffix) { return (Label) dialog.getDialogPane().lookup("#result-row-" + suffix); }
}
