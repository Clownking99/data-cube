package com.datacube.fx;

import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.service.ConnectionManager;
import com.datacube.service.JdbcEditorSession;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.model.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real AppShell/editor/queue/session/runner. Case A alone injects explicitly limited executor scheduling. */
class AppShellSqlCancelIdentityTest {
    @TempDir Path directory;
    @BeforeAll static void preloadFxInRunnerOwnedHome() throws Exception {AppShellWorkspaceShutdownTest.preloadFxNatives();}

    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void queuedOldCancellationCannotTargetTheNextExecution(DbType type) throws Exception {
        try(var f=new Fixture(type,directory,false)) {
            f.execute(ShellSqlCancelIdentityJdbcProbe.FIRST);assertTrue(f.jdbc.firstEntered.await(5,TimeUnit.SECONDS));
            Object runner=field(field(f.pane,"tasks"),"runner");ExecutorService original=(ExecutorService)field(runner,"executor");
            var armed=new AtomicBoolean();var held=new AtomicReference<Runnable>();var delivered=new AtomicBoolean();
            ExecutorService scheduler=ShellSqlJdbcProbe.proxy(ExecutorService.class,(p,m,a)-> {
                if(m.getName().equals("execute") && Platform.isFxApplicationThread() && armed.get()) {
                    assertTrue(held.compareAndSet(null,(Runnable)a[0]),"only the actual cancel button's one ScopedFuture is retained");
                    assertInstanceOf(FutureTask.class,a[0]);f.jdbc.trace.add("test-scheduler-held-original-cancel-ScopedFuture");return null;
                }
                try{return m.invoke(original,a);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
            try {
                set(runner,"executor",scheduler);
                FxUiTestSupport.call(()-> {armed.set(true);try{f.cancel();}finally{armed.set(false);}return null;});assertNotNull(held.get());
                f.jdbc.firstRelease.countDown();f.awaitFirstReturnOrCancellationReservation();
                boolean beganSecond=f.trySecondWhileOldCancellationPending("A");
                if(beganSecond)assertTrue(f.jdbc.secondEntered.await(5,TimeUnit.SECONDS));
                SqlExecutionControl next=beganSecond ? f.activeControl() : null;
                f.jdbc.trace.add("test-scheduler-dispatch-original-cancel-ScopedFuture");delivered.set(true);original.execute(held.get());((Future<?>)held.get()).get(5,TimeUnit.SECONDS);
                DataCubeFxShutdownContractTest.awaitLayoutPulses();f.record("A-after-old-cancel",type,beganSecond,next);
                if(beganSecond)f.assertSecondUnaffected(next);
                else {f.awaitIdle();f.execute(ShellSqlCancelIdentityJdbcProbe.SECOND);assertTrue(f.jdbc.secondEntered.await(5,TimeUnit.SECONDS));}
                f.finishSecondAndAssertNormal();
            }finally {
                armed.set(false);f.jdbc.releaseAll();set(runner,"executor",original);
                if(held.get()!=null && delivered.compareAndSet(false,true)){original.execute(held.get());((Future<?>)held.get()).get(5,TimeUnit.SECONDS);}
            }
        }
    }

    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void oldPhysicalCancelFailureCannotCloseTheNextExecutionConnection(DbType type) throws Exception {
        try(var f=new Fixture(type,directory,true)) {
            f.execute(ShellSqlCancelIdentityJdbcProbe.FIRST);assertTrue(f.jdbc.firstEntered.await(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(()-> {f.cancel();return null;});assertTrue(f.jdbc.cancelEntered.await(5,TimeUnit.SECONDS));
            f.jdbc.firstRelease.countDown();f.awaitFirstReturnOrCancellationReservation();
            assertEquals(1,f.jdbc.statements.getFirst().cancels.get());assertTrue(f.jdbc.cancelThread.get().isAlive());
            boolean beganSecond=f.trySecondWhileOldCancellationPending("B");
            if(beganSecond)assertTrue(f.jdbc.secondEntered.await(5,TimeUnit.SECONDS));
            SqlExecutionControl next=beganSecond ? f.activeControl() : null;
            f.jdbc.trace.add("fixture-release-old-physical-cancel-to-throw");f.jdbc.cancelRelease.countDown();
            assertTrue(f.jdbc.cancelThread.get().join(Duration.ofSeconds(3)),"actual cancel task thread must finish");DataCubeFxShutdownContractTest.awaitLayoutPulses();
            f.record("B-after-old-cancel-throw",type,beganSecond,next);
            if(beganSecond)f.assertSecondUnaffected(next);
            else {f.awaitIdle();f.execute(ShellSqlCancelIdentityJdbcProbe.SECOND);assertTrue(f.jdbc.secondEntered.await(5,TimeUnit.SECONDS));}
            f.finishSecondAndAssertNormal();
        }
    }

    static final class Fixture implements AutoCloseable {
        final String previousHome=System.getProperty("user.home");
        final ShellSqlCancelIdentityJdbcProbe jdbc;final AppShell shell;final Stage stage;final SqlEditorPane pane;
        final JdbcEditorSession session;final SerialSessionOperationQueue queue;
        Fixture(DbType type,Path directory,boolean lateCancelFailure) throws Exception {
            jdbc=new ShellSqlCancelIdentityJdbcProbe(type,lateCancelFailure);
            var config=new ConnConfig("synthetic-cancel","synthetic-cancel",type,"synthetic.invalid",1,"synthetic","synthetic","",Map.of("environment","TEST","readOnly","false"));
            Path owned=Files.createDirectory(directory.resolve("profile-"+UUID.randomUUID())).toRealPath();System.setProperty("user.home",owned.toString());
            var initialized=new AtomicReference<AppShell>();var initializedStage=new AtomicReference<Stage>();
            try {
                Object[] data=FxUiTestSupport.call(()-> {
                    var actual=new AppShell();initialized.set(actual);var manager=(ConnectionManager)field(actual,"connMgr");
                    set(manager,"providerResolver",(Function<DbType,com.datacube.spi.DatabaseProvider>)ignored->jdbc.provider(type));manager.register(config);
                    @SuppressWarnings("unchecked") var live=(Map<String,java.sql.Connection>)field(manager,"live");live.put("synthetic-global-close-only",jdbc.globalCachedCloseOnly());
                    ((ConnectionTreePane.Actions)field(actual,"treeActions")).openSqlEditor(config,null);
                    Object registry=field(field(actual,"contentTabs"),"guardedTabs");
                    @SuppressWarnings("unchecked") var entries=(Map<Tab,Object>)field(registry,"entries");assertEquals(1,entries.size());
                    Object coordinator=entries.values().iterator().next();SqlEditorPane editor=AppShellSqlTransactionShutdownTest.capturedPane(field(coordinator,"mandatoryGuard"));
                    var window=new Stage();initializedStage.set(window);var controller=new WindowShutdownController(window,actual.getRoot(),actual::isRunning,actual::shutdownAsync);
                    var scene=new Scene(controller.getRoot(),900,600);scene.getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),ThemeManager.class.getResource("theme-dark.css").toExternalForm());window.setScene(scene);window.show();return new Object[]{actual,window,editor};
                });
                shell=(AppShell)data[0];stage=(Stage)data[1];pane=(SqlEditorPane)data[2];session=(JdbcEditorSession)field(pane,"jdbcSession");queue=(SerialSessionOperationQueue)field(pane,"sessionOperations");
                assertSame(jdbc.runner,field(session,"runner"));assertEquals(JdbcEditorSession.TransactionMode.AUTO_COMMIT,session.snapshot().transactionMode());DataCubeFxShutdownContractTest.awaitLayoutPulses();
            }catch(Exception|Error failure) {
                jdbc.releaseAll();try {if(initialized.get()!=null)AppShellSqlTransactionShutdownTest.dispose(initialized.get());}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                try {if(initializedStage.get()!=null)FxUiTestSupport.call(()-> {initializedStage.get().setOnCloseRequest(null);initializedStage.get().close();return null;});}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                System.setProperty("user.home",previousHome);throw failure;
            }
        }
        void execute(String sql) throws Exception {FxUiTestSupport.call(()-> {pane.setSqlText(sql);Button execute=(Button)field(pane,"executeBtn");assertFalse(execute.isDisabled());execute.fire();return null;});}
        void cancel() throws Exception {Button cancel=(Button)field(pane,"cancelBtn");assertFalse(cancel.isDisabled());cancel.fire();}
        SqlExecutionControl activeControl() throws Exception {@SuppressWarnings("unchecked") var control=(AtomicReference<SqlExecutionControl>)field(session,"activeControl");return control.get();}
        void awaitFirstReturnOrCancellationReservation() throws Exception {
            assertTrue(jdbc.firstClosed.await(5,TimeUnit.SECONDS),"first production Statement close after execute returns");
            Thread worker=jdbc.firstThread.get();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline) {
                if(queue.idle().toCompletableFuture().isDone() || !worker.isAlive())break;
                if(worker.getState()==Thread.State.WAITING && Arrays.stream(worker.getStackTrace()).anyMatch(e->e.getClassName().equals(JdbcEditorSession.class.getName()) && e.getMethodName().equals("finishOperation")))break;
                Thread.onSpinWait();
            }
            assertTrue(queue.idle().toCompletableFuture().isDone() || !worker.isAlive() || (worker.getState()==Thread.State.WAITING && Arrays.stream(worker.getStackTrace()).anyMatch(e->e.getClassName().equals(JdbcEditorSession.class.getName()) && e.getMethodName().equals("finishOperation"))),"first operation must be physically finished or waiting to settle its cancellation");
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
        }
        boolean trySecondWhileOldCancellationPending(String scenario) throws Exception {
            return FxUiTestSupport.call(()-> {
                Button button=(Button)field(pane,"executeBtn");boolean allowed=!button.isDisabled();
                jdbc.trace.add("second-real-button-while-old-cancel-pending:scenario="+scenario+":allowed="+allowed+":queue="+queue.snapshot()+":session="+session.snapshot());
                System.out.println("CANCEL_IDENTITY_ENTRY scenario="+scenario+" allowed="+allowed+" queue="+queue.snapshot()+" session="+session.snapshot()+" "+jdbc.handles());
                pane.setSqlText(ShellSqlCancelIdentityJdbcProbe.SECOND);button.fire();
                if(!allowed)assertEquals(List.of(ShellSqlCancelIdentityJdbcProbe.FIRST),jdbc.executed,"disabled real button must refuse a new execution");
                return allowed;
            });
        }
        void record(String phase,DbType type,boolean beganSecond,SqlExecutionControl next) throws Exception {
            System.out.println("CANCEL_IDENTITY_ACTUAL phase="+phase+" provider="+type+" secondBegan="+beganSecond+" nextControlCancelled="+(next!=null&&next.cancellationRequested())+" activeStillNext="+(next!=null&&activeControl()==next)+" queue="+queue.snapshot()+" session="+session.snapshot()+" visible="+FxUiTestSupport.call(this::visible)+" "+jdbc.handles()+" executed="+jdbc.executed+" trace="+jdbc.trace+" fixtureCleanupNotStarted=true");
        }
        void assertSecondUnaffected(SqlExecutionControl next) throws Exception {
            var second=jdbc.second();assertAll(
                    ()->assertNotNull(next),
                    ()->assertSame(next,activeControl(),"old request must not replace/clear next control"),
                    ()->assertFalse(next.cancellationRequested(),"old cancellation must not mark next execution"),
                    ()->assertEquals(0,second.cancels.get(),"old cancellation must not reach next Statement"),
                    ()->assertFalse(second.owner.closed.get(),"old fallback must not close connection owned by next execution"),
                    ()->assertEquals(0,second.owner.closes.get()),
                    ()->assertEquals(JdbcEditorSession.ConnectionState.CONNECTED,session.snapshot().connectionState()));
        }
        void finishSecondAndAssertNormal() throws Exception {
            jdbc.secondRelease.countDown();awaitIdle();
            FxUiTestSupport.call(()-> {var report=((SqlBatchResults)field(pane,"batchResults")).report();System.out.println("CANCEL_IDENTITY_SECOND_TERMINAL report="+report+" visible="+visible()+" trace="+jdbc.trace);assertNotNull(report);assertEquals(1,report.normal());assertEquals(0,report.failed());assertEquals(0,report.cancelled());assertEquals(97,report.entries().getFirst().updateCount());assertEquals(report.summary(),visible().status());return null;});
            assertEquals(List.of(ShellSqlCancelIdentityJdbcProbe.FIRST,ShellSqlCancelIdentityJdbcProbe.SECOND),jdbc.executed);
        }
        void awaitIdle() throws Exception {queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();}
        AppShellSqlShutdownTest.Visible visible() throws Exception {
            @SuppressWarnings("unchecked") var table=(TableView<Object>)field(pane,"resultTable");var report=((SqlBatchResults)field(pane,"batchResults")).report();
            return new AppShellSqlShutdownTest.Visible(((Label)field(pane,"statusLabel")).getText(),field(pane,"resultStatusRevision"),report==null ? null : report.summary(),List.copyOf(table.getItems()),table.getColumns().stream().map(TableColumn::getText).toList());
        }
        @Override public void close() throws Exception {
            jdbc.releaseAll();System.out.println("CANCEL_IDENTITY_FIXTURE_CLEANUP_BEGIN notProductRecovery=true trace="+jdbc.trace);
            try {
                awaitIdle();for(Thread worker:Arrays.asList(jdbc.firstThread.get(),jdbc.secondThread.get(),jdbc.cancelThread.get()))if(worker!=null)assertTrue(worker.join(Duration.ofSeconds(3)));
                assertEquals(ShutdownOutcome.COMPLETED,FxUiTestSupport.call(shell::shutdownAsync).toCompletableFuture().get(10,TimeUnit.SECONDS));DataCubeFxShutdownContractTest.awaitLayoutPulses();
                for(var connection:jdbc.connections){assertTrue(connection.closed.get());assertEquals(1,connection.closes.get());}
                for(var statement:jdbc.statements){assertTrue(statement.closed.get());assertEquals(1,statement.closes.get());}
                assertEquals(1,jdbc.globalCloses.get());System.out.println("CANCEL_IDENTITY_FIXTURE_CLEANUP_END notProductRecovery=true "+jdbc.handles()+" trace="+jdbc.trace);
            }finally {try{FxUiTestSupport.call(()-> {stage.setOnCloseRequest(null);stage.close();return null;});}finally{System.setProperty("user.home",previousHome);}}
        }
    }
    static Object field(Object owner,String name) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void set(Object owner,String name,Object value) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
}
