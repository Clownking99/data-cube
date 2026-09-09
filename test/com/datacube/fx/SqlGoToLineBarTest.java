package com.datacube.fx;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlGoToLineBarTest {
    @ParameterizedTest @CsvSource({"1,3,1", "2,3,2", "3,3,3", "0002,3,2",
            "' 2 ',3,2", "2147483647,2147483647,2147483647", "1,0,-1"})
    void parsesOnlyAnExistingOneBasedLine(String input, int count, int expected) {
        assertEquals(expected, SqlGoToLineBar.parseLine(input, count));
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "-1", "+1", "1.5", "2:3", "1\n2", "１", "one", "4",
            "2147483648", "99999999999999999999"})
    void rejectsInvalidNumbersWithoutOverflowOrClamping(String input) {
        assertEquals(-1, SqlGoToLineBar.parseLine(input, 3));
    }

    @ParameterizedTest @CsvSource({"1,0", "2,7", "3,8", "4,13"})
    void navigationCollapsesSelectionAtTheRequestedLineStartWithoutAnEdit(int target, int offset) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("first;\n\n中😀;\n")) {
                f.editor.selectRange(5, 1);
                f.bar.launcher().fire();
                assertEquals("1", f.input().getText());
                assertEquals(5, f.editor.getAnchor());
                assertEquals(1, f.editor.getCaretPosition(), "opening must not move the editor");
                f.input().setText(Integer.toString(target));
                f.input().fireEvent(new ActionEvent());
                assertEquals(offset, f.editor.getCaretPosition());
                assertEquals(offset, f.editor.getAnchor());
                assertEquals(target - 1, f.editor.getCurrentParagraph());
                assertEquals(0, f.editor.getCaretColumn());
                assertFalse(f.bar.getNode().isManaged());
                assertEquals("first;\n\n中😀;\n", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void emptyDocumentStillHasANavigableFirstLine() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("")) {
                f.bar.show();
                assertEquals("1", f.input().getText());
                assertFalse(f.submit().isDisabled());
                f.submit().fire();
                assertEquals(0, f.editor.getCaretPosition());
                assertEquals("", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
                assertFalse(f.bar.getNode().isVisible());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"button", "escape"})
    void cancellingInvalidInputPreservesTheReverseSelection(String entry) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("one\ntwo\nthree")) {
                f.editor.selectRange(12, 5);
                f.bar.show();
                assertEquals("2", f.input().getText());
                f.input().setText("4");
                assertTrue(f.submit().isDisabled());
                assertEquals("请输入 1–3 之间的整数行号", f.status().getText());
                f.input().fireEvent(new ActionEvent());
                assertTrue(f.bar.getNode().isVisible());
                if (entry.equals("button")) ((Button) f.bar.getNode().lookup("#sql-go-to-line-cancel")).fire();
                else f.input().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                        false, false, false, false));
                assertFalse(f.bar.getNode().isManaged());
                assertEquals(12, f.editor.getAnchor());
                assertEquals(5, f.editor.getCaretPosition());
                assertEquals("one\ntwo\nthree", f.editor.getText());
                assertFalse(f.editor.isUndoAvailable());
                f.bar.show();
                assertEquals("2", f.input().getText(), "reopening uses current line, not stale invalid input");
            }
            return null;
        });
    }

    @Test void documentChangesRevalidateAgainstTheLatestLineCount() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("a\nb\nc")) {
                f.bar.show();
                f.input().setText("3");
                assertFalse(f.submit().isDisabled());
                f.editor.replaceText("only");
                assertTrue(f.submit().isDisabled());
                assertEquals("请输入 1–1 之间的整数行号", f.status().getText());
                f.input().fireEvent(new ActionEvent());
                assertEquals(4, f.editor.getCaretPosition());
                assertTrue(f.bar.getNode().isVisible());
                f.editor.appendText("\n\nx");
                assertFalse(f.submit().isDisabled());
                f.submit().fire();
                assertEquals(6, f.editor.getCaretPosition());
            }
            return null;
        });
    }

    @Test void readOnlyNavigationIsAllowedButBusyDisabledAndClosedActionsAreRejected() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("a\nb\nc")) {
                f.editor.setEditable(false);
                f.bar.show();
                f.input().setText("2");
                f.allowed.set(false); // State may change after validation, before Enter/button delivery.
                f.input().fireEvent(new ActionEvent());
                assertEquals(0, f.editor.getCaretPosition());
                assertEquals("当前编辑状态暂不可定位", f.status().getText());
                f.bar.hide(false);
                f.bar.show();
                assertFalse(f.bar.getNode().isVisible());
                f.allowed.set(true);
                f.editor.setDisable(true);
                f.bar.show();
                assertFalse(f.bar.getNode().isVisible());
                f.editor.setDisable(false);
                f.bar.show();
                f.input().setText("2");
                f.submit().fire();
                assertEquals(2, f.editor.getCaretPosition(), "read-only means no edits, not no navigation");
                f.bar.show();
                String frozen = f.status().getText();
                f.bar.close();
                f.editor.replaceText("different");
                f.input().setText("1");
                f.input().fireEvent(new ActionEvent());
                f.bar.show();
                assertEquals(9, f.editor.getCaretPosition());
                assertEquals(frozen, f.status().getText());
                assertFalse(f.bar.getNode().isVisible());
                assertTrue(f.bar.launcher().isDisabled());
            }
            return null;
        });
    }

    @Test void navigationLeavesExistingUndoAndRedoHistoryIntact() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture("a\nb")) {
                f.editor.appendText("\nc");
                f.bar.show();
                f.input().setText("2");
                f.submit().fire();
                assertEquals(2, f.editor.getCaretPosition());
                f.editor.undo();
                assertEquals("a\nb", f.editor.getText(), "one undo must still remove the original edit");
                f.bar.show();
                f.input().setText("1");
                f.submit().fire();
                f.editor.redo();
                assertEquals("a\nb\nc", f.editor.getText(), "navigation must not clear redo history");
            }
            return null;
        });
    }

    private static final class Fixture implements AutoCloseable {
        final CodeArea editor;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final SqlGoToLineBar bar;
        Fixture(String text) {
            editor = new CodeArea(text);
            editor.moveTo(0);
            editor.getUndoManager().forgetHistory();
            bar = new SqlGoToLineBar(editor, allowed::get, () -> {});
            new Scene(new VBox(bar.getNode(), editor));
        }
        TextField input() { return (TextField) bar.getNode().lookup("#sql-go-to-line-input"); }
        Button submit() { return (Button) bar.getNode().lookup("#sql-go-to-line-submit"); }
        Label status() { return (Label) bar.getNode().lookup("#sql-go-to-line-status"); }
        @Override public void close() { bar.close(); }
    }
}
