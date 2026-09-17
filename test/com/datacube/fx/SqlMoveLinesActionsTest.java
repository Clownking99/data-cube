package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlMoveLinesActionsTest {
    @TempDir Path directory;
    @Test void moveIsOneUndoStepBetweenTypingAndPreservesReverseRange() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("a\nbb\nccc"); var hidden = new AtomicInteger(); var scope = new AtomicInteger();
            var feedback = new AtomicReference<String>();
            try (var action = new SqlMoveLinesActions(editor, settings(), () -> true, hidden::incrementAndGet,
                    edit -> { scope.incrementAndGet(); edit.run(); }, feedback::set)) {
                editor.getUndoManager().forgetHistory(); editor.appendText("!"); editor.selectRange(5, 2);
                action.downButton().fire(); assertEquals("a\nccc!\nbb", editor.getText());
                assertEquals(9, editor.getAnchor()); assertEquals(7, editor.getCaretPosition());
                assertEquals(1, scope.get()); assertEquals(1, hidden.get()); assertTrue(feedback.get().contains("1 行"));
                editor.undo(); assertEquals("a\nbb\nccc!", editor.getText());
                editor.undo(); assertEquals("a\nbb\nccc", editor.getText()); assertFalse(editor.isUndoAvailable());
                editor.redo(); editor.redo(); assertEquals("a\nccc!\nbb", editor.getText());
                editor.appendText("?"); editor.undo(); assertEquals("a\nccc!\nbb", editor.getText());
                editor.undo(); assertEquals("a\nbb\nccc!", editor.getText());
            }
            return null;
        });
    }

    @Test void bothShortcutsAreEditorScopedExactAndCanBeRebound() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("a\nb"); var other = new TextField(); new Scene(new VBox(editor, other)); var settings = settings();
            try (var action = new SqlMoveLinesActions(editor, settings, () -> true, () -> {}, Runnable::run, ignored -> {})) {
                editor.moveTo(0); other.fireEvent(key(KeyCode.DOWN)); assertEquals("a\nb", editor.getText());
                editor.fireEvent(key(KeyCode.DOWN)); assertEquals("b\na", editor.getText());
                editor.fireEvent(key(KeyCode.UP)); assertEquals("a\nb", editor.getText());
                settings.apply(Map.of(ShortcutAction.SQL_MOVE_LINES_UP, KeyCombination.keyCombination("Ctrl+Alt+U"),
                        ShortcutAction.SQL_MOVE_LINES_DOWN, KeyCombination.keyCombination("Ctrl+Alt+J")));
                assertEquals(KeyCombination.keyCombination("Ctrl+Alt+J"), settings().get(ShortcutAction.SQL_MOVE_LINES_DOWN));
                assertEquals(KeyCombination.keyCombination("Ctrl+Alt+U"), settings().get(ShortcutAction.SQL_MOVE_LINES_UP));
                assertTrue(action.downButton().getTooltip().getText().contains("Ctrl+Alt+J"));
                assertTrue(action.upButton().getTooltip().getText().contains("Ctrl+Alt+U"));
                editor.fireEvent(key(KeyCode.DOWN)); assertEquals("a\nb", editor.getText());
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.J, false, true, true, false));
                assertEquals("b\na", editor.getText());
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.U, false, true, true, false));
                assertEquals("a\nb", editor.getText());
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, true, false, true, false));
                assertEquals("a\nb", editor.getText());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "readonly", "disabled", "parent-disabled", "closed"})
    void unavailableActionRejectsBothButtonsAndKeys(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("a\nbb\nc"); var root = new VBox(editor); new Scene(root); var allowed = new AtomicBoolean(true);
            var settings = settings(); var action = new SqlMoveLinesActions(editor, settings, allowed::get,
                    () -> fail("must not edit"), Runnable::run, ignored -> fail("must not report movement"));
            try {
                editor.getUndoManager().forgetHistory(); editor.selectRange(4, 2);
                switch (state) {
                    case "guard" -> allowed.set(false);
                    case "readonly" -> editor.setEditable(false);
                    case "disabled" -> editor.setDisable(true);
                    case "parent-disabled" -> root.setDisable(true);
                    case "closed" -> action.close();
                    default -> throw new AssertionError(state);
                }
                String hint = action.upButton().getTooltip().getText();
                action.upButton().getOnAction().handle(new javafx.event.ActionEvent()); action.downButton().getOnAction().handle(new javafx.event.ActionEvent());
                editor.fireEvent(key(KeyCode.UP)); editor.fireEvent(key(KeyCode.DOWN));
                assertEquals("a\nbb\nc", editor.getText()); assertEquals(4, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable());
                if (state.equals("closed")) {
                    settings.apply(Map.of(ShortcutAction.SQL_MOVE_LINES_UP, KeyCombination.keyCombination("Ctrl+Alt+U")));
                    assertEquals(hint, action.upButton().getTooltip().getText());
                    assertTrue(action.upButton().isDisabled()); assertTrue(action.downButton().isDisabled());
                }
            } finally { action.close(); }
            return null;
        });
    }

    @Test void boundaryAndIdenticalBodiesDoNotCreateUndo() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("same\nsame"); var message = new AtomicReference<String>();
            try (var action = new SqlMoveLinesActions(editor, settings(), () -> true, () -> {}, Runnable::run, message::set)) {
                editor.getUndoManager().forgetHistory(); editor.selectRange(1, 3); action.upButton().fire();
                assertEquals(1, editor.getAnchor()); assertEquals(3, editor.getCaretPosition()); assertTrue(message.get().contains("首行"));
                action.downButton().fire(); assertEquals(6, editor.getAnchor()); assertEquals(8, editor.getCaretPosition());
                assertEquals("same\nsame", editor.getText()); assertTrue(message.get().contains("正文相同")); assertFalse(editor.isUndoAvailable());
                action.downButton().fire(); assertTrue(message.get().contains("末行")); assertFalse(editor.isUndoAvailable());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "callback-close", "callback-text", "callback-selection", "scope-close"})
    void preparedPlanCannotApplyAfterGuardOrSnapshotChanges(String change) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("a\nbb"); var allowed = new AtomicBoolean(true); var checks = new AtomicInteger();
            Runnable before = () -> {
                if (change.equals("callback-close")) allowed.set(false);
                if (change.equals("callback-text")) editor.replaceText("new");
                if (change.equals("callback-selection")) editor.selectRange(1, 0);
            };
            try (var action = new SqlMoveLinesActions(editor, settings(),
                    () -> allowed.get() && (!change.equals("admission") || checks.incrementAndGet() == 1), before,
                    edit -> { if (change.equals("scope-close")) allowed.set(false); edit.run(); }, ignored -> fail("must not report movement"))) {
                editor.moveTo(0); editor.getUndoManager().forgetHistory(); action.downButton().fire();
                assertEquals(change.equals("callback-text") ? "new" : "a\nbb", editor.getText());
                if (!change.equals("callback-text")) assertFalse(editor.isUndoAvailable());
                if (change.equals("callback-selection")) { assertEquals(1, editor.getAnchor()); assertEquals(0, editor.getCaretPosition()); }
            }
            return null;
        });
    }

    @Test void excessiveRangeIsRejectedWithoutChangingSelectionOrUndo() throws Exception {
        FxUiTestSupport.call(() -> {
            String text = "x\n".repeat(10_001) + "tail"; var editor = new CodeArea(text); var message = new AtomicReference<String>();
            try (var action = new SqlMoveLinesActions(editor, settings(), () -> true, () -> fail("must not edit"), Runnable::run, message::set)) {
                editor.selectRange(0, 20_002); editor.getUndoManager().forgetHistory(); action.downButton().fire();
                assertEquals(text, editor.getText()); assertEquals(0, editor.getAnchor()); assertEquals(20_002, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable()); assertTrue(message.get().contains("10,000"));
            }
            return null;
        });
    }
    private ShortcutSettings settings() { return new ShortcutSettings(directory.resolve("shortcuts")); }
    static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, true, false); }
}
