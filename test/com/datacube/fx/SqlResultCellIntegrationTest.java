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

    @Test void rowDetailsCaptureSortedFilteredVisibleProjectionWithoutChangingSourceAndRemainFrozen() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample());
                ((ResultFilterState) field(f.pane, "resultFilterState")).setSearchText("keep");
                invoke(f.pane, "renderResultFilterSnapshot");
                var seq = f.table.getColumns().get(0); var id = f.table.getColumns().get(1);
                var hidden = f.table.getColumns().get(2); var value = f.table.getColumns().get(3);
                hidden.setVisible(false); value.setPrefWidth(183);
                f.table.getColumns().setAll(List.of(seq, value, hidden, id));
                id.setSortType(TableColumn.SortType.DESCENDING); f.table.getSortOrder().setAll(List.of(id)); f.table.sort();
                f.select(0, 0); f.table.getSelectionModel().select(1, value); f.table.getFocusModel().focus(1, value);
                f.editor.selectRange(9, 2);
                var rows = List.copyOf(f.table.getItems()); var selection = List.copyOf(f.table.getSelectionModel().getSelectedCells());
                var snapshot = f.pane.captureResultRowPreview();
                assertEquals(2, snapshot.displayRow()); assertEquals(2, snapshot.sourceRow());
                assertEquals(List.of(3, 1), snapshot.fields().stream().map(com.datacube.sqleditor.result.ResultCellPreview::column).toList());
                assertEquals(List.of("one-B", "1"), snapshot.fields().stream().map(com.datacube.sqleditor.result.ResultCellPreview::text).toList());
                f.rowMenu().fire(); var dialog = f.rowDialog(); assertTrue(dialog.isShowing());
                f.rowMenu().fire(); assertSame(dialog, f.rowDialog());
                assertEquals("one-B", ResultRowDialogTest.text(dialog).getText());
                ResultRowDialogTest.fields(dialog).getSelectionModel().selectLast(); assertEquals("1", ResultRowDialogTest.text(dialog).getText());
                assertEquals(selection, f.table.getSelectionModel().getSelectedCells()); assertSame(value, f.table.getFocusModel().getFocusedCell().getTableColumn());
                assertEquals(1, f.table.getFocusModel().getFocusedCell().getRow());
                assertSame(rows.getFirst(), f.table.getItems().getFirst()); assertSame(rows.getLast(), f.table.getItems().getLast());
                assertEquals(List.of(seq, value, hidden, id), f.table.getColumns()); assertFalse(hidden.isVisible());
                assertEquals(List.of(id), f.table.getSortOrder()); assertEquals(183, value.getPrefWidth());
                assertEquals(9, f.editor.getAnchor()); assertEquals(2, f.editor.getCaretPosition());
                assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                hidden.setVisible(true); f.show(sample()); invoke(f.pane, "clearResultFilterState");
                assertEquals(List.of("one-B", "1"), ResultRowDialogTest.fields(dialog).getItems().stream()
                        .map(com.datacube.sqleditor.result.ResultCellPreview::text).toList());
                ResultRowDialogTest.fields(dialog).getSelectionModel().selectFirst(); assertEquals("one-B", ResultRowDialogTest.text(dialog).getText());
                dialog.close(); assertNull(f.rowDialog());
                f.show(sample()); f.select(2, 1); f.rowMenu().fire(); var latest = f.rowDialog();
                assertEquals("keep", ResultRowDialogTest.text(latest).getText());
                assertEquals(3, ResultRowDialogTest.fields(latest).getItems().size());
                f.pane.finalizeCloseOnFx(); assertFalse(latest.isShowing()); assertNull(f.rowDialog());
                return null;
            });
            assertEquals("select 'offline';", Files.readString(f.file)); f.assertOffline();
        }
    }

    @Test void invalidNonFocusedVisibleFieldRejectsWholeRowInsteadOfShowingPartialData() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(QueryResult.query(List.of("a", "b"), List.of(List.of("only-a")), 0)); f.select(0, 0);
                assertEquals("only-a", f.pane.captureResultCellPreview().text());
                assertNull(f.pane.captureResultRowPreview()); f.rowMenu().fire(); assertNull(f.rowDialog());
                assertEquals("请先选择一个结果数据单元格，再查看其所在行的可见字段。", ((javafx.scene.control.Label) field(f.pane, "statusLabel")).getText());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void resultColumnFindPreservesFilteredSortedRowsAndOnlyExplicitlyRevealsTheChosenColumn() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample());
                ((ResultFilterState) field(f.pane, "resultFilterState")).setSearchText("keep");
                invoke(f.pane, "renderResultFilterSnapshot");
                var seq = f.table.getColumns().get(0); var id = f.table.getColumns().get(1);
                var hidden = f.table.getColumns().get(2); var value = f.table.getColumns().get(3);
                hidden.setVisible(false); value.setPrefWidth(179);
                f.table.getColumns().setAll(List.of(seq, value, id, hidden));
                id.setSortType(TableColumn.SortType.DESCENDING); f.table.getSortOrder().setAll(List.of(id)); f.table.sort();
                invoke(f.pane, "renderResultFilterToolbar");
                f.select(1, 2); f.editor.selectRange(9, 2);
                var rows = List.copyOf(f.table.getItems());
                var selection = List.copyOf(f.table.getSelectionModel().getSelectedCells());
                f.findColumnItem().fire(); var dialog = f.columnFinder(); assertTrue(dialog.isShowing());
                f.findColumnItem().fire(); assertSame(dialog, f.columnFinder());
                ResultColumnFindDialogTest.query(dialog).setText("one-B");
                assertTrue(ResultColumnFindDialogTest.list(dialog).getItems().isEmpty(), "column lookup must not search row values");
                ResultColumnFindDialogTest.query(dialog).setText("VALUE");
                assertEquals(List.of(value, hidden), ResultColumnFindDialogTest.list(dialog).getItems());
                ResultColumnFindDialogTest.list(dialog).getSelectionModel().select(hidden);
                dialog.close(); assertNull(f.columnFinder()); assertFalse(hidden.isVisible());
                f.findColumnItem().fire(); dialog = f.columnFinder();
                ResultColumnFindDialogTest.query(dialog).setText("value");
                ResultColumnFindDialogTest.list(dialog).getSelectionModel().select(hidden);
                ResultColumnFindDialogTest.confirm(dialog).fire();
                assertNull(f.columnFinder()); assertTrue(hidden.isVisible());
                assertEquals("列（3/3）", ((javafx.scene.control.MenuButton) f.pane.getNode().lookup("#sql-result-columns")).getText());
                assertSame(rows.get(0), f.table.getItems().get(0)); assertSame(rows.get(1), f.table.getItems().get(1));
                assertEquals(selection, f.table.getSelectionModel().getSelectedCells());
                assertSame(value, f.table.getFocusModel().getFocusedCell().getTableColumn());
                assertEquals(1, f.table.getFocusModel().getFocusedCell().getRow());
                assertEquals(List.of(seq, value, id, hidden), f.table.getColumns());
                assertEquals(List.of(id), f.table.getSortOrder()); assertEquals(179, value.getPrefWidth());
                assertEquals("one-B", f.pane.captureResultCellPreview().text());
                assertEquals(List.of("value", "id", "value"), f.pane.captureResultExportSnapshot().columns());
                assertEquals(9, f.editor.getAnchor()); assertEquals(2, f.editor.getCaretPosition());
                assertFalse(f.document().dirty()); assertFalse(f.editor.isUndoAvailable());
                return null;
            });
            assertEquals("select 'offline';", Files.readString(f.file)); f.assertOffline();
        }
    }

    @Test void newResultsRejectOldFindMenuAndOpenCandidatesAndFinalizationClosesFinder() throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample()); var oldItem = f.findColumnItem();
                f.show(sample()); oldItem.fire(); assertNull(f.columnFinder());
                f.findColumnItem().fire(); var oldDialog = f.columnFinder();
                f.show(sample()); assertTrue(ResultColumnFindDialogTest.confirm(oldDialog).isDisabled());
                ResultColumnFindDialogTest.confirm(oldDialog).fireEvent(new javafx.event.ActionEvent());
                assertTrue(oldDialog.isShowing()); assertNull(oldDialog.getResult());
                oldDialog.close(); f.findColumnItem().fire(); var latest = f.columnFinder();
                f.pane.finalizeCloseOnFx(); assertFalse(latest.isShowing()); assertNull(f.columnFinder());
                oldItem.fire(); assertNull(f.columnFinder());
                return null;
            });
            f.assertOffline();
        }
    }

    @Test void compactRowsKeepSortedFilteredCellCopyExportAndSqlOnOriginalValues() throws Exception {
        String original = "first\r\nsecond\n" + "中😀".repeat(150);
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                var toggle = (javafx.scene.control.CheckBox) f.pane.getNode().lookup("#sql-result-compact-rows");
                assertTrue(toggle.isDisabled()); assertFalse(toggle.isSelected());
                f.show(QueryResult.query(List.of("id", "value", "value"),
                        List.of(List.of(2, "skip", "skip"), List.of(1, "keep", original), List.of(3, "keep", "other")), 0));
                ((ResultFilterState) field(f.pane, "resultFilterState")).setSearchText("keep");
                invoke(f.pane, "renderResultFilterSnapshot");
                var seq = f.table.getColumns().get(0); var id = f.table.getColumns().get(1);
                var hidden = f.table.getColumns().get(2); var value = f.table.getColumns().get(3);
                hidden.setVisible(false); value.setPrefWidth(173);
                f.table.getColumns().setAll(List.of(seq, value, id, hidden));
                id.setSortType(TableColumn.SortType.DESCENDING); f.table.getSortOrder().setAll(List.of(id)); f.table.sort();
                f.select(1, 2); f.editor.selectRange(9, 2);
                var rows = List.copyOf(f.table.getItems());
                var selection = List.copyOf(f.table.getSelectionModel().getSelectedCells());
                var copied = new java.util.concurrent.atomic.AtomicReference<String>();
                f.pane.setClipboardWriterForTesting(text -> { copied.set(text); return true; });
                for (boolean compact : List.of(true, false, true)) {
                    toggle.fire(); assertEquals(compact, toggle.isSelected());
                    assertSame(rows.get(0), f.table.getItems().get(0)); assertSame(rows.get(1), f.table.getItems().get(1));
                    assertEquals(selection, f.table.getSelectionModel().getSelectedCells());
                    assertEquals(1, f.table.getFocusModel().getFocusedCell().getRow());
                    assertSame(value, f.table.getFocusModel().getFocusedCell().getTableColumn());
                    assertEquals(List.of(id), f.table.getSortOrder()); assertFalse(hidden.isVisible());
                    assertEquals(173, value.getPrefWidth()); assertEquals(List.of(seq, value, id, hidden), f.table.getColumns());
                    var preview = f.pane.captureResultCellPreview();
                    assertEquals(original, preview.text()); assertEquals(2, preview.sourceRow()); assertEquals(3, preview.column());
                    var copy = (javafx.scene.control.MenuButton) f.pane.getNode().lookup("#sql-result-copy");
                    copy.getItems().stream().filter(item -> "当前单元格".equals(item.getText())).findFirst().orElseThrow().fire();
                    assertEquals("\"" + original + "\"", copied.get(), "existing TSV quoting must preserve the whole multiline value");
                    var exported = f.pane.captureResultExportSnapshot();
                    assertEquals(List.of("value", "id"), exported.columns());
                    assertEquals(List.of(original, 1), exported.rows(com.datacube.sqleditor.result.ResultExportScope.CURRENT_FILTERED).get(1));
                    assertEquals(9, f.editor.getAnchor()); assertEquals(2, f.editor.getCaretPosition());
                    assertFalse(f.editor.isUndoAvailable()); assertFalse(f.document().dirty());
                }
                f.show(QueryResult.query(List.of("new"), List.of(List.of("replacement\nvalue")), 0));
                assertTrue(toggle.isSelected(), "same editor keeps its display choice for new results");
                invoke(f.pane, "clearResultFilterState"); assertTrue(toggle.isDisabled()); assertTrue(toggle.isSelected());
                return null;
            });
            assertEquals("select 'offline';", Files.readString(f.file)); f.assertOffline();
        }
    }

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
                ((javafx.scene.control.CheckBox) f.pane.getNode().lookup("#sql-result-compact-rows")).fire();
                f.findColumnItem().fire(); assertNotNull(f.columnFinder()); f.columnFinder().close();
                f.rowMenu().fire(); var rowDialog = f.rowDialog();
                assertEquals("two-B", ResultRowDialogTest.text(rowDialog).getText());
                f.button().fire();
                var text = (TextArea) f.dialog().getDialogPane().lookup("#result-cell-text");
                assertEquals("two-B", text.getText());
                var toolbar = (SqlResultToolbar) field(f.pane, "resultToolbar");
                assertTrue(toolbar.flushPendingSearch(), "viewer must not flush or implicitly change selection");
                assertEquals(2, f.table.getItems().size());
                assertEquals("two-B", text.getText(), "later local filtering cannot retarget the snapshot");
                assertEquals("two-B", ResultRowDialogTest.text(rowDialog).getText()); rowDialog.close();
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
                assertNull(f.pane.captureResultRowPreview()); f.rowMenu().fire(); assertNull(f.rowDialog());
                assertEquals("请先选择一个结果数据单元格，再查看其所在行的可见字段。", ((javafx.scene.control.Label) field(f.pane, "statusLabel")).getText());
                return null;
            });
            f.assertOffline();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"admission", "resources", "tasks", "running", "queue-closed", "disabled", "plan", "finalized", "queue-pending"})
    void closedBusyOrNonTableStatesRejectOldViewActions(String state) throws Exception {
        try (var f = new Fixture()) {
            FxUiTestSupport.call(() -> {
                f.show(sample()); f.select(0, 2);
                var oldFindItem = f.findColumnItem();
                var release = new java.util.concurrent.CountDownLatch(1);
                switch (state) {
                    case "admission" -> ((SqlEditorConnectionAdmission) field(f.pane, "admission")).beginClosing();
                    case "resources" -> ((AtomicBoolean) field(f.pane, "resourcesClosing")).set(true);
                    case "tasks" -> ((FxTaskScope) field(f.pane, "tasks")).close();
                    case "running" -> setField(f.pane, "running", true);
                    case "queue-closed" -> ((SerialSessionOperationQueue) field(f.pane, "sessionOperations")).stopAcceptingAndCancelQueued();
                    case "disabled" -> f.pane.getNode().setDisable(true);
                    case "plan" -> invoke(f.pane, "usePlan");
                    case "finalized" -> f.pane.finalizeCloseOnFx();
                    case "queue-pending" -> ((SerialSessionOperationQueue) field(f.pane, "sessionOperations")).submit(
                            SerialSessionOperationQueue.OperationKind.SET_MODE,
                            () -> { if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("test gate timed out"); return null; },
                            ignored -> { }, error -> fail(error));
                    default -> throw new AssertionError(state);
                }
                try {
                    assertNull(f.pane.captureResultCellPreview());
                    assertNull(f.pane.captureResultRowPreview()); f.rowMenu().fire(); assertNull(f.rowDialog());
                    f.menu().getOnAction().handle(new javafx.event.ActionEvent());
                    f.button().getOnAction().handle(new javafx.event.ActionEvent());
                    var compact = (javafx.scene.control.CheckBox) f.pane.getNode().lookup("#sql-result-compact-rows");
                    compact.setSelected(true); compact.getOnAction().handle(new javafx.event.ActionEvent());
                    assertFalse(compact.isSelected(), "stale display action must restore the actual mode");
                    oldFindItem.fire(); assertNull(f.columnFinder());
                    assertNull(f.dialog());
                    assertFalse(f.document().dirty());
                } finally {
                    release.countDown();
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
        MenuItem rowMenu() { return table.getContextMenu().getItems().stream().filter(i -> "sql-result-view-row-menu".equals(i.getId())).findFirst().orElseThrow(); }
        ResultRowDialog rowDialog() { return (ResultRowDialog) field(pane, "resultRowDialog"); }
        MenuItem findColumnItem() {
            var columns = (javafx.scene.control.MenuButton) pane.getNode().lookup("#sql-result-columns");
            return columns.getItems().stream().filter(item -> "sql-result-columns-find".equals(item.getId())).findFirst().orElseThrow();
        }
        ResultColumnFindDialog columnFinder() { return (ResultColumnFindDialog) field(field(pane, "resultColumnMenu"), "finder"); }
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
