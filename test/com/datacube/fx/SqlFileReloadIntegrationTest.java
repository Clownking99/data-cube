package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.spi.model.QueryResult;
import com.datacube.fx.task.FxTaskScope;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.*;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlFileReloadIntegrationTest {
    @TempDir Path directory;

    @Test void boundFileHasReloadEntryWithoutAnyDatabaseAccess() throws Exception {
        try (var fixture = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var button = (Button) fixture.root.lookup("#sql-file-reload");
                assertNotNull(button, "an externally edited SQL file needs a reload entry");
                assertFalse(button.isDisabled()); return null;
            });
            fixture.assertOffline();
        }
    }

    @Test void actualReloadChangesOnlySqlFileStateNotResultsSchemaLayoutOrNetwork() throws Exception {
        try (var f = new Fixture()) {
            var before = FxUiTestSupport.call(() -> {
                setField(f.pane, "reloadSqlFileConfirmation", (java.util.function.BooleanSupplier) () -> true);
                ((TextField) field(f.pane, "schemaField")).setText("KEEP_SCHEMA");
                f.editor().appendText("-- unsaved\n");
                var show = SqlEditorPane.class.getDeclaredMethod("showQueryResult", QueryResult.class); show.setAccessible(true);
                show.invoke(f.pane, QueryResult.query(List.of("n"), List.of(List.of(7)), 3));
                ((SqlPanelLayout) field(f.pane, "panelLayout")).select(SqlPanelLayout.Mode.RESULTS);
                return ((TableView<?>) field(f.pane, "resultTable")).getItems();
            });
            String disk = "select disk_version from sample;\r\n-- external\n"; Files.writeString(f.file, disk);
            var result = FxUiTestSupport.call(f.pane::reloadSqlFile);
            assertTrue(result.toCompletableFuture().get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                assertEquals(disk.replace("\r\n", "\n"), f.editor().getText());
                assertEquals(disk, f.document().physicalText()); assertFalse(f.document().dirty());
                assertFalse(f.editor().isUndoAvailable()); assertFalse(f.editor().isRedoAvailable());
                assertSame(before, ((TableView<?>) field(f.pane, "resultTable")).getItems());
                assertEquals("KEEP_SCHEMA", ((TextField) field(f.pane, "schemaField")).getText());
                assertEquals(SqlPanelLayout.Mode.RESULTS, ((SqlPanelLayout) field(f.pane, "panelLayout")).mode());
                assertTrue(((Label) field(f.pane, "statusLabel")).getText().contains("未执行 SQL"));
                assertFalse(f.button().isDisabled()); return null;
            });
            assertEquals(disk, Files.readString(f.file)); f.assertOffline();
        }
    }

    @Test void actualButtonCancelPreservesTextAndUndo() throws Exception {
        try (var f = new Fixture()) {
            var called = new AtomicInteger();
            FxUiTestSupport.call(() -> {
                setField(f.pane, "reloadSqlFileConfirmation", (java.util.function.BooleanSupplier) () -> { called.incrementAndGet(); return false; });
                f.editor().appendText("-- unsaved"); f.editor().selectRange(1, 5);
                f.button().fire(); assertEquals(1, called.get());
                assertEquals("select 1;\n-- unsaved", f.editor().getText());
                assertEquals(1, f.editor().getAnchor()); assertEquals(5, f.editor().getCaretPosition());
                assertTrue(f.editor().isUndoAvailable()); assertTrue(f.document().dirty()); return null;
            });
            assertEquals("select 1;\r\n", Files.readString(f.file)); f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "editorDisabled", "running", "admission", "queue"})
    void staleReloadButtonCannotConfirmOrChangeBlockedPane(String blocker) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                setField(f.pane, "reloadSqlFileConfirmation", (java.util.function.BooleanSupplier) () -> { fail("blocked pane must not confirm"); return true; });
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "editorDisabled" -> f.editor().setDisable(true);
                    case "running" -> setField(f.pane, "running", true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing");
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued");
                }
                f.button().getOnAction().handle(new javafx.event.ActionEvent());
                assertFalse(f.pane.reloadSqlFile().toCompletableFuture().join());
                assertEquals("select 1;\n", f.editor().getText());
                if (blocker.equals("running")) setField(f.pane, "running", false);
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void confirmationIsExplicitAboutDiscardingAndDefaultsToCancel() throws Exception {
        FxUiTestSupport.call(() -> {
            var dialog = SqlFileReloadDialog.create(null);
            assertTrue(dialog.getContentText().contains("未保存的修改以及撤销/重做记录将被丢弃"));
            assertTrue(dialog.getContentText().contains("不会写入磁盘或执行 SQL"));
            assertFalse(((Button) dialog.getDialogPane().lookupButton(SqlFileReloadDialog.RELOAD)).isDefaultButton());
            assertTrue(((Button) dialog.getDialogPane().lookupButton(SqlFileReloadDialog.CANCEL)).isDefaultButton());
            assertEquals(ButtonBar.ButtonData.CANCEL_CLOSE, SqlFileReloadDialog.CANCEL.getButtonData()); return null;
        });
    }

    final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SqlScriptFileStore store = new SqlScriptFileStore();
        final Path file = directory.resolve("reload.sql");
        final SqlEditorPane pane;
        final Parent root;
        Fixture() throws Exception {
            var loaded = store.load(Files.writeString(file, "select 1;\r\n"));
            pane = FxUiTestSupport.call(() -> {
                var p = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(directory.resolve("settings")), (id, table) -> fail("unexpected designer"),
                        new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
                p.installSqlScriptFileController(loaded, store, new RecentSqlFiles(directory.resolve("recent")), ignored -> { }, "SQL");
                return p;
            });
            root = FxUiTestSupport.call(() -> { var node = (Parent) pane.getNode(); new Scene(node, 880, 850); node.applyCss(); node.layout(); return node; });
        }
        void assertOffline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        CodeArea editor() { return (CodeArea) field(pane, "editorArea"); }
        Button button() { return (Button) root.lookup("#sql-file-reload"); }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        @Override public void close() throws Exception {
            pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            runner.close(); probe.manager.closeAll();
        }
    }
    static Object field(Object owner, String name) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    static void invoke(Object owner, String name) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner);
    }
}
