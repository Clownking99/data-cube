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
import javafx.application.Platform;
import javafx.stage.Window;
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

/** Actual SQL controls, non-cancellable JDBC transactions, original mandatory guard and Stage close. */
class AppShellSqlTransactionShutdownTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeAll static void preloadFxInRunnerOwnedHome() throws Exception {
        AppShellWorkspaceShutdownTest.preloadFxNatives();
    }
    @ParameterizedTest(name="{0} {1} fail={2} appliedBeforeFailure={3}")
    @CsvSource({"POSTGRESQL,COMMIT,false,false","ORACLE,COMMIT,false,false",
            "POSTGRESQL,ROLLBACK,false,false","ORACLE,ROLLBACK,false,false",

            "POSTGRESQL,ROLLBACK,true,false","ORACLE,ROLLBACK,true,false",
            "POSTGRESQL,COMMIT,true,true","ORACLE,COMMIT,true,true"})
    void actualTransactionResultControlsWindowExit(DbType type,ShellSqlTransactionJdbcProbe.Action action,boolean fail,boolean applied) throws Exception {
        runTransactionCase(type,action,fail,applied);
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void windowExitMustNotHideAnUnreportedExplicitCommitFailure(DbType type) throws Exception {
        runTransactionCase(type,ShellSqlTransactionJdbcProbe.Action.COMMIT,true,false);
    }
    private void runTransactionCase(DbType type,ShellSqlTransactionJdbcProbe.Action action,boolean fail,boolean applied) throws Exception {
        try(var f=new Fixture(type,directory);var warning=new AppShellGridShutdownTest.WarningCapture()) {
            f.jdbc.action=action;f.jdbc.fail=fail;f.jdbc.effectBeforeFailure=applied;
            f.manualPendingTransaction();f.transact(action);assertActivePhysicalTransaction(f,action.name());
            long started=System.nanoTime();var closing=f.closeWindow();
            f.awaitMandatoryCallbackSuppression();DataCubeFxShutdownContractTest.awaitLayoutPulses();
            var before=FxUiTestSupport.call(()-> {DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage,f.shell.getRoot());return f.visible();});
            Object attempt=FxUiTestSupport.call(()->field(f.coordinator,"current"));
            assertPhysicalOwned(f,closing,action);
            assertSealedAndRepeatClose(f);
            if(!fail) {
                assertTrue(warning.warned.await(8,TimeUnit.SECONDS),"unmodified production PT5S warning");
                FxUiTestSupport.call(()-> {
                    assertEquals(CloseAttemptStatus.STILL_CLOSING,((CloseAttempt)field(attempt,"exposed")).status());
                    DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage,f.shell.getRoot());return null;
                });assertPhysicalOwned(f,closing,action);
                System.out.println("TRANSACTION_WARNING type="+type+" action="+action+" elapsedMillis="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" timeout=productionPT5S queueIdle=false sessionRunning=false cancel=0 physicalStillBlocked=true");
            }
            f.jdbc.release.countDown();var outcome=closing.get(10,TimeUnit.SECONDS);joinPhysicalTask(f);
            DataCubeFxShutdownContractTest.awaitLayoutPulses();recordActual(f,type+"/"+action+"/fail="+fail+"/applied="+applied,outcome);
            if(fail) {
                assertProtected(f,before,outcome);
                assertEquals(applied ? 1 : 0,f.jdbc.committedWrites.get());assertEquals(applied ? 0 : 1,f.jdbc.pendingWrites.get());
                assertEquals(action==ShellSqlTransactionJdbcProbe.Action.COMMIT ? 1 : 0,f.jdbc.commits.get());
                assertEquals(action==ShellSqlTransactionJdbcProbe.Action.ROLLBACK ? 1 : 0,f.jdbc.rollbacks.get(),"no additional rollback/retry after unreported transaction failure");
            }else {
                assertCompleted(f,outcome);assertEquals(0,f.jdbc.pendingWrites.get());
                assertEquals(action==ShellSqlTransactionJdbcProbe.Action.COMMIT ? 1 : 0,f.jdbc.commits.get());
                assertEquals(action==ShellSqlTransactionJdbcProbe.Action.ROLLBACK ? 1 : 0,f.jdbc.rollbacks.get());
                assertEquals(action==ShellSqlTransactionJdbcProbe.Action.COMMIT ? 1 : 0,f.jdbc.committedWrites.get());
                AppShellSqlShutdownTest.order(f.jdbc.trace,action+"-return:1","session-close","global-close");
            }
            assertEquals(0,f.jdbc.cancelCalls.get());assertEquals(List.of(ShellSqlTransactionJdbcProbe.FIRST),f.jdbc.executed);
            System.out.println("TRANSACTION_PRODUCT_ASSERTIONS_COMPLETE case="+type+"/"+action+"/fail="+fail+"/applied="+applied+" fixtureCleanupNotYetStarted=true");
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void physicalQueueIdleBeforeFxFailurePresentationStillProtectsWindow(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.manualPendingTransaction();f.transact(ShellSqlTransactionJdbcProbe.Action.COMMIT);assertActivePhysicalTransaction(f,"COMMIT");
            var before=FxUiTestSupport.call(f::visible);
            var closing=FxUiTestSupport.call(()-> {
                f.jdbc.release.countDown();assertTrue(f.jdbc.executionThread.get().join(Duration.ofSeconds(3)),"physical queue worker finishes while this FX dispatch retains control");
                assertTrue(f.queue.idle().toCompletableFuture().isDone());assertNull(f.queue.snapshot().currentKind());
                assertEquals(before,f.visible(),"real failure UI callback has not run in this retained FX turn");
                f.jdbc.trace.add("fx-race-queue-idle-before-error-presented");DataCubeFxShutdownContractTest.closeRequest(f.stage);return f.requested.get();
            });
            var outcome=closing.get(10,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            recordActual(f,type+"/physical-idle-fx-not-presented",outcome);assertPreGuardErrorAndProtect(f,outcome);
            assertEquals(0,f.jdbc.rollbacks.get());
            System.out.println("TRANSACTION_PRODUCT_ASSERTIONS_COMPLETE case="+type+"/fx-race fixtureCleanupNotYetStarted=true");
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void presentedOldCommitErrorThenActualRollbackRecoveryDoesNotPoisonFutureExit(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.manualPendingTransaction();f.transact(ShellSqlTransactionJdbcProbe.Action.COMMIT);assertActivePhysicalTransaction(f,"COMMIT");
            f.jdbc.release.countDown();f.queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);joinPhysicalTask(f);
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()-> {
                assertTrue(f.visible().status().startsWith("ERROR"));assertEquals(List.of("错误"),f.visible().columns());
                assertTrue(f.visible().rows().toString().contains("synthetic explicit COMMIT failed"));
                assertTrue(f.stage.isShowing());assertFalse(f.shell.getRoot().isDisabled());
                f.jdbc.trace.add("old-error-presented-to-ui");return null;
            });
            f.transact(ShellSqlTransactionJdbcProbe.Action.ROLLBACK);
            f.queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);joinPhysicalTask(f);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            assertFalse(f.session.snapshot().hasPendingTransaction());assertEquals(1,f.jdbc.rollbacks.get());
            var outcome=f.closeWindow().get(10,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            recordActual(f,type+"/old-error-shown-and-explicitly-recovered",outcome);assertCompleted(f,outcome);
            assertEquals(1,f.jdbc.commits.get());assertEquals(1,f.jdbc.rollbacks.get());
            AppShellSqlShutdownTest.order(f.jdbc.trace,"COMMIT-throw:synthetic","old-error-presented-to-ui","ROLLBACK-return:1","session-close","global-close");
            System.out.println("TRANSACTION_PRODUCT_ASSERTIONS_COMPLETE case="+type+"/old-error-recovery fixtureCleanupNotYetStarted=true");
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void actualModeChangeConfirmationCommitFailureUsesTheSameProtection(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.manualPendingTransaction();f.chooseModeChangeTransaction(true);assertActivePhysicalTransaction(f,"SET_MODE");
            var before=FxUiTestSupport.call(f::visible);var closing=f.closeWindow();
            f.awaitMandatoryCallbackSuppression();DataCubeFxShutdownContractTest.awaitLayoutPulses();assertPhysicalOwned(f,closing,ShellSqlTransactionJdbcProbe.Action.COMMIT);
            f.jdbc.release.countDown();var outcome=closing.get(10,TimeUnit.SECONDS);joinPhysicalTask(f);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            recordActual(f,type+"/mode-change-original-Alert-commit",outcome);assertProtected(f,before,outcome);
            assertEquals(JdbcEditorSession.TransactionMode.MANUAL,f.session.snapshot().transactionMode());assertEquals(1,f.jdbc.commits.get());assertEquals(0,f.jdbc.rollbacks.get());
            System.out.println("TRANSACTION_PRODUCT_ASSERTIONS_COMPLETE case="+type+"/SET_MODE fixtureCleanupNotYetStarted=true");
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void queuedModeAttemptCannotReplaceTheRunningFailedCommit(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.manualPendingTransaction();f.transact(ShellSqlTransactionJdbcProbe.Action.COMMIT);assertActivePhysicalTransaction(f,"COMMIT");
            // Synthetically change the actual ComboBox value while disabled to exercise its real nested handler.
            // This proves multiple submitted attempts without claiming native user input can bypass disabled controls.
            f.chooseModeChangeTransaction(false);assertEquals(1,f.queue.snapshot().queued());
            var queued=(Future<?>)FxUiTestSupport.call(()->field(((Deque<?>)field(f.queue,"queued")).getLast(),"completion"));
            var before=FxUiTestSupport.call(f::visible);var closing=f.closeWindow();f.awaitMandatoryCallbackSuppression();DataCubeFxShutdownContractTest.awaitLayoutPulses();
            assertTrue(queued.isCancelled());assertEquals(0,f.queue.snapshot().queued());
            f.jdbc.release.countDown();var outcome=closing.get(10,TimeUnit.SECONDS);joinPhysicalTask(f);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            recordActual(f,type+"/cancelled-queued-mode-cannot-overwrite-running-commit",outcome);assertProtected(f,before,outcome);
            assertEquals(1,f.jdbc.commits.get());assertEquals(0,f.jdbc.rollbacks.get(),"queued mode-selected rollback must never start");
            System.out.println("TRANSACTION_PRODUCT_ASSERTIONS_COMPLETE case="+type+"/multiple-attempts fixtureCleanupNotYetStarted=true");
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void nonSelectedTransactionTabIsCapturedBeforeWorkspaceFreeze(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.manualPendingTransaction();f.transact(ShellSqlTransactionJdbcProbe.Action.COMMIT);assertActivePhysicalTransaction(f,"COMMIT");
            f.openSecondSqlTab();
            var before=FxUiTestSupport.call(()-> {assertNotSame(f.tab,f.tabs.getSelectionModel().getSelectedItem());return f.visible();});
            var closing=FxUiTestSupport.call(()-> {
                f.jdbc.release.countDown();assertTrue(f.jdbc.executionThread.get().join(Duration.ofSeconds(3)));
                assertTrue(f.queue.idle().toCompletableFuture().isDone());assertNull(f.queue.snapshot().currentKind());assertEquals(before,f.visible());
                assertNotSame(f.tab,f.tabs.getSelectionModel().getSelectedItem());f.jdbc.trace.add("non-selected-failed-tab-before-window-close");
                DataCubeFxShutdownContractTest.closeRequest(f.stage);return f.requested.get();
            });
            var outcome=closing.get(10,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            recordActual(f,type+"/non-selected-tab-physical-idle",outcome);assertPreGuardErrorAndProtect(f,outcome);assertEquals(0,f.jdbc.rollbacks.get());
            System.out.println("TRANSACTION_PRODUCT_ASSERTIONS_COMPLETE case="+type+"/non-selected-tab fixtureCleanupNotYetStarted=true");
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void mandatoryDraftFlushFailureRestoresWindowAndReleasesEarlyTransactionCapture(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.manualPendingTransaction();SqlDraftUi drafts=f.readyDrafts();
            Object directoryOwner=field(field(field(drafts.runtime(),"backend"),"store"),"directory");Object mover=field(directoryOwner,"mover");
            Class<?> moverType=mover.getClass().getInterfaces()[0];var injectedFailures=new AtomicInteger();var failDraft=new AtomicBoolean(true);
            Path ownedDirectory=Path.of(System.getProperty("user.home"),".datacube","sql-drafts").toRealPath();
            Object fault=Proxy.newProxyInstance(moverType.getClassLoader(),new Class<?>[]{moverType},(proxy,method,args)-> {
                Path target=(Path)args[1];assertEquals(ownedDirectory,target.getParent(),"only this fixture owned directory can be touched");
                if(failDraft.get() && target.getFileName().toString().endsWith(".draft")){injectedFailures.incrementAndGet();throw new java.io.IOException("synthetic owned latest draft publication failure");}
                method.setAccessible(true);try{return method.invoke(mover,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
            set(directoryOwner,"mover",fault);
            try {
                FxUiTestSupport.call(()-> {f.pane.setSqlText(ShellSqlTransactionJdbcProbe.FIRST+";\n-- synthetic unsaved draft");return null;});
                f.transact(ShellSqlTransactionJdbcProbe.Action.COMMIT);assertActivePhysicalTransaction(f,"COMMIT");
                assertEquals(ShutdownOutcome.CANCELLED,f.closeWindow().get(10,TimeUnit.SECONDS));DataCubeFxShutdownContractTest.awaitLayoutPulses();
                assertTrue(injectedFailures.get()>0);assertFalse(f.queue.idle().toCompletableFuture().isDone());assertEquals(0,f.jdbc.closes.get());assertEquals(0,f.jdbc.globalCloses.get());assertEquals(0,f.jdbc.rollbacks.get());
                FxUiTestSupport.call(()-> {
                    assertTrue(f.stage.isShowing());assertFalse(f.shell.getRoot().isDisabled());DataCubeFxShutdownContractTest.assertNoFeedback(f.stage.getScene().getRoot());
                    assertTrue(f.queue.snapshot().accepting());assertTrue(f.managed());return null;
                });
                f.jdbc.trace.add("draft-flush-rejected-window-recovered");failDraft.set(false);set(directoryOwner,"mover",mover);
                f.jdbc.release.countDown();f.queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);joinPhysicalTask(f);DataCubeFxShutdownContractTest.awaitLayoutPulses();
                FxUiTestSupport.call(()-> {assertTrue(f.visible().status().startsWith("ERROR"));assertTrue(f.visible().rows().toString().contains("synthetic explicit COMMIT failed"));f.jdbc.trace.add("post-cancel-error-presented");return null;});
                f.transact(ShellSqlTransactionJdbcProbe.Action.ROLLBACK);f.queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);joinPhysicalTask(f);DataCubeFxShutdownContractTest.awaitLayoutPulses();
                assertFalse(f.session.snapshot().hasPendingTransaction());
                var outcome=f.closeWindow().get(10,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();recordActual(f,type+"/cancel-draft-flush-recover-retry-exit",outcome);assertCompleted(f,outcome);
                assertEquals(2,f.shutdownRequests.get());assertEquals(1,f.jdbc.commits.get());assertEquals(1,f.jdbc.rollbacks.get());
                AppShellSqlShutdownTest.order(f.jdbc.trace,"draft-flush-rejected-window-recovered","COMMIT-throw:synthetic","post-cancel-error-presented","ROLLBACK-return:1","session-close","global-close");
                System.out.println("TRANSACTION_PRODUCT_ASSERTIONS_COMPLETE case="+type+"/draft-flush-recovery fixtureCleanupNotYetStarted=true");
            }finally {failDraft.set(false);set(directoryOwner,"mover",mover);}
        }
    }
    static void assertPreGuardErrorAndProtect(Fixture f,ShutdownOutcome outcome) throws Exception {
        var presented=FxUiTestSupport.call(()-> {
            assertTrue(f.visible().status().startsWith("ERROR"),"normal failure callback really ran in pre-guard asynchronous workspace gap");
            assertEquals(List.of("错误"),f.visible().columns());assertTrue(f.visible().rows().toString().contains("synthetic explicit COMMIT failed"));
            return f.visible();
        });
        assertProtected(f,presented,outcome);
    }
    static void assertActivePhysicalTransaction(Fixture f,String kind) throws Exception {
        assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS));assertEquals(kind,f.queue.snapshot().currentKind().name());
        assertFalse(f.queue.snapshot().currentCancellable());assertFalse(f.queue.idle().toCompletableFuture().isDone());
        assertFalse(f.session.snapshot().running(),"session running false is not physical transaction completion");
        assertTrue(f.jdbc.executionThread.get().isAlive());assertFalse(f.jdbc.executionThread.get().isInterrupted());
    }
    static void assertPhysicalOwned(Fixture f,CompletableFuture<ShutdownOutcome> closing,ShellSqlTransactionJdbcProbe.Action action) throws Exception {
        assertFalse(closing.isDone());assertFalse(f.queue.idle().toCompletableFuture().isDone());assertTrue(f.jdbc.executionThread.get().isAlive());
        assertEquals(action==ShellSqlTransactionJdbcProbe.Action.COMMIT ? 1 : 0,f.jdbc.commits.get());
        assertEquals(action==ShellSqlTransactionJdbcProbe.Action.ROLLBACK ? 1 : 0,f.jdbc.rollbacks.get());
        assertEquals(0,f.jdbc.closes.get());assertEquals(0,f.jdbc.globalCloses.get());assertEquals(0,f.jdbc.cancelCalls.get());assertEquals(1,f.jdbc.pendingWrites.get());
        f.runner.submit(()->{}).get(2,TimeUnit.SECONDS);
        FxUiTestSupport.call(()-> {assertTrue(f.managed());assertTrue(f.tabs.getTabs().contains(f.tab));assertTrue(f.tab.isDisable());assertFalse(((FxTaskScope)field(f.pane,"tasks")).isClosed());assertFalse(((AtomicBoolean)field(f.pane,"uiFinalized")).get());return null;});
    }
    static void assertSealedAndRepeatClose(Fixture f) throws Exception {
        assertFalse(f.queue.snapshot().accepting());assertThrows(RejectedExecutionException.class,()->f.queue.submit(SerialSessionOperationQueue.OperationKind.COMMIT,()->null,ignored->{},ignored->{}));
        FxUiTestSupport.call(()-> {int count=f.tabs.getTabs().size();f.actions.openSqlEditor(f.config,null);assertEquals(count,f.tabs.getTabs().size());DataCubeFxShutdownContractTest.closeRequest(f.stage);assertEquals(1,f.shutdownRequests.get());return null;});
    }
    static void assertProtected(Fixture f,Visible before,ShutdownOutcome outcome) throws Exception {
        assertEquals(ShutdownOutcome.FAILED_PARTIAL,outcome);assertEquals(0,f.jdbc.closes.get());assertEquals(0,f.jdbc.globalCloses.get());assertEquals(0,f.jdbc.cancelCalls.get());
        f.runner.submit(()->{}).get(2,TimeUnit.SECONDS);
        FxUiTestSupport.call(()-> {
            DataCubeFxShutdownContractTest.assertFailureFeedback(f.stage,f.shell.getRoot());assertEquals(before,f.visible(),"suppressed terminal must not refresh SQL results/status/revision");
            assertTrue(f.managed());assertTrue(f.tabs.getTabs().contains(f.tab));assertTrue(f.tab.isDisable());
            assertFalse(((AtomicBoolean)field(f.pane,"uiFinalized")).get());assertFalse(((AtomicBoolean)field(f.pane,"resourcesClosed")).get());assertFalse(((FxTaskScope)field(f.pane,"tasks")).isClosed());
            DataCubeFxShutdownContractTest.closeRequest(f.stage);assertEquals(1,f.shutdownRequests.get());assertEquals(0,f.hidden.get());return null;
        });
        assertEquals(ShutdownOutcome.FAILED_PARTIAL,FxUiTestSupport.call(f.shell::shutdownAsync).toCompletableFuture().get(2,TimeUnit.SECONDS));
        assertEquals(0,f.jdbc.closes.get());assertEquals(0,f.jdbc.globalCloses.get());
    }
    static void assertCompleted(Fixture f,ShutdownOutcome outcome) throws Exception {
        assertEquals(ShutdownOutcome.COMPLETED,outcome);assertEquals(1,f.jdbc.closes.get());assertEquals(1,f.jdbc.globalCloses.get());
        FxUiTestSupport.call(()-> {
            assertFalse(f.stage.isShowing());assertEquals(1,f.hidden.get());assertFalse(f.managed());assertFalse(f.tabs.getTabs().contains(f.tab));
            DataCubeFxShutdownContractTest.assertNoFeedback(f.stage.getScene().getRoot());assertTrue(((AtomicBoolean)field(f.pane,"uiFinalized")).get());assertTrue(((AtomicBoolean)field(f.pane,"resourcesClosed")).get());
            assertThrows(IllegalStateException.class,()->f.actions.openSqlEditor(f.config,null));return null;
        });
        assertThrows(RejectedExecutionException.class,()->f.runner.submit(()->{}));
    }
    static void joinPhysicalTask(Fixture f) throws Exception {assertTrue(f.jdbc.executionThread.get().join(Duration.ofSeconds(5)));assertTrue(f.queue.idle().toCompletableFuture().isDone());}
    static void recordActual(Fixture f,String label,ShutdownOutcome outcome) throws Exception {
        FxUiTestSupport.call(()-> {var root=f.stage.getScene().getRoot();System.out.println("TRANSACTION_EXIT_ACTUAL case="+label+" outcome="+outcome+" showing="+f.stage.isShowing()+" failureFeedback="+(root.lookup("#shutdown-failure-notice")!=null)+" commits="+f.jdbc.commits.get()+" rollbacks="+f.jdbc.rollbacks.get()+" sessionClose="+f.jdbc.closes.get()+" globalClose="+f.jdbc.globalCloses.get()+" committedWrites="+f.jdbc.committedWrites.get()+" pendingWrites="+f.jdbc.pendingWrites.get()+" fixtureCleanupNotYetStarted=true trace="+f.jdbc.trace);return null;});
    }
    record Visible(String status,Object revision,String resultSummary,List<?> rows,List<String> columns) {}
    static final class Fixture implements AutoCloseable {
        final String previousHome=System.getProperty("user.home");
        final ShellSqlTransactionJdbcProbe jdbc;final AppShell shell;final Stage stage;final TabPane tabs;final Tab tab;
        final SqlEditorPane pane;final JdbcEditorSession session;final SerialSessionOperationQueue queue;
        final FxTaskRunner runner;final Object coordinator;final ConnConfig config;final ConnectionTreePane.Actions actions;
        final AtomicInteger shutdownRequests=new AtomicInteger(),hidden=new AtomicInteger();
        final List<SqlEditorPane> additionalPanes=new ArrayList<>();
        final AtomicReference<CompletableFuture<ShutdownOutcome>> requested=new AtomicReference<>();
        Fixture(DbType type,Path directory) throws Exception {
            jdbc=new ShellSqlTransactionJdbcProbe(type);config=new ConnConfig("synthetic-sql","synthetic-sql",type,"synthetic.invalid",1,"synthetic","synthetic","",Map.of("environment","TEST","readOnly","false"));
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
                jdbc.release.countDown();
                try{if(initialized.get()!=null)dispose(initialized.get());}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                try{if(initializedStage.get()!=null)FxUiTestSupport.call(()->{initializedStage.get().setOnCloseRequest(null);initializedStage.get().close();return null;});}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                System.setProperty("user.home",previousHome);throw failure;
            }
        }
        void manualPendingTransaction() throws Exception {
            FxUiTestSupport.call(()-> { @SuppressWarnings("unchecked") var mode=(ComboBox<JdbcEditorSession.TransactionMode>)field(pane,"transactionModeBox");assertFalse(mode.isDisabled());mode.setValue(JdbcEditorSession.TransactionMode.MANUAL);return null;});
            queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            assertEquals(JdbcEditorSession.TransactionMode.MANUAL,session.snapshot().transactionMode());
            execute(ShellSqlTransactionJdbcProbe.FIRST);queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()->{assertNotNull(((SqlBatchResults)field(pane,"batchResults")).report(),"the real pre-exit DML must have rendered before the next execution clears it");return null;});
            assertTrue(session.snapshot().hasPendingTransaction());assertEquals(List.of(ShellSqlTransactionJdbcProbe.FIRST),jdbc.executed);
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
        @SuppressWarnings("unchecked") boolean managed() throws Exception {return ((AsyncManagedTabRegistry<Tab>)field(field(shell,"contentTabs"),"guardedTabs")).isManaged(tab);}
        void transact(ShellSqlTransactionJdbcProbe.Action action) throws Exception {
            FxUiTestSupport.call(()-> {Button button=(Button)field(pane,action==ShellSqlTransactionJdbcProbe.Action.COMMIT ? "commitBtn" : "rollbackBtn");assertFalse(button.isDisabled());button.fire();return null;});
        }
        void chooseModeChangeTransaction(boolean commit) throws Exception {
            FxUiTestSupport.call(()-> {
                var answered=new CompletableFuture<Void>();var createdDialog=new AtomicReference<Window>();Set<Window> existing=Set.copyOf(Window.getWindows());
                Platform.runLater(()-> {
                    try {
                        Window window=Window.getWindows().stream().filter(w->!existing.contains(w)&&w.isShowing()&&w.getScene().getRoot() instanceof DialogPane).findFirst().orElseThrow();createdDialog.set(window);
                        DialogPane dialog=(DialogPane)window.getScene().getRoot();assertTrue(dialog.getContentText().contains("未提交事务"));
                        ButtonType choice=dialog.getButtonTypes().stream().filter(t->t.getText().equals(commit ? "提交" : "回滚")).findFirst().orElseThrow();
                        ((Button)dialog.lookupButton(choice)).fire();answered.complete(null);
                    }catch(Throwable failure){if(createdDialog.get()!=null)createdDialog.get().hide();answered.completeExceptionally(failure);}
                });
                @SuppressWarnings("unchecked") var mode=(ComboBox<JdbcEditorSession.TransactionMode>)field(pane,"transactionModeBox");
                mode.setValue(JdbcEditorSession.TransactionMode.AUTO_COMMIT);answered.get(2,TimeUnit.SECONDS);return null;
            });
        }
        void openSecondSqlTab() throws Exception {
            FxUiTestSupport.call(()-> {
                actions.openSqlEditor(config,null);Tab second=tabs.getSelectionModel().getSelectedItem();assertNotSame(tab,second);
                Object registry=field(field(shell,"contentTabs"),"guardedTabs");Object coord=((Map<?,?>)field(registry,"entries")).get(second);
                additionalPanes.add(capturedPane(field(coord,"mandatoryGuard")));tabs.getSelectionModel().select(second);return null;
            });DataCubeFxShutdownContractTest.awaitLayoutPulses();
        }
        void awaitMandatoryCallbackSuppression() throws Exception {
            // Layout pulses do not establish completion of the real asynchronous draft flush.
            // Observe the actual queue under its own monitor before releasing physical JDBC.
            var suppressed=new CompletableFuture<Void>();
            AutoCloseable observation=FxUiTestSupport.call(()-> {
                @SuppressWarnings("unchecked") var drafts=((LazyValue<SqlDraftUi>)field(shell,"sqlDrafts")).get();
                Runnable check=()-> {
                    try {
                        synchronized(queue) {
                            if(!(boolean)field(queue,"callbacksEnabled")) {
                                if(!suppressed.isDone())jdbc.trace.add("mandatory-callbacks-suppressed-before-jdbc-release");
                                suppressed.complete(null);
                            }
                        }
                    }catch(Throwable failure){suppressed.completeExceptionally(failure);}
                };
                var result=drafts.observe(check);check.run();return result;
            });
            try {suppressed.get(5,TimeUnit.SECONDS);}
            finally {FxUiTestSupport.call(()-> {observation.close();return null;});}
        }
        SqlDraftUi readyDrafts() throws Exception {
            @SuppressWarnings("unchecked") var drafts=FxUiTestSupport.call(()->((LazyValue<SqlDraftUi>)field(shell,"sqlDrafts")).get());
            var ready=new CompletableFuture<Void>();AutoCloseable observation=FxUiTestSupport.call(()-> {
                Runnable check=()-> {if(drafts.runtime().mode()!=com.datacube.config.SqlDraftCoordinator.Mode.INITIALIZING)ready.complete(null);};var result=drafts.observe(check);check.run();return result;
            });
            try {ready.get(5,TimeUnit.SECONDS);assertTrue(FxUiTestSupport.call(()->drafts.runtime().refresh()).get(5,TimeUnit.SECONDS).succeeded());}
            finally {FxUiTestSupport.call(()-> {observation.close();return null;});}
            return drafts;
        }
        @Override public void close() throws Exception {
            jdbc.release.countDown();
            System.out.println("FIXTURE_CLEANUP_BEGIN notProductRecovery=true commits="+jdbc.commits.get()+" rollbacks="+jdbc.rollbacks.get()+" closes="+jdbc.closes.get()+" globalCloses="+jdbc.globalCloses.get()+" committedWrites="+jdbc.committedWrites.get()+" pendingWrites="+jdbc.pendingWrites.get());
            jdbc.trace.add("fixture-cleanup-begin");
            try {
                var closing=requested.get();if(closing!=null)closing.get(10,TimeUnit.SECONDS);
                var cleaned=new CompletableFuture<Void>();
                Thread.startVirtualThread(()-> {
                    try {
                        pane.closeResources();for(SqlEditorPane additional:additionalPanes)additional.closeResources();
                        var method=AppShell.class.getDeclaredMethod("shutdownRemaining");method.setAccessible(true);method.invoke(shell);
                        cleaned.complete(null);
                    }catch(Throwable failure){cleaned.completeExceptionally(failure);}
                });
                cleaned.get(8,TimeUnit.SECONDS);
                FxUiTestSupport.call(()-> {pane.finalizeCloseOnFx();additionalPanes.forEach(SqlEditorPane::finalizeCloseOnFx);return null;});
                assertEquals(1,jdbc.closes.get());assertEquals(1,jdbc.globalCloses.get());
                System.out.println("FIXTURE_CLEANUP_END notProductRecovery=true commits="+jdbc.commits.get()+" rollbacks="+jdbc.rollbacks.get()+" closes="+jdbc.closes.get()+" globalCloses="+jdbc.globalCloses.get()+" committedWrites="+jdbc.committedWrites.get()+" pendingWrites="+jdbc.pendingWrites.get()+" trace="+jdbc.trace);
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
