package com.datacube.fx;

import com.datacube.service.SchemaObjectCatalog;
import com.datacube.spi.model.TableInfo;
import com.datacube.spi.model.TableRef;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectSearchDialogTest {
    @Test void rejectedSubmissionIsNotStuckLoadingAndCanRetry() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.rejectNext = true; f.picker.reload();
                assertTrue(f.jobs.isEmpty()); assertEquals(0, f.loads);
                assertTrue(f.status().contains("暂不可用"));
                assertEquals("未加载对象", ((Label) f.list().getPlaceholder()).getText());
                assertFalse(f.button("reload").isDisabled()); assertTrue(f.confirm().isDisabled());
                f.button("reload").fire(); f.jobs.getLast().publish();
                assertEquals(4, f.list().getItems().size()); assertEquals(1, f.loads);
            }
            return null;
        });
    }
    @Test void loadingFiltersTypedQueryWithoutAnotherRequestOrImplicitSelection() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                assertTrue(f.jobs.isEmpty(), "construction must not request metadata before showing");
                f.picker.reload();
                assertTrue(f.confirm().isDisabled()); assertTrue(f.button("reload").isDisabled());
                assertFalse(f.query().isDisabled(), "typing during load must keep focus and prepare local filter");
                f.query().setText("REPORT");
                assertEquals(1, f.jobs.size()); assertEquals(0, f.loads);
                f.jobs.getLast().publish();
                assertEquals(1, f.loads); assertEquals("匹配 1 / 4 个对象", f.status());
                assertEquals(List.of(item("report", TableInfo.Kind.VIEW)), List.copyOf(f.list().getItems()));
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertTrue(f.confirm().isDisabled());
                f.query().fireEvent(key(KeyCode.ENTER)); assertNull(f.dialog.getResult());
                f.query().fireEvent(key(KeyCode.DOWN));
                assertEquals(item("report", TableInfo.Kind.VIEW), f.list().getSelectionModel().getSelectedItem());
                assertTrue(f.preview().getText().contains("类型：视图")); assertFalse(f.preview().isEditable());
                f.query().fireEvent(key(KeyCode.ENTER));
                assertEquals(new TableRef("Exact Schema", "report"), f.dialog.getResult());
                assertEquals(1, f.jobs.size()); assertEquals(1, f.loads);
            }
            return null;
        });
    }

    @Test void filteringPreservesExactTypeAndClearsExcludedSelectionInsteadOfReplacingIt() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().select(1); // Same name, different type.
                f.query().setText(" SAME ");
                assertEquals(TableInfo.Kind.VIEW, f.list().getSelectionModel().getSelectedItem().kind());
                assertEquals("匹配 2 / 4 个对象", f.status());
                f.query().setText("report");
                assertNull(f.list().getSelectionModel().getSelectedItem()); assertEquals("", f.preview().getText());
                assertTrue(f.confirm().isDisabled());
                f.button("clear").fire(); assertEquals(4, f.list().getItems().size());
                assertNull(f.list().getSelectionModel().getSelectedItem());
                f.query().setText(".* 中😀"); assertEquals(1, f.list().getItems().size());
                f.query().setText("hidden comment"); assertEquals("匹配 0 / 4 个对象", f.status());
                assertEquals(1, f.loads);
            }
            return null;
        });
    }

    @Test void reloadClearsOldCandidatesAndLateSuccessOrFailureCannotOverwriteNewSnapshot() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().selectFirst();
                f.picker.reload(); Job old = f.jobs.getLast();
                assertTrue(f.list().getItems().isEmpty()); assertTrue(f.confirm().isDisabled()); assertEquals("", f.preview().getText());
                f.picker.reload(); assertTrue(old.future.isCancelled());
                f.jobs.getLast().success.accept(List.of(item("new", TableInfo.Kind.TABLE)));
                old.success.accept(seed()); old.failure.accept(new IllegalStateException("SECRET HOST"));
                assertEquals(List.of(item("new", TableInfo.Kind.TABLE)), List.copyOf(f.list().getItems()));
                assertEquals("匹配 1 / 1 个对象", f.status()); assertNull(f.list().getSelectionModel().getSelectedItem());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failuresAreSafeAndRetryCanRecoverWithoutPartialResults(boolean oversized) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.picker.reload();
                f.jobs.getLast().failure.accept(oversized ? new SchemaObjectCatalog.TooManyObjectsException()
                        : new java.sql.SQLException("SECRET credential and path"));
                assertTrue(f.list().getItems().isEmpty()); assertTrue(f.confirm().isDisabled());
                assertFalse(f.button("reload").isDisabled()); assertFalse(f.status().contains("SECRET"));
                assertTrue(f.status().contains(oversized ? "10000" : "连接与权限"));
                f.button("reload").fire(); f.jobs.getLast().publish();
                assertEquals(4, f.list().getItems().size()); assertEquals("匹配 4 / 4 个对象", f.status());
            }
            return null;
        });
    }

    @Test void emptySchemaAndNoMatchHaveDistinctFeedback() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var empty = new Fixture(List.of()); var f = new Fixture(seed())) {
                empty.load(); f.load(); f.query().setText("absent");
                assertEquals("当前 Schema 没有可见的表/视图", ((Label) empty.list().getPlaceholder()).getText());
                assertTrue(((Label) f.list().getPlaceholder()).getText().contains("没有匹配"));
                assertEquals("匹配 0 / 0 个对象", empty.status()); assertEquals("匹配 0 / 4 个对象", f.status());
                assertTrue(empty.confirm().isDisabled()); assertTrue(f.confirm().isDisabled());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"closed", "source", "disabled"})
    void staleCandidateCannotConfirmOrPublish(String change) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().selectFirst();
                Job pending = f.jobs.getLast();
                switch (change) {
                    case "closed" -> f.picker.close();
                    case "source" -> { f.allowed.set(false); f.picker.sourceChanged(); }
                    default -> f.dialog.getDialogPane().setDisable(true);
                }
                f.confirm().fire(); assertNull(f.dialog.getResult());
                pending.success.accept(List.of(item("late", TableInfo.Kind.TABLE)));
                pending.failure.accept(new IllegalStateException("SECRET"));
                assertFalse(f.status().contains("SECRET"));
                assertFalse(f.list().getItems().stream().anyMatch(i -> i.name().equals("late")));
                f.picker.reload(); assertEquals(1, f.jobs.size());
            }
            return null;
        });
    }

    @Test void closingBeforeQueuedWorkRunsCancelsWithoutCallingLoader() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.picker.reload(); Job queued = f.jobs.getLast(); f.picker.close();
                assertTrue(queued.future.isCancelled());
                assertThrows(java.util.concurrent.CancellationException.class, queued.work::call);
                assertEquals(0, f.loads); assertEquals(1, f.closedScopes);
            }
            return null;
        });
    }

    @Test void queryBoundaryAndCandidateCapAreExplicitWithoutLosingTheSnapshot() throws Exception {
        FxUiTestSupport.call(() -> {
            var names = java.util.stream.IntStream.range(0, 201).mapToObj(i -> item("name_" + i, TableInfo.Kind.TABLE)).toList();
            try (var f = new Fixture(names)) {
                f.load(); assertEquals(200, f.list().getItems().size()); assertTrue(f.status().contains("匹配 201 / 201"));
                assertTrue(f.status().contains("前 200"));
                f.query().setText("name_200"); assertEquals(List.of(names.getLast()), List.copyOf(f.list().getItems()));
                f.query().setText("x".repeat(256)); assertEquals(256, f.query().getLength());
                f.query().setText("y".repeat(257)); assertEquals("x".repeat(256), f.query().getText());
                assertTrue(f.status().contains("超长输入未应用"));
                f.button("clear").fire(); assertEquals(200, f.list().getItems().size()); assertEquals(1, f.loads);
            }
            return null;
        });
    }

    @Test void previewEnterDoesNotConfirmAndKeyboardCancelReturnsNoObject() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().selectFirst();
                f.preview().fireEvent(key(KeyCode.ENTER)); assertNull(f.dialog.getResult());
                f.preview().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, false));
                assertSame(f.query(), f.dialog.getDialogPane().getScene().getFocusOwner());
                f.query().fireEvent(key(KeyCode.ESCAPE)); assertNull(f.dialog.getResult());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings = {"dark", "light"})
    void narrowThemesKeepTargetSearchPreviewAndConfirmationVisible(String theme) throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                DialogPane pane = f.dialog.getDialogPane();
                pane.getStylesheets().addAll(getClass().getResource("theme-base.css").toExternalForm(),
                        getClass().getResource("theme-" + theme + ".css").toExternalForm());
                f.load(); pane.resize(480, 610); f.query().requestFocus(); pane.applyCss(); pane.layout();
                for (String id : new String[]{"target", "query", "clear", "list", "preview", "reload"}) {
                    var node = pane.lookup("#schema-object-" + id);
                    var bounds = node.localToScene(node.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= 480, id + ": " + bounds);
                    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= 610, id + ": " + bounds);
                }
                Text prompt = (Text) f.query().lookup(".text");
                assertEquals(f.query().getPromptText(), prompt.getText());
                assertTrue(((Color) prompt.getFill()).getOpacity() > 0.9);
                assertFalse(((TextArea) pane.lookup("#schema-object-target")).isEditable());
                assertTrue(f.confirm().localToScene(f.confirm().getBoundsInLocal()).getMaxX() <= 480);
            }
            return null;
        });
    }

    private static TableInfo item(String name, TableInfo.Kind kind) { return new TableInfo("Exact Schema", name, kind, null); }
    private static List<TableInfo> seed() { return List.of(item("same", TableInfo.Kind.TABLE), item("same", TableInfo.Kind.VIEW),
            item("report", TableInfo.Kind.VIEW), new TableInfo("Exact Schema", ".* 中😀", TableInfo.Kind.TABLE, "hidden comment")); }
    private static KeyEvent key(KeyCode code) { return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false); }
    private record Job(Callable<List<TableInfo>> work, Consumer<List<TableInfo>> success, Consumer<Throwable> failure,
                       CompletableFuture<Void> future) {
        void publish() throws Exception { success.accept(work.call()); }
    }
    private static final class Fixture implements AutoCloseable {
        final List<Job> jobs = new ArrayList<>();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final SchemaObjectSearchDialog picker;
        final Dialog<TableRef> dialog;
        int loads, closedScopes;
        boolean rejectNext;
        Fixture(List<TableInfo> names) {
            picker = new SchemaObjectSearchDialog("synthetic_" + "long_connection_".repeat(8), "Exact Schema", null,
                    () -> { loads++; return names; }, (work, success, failure) -> {
                        if (rejectNext) { rejectNext = false; throw new java.util.concurrent.RejectedExecutionException(); }
                        Job job = new Job(work, success, failure, new CompletableFuture<>()); jobs.add(job); return job.future;
                    }, () -> closedScopes++, allowed::get);
            dialog = picker.dialog(); dialog.getDialogPane().resize(640, 610);
            dialog.getDialogPane().applyCss(); dialog.getDialogPane().layout();
        }
        void load() throws Exception { picker.reload(); jobs.getLast().publish(); }
        TextField query() { return (TextField) dialog.getDialogPane().lookup("#schema-object-query"); }
        @SuppressWarnings("unchecked") ListView<TableInfo> list() { return (ListView<TableInfo>) dialog.getDialogPane().lookup("#schema-object-list"); }
        TextArea preview() { return (TextArea) dialog.getDialogPane().lookup("#schema-object-preview"); }
        String status() { return ((Label) dialog.getDialogPane().lookup("#schema-object-status")).getText(); }
        Button button(String id) { return (Button) dialog.getDialogPane().lookup("#schema-object-" + id); }
        Button confirm() { return (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst()); }
        @Override public void close() { picker.close(); }
    }
}
