package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlEditorWholeWordIntegrationTest {
    @TempDir Path directory;

    @Test void wholeWordReplaceUsesRealPaneFileAndDraftWithoutDatabaseOrSourceWrites() throws Exception {
        String original = "select id,user_id,id2 from t;\r\nselect ID;\n";
        try (var f = new Fixture(original); var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var runtime = FxUiTestSupport.call(() -> new SqlDraftCoordinator(directory.resolve("drafts"), writer,
                    javafx.application.Platform::runLater, javafx.application.Platform::isFxApplicationThread,
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), System::currentTimeMillis));
            try {
                writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS);
                var binding = FxUiTestSupport.call(() -> f.pane.bindDraft(runtime, UUID.randomUUID(), null, ignored -> { }));
                awaitStatus(f, "sql-find-status", "共 2 处", () -> {
                    f.editor.moveTo(0); f.editor.fireEvent(key(KeyCode.F, true));
                    assertTrue(f.pane.getNode().lookup("#sql-find-bar").isVisible());
                    assertFalse(f.words().isSelected()); f.words().fire();
                    assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                    f.query().setText("id"); f.query().fireEvent(key(KeyCode.ENTER, false));
                });
                awaitStatus(f, "sql-replace-status", "已替换 2 处，可撤销；未自动保存文件或执行 SQL。", () -> {
                    f.editor.fireEvent(key(KeyCode.H, true));
                    assertTrue(f.words().isSelected());
                    // Opening replace refreshes the find snapshot; submit explicitly, then wait below.
                    f.query().fireEvent(key(KeyCode.ENTER, false));
                }, () -> {
                    ((TextField) f.pane.getNode().lookup("#sql-replace-text")).setText("key");
                    ((Button) f.pane.getNode().lookup("#sql-replace-all")).fire();
                });
                FxUiTestSupport.call(() -> {
                    assertEquals("select key,user_id,id2 from t;\r\nselect key;\n", f.document().physicalText());
                    assertTrue(f.document().dirty()); assertTrue(f.title.endsWith("*"));
                    assertEquals(f.file.toRealPath(), f.document().path());
                    assertTrue(((Button) f.pane.getNode().lookup("#sql-execute")).isDisabled());
                    return null;
                });
                var handle = (SqlDraftCoordinator.Handle) field(binding, "handle");
                FxUiTestSupport.call(handle::flush).get(5, TimeUnit.SECONDS);
                var snapshot = FxUiTestSupport.call(runtime::refresh).get(5, TimeUnit.SECONDS).snapshot();
                assertEquals(java.util.List.of("select key,user_id,id2 from t;\nselect key;\n"),
                        snapshot.drafts().stream().map(SqlDraft::sql).toList(), "drafts retain the editor's logical LF text");
                FxUiTestSupport.call(() -> {
                    f.editor.undo(); assertEquals(original, f.document().physicalText());
                    assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable()); return null;
                });
                assertEquals(original, Files.readString(f.file));
                assertEquals(0, f.probe.providers.get()); assertEquals(0, f.probe.sessions.get());
                assertEquals(0, f.probe.metadata.get()); assertEquals(0, f.probe.network.get());
            } finally {
                f.pane.closeResources(); FxUiTestSupport.call(runtime::shutdown).get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static KeyEvent key(KeyCode code, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false);
    }

    private static void awaitStatus(Fixture f, String id, String expected, Runnable action) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        FxUiTestSupport.call(() -> {
            Label label = (Label) f.pane.getNode().lookup("#" + id);
            label.textProperty().addListener(new ChangeListener<>() {
                @Override public void changed(ObservableValue<? extends String> value, String oldText, String text) {
                    if (expected.equals(text)) { label.textProperty().removeListener(this); ready.complete(null); }
                }
            });
            action.run(); return null;
        });
        ready.get(5, TimeUnit.SECONDS);
    }

    private static void awaitStatus(Fixture f, String id, String expected, Runnable refresh, Runnable replace) throws Exception {
        awaitStatus(f, "sql-find-status", "共 2 处", refresh);
        awaitStatus(f, id, expected, replace);
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe(); final FxTaskRunner runner = new FxTaskRunner();
        final Path file = directory.resolve("synthetic-words.sql"); final SqlEditorPane pane; final CodeArea editor;
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
        CheckBox words() { return (CheckBox) pane.getNode().lookup("#sql-find-whole-word"); }
        TextField query() { return (TextField) pane.getNode().lookup("#sql-find-query"); }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        @Override public void close() throws Exception {
            pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; }); runner.close(); probe.manager.closeAll();
        }
    }
    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
