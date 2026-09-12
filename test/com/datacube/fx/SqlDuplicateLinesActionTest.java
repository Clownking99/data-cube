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

class SqlDuplicateLinesActionTest {
    @TempDir Path directory;

    @Test void duplicationIsOneUndoStepBetweenTypingAndPreservesReverseSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("a\nb\nlast"); var message = new AtomicReference<String>(); var hidden = new AtomicInteger();
            try (var action = new SqlDuplicateLinesAction(editor, settings(), () -> true, hidden::incrementAndGet, message::set)) {
                editor.getUndoManager().forgetHistory(); editor.insertText(editor.getLength(), "!"); editor.selectRange(4, 0);
                action.button().fire(); assertEquals("a\nb\na\nb\nlast!", editor.getText());
                assertEquals(8, editor.getAnchor()); assertEquals(4, editor.getCaretPosition());
                assertEquals(1, hidden.get()); assertTrue(message.get().contains("2 行"));
                editor.undo(); assertEquals("a\nb\nlast!", editor.getText());
                editor.undo(); assertEquals("a\nb\nlast", editor.getText()); assertFalse(editor.isUndoAvailable());
                editor.redo(); editor.redo(); assertEquals("a\nb\na\nb\nlast!", editor.getText());
                editor.moveTo(editor.getLength()); editor.insertText(editor.getLength(), "?");
                editor.undo(); assertEquals("a\nb\na\nb\nlast!", editor.getText());
                editor.undo(); assertEquals("a\nb\nlast!", editor.getText());
            }
            return null;
        });
    }

    @Test void shortcutRebindPersistsAndOnlyHandlesEditorWithExactModifiers() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;"); var other = new TextField("schema"); new Scene(new VBox(editor, other));
            var settings = settings();
            try (var action = new SqlDuplicateLinesAction(editor, settings, () -> true, () -> { }, ignored -> { })) {
                other.fireEvent(key(KeyCode.D)); assertEquals("select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.D)); assertEquals("select 1;\nselect 1;", editor.getText()); editor.undo();
                settings.apply(Map.of(ShortcutAction.SQL_DUPLICATE_LINES, KeyCombination.keyCombination("Ctrl+Shift+J")));
                assertEquals(KeyCombination.keyCombination("Ctrl+Shift+J"), settings().get(ShortcutAction.SQL_DUPLICATE_LINES));
                assertTrue(action.button().getTooltip().getText().contains("Ctrl+Shift+J"));
                editor.fireEvent(key(KeyCode.D)); assertEquals("select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.J)); assertEquals("select 1;\nselect 1;", editor.getText());
                var tabConsumed = new AtomicBoolean(true);
                editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> { if (e.getCode() == KeyCode.TAB) { tabConsumed.set(e.isConsumed()); e.consume(); } });
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false)); assertFalse(tabConsumed.get());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "readonly", "disabled", "parent-disabled", "closed"})
    void unavailableOrClosedActionCannotChangeTextSelectionOrUndo(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;"); var root = new VBox(editor); new Scene(root);
            var allowed = new AtomicBoolean(true); var settings = settings();
            var action = new SqlDuplicateLinesAction(editor, settings, allowed::get, () -> fail("must not edit"), ignored -> { });
            try {
                editor.getUndoManager().forgetHistory(); editor.selectRange(8, 2);
                switch (state) {
                    case "guard" -> allowed.set(false);
                    case "readonly" -> editor.setEditable(false);
                    case "disabled" -> editor.setDisable(true);
                    case "parent-disabled" -> root.setDisable(true);
                    case "closed" -> action.close();
                    default -> throw new AssertionError(state);
                }
                String hint = action.button().getTooltip().getText();
                action.button().getOnAction().handle(new javafx.event.ActionEvent()); editor.fireEvent(key(KeyCode.D));
                assertEquals("select 1;", editor.getText()); assertEquals(8, editor.getAnchor()); assertEquals(2, editor.getCaretPosition()); assertFalse(editor.isUndoAvailable());
                if (state.equals("closed")) {
                    settings.apply(Map.of(ShortcutAction.SQL_DUPLICATE_LINES, KeyCombination.keyCombination("Ctrl+Shift+J")));
                    assertEquals(hint, action.button().getTooltip().getText()); assertTrue(action.button().isDisabled());
                }
            } finally { action.close(); }
            return null;
        });
    }

    @Test void limitFailureKeepsOriginalTextSelectionUndoAndReportsBound() throws Exception {
        FxUiTestSupport.call(() -> {
            String many = "x\n".repeat(10_001); var editor = new CodeArea(many); var message = new AtomicReference<String>();
            try (var action = new SqlDuplicateLinesAction(editor, settings(), () -> true, () -> fail("must not edit"), message::set)) {
                editor.selectAll(); editor.getUndoManager().forgetHistory(); action.button().fire();
                assertEquals(many, editor.getText()); assertEquals(0, editor.getAnchor()); assertEquals(many.length(), editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable()); assertTrue(message.get().contains("10,000"));
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "callback-close", "callback-text", "callback-selection"})
    void preparedPlanIsRejectedIfAdmissionOrEditorChanges(String change) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("abc"); var allowed = new AtomicBoolean(true); var checks = new AtomicInteger();
            Runnable before = () -> {
                if (change.equals("callback-close")) allowed.set(false);
                if (change.equals("callback-text")) editor.replaceText("new");
                if (change.equals("callback-selection")) editor.selectRange(2, 1);
            };
            try (var action = new SqlDuplicateLinesAction(editor, settings(),
                    () -> allowed.get() && (!change.equals("admission") || checks.incrementAndGet() == 1), before, ignored -> fail("must not report duplicate"))) {
                editor.getUndoManager().forgetHistory(); action.button().fire();
                assertEquals(change.equals("callback-text") ? "new" : "abc", editor.getText());
                if (!change.equals("callback-text")) assertFalse(editor.isUndoAvailable());
                if (change.equals("callback-selection")) { assertEquals(2, editor.getAnchor()); assertEquals(1, editor.getCaretPosition()); }
            }
            return null;
        });
    }

    private ShortcutSettings settings() { return new ShortcutSettings(directory.resolve("shortcuts")); }
    static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, true, true, false, false); }
}
