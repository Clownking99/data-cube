package com.datacube.fx;

import com.datacube.config.ShortcutAction;
import com.datacube.config.ShortcutSettings;
import java.nio.file.Path;
import java.util.Map;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCombination;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorScopeBarTest {
    @TempDir Path directory;

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
