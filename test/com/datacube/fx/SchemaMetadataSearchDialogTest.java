package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.SchemaMetadataSearch.*;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMetadataSearchDialogTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"read,false", "driver,false", "read,true", "driver,true"})
    void disposalWaitsForBothPhysicalTasksEvenWhenCancellationFails(String first, boolean cancelFails) throws Exception {
        try (var f=new CancelFixture(cancelFails)) {
            var disposed=f.view.disposal().toCompletableFuture();
            var completedOnFx=new AtomicBoolean();
            f.view.disposal().thenRun(() -> completedOnFx.set(javafx.application.Platform.isFxApplicationThread()));
            FxUiTestSupport.call(() -> { f.view.close(); assertFalse(f.view.dialog().isShowing()); return null; });
            assertTrue(f.cancelStarted.await(5,TimeUnit.SECONDS));
            assertFalse(disposed.isDone(), "hiding must return before physical work finishes");
            f.complete(first);
            assertFalse(disposed.isDone(), "one unfinished task still owns the closed search");
            f.complete(first.equals("read") ? "driver" : "read");
            assertTrue(disposed.isDone()); assertTrue(completedOnFx.get());
            FxUiTestSupport.call(() -> {
                assertTrue(list(f.view).getItems().isEmpty()); assertNull(f.view.dialog().getResult());
                button(f.view,"submit").fire(); assertEquals(1,f.calls.get()); return null;
            });
        }
    }

    @Test void idleCloseDisposesImmediatelyOnFxWithoutStartingWork() throws Exception {
        try (var runner=new FxTaskRunner()) {
            var calls=new AtomicInteger();
            FxUiTestSupport.call(() -> {
                var d=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> { calls.incrementAndGet(); return result(); },() -> true);
                var disposed=d.disposal().toCompletableFuture();
                assertFalse(disposed.isDone()); d.dialog().show(); d.close(); d.close();
                assertTrue(disposed.isDone()); assertFalse(d.dialog().isShowing()); assertEquals(0,calls.get()); return null;
            });
        }
    }

    @Test void rejectedCancellationRetainsOwnershipUntilReadReturns() throws Exception {
        try (var f=new CancelFixture(false)) {
            f.rejectTasks.set(true);
            var disposed=f.view.disposal().toCompletableFuture();
            FxUiTestSupport.call(() -> { f.view.close(); assertFalse(f.view.dialog().isShowing()); return null; });
            assertFalse(disposed.isDone()); assertEquals(1,f.cancelStarted.getCount(), "rejected cancel must not run");
            f.complete("read"); assertTrue(disposed.isDone()); assertEquals(1,f.calls.get());
            FxUiTestSupport.call(() -> { assertTrue(list(f.view).getItems().isEmpty()); return null; });
        }
    }

    @Test void fatalReadFailureStillReleasesClosedSearchWithoutSwallowingError() throws Exception {
        try (var f=new CancelFixture(false,true)) {
            var disposed=f.view.disposal().toCompletableFuture();
            FxUiTestSupport.call(() -> { f.view.close(); return null; });
            assertTrue(f.cancelStarted.await(5,TimeUnit.SECONDS));
            f.complete("driver"); assertFalse(disposed.isDone());
            f.readRelease.countDown(); assertTrue(f.completed.tryAcquire(5,TimeUnit.SECONDS));
            var failure=assertInstanceOf(ExecutionException.class,f.taskFailures.remove());
            assertInstanceOf(AssertionError.class,failure.getCause()); assertTrue(f.taskFailures.isEmpty());
            FxUiTestSupport.call(() -> {
                assertTrue(disposed.isDone()); assertTrue(list(f.view).getItems().isEmpty());
                assertNull(f.view.dialog().getResult()); return null;
            });
        }
    }

    @Test void backgroundIdleCloseDisposesOnlyAfterFxHidesWindow() throws Exception {
        try (var runner=new FxTaskRunner()) {
            var calls=new AtomicInteger(); var blocked=new CountDownLatch(1); var release=new CountDownLatch(1);
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> { calls.incrementAndGet(); return result(); },() -> true);
                view.dialog().show(); return view;
            });
            var disposed=d.disposal().toCompletableFuture();
            try {
                javafx.application.Platform.runLater(() -> {
                    blocked.countDown();
                    try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                });
                assertTrue(blocked.await(5,TimeUnit.SECONDS));
                d.close(); assertFalse(disposed.isDone(), "close publication alone cannot release the FX reservation");
            } finally { release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
            FxUiTestSupport.call(() -> {
                assertTrue(disposed.isDone()); assertFalse(d.dialog().isShowing()); assertEquals(0,calls.get()); return null;
            });
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "cancel,read,false", "cancel,driver,false", "timeout,read,true", "timeout,driver,false",
            "change,read,false", "change,driver,true", "clear,read,false", "clear,driver,false"})
    void cancelledReadBecomesRetryableOnlyAfterBothTasksFinish(String cause, String first, boolean cancelFails) throws Exception {
        try (var f = new CancelFixture(cancelFails)) {
            f.abandon(cause);
            f.complete(first);
            FxUiTestSupport.call(() -> {
                assertTrue(button(f.view,"submit").isDisabled(), "one unfinished task must still block new reads");
                assertTrue(status(f.view).contains("等待"));
                button(f.view,"submit").fire(); assertEquals(1,f.calls.get());
                assertTrue(list(f.view).getItems().isEmpty()); return null;
            });
            f.complete(first.equals("read") ? "driver" : "read");
            FxUiTestSupport.call(() -> {
                assertTrue(status(f.view).contains("读取已结束"), status(f.view));
                assertFalse(status(f.view).contains("等待"), status(f.view));
                assertTrue(status(f.view).contains(switch(cause) {
                    case "cancel" -> "已取消"; case "timeout" -> "超时"; default -> "旧结果已失效";
                }), status(f.view));
                assertEquals(cause.equals("clear"), button(f.view,"submit").isDisabled());
                assertTrue(button(f.view,"cancel-read").isDisabled());
                assertTrue(list(f.view).getItems().isEmpty()); assertTrue(button(f.view,"data").isDisabled());
                assertEquals("", ((TextArea)f.view.dialog().getDialogPane().lookup("#metadata-search-preview")).getText());
                assertNull(f.view.dialog().getResult());
                text(f.view).setText("retry term");
                @SuppressWarnings("unchecked") var modes=(ChoiceBox<Mode>)f.view.dialog().getDialogPane().lookup("#metadata-search-mode");
                modes.setValue(Mode.COLUMN_COMMENT);
                assertEquals(1,f.calls.get(), "condition changes never automatically retry");
                button(f.view,"submit").fire(); return null;
            });
            f.awaitTask();
            FxUiTestSupport.call(() -> {
                assertEquals(2,f.calls.get());
                Request request=f.requests.getLast();
                assertEquals(target(),request.connection()); assertEquals("s",request.schema());
                assertEquals("retry term",request.term()); assertEquals(Mode.COLUMN_COMMENT,request.mode());
                assertEquals("retry term",list(f.view).getItems().getFirst().excerpt());
                assertTrue(status(f.view).startsWith("已读取 1 条匹配"), "late cancellation must not overwrite retry results");
                list(f.view).getSelectionModel().selectFirst();
                assertFalse(button(f.view,"select").isDisabled()); return null;
            });
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void closedOrInvalidatedSearchDoesNotPublishCancellationCompletion(boolean close) throws Exception {
        try (var f=new CancelFixture(false)) {
            f.abandon("cancel");
            String before=FxUiTestSupport.call(() -> {
                if(close) f.view.close(); else f.allowed.set(false);
                return status(f.view);
            });
            f.complete("driver"); f.complete("read");
            FxUiTestSupport.call(() -> {
                assertEquals(before,status(f.view)); assertTrue(list(f.view).getItems().isEmpty());
                assertTrue(button(f.view,"submit").isDisabled()); assertTrue(button(f.view,"select").isDisabled());
                button(f.view,"submit").fire(); assertEquals(1,f.calls.get()); assertNull(f.view.dialog().getResult());
                if(close) assertFalse(f.view.dialog().isShowing()); return null;
            });
            assertEquals(close, f.view.disposal().toCompletableFuture().isDone());
        }
    }

    private static String status(SchemaMetadataSearchDialog d) {
        return ((Label)d.dialog().getDialogPane().lookup("#metadata-search-status")).getText();
    }

    /** Task completion means its FX callback is already enqueued; no sleeps or timing guesses. */
    private final class CancelFixture implements AutoCloseable {
        final Semaphore completed=new Semaphore(0);
        final Queue<Throwable> taskFailures=new ConcurrentLinkedQueue<>();
        final CountDownLatch started=new CountDownLatch(1), cancelStarted=new CountDownLatch(1),
                readRelease=new CountDownLatch(1), cancelRelease=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
        final AtomicBoolean allowed=new AtomicBoolean(true);
        final AtomicBoolean rejectTasks=new AtomicBoolean();
        final List<Request> requests=new CopyOnWriteArrayList<>();
        final FxTaskRunner runner;
        final SchemaMetadataSearchDialog view;
        CancelFixture(boolean cancelFails) throws Exception { this(cancelFails,false); }
        CancelFixture(boolean cancelFails, boolean readFails) throws Exception {
            var executor=new ThreadPoolExecutor(0,Integer.MAX_VALUE,1,TimeUnit.SECONDS,
                    new SynchronousQueue<>(),Thread.ofVirtual().factory()) {
                @Override public void execute(Runnable task) {
                    if (rejectTasks.get()) throw new RejectedExecutionException("synthetic closed runner");
                    super.execute(task);
                }
                @Override protected void afterExecute(Runnable task,Throwable error) {
                    try { ((Future<?>)task).get(); }
                    catch (Exception failure) { taskFailures.add(failure); }
                    finally { completed.release(); }
                }
            };
            var constructor=FxTaskRunner.class.getDeclaredConstructor(ExecutorService.class,java.time.Duration.class);
            constructor.setAccessible(true); runner=constructor.newInstance(executor,java.time.Duration.ofSeconds(1));
            view=FxUiTestSupport.call(() -> {
                var d=new SchemaMetadataSearchDialog(target(),"s",null,runner,(request,control) -> {
                    requests.add(request);
                    if(calls.incrementAndGet()>1) return new Result(List.of(new Hit(
                            new TableInfo("s","retry_table",TableInfo.Kind.TABLE,null),request.mode(),"note",request.term())),false);
                    var statement=(java.sql.Statement)java.lang.reflect.Proxy.newProxyInstance(
                            getClass().getClassLoader(),new Class<?>[]{java.sql.Statement.class},(proxy,method,args) -> {
                                if(method.getName().equals("cancel")) {
                                    cancelStarted.countDown(); assertTrue(cancelRelease.await(5,TimeUnit.SECONDS));
                                    if(cancelFails) throw new java.sql.SQLException("synthetic cancellation failure");
                                }
                                return null;
                            });
                    var activation=control.activate(statement,0); started.countDown();
                    try {
                        assertTrue(readRelease.await(5,TimeUnit.SECONDS));
                        if (readFails) throw new AssertionError("synthetic fatal read failure");
                        return result();
                    }
                    finally { control.release(activation); }
                },allowed::get);
                d.dialog().show(); text(d).setText("customer"); button(d,"submit").fire(); return d;
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
        }
        void abandon(String cause) throws Exception {
            FxUiTestSupport.call(() -> {
                switch(cause) {
                    case "cancel" -> button(view,"cancel-read").fire();
                    case "change" -> text(view).setText("changed term");
                    case "clear" -> text(view).clear();
                    default -> {
                        var field=SchemaMetadataSearchDialog.class.getDeclaredField("deadline"); field.setAccessible(true);
                        ((javafx.animation.PauseTransition)field.get(view)).getOnFinished().handle(new javafx.event.ActionEvent());
                    }
                }
                return null;
            });
            assertTrue(cancelStarted.await(5,TimeUnit.SECONDS));
        }
        void complete(String task) throws Exception {
            if(task.equals("read")) readRelease.countDown(); else cancelRelease.countDown();
            awaitTask();
        }
        void awaitTask() throws Exception {
            assertTrue(completed.tryAcquire(5,TimeUnit.SECONDS));
            assertTrue(taskFailures.isEmpty(), () -> "Background task failures: " + taskFailures);
            FxUiTestSupport.call(() -> null);
        }
        @Override public void close() throws Exception {
            readRelease.countDown(); cancelRelease.countDown();
            FxUiTestSupport.call(() -> { view.close(); return null; }); runner.close();
        }
    }

    @Test void emptyQueryGuidanceRemainsVisibleInBothThemesAndFocusStatesWithoutReading() throws Exception {
        try (var runner = new FxTaskRunner()) {
            var calls = new AtomicInteger();
            var d = FxUiTestSupport.call(() -> {
                var picker = new SchemaMetadataSearchDialog(target(), "s", null, runner,
                        (r,c) -> { calls.incrementAndGet(); return result(); }, () -> true);
                picker.dialog().show(); return picker;
            });
            try {
                FxUiTestSupport.call(() -> {
                    var root = d.dialog().getDialogPane(); var input = text(d);
                    for (String theme : List.of("dark", "light")) {
                        root.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                                ThemeManager.class.getResource("theme-" + theme + ".css").toExternalForm());
                        for (boolean focused : List.of(false, true)) {
                            input.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), focused);
                            root.applyCss(); root.layout();
                            var prompt = input.lookupAll(".text").stream().filter(javafx.scene.text.Text.class::isInstance)
                                    .map(javafx.scene.text.Text.class::cast).filter(t -> input.getPromptText().equals(t.getText())).findFirst().orElseThrow();
                            assertTrue(prompt.isVisible());
                            assertEquals(javafx.scene.paint.Color.web(theme.equals("dark") ? "#A8A8B8" : "#555555"), prompt.getFill());
                        }
                    }
                    assertEquals(0, calls.get(), "theme and focus changes must not start metadata reads");
                    return null;
                });
            } finally { FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"light,false","dark,false","light,true","dark,true"})
    void compactWindowKeepsControlsReachableWithLongTargetAndStatus(String theme, boolean longText) throws Exception {
        try (var runner=new FxTaskRunner()) {
            var loaded=new CountDownLatch(1); var calls=new AtomicInteger();
            var d=FxUiTestSupport.call(() -> {
                var target=longText ? new ConnConfig("synthetic", "长连接名称".repeat(80), DbType.POSTGRESQL,"invalid.example",1,"db","u","",Map.of()) : target();
                var view=new SchemaMetadataSearchDialog(target,"s",null,runner,(r,c) -> {calls.incrementAndGet(); return result();},() -> true);
                view.dialog().show(); listen(view,loaded); text(view).setText("customer"); button(view,"submit").fire();
                var pane=view.dialog().getDialogPane();
                pane.getScene().getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),
                        ThemeManager.class.getResource("theme-"+theme+".css").toExternalForm());
                var stage=(javafx.stage.Stage)pane.getScene().getWindow(); stage.setWidth(640); stage.setHeight(480);
                return view;
            });
            try {
                assertTrue(loaded.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    var pane=d.dialog().getDialogPane();
                    if(longText) ((Label)pane.lookup("#metadata-search-status")).setText("较长状态说明".repeat(40));
                    list(d).getSelectionModel().selectFirst(); pane.applyCss(); pane.layout();
                    assertTrue(pane.getScene().getWindow().getWidth()<=641); assertTrue(pane.getScene().getWindow().getHeight()<=481);
                    for(String id:List.of("query","mode","submit","results","preview","select","data","ddl")) {
                        var control=(Control)pane.lookup("#metadata-search-"+id);
                        assertFalse(control.isDisabled()); control.requestFocus(); pane.layout();
                        assertVisible(d,control);
                    }
                    assertTrue(list(d).getHeight()>=120,"results retain useful height");
                    pane.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.F,false,true,false,false));
                    pane.layout(); assertSame(text(d),pane.getScene().getFocusOwner()); assertVisible(d,text(d));
                    var scroll=(ScrollPane)pane.lookup("#metadata-search-scroll"); scroll.setVvalue(1); pane.layout();
                    assertSame(text(d),pane.getScene().getFocusOwner());
                    pane.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.F,false,true,false,false));
                    pane.layout(); assertVisible(d,text(d));
                    assertEquals(1,calls.get(),"focus and Ctrl+F never read or execute"); assertNull(d.dialog().getResult());
                    var close=pane.lookupButton(ButtonType.CANCEL);
                    var closeBounds=close.localToScene(close.getBoundsInLocal());
                    assertTrue(closeBounds.getMaxY()<=pane.getScene().getHeight()+1,"dialog cancel stays in window");
                    return null;
                });
            } finally { FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @Test void resizingKeepsExistingActionFocusVisibleAndCancelOutsideScroll() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var loaded=new CountDownLatch(1);
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> result(),() -> true);
                view.dialog().show(); listen(view,loaded); text(view).setText("customer"); button(view,"submit").fire(); return view;
            });
            try {
                assertTrue(loaded.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    list(d).getSelectionModel().selectFirst(); button(d,"ddl").requestFocus();
                    var pane=d.dialog().getDialogPane(); var stage=(javafx.stage.Stage)pane.getScene().getWindow();
                    stage.setWidth(640); stage.setHeight(480); pane.applyCss(); pane.layout(); return null;
                });
                awaitLayoutPulses();
                FxUiTestSupport.call(() -> {
                    var pane=d.dialog().getDialogPane(); pane.layout(); assertSame(button(d,"ddl"),pane.getScene().getFocusOwner());
                    assertVisible(d,button(d,"ddl"));
                    var scroll=(ScrollPane)pane.lookup("#metadata-search-scroll"); scroll.setVvalue(0); pane.layout();
                    return null;
                });
                awaitLayoutPulses();
                FxUiTestSupport.call(() -> {
                    var pane=d.dialog().getDialogPane(); var scroll=(ScrollPane)pane.lookup("#metadata-search-scroll");
                    assertEquals(0,scroll.getVvalue(),"manual scrolling must not be pulled back to focused action");
                    pane.lookupButton(ButtonType.CANCEL).requestFocus();
                    return null;
                });
                awaitLayoutPulses();
                FxUiTestSupport.call(() -> {
                    var pane=d.dialog().getDialogPane();
                    assertSame(pane.lookupButton(ButtonType.CANCEL),pane.getScene().getFocusOwner());
                    assertEquals(0,((ScrollPane)pane.lookup("#metadata-search-scroll")).getVvalue()); return null;
                });
            } finally { FxUiTestSupport.call(() -> {d.close();return null;}); }
        }
    }
    @Test void enterInQueryOnlyExplicitlySearchesAndNeverChoosesAnAction() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var loaded=new CountDownLatch(1); var calls=new AtomicInteger();
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> {calls.incrementAndGet();return result();},() -> true);
                view.dialog().show(); listen(view,loaded); text(view).setText("customer"); assertEquals(0,calls.get());
                text(view).fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.ENTER,false,false,false,false));
                text(view).fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_RELEASED,"","",javafx.scene.input.KeyCode.ENTER,false,false,false,false));
                return view;
            });
            try { assertTrue(loaded.await(5,TimeUnit.SECONDS)); FxUiTestSupport.call(() -> {
                assertEquals(1,calls.get()); assertNull(d.dialog().getResult()); assertTrue(d.dialog().isShowing()); return null;
            }); } finally { FxUiTestSupport.call(() -> {d.close();return null;}); }
        }
    }
    private static void awaitLayoutPulses() throws Exception {
        var ready=new CountDownLatch(1);
        FxUiTestSupport.call(() -> {
            new javafx.animation.AnimationTimer() {
                int frames;
                @Override public void handle(long now) { if(++frames>=3) {stop();ready.countDown();} }
            }.start(); return null;
        });
        assertTrue(ready.await(5,TimeUnit.SECONDS),"layout pulses timed out");
    }
    private static void assertVisible(SchemaMetadataSearchDialog d, javafx.scene.Node control) {
        var pane=d.dialog().getDialogPane(); var scroll=(ScrollPane)pane.lookup("#metadata-search-scroll");
        var viewport=scroll==null ? pane : scroll.lookup(".viewport");
        var visible=scroll==null ? new javafx.geometry.BoundingBox(0,0,pane.getScene().getWidth(),pane.getScene().getHeight())
                : viewport.localToScene(viewport.getBoundsInLocal());
        var bounds=control.localToScene(control.getBoundsInLocal());
        assertTrue(bounds.getMinX()>=visible.getMinX()-1 && bounds.getMaxX()<=visible.getMaxX()+1
                && bounds.getMinY()>=visible.getMinY()-1 && bounds.getMaxY()<=visible.getMaxY()+1,
                control.getId()+" must be visible: "+bounds+" viewport "+visible);
    }
    private ConnConfig target() { return new ConnConfig("synthetic", "synthetic", DbType.POSTGRESQL,"invalid.example",1,"db","u","",Map.of()); }
    private Result result() { return new Result(List.of(new Hit(new TableInfo("s","orders",TableInfo.Kind.TABLE,null),Mode.COLUMN_NAME,"customer_id","customer_id")),false); }
    @Test void typingNeverReadsAndEachExplicitResultActionKeepsIdentity() throws Exception {
        for (var action:SchemaMetadataSearchDialog.Action.values()) try (var runner=new FxTaskRunner()) {
            var calls=new AtomicInteger(); var loaded=new CountDownLatch(1);
            var picker=FxUiTestSupport.call(() -> {
                var d=new SchemaMetadataSearchDialog(target(),"s",null,runner,(request,control) -> {
                    calls.incrementAndGet(); assertEquals("customer",request.term()); assertEquals("s",request.schema()); return result();
                },() -> true);
                d.dialog().show(); listen(d,loaded); text(d).setText("customer");
                assertEquals(0,calls.get()); assertTrue(button(d,"select").isDisabled()); button(d,"submit").fire(); return d;
            });
            try {
                assertTrue(loaded.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { list(picker).getSelectionModel().selectFirst(); button(picker,action.name().toLowerCase(Locale.ROOT)).fire(); return null; });
                assertEquals(action,picker.dialog().getResult().action());
                assertEquals(new TableRef("s","orders"),picker.dialog().getResult().hit().object().ref()); assertEquals(1,calls.get());
            } finally { FxUiTestSupport.call(() -> { picker.close(); return null; }); }
        }
    }
    @Test void changedConditionsInvalidateResultsAndChangedTargetBlocksStaleActions() throws Exception {
        try (var runner=new FxTaskRunner()) {
            var allowed=new AtomicBoolean(true); var loaded=new CountDownLatch(1);
            var d=FxUiTestSupport.call(() -> {
                var picker=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> result(),allowed::get);
                picker.dialog().show(); listen(picker,loaded); text(picker).setText("customer"); button(picker,"submit").fire(); return picker;
            });
            try {
                assertTrue(loaded.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    list(d).getSelectionModel().selectFirst(); allowed.set(false); button(d,"data").fire(); assertNull(d.dialog().getResult());
                    allowed.set(true); text(d).setText("new term"); assertTrue(list(d).getItems().isEmpty()); assertTrue(button(d,"select").isDisabled());
                    assertNull(d.dialog().getResult()); return null;
                });
            } finally { FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @Test void cancellationWaitsForPhysicalCompletionAndCloseDropsLateResults() throws Exception {
        try (var runner=new FxTaskRunner()) {
            var started=new CountDownLatch(1); var release=new CountDownLatch(1); var ended=new CountDownLatch(1);
            var control=new AtomicReference<SqlExecutionControl>(); var calls=new AtomicInteger();
            var d=FxUiTestSupport.call(() -> {
                var picker=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> {
                    calls.incrementAndGet(); control.set(c); started.countDown();
                    try { assertTrue(release.await(5,TimeUnit.SECONDS)); return result(); } finally { ended.countDown(); }
                },() -> true);
                picker.dialog().show(); text(picker).setText("customer"); button(picker,"submit").fire(); return picker;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    button(d,"cancel-read").fire(); assertTrue(control.get().cancellationRequested());
                    assertTrue(button(d,"submit").isDisabled()); button(d,"submit").fire(); assertEquals(1,calls.get()); d.close(); return null;
                });
                release.countDown(); assertTrue(ended.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { assertTrue(list(d).getItems().isEmpty()); assertNull(d.dialog().getResult()); return null; });
            } finally { release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @Test void findShortcutStaysInCatalogDialogAndTabReachesMatchSourceWithoutReading() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var calls=new AtomicInteger();
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> { calls.incrementAndGet(); return result(); },() -> true);
                view.dialog().show(); return view;
            });
            try { FxUiTestSupport.call(() -> {
                button(d,"submit").requestFocus();
                d.dialog().getDialogPane().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "","",javafx.scene.input.KeyCode.F,false,true,false,false));
                assertSame(text(d),text(d).getScene().getFocusOwner());
                text(d).fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "","",javafx.scene.input.KeyCode.TAB,false,false,false,false));
                assertSame(d.dialog().getDialogPane().lookup("#metadata-search-mode"),text(d).getScene().getFocusOwner());
                assertEquals(0,calls.get()); return null;
            }); } finally { FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    private static TextField text(SchemaMetadataSearchDialog d) { return (TextField)d.dialog().getDialogPane().lookup("#metadata-search-query"); }
    @Test void deadlineKeepsAdmissionClosedUntilReadReallyEndsAndDropsLateData() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var started=new CountDownLatch(1); var release=new CountDownLatch(1); var ready=new CountDownLatch(1);
            var control=new AtomicReference<SqlExecutionControl>();
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> {
                    control.set(c); started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return result();
                },() -> true);
                view.dialog().show(); text(view).setText("id"); button(view,"submit").fire(); return view;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    var field=SchemaMetadataSearchDialog.class.getDeclaredField("deadline"); field.setAccessible(true);
                    ((javafx.animation.PauseTransition)field.get(d)).getOnFinished().handle(new javafx.event.ActionEvent());
                    assertTrue(control.get().cancellationRequested()); assertTrue(button(d,"submit").isDisabled());
                    button(d,"submit").disabledProperty().addListener((o,b,a) -> { if(!a) ready.countDown(); });
                    return null;
                });
                release.countDown(); assertTrue(ready.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { assertTrue(list(d).getItems().isEmpty()); assertTrue(button(d,"data").isDisabled()); return null; });
            } finally { release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    @Test void backgroundClosePublishesCancellationWithoutWaitingForTheFxQueue() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var started=new CountDownLatch(1); var release=new CountDownLatch(1); var fxBlocked=new CountDownLatch(1); var fxRelease=new CountDownLatch(1);
            var control=new AtomicReference<SqlExecutionControl>();
            var d=FxUiTestSupport.call(() -> {
                var view=new SchemaMetadataSearchDialog(target(),"s",null,runner,(r,c) -> {
                    control.set(c); started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return result();
                },() -> true);
                view.dialog().show(); text(view).setText("id"); button(view,"submit").fire(); return view;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                javafx.application.Platform.runLater(() -> { fxBlocked.countDown(); try { assertTrue(fxRelease.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); } });
                assertTrue(fxBlocked.await(5,TimeUnit.SECONDS));
                d.close(); assertTrue(control.get().cancellationRequested());
            } finally { fxRelease.countDown(); release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
        }
    }
    private static Button button(SchemaMetadataSearchDialog d,String suffix) { return (Button)d.dialog().getDialogPane().lookup("#metadata-search-"+suffix); }
    @SuppressWarnings("unchecked") private static ListView<Hit> list(SchemaMetadataSearchDialog d) { return (ListView<Hit>)d.dialog().getDialogPane().lookup("#metadata-search-results"); }
    private static void listen(SchemaMetadataSearchDialog d,CountDownLatch ready) { list(d).getItems().addListener((ListChangeListener<Hit>)c -> { if (!list(d).getItems().isEmpty()) ready.countDown(); }); }
}
