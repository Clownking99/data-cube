package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.ResultRowPreview;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ResultRowTextFindTest {
    @Test void bodySearchDoesNotFilterFieldsOrSelectUntilAsked() throws Exception {
        FxUiTestSupport.call(() -> {
            var snapshot = ResultRowPreview.capture(QueryResult.query(List.of("body", "other"),
                    List.of(List.of("alpha beta ALPHA alpha", "different")), 0), 0, List.of(0, 1), 0);
            var dialog = new ResultRowDialog(null, snapshot, 1);
            try {
                dialog.show(); var area = ResultRowDialogTest.text(dialog); area.selectRange(9, 6);
                var query = query(dialog); assertNotNull(query, "Row detail needs its own body search entry");
                query.setText("alpha");
                assertEquals("匹配 3 处 · 仅查找已显示内容", status(dialog));
                assertEquals(9, area.getAnchor()); assertEquals(6, area.getCaretPosition());
                assertEquals(snapshot.fields(), ResultRowDialogTest.fields(dialog).getItems());
                assertEquals("", ResultRowDialogTest.query(dialog).getText());
                button(dialog, "next").fire(); assertEquals("ALPHA", area.getSelectedText());
                assertEquals(11, area.getAnchor()); assertEquals(16, area.getCaretPosition());
                button(dialog, "next").fire(); assertMatch(dialog, 17, 22, "第 3 / 3 处");
                button(dialog, "next").fire(); assertMatch(dialog, 0, 5, "第 1 / 3 处");
                button(dialog, "previous").fire(); assertMatch(dialog, 17, 22, "第 3 / 3 处");
                matchCase(dialog).fire(); assertMatch(dialog, 17, 22, "第 2 / 2 处");
                button(dialog, "previous").fire(); assertMatch(dialog, 0, 5, "第 1 / 2 处");
                assertSame(snapshot.fields().getFirst(), ResultRowDialogTest.fields(dialog).getSelectionModel().getSelectedItem());
                assertEquals("alpha beta ALPHA alpha", area.getText()); assertFalse(area.isEditable());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void changingDuplicateFieldsRetainsQueryButRebuildsOffsetsWithoutNavigating() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("same", "same", "other"), List.of("aa AA aa", "xxxx aa", "none"), List.of(1, 0, 2));
            try {
                dialog.show(); query(dialog).setText("aa"); matchCase(dialog).fire(); button(dialog, "next").fire();
                assertMatch(dialog, 0, 2, "第 1 / 2 处");
                ResultRowDialogTest.fields(dialog).getSelectionModel().selectFirst();
                assertEquals("xxxx aa", ResultRowDialogTest.text(dialog).getText());
                assertEquals("aa", query(dialog).getText()); assertTrue(matchCase(dialog).isSelected());
                assertTrue(ResultRowDialogTest.text(dialog).getSelectedText().isEmpty());
                assertEquals(0, ResultRowDialogTest.text(dialog).getCaretPosition()); assertTrue(status(dialog).startsWith("匹配 1 处"));
                button(dialog, "next").fire(); assertMatch(dialog, 5, 7, "第 1 / 1 处");
                ResultRowDialogTest.fields(dialog).getSelectionModel().selectLast();
                assertEquals("none", ResultRowDialogTest.text(dialog).getText()); assertTrue(status(dialog).startsWith("没有匹配"));
                assertTrue(button(dialog, "next").isDisabled()); assertTrue(button(dialog, "previous").isDisabled());
                button(dialog, "next").getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals(0, ResultRowDialogTest.text(dialog).getCaretPosition());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void fieldFilteringKeepsCurrentMatchOrClearsItWithoutChoosingAnotherField() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("same", "same", "other"), List.of("one target", "two target", "target elsewhere"), List.of(1, 0, 2));
            try {
                dialog.show(); query(dialog).setText("target"); button(dialog, "next").fire();
                var selected = ResultRowDialogTest.fields(dialog).getSelectionModel().getSelectedItem();
                ResultRowDialogTest.query(dialog).setText("same");
                assertSame(selected, ResultRowDialogTest.fields(dialog).getSelectionModel().getSelectedItem());
                assertMatch(dialog, 4, 10, "第 1 / 1 处");
                ResultRowDialogTest.query(dialog).setText("other");
                assertNull(ResultRowDialogTest.fields(dialog).getSelectionModel().getSelectedItem());
                assertEquals("", ResultRowDialogTest.text(dialog).getText()); assertEquals("target", query(dialog).getText());
                assertTrue(status(dialog).startsWith("没有匹配")); assertTrue(button(dialog, "next").isDisabled());
                ResultRowDialogTest.query(dialog).clear();
                assertEquals(3, ResultRowDialogTest.fields(dialog).getItems().size());
                assertNull(ResultRowDialogTest.fields(dialog).getSelectionModel().getSelectedItem()); assertTrue(button(dialog, "next").isDisabled());
                ResultRowDialogTest.fields(dialog).getSelectionModel().selectLast();
                assertEquals(0, ResultRowDialogTest.text(dialog).getCaretPosition());
                button(dialog, "next").fire(); assertMatch(dialog, 0, 6, "第 1 / 1 处");
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"NULL"})
    void placeholdersAreNotSearchableButLiteralNullIs(String value) throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("NULL"), Arrays.asList(value), List.of(0));
            try {
                dialog.show(); query(dialog).setText("NULL");
                assertEquals(!"NULL".equals(value), button(dialog, "next").isDisabled());
                assertEquals(value == null ? "" : value, ResultRowDialogTest.text(dialog).getText());
                if ("NULL".equals(value)) { button(dialog, "next").fire(); assertMatch(dialog, 0, 4, "第 1 / 1 处"); }
                else assertTrue(status(dialog).startsWith("没有匹配"));
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void bodySearchNeverSearchesMetadataOtherFieldsHiddenValuesOrTruncatedTail() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("metadata_only", "second", "hidden"),
                    List.of("x".repeat(4096) + "tail_only", "other_only", "hidden_only"), List.of(0, 1));
            try {
                dialog.show(); assertTrue(ResultRowDialogTest.label(dialog, "summary").getText().contains("正文已截断"));
                for (String token : List.of("metadata_only", "other_only", "hidden_only", "tail_only", "VARCHAR")) {
                    query(dialog).setText(token); assertTrue(button(dialog, "next").isDisabled(), token);
                    assertTrue(status(dialog).startsWith("没有匹配"), token);
                }
                query(dialog).setText("x"); assertTrue(status(dialog).startsWith("匹配 4096 处"));
                ResultRowDialogTest.text(dialog).positionCaret(4096); button(dialog, "previous").fire();
                assertMatch(dialog, 4095, 4096, "第 4096 / 4096 处");
                assertEquals(4096, ResultRowDialogTest.text(dialog).getLength());
                assertEquals(2, ResultRowDialogTest.fields(dialog).getItems().size());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(ints = {1023, 1024, 1025})
    void overlongQueryRejectsWholeInputAndRecoversWithoutMovingSelection(int length) throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("body"), List.of("x".repeat(1200)), List.of(0));
            try {
                dialog.show(); ResultRowDialogTest.text(dialog).selectRange(5, 2); query(dialog).setText("x".repeat(length));
                assertEquals(length > 1024, button(dialog, "next").isDisabled());
                assertTrue(status(dialog).contains(length > 1024 ? "最多 1024" : "匹配 1 处"));
                assertEquals(5, ResultRowDialogTest.text(dialog).getAnchor()); assertEquals(2, ResultRowDialogTest.text(dialog).getCaretPosition());
                query(dialog).setText("xxx"); button(dialog, "next").fire(); assertMatch(dialog, 6, 9, "第 3 / 400 处");
                query(dialog).clear(); assertTrue(status(dialog).startsWith("输入查找文本")); assertTrue(button(dialog, "next").isDisabled());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void literalSpacesAndUnicodeUseTheDisplayedTextOffsets() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("body"), List.of(".* x 中😀\n  end"), List.of(0));
            try {
                dialog.show(); query(dialog).setText(".*"); button(dialog, "next").fire(); assertMatch(dialog, 0, 2, "第 1 / 1 处");
                query(dialog).setText("中😀"); button(dialog, "next").fire(); assertMatch(dialog, 5, 8, "第 1 / 1 处");
                query(dialog).setText("  "); button(dialog, "next").fire(); assertMatch(dialog, 9, 11, "第 1 / 1 处");
                assertEquals(".* x 中😀\n  end", ResultRowDialogTest.text(dialog).getText());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void fieldAndBodyShortcutsStayDistinctAndNavigationDoesNotCloseTheDialog() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("body", "other"), List.of("a A a", "another"), List.of(0, 1));
            try {
                dialog.show(); query(dialog).setText("a"); var fields = ResultRowDialogTest.fields(dialog);
                fields.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, true, false));
                assertSame(query(dialog), dialog.getDialogPane().getScene().getFocusOwner()); assertEquals("a", query(dialog).getSelectedText());
                query(dialog).fireEvent(key(KeyCode.ENTER, false, false)); assertMatch(dialog, 0, 1, "第 1 / 3 处");
                query(dialog).fireEvent(key(KeyCode.ENTER, true, false)); assertMatch(dialog, 4, 5, "第 3 / 3 处");
                ResultRowDialogTest.text(dialog).fireEvent(key(KeyCode.F3, false, false)); assertMatch(dialog, 0, 1, "第 1 / 3 处");
                ResultRowDialogTest.text(dialog).fireEvent(key(KeyCode.F3, true, false)); assertMatch(dialog, 4, 5, "第 3 / 3 处");
                query(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F3, false, false, true, false));
                assertMatch(dialog, 4, 5, "第 3 / 3 处");
                query(dialog).fireEvent(key(KeyCode.F, false, true));
                assertSame(ResultRowDialogTest.query(dialog), dialog.getDialogPane().getScene().getFocusOwner());
                ResultRowDialogTest.query(dialog).setText("other"); ResultRowDialogTest.query(dialog).fireEvent(key(KeyCode.ENTER, false, false));
                assertEquals(2, fields.getSelectionModel().getSelectedItem().column()); assertEquals("another", ResultRowDialogTest.text(dialog).getText());
                assertEquals("a", query(dialog).getText()); assertTrue(dialog.isShowing());
            } finally { dialog.close(); }
            return null;
        });
    }

    @Test void footerFocusStillAllowsBodySearchFieldSearchAndNavigation() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("body"), List.of("a A a"), List.of(0));
            try {
                dialog.show(); query(dialog).setText("a");
                var close = (Button) dialog.getDialogPane().lookup("#result-row-close");
                close.requestFocus();
                close.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, true, false));
                assertSame(query(dialog), dialog.getDialogPane().getScene().getFocusOwner());
                assertEquals("a", query(dialog).getSelectedText());
                close.requestFocus(); close.fireEvent(key(KeyCode.F3, false, false));
                assertMatch(dialog, 0, 1, "第 1 / 3 处");
                close.fireEvent(key(KeyCode.F3, true, false)); assertMatch(dialog, 4, 5, "第 3 / 3 处");
                close.fireEvent(key(KeyCode.F, false, true));
                assertSame(ResultRowDialogTest.query(dialog), dialog.getDialogPane().getScene().getFocusOwner());
                // Extra modifiers must not steal another shortcut or move the current match.
                ResultRowDialogTest.query(dialog).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, true, true, true, false));
                assertSame(ResultRowDialogTest.query(dialog), dialog.getDialogPane().getScene().getFocusOwner());
                assertMatch(dialog, 4, 5, "第 3 / 3 处"); assertTrue(dialog.isShowing());
            } finally { dialog.close(); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"query", "case", "next", "previous"})
    void escapeClosesFromBodyControlsAndOwnerCallbackCannotReplaceCleanup(String control) throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = dialog(List.of("body"), List.of("old old"), List.of(0)); var hidden = new AtomicBoolean();
            dialog.setOnHidden(event -> hidden.set(true));
            try {
                dialog.show(); query(dialog).setText("old"); button(dialog, "next").fire();
                dialog.getDialogPane().lookup("#result-row-find-" + control).fireEvent(key(KeyCode.ESCAPE, false, false));
                assertFalse(dialog.isShowing()); assertTrue(hidden.get()); String closedStatus = status(dialog);
                ResultRowDialogTest.text(dialog).setText("new new"); query(dialog).setText("new"); matchCase(dialog).fire();
                button(dialog, "next").getOnAction().handle(new javafx.event.ActionEvent());
                assertEquals(closedStatus, status(dialog)); assertTrue(button(dialog, "next").isDisabled());
                assertEquals("", ResultRowDialogTest.text(dialog).getSelectedText());
            } finally { dialog.close(); }
            return null;
        });
    }

    static ResultRowDialog dialog(List<String> names, List<?> values, List<Integer> visible) {
        return new ResultRowDialog(null, ResultRowPreview.capture(QueryResult.query(names,
                List.of(new java.util.ArrayList<Object>(values)), 0), 0, visible, 0), 1);
    }
    static CheckBox matchCase(ResultRowDialog dialog) { return (CheckBox) dialog.getDialogPane().lookup("#result-row-find-case"); }
    private static void assertMatch(ResultRowDialog dialog, int start, int end, String message) {
        assertEquals(start, ResultRowDialogTest.text(dialog).getSelection().getStart());
        assertEquals(end, ResultRowDialogTest.text(dialog).getSelection().getEnd()); assertTrue(status(dialog).startsWith(message), status(dialog));
    }
    private static KeyEvent key(KeyCode code, boolean shift, boolean control) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false); }
    static TextField query(ResultRowDialog dialog) { return (TextField) dialog.getDialogPane().lookup("#result-row-find-query"); }
    static Button button(ResultRowDialog dialog, String suffix) { return (Button) dialog.getDialogPane().lookup("#result-row-find-" + suffix); }
    static String status(ResultRowDialog dialog) { return ((Label) dialog.getDialogPane().lookup("#result-row-find-status")).getText(); }
}
