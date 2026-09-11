package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import com.datacube.spi.model.*;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.sqleditor.SqlScriptDocument;
import com.datacube.sqleditor.result.ResultFilterState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SqlResultCellIntegrationTest {
    @TempDir Path directory;

    @Test void sortedFilteredReorderedDuplicateColumnsResolveByIdentityWithoutChangingEditorOrFile() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample());
                ((ResultFilterState) field(f.pane, "resultFilterState")).setSearchText("keep");
                invoke(f.pane, "renderResultFilterSnapshot");
                var table = f.table;
                var seq = table.getColumns().get(0);
                var id = table.getColumns().get(1);
                var firstValue = table.getColumns().get(2);
                var secondValue = table.getColumns().get(3);
                table.getColumns().setAll(List.of(seq, secondValue, id, firstValue));
                firstValue.setVisible(false);
                id.setSortType(TableColumn.SortType.DESCENDING);
                table.getSortOrder().setAll(List.of(id)); table.sort();
                assertEquals(3, table.getItems().getFirst().getFirst());
                table.getSelectionModel().clearAndSelect(1, secondValue);
                table.getFocusModel().focus(1, secondValue);
                f.editor.selectRange(8, 2);
                var before = List.copyOf(table.getSelectionModel().getSelectedCells());
                var preview = f.pane.captureResultCellPreview();
                assertNotNull(preview); assertEquals("one-B", preview.text());
                assertEquals(2, preview.sourceRow()); assertEquals(2, preview.displayRow());
                assertEquals(3, preview.column()); assertEquals("value", preview.label());
                assertEquals("VARCHAR", preview.type());
                assertEquals(before, table.getSelectionModel().getSelectedCells());
                assertEquals(List.of(id), table.getSortOrder());
                assertFalse(firstValue.isVisible());
                assertEquals(8, f.editor.getAnchor()); assertEquals(2, f.editor.getCaretPosition());
                assertFalse(f.editor.isUndoAvailable()); assertFalse(f.document().dirty());
                assertEquals("select 'offline';", f.editor.getText());
                assertNull(field(f.pane, "jdbcSession"));
                return null;
            });
            assertEquals("select 'offline';", Files.readString(f.file));
            f.assertOffline();
        }
    }

    @Test void explicitToolbarAndContextEntryShowImmutableSnapshotAndFinalizationClosesDialog() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                assertTrue(f.button().isDisabled());
                f.show(sample());
                f.select(0, 2);
                f.button().fire();
                var dialog = f.dialog();
                assertNotNull(dialog); assertTrue(dialog.isShowing());
                var text = (TextArea) dialog.getDialogPane().lookup("#result-cell-text");
                assertEquals("two-B", text.getText()); assertFalse(text.isEditable());
                f.button().fire(); assertSame(dialog, f.dialog(), "one viewer per editor");
                f.show(QueryResult.query(List.of("new"), List.of(List.of("replacement")), 0));
                assertEquals("two-B", text.getText(), "new result must not retarget an open snapshot");
                dialog.close(); assertNull(f.dialog());
                f.select(0, 0);
                f.menu().fire();
                assertEquals("replacement", ((TextArea) f.dialog().getDialogPane().lookup("#result-cell-text")).getText());
                var last = f.dialog();
                f.pane.finalizeCloseOnFx();
                assertFalse(last.isShowing()); assertNull(f.dialog());
                f.menu().getOnAction().handle(new javafx.event.ActionEvent());
                assertNull(f.dialog());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void pendingSearchDoesNotChangeTheCellCapturedAtClickTime() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample()); f.select(0, 2);
                ((javafx.scene.control.TextField) f.pane.getNode().lookup("#sql-result-search")).setText("keep");
                f.button().fire();
                var text = (TextArea) f.dialog().getDialogPane().lookup("#result-cell-text");
                assertEquals("two-B", text.getText());
                var toolbar = (SqlResultToolbar) field(f.pane, "resultToolbar");
                assertTrue(toolbar.flushPendingSearch(), "viewer must not flush or implicitly change selection");
                assertEquals(2, f.table.getItems().size());
                assertEquals("two-B", text.getText(), "later local filtering cannot retarget the snapshot");
                f.dialog().close();
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"no-focus", "unselected-focus", "sequence", "hidden", "detached-row", "mismatched-result", "cleared", "short-row"})
    void missingOrStaleCellsNeverFallBackToAnotherValue(String state) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample()); f.select(0, 2);
                switch (state) {
                    case "no-focus" -> f.table.getFocusModel().focus(-1);
                    case "unselected-focus" -> f.table.getSelectionModel().clearSelection();
                    case "sequence" -> {
                        f.table.getSelectionModel().clearAndSelect(0, f.table.getColumns().getFirst());
                        f.table.getFocusModel().focus(0, f.table.getColumns().getFirst());
                    }
                    case "hidden" -> f.table.getColumns().get(3).setVisible(false);
                    case "detached-row" -> {
                        f.table.getItems().set(0, FXCollections.observableArrayList(f.table.getItems().getFirst()));
                        f.select(0, 2);
                    }
                    case "mismatched-result" -> ((ResultFilterState) field(f.pane, "resultFilterState"))
                            .showOriginal(sample(), "select synthetic", null, "unsupported");
                    case "cleared" -> invoke(f.pane, "clearResultFilterState");
                    case "short-row" -> { f.show(QueryResult.query(List.of("a", "b"), List.of(List.of("only-a")), 0)); f.select(0, 1); }
                    default -> throw new AssertionError(state);
                }
                assertNull(f.pane.captureResultCellPreview());
                f.menu().fire(); assertNull(f.dialog());
                assertEquals("请先选择一个结果数据单元格，再查看其内容。", ((javafx.scene.control.Label) field(f.pane, "statusLabel")).getText());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "resources", "tasks", "running", "queue-closed", "disabled", "plan"})
    void closedBusyOrNonTableStatesRejectOldViewActions(String state) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample()); f.select(0, 2);
                switch (state) {
                    case "admission" -> ((SqlEditorConnectionAdmission) field(f.pane, "admission")).beginClosing();
                    case "resources" -> ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(true);
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "running" -> setField(f.pane, "running", true);
                    case "queue-closed" -> ((SerialSessionOperationQueue) field(f.pane, "sessionOperations")).stopAcceptingAndCancelQueued();
                    case "disabled" -> f.pane.getNode().setDisable(true);
                    case "plan" -> invoke(f.pane, "usePlan");
                    default -> throw new AssertionError(state);
                }
                try {
                    assertNull(f.pane.captureResultCellPreview());
                    f.menu().getOnAction().handle(new javafx.event.ActionEvent());
                    f.button().getOnAction().handle(new javafx.event.ActionEvent());
                    assertNull(f.dialog());
                    assertFalse(f.document().dirty());
                } finally {
                    if (state.equals("resources")) ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(false);
                    if (state.equals("running")) setField(f.pane, "running", false);
                }
                return null;
            });
            f.assertOffline();
        }
    }

    private static QueryResult sample() {
        return QueryResult.queryWithMetadata(List.of(new ResultColumn(0, "id", Types.INTEGER, "INTEGER"),
                new ResultColumn(1, "value", Types.VARCHAR, "VARCHAR"), new ResultColumn(2, "value", Types.VARCHAR, "VARCHAR")),
                List.of(List.of(2, "skip", "two-B"), List.of(1, "keep", "one-B"), List.of(3, "keep", "three-B")), 12, false);
    }

    private final class Fixture implements AutoCloseable {
        final DraftConnectionProbe probe = new DraftConnectionProbe();
        final FxTaskRunner runner = new FxTaskRunner();
        final Path file = directory.resolve("offline.sql");
        final SqlEditorPane pane;
        final CodeArea editor;
        final TableView<ObservableList<Object>> table;
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            var loaded = new SqlScriptFileStore().load(Files.writeString(file, "select 'offline';"));
            pane = FxUiTestSupport.call(() -> {
                var p = SqlEditorPane.openSqlFile(new SessionContext(), probe.manager, new ObjectTreeService(probe.manager),
                        new AppSettings(directory.resolve("settings")), (id, value) -> fail("unexpected designer"),
                        new SqlHistoryStore(directory.resolve("history")), new ShortcutSettings(directory.resolve("shortcuts")), runner);
                p.installSqlScriptFileController(loaded, new SqlScriptFileStore(), new RecentSqlFiles(directory.resolve("recent")), ignored -> { }, "SQL");
                Parent root = (Parent) p.getNode(); new Scene(root, 880, 800); root.applyCss(); root.layout(); return p;
            });
            editor = (CodeArea) field(pane, "editorArea");
            table = (TableView<ObservableList<Object>>) field(pane, "resultTable");
            FxUiTestSupport.call(() -> { editor.getUndoManager().forgetHistory(); return null; });
        }
        void show(QueryResult result) throws Exception {
            Method method = SqlEditorPane.class.getDeclaredMethod("showQueryResult", QueryResult.class, String.class);
            method.setAccessible(true); assertEquals(true, method.invoke(pane, result, "select synthetic"));
        }
        void select(int row, int sourceColumn) {
            var column = table.getColumns().stream().filter(c -> Integer.valueOf(sourceColumn).equals(c.getUserData())).findFirst().orElseThrow();
            table.getSelectionModel().clearAndSelect(row, column); table.getFocusModel().focus(row, column);
        }
        Button button() { return (Button) pane.getNode().lookup("#sql-result-view-cell"); }
        MenuItem menu() { return table.getContextMenu().getItems().stream().filter(i -> "sql-result-view-cell-menu".equals(i.getId())).findFirst().orElseThrow(); }
        ResultCellDialog dialog() { return (ResultCellDialog) field(pane, "resultCellDialog"); }
        SqlScriptDocument document() { return (SqlScriptDocument) field(field(pane, "fileController"), "document"); }
        void assertOffline() {
            assertEquals(0, probe.providers.get()); assertEquals(0, probe.sessions.get());
            assertEquals(0, probe.metadata.get()); assertEquals(0, probe.network.get());
        }
        @Override public void close() throws Exception {
            pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; });
            runner.close(); probe.manager.closeAll();
        }
    }
    private static Object field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner);
    }
}
