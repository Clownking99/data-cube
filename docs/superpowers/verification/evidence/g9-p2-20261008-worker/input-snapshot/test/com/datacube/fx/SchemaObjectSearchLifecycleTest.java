package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.spi.model.TableInfo;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.ListView;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaObjectSearchLifecycleTest {
    @Test void closeDuringSubmissionCancelsQueuedFutureAndPreventsItsLoaderFromStarting() throws Exception {
        FxUiTestSupport.call(() -> {
            var holder=new AtomicReference<SchemaObjectSearchDialog>();
            var work=new AtomicReference<java.util.concurrent.Callable<List<TableInfo>>>();
            var queued=new CompletableFuture<Void>(); var calls=new AtomicInteger();
            var d=new SchemaObjectSearchDialog("synthetic","s",null,() -> { calls.incrementAndGet(); return List.of(); },
                    (task,success,failure) -> { work.set(task); holder.get().close(); return queued; },() -> {},() -> true);
            holder.set(d); d.reload();
            assertTrue(queued.isCancelled()); assertTrue(d.disposal().toCompletableFuture().isDone());
            assertThrows(java.util.concurrent.CancellationException.class,() -> work.get().call());
            assertEquals(0,calls.get()); return null;
        });
    }

    @Test void backgroundIdleCloseWaitsForFxCleanupWithoutReading() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var calls=new AtomicInteger(); var blocked=new CountDownLatch(1); var release=new CountDownLatch(1);
            var d=FxUiTestSupport.call(() -> SchemaObjectSearchDialog.create("synthetic","s",null,
                    () -> { calls.incrementAndGet(); return List.of(); },runner,() -> true));
            var disposed=d.disposal().toCompletableFuture();
            try {
                Platform.runLater(() -> {
                    blocked.countDown();
                    try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); }
                });
                assertTrue(blocked.await(5,TimeUnit.SECONDS)); d.close(); assertFalse(disposed.isDone());
            } finally { release.countDown(); FxUiTestSupport.call(() -> { d.close(); return null; }); }
            assertTrue(disposed.isDone()); assertEquals(0,calls.get());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancelledFutureCannotDisposeAnUninterruptibleReadOrAffectOtherRunnerWork(boolean backgroundClose) throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1); var interrupted=new CountDownLatch(1);
        var completedOnFx=new AtomicBoolean();
        try(var runner=new FxTaskRunner()) {
            var picker=FxUiTestSupport.call(() -> {
                var d=SchemaObjectSearchDialog.create("synthetic","s",null,() -> {
                    started.countDown(); long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(release.getCount()!=0) {
                        try { assertTrue(release.await(Math.max(1,expires-System.nanoTime()),TimeUnit.NANOSECONDS)); }
                        catch(InterruptedException ignored) { interrupted.countDown(); }
                    }
                    return List.of(new TableInfo("s","late",TableInfo.Kind.TABLE,null));
                },runner,() -> true); d.dialog().show(); return d;
            });
            var disposed=picker.disposal().toCompletableFuture();
            picker.disposal().thenRun(() -> completedOnFx.set(Platform.isFxApplicationThread()));
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                if(backgroundClose) picker.close(); else FxUiTestSupport.call(() -> { picker.dialog().close(); return null; });
                assertTrue(interrupted.await(5,TimeUnit.SECONDS));
                assertFalse(disposed.isDone(),"cancelled Future is not physical read completion");
                runner.submit(() -> {}).get(5,TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> { assertFalse(picker.dialog().isShowing()); assertTrue(list(picker).getItems().isEmpty()); return null; });
                release.countDown(); disposed.get(5,TimeUnit.SECONDS); assertTrue(completedOnFx.get());
                FxUiTestSupport.call(() -> { assertTrue(list(picker).getItems().isEmpty()); assertNull(picker.dialog().getResult()); return null; });
            } finally { release.countDown(); FxUiTestSupport.call(() -> { picker.close(); return null; }); }
        }
    }

    @Test void runningReadRejectsReloadAndMetadataEntryUntilPhysicalReturnThenRequiresExplicitRetry() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1); var published=new CountDownLatch(1);
        var calls=new AtomicInteger(); var fieldOpens=new AtomicInteger();
        try(var runner=new FxTaskRunner()) {
            var picker=FxUiTestSupport.call(() -> {
                var d=SchemaObjectSearchDialog.create("synthetic","s",null,() -> {
                    if(calls.incrementAndGet()==1) { started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
                    return List.of(new TableInfo("s","report",TableInfo.Kind.VIEW,null));
                },runner,() -> true);
                d.installMetadataSearch(fieldOpens::incrementAndGet,() -> {});
                list(d).getItems().addListener((ListChangeListener<TableInfo>) c -> { if(!list(d).getItems().isEmpty()) published.countDown(); });
                d.dialog().show(); return d;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    picker.reload(); picker.reload();
                    var metadata=(Button)picker.dialog().getDialogPane().lookup("#schema-object-metadata-search");
                    assertTrue(metadata.isDisabled()); metadata.fire(); assertEquals(0,fieldOpens.get());
                    assertEquals(1,calls.get()); return null;
                });
                release.countDown(); assertTrue(published.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertEquals(1,calls.get(),"physical return must not automatically reload");
                    var metadata=(Button)picker.dialog().getDialogPane().lookup("#schema-object-metadata-search");
                    assertFalse(metadata.isDisabled()); metadata.fire(); assertEquals(1,fieldOpens.get());
                    picker.reload(); return null;
                });
                var retried=new CountDownLatch(1);
                FxUiTestSupport.call(() -> {
                    if(list(picker).getItems().size()==1) retried.countDown();
                    else list(picker).getItems().addListener((ListChangeListener<TableInfo>) c -> { if(!list(picker).getItems().isEmpty()) retried.countDown(); });
                    return null;
                });
                assertTrue(retried.await(5,TimeUnit.SECONDS)); assertEquals(2,calls.get());
            } finally { release.countDown(); FxUiTestSupport.call(() -> { picker.close(); return null; }); }
        }
    }

    @Test void fatalReadFailureReleasesOwnershipAndKeepsDiagnosticSafe() throws Exception {
        try(var runner=new FxTaskRunner()) {
            var task=new AtomicReference<Future<?>>();
            var picker=FxUiTestSupport.call(() -> {
                var d=new SchemaObjectSearchDialog("synthetic","s",null,() -> { throw new AssertionError("PRIVATE diagnostic"); },
                        (work,success,failure) -> {
                            var future=runner.submit(() -> { try { work.call(); } catch(Exception e) { throw new RuntimeException(e); } });
                            task.set(future); return future;
                        },() -> {},() -> true);
                d.dialog().show(); return d;
            });
            try {
                var error=assertThrows(ExecutionException.class,() -> task.get().get(5,TimeUnit.SECONDS));
                assertInstanceOf(AssertionError.class,error.getCause());
                FxUiTestSupport.call(() -> {
                    assertFalse(((Button)picker.dialog().getDialogPane().lookup("#schema-object-reload")).isDisabled());
                    var status=((Label)picker.dialog().getDialogPane().lookup("#schema-object-status")).getText();
                    assertTrue(status.contains("读取失败")); assertFalse(status.contains("PRIVATE"));
                    picker.close(); assertTrue(picker.disposal().toCompletableFuture().isDone()); return null;
                });
            } finally { FxUiTestSupport.call(() -> { picker.close(); return null; }); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualShownDialogLoadsOffFxAndClosingCancelsOnlyItsOwnScope(boolean cancel) throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1),
                finished = new CountDownLatch(1), published = new CountDownLatch(1);
        AtomicBoolean workerOnFx = new AtomicBoolean(true), interrupted = new AtomicBoolean();
        try (var runner = new FxTaskRunner()) {
            var picker = FxUiTestSupport.call(() -> {
                var result = SchemaObjectSearchDialog.create("synthetic", "s", null, () -> {
                    workerOnFx.set(Platform.isFxApplicationThread()); started.countDown();
                    try {
                        release.await();
                        return List.of(new TableInfo("s", "report", TableInfo.Kind.VIEW, null),
                                new TableInfo("s", "other", TableInfo.Kind.TABLE, null));
                    } catch (InterruptedException e) { interrupted.set(true); throw e; }
                    finally { finished.countDown(); }
                }, runner, () -> true);
                list(result).getItems().addListener((ListChangeListener<TableInfo>) change -> {
                    if (!list(result).getItems().isEmpty()) published.countDown();
                });
                result.dialog().show();
                ((TextField) result.dialog().getDialogPane().lookup("#schema-object-query")).setText("REPORT");
                return result;
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS)); assertFalse(workerOnFx.get());
                if (cancel) FxUiTestSupport.call(() -> { picker.dialog().close(); return null; });
                else release.countDown();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                if (!cancel) assertTrue(published.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertEquals(cancel ? List.of() : List.of(new TableInfo("s", "report", TableInfo.Kind.VIEW, null)),
                            List.copyOf(list(picker).getItems()));
                    assertNull(list(picker).getSelectionModel().getSelectedItem());
                    assertNull(picker.dialog().getResult());
                    return null;
                });
                assertEquals(cancel, interrupted.get());
                runner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                FxUiTestSupport.call(() -> { picker.dialog().close(); picker.close(); return null; });
            }
        }
    }
    @SuppressWarnings("unchecked") private static ListView<TableInfo> list(SchemaObjectSearchDialog picker) {
        // Before showing, ScrollPane content is not yet attached by its skin.
        var content=picker.dialog().getDialogPane().getContent();
        if(content instanceof javafx.scene.control.ScrollPane scroll) content=scroll.getContent();
        return (ListView<TableInfo>) content.lookup("#schema-object-list");
    }
}
