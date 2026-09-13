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

class SqlFormatActionTest {
    @TempDir Path directory;

    @Test void formatIsOneUndoBetweenTypingAndPreservesReverseSelectionAndDynamicLabel() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select a,b from t;"); var message = new AtomicReference<String>(); var refreshed = new AtomicInteger();
            try (var action = new SqlFormatAction(editor, settings(), () -> true, () -> { }, refreshed::incrementAndGet, message::set)) {
                assertEquals("美化全文", action.button().getText());
                editor.getUndoManager().forgetHistory(); editor.appendText("\n-- retained"); editor.selectRange(18, 0);
                assertEquals("美化选中", action.button().getText()); action.button().fire();
                String expected = "SELECT a,\n       b\n  FROM t;\n-- retained";
                assertEquals(expected, editor.getText()); assertEquals(28, editor.getAnchor()); assertEquals(0, editor.getCaretPosition());
                assertEquals(1, refreshed.get()); assertTrue(message.get().contains("选中"));
                editor.undo(); assertEquals("select a,b from t;\n-- retained", editor.getText());
                editor.undo(); assertEquals("select a,b from t;", editor.getText()); assertFalse(editor.isUndoAvailable());
                editor.redo(); editor.redo(); assertEquals(expected, editor.getText());
                editor.moveTo(editor.getLength()); editor.appendText("!"); editor.undo(); assertEquals(expected, editor.getText());
                editor.undo(); assertEquals("select a,b from t;\n-- retained", editor.getText());
            } return null;
        });
    }

    @Test void shortcutIsEditorScopedExactAndRebindableWithoutChangingFind() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;"); var other = new TextField(); new Scene(new VBox(editor, other)); var shortcuts = settings();
            try (var action = new SqlFormatAction(editor, shortcuts, () -> true, () -> { }, () -> { }, ignored -> { })) {
                other.fireEvent(key(KeyCode.L)); assertEquals("select 1;", editor.getText());
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertEquals("select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.L)); assertEquals("SELECT 1;", editor.getText()); editor.undo();
                shortcuts.apply(Map.of(ShortcutAction.SQL_FORMAT, KeyCombination.keyCombination("Ctrl+Alt+K")));
                assertEquals(KeyCombination.keyCombination("Ctrl+Alt+K"), settings().get(ShortcutAction.SQL_FORMAT));
                assertTrue(action.button().getTooltip().getText().contains("Ctrl+Alt+K"));
                assertEquals(KeyCombination.keyCombination("Ctrl+F"), shortcuts.get(ShortcutAction.SQL_FIND));
                editor.fireEvent(key(KeyCode.L)); assertEquals("select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.K)); assertEquals("SELECT 1;", editor.getText());
            } return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "busy", "readonly", "disabled", "parent-disabled", "closed"})
    void guardsBlockBothOldButtonAndKeyWithoutEditing(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;"); var root = new VBox(editor); new Scene(root);
            var allowed = new AtomicBoolean(true); var shortcuts = settings();
            try (var action = new SqlFormatAction(editor, shortcuts, allowed::get, () -> fail("no edit"), () -> fail("no highlight"), ignored -> { })) {
                editor.getUndoManager().forgetHistory(); editor.selectRange(8, 2);
                switch (state) {
                    case "guard" -> allowed.set(false);
                    case "busy" -> action.setBusy(true);
                    case "readonly" -> editor.setEditable(false);
                    case "disabled" -> editor.setDisable(true);
                    case "parent-disabled" -> root.setDisable(true);
                    case "closed" -> action.close();
                    default -> throw new AssertionError(state);
                }
                String hint = action.button().getTooltip().getText();
                action.button().getOnAction().handle(new javafx.event.ActionEvent()); editor.fireEvent(key(KeyCode.L));
                assertEquals("select 1;", editor.getText()); assertEquals(8, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable());
                if (state.equals("closed")) {
                    shortcuts.apply(Map.of(ShortcutAction.SQL_FORMAT, KeyCombination.keyCombination("Ctrl+Alt+K")));
                    editor.selectRange(0, 0); assertEquals(hint, action.button().getTooltip().getText()); assertTrue(action.button().isDisabled());
                }
            } return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "text", "selection"})
    void beforeEditCannotApplyStaleSnapshot(String change) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;"); var allowed = new AtomicBoolean(true);
            Runnable before = () -> {
                if (change.equals("guard")) allowed.set(false);
                if (change.equals("text")) editor.replaceText("new");
                if (change.equals("selection")) editor.selectRange(2, 1);
            };
            try (var action = new SqlFormatAction(editor, settings(), allowed::get, before, () -> fail("no highlight"), ignored -> fail("no success"))) {
                editor.getUndoManager().forgetHistory(); action.button().fire();
                assertEquals(change.equals("text") ? "new" : "select 1;", editor.getText());
                if (!change.equals("text")) assertFalse(editor.isUndoAvailable());
                if (change.equals("selection")) { assertEquals(2, editor.getAnchor()); assertEquals(1, editor.getCaretPosition()); }
            } return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"limit", "failure"})
    void planningFailureKeepsEditorAndDoesNotExposeExceptionText(String kind) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;"); var feedback = new AtomicReference<String>();
            try (var action = new SqlFormatAction(editor, settings(), () -> true, () -> fail("no edit"), () -> fail("no highlight"), feedback::set,
                    (text, anchor, caret) -> { if (kind.equals("limit")) throw new IllegalArgumentException("secret SQL"); throw new IllegalStateException("secret SQL"); })) {
                editor.selectRange(9, 0); editor.getUndoManager().forgetHistory(); action.button().fire();
                assertEquals("select 1;", editor.getText()); assertEquals(9, editor.getAnchor()); assertEquals(0, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable()); assertTrue(feedback.get().contains("未应用")); assertFalse(feedback.get().contains("secret"));
            } return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"SELECT 1;", " \t\n", ""})
    void unchangedOrBlankDoesNotTouchUndoSelectionOrCallbacks(String sql) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea(sql); var message = new AtomicReference<String>();
            try (var action = new SqlFormatAction(editor, settings(), () -> true, () -> fail("no edit"), () -> fail("no refresh"), message::set)) {
                editor.selectRange(sql.length(), 0); editor.getUndoManager().forgetHistory(); action.button().fire();
                assertEquals(sql, editor.getText()); assertEquals(sql.length(), editor.getAnchor()); assertEquals(0, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable()); assertNotNull(message.get());
            } return null;
        });
    }

    private ShortcutSettings settings() { return new ShortcutSettings(directory.resolve("shortcuts")); }
    static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, true, false); }
}
