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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

class SqlEditorMoveLinesIntegrationTest {
    @TempDir Path directory;

    @Test void toolbarOffersBothDirectionsWithoutConnecting() throws Exception {
        var probe = new DraftConnectionProbe(); var runner = new FxTaskRunner();
        var pane = FxUiTestSupport.call(() -> {
            var value = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                    new AppSettings(directory.resolve("settings")), (id, table) -> fail("unexpected navigation"),
                    new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
            new Scene((javafx.scene.Parent) value.getNode()); return value;
        });
        try {
            FxUiTestSupport.call(() -> {
                assertNotNull(pane.getNode().lookup("#sql-move-lines-up"), "Toolbar needs explicit move-up entry");
                assertNotNull(pane.getNode().lookup("#sql-move-lines-down"), "Toolbar needs explicit move-down entry");
                return null;
            });
            assertEquals(0, probe.providers.get() + probe.sessions.get() + probe.metadata.get() + probe.network.get());
        } finally {
            pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            runner.close(); probe.manager.closeAll();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void physicalSeparatorsFileIdentityReverseRangeAndPassiveTargetSurviveUndoRedo(boolean up) throws Exception {
        String raw = "head\r\n  select '中😀';\n\tselect 2;\r-- keep";
        String expected = up ? "  select '中😀';\r\n\tselect 2;\nhead\r-- keep"
                : "head\r\n-- keep\n  select '中😀';\r\tselect 2;";
        try (var f = new Fixture(raw)) {
            var connection = new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 12).applyTo(
                    new ConnConfig("synthetic", "synthetic readonly", DbType.POSTGRESQL, "example.invalid", 1, "synthetic", "", "", Map.of()));
            f.probe.manager.register(connection);
            FxUiTestSupport.call(() -> {
                assertTrue(f.pane.chooseFileConnection(connection));
                f.pane.setClipboardWriterForTesting(text -> { fail("must not use clipboard"); return false; });
                f.editor.setWrapText(true); int end = f.editor.getText().indexOf("-- keep");
                f.editor.selectRange(end, 7); f.button(up).fire();
                assertEquals(expected, f.document().physicalText());
                String normalized = expected.replace("\r\n", "\n").replace("\r", "\n");
                assertEquals(normalized, f.editor.getText()); assertEquals(normalized.indexOf("select"), f.editor.getCaretPosition());
                assertEquals(up ? normalized.indexOf("head") : normalized.length(), f.editor.getAnchor());
                assertEquals(up ? "select '中😀';\n\tselect 2;\n" : "select '中😀';\n\tselect 2;", f.editor.getSelectedText());
                assertTrue(f.editor.getStyleOfChar(f.editor.getCaretPosition()).contains("sql-keyword"));
                assertTrue(f.document().dirty()); assertTrue(f.title.endsWith("*")); assertTrue(f.editor.isWrapText());
                assertEquals(f.file.toRealPath(), f.document().target().path()); assertNull(field(f.pane, "jdbcSession"));
                f.editor.undo(); assertEquals(raw, f.document().physicalText());
                assertFalse(f.document().dirty()); assertFalse(f.title.endsWith("*")); assertFalse(f.editor.isUndoAvailable());
                f.editor.redo(); assertEquals(expected, f.document().physicalText()); return null;
            });
            assertEquals(raw, Files.readString(f.file)); f.assertOffline();
        }
    }

    @Test void movingQualifiedTextDoesNotRequestCompletionAndPreservesExplicitExecutionRange() throws Exception {
        try (var f = new Fixture("select a.\nfrom table_a a;\n-- end")) {
            var calls = new AtomicInteger();
            FxUiTestSupport.call(() -> {
                var completion = (SqlAutoComplete) field(f.pane, "autoComplete");
                completion.setMemberProvider(qualifier -> { calls.incrementAndGet(); return List.of("unexpected"); });
                f.editor.selectRange(0, "select a.".length()); f.button(false).fire();
                assertEquals("from table_a a;\nselect a.\n-- end", f.editor.getText());
                var range = SqlExecutionRange.resolve(f.editor.getText(), f.editor.getAnchor(), f.editor.getCaretPosition());
                assertTrue(range.selection()); assertEquals("select a.", range.extract(f.editor.getText()));
                assertTrue(((Button) f.pane.getNode().lookup("#sql-execute")).getText().contains("选中")); return null;
            });
            FxUiTestSupport.call(() -> null); assertEquals(0, calls.get()); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "resources", "tasks", "file-busy", "finalized", "readonly", "disabled"})
    void paneGuardsRejectBothDirectionsAndOldKeyboard(String state) throws Exception {
        try (var f = new Fixture("a\nbb\nc")) {
            FxUiTestSupport.call(() -> {
                f.editor.selectRange(4, 2);
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
                    f.button(true).getOnAction().handle(new javafx.event.ActionEvent());
                    f.button(false).getOnAction().handle(new javafx.event.ActionEvent());
                    f.editor.fireEvent(SqlMoveLinesActionsTest.key(KeyCode.UP)); f.editor.fireEvent(SqlMoveLinesActionsTest.key(KeyCode.DOWN));
                    assertEquals("a\nbb\nc", f.editor.getText()); assertEquals(4, f.editor.getAnchor()); assertEquals(2, f.editor.getCaretPosition());
                    assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                } finally {
                    if (state.equals("resources")) ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(false);
                    if (state.equals("file-busy")) setField(field(f.pane, "fileController"), "busy", false);
                }
                return null;
            }); f.assertOffline();
        }
    }

