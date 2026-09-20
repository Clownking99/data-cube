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
import javafx.scene.control.TextField;
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

    @ParameterizedTest @ValueSource(strings = {"fields", "text", "query"})
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
                query(dialog).requestFocus();
                query(dialog).pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
                root.applyCss(); root.layout();
                var prompt = query(dialog).lookupAll(".text").stream()
                        .filter(node -> node instanceof javafx.scene.text.Text text && text.getText().equals(query(dialog).getPromptText()))
                        .map(node -> (javafx.scene.text.Text) node).findFirst().orElseThrow();
                assertTrue(prompt.isVisible());
                assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), prompt.getFill());
                assertEquals(4096, text(dialog).getText().length());
                assertTrue(label(dialog, "summary").getText().contains("正文已截断"));
                assertTrue(label(dialog, "detail").getText().contains("列名或类型名过长"));
                assertFalse(fields(dialog).getColumns().getFirst().getCellData(0).toString().contains("\n"));
                for (String id : List.of("identity", "query", "clear", "hide-null", "filter-status", "fields", "metadata", "summary", "boundary", "wrap", "text", "close")) {
                    Region node = (Region) root.lookup("#result-row-" + id); var bounds = node.localToScene(node.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= root.getWidth() + 1, id);
                    assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= root.getHeight() + 1, id);
                }
                for (String id : List.of("identity", "summary", "boundary")) {
                    var label = label(dialog, id); assertTrue(label.getHeight() + 1 >= label.prefHeight(label.getWidth()), id);
                }
                assertTrue(text(dialog).getHeight() >= 90);
                var bodyQuery = ResultRowTextFindTest.query(dialog); bodyQuery.requestFocus();
                bodyQuery.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
                root.applyCss(); root.layout();
                var bodyPrompt = bodyQuery.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                        .map(javafx.scene.text.Text.class::cast).filter(t -> bodyQuery.getPromptText().equals(t.getText())).findFirst().orElseThrow();
                assertTrue(bodyPrompt.isVisible());
                assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), bodyPrompt.getFill());
                bodyQuery.setText("x"); ResultRowTextFindTest.button(dialog, "next").fire(); root.applyCss(); root.layout();
                assertSame(bodyQuery, root.getScene().getFocusOwner()); assertEquals("x", text(dialog).getSelectedText());
                assertTrue(text(dialog).lookupAll("Path").stream().filter(javafx.scene.shape.Path.class::isInstance)
                        .map(javafx.scene.shape.Path.class::cast).anyMatch(p -> p.isVisible() && !p.getElements().isEmpty()
                                && javafx.scene.paint.Color.web("#6C5CE7").equals(p.getFill())), "unfocused body match must stay visible");
                for (String search : List.of("x", "x".repeat(1025), "")) {
                    bodyQuery.setText(search); root.applyCss(); root.layout();
                    for (String id : List.of("find-query", "find-previous", "find-next", "find-case", "find-status", "wrap", "text", "close")) {
                        Region node = (Region) root.lookup("#result-row-" + id); var bounds = node.localToScene(node.getLayoutBounds());
                        assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= root.getWidth() + 1, id);
                        assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= root.getHeight() + 1, id);
                        if (node instanceof Label) assertTrue(node.getHeight() + 1 >= node.prefHeight(node.getWidth()), id);
                    }
                    assertTrue(text(dialog).getHeight() >= 90);
                }
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

    @ParameterizedTest @CsvSource({"' SAME ', '3:2'", "'客户', '4'", "'[.*]', '5'", "'only-in-value', ''", "'OTHER', ''", "'原列 1', ''", "'a b', '6'"})
    void nameFilterIsLiteralCaseInsensitiveAndKeepsSnapshotOrder(String query, String expectedColumns) throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.query(List.of("id", "same", "SAME", "客户", "[.*]", "a\nb"),
                    List.of(List.of("only-in-value", "left", "right", "four", "five", "six")), 0);
            var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(2, 0, 1, 3, 4, 5), 0), 1);
            try {
                dialog.show(); query(dialog).setText(query);
                var columns = fields(dialog).getItems().stream().map(f -> String.valueOf(f.column())).toList();
                assertEquals(expectedColumns, String.join(":", columns));
                assertEquals("匹配 " + columns.size() + " / 6 个快照字段", label(dialog, "filter-status").getText());
                assertNull(fields(dialog).getSelectionModel().getSelectedItem()); assertEquals("", text(dialog).getText());
                assertTrue(dialog.isShowing());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void survivingDuplicateKeepsIdentityAndTextSelectionThenExclusionClearsDetails() throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.query(List.of("same", "same", "other"), List.of(List.of("first-value", "second-value", "third")), 0);
            var snapshot = ResultRowPreview.capture(result, 0, List.of(1, 2, 0), 0);
            var dialog = new ResultRowDialog(null, snapshot, 1);
            try {
                dialog.show(); text(dialog).selectRange(7, 2);
                query(dialog).setText("SAME");
                assertSame(snapshot.fields().getLast(), fields(dialog).getSelectionModel().getSelectedItem());
                assertEquals(1, fields(dialog).getSelectionModel().getSelectedIndex());
                assertEquals("first-value", text(dialog).getText()); assertEquals(7, text(dialog).getAnchor()); assertEquals(2, text(dialog).getCaretPosition());
                query(dialog).setText("other");
                assertNull(fields(dialog).getSelectionModel().getSelectedItem()); assertEquals("", text(dialog).getText());
                assertEquals("", label(dialog, "summary").getText()); assertEquals("请选择字段查看正文。", label(dialog, "detail").getText());
                query(dialog).setText("not-found");
                assertEquals("没有匹配的字段。", label(dialog, "detail").getText());
                assertEquals("没有匹配的字段", ((Label) fields(dialog).getPlaceholder()).getText());
                ((Button) dialog.getDialogPane().lookup("#result-row-clear")).fire();
                assertEquals("", query(dialog).getText()); assertEquals(snapshot.fields(), fields(dialog).getItems());
                assertNull(fields(dialog).getSelectionModel().getSelectedItem()); assertEquals("", text(dialog).getText());
                assertTrue(dialog.getDialogPane().lookup("#result-row-clear").isDisabled());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {255, 256, 257})
    void overlongQueryIsRejectedWithoutSearchingItsPrefix(int length) throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.query(List.of("x".repeat(300)), List.of(List.of("value")), 0);
            var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(0), 0), 1);
            try {
                dialog.show(); query(dialog).setText("x".repeat(length));
                assertEquals(length <= 256 ? 1 : 0, fields(dialog).getItems().size());
                if (length > 256) {
                    assertTrue(label(dialog, "filter-status").getText().contains("最多 256"));
                    assertEquals("", text(dialog).getText()); assertNull(fields(dialog).getSelectionModel().getSelectedItem());
                    query(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                    assertTrue(dialog.isShowing());
                    assertNull(fields(dialog).getSelectionModel().getSelectedItem());
                } else assertEquals("value", text(dialog).getText());
                query(dialog).clear(); assertEquals(1, fields(dialog).getItems().size());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"ENTER", "DOWN"})
    void queryKeyboardExplicitlySelectsFirstMatchAndShortcutFocusesQuery(String key) throws Exception {
        FxUiTestSupport.call(() -> {
            var result = QueryResult.query(List.of("id", "same", "same"), List.of(List.of("zero", "one", "two")), 0);
            var dialog = new ResultRowDialog(null, ResultRowPreview.capture(result, 0, List.of(0, 2, 1), 0), 1);
            try {
                dialog.show(); query(dialog).setText("same");
                assertEquals("", text(dialog).getText());
                query(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.valueOf(key), false, false, false, false));
                assertEquals(3, fields(dialog).getSelectionModel().getSelectedItem().column()); assertEquals("two", text(dialog).getText());
                assertSame(fields(dialog), dialog.getDialogPane().getScene().getFocusOwner()); assertTrue(dialog.isShowing());
                fields(dialog).getSelectionModel().selectLast();
                fields(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertSame(query(dialog), dialog.getDialogPane().getScene().getFocusOwner()); assertEquals("same", query(dialog).getSelectedText());
                query(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.valueOf(key), false, false, false, false));
                assertEquals(2, fields(dialog).getSelectionModel().getSelectedItem().column()); assertEquals("one", text(dialog).getText());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void filteringNeverIncludesHiddenOmittedOrTruncatedNamesAndBlankRestoresSnapshot() throws Exception {
        FxUiTestSupport.call(() -> {
            var names = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 202).mapToObj(i -> "field" + i).toList());
            names.set(0, "hidden_marker"); names.set(1, "x".repeat(512) + "tail_marker"); names.set(201, "omitted_marker");
            var result = QueryResult.query(names, List.of(names.stream().map(n -> (Object) n).toList()), 0);
            var snapshot = ResultRowPreview.capture(result, 0, java.util.stream.IntStream.range(1, 202).boxed().toList(), 0);
            var dialog = new ResultRowDialog(null, snapshot, 2);
            try {
                dialog.show();
                for (String marker : List.of("hidden_marker", "omitted_marker", "tail_marker")) {
                    query(dialog).setText(marker); assertTrue(fields(dialog).getItems().isEmpty(), marker);
                    assertEquals("", text(dialog).getText()); assertTrue(label(dialog, "identity").getText().contains("前 200"));
                }
                query(dialog).setText("   "); assertEquals(snapshot.fields(), fields(dialog).getItems());
                assertEquals("匹配 200 / 200 个快照字段", label(dialog, "filter-status").getText());
                assertTrue(label(dialog, "boundary").getText().contains("仅匹配窗口已列出的字段名"));
            } finally { dialog.close(); }
            return null;
        });
    }

    static TextField query(ResultRowDialog dialog) { return (TextField) dialog.getDialogPane().lookup("#result-row-query"); }
    @SuppressWarnings("unchecked") static TableView<ResultCellPreview> fields(ResultRowDialog dialog) { return (TableView<ResultCellPreview>) dialog.getDialogPane().lookup("#result-row-fields"); }
    static TextArea text(ResultRowDialog dialog) { return (TextArea) dialog.getDialogPane().lookup("#result-row-text"); }
    static Label label(ResultRowDialog dialog, String suffix) { return (Label) dialog.getDialogPane().lookup("#result-row-" + suffix); }
}
