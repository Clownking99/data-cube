package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.*;
import com.datacube.spi.model.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorDuplicateIntegrationTest {
    @TempDir Path directory;

    @Test void toolbarDuplicatesCurrentLineWithoutWritingFileOrOpeningConnection() throws Exception {
        try (var f = new Fixture("select 1;\nselect 2;")) {
            FxUiTestSupport.call(() -> {
                assertNotNull(f.button(), "SQL toolbar needs the explicit duplicate-lines entry");
                f.editor.moveTo(3); f.button().fire();
                assertEquals("select 1;\nselect 1;\nselect 2;", f.editor.getText());
                assertEquals(13, f.editor.getCaretPosition()); assertEquals(13, f.editor.getAnchor());
                assertTrue(f.document().dirty()); assertTrue(f.title.endsWith("*"));
                f.editor.undo(); assertEquals("select 1;\nselect 2;", f.editor.getText());
                assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            assertEquals("select 1;\nselect 2;", Files.readString(f.file)); f.assertOffline();
        }
    }

    @Test void mixedSeparatorsReverseSelectionFileIdentityAndPassiveReadOnlyTargetSurviveUndoRedo() throws Exception {
        String raw = "  select '中😀';\r\n\tselect 2;\n-- keep\r";
        try (var f = new Fixture(raw)) {
            var connection = new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 12).applyTo(
                    new ConnConfig("synthetic", "synthetic readonly", DbType.POSTGRESQL, "example.invalid", 1, "synthetic", "", "", Map.of()));
            f.probe.manager.register(connection);
            FxUiTestSupport.call(() -> {
                assertTrue(f.pane.chooseFileConnection(connection));
                f.pane.setClipboardWriterForTesting(text -> { fail("duplicate must not use clipboard"); return false; });
                f.editor.setWrapText(true); String before = f.editor.getText(); int end = before.indexOf("-- keep");
                f.editor.selectRange(end, 2); f.button().fire();
                String copy = "  select '中😀';\r\n\tselect 2;\r\n";
                String expected = "  select '中😀';\r\n\tselect 2;\n" + copy + "-- keep\r";
                assertEquals(expected, f.document().physicalText());
                assertEquals(end * 2, f.editor.getAnchor()); assertEquals(end + 2, f.editor.getCaretPosition());
                var range = SqlExecutionRange.resolve(f.editor.getText(), f.editor.getAnchor(), f.editor.getCaretPosition());
                assertTrue(range.selection()); assertEquals(before.substring(2, end), range.extract(f.editor.getText()));
                assertEquals(f.file.toRealPath(), f.document().target().path()); assertTrue(f.document().dirty()); assertTrue(f.title.endsWith("*"));
                assertTrue(f.editor.isWrapText()); assertNull(field(f.pane, "jdbcSession"));
                f.editor.undo(); assertEquals(raw, f.document().physicalText()); assertFalse(f.document().dirty()); assertFalse(f.title.endsWith("*"));
                f.editor.redo(); assertEquals(expected, f.document().physicalText());
                return null;
            });
            assertEquals(raw, Files.readString(f.file)); f.assertOffline();
        }
    }

    @Test void newShortcutIsEditorScopedAndRebindingKeepsExistingCommentAction() throws Exception {
        try (var f = new Fixture("select 1;")) {
            FxUiTestSupport.call(() -> {
                var schema = (TextField) field(f.pane, "schemaField"); schema.fireEvent(SqlDuplicateLinesActionTest.key(KeyCode.D));
                assertEquals("select 1;", f.editor.getText());
                var shortcuts = (ShortcutSettings) field(f.pane, "shortcuts");
                shortcuts.apply(Map.of(ShortcutAction.SQL_DUPLICATE_LINES, KeyCombination.keyCombination("Ctrl+Shift+J")));
                f.editor.moveTo(3); f.editor.fireEvent(SqlDuplicateLinesActionTest.key(KeyCode.D)); assertEquals("select 1;", f.editor.getText());
                f.editor.fireEvent(SqlDuplicateLinesActionTest.key(KeyCode.J)); assertEquals("select 1;\nselect 1;", f.editor.getText());
                f.editor.undo(); ((Button) f.pane.getNode().lookup("#sql-line-comment")).fire(); assertEquals("-- select 1;", f.editor.getText());
                return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "resources", "tasks", "file-busy", "finalized", "readonly", "disabled"})
    void paneGuardsRejectOldButtonAndKeyboardWithoutEditing(String state) throws Exception {
        try (var f = new Fixture("select 1;")) {
            FxUiTestSupport.call(() -> {
                f.editor.selectRange(8, 2);
                switch (state) {
                    case "admission" -> ((SqlEditorConnectionAdmission) field(f.pane, "admission")).beginClosing();
                    case "resources" -> ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(true);
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "file-busy" -> setField(field(f.pane, "fileController"), "busy", true);
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "readonly" -> f.editor.setEditable(false);
                    case "disabled" -> f.pane.getNode().setDisable(true);
                    default -> throw new AssertionError(state);
                }
                try {
                    f.button().getOnAction().handle(new javafx.event.ActionEvent()); f.editor.fireEvent(SqlDuplicateLinesActionTest.key(KeyCode.D));
                    assertEquals("select 1;", f.editor.getText()); assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                    assertEquals(8, f.editor.getAnchor()); assertEquals(2, f.editor.getCaretPosition());
                } finally {
                    if (state.equals("resources")) ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(false);
                    if (state.equals("file-busy")) setField(field(f.pane, "fileController"), "busy", false);
                }
                return null;
            }); f.assertOffline();
        }
    }

    @Test void duplicateEntersDraftCheckpointButFreezeStopsFurtherCopiesAndSourceRemainsUntouched() throws Exception {
        try (var f = new Fixture("select 1;\nselect 2;"); var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var runtime = FxUiTestSupport.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"), writer,
                    javafx.application.Platform::runLater, javafx.application.Platform::isFxApplicationThread,
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), System::currentTimeMillis));
            try {
                writer.submit(() -> { }).get(5, TimeUnit.SECONDS); FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS);
                var binding = FxUiTestSupport.call(() -> f.pane.bindDraft(runtime, UUID.randomUUID(), null, ignored -> { }));
                FxUiTestSupport.call(() -> { f.editor.moveTo(0); f.button().fire(); return null; });
                var handle = (SqlDraftCoordinator.Handle) field(binding, "handle"); FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
                var snapshot = FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS).snapshot();
                String expected = "select 1;\nselect 1;\nselect 2;";
                assertTrue(snapshot.drafts().stream().anyMatch(d -> d.sql().equals(expected)));
                FxUiTestSupport.call(() -> {
                    binding.freeze(); f.button().getOnAction().handle(new javafx.event.ActionEvent()); f.editor.fireEvent(SqlDuplicateLinesActionTest.key(KeyCode.D));
                    assertEquals(expected, f.editor.getText()); return null;
                });
                assertEquals("select 1;\nselect 2;", Files.readString(f.file)); f.assertOffline();
            } finally { f.pane.closeResources(); FxUiTestSupport.call(runtime::shutdown).get(5, TimeUnit.SECONDS); }
        }
    }

    @ParameterizedTest @CsvSource({"600,dark", "880,dark", "600,light", "880,light"})
    void actualButtonAndTextFitBothThemesAndWrappedToolbar(double width, String theme) throws Exception {
        try (var f = new Fixture("select 1;")) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) f.pane.getNode();
                root.getScene().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                root.resize(width, 850); root.applyCss(); root.layout();
                var button = f.button(); var bounds = button.localToScene(button.getLayoutBounds());
                assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1);
                assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= root.getLayoutBounds().getHeight());
                var text = (javafx.scene.text.Text) button.lookup(".text"); assertEquals("重复行", text.getText());
                assertTrue(text.getLayoutBounds().getWidth() <= button.getWidth());
                button.fire(); assertEquals("select 1;\nselect 1;", f.editor.getText()); return null;
            }); f.assertOffline();
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe(); final FxTaskRunner runner = new FxTaskRunner();
        final Path file = directory.resolve("合成 重复行.sql"); final SqlEditorPane pane; final CodeArea editor;
        String title;
        Fixture(String sql) throws Exception {
            var loaded = new SqlScriptFileStore().load(Files.writeString(file, sql));
            pane = FxUiTestSupport.call(() -> {
                var p = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(directory.resolve("settings")), (id, table) -> fail("unexpected designer"),
                        new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
                p.installSqlScriptFileController(loaded, new SqlScriptFileStore(), new RecentSqlFiles(directory.resolve("recent")), value -> title = value, "SQL");
                Parent root = (Parent) p.getNode(); new Scene(root, 880, 850); root.applyCss(); root.layout(); return p;
            });
            editor = FxUiTestSupport.call(() -> (CodeArea) pane.getNode().lookup("#sql-editor"));
            FxUiTestSupport.call(() -> { editor.getUndoManager().forgetHistory(); return null; });
        }
        Button button() { return (Button) pane.getNode().lookup("#sql-duplicate-lines"); }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        void assertOffline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; }); runner.close(); probe.manager.closeAll();
        }
    }
    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void setField(Object owner, String name, Object value) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
