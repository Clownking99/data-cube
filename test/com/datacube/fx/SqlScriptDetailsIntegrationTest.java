package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptDetailsIntegrationTest {
    @TempDir Path directory;

    @Test void mixedFailureIsNotReportedAsGreenSuccess() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(List.of(new ScriptOutcome(1, "select 1", QueryResult.query(List.of("n"), List.of(List.of(1)), 2)),
                        new ScriptOutcome(2, "broken sql", QueryResult.error("syntax error", 3))));
                Label status = (Label) field(f.pane, "statusLabel");
                assertFalse(status.getStyle().contains("-status-ok"), "a failed script must not use success styling");
                assertTrue(status.getText().contains("失败 1"));
                assertNotNull(f.root.lookup("#sql-script-details"));
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void sortedRowOpensOriginalSqlAndFullErrorWithoutChangingFileEditorOrUndo() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.batch();
                f.editor.insertText(0, "-- draft\n"); f.editor.selectRange(9, 15);
                String expected = f.editor.getText();
                var column = f.table().getColumns().getFirst(); column.setSortType(TableColumn.SortType.DESCENDING);
                f.table().getSortOrder().setAll(List.of(column)); f.table().sort();
                f.select(0); assertEquals(2, f.table().getItems().getFirst().getFirst());
                f.button().fire(); var dialog = f.dialog(); assertNotNull(dialog); assertTrue(dialog.isShowing());
                var sql = (TextArea) dialog.getDialogPane().lookup("#sql-script-detail-sql");
                var error = (TextArea) dialog.getDialogPane().lookup("#sql-script-detail-error");
                assertEquals("broken '<script>literal</script>'", sql.getText());
                assertEquals(f.error, error.getText()); assertFalse(sql.isEditable()); assertFalse(error.isEditable());
                assertTrue(((Label) dialog.getDialogPane().lookup("#sql-script-detail-identity")).getText().startsWith("语句 #2 · 失败"));
                assertTrue(((Label) dialog.getDialogPane().lookup("#sql-script-detail-boundary")).getText().contains("不代表已回滚"));
                f.details().showSelected(); assertSame(dialog, f.dialog(), "duplicate opening must reuse the existing dialog");
                dialog.getDialogPane().fireEvent(key(KeyCode.ESCAPE, false));
                assertFalse(dialog.isShowing()); assertNull(f.dialog());
                assertEquals(expected, f.editor.getText()); assertEquals(9, f.editor.getAnchor()); assertEquals(15, f.editor.getCaretPosition());
                assertTrue(f.document().dirty()); f.editor.undo();
                assertEquals(f.original, f.document().physicalText()); assertFalse(f.document().dirty());
                assertFalse(f.editor.isUndoAvailable()); return null;
            });
            f.assertOffline();
        }
    }

    @Test void enterRequiresSelectionAndIgnoresModifiedShortcutAndEqualButForeignRows() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.batch(); assertTrue(f.button().isDisabled());
                f.table().fireEvent(key(KeyCode.ENTER, false)); assertNull(f.dialog());
                f.select(0); f.table().fireEvent(key(KeyCode.ENTER, true)); assertNull(f.dialog());
                f.table().fireEvent(key(KeyCode.ENTER, false)); assertNotNull(f.dialog()); f.dialog().close();
                var cloned = FXCollections.observableArrayList(f.table().getItems().getFirst());
                f.table().setItems(FXCollections.observableArrayList(List.of(cloned))); f.select(0);
                f.details().showSelected(); assertNull(f.dialog()); assertTrue(f.button().isDisabled()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"query", "error", "plan", "clear", "batch", "close"})
    void replacingResultClosesDialogAndDiscardsOldSelection(String replacement) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.window(); f.batch(); f.select(1); f.button().fire(); var dialog = f.dialog();
                assertTrue(dialog.isShowing());
                switch (replacement) {
                    case "query" -> invoke(f.pane, "showQueryResult", new Class<?>[]{QueryResult.class, String.class},
                            QueryResult.query(List.of("n"), List.of(List.of(1)), 1), "select 1");
                    case "error" -> invoke(f.pane, "showError", new Class<?>[]{String.class, long.class}, "new error", 1L);
                    case "plan" -> invoke(f.pane, "showPlan", new Class<?>[]{String.class, long.class, int.class}, "scan", 1L, 2);
                    case "clear" -> invoke(f.pane, "clearResultFilterState", new Class<?>[]{});
                    case "batch" -> f.show(List.of(new ScriptOutcome(7, "new 7", QueryResult.update(1, 0)), new ScriptOutcome(8, "new 8", QueryResult.update(1, 0))));
                    case "close" -> f.pane.finalizeCloseOnFx();
                }
                assertFalse(dialog.isShowing()); assertNull(f.dialog()); assertNull(f.details().selectedEntry());
                f.details().showSelected(); assertNull(f.dialog());
                assertEquals(replacement.equals("batch"), f.details().getNode().isVisible());
                assertEquals(replacement.equals("batch"), f.details().getNode().isManaged());
                if (replacement.equals("batch")) { f.select(0); assertEquals("new 7", f.details().selectedEntry().sql().value()); }
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void rejectedQueryDoesNotDiscardCurrentBatchEvidence() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.batch(); f.select(1); var entry = f.details().selectedEntry(); var rows = f.table().getItems();
                assertEquals(false, invoke(f.pane, "showQueryResult", new Class<?>[]{QueryResult.class, String.class}, null, "invalid"));
                assertSame(rows, f.table().getItems()); assertSame(entry, f.details().selectedEntry());
                assertTrue(f.details().getNode().isVisible()); return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"resources", "tasks", "finalized", "disabled", "tableDisabled", "running", "admission", "queue"})
    void staleActionsCannotOpenBlockedPane(String blocker) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.batch(); f.select(0);
                switch (blocker) {
                    case "resources" -> f.pane.closeResources();
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "disabled" -> f.root.setDisable(true);
                    case "tableDisabled" -> f.table().setDisable(true);
                    case "running" -> setField(f.pane, "running", true);
                    case "admission" -> invoke(field(f.pane, "admission"), "beginClosing", new Class<?>[]{});
                    case "queue" -> invoke(field(f.pane, "sessionOperations"), "stopAcceptingAndCancelQueued", new Class<?>[]{});
                }
                f.button().getOnAction().handle(new javafx.event.ActionEvent());
                f.table().fireEvent(key(KeyCode.ENTER, false)); assertNull(f.dialog());
                if (blocker.equals("running")) setField(f.pane, "running", false);
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"normal", "timeout", "cancelled"})
    void summaryDistinguishesNormalTimeoutAndCancellation(String kind) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var result = switch (kind) { case "normal" -> QueryResult.update(1, -1); case "timeout" -> QueryResult.timeout("slow", 1); default -> QueryResult.cancelled("cancel", 1); };
                f.show(List.of(new ScriptOutcome(1, "first", QueryResult.update(1, 1)), new ScriptOutcome(2, "second", result)));
                Label status = (Label) field(f.pane, "statusLabel");
                assertEquals(kind.equals("normal"), status.getStyle().contains("-status-ok"));
                assertTrue(status.getText().contains(switch (kind) { case "normal" -> "正常 2"; case "timeout" -> "超时 1"; default -> "取消 1"; }));
                assertEquals(switch (kind) { case "normal" -> "影响行数未提供"; case "timeout" -> "slow"; default -> "cancel"; }, f.table().getItems().get(1).get(3));
                return null;
            });
        }
    }

    @ParameterizedTest @CsvSource({"480,dark", "640,light", "880,dark"})
    void batchEntryFitsNarrowAndWidePanels(double width, String theme) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.root.getScene().setRoot(new javafx.scene.layout.VBox());
                new Scene(f.root, width, 850).getStylesheets().addAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                f.batch(); f.root.resize(width, 850); f.root.applyCss(); f.root.layout();
                var bar = f.details().getNode(); var bounds = f.button().getBoundsInParent();
                assertTrue(bar.isVisible()); assertTrue(bar.getHeight() >= f.button().getHeight());
                assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= bar.getWidth());
                assertTrue(f.button().getWidth() >= f.button().prefWidth(-1) - 1); return null;
            });
        }
    }

    final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final SqlEditorPane pane;
        final Parent root;
        final String original = "select id from demo;\r\nselect name from demo;\n";
        final String error = "<script>literal text</script>\n" + "extended error ".repeat(15) + "\nSQLState=42000";
        final Path file = directory.resolve("synthetic-details.sql");
        final CodeArea editor;
        Stage stage;
        Fixture() throws Exception {
            var loaded = new SqlScriptFileStore().load(Files.writeString(file, original));
            pane = FxUiTestSupport.call(() -> SqlEditorPane.openSqlFile(new SessionContext(), probe.manager,
                    new ObjectTreeService(probe.manager), new AppSettings(directory.resolve("settings.properties")),
                    (id, table) -> fail("unexpected designer"), new SqlHistoryStore(directory.resolve("history")),
                    new ShortcutSettings(directory.resolve("shortcuts.properties")), runner));
            root = FxUiTestSupport.call(() -> {
                pane.installSqlScriptFileController(loaded, new SqlScriptFileStore(), new RecentSqlFiles(directory.resolve("recent")), ignored -> { }, "SQL");
                Parent node = (Parent) pane.getNode(); new Scene(node, 880, 850); node.applyCss(); node.layout(); return node;
            });
            editor = FxUiTestSupport.call(() -> { var area = (CodeArea) root.lookup("#sql-editor"); area.getUndoManager().forgetHistory(); return area; });
        }
        void window() { stage = new Stage(); stage.setScene(root.getScene()); stage.show(); }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        SqlScriptDetails details() { return (SqlScriptDetails) field(pane, "scriptDetails"); }
        SqlScriptDetailsDialog dialog() { return (SqlScriptDetailsDialog) field(details(), "dialog"); }
        Button button() { return (Button) root.lookup("#sql-script-details"); }
        @SuppressWarnings("unchecked") TableView<ObservableList<Object>> table() { return (TableView<ObservableList<Object>>) field(pane, "resultTable"); }
        void select(int row) { var column = table().getColumns().getFirst(); table().getSelectionModel().clearAndSelect(row, column); table().getFocusModel().focus(row, column); }
        void batch() throws Exception { show(List.of(new ScriptOutcome(1, "select 1", QueryResult.query(List.of("n"), List.of(List.of(1)), 2)), new ScriptOutcome(2, "broken '<script>literal</script>'", QueryResult.error(error, 3)))); }
        void show(List<ScriptOutcome> outcomes) throws Exception {
            var method = SqlEditorPane.class.getDeclaredMethod("showScriptResults", List.class, long.class);
            method.setAccessible(true); method.invoke(pane, outcomes, 5L);
        }
        void assertOffline() throws Exception {
            assertEquals(original, Files.readString(file));
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); if (stage != null) stage.close(); return null; });
            runner.close(); probe.manager.closeAll();
        }
    }
    static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    static void setField(Object owner, String name, Object value) throws Exception { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args);
    }
    static KeyEvent key(KeyCode code, boolean control) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false); }
}
