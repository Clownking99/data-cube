package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.FxTaskScope;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.provider.oracle.OracleSqlRunner;
import com.datacube.provider.postgres.PgSqlRunner;
import com.datacube.service.ConnectionManager;
import com.datacube.service.JdbcEditorSession;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.model.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real tree-created SQL pane, production queue/session/runners/mandatory guard and whole-window exit. */
class AppShellSqlShutdownTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeAll static void preloadFxInRunnerOwnedHome() throws Exception {
        AppShellWorkspaceShutdownTest.preloadFxNatives();
    }
    @ParameterizedTest(name="{0} cancelStillExecuting={1}")
    @CsvSource({"POSTGRESQL,false","ORACLE,false","POSTGRESQL,true","ORACLE,true"})
    void windowExitWaitsForRealSqlExecutionThenRollsBackAndReleasesOwnership(DbType type,boolean slow) throws Exception {
        try(var f=new Fixture(type,directory);var warning=new AppShellGridShutdownTest.WarningCapture()) {
            f.manualPendingTransaction();
            f.execute(ShellSqlJdbcProbe.BLOCKED+";\n"+ShellSqlJdbcProbe.THIRD+";");
            assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS));
            var queuedCallbacks=new AtomicInteger();
            Future<?> queued=FxUiTestSupport.call(()->f.queue.submit(SerialSessionOperationQueue.OperationKind.EXECUTE,
                    ()->f.session.executeScript(ShellSqlJdbcProbe.QUEUED,null,10,null,type==DbType.ORACLE),
                    ignored->queuedCallbacks.incrementAndGet(),ignored->queuedCallbacks.incrementAndGet()));
            assertEquals(1,f.queue.snapshot().queued());
            long closeStarted=System.nanoTime();CompletableFuture<ShutdownOutcome> closing=f.closeWindow();
            assertTrue(f.jdbc.cancelled.await(5,TimeUnit.SECONDS),"actual mandatory guard must reach Statement.cancel");
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            var closeAttempt=FxUiTestSupport.call(()->field(f.coordinator,"current"));
            var before=FxUiTestSupport.call(()-> {
                DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage,f.shell.getRoot());
                assertFalse(((AtomicBoolean)field(f.pane,"uiFinalized")).get());assertFalse((boolean)field(closeAttempt,"finalizerInvoked"));
                return f.visible();
            });
            var waiting=FxUiTestSupport.call(()->f.stage.getScene().getRoot().lookup("#shutdown-pending-notice"));
            assertTrue(queued.isCancelled());assertFalse(f.queue.snapshot().accepting());
            assertThrows(RejectedExecutionException.class,()->f.queue.submit(SerialSessionOperationQueue.OperationKind.EXECUTE,
                    ()->null,ignored->{},ignored->{}));
            f.assertOwnedWhileExecuting(closing);
            FxUiTestSupport.call(()-> {
                int count=f.tabs.getTabs().size();f.actions.openSqlEditor(f.config,null);assertEquals(count,f.tabs.getTabs().size(),"actual tree entry cannot install a tab after shutdown seals ownership");
                DataCubeFxShutdownContractTest.closeRequest(f.stage);assertEquals(1,f.shutdownRequests.get());return null;
            });
            if(slow) {
                assertTrue(warning.warned.await(8,TimeUnit.SECONDS),"unmodified production PT5S warning");
                FxUiTestSupport.call(()-> {
                    Object attempt=field(f.coordinator,"current");assertEquals(CloseAttemptStatus.STILL_CLOSING,((CloseAttempt)field(attempt,"exposed")).status());
                    assertFalse((boolean)field(attempt,"finalizerInvoked"));
                    DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage,f.shell.getRoot());
                    assertSame(waiting,f.stage.getScene().getRoot().lookup("#shutdown-pending-notice"));return null;
                });
                f.assertOwnedWhileExecuting(closing);
                long warningMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-closeStarted);assertTrue(warningMillis>=4500,"the original five-second warning was not shortened: "+warningMillis);
                System.out.println("SQL_WARNING type="+type+" millis="+warningMillis+" timeout=productionPT5S pending=true physicalExecuteStillRunning=true rollback=0 sessionClose=0 globalClose=0");
            }
            f.jdbc.release.countDown();
            assertTrue(f.jdbc.rollbackEntered.await(5,TimeUnit.SECONDS));
            Thread execution=f.jdbc.executionThread.get();assertTrue(execution.isVirtual());
            assertTrue(execution.join(Duration.ofSeconds(5)),"real task ends only after production progress/terminal dispatch");
            assertTrue(f.queue.idle().toCompletableFuture().isDone());assertFalse(f.session.snapshot().running());
            assertEquals(0,f.jdbc.closes.get());assertEquals(0,f.jdbc.globalCloses.get());assertEquals(0,f.jdbc.commits.get());
            assertEquals(List.of(1,1),f.jdbc.statementCloses.stream().map(AtomicInteger::get).toList());
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()-> {
                assertEquals(before,f.visible(),"late real updateCount=73 progress and terminal must not refresh closing UI");
                assertFalse(((AtomicBoolean)field(f.pane,"uiFinalized")).get());
                assertFalse(((FxTaskScope)field(f.pane,"tasks")).isClosed());
                DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage,f.shell.getRoot());
                f.jdbc.trace.add("ui-unchanged-after-late-update:73");return null;
            });
            assertFalse(closing.isDone());assertEquals(0,queuedCallbacks.get());
            f.jdbc.trace.add("rollback-released");f.jdbc.rollbackRelease.countDown();
            assertEquals(ShutdownOutcome.COMPLETED,closing.get(10,TimeUnit.SECONDS));
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()-> {
                assertFalse(f.stage.isShowing());assertEquals(1,f.hidden.get());assertEquals(1,f.shutdownRequests.get());
                DataCubeFxShutdownContractTest.assertNoFeedback(f.stage.getScene().getRoot());assertNull(waiting.getParent());
                assertFalse(f.tabs.getTabs().contains(f.tab));assertFalse(f.managed());
                assertTrue(((AtomicBoolean)field(f.pane,"resourcesClosed")).get());assertTrue(((AtomicBoolean)field(f.pane,"uiFinalized")).get());assertTrue((boolean)field(closeAttempt,"finalizerInvoked"));
                assertEquals(before.status(),((Label)field(f.pane,"statusLabel")).getText());assertEquals(before.revision(),field(f.pane,"resultStatusRevision"));
                assertThrows(IllegalStateException.class,()->f.actions.openSqlEditor(f.config,null));
                DataCubeFxShutdownContractTest.closeRequest(f.stage);assertEquals(1,f.shutdownRequests.get());return null;
            });
            assertThrows(RejectedExecutionException.class,()->f.runner.submit(()->{}));
            assertEquals(JdbcEditorSession.ConnectionState.CLOSED,f.session.snapshot().connectionState());
            assertEquals(1,f.jdbc.rollbacks.get());assertEquals(0,f.jdbc.pendingWrites.get());assertEquals(0,f.jdbc.commits.get());
            assertEquals(1,f.jdbc.opens.get());assertEquals(1,f.jdbc.closes.get());assertEquals(1,f.jdbc.globalCloses.get());
            assertEquals(1,f.jdbc.cancelCalls.get());assertEquals(0,queuedCallbacks.get());
            assertEquals(List.of(ShellSqlJdbcProbe.FIRST,ShellSqlJdbcProbe.BLOCKED),f.jdbc.executed);
            assertEquals(ShutdownOutcome.COMPLETED,FxUiTestSupport.call(f.shell::shutdownAsync).toCompletableFuture().get(2,TimeUnit.SECONDS));
            assertEquals(1,f.jdbc.closes.get());assertEquals(1,f.jdbc.globalCloses.get());
            order(f.jdbc.trace,"execute-return:73","statement-close:"+ShellSqlJdbcProbe.BLOCKED,"rollback-enter","ui-unchanged-after-late-update:73","rollback-released","rollback-complete","session-close","global-close");
            System.out.println("SQL_EXIT type="+type+" slow="+slow+" final=COMPLETED physicalIdleBeforeRollback=true commits=0 rollbacks=1 sessionClose=1 globalClose=1 queuedNotExecuted=true lateUiUnchanged=true fixtureCleanupNotYetStarted=true trace="+f.jdbc.trace);
        }
    }
    static void order(List<String> trace,String... events) {
        int previous=-1;for(String event:events){int index=trace.indexOf(event);assertTrue(index>previous,"ordered physical lifecycle: "+event+" in "+trace);previous=index;}
    }
    record Visible(String status,Object revision,String resultSummary,List<?> rows,List<String> columns) {}
    static final class Fixture implements AutoCloseable {
        final String previousHome=System.getProperty("user.home");
        final ShellSqlJdbcProbe jdbc;final AppShell shell;final Stage stage;final TabPane tabs;final Tab tab;
        final SqlEditorPane pane;final JdbcEditorSession session;final SerialSessionOperationQueue queue;
        final FxTaskRunner runner;final Object coordinator;final ConnConfig config;final ConnectionTreePane.Actions actions;
        final AtomicInteger shutdownRequests=new AtomicInteger(),hidden=new AtomicInteger();
        final AtomicReference<CompletableFuture<ShutdownOutcome>> requested=new AtomicReference<>();
        Fixture(DbType type,Path directory) throws Exception {
            jdbc=new ShellSqlJdbcProbe(type);config=new ConnConfig("synthetic-sql","synthetic-sql",type,"synthetic.invalid",1,"synthetic","synthetic","",Map.of("environment","TEST","readOnly","false"));
            Path owned=Files.createDirectory(directory.resolve("profile-"+UUID.randomUUID())).toRealPath();System.setProperty("user.home",owned.toString());
            var initialized=new AtomicReference<AppShell>();var initializedStage=new AtomicReference<Stage>();
            try {
                Object[] data=FxUiTestSupport.call(()-> {
                    var actual=new AppShell();initialized.set(actual);
                    var manager=(ConnectionManager)field(actual,"connMgr");DatabaseProvider provider=jdbc.provider(type);
                    set(manager,"providerResolver",(Function<DbType,DatabaseProvider>)ignored->provider);manager.register(config);
                    @SuppressWarnings("unchecked") var live=(Map<String,java.sql.Connection>)field(manager,"live");live.put("synthetic-global-close-only",jdbc.globalCachedCloseOnly());
                    var tree=(ConnectionTreePane.Actions)field(actual,"treeActions");tree.openSqlEditor(config,null);
                    var owner=field(field(actual,"contentTabs"),"guardedTabs");
                    @SuppressWarnings("unchecked") var entries=(Map<Tab,Object>)field(owner,"entries");assertEquals(1,entries.size());
                    Tab opened=entries.keySet().iterator().next();Object coord=entries.get(opened);SqlEditorPane editor=capturedPane(field(coord,"mandatoryGuard"));
                    var window=new Stage();initializedStage.set(window);
                    var controller=new WindowShutdownController(window,actual.getRoot(),actual::isRunning,()-> {
                        shutdownRequests.incrementAndGet();var result=actual.shutdownAsync();requested.set(result.toCompletableFuture());return result;
                    });
                    var scene=new Scene(controller.getRoot(),900,600);scene.getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),ThemeManager.class.getResource("theme-dark.css").toExternalForm());
                    window.setScene(scene);window.setOnHidden(ignored->hidden.incrementAndGet());window.show();window.setWidth(900);window.setHeight(600);
                    return new Object[]{actual,window,((ContentTabPane)field(actual,"contentTabs")).getNode(),opened,editor,coord,tree};
                });
                shell=(AppShell)data[0];stage=(Stage)data[1];tabs=(TabPane)data[2];tab=(Tab)data[3];pane=(SqlEditorPane)data[4];coordinator=data[5];actions=(ConnectionTreePane.Actions)data[6];
                session=(JdbcEditorSession)field(pane,"jdbcSession");queue=(SerialSessionOperationQueue)field(pane,"sessionOperations");runner=(FxTaskRunner)field(shell,"tasks");
                assertEquals(type==DbType.ORACLE ? OracleSqlRunner.class : PgSqlRunner.class,field(session,"runner").getClass());
                DataCubeFxShutdownContractTest.awaitLayoutPulses();
            } catch(Exception | Error failure) {
                jdbc.release.countDown();jdbc.rollbackRelease.countDown();
                try{if(initialized.get()!=null)dispose(initialized.get());}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                try{if(initializedStage.get()!=null)FxUiTestSupport.call(()->{initializedStage.get().setOnCloseRequest(null);initializedStage.get().close();return null;});}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                System.setProperty("user.home",previousHome);throw failure;
            }
        }
        void manualPendingTransaction() throws Exception {
            FxUiTestSupport.call(()-> { @SuppressWarnings("unchecked") var mode=(ComboBox<JdbcEditorSession.TransactionMode>)field(pane,"transactionModeBox");assertFalse(mode.isDisabled());mode.setValue(JdbcEditorSession.TransactionMode.MANUAL);return null;});
            queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            assertEquals(JdbcEditorSession.TransactionMode.MANUAL,session.snapshot().transactionMode());
            execute(ShellSqlJdbcProbe.FIRST);queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()->{assertNotNull(((SqlBatchResults)field(pane,"batchResults")).report(),"the real pre-exit DML must have rendered before the next execution clears it");return null;});
            assertTrue(session.snapshot().hasPendingTransaction());assertEquals(List.of(ShellSqlJdbcProbe.FIRST),jdbc.executed);
            assertEquals(1,jdbc.pendingWrites.get());assertEquals(0,jdbc.commits.get());assertEquals(0,jdbc.rollbacks.get());
            System.out.println("SQL_MANUAL_PRECONDITION transaction=ACTIVE realUiMode=true realUiDml=true committed=0 pendingWrites=1 runner="+jdbc.runner.getClass().getSimpleName());
        }
        void execute(String sql) throws Exception {FxUiTestSupport.call(()->{pane.setSqlText(sql);Button button=(Button)field(pane,"executeBtn");assertFalse(button.isDisabled());button.fire();return null;});}
        CompletableFuture<ShutdownOutcome> closeWindow() throws Exception {return FxUiTestSupport.call(()->{DataCubeFxShutdownContractTest.closeRequest(stage);return requested.get();});}
        Visible visible() throws Exception {
            @SuppressWarnings("unchecked") var table=(TableView<Object>)field(pane,"resultTable");
            var report=((SqlBatchResults)field(pane,"batchResults")).report();
            return new Visible(((Label)field(pane,"statusLabel")).getText(),field(pane,"resultStatusRevision"),report==null ? null : report.summary(),List.copyOf(table.getItems()),table.getColumns().stream().map(TableColumn::getText).toList());
        }
        void assertOwnedWhileExecuting(CompletableFuture<ShutdownOutcome> closing) throws Exception {
            assertFalse(closing.isDone());assertEquals(0,jdbc.rollbacks.get());assertEquals(0,jdbc.closes.get());assertEquals(0,jdbc.globalCloses.get());assertEquals(0,jdbc.commits.get());
            assertTrue(jdbc.executionThread.get().isAlive());assertFalse(queue.idle().toCompletableFuture().isDone());assertEquals(0,jdbc.statementCloses.getLast().get());
            runner.submit(()->{}).get(2,TimeUnit.SECONDS);
            FxUiTestSupport.call(()->{assertTrue(tabs.getTabs().contains(tab));assertTrue(tab.isDisable());assertTrue(managed());assertFalse(((FxTaskScope)field(pane,"tasks")).isClosed());return null;});
        }
        @SuppressWarnings("unchecked") boolean managed() throws Exception {return ((AsyncManagedTabRegistry<Tab>)field(field(shell,"contentTabs"),"guardedTabs")).isManaged(tab);}
        @Override public void close() throws Exception {
            jdbc.release.countDown();jdbc.rollbackRelease.countDown();
            try {
                var closing=requested.get();if(closing==null)closing=FxUiTestSupport.call(shell::shutdownAsync).toCompletableFuture();closing.get(10,TimeUnit.SECONDS);
                System.out.println("FIXTURE_CLEANUP sqlSyntheticStageOnly=true fixtureCleanupNotProductEvidence=true");
            } finally {
                try{FxUiTestSupport.call(()->{stage.setOnCloseRequest(null);stage.close();return null;});}finally{System.setProperty("user.home",previousHome);}
            }
        }
    }
    static SqlEditorPane capturedPane(Object guard) throws Exception {
        for(Field capture:guard.getClass().getDeclaredFields()){capture.setAccessible(true);if(capture.get(guard) instanceof SqlEditorPane pane)return pane;}
        throw new AssertionError("original production mandatory method reference must capture SqlEditorPane");
    }
    static Object field(Object owner,String name) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void set(Object owner,String name,Object value) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    static void dispose(AppShell shell) throws Exception {
        var finished=new CompletableFuture<Void>();Thread.startVirtualThread(()->{try{var method=AppShell.class.getDeclaredMethod("shutdownRemaining");method.setAccessible(true);method.invoke(shell);finished.complete(null);}catch(Throwable failure){finished.completeExceptionally(failure);}});finished.get(8,TimeUnit.SECONDS);
        System.out.println("FIXTURE_FALLBACK syntheticResourcesOnly=true notProductRecovery=true");
    }
}
