package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.*;
import com.datacube.spi.model.*;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DataGridSaveFlowTest {
    @TempDir Path directory;
    private record DialogRead(String text, boolean cancelDefault) { }
    private static CompletableFuture<DialogRead> answer(boolean approve) {
        return answer(approve, () -> {});
    }
    private static CompletableFuture<DialogRead> answer(boolean approve, Runnable beforeAnswer) {
        var result = new CompletableFuture<DialogRead>();
        Platform.runLater(() -> {
            DialogPane pane = null;
            try {
                pane = Window.getWindows().stream().filter(Window::isShowing).map(w -> w.getScene().getRoot())
                        .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                String text = pane.getContent() instanceof TextArea area ? area.getText() : pane.getContentText();
                var cancel = pane.lookupButton(ButtonType.CANCEL);
                result.complete(new DialogRead(text, cancel instanceof Button b && b.isDefaultButton()));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
            finally {
                if (pane != null) {
                    beforeAnswer.run();
                    ((Button) pane.lookupButton(approve ? pane.getButtonTypes().getFirst() : ButtonType.CANCEL)).fire();
                }
            }
        });
        return result;
    }
    private static Object field(Object p, String name) throws Exception { return DataGridExplicitSaveTest.field(p, name); }
    private static Button button(DataGridPane p, String name) throws Exception { return (Button) field(p, name); }
    private static TableView<EditableGridModel.Row> grid(DataGridPane p) throws Exception { return DataGridExplicitSaveTest.grid(p); }
    private static GridChangeSet changes(DataGridPane p) throws Exception { return (GridChangeSet) field(p, "changes"); }
    private static void stage(DataGridPane p, int row, String name) throws Exception {
        var model = (EditableGridModel) field(p, "model");
        var selected = grid(p).getItems().get(row); selected.cell(1).setText(name); model.reconcile(selected);
        var refresh = DataGridPane.class.getDeclaredMethod("updateChanges"); refresh.setAccessible(true); refresh.invoke(p);
    }
    @SuppressWarnings("unchecked")
    private static TableCell<EditableGridModel.Row, String> editCell(DataGridPane p, int rowIndex, int columnIndex) throws Exception {
        var grid = grid(p); var column = (TableColumn<EditableGridModel.Row, String>) grid.getColumns().get(columnIndex + 1);
        var row = new TableRow<EditableGridModel.Row>(); row.updateTableView(grid); row.updateIndex(rowIndex);
        var cell = column.getCellFactory().call(column);
        cell.updateTableView(grid); cell.updateTableColumn(column); cell.updateTableRow(row); cell.updateIndex(rowIndex);
        cell.startEdit(); assertTrue(cell.isEditing()); assertInstanceOf(TextField.class, cell.getGraphic()); return cell;
    }
    private static CountDownLatch saved(DataGridPane p) throws Exception {
        var ready = new CountDownLatch(1);
        ((Label) field(p, "statusLabel")).textProperty().addListener((o, a, b) -> { if (b.startsWith("本次已提交")) ready.countDown(); });
        return ready;
    }
    private final class Harness implements AutoCloseable {
        final GridSaveProbe probe;
        final FxTaskRunner runner = new FxTaskRunner();
        final List<DataGridPane> panes = new ArrayList<>();
        Harness(String environment) { probe = new GridSaveProbe(DbType.POSTGRESQL, false, environment); }
        DataGridPane open() throws Exception {
            var loaded = new CountDownLatch(1);
            var pane = FxUiTestSupport.call(() -> {
                var p = new DataGridPane(new DataBrowseService(probe.manager), probe.edit, "target", "synthetic",
                        new TableRef("synthetic", "items"), new AppSettings(directory.resolve("settings-" + panes.size())), false, runner);
                grid(p).itemsProperty().addListener((o, a, b) -> loaded.countDown()); return p;
            });
            panes.add(pane); assertTrue(loaded.await(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { assertFalse((boolean) field(pane, "busy")); return null; }); return pane;
        }
        @Override public void close() throws Exception {
            for (var pane : panes) { pane.closeResources(); FxUiTestSupport.call(() -> { pane.finalizeCloseOnFx(); return null; }); }
            runner.close(); probe.close();
        }
    }

    @Test void activeCellDraftCanBePreviewedAndSavedWithoutAnExtraFocusChange() throws Exception {
        try (var h = new Harness("PRODUCTION")) {
            var pane = h.open();
            var done = FxUiTestSupport.call(() -> {
                var cell = editCell(pane, 0, 1); ((TextField) cell.getGraphic()).setText("edited in cell");
                assertFalse(button(pane, "previewBtn").isDisabled(), "active draft must expose explicit preview");
                var preview = answer(true); button(pane, "previewBtn").fire();
                assertTrue(preview.join().text().contains("edited in cell")); assertEquals(0, h.probe.jdbc.executes.get());
                var ready = saved(pane); var confirm = answer(true); button(pane, "saveBtn").fire();
                assertTrue(confirm.join().text().contains("synthetic.invalid:1"));
                assertTrue(confirm.join().text().contains("逐行")); return ready;
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                assertFalse(changes(pane).hasPending()); assertEquals(EditableGridModel.RowState.SAVED, grid(pane).getItems().getFirst().state());
                assertFalse(button(pane, "saveBtn").isVisible() && !button(pane, "saveBtn").isDisabled()); return null;
            });
            assertEquals(1, h.probe.jdbc.executes.get()); assertEquals("edited in cell", h.probe.jdbc.bindings.getFirst().get(1));
        }
    }

    @Test void enteringNullCellWithoutTypingPreservesNullAndEscapeOnlyCancelsActiveDraft() throws Exception {
        try (var h = new Harness("TEST")) {
            var pane = h.open();
            FxUiTestSupport.call(() -> {
                var model = (EditableGridModel) field(pane, "model");
                var set = new GridChangeSet(model, List.of(Arrays.asList(1, null)));
                var f = DataGridPane.class.getDeclaredField("changes"); f.setAccessible(true); f.set(pane, set);
                grid(pane).getItems().setAll(set.rows());
                var cell = editCell(pane, 0, 1); cell.cancelEdit();
                assertTrue(set.rows().getFirst().cell(1).isNull()); assertFalse(set.hasPending());
                cell = editCell(pane, 0, 1); var editor = (TextField) cell.getGraphic(); editor.setText("typed"); editor.setText("");
                editor.fireEvent(new ActionEvent());
                assertFalse(set.rows().getFirst().cell(1).isNull()); assertTrue(set.hasPending());
                cell = editCell(pane, 0, 1); editor = (TextField) cell.getGraphic(); editor.setText("cancel this only");
                editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertEquals("", set.rows().getFirst().cell(1).text()); assertTrue(set.hasPending());
                assertEquals(0, h.probe.jdbc.executes.get()); return null;
            });
        }
    }

    @Test void deletingAndDiscardingRowsNeverWritesAndCanceledSavePreservesChanges() throws Exception {
        try (var h = new Harness("PRODUCTION")) {
            var pane = h.open();
            FxUiTestSupport.call(() -> {
                grid(pane).getSelectionModel().selectFirst(); button(pane, "deleteBtn").fire();
                assertEquals(EditableGridModel.RowState.DELETED, grid(pane).getItems().getFirst().state());
                button(pane, "addBtn").fire(); assertEquals(3, grid(pane).getItems().size());
                button(pane, "deleteBtn").fire(); assertEquals(2, grid(pane).getItems().size());
                var confirm = answer(false); button(pane, "saveBtn").fire(); assertNotNull(confirm.join());
                assertEquals(1, changes(pane).pendingCount()); assertEquals(0, h.probe.jdbc.executes.get());
                grid(pane).getSelectionModel().selectFirst(); var discard = answer(true); button(pane, "discardBtn").fire();
                assertTrue(discard.join().cancelDefault()); assertFalse(changes(pane).hasPending());
                assertEquals(EditableGridModel.RowState.CLEAN, grid(pane).getItems().getFirst().state()); return null;
            });
            assertEquals(0, h.probe.jdbc.executes.get()); assertEquals(0, h.probe.jdbc.commits.get());
        }
    }

    @Test void navigationRefreshFilterAndCloseDefaultToCancelAndPreserveEdits() throws Exception {
        try (var h = new Harness("TEST")) {
            var pane = h.open();
            var nextLoad = FxUiTestSupport.call(() -> {
                stage(pane, 0, "pending");
                var filter = (TextField) field(pane, "filterField"); filter.setText("id > 10");
                for (String action : List.of("nextBtn", "reloadBtn")) {
                    var dialog = answer(false); button(pane, action).fire(); assertTrue(dialog.join().cancelDefault());
                }
                var query = answer(false); filter.fireEvent(new ActionEvent()); assertTrue(query.join().cancelDefault());
                var close = answer(false); assertEquals(CloseGuardOutcome.REJECTED, pane.requestClose().toCompletableFuture().join());
                assertTrue(close.join().cancelDefault()); assertEquals(1, h.probe.reads.get()); assertEquals(0L, field(pane, "offset"));
                assertEquals("pending", grid(pane).getItems().getFirst().cell(1).text());
                var loaded = new CountDownLatch(1); grid(pane).itemsProperty().addListener((o,a,b) -> loaded.countDown());
                var discard = answer(true); button(pane, "nextBtn").fire(); assertTrue(discard.join().cancelDefault()); return loaded;
            });
            assertTrue(nextLoad.await(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { assertFalse(changes(pane).hasPending()); assertEquals(200L, field(pane, "offset")); return null; });
            assertNull(h.probe.filter, "unapplied filter must not silently take effect on page change");
            assertEquals(0, h.probe.jdbc.executes.get());
        }
    }

    @Test void partialFailureRetainsUncommittedEditsAndSubsequentSaveDoesNotReplayCommittedRow() throws Exception {
        try (var h = new Harness("TEST")) {
            var pane = h.open(); h.probe.jdbc.faults.addAll(List.of("", "execute"));
            var done = FxUiTestSupport.call(() -> { stage(pane, 0, "first"); stage(pane, 1, "second-edited");
                var ready = saved(pane); var dialog = answer(true); button(pane, "saveBtn").fire(); dialog.join(); return ready; });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            var retried = FxUiTestSupport.call(() -> {
                assertEquals(1, changes(pane).pendingCount()); assertFalse(grid(pane).getItems().getFirst().editable());
                assertTrue(grid(pane).getItems().getLast().result().contains("已回滚"));
                var ready = saved(pane); var dialog = answer(true); button(pane, "saveBtn").fire(); dialog.join(); return ready;
            });
            assertTrue(retried.await(5, TimeUnit.SECONDS));
            assertEquals(3, h.probe.jdbc.executes.get()); assertEquals(2, h.probe.jdbc.commits.get());
            assertEquals(List.of(1, 2, 2), h.probe.jdbc.bindings.stream().map(m -> m.get(2)).toList());
        }
    }

    @Test void savingRejectsInteractiveCloseAndCancelOnlyStopsRemainingRows() throws Exception {
        try (var h = new Harness("TEST")) {
            var pane = h.open(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            h.probe.jdbc.onExecute = () -> { entered.countDown(); await(release); };
            try {
                var done = FxUiTestSupport.call(() -> { stage(pane, 0, "first"); stage(pane, 1, "second-edited");
                    var ready = saved(pane); var dialog = answer(true); button(pane, "saveBtn").fire(); dialog.join(); return ready; });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertEquals(CloseGuardOutcome.REJECTED, pane.requestClose().toCompletableFuture().join());
                    assertTrue(button(pane, "reloadBtn").isDisabled()); assertTrue(button(pane, "saveBtn").isDisabled());
                    button(pane, "cancelSaveBtn").fire(); return null;
                });
                release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS));
                assertEquals(1, h.probe.jdbc.executes.get()); assertEquals(1, h.probe.jdbc.commits.get());
                FxUiTestSupport.call(() -> { assertEquals(1, changes(pane).pendingCount());
                    assertTrue(grid(pane).getItems().getLast().result().contains("未执行")); return null; });
            } finally { release.countDown(); }
        }
    }

    @Test void safetyTighteningKeepsPendingRowsAndApplicationExitHonorsGridGuard() throws Exception {
        try (var h = new Harness("TEST")) {
            var first = h.open(); var second = h.open();
            var tabs = FxUiTestSupport.call(() -> {
                stage(first, 0, "first-tab"); stage(second, 0, "second-tab");
                var content = new ContentTabPane();
                for (var pane : List.of(first, second)) content.openManagedTab("synthetic", () -> new ContentTabPane.ManagedTabSpec(
                        pane.getNode(), pane::requestClose, pane::requestMandatoryClose, pane::finalizeCloseOnFx, pane::closeResources));
                return content;
            });
            h.probe.tighten();
            var closeResult = FxUiTestSupport.call(() -> {
                assertTrue(button(first, "saveBtn").isDisabled()); assertTrue(button(second, "saveBtn").isDisabled());
                assertFalse(button(first, "discardAllBtn").isDisabled());
                var dialog = answer(false); var secondDialog = answer(false);
                var result = tabs.closeAllManagedTabs(); dialog.join(); secondDialog.join(); return result;
            });
            assertEquals(TabCloseOutcome.CANCELLED, closeResult.toCompletableFuture().get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                assertEquals(2, ((TabPane) tabs.getNode()).getTabs().size());
                assertEquals("first-tab", grid(first).getItems().getFirst().cell(1).text());
                assertEquals("second-tab", grid(second).getItems().getFirst().cell(1).text()); return null;
            });
            assertEquals(0, h.probe.jdbc.executes.get());
        }
    }

    @Test void lateProductionApprovalAfterSafetyChangeCannotSaveThePendingDraft() throws Exception {
        try (var h = new Harness("PRODUCTION")) {
            var pane = h.open();
            FxUiTestSupport.call(() -> {
                stage(pane, 0, "pending");
                var warning = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<DialogRead>>();
                var dialog = answer(true, () -> { h.probe.tighten(); warning.set(answer(true)); });
                button(pane, "saveBtn").fire();
                assertTrue(dialog.join().text().contains("生产"));
                assertTrue(warning.get().join().text().contains("变化或删除"));
                assertEquals(1, changes(pane).pendingCount()); assertTrue(button(pane, "saveBtn").isDisabled());
                assertFalse((boolean) field(pane, "busy")); return null;
            });
            assertEquals(0, h.probe.jdbc.executes.get());
        }
    }

    @Test void mandatoryCloseWaitsForInFlightRowAndSuppressesLatePageCallbacks() throws Exception {
        try (var h = new Harness("TEST")) {
            var pane = h.open();
            var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
            h.probe.jdbc.onExecute = () -> {
                entered.countDown(); boolean wasInterrupted = false;
                while (true) try { await(release); break; }
                catch (RuntimeException failure) {
                    if (!(failure.getCause() instanceof InterruptedException)) throw failure;
                    wasInterrupted = true; Thread.interrupted(); interrupted.countDown();
                }
                if (wasInterrupted) Thread.currentThread().interrupt();
            };
            try {
                FxUiTestSupport.call(() -> {
                    stage(pane, 0, "first"); stage(pane, 1, "second-edited");
                    var confirm = answer(true); button(pane, "saveBtn").fire(); confirm.join(); return null;
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var closing = pane.requestMandatoryClose().toCompletableFuture();
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertFalse(closing.isDone(), "driver has not settled, cleanup cannot approve yet");
                release.countDown(); assertEquals(CloseGuardOutcome.APPROVED, closing.get(5, TimeUnit.SECONDS));
                assertEquals(1, h.probe.jdbc.executes.get()); assertEquals(0, h.probe.jdbc.commits.get());
                assertEquals(1, h.probe.jdbc.rollbacks.get());
                FxUiTestSupport.call(() -> {
                    assertEquals(2, changes(pane).pendingCount());
                    assertFalse(((Label) field(pane, "statusLabel")).getText().startsWith("本次已提交")); return null;
                });
            } finally { release.countDown(); }
        }
    }

    @Test void failedRefreshKeepsCommittedRowsLockedAndFileSaveShortcutDoesNotWrite() throws Exception {
        try (var h = new Harness("TEST")) {
            var pane = h.open();
            var done = FxUiTestSupport.call(() -> {
                stage(pane, 0, "first-edited");
                grid(pane).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.S, false, true, false, false));
                assertEquals(0, h.probe.jdbc.executes.get()); assertFalse((boolean) field(pane, "busy"));
                var ready = saved(pane); var confirm = answer(true); button(pane, "saveBtn").fire(); confirm.join(); return ready;
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            h.probe.failRead = true;
            var errorSeen = new CompletableFuture<DialogRead>();
            FxUiTestSupport.call(() -> {
                ((Label) field(pane, "statusLabel")).textProperty().addListener((o, a, b) -> {
                    if (b.startsWith("错误:")) answer(true).whenComplete((result, failure) -> {
                        if (failure == null) errorSeen.complete(result); else errorSeen.completeExceptionally(failure);
                    });
                });
                button(pane, "reloadBtn").fire(); return null;
            });
            assertTrue(errorSeen.get(5, TimeUnit.SECONDS).text().contains("synthetic read failed"));
            FxUiTestSupport.call(() -> {
                assertFalse(changes(pane).hasPending()); assertFalse(grid(pane).getItems().getFirst().editable());
                assertEquals("first-edited", grid(pane).getItems().getFirst().cell(1).text());
                assertTrue(button(pane, "saveBtn").isDisabled()); return null;
            });
            assertEquals(1, h.probe.jdbc.executes.get()); assertEquals(1, h.probe.jdbc.commits.get());
        }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("controlled JDBC barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
    }
}