    @Test void movementEntersDraftCheckpointButFreezeStopsLaterEdits() throws Exception {
        try (var f = new Fixture("select 1;\nselect 2;"); var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var runtime = FxUiTestSupport.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"), writer,
                    javafx.application.Platform::runLater, javafx.application.Platform::isFxApplicationThread,
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), System::currentTimeMillis));
            try {
                writer.submit(() -> {}).get(5, TimeUnit.SECONDS); FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS);
                var binding = FxUiTestSupport.call(() -> f.pane.bindDraft(runtime, UUID.randomUUID(), null, ignored -> {}));
                FxUiTestSupport.call(() -> { f.editor.moveTo(3); f.button(false).fire(); return null; });
                var handle = (SqlDraftCoordinator.Handle) field(binding, "handle"); FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
                var snapshot = FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS).snapshot();
                assertTrue(snapshot.drafts().stream().anyMatch(d -> d.sql().equals("select 2;\nselect 1;")));
                FxUiTestSupport.call(() -> {
                    binding.freeze(); f.button(true).getOnAction().handle(new javafx.event.ActionEvent());
                    f.editor.fireEvent(SqlMoveLinesActionsTest.key(KeyCode.UP));
                    assertEquals("select 2;\nselect 1;", f.editor.getText()); return null;
                });
                assertEquals("select 1;\nselect 2;", Files.readString(f.file)); f.assertOffline();
            } finally { f.pane.closeResources(); FxUiTestSupport.call(runtime::shutdown).get(5, TimeUnit.SECONDS); }
        }
    }

    @ParameterizedTest @CsvSource({"600,dark", "880,dark", "600,light", "880,light"})
    void bothDirectionButtonsFitWrappedToolbarWithReadableLabels(double width, String theme) throws Exception {
        try (var f = new Fixture("a\nbb\nc")) {
            FxUiTestSupport.call(() -> {
                Parent root = (Parent) f.pane.getNode();
                root.getScene().getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                root.resize(width, 850); root.applyCss(); root.layout();
                for (boolean up : new boolean[]{true, false}) {
                    var button = f.button(up); var bounds = button.localToScene(button.getLayoutBounds());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= width + 1);
                    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= 850);
                    var label = (javafx.scene.text.Text) button.lookup(".text"); assertEquals(up ? "上移行" : "下移行", label.getText());
                    assertTrue(label.getLayoutBounds().getWidth() <= button.getWidth());
                }
                f.editor.moveTo(0); f.button(false).fire(); assertEquals("bb\na\nc", f.editor.getText()); return null;
            }); f.assertOffline();
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe(); final FxTaskRunner runner = new FxTaskRunner();
        final Path file = directory.resolve("synthetic-move.sql"); final SqlEditorPane pane; final CodeArea editor;
        String title;
        Fixture(String sql) throws Exception {
            var loaded = new SqlScriptFileStore().load(Files.writeString(file, sql));
            pane = FxUiTestSupport.call(() -> {
                var p = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(directory.resolve("settings")), (id, table) -> fail("unexpected navigation"),
                        new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
                p.installSqlScriptFileController(loaded, new SqlScriptFileStore(), new RecentSqlFiles(directory.resolve("recent")), value -> title = value, "SQL");
                Parent root = (Parent) p.getNode(); new Scene(root, 880, 850); root.applyCss(); root.layout(); return p;
            });
            editor = FxUiTestSupport.call(() -> (CodeArea) pane.getNode().lookup("#sql-editor"));
            FxUiTestSupport.call(() -> { editor.getUndoManager().forgetHistory(); return null; });
        }
        Button button(boolean up) { return (Button) pane.getNode().lookup(up ? "#sql-move-lines-up" : "#sql-move-lines-down"); }
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
