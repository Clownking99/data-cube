package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlLineCommentActionTest {
    @TempDir Path directory;

    @Test void buttonPreservesBackwardRangeAndSeparatesToggleFromTypingInUndoRedo() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("a\nb\nlast");
            var before = new AtomicInteger(); var message = new AtomicReference<String>();
            try (var action = new SqlLineCommentAction(editor, settings(), () -> true, before::incrementAndGet, message::set)) {
                editor.getUndoManager().forgetHistory(); editor.insertText(editor.getLength(), "!"); editor.selectRange(4, 0);
                action.button().fire();
                assertEquals("-- a\n-- b\nlast!", editor.getText());
                assertEquals(10, editor.getAnchor()); assertEquals(0, editor.getCaretPosition());
                assertEquals("-- a\n-- b\n", editor.getSelectedText());
                assertEquals(1, before.get()); assertTrue(message.get().contains("添加 2 行注释"));
                editor.undo(); assertEquals("a\nb\nlast!", editor.getText());
                editor.undo(); assertEquals("a\nb\nlast", editor.getText()); assertFalse(editor.isUndoAvailable());
                editor.redo(); editor.redo(); assertEquals("-- a\n-- b\nlast!", editor.getText());
                editor.selectRange(10, 0); action.apply();
                assertEquals("a\nb\nlast!", editor.getText()); assertEquals(4, editor.getAnchor()); assertEquals(0, editor.getCaretPosition());
                assertTrue(message.get().contains("取消 2 行注释"));
                editor.insertText(editor.getLength(), "?"); editor.undo(); assertEquals("a\nb\nlast!", editor.getText());
                editor.undo(); assertEquals("-- a\n-- b\nlast!", editor.getText());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"guard", "readonly", "disabled", "parent-disabled", "closed"})
    void unavailableAndStaleButtonCannotChangeTextSelectionOrUndo(String state) throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("  select 1;"); var parent = new VBox(editor); new Scene(parent);
            var allowed = new AtomicBoolean(true); var shortcuts = settings();
            try (var action = new SqlLineCommentAction(editor, shortcuts, allowed::get, () -> fail("unexpected edit"), ignored -> { })) {
                editor.getUndoManager().forgetHistory(); editor.selectRange(8, 2);
                switch (state) {
                    case "guard" -> allowed.set(false);
                    case "readonly" -> editor.setEditable(false);
                    case "disabled" -> editor.setDisable(true);
                    case "parent-disabled" -> parent.setDisable(true);
                    case "closed" -> action.close();
                    default -> throw new AssertionError(state);
                }
                String hint = action.button().getTooltip().getText();
                action.button().getOnAction().handle(new javafx.event.ActionEvent()); action.apply();
                assertEquals("  select 1;", editor.getText()); assertEquals(8, editor.getAnchor()); assertEquals(2, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable());
                if (state.equals("closed")) {
                    shortcuts.apply(Map.of(ShortcutAction.SQL_LINE_COMMENT, KeyCombination.keyCombination("Ctrl+U")));
                    assertEquals(hint, action.button().getTooltip().getText()); assertTrue(action.button().isDisabled());
                }
            }
            return null;
        });
    }

    @Test void blankAndOverLimitHaveFeedbackButNeverEditOrCreateUndo() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea(" \n\t"); var message = new AtomicReference<String>();
            try (var action = new SqlLineCommentAction(editor, settings(), () -> true, () -> fail("unexpected edit"), message::set)) {
                editor.selectRange(editor.getLength(), 0); editor.getUndoManager().forgetHistory(); action.apply();
                assertEquals(" \n\t", editor.getText()); assertEquals(3, editor.getAnchor()); assertEquals(0, editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable()); assertTrue(message.get().contains("均为空白"));
                String many = "x\n".repeat(10001);
                editor.replaceText(many); editor.selectAll(); editor.getUndoManager().forgetHistory(); action.apply();
                assertEquals(many, editor.getText()); assertEquals(0, editor.getAnchor()); assertEquals(many.length(), editor.getCaretPosition());
                assertFalse(editor.isUndoAvailable()); assertTrue(message.get().contains("10,000"));
            }
            return null;
        });
    }

    @Test void rechecksAdmissionAfterPlanAndBeforeAnyMutation() throws Exception {
        FxUiTestSupport.call(() -> {
            var editor = new CodeArea("select 1;"); var checks = new AtomicInteger();
            try (var action = new SqlLineCommentAction(editor, settings(), () -> checks.incrementAndGet() == 1,
                    () -> fail("admission closed"), ignored -> { })) {
                editor.moveTo(4); editor.getUndoManager().forgetHistory(); action.apply();
                assertEquals(2, checks.get()); assertEquals("select 1;", editor.getText());
                assertEquals(4, editor.getCaretPosition()); assertFalse(editor.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void backgroundShortcutChangeRefreshesHintOnFxAndCloseDetachesListener() throws Exception {
        var shortcuts = settings();
        var action = FxUiTestSupport.call(() -> new SqlLineCommentAction(new CodeArea("x"), shortcuts, () -> true, () -> { }, ignored -> { }));
        try {
            shortcuts.apply(Map.of(ShortcutAction.SQL_LINE_COMMENT, KeyCombination.keyCombination("Ctrl+U")));
            FxUiTestSupport.call(() -> {
                assertTrue(action.button().getTooltip().getText().contains("Ctrl+U"));
                assertEquals(KeyCombination.keyCombination("Ctrl+U"), settings().get(ShortcutAction.SQL_LINE_COMMENT));
                action.close(); return null;
            });
            shortcuts.apply(Map.of());
            FxUiTestSupport.call(() -> { assertTrue(action.button().getTooltip().getText().contains("Ctrl+U")); return null; });
        } finally { FxUiTestSupport.call(() -> { action.close(); return null; }); }
    }

    private ShortcutSettings settings() { return new ShortcutSettings(directory.resolve("shortcuts")); }
}
