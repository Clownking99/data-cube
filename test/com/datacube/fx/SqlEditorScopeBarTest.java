package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import java.nio.file.Path;
import java.util.Map;
import javafx.scene.control.Label;
import javafx.scene.control.CheckBox;
import javafx.scene.input.KeyCombination;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorScopeBarTest {
    @TempDir Path directory;

    @Test void wrappingChangesOnlyTheViewAndKeepsLogicalPositionAndExecutionSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            String text = "select '" + "中😀 long value ".repeat(80) + "';\nselect 2;";
            CodeArea editor = new CodeArea(text);
            editor.getUndoManager().forgetHistory();
            try (var bar = new SqlEditorScopeBar(editor, new ShortcutSettings(directory.resolve("wrap.properties")))) {
                var wrap = (CheckBox) bar.getNode().lookup("#sql-editor-wrap");
                assertNotNull(wrap, "the editor needs a visible wrapping control");
                assertFalse(wrap.isSelected()); assertFalse(editor.isWrapText());
                editor.selectRange(text.length(), text.indexOf('\n') + 1);
                String position = label(bar, "sql-editor-position");
                String scope = label(bar, "sql-execution-scope");
                wrap.fire();
                assertTrue(wrap.isSelected()); assertTrue(editor.isWrapText());
                assertEquals(text, editor.getText());
                assertEquals(text.length(), editor.getAnchor());
                assertEquals(text.indexOf('\n') + 1, editor.getCaretPosition());
                assertEquals(position, label(bar, "sql-editor-position"));
                assertEquals(scope, label(bar, "sql-execution-scope"));
                assertEquals("执行选中 (F5)", bar.executeLabelProperty().get());
                assertEquals(2, editor.getParagraphs().size());
                wrap.fire();
                assertFalse(editor.isWrapText());
                assertEquals(text, editor.getText());
                assertFalse(editor.isUndoAvailable()); assertFalse(editor.isRedoAvailable());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"", "single", "select 1;\n\t select 2;\n", "\t中文😀", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void wrappingPreservesForwardSelectionAndExistingUndoRedoHistory(String text) throws Exception {
        FxUiTestSupport.call(() -> {
            CodeArea editor = new CodeArea(text);
            editor.getUndoManager().forgetHistory();
            editor.appendText("-- edit");
            editor.undo();
            assertTrue(editor.isRedoAvailable());
            try (var bar = new SqlEditorScopeBar(editor, new ShortcutSettings(directory.resolve("history.properties")))) {
                editor.selectRange(0, text.length());
                var wrap = (CheckBox) bar.getNode().lookup("#sql-editor-wrap");
                wrap.fire();
                assertTrue(editor.isWrapText());
                assertEquals(0, editor.getAnchor()); assertEquals(text.length(), editor.getCaretPosition());
                assertEquals(text, editor.getText());
                assertTrue(editor.isRedoAvailable()); assertFalse(editor.isUndoAvailable());
                editor.redo();
                assertEquals(text + "-- edit", editor.getText());
                wrap.fire();
                editor.undo();
                assertEquals(text, editor.getText());
                assertFalse(editor.isUndoAvailable(), "wrapping must not consume an undo step");
            }
            return null;
        });
    }

    @Test void wrappingIsPerEditorAndNewEditorsKeepTheDefault() throws Exception {
        FxUiTestSupport.call(() -> {
            var settings = new ShortcutSettings(directory.resolve("independent.properties"));
            CodeArea first = new CodeArea("select 1;");
            CodeArea second = new CodeArea("select 2;");
            try (var a = new SqlEditorScopeBar(first, settings); var b = new SqlEditorScopeBar(second, settings)) {
                ((CheckBox) a.getNode().lookup("#sql-editor-wrap")).fire();
                assertTrue(first.isWrapText()); assertFalse(second.isWrapText());
                assertFalse(((CheckBox) b.getNode().lookup("#sql-editor-wrap")).isSelected());
                CodeArea third = new CodeArea("select 3;");
                try (var c = new SqlEditorScopeBar(third, settings)) {
                    assertFalse(third.isWrapText());
                    assertFalse(((CheckBox) c.getNode().lookup("#sql-editor-wrap")).isSelected());
                }
            }
            assertFalse(java.nio.file.Files.exists(directory.resolve("independent.properties")), "view options are not saved as settings");
            return null;
        });
    }

    @Test void blockedDisabledAndClosedControlsDoNotChangeTheViewAndCanReopenBeforeDisposal() throws Exception {
        FxUiTestSupport.call(() -> {
            CodeArea editor = new CodeArea("select 1;");
            var allowed = new java.util.concurrent.atomic.AtomicBoolean(false);
            var bar = new SqlEditorScopeBar(editor, new ShortcutSettings(directory.resolve("guard.properties")), null, allowed::get);
            try {
                var wrap = (CheckBox) bar.getNode().lookup("#sql-editor-wrap");
                wrap.fire();
                assertFalse(editor.isWrapText()); assertFalse(wrap.isSelected());
                allowed.set(true);
                wrap.fire(); assertTrue(editor.isWrapText());
                editor.setDisable(true);
                wrap.fire(); assertTrue(editor.isWrapText()); assertTrue(wrap.isSelected());
                editor.setDisable(false);
                allowed.set(false);
                wrap.fire(); assertTrue(editor.isWrapText()); assertTrue(wrap.isSelected());
                allowed.set(true);
                wrap.fire(); assertFalse(editor.isWrapText());
                var oldAction = wrap.getOnAction();
                bar.close(); bar.close();
                assertTrue(wrap.isDisabled());
                wrap.setSelected(true); oldAction.handle(new javafx.event.ActionEvent());
                assertFalse(editor.isWrapText()); assertFalse(wrap.isSelected());
                assertEquals("select 1;", editor.getText()); assertFalse(editor.isUndoAvailable());
            } finally { bar.close(); }
            return null;
        });
    }

    @Test void caretSelectionAndTextChangesStayAccurateWithoutEditingOrMovingSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            CodeArea editor = new CodeArea();
            var settings = new ShortcutSettings(directory.resolve("shortcuts.properties"));
            try (var bar = new SqlEditorScopeBar(editor, settings)) {
                assertEquals("行 1 · 列 1", label(bar, "sql-editor-position"));
                assertEquals("执行全部 (F5)", bar.executeLabelProperty().get());
                editor.replaceText("select 1;\n\t中😀");
                editor.getUndoManager().forgetHistory();
                editor.moveTo(14);
                assertEquals("行 2 · 列 5", label(bar, "sql-editor-position"));
                editor.selectRange(14, 10);
                assertEquals("行 2 · 列 1 · 选中 4", label(bar, "sql-editor-position"));
                assertEquals("执行选中 (F5)", bar.executeLabelProperty().get());
                assertEquals("执行范围：选中内容", label(bar, "sql-execution-scope"));
                assertEquals(14, editor.getAnchor());
                assertEquals(10, editor.getCaretPosition());
                editor.selectRange(9, 11);
                assertEquals("行 2 · 列 2 · 选中 2", label(bar, "sql-editor-position"));
                assertEquals("执行全部 (F5)", bar.executeLabelProperty().get());
                assertEquals("执行范围：全部 SQL（选区仅含空白）", label(bar, "sql-execution-scope"));
                assertEquals("select 1;\n\t中😀", editor.getText());
                assertFalse(editor.isUndoAvailable(), "scope/caret reporting must not add an edit");
                editor.clear(); // Shortening the document must settle old selection offsets safely.
                assertEquals("行 1 · 列 1", label(bar, "sql-editor-position"));
                assertEquals("执行范围：全部 SQL", label(bar, "sql-execution-scope"));
            }
            return null;
        });
    }

    @Test void rebindingUpdatesBothEditorsWithoutSharingSelectionsAndDetachesOnClose() throws Exception {
        FxUiTestSupport.call(() -> {
            var settings = new ShortcutSettings(directory.resolve("shortcuts.properties"));
            CodeArea first = new CodeArea("select 1;");
            CodeArea second = new CodeArea("select 2;");
            try (var a = new SqlEditorScopeBar(first, settings); var b = new SqlEditorScopeBar(second, settings)) {
                first.selectAll();
                var binding = KeyCombination.keyCombination("Ctrl+Enter");
                settings.apply(Map.of(ShortcutAction.SQL_EXECUTE, binding));
                assertEquals("执行选中 (" + binding.getDisplayText() + ")", a.executeLabelProperty().get());
                assertEquals("执行全部 (" + binding.getDisplayText() + ")", b.executeLabelProperty().get());
                settings.apply(Map.of(ShortcutAction.SQL_EXECUTE, KeyCombination.keyCombination("F6")));
                assertEquals("执行选中 (F6)", a.executeLabelProperty().get());
                assertEquals("执行全部 (F6)", b.executeLabelProperty().get());
                a.close();
                String frozenPosition = label(a, "sql-editor-position");
                first.clear();
                settings.apply(Map.of());
                assertEquals("执行选中 (F6)", a.executeLabelProperty().get());
                assertEquals(frozenPosition, label(a, "sql-editor-position"));
                assertEquals("执行全部 (F5)", b.executeLabelProperty().get());
                assertEquals("select 2;", second.getText());
                assertFalse(second.isUndoAvailable());
            }
            return null;
        });
    }

    @Test void queuedBackgroundShortcutRefreshCannotReviveAClosedBar() throws Exception {
        var bar = FxUiTestSupport.call(() -> {
            var settings = new ShortcutSettings(directory.resolve("background.properties"));
            var value = new SqlEditorScopeBar(new CodeArea("select 1;"), settings);
            try {
                var applied = new java.util.concurrent.CompletableFuture<Void>();
                Thread.ofVirtual().start(() -> {
                    try {
                        settings.apply(Map.of(ShortcutAction.SQL_EXECUTE, KeyCombination.keyCombination("F6")));
                        applied.complete(null);
                    } catch (Throwable failure) { applied.completeExceptionally(failure); }
                });
                // Propagate worker failures; merely observing thread exit could falsely pass.
                applied.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(KeyCombination.keyCombination("F6"), settings.get(ShortcutAction.SQL_EXECUTE));
                return value;
            } finally { value.close(); }
        });
        FxUiTestSupport.call(() -> {
            assertEquals("执行全部 (F5)", bar.executeLabelProperty().get(), "queued refresh must respect disposal");
            return null;
        });
    }

    private static String label(SqlEditorScopeBar bar, String id) {
        return ((Label) bar.getNode().lookup("#" + id)).getText();
    }
}
