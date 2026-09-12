package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.sqleditor.result.ResultCellPreview;
import java.util.Arrays;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import static org.junit.jupiter.api.Assertions.*;

class ResultCellFindTest {
    @Test void inputPreservesSelectionAndExplicitNavigationCyclesBothDirections() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("alpha beta ALPHA alpha");
            try {
                d.show(); var area = area(d); area.selectRange(9, 6);
                query(d).setText("alpha");
                assertEquals("匹配 3 处 · 仅查找已显示内容", status(d));
                assertEquals(9, area.getAnchor()); assertEquals(6, area.getCaretPosition());
                button(d, "next").fire(); assertSelection(d, 11, 16, "第 2 / 3 处");
                button(d, "next").fire(); assertSelection(d, 17, 22, "第 3 / 3 处");
                button(d, "next").fire(); assertSelection(d, 0, 5, "第 1 / 3 处");
                button(d, "previous").fire(); assertSelection(d, 17, 22, "第 3 / 3 处");
                matchCase(d).fire(); assertEquals(17, area.getAnchor()); assertEquals(22, area.getCaretPosition());
                button(d, "previous").fire(); assertSelection(d, 0, 5, "第 1 / 2 处");
                area.positionCaret(17); button(d, "next").fire(); assertSelection(d, 17, 22, "第 2 / 2 处");
                query(d).setText("missing"); assertTrue(status(d).startsWith("没有匹配"));
                assertTrue(button(d, "next").isDisabled()); assertTrue(button(d, "previous").isDisabled());
                button(d, "next").fireEvent(new javafx.event.ActionEvent());
                assertEquals(17, area.getAnchor()); assertEquals(22, area.getCaretPosition());
                query(d).clear(); assertTrue(status(d).startsWith("输入查找文本"));
                assertEquals("alpha beta ALPHA alpha", area.getText()); assertFalse(area.isEditable());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"'.*', 1, 0, 2", "' ', 5, 2, 3", "'中😀', 1, 5, 8", "'ä', 2, 9, 10", "'aa', 2, 13, 15"})
    void literalWhitespaceUnicodeAndNonOverlappingMatchesUseDisplayedOffsets(String query, int count, int start, int end) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog(".* x 中😀 Ä ä aaaa");
            try {
                d.show(); query(d).setText(query);
                assertTrue(status(d).startsWith("匹配 " + count + " 处"), status(d));
                button(d, "next").fire(); assertSelection(d, start, end, "第 1 / " + count + " 处");
            } finally { d.close(); }
            return null;
        });
    }

    @Test void normalizedLineEndingsAndEmojiDoNotShiftSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("head\r\n😀\rtarget\nend target");
            try {
                d.show(); String shown = area(d).getText(); query(d).setText("target");
                button(d, "next").fire();
                assertEquals(shown.indexOf("target"), area(d).getSelection().getStart());
                assertEquals("target", area(d).getSelectedText());
                button(d, "next").fire(); assertEquals(shown.lastIndexOf("target"), area(d).getSelection().getStart());
                assertEquals("target", area(d).getSelectedText()); assertEquals(shown, area(d).getText());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @CsvSource({"1023, true", "1024, true", "1025, false"})
    void queryLimitRejectsWholeInputAndCanRecover(int length, boolean allowed) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("a".repeat(1200));
            try {
                d.show(); area(d).selectRange(5, 2); query(d).setText("a".repeat(length));
                assertEquals(!allowed, button(d, "next").isDisabled());
                assertTrue(status(d).contains(allowed ? "匹配 1 处" : "最多 1024"), status(d));
                assertEquals(5, area(d).getAnchor()); assertEquals(2, area(d).getCaretPosition());
                query(d).setText("aaa"); button(d, "next").fire(); assertEquals("aaa", area(d).getSelectedText());
                assertEquals(6, area(d).getSelection().getStart());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void matchLimitAndSnapshotLimitAreExplicitAndNeverSearchOmittedTailOrMetadata() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("x".repeat(65_536) + "TAIL_ONLY");
            try {
                d.show(); query(d).setText("TAIL_ONLY"); assertTrue(button(d, "next").isDisabled());
                assertTrue(((Label) d.getDialogPane().lookup("#result-cell-summary")).getText().contains("已截断"));
                query(d).setText("value"); assertTrue(button(d, "next").isDisabled(), "column label is not body");
                query(d).setText("x"); assertTrue(status(d).contains("仅定位前 10000 处"));
                area(d).positionCaret(65_535); button(d, "previous").fire();
                assertSelection(d, 9999, 10000, "第 10000 / 10000 处");
                button(d, "next").fire(); assertSelection(d, 0, 1, "第 1 / 10000 处");
                assertEquals(65_536, area(d).getText().length());
            } finally { d.close(); }
            return null;
        });
    }

    @ParameterizedTest @NullAndEmptySource
    void nullAndEmptyRemainDistinctButHaveNoSearchablePlaceholder(String value) throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog(value);
            try {
                d.show(); query(d).setText("NULL"); assertTrue(button(d, "next").isDisabled());
                assertTrue(status(d).startsWith("没有匹配")); assertEquals("", area(d).getText());
                assertTrue(((Label) d.getDialogPane().lookup("#result-cell-summary")).getText()
                        .startsWith(value == null ? "NULL（数据库空值）" : "空字符串"));
            } finally { d.close(); }
            return null;
        });
    }

    @Test void shortcutsStayInDialogAndClosedActionsCannotSelect() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("a A a");
            try {
                d.show(); query(d).setText("a");
                area(d).fireEvent(key(KeyCode.F, false, true));
                assertSame(query(d), d.getDialogPane().getScene().getFocusOwner()); assertEquals("a", query(d).getSelectedText());
                query(d).fireEvent(key(KeyCode.ENTER, false, false)); assertSelection(d, 0, 1, "第 1 / 3 处");
                query(d).fireEvent(key(KeyCode.ENTER, true, false)); assertSelection(d, 4, 5, "第 3 / 3 处");
                area(d).fireEvent(key(KeyCode.F3, false, false)); assertSelection(d, 0, 1, "第 1 / 3 处");
                area(d).fireEvent(key(KeyCode.F3, true, false)); assertSelection(d, 4, 5, "第 3 / 3 处");
                ((Button) d.getDialogPane().lookup("#result-cell-close")).fireEvent(key(KeyCode.F3, false, false));
                assertSelection(d, 0, 1, "第 1 / 3 处");
                area(d).fireEvent(key(KeyCode.F3, true, false));
                query(d).fireEvent(key(KeyCode.ESCAPE, false, false)); assertFalse(d.isShowing());
                query(d).setText("A"); button(d, "next").fireEvent(new javafx.event.ActionEvent());
                assertEquals(4, area(d).getAnchor()); assertEquals(5, area(d).getCaretPosition());
                assertTrue(button(d, "next").isDisabled());
            } finally { d.close(); }
            return null;
        });
    }

    @Test void changedDisplayedTextInvalidatesOffsetsAndOwnerHiddenHandlerDoesNotReplaceCleanup() throws Exception {
        FxUiTestSupport.call(() -> {
            var d = dialog("old old"); var hidden = new java.util.concurrent.atomic.AtomicBoolean();
            d.setOnHidden(event -> hidden.set(true));
            try {
                d.show(); query(d).setText("old"); button(d, "next").fire();
                area(d).setText("new old"); button(d, "next").fire(); assertSelection(d, 4, 7, "第 1 / 1 处");
                d.close(); assertTrue(hidden.get()); String closedStatus = status(d);
                area(d).setText("old old old"); query(d).setText("new"); matchCase(d).fire();
                button(d, "next").fireEvent(new javafx.event.ActionEvent());
                assertEquals(closedStatus, status(d)); assertTrue(area(d).getSelectedText().isEmpty());
            } finally { d.close(); }
            return null;
        });
    }

    static ResultCellDialog dialog(String value) {
        return new ResultCellDialog(null, ResultCellPreview.capture(
                QueryResult.query(List.of("value"), List.of(Arrays.asList(value)), 0), 0, 0, 0));
    }
    static TextArea area(ResultCellDialog d) { return (TextArea) d.getDialogPane().lookup("#result-cell-text"); }
    static TextField query(ResultCellDialog d) { return (TextField) d.getDialogPane().lookup("#result-cell-find-query"); }
    static Button button(ResultCellDialog d, String action) { return (Button) d.getDialogPane().lookup("#result-cell-find-" + action); }
    static CheckBox matchCase(ResultCellDialog d) { return (CheckBox) d.getDialogPane().lookup("#result-cell-find-case"); }
    static String status(ResultCellDialog d) { return ((Label) d.getDialogPane().lookup("#result-cell-find-status")).getText(); }
    static void assertSelection(ResultCellDialog d, int start, int end, String status) {
        assertEquals(start, area(d).getSelection().getStart()); assertEquals(end, area(d).getSelection().getEnd());
        assertTrue(status(d).startsWith(status), status(d));
    }
    private static KeyEvent key(KeyCode code, boolean shift, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false);
    }
}
