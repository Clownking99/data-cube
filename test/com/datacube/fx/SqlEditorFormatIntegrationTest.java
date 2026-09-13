package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorFormatIntegrationTest {
    @TempDir Path directory;

    @Test void selectedSqlLeavesOtherStatementsAndPhysicalSeparatorsUntouched() throws Exception {
        String raw = "-- keep\r\nselect a,b from t;\nselect untouched;\r";
        try (var f = new Fixture(raw)) {
            FxUiTestSupport.call(() -> {
                int start = f.editor.getText().indexOf("select a");
                int end = f.editor.getText().indexOf("\nselect untouched");
                f.editor.selectRange(end, start);
                f.button().fire();
                assertEquals("-- keep\r\nSELECT a,\r\n       b\r\n  FROM t;\nselect untouched;\r", f.document().physicalText());
                assertEquals("SELECT a,\n       b\n  FROM t;", f.editor.getSelectedText());
                assertTrue(f.editor.getStyleOfChar(start).contains("sql-keyword"), "format must retain SQL highlighting");
                assertTrue(f.editor.getAnchor() > f.editor.getCaretPosition());
                assertTrue(f.document().dirty()); assertTrue(f.title.endsWith("*"));
                assertEquals(f.file.toRealPath(), f.document().path());
                f.editor.undo(); assertEquals(raw, f.document().physicalText());
                assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            assertEquals(raw, Files.readString(f.file)); f.assertOffline();
        }
    }

    @Test void whitespaceSelectionNeverFallsBackToFormattingWholeFile() throws Exception {
        try (var f = new Fixture("select a,b from t;\n  \nselect c;")) {
            FxUiTestSupport.call(() -> {
                String before = f.editor.getText(); int start = before.indexOf("  ");
                f.editor.selectRange(start, start + 2); f.button().fire();
                assertEquals(before, f.editor.getText()); assertFalse(f.document().dirty());
                assertEquals(start, f.editor.getAnchor()); assertEquals(start + 2, f.editor.getCaretPosition());
                assertFalse(f.editor.isUndoAvailable()); return null;
            }); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "resources", "tasks", "file-busy", "running", "finalized", "readonly", "disabled"})
    void paneGuardsRejectBothOldButtonAndShortcut(String state) throws Exception {
        try (var f = new Fixture("select 1;")) {
            FxUiTestSupport.call(() -> {
                f.editor.selectRange(9, 0);
                switch (state) {
                    case "admission" -> ((SqlEditorConnectionAdmission) field(f.pane, "admission")).beginClosing();
                    case "resources" -> ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(true);
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "file-busy" -> setField(field(f.pane, "fileController"), "busy", true);
                    case "running" -> setField(f.pane, "running", true);
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "readonly" -> f.editor.setEditable(false);
                    case "disabled" -> f.pane.getNode().setDisable(true);
                    default -> throw new AssertionError(state);
                }
                try {
                    f.button().getOnAction().handle(new javafx.event.ActionEvent()); f.editor.fireEvent(SqlFormatActionTest.key(KeyCode.L));
                    assertEquals("select 1;", f.editor.getText()); assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                    assertEquals(9, f.editor.getAnchor()); assertEquals(0, f.editor.getCaretPosition());
                } finally {
                    if (state.equals("resources")) ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(false);
                    if (state.equals("file-busy")) setField(field(f.pane, "fileController"), "busy", false);
                    if (state.equals("running")) setField(f.pane, "running", false);
                }
                return null;
            }); f.assertOffline();
        }
    }

    @Test void formattedTextEntersDraftButFreezeStopsFurtherChangesWithoutSavingSource() throws Exception {
        try (var f = new Fixture("select 1;"); var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var runtime = FxUiTestSupport.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"), writer,
                    javafx.application.Platform::runLater, javafx.application.Platform::isFxApplicationThread,
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), System::currentTimeMillis));
            try {
                writer.submit(() -> { }).get(5, TimeUnit.SECONDS); FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS);
                var binding = FxUiTestSupport.call(() -> f.pane.bindDraft(runtime, UUID.randomUUID(), null, ignored -> { }));
                FxUiTestSupport.call(() -> { f.button().fire(); return null; });
                var handle = (SqlDraftCoordinator.Handle) field(binding, "handle"); FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
                var snapshot = FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS).snapshot();
                assertTrue(snapshot.drafts().stream().anyMatch(d -> d.sql().equals("SELECT 1;")));
                FxUiTestSupport.call(() -> {
                    f.editor.undo(); assertEquals("select 1;", f.editor.getText()); binding.freeze();
                    f.button().getOnAction().handle(new javafx.event.ActionEvent()); f.editor.fireEvent(SqlFormatActionTest.key(KeyCode.L));
                    assertEquals("select 1;", f.editor.getText()); return null;
                });
                assertEquals("select 1;", Files.readString(f.file)); f.assertOffline();
            } finally { f.pane.closeResources(); FxUiTestSupport.call(runtime::shutdown).get(5, TimeUnit.SECONDS); }
        }
    }

    @ParameterizedTest @CsvSource({"600,dark", "880,dark", "600,light", "880,light"})
    void dynamicFormatLabelFitsToolbarInBothThemesAndShortcutKeepsSelection(double width, String theme) throws Exception {
        try (var f = new Fixture("select 1;\nselect untouched;")) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) f.pane.getNode();
                root.getScene().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.editor.setWrapText(true); assertEquals("美化全文", f.button().getText());
                f.editor.selectRange(9, 0); assertEquals("美化选中", f.button().getText());
                root.resize(width, 850); root.applyCss(); root.layout();
                var bounds = f.button().localToScene(f.button().getLayoutBounds());
                assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1);
                var text = (javafx.scene.text.Text) f.button().lookup(".text"); assertEquals("美化选中", text.getText());
                assertTrue(text.getLayoutBounds().getWidth() <= f.button().getWidth());
                f.editor.fireEvent(SqlFormatActionTest.key(KeyCode.L));
                assertEquals("SELECT 1;\nselect untouched;", f.editor.getText());
                assertEquals("SELECT 1;", f.editor.getSelectedText()); assertTrue(f.editor.isWrapText());
                assertTrue(((Button) root.lookup("#sql-execute")).getText().contains("选中"));
                return null;
            }); f.assertOffline();
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe(); final FxTaskRunner runner = new FxTaskRunner();
        final Path file = directory.resolve("synthetic-format.sql"); final SqlEditorPane pane; final CodeArea editor;
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
        Button button() { return (Button) pane.getNode().lookup("#sql-format"); }
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
