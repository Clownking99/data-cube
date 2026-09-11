package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

class SqlIndentActionsTest {
    @TempDir Path directory;

    @Test void multiLineEditIsOneUndoStepSeparateFromTypingAndPreservesBackwardSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("a\nb\nlast");
            var message = new AtomicReference<String>();
            var hidden = new AtomicBoolean();
            try (var actions = new SqlIndentActions(editor, settings(), () -> true, () -> hidden.set(true), message::set)) {
                editor.getUndoManager().forgetHistory();
                editor.insertText(editor.getLength(), "!");
                editor.selectRange(4, 0);
                actions.indentButton().fire();
                assertEquals("    a\n    b\nlast!", editor.getText());
                assertEquals(12, editor.getAnchor()); assertEquals(4, editor.getCaretPosition());
                assertTrue(hidden.get()); assertTrue(message.get().contains("2 行"));
                editor.undo();
                assertEquals("a\nb\nlast!", editor.getText());
                editor.undo();
                assertEquals("a\nb\nlast", editor.getText());
                assertFalse(editor.isUndoAvailable());
                editor.redo(); editor.redo();
                assertEquals("    a\n    b\nlast!", editor.getText());
                editor.selectRange(12, 4);
                actions.outdentButton().fire();
                assertEquals("a\nb\nlast!", editor.getText());
                assertEquals(4, editor.getAnchor()); assertEquals(0, editor.getCaretPosition());
                editor.undo();
                assertEquals("    a\n    b\nlast!", editor.getText());
            }
            return null;
        });
    }

    @Test void rebindPersistsRefreshesHintsAndDoesNotHandleOtherFieldsOrTab() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;");
            var other = new TextField("schema");
            new Scene(new VBox(editor, other), 600, 400);
            var settings = settings();
            try (var actions = new SqlIndentActions(editor, settings, () -> true, () -> { }, ignored -> { })) {
                editor.getUndoManager().forgetHistory();
                other.fireEvent(key(KeyCode.CLOSE_BRACKET));
                assertEquals("select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.CLOSE_BRACKET));
                assertEquals("    select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.OPEN_BRACKET));
                assertEquals("select 1;", editor.getText());
                settings.apply(Map.of(ShortcutAction.SQL_INDENT, KeyCombination.keyCombination("Ctrl+I"),
                        ShortcutAction.SQL_OUTDENT, KeyCombination.keyCombination("Ctrl+U")));
                assertEquals(KeyCombination.keyCombination("Ctrl+I"), settings().get(ShortcutAction.SQL_INDENT));
                assertTrue(actions.indentButton().getTooltip().getText().contains("Ctrl+I"));
                editor.fireEvent(key(KeyCode.CLOSE_BRACKET));
                assertEquals("select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.I));
                assertEquals("    select 1;", editor.getText());
                editor.fireEvent(key(KeyCode.U));
                assertEquals("select 1;", editor.getText());
                // Observe after our filter, before editor behavior consumes normal Tab.
                var tabConsumed = new AtomicBoolean(true);
                editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (event.getCode() == KeyCode.TAB) { tabConsumed.set(event.isConsumed()); event.consume(); }
                });
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
                assertFalse(tabConsumed.get());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "readonly", "disabled", "parent-disabled", "closed"})
    void unavailableAndStaleActionsCannotMutateTextOrUndo(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("  select 1;");
            var root = new VBox(editor);
            new Scene(root);
            var allowed = new AtomicBoolean(true);
            var settings = settings();
            var actions = new SqlIndentActions(editor, settings, allowed::get, () -> fail("unexpected edit"), ignored -> { });
            try {
                editor.getUndoManager().forgetHistory();
                editor.selectRange(7, 2);
                switch (state) {
                    case "guard" -> allowed.set(false);
                    case "readonly" -> editor.setEditable(false);
                    case "disabled" -> editor.setDisable(true);
                    case "parent-disabled" -> root.setDisable(true);
                    case "closed" -> actions.close();
                    default -> throw new AssertionError(state);
                }
                var oldHint = actions.indentButton().getTooltip().getText();
                actions.indentButton().getOnAction().handle(new javafx.event.ActionEvent());
                actions.outdentButton().getOnAction().handle(new javafx.event.ActionEvent());
                editor.fireEvent(key(KeyCode.CLOSE_BRACKET));
                assertEquals("  select 1;", editor.getText());
                assertEquals(7, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable());
                if (state.equals("closed")) {
                    settings.apply(Map.of(ShortcutAction.SQL_INDENT, KeyCombination.keyCombination("Ctrl+I")));
                    assertEquals(oldHint, actions.indentButton().getTooltip().getText(), "listener detached");
                }
            } finally { actions.close(); }
            return null;
        });
    }

    @Test void noOpAndOverLimitLeaveUndoAndSelectionUntouchedWithFeedback() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;");
            var message = new AtomicReference<String>();
            try (var actions = new SqlIndentActions(editor, settings(), () -> true, () -> fail("must not edit"), message::set)) {
                editor.getUndoManager().forgetHistory();
                editor.selectRange(8, 2);
                actions.outdentButton().fire();
                assertTrue(message.get().contains("没有可移除"));
                assertEquals(8, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable());
                String many = "x\n".repeat(10_001);
                editor.replaceText(many); editor.selectAll(); editor.getUndoManager().forgetHistory();
                actions.indentButton().fire();
                assertEquals(many, editor.getText());
                assertEquals(0, editor.getAnchor()); assertEquals(many.length(), editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable()); assertTrue(message.get().contains("10,000"));
            }
            return null;
        });
    }

    @Test void admissionIsRecheckedBeforeApplyingPreparedEdits() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;");
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            try (var actions = new SqlIndentActions(editor, settings(), () -> checks.incrementAndGet() == 1,
                    () -> fail("admission closed before edit"), ignored -> { })) {
                editor.getUndoManager().forgetHistory();
                actions.indentButton().fire();
                assertEquals(2, checks.get());
                assertEquals("select 1;", editor.getText());
                assertFalse(editor.isUndoAvailable());
            }
            return null;
        });
    }

    private ShortcutSettings settings() { return new ShortcutSettings(directory.resolve("shortcuts")); }
    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false);
    }
}
