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

/** Real explicit transaction button failure must remain visible during whole-window shutdown. */
class AppShellSqlTransactionShutdownTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeAll static void preloadFxInRunnerOwnedHome() throws Exception {
        AppShellWorkspaceShutdownTest.preloadFxNatives();
    }
    @ParameterizedTest(name="{0} explicitCommitFailure")
    @CsvSource({"POSTGRESQL","ORACLE"})
    void windowExitMustNotHideAnUnreportedExplicitCommitFailure(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.manualPendingTransaction();
            FxUiTestSupport.call(()-> {Button button=(Button)field(f.pane,"commitBtn");assertFalse(button.isDisabled());button.fire();return null;});
            assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS),"real COMMIT button must reach mock physical commit");
            assertEquals(SerialSessionOperationQueue.OperationKind.COMMIT,f.queue.snapshot().currentKind());
            assertFalse(f.queue.snapshot().currentCancellable());assertFalse(f.queue.idle().toCompletableFuture().isDone());
            assertFalse(f.session.snapshot().running(),"snapshot running is false despite a physical non-cancellable commit");
            var closing=f.closeWindow();DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()-> {DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage,f.shell.getRoot());return null;});
            assertFalse(closing.isDone());assertTrue(f.jdbc.executionThread.get().isAlive());
            assertEquals(0,f.jdbc.cancelCalls.get());assertEquals(0,f.jdbc.rollbacks.get());assertEquals(0,f.jdbc.closes.get());assertEquals(0,f.jdbc.globalCloses.get());
            f.jdbc.rollbackRelease.countDown();f.jdbc.release.countDown();
            var outcome=closing.get(10,TimeUnit.SECONDS);
            assertTrue(f.jdbc.executionThread.get().join(Duration.ofSeconds(5)));
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()-> {
                var root=f.stage.getScene().getRoot();
                System.out.println("TRANSACTION_EXIT_ACTUAL type="+type+" outcome="+outcome+" showing="+f.stage.isShowing()+" pendingFeedback="+(root.lookup("#shutdown-pending-notice")!=null)+" failureFeedback="+(root.lookup("#shutdown-failure-notice")!=null)+" session="+f.session.snapshot().connectionState()+" commits="+f.jdbc.commits.get()+" rollbacks="+f.jdbc.rollbacks.get()+" sessionClose="+f.jdbc.closes.get()+" globalClose="+f.jdbc.globalCloses.get()+" uiFinalized="+((AtomicBoolean)field(f.pane,"uiFinalized")).get()+" managed="+f.managed()+" trace="+f.jdbc.trace);
                assertAll("unreported explicit COMMIT failure must be visible and retain physical ownership",
                        ()->assertEquals(ShutdownOutcome.FAILED_PARTIAL,outcome),
                        ()->DataCubeFxShutdownContractTest.assertFailureFeedback(f.stage,f.shell.getRoot()),
                        ()->assertTrue(f.stage.isShowing()),
                        ()->assertTrue(f.managed()),
                        ()->assertFalse(((AtomicBoolean)field(f.pane,"uiFinalized")).get()),
                        ()->assertEquals(0,f.jdbc.rollbacks.get(),"no automatic rollback after uncertain explicit commit failure"),
                        ()->assertEquals(0,f.jdbc.closes.get()),()->assertEquals(0,f.jdbc.globalCloses.get()));
                return null;
            });
        }
    }
    record Visible(String status,Object revision,String resultSummary,List<?> rows,List<String> columns) {}
    static final class Fixture implements AutoCloseable {
        final String previousHome=System.getProperty("user.home");
        final ShellSqlTransactionJdbcProbe jdbc;final AppShell shell;final Stage stage;final TabPane tabs;final Tab tab;
        final SqlEditorPane pane;final JdbcEditorSession session;final SerialSessionOperationQueue queue;
        final FxTaskRunner runner;final Object coordinator;final ConnConfig config;final ConnectionTreePane.Actions actions;
        final AtomicInteger shutdownRequests=new AtomicInteger(),hidden=new AtomicInteger();
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
