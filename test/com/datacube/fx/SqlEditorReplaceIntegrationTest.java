package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.beans.value.ChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorReplaceIntegrationTest {
    @TempDir Path directory;

    @Test void replacementPreservesPhysicalLineEndingsAndFileIdentityWhileUndoRestoresCleanState() throws Exception {
        String raw = "select '中';\r\n-- keep LF\nselect 2;\r-- tail\r\n";
        try (var f = new Fixture(raw)) {
            ConnConfig readonly = new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 12).applyTo(
                    new ConnConfig("synthetic", "read-only target", DbType.POSTGRESQL, "synthetic.invalid", 1,
                            "synthetic", "", "", Map.of()));
            f.probe.manager.register(readonly);
            FxUiTestSupport.call(() -> { assertTrue(f.pane.chooseFileConnection(readonly)); return null; });
            f.prepare("select", "SELECT");
            f.replaceAll();
            FxUiTestSupport.call(() -> {
                assertEquals(raw.replace("\r\n", "\n").replace('\r', '\n').replace("select", "SELECT"), f.editor.getText());
                assertTrue(f.document().dirty());
                assertEquals(raw.replace("select", "SELECT"), f.document().physicalText(), "untouched mixed separators must survive");
                assertEquals(f.file.toRealPath(), f.document().target().path());
                assertTrue(f.title.endsWith("*"));
                assertNull(field(f.pane, "jdbcSession"), "read-only DB configuration does not prohibit offline text editing");
                f.editor.undo();
                assertEquals(raw, f.document().physicalText());
                assertFalse(f.document().dirty());
                assertFalse(f.title.endsWith("*"));
                assertFalse(f.editor.isUndoAvailable());
                f.editor.redo();
                assertEquals(raw.replace("select", "SELECT"), f.document().physicalText());
                assertTrue(f.document().dirty());
                return null;
            });
            assertEquals(raw, Files.readString(f.file));
            f.assertOffline();
        }
    }

    @Test void replacementUsesExistingDraftCheckpointWithoutSavingTheSqlFile() throws Exception {
        String raw = "select 1; select 2;";
        try (var f = new Fixture(raw); var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            SqlDraftCoordinator runtime = FxUiTestSupport.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"),
                    writer, javafx.application.Platform::runLater, javafx.application.Platform::isFxApplicationThread,
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), System::currentTimeMillis));
            try {
                // Barrier after the initial writer task, then the FX call observes its posted completion.
                writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS);
                var binding = FxUiTestSupport.call(() -> f.pane.bindDraft(runtime, UUID.randomUUID(), null, ignored -> { }));
                f.prepare("select", "SELECT");
                f.replaceAll();
                var handle = (SqlDraftCoordinator.Handle) field(binding, "handle");
                FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
                var snapshot = FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS).snapshot();
                assertTrue(snapshot.drafts().stream().anyMatch(d -> d.sql().equals("SELECT 1; SELECT 2;")));
                assertEquals(raw, Files.readString(f.file));
                f.assertOffline();
            } finally {
                f.pane.closeResources();
                FxUiTestSupport.call(runtime::shutdown).get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void closingAdmissionBlocksReplacementEvenIfOldButtonsRemainVisible() throws Exception {
        try (var f = new Fixture("select 1;")) {
            f.prepare("select", "SELECT");
            FxUiTestSupport.call(() -> {
                ((SqlEditorConnectionAdmission) field(f.pane, "admission")).beginClosing();
                f.button("all").fire();
                assertEquals("select 1;", f.editor.getText());
                assertFalse(f.document().dirty());
                assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            f.assertOffline();
        }
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final Path file = directory.resolve("合成 脚本.sql");
        final SqlEditorPane pane;
        final CodeArea editor;
        String title;
        Fixture(String sql) throws Exception {
            var loaded = new SqlScriptFileStore().load(Files.writeString(file, sql));
            pane = FxUiTestSupport.call(() -> {
                var p = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(directory.resolve("settings")), (id, table) -> fail("unexpected designer"),
                        new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
                p.installSqlScriptFileController(loaded, new SqlScriptFileStore(),
                        new RecentSqlFiles(directory.resolve("recent")), value -> title = value, "SQL");
                Parent root = (Parent) p.getNode();
                new Scene(root, 880, 800); root.applyCss(); root.layout();
                return p;
            });
            editor = FxUiTestSupport.call(() -> (CodeArea) pane.getNode().lookup("#sql-editor"));
            FxUiTestSupport.call(() -> { editor.getUndoManager().forgetHistory(); return null; });
        }
        Button button(String name) { return (Button) pane.getNode().lookup("#sql-replace-" + name); }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        void prepare(String query, String value) throws Exception {
            CompletableFuture<Void> ready = new CompletableFuture<>();
            ChangeListener<Boolean> listener = (obs, before, disabled) -> { if (!disabled) ready.complete(null); };
            FxUiTestSupport.call(() -> {
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.H, false, true, false, false));
                button("all").disabledProperty().addListener(listener);
                ((TextField) pane.getNode().lookup("#sql-replace-text")).setText(value);
                var search = (TextField) pane.getNode().lookup("#sql-find-query");
                search.setText(query);
                search.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                return null;
            });
            try { ready.get(5, TimeUnit.SECONDS); }
            finally { FxUiTestSupport.call(() -> { button("all").disabledProperty().removeListener(listener); return null; }); }
        }
        void replaceAll() throws Exception {
            CompletableFuture<Void> finished = new CompletableFuture<>();
            Label status = FxUiTestSupport.call(() -> (Label) pane.getNode().lookup("#sql-replace-status"));
            ChangeListener<String> listener = (obs, before, text) -> {
                if (text.startsWith("已替换")) finished.complete(null);
                else if (text.contains("失败") || text.contains("未应用")) finished.completeExceptionally(new AssertionError(text));
            };
            FxUiTestSupport.call(() -> { status.textProperty().addListener(listener); button("all").fire(); return null; });
            try { finished.get(5, TimeUnit.SECONDS); }
            finally { FxUiTestSupport.call(() -> { status.textProperty().removeListener(listener); return null; }); }
        }
        void assertOffline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            pane.closeResources();
            FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            runner.close(); probe.manager.closeAll();
        }
    }

    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
