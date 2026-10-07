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

    @Test void pendingReloadIsRejectedAndLateCompletedCallbackCannotOverwriteNewSnapshot() throws Exception {
        FxUiTestSupport.call(() -> {
            try (var f = new Fixture(seed())) {
                f.load(); f.list().getSelectionModel().selectFirst();
                f.picker.reload(); Job old = f.jobs.getLast();
                assertTrue(f.list().getItems().isEmpty()); assertTrue(f.confirm().isDisabled()); assertEquals("", f.preview().getText());
                f.picker.reload(); assertEquals(2,f.jobs.size()); assertFalse(old.future.isCancelled());
                old.publish(); f.picker.reload();
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
                assertTrue(f.picker.disposal().toCompletableFuture().isDone());
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"light,640,false","dark,640,false","light,480,false","dark,480,false",
            "light,640,true","dark,640,true","light,480,true","dark,480,true"})
    void compactStagesKeepLoadedToolsAndResultsReachable(String theme, int width, boolean longTarget) throws Exception {
        var fixture=FxUiTestSupport.call(() -> {
            var f=new Fixture(seed(),longTarget ? "合成长连接".repeat(60) : "synthetic");
            f.picker.installMetadataSearch(() -> {},() -> {});
            f.picker.installCopyAction(ref -> { assertEquals(new TableRef("Exact Schema","same"),ref);
                return new ConnectionTreeClipboard.CopyResult("已复制限定名称；粘贴前请确认目标连接。",true); });
            f.dialog.show(); assertEquals(1,f.jobs.size()); f.jobs.getFirst().publish();
            f.list().getSelectionModel().selectFirst(); f.button("copy").fire();
            var pane=f.dialog.getDialogPane();
            pane.getScene().getStylesheets().setAll(getClass().getResource("theme-base.css").toExternalForm(),
                    getClass().getResource("theme-"+theme+".css").toExternalForm());
            var stage=(javafx.stage.Stage)pane.getScene().getWindow(); stage.setWidth(width); stage.setHeight(480);
            return f;
        });
        try {
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=fixture.dialog.getDialogPane(); pane.applyCss(); pane.layout();
                assertTrue(pane.getScene().getWindow().getWidth()<=width+1); assertTrue(pane.getScene().getWindow().getHeight()<=481);
                for(String id:List.of("target","query","kind","list","preview","reload","copy","metadata-search")) {
                    var node=(Control)pane.lookup("#schema-object-"+id);
                    assertFalse(node.isDisabled()); node.requestFocus(); pane.layout(); assertContentVisible(pane,node);
                }
                var hint=pane.lookupAll(".label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(label -> label.getText().startsWith("读取仅限此 Schema")).findFirst().orElseThrow();
                var scroll=(ScrollPane)pane.lookup("#schema-object-scroll");
                if(scroll!=null) {scroll.setVvalue(1);pane.layout();}
                assertContentVisible(pane,hint); assertContentVisible(pane,pane.lookup("#schema-object-copy-status"));
                var selectedCell=fixture.list().lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
                        .map(ListCell.class::cast).filter(cell -> !cell.isEmpty() && cell.isSelected()).findFirst().orElseThrow();
                var row=selectedCell.localToScene(selectedCell.getBoundsInLocal());
                var results=fixture.list().localToScene(fixture.list().getBoundsInLocal());
                assertTrue(row.getMinY()>=results.getMinY() && row.getMaxY()<=results.getMaxY(),
                        "selected row is clipped: "+row+" results "+results);
                for(String id:List.of("confirm","cancel")) {
                    var bounds=fixture.button(id).localToScene(fixture.button(id).getBoundsInLocal());
                    assertTrue(bounds.getMinX()>=0 && bounds.getMaxX()<=pane.getScene().getWidth()+1
                            && bounds.getMinY()>=0 && bounds.getMaxY()<=pane.getScene().getHeight()+1,id+" outside stage: "+bounds);
                }
                assertEquals(1,fixture.loads); assertEquals(1,fixture.jobs.size()); assertNull(fixture.dialog.getResult());
                return null;
            });
        } finally { FxUiTestSupport.call(() -> {fixture.close();return null;}); }
    }
    @ParameterizedTest @ValueSource(strings={"light","dark"})
    void compactFocusSurvivesFeedbackFilteringResizeAndExplicitKeyboardActions(String theme) throws Exception {
        var copies=new java.util.concurrent.atomic.AtomicInteger(); var metadataOpens=new java.util.concurrent.atomic.AtomicInteger();
        var f=FxUiTestSupport.call(() -> {
            var fixture=new Fixture(seed(),"synthetic");
            fixture.picker.installMetadataSearch(metadataOpens::incrementAndGet,() -> {});
            fixture.picker.installCopyAction(ref -> { assertEquals(new TableRef("Exact Schema","same"),ref);copies.incrementAndGet();
                return new ConnectionTreeClipboard.CopyResult("已复制限定名称；粘贴前请确认目标连接。",true); });
            fixture.dialog.show();fixture.jobs.getFirst().publish();fixture.list().getSelectionModel().selectFirst();
            var pane=fixture.dialog.getDialogPane();
            pane.getScene().getStylesheets().setAll(getClass().getResource("theme-base.css").toExternalForm(),
                    getClass().getResource("theme-"+theme+".css").toExternalForm());
            var stage=(javafx.stage.Stage)pane.getScene().getWindow();stage.setWidth(640);stage.setHeight(480);return fixture;
        });
        try {
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {f.button("copy").requestFocus();f.dialog.getDialogPane().layout();assertContentVisible(f.dialog.getDialogPane(),f.button("copy"));
                f.button("copy").fire();return null;});
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();pane.layout();assertSame(f.button("copy"),pane.getScene().getFocusOwner());
                assertContentVisible(pane,f.button("copy"));assertTrue(pane.lookup("#schema-object-copy-status").isManaged());
                f.query().setText("same");return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();pane.layout();assertSame(f.button("copy"),pane.getScene().getFocusOwner());
                assertContentVisible(pane,f.button("copy"));assertFalse(pane.lookup("#schema-object-copy-status").isManaged());
                f.button("metadata-search").requestFocus();f.button("metadata-search").fire();
                var stage=(javafx.stage.Stage)pane.getScene().getWindow();stage.setWidth(480);stage.setHeight(480);pane.layout();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();pane.layout();assertSame(f.button("metadata-search"),pane.getScene().getFocusOwner());
                assertContentVisible(pane,f.button("metadata-search"));((ScrollPane)pane.lookup("#schema-object-scroll")).setVvalue(0);pane.layout();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();assertEquals(0,((ScrollPane)pane.lookup("#schema-object-scroll")).getVvalue(),"manual scrolling keeps its position");
                f.confirm().requestFocus();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();assertSame(f.confirm(),pane.getScene().getFocusOwner());
                assertEquals(0,((ScrollPane)pane.lookup("#schema-object-scroll")).getVvalue());f.button("cancel").requestFocus();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();assertSame(f.button("cancel"),pane.getScene().getFocusOwner());
                assertEquals(0,((ScrollPane)pane.lookup("#schema-object-scroll")).getVvalue());
                f.query().requestFocus();((ScrollPane)pane.lookup("#schema-object-scroll")).setVvalue(1);pane.layout();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();assertSame(f.query(),pane.getScene().getFocusOwner());
                assertEquals(1,((ScrollPane)pane.lookup("#schema-object-scroll")).getVvalue());
                pane.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.F,false,true,false,false));pane.layout();return null;
            });
            awaitLayoutPulses();
            FxUiTestSupport.call(() -> {
                var pane=f.dialog.getDialogPane();assertSame(f.query(),pane.getScene().getFocusOwner());
                assertContentVisible(pane,f.query());f.query().fireEvent(key(KeyCode.TAB));
                assertSame(f.button("clear"),pane.getScene().getFocusOwner());
                f.query().requestFocus();f.query().fireEvent(key(KeyCode.DOWN));pane.layout();assertSame(f.list(),pane.getScene().getFocusOwner());
                assertContentVisible(pane,f.list());assertEquals(1,f.loads);assertEquals(1,f.jobs.size());
                assertEquals(1,copies.get());assertEquals(1,metadataOpens.get());assertNull(f.dialog.getResult());
                f.list().fireEvent(key(KeyCode.ENTER));assertEquals(new TableRef("Exact Schema","same"),f.dialog.getResult());
                assertFalse(f.dialog.isShowing());assertEquals(1,f.loads);return null;
            });
        } finally {FxUiTestSupport.call(() -> {f.close();return null;});}
    }
    private static void assertContentVisible(DialogPane pane, javafx.scene.Node node) {
        var scroll=(ScrollPane)pane.lookup("#schema-object-scroll");
        var visible=scroll==null ? new javafx.geometry.BoundingBox(0,0,pane.getScene().getWidth(),pane.getScene().getHeight())
                : scroll.lookup(".viewport").localToScene(scroll.lookup(".viewport").getBoundsInLocal());
        var bounds=node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMinX()>=visible.getMinX()-1 && bounds.getMaxX()<=visible.getMaxX()+1
                && bounds.getMinY()>=visible.getMinY()-1 && bounds.getMaxY()<=visible.getMaxY()+1,
                node.getId()+" must be reachable: "+bounds+" visible "+visible);
    }
    private static void awaitLayoutPulses() throws Exception {
        var ready=new java.util.concurrent.CountDownLatch(1);
        FxUiTestSupport.call(() -> {
            new javafx.animation.AnimationTimer() {
                int frames;
                @Override public void handle(long now) {if(++frames>=3){stop();ready.countDown();}}
            }.start(); return null;
        });
        assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS),"FX layout pulses timed out");
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
        Fixture(List<TableInfo> names) { this(names,"synthetic_" + "long_connection_".repeat(8)); }
        Fixture(List<TableInfo> names, String connectionName) {
            picker = new SchemaObjectSearchDialog(connectionName, "Exact Schema", null,
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
