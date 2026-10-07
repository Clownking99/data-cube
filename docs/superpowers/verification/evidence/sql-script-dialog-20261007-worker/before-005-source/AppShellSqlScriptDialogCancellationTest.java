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
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import com.datacube.config.SqlDraftCoordinator;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Production controls/session/runner/Alert, with deterministic scheduling of an earlier FX cancel action. */
class AppShellSqlScriptDialogCancellationTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeAll static void preloadFxInRunnerOwnedHome() throws Exception {
        AppShellWorkspaceShutdownTest.preloadFxNatives();
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL","ORACLE"})
    void earlierCancelActionMustDiscardAnAlreadyQueuedScriptErrorQuestion(DbType type) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.execute();assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(()-> {
                // This retained FX action runs before the error dialog runnable can be dispatched.
                f.jdbc.release.countDown();Thread worker=f.jdbc.executionThread.get();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);StackTraceElement[] stack;
                do {
                    stack=worker.getStackTrace();
                    if(worker.getState()==Thread.State.WAITING && isQuestionWait(stack))break;
                    Thread.onSpinWait();
                }while(System.nanoTime()<deadline);
                assertEquals(Thread.State.WAITING,worker.getState());
                assertTrue(isQuestionWait(stack),"actual production question wait, not a replacement policy");
                f.jdbc.trace.add("fx-held-policy-question-wait-observed");System.out.println("SCRIPT_DIALOG_ACTUAL_WORKER_STACK type="+type+" "+Arrays.toString(stack));
                assertTrue(f.errorWindows().isEmpty(),"the error dialog cannot have displayed in this retained FX turn");
                Button cancel=(Button)field(f.pane,"cancelBtn");assertFalse(cancel.isDisabled());cancel.fire();f.jdbc.trace.add("earlier-real-cancel-button-fired");
                // Baseline closes the connection when no Statement is active; repaired code may instead finish.
                // Neither outcome is imposed as the cancellation contract.
                while(f.jdbc.connectionClosed.getCount()!=0 && !f.queue.idle().toCompletableFuture().isDone() && System.nanoTime()<deadline)Thread.onSpinWait();
                f.jdbc.trace.add("fx-cancel-observation-complete:connectionClosed="+(f.jdbc.connectionClosed.getCount()==0)+":queueIdle="+f.queue.idle().toCompletableFuture().isDone());
                return null;
            });
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()-> {
                var windows=f.errorWindows();
                @SuppressWarnings("unchecked") var control=((AtomicReference<SqlExecutionControl>)field(f.session,"activeControl")).get();
                System.out.println("SCRIPT_DIALOG_CANCEL_ACTUAL type="+type+" dialogCount="+windows.size()+" owners="+windows.stream().map(Stage::getOwner).toList()+" modalities="+windows.stream().map(Stage::getModality).toList()+" stageShowing="+f.stage.isShowing()+" queueIdle="+f.queue.idle().toCompletableFuture().isDone()+" current="+f.queue.snapshot().currentKind()+" session="+f.session.snapshot()+" controlCancelled="+(control!=null&&control.cancellationRequested())+" sessionClose="+f.jdbc.closes.get()+" globalClose="+f.jdbc.globalCloses.get()+" statementClose="+f.jdbc.statementCloses.get()+" cancelCalls="+f.jdbc.cancelCalls.get()+" executed="+f.jdbc.executed+" fixtureCleanupNotYetStarted=true trace="+f.jdbc.trace);
                assertAll(
                        ()->assertTrue(windows.isEmpty(),"a cancellation that preceded display must discard the old question"),
                        ()->assertTrue(f.queue.idle().toCompletableFuture().isDone(),"the real script policy wait must be released by cancellation"),
                        ()->assertEquals(List.of(ShellSqlScriptDialogJdbcProbe.FIRST),f.jdbc.executed,"successor SQL must not execute"));
                return null;
            });
            f.executeNextAndAssert(73);
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL,CONTINUE","ORACLE,CONTINUE","POSTGRESQL,CONTINUE_ALL","ORACLE,CONTINUE_ALL","POSTGRESQL,ABORT","ORACLE,ABORT","POSTGRESQL,X","ORACLE,X"})
    void normalQuestionChoicesRetainTheirOriginalScriptSemantics(DbType type,String choice) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.jdbc.errors.add(ShellSqlScriptDialogJdbcProbe.SECOND);f.jdbc.release.countDown();
            f.execute(ShellSqlScriptDialogJdbcProbe.SCRIPT+ShellSqlScriptDialogJdbcProbe.THIRD+";");
            int prompts=choice.equals("CONTINUE") ? 2 : 1;
            for(int i=0;i<prompts;i++)f.answer(f.awaitErrorWindow(),choice);
            f.awaitIdle();assertEquals(choice.startsWith("CONTINUE") ? List.of(ShellSqlScriptDialogJdbcProbe.FIRST,ShellSqlScriptDialogJdbcProbe.SECOND,ShellSqlScriptDialogJdbcProbe.THIRD) : List.of(ShellSqlScriptDialogJdbcProbe.FIRST),f.jdbc.executed);
            assertEquals(0,f.jdbc.cancelCalls.get());assertEquals(0,f.jdbc.closes.get());
            FxUiTestSupport.call(()-> {assertTrue(f.errorWindows().isEmpty());var report=((SqlBatchResults)field(f.pane,"batchResults")).report();assertNotNull(report);System.out.println("SCRIPT_DIALOG_NORMAL choice="+choice+" report="+report+" status="+f.visible().status());assertEquals(choice.startsWith("CONTINUE") ? 2 : 1,report.failed());assertEquals(choice.startsWith("CONTINUE") ? 1 : 0,report.normal());assertEquals(0,report.cancelled());assertEquals(report.summary(),f.visible().status());return null;});
        }
    }
    @ParameterizedTest @CsvSource({"POSTGRESQL,true","ORACLE,true","POSTGRESQL,false","ORACLE,false"})
    void actualWholeWindowCloseOwnsBothShownAndQueuedQuestions(DbType type,boolean shown) throws Exception {
        try(var f=new Fixture(type,directory)) {
            f.execute();assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS));
            CompletableFuture<ShutdownOutcome> closing;
            if(shown){f.jdbc.release.countDown();f.awaitErrorWindow();closing=f.closeWindow();}
            else closing=FxUiTestSupport.call(()-> {f.jdbc.release.countDown();awaitQuestionWorker(f.jdbc.executionThread.get());assertTrue(f.errorWindows().isEmpty());return f.closeWindow();});
            assertEquals(1,f.shutdownRequests.get());
            assertEquals(ShutdownOutcome.COMPLETED,closing.get(10,TimeUnit.SECONDS));DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()-> {assertFalse(f.stage.isShowing());assertTrue(f.errorWindows().isEmpty());assertFalse(f.tabs.getTabs().contains(f.tab));assertTrue(((AtomicBoolean)field(f.pane,"uiFinalized")).get());return null;});
            assertEquals(List.of(ShellSqlScriptDialogJdbcProbe.FIRST),f.jdbc.executed);assertEquals(1,f.jdbc.closes.get());assertEquals(1,f.jdbc.globalCloses.get());assertEquals(0,f.jdbc.commits.get());assertEquals(0,f.jdbc.rollbacks.get());
            assertEquals(ShutdownOutcome.COMPLETED,FxUiTestSupport.call(f.shell::shutdownAsync).toCompletableFuture().get(2,TimeUnit.SECONDS));
        }
    }
    @ParameterizedTest @CsvSource({"true,true","false,true","true,false"})
    void actualSingleTabCloseOnlyEndsQuestionsAfterItsOriginalConfirmation(boolean shown,boolean approved) throws Exception {
        try(var f=new Fixture(DbType.POSTGRESQL,directory)) {
            f.execute();assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS));
            if(shown){f.jdbc.release.countDown();f.awaitErrorWindow();}
            var settlement=f.closeTab(approved,!shown);assertEquals(approved ? TabCloseOutcome.COMPLETED : TabCloseOutcome.CANCELLED,settlement.get(10,TimeUnit.SECONDS));DataCubeFxShutdownContractTest.awaitLayoutPulses();
            if(approved){assertEquals(List.of(ShellSqlScriptDialogJdbcProbe.FIRST),f.jdbc.executed);assertEquals(1,f.jdbc.closes.get());FxUiTestSupport.call(()-> {assertTrue(f.errorWindows().isEmpty());assertFalse(f.tabs.getTabs().contains(f.tab));return null;});}
            else {assertFalse(f.queue.idle().toCompletableFuture().isDone());assertEquals(0,f.jdbc.closes.get());f.answer(f.awaitErrorWindow(),"CONTINUE");f.awaitIdle();assertEquals(List.of(ShellSqlScriptDialogJdbcProbe.FIRST,ShellSqlScriptDialogJdbcProbe.SECOND),f.jdbc.executed);FxUiTestSupport.call(()-> {assertTrue(f.tabs.getTabs().contains(f.tab));return null;});}
        }
    }
    @org.junit.jupiter.api.Test void rejectedMandatoryDraftFlushKeepsTheQuestionAnswerable() throws Exception {
        try(var f=new Fixture(DbType.POSTGRESQL,directory)) {
            SqlDraftUi drafts=f.readyDrafts();Object owned=field(field(field(drafts.runtime(),"backend"),"store"),"directory");Object mover=field(owned,"mover");Class<?> api=mover.getClass().getInterfaces()[0];var faults=new AtomicInteger();
            Path ownedDirectory=Path.of(System.getProperty("user.home"),".datacube","sql-drafts").toRealPath();
            Object fault=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,a)-> {Path target=(Path)a[1];assertEquals(ownedDirectory,target.getParent());if(target.getFileName().toString().endsWith(".draft")){faults.incrementAndGet();throw new java.io.IOException("synthetic owned draft failure");}m.setAccessible(true);try{return m.invoke(mover,a);}catch(InvocationTargetException e){throw e.getCause();}});
            set(owned,"mover",fault);
            try {
                f.execute();assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS));f.jdbc.release.countDown();Stage question=f.awaitErrorWindow();
                var closing=f.closeWindow();
                assertEquals(ShutdownOutcome.CANCELLED,closing.get(10,TimeUnit.SECONDS));assertEquals(1,f.shutdownRequests.get());DataCubeFxShutdownContractTest.awaitLayoutPulses();assertTrue(faults.get()>0);
                FxUiTestSupport.call(()-> {assertTrue(question.isShowing());assertSame(f.stage,question.getOwner());assertFalse(f.shell.getRoot().isDisabled());assertTrue(f.queue.snapshot().accepting());DataCubeFxShutdownContractTest.assertNoFeedback(f.stage.getScene().getRoot());return null;});
                assertEquals(0,f.jdbc.closes.get());assertFalse(f.queue.idle().toCompletableFuture().isDone());set(owned,"mover",mover);f.answer(question,"CONTINUE");f.awaitIdle();assertEquals(2,f.jdbc.executed.size());
            }finally{set(owned,"mover",mover);}
        }
    }
    @org.junit.jupiter.api.Test void physicalCancellationCompletesBeforeNewExecutionAndItsLateUiCallbackCannotOverwriteIt() throws Exception {
        try(var f=new Fixture(DbType.POSTGRESQL,directory)) {
            f.jdbc.blockClose=true;Object scope=field(f.pane,"tasks");
            @SuppressWarnings("unchecked") Consumer<Runnable> dispatcher=(Consumer<Runnable>)field(scope,"uiDispatcher");var retained=new CompletableFuture<Runnable>();var delivered=new AtomicBoolean();
            set(scope,"uiDispatcher",(Consumer<Runnable>)runnable-> {if(Thread.currentThread()==f.jdbc.cancelThread.get())retained.complete(runnable);else dispatcher.accept(runnable);});
            try {
                f.execute();assertTrue(f.jdbc.entered.await(5,TimeUnit.SECONDS));f.jdbc.release.countDown();f.awaitErrorWindow();
                FxUiTestSupport.call(()-> {((Button)field(f.pane,"cancelBtn")).fire();return null;});assertTrue(f.jdbc.closeEntered.await(5,TimeUnit.SECONDS));
                FxUiTestSupport.call(()-> {assertTrue(f.errorWindows().isEmpty());assertTrue(((Button)field(f.pane,"executeBtn")).isDisabled());((Button)field(f.pane,"executeBtn")).fire();assertEquals(1,f.jdbc.executed.size());return null;});
                assertFalse(f.queue.idle().toCompletableFuture().isDone());assertTrue(f.jdbc.cancelThread.get().isAlive());assertEquals(0,f.jdbc.globalCloses.get());
                f.jdbc.closeRelease.countDown();Runnable callback=retained.get(5,TimeUnit.SECONDS);f.awaitIdle();f.executeNextAndAssert(97);var before=FxUiTestSupport.call(f::visible);
                delivered.set(true);dispatcher.accept(callback);DataCubeFxShutdownContractTest.awaitLayoutPulses();assertEquals(before,FxUiTestSupport.call(f::visible));
                System.out.println("SCRIPT_CANCEL_OLD_CALLBACK actualRunnableRetainedByTestScheduler=true physicalCancelBeforeNext=true newUpdateCount=97 newUiUnchangedAfterOriginalCallback=true trace="+f.jdbc.trace);
            }finally{f.jdbc.closeRelease.countDown();set(scope,"uiDispatcher",dispatcher);if(retained.isDone()&&!delivered.get())dispatcher.accept(retained.getNow(null));}
        }
    }
    static void awaitQuestionWorker(Thread worker) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){if(worker.getState()==Thread.State.WAITING && isQuestionWait(worker.getStackTrace()))return;Thread.onSpinWait();}
        fail("bounded actual production question wait observation");
    }
    static boolean isQuestionWait(StackTraceElement[] stack) {
        return Arrays.stream(stack).anyMatch(e -> e.getClassName().equals(ScriptErrorQuestionGate.class.getName()) && e.getMethodName().equals("onError"))
                && Arrays.stream(stack).anyMatch(e -> e.getClassName().equals(CompletableFuture.class.getName()) && e.getMethodName().equals("get"));
    }
    static final class Fixture implements AutoCloseable {
        final String previousHome=System.getProperty("user.home");
        final ShellSqlScriptDialogJdbcProbe jdbc;final AppShell shell;final Stage stage;
        final SqlEditorPane pane;final JdbcEditorSession session;final SerialSessionOperationQueue queue;
        final Tab tab;final TabPane tabs;final Object coordinator;
        final Set<Window> priorWindows;
        final AtomicReference<CompletableFuture<ShutdownOutcome>> requested=new AtomicReference<>();
        final AtomicInteger shutdownRequests=new AtomicInteger();
        Fixture(DbType type,Path directory) throws Exception {
            jdbc=new ShellSqlScriptDialogJdbcProbe(type);
            var config=new ConnConfig("synthetic-script","synthetic-script",type,"synthetic.invalid",1,"synthetic","synthetic","",Map.of("environment","TEST","readOnly","false"));
            Path owned=Files.createDirectory(directory.resolve("profile-"+UUID.randomUUID())).toRealPath();System.setProperty("user.home",owned.toString());
            var initialized=new AtomicReference<AppShell>();var initializedStage=new AtomicReference<Stage>();
            try {
                Object[] data=FxUiTestSupport.call(()-> {
                    priorWindowsHolder.set(Set.copyOf(Window.getWindows()));
                    var actual=new AppShell();initialized.set(actual);var manager=(ConnectionManager)field(actual,"connMgr");
                    set(manager,"providerResolver",(Function<DbType,com.datacube.spi.DatabaseProvider>)ignored->jdbc.provider(type));manager.register(config);
                    @SuppressWarnings("unchecked") var live=(Map<String,java.sql.Connection>)field(manager,"live");live.put("synthetic-global-close-only",jdbc.globalCachedCloseOnly());
                    ((ConnectionTreePane.Actions)field(actual,"treeActions")).openSqlEditor(config,null);
                    Object registry=field(field(actual,"contentTabs"),"guardedTabs");
                    @SuppressWarnings("unchecked") var entries=(Map<Tab,Object>)field(registry,"entries");assertEquals(1,entries.size());
                    Tab opened=entries.keySet().iterator().next();Object coord=entries.get(opened);
                    SqlEditorPane editor=AppShellSqlTransactionShutdownTest.capturedPane(field(coord,"mandatoryGuard"));
                    var window=new Stage();initializedStage.set(window);var controller=new WindowShutdownController(window,actual.getRoot(),actual::isRunning,()-> {shutdownRequests.incrementAndGet();var result=actual.shutdownAsync();requested.set(result.toCompletableFuture());return result;});
                    var scene=new Scene(controller.getRoot(),900,600);scene.getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),ThemeManager.class.getResource("theme-dark.css").toExternalForm());
                    window.setScene(scene);window.show();return new Object[]{actual,window,editor,opened,coord,((ContentTabPane)field(actual,"contentTabs")).getNode()};
                });
                shell=(AppShell)data[0];stage=(Stage)data[1];pane=(SqlEditorPane)data[2];priorWindows=priorWindowsHolder.get();tab=(Tab)data[3];coordinator=data[4];tabs=(TabPane)data[5];
                session=(JdbcEditorSession)field(pane,"jdbcSession");queue=(SerialSessionOperationQueue)field(pane,"sessionOperations");
                assertSame(jdbc.runner,field(session,"runner"));assertEquals(JdbcEditorSession.TransactionMode.AUTO_COMMIT,session.snapshot().transactionMode());DataCubeFxShutdownContractTest.awaitLayoutPulses();
            }catch(Exception|Error failure) {
                jdbc.release.countDown();
                try {if(initialized.get()!=null)AppShellSqlTransactionShutdownTest.dispose(initialized.get());}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                try {if(initializedStage.get()!=null)FxUiTestSupport.call(()-> {initializedStage.get().setOnCloseRequest(null);initializedStage.get().close();return null;});}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                System.setProperty("user.home",previousHome);throw failure;
            }
        }
        private final AtomicReference<Set<Window>> priorWindowsHolder=new AtomicReference<>();
        List<Stage> errorWindows() {
            return Window.getWindows().stream().filter(w->!priorWindows.contains(w)&&w instanceof Stage s&&s.isShowing()&&"执行遇错".equals(s.getTitle())).map(w->(Stage)w).toList();
        }
        CompletableFuture<ShutdownOutcome> closeWindow() throws Exception {return FxUiTestSupport.call(()-> {DataCubeFxShutdownContractTest.closeRequest(stage);return requested.get();});}
        void execute() throws Exception {execute(ShellSqlScriptDialogJdbcProbe.SCRIPT);}
        void execute(String sql) throws Exception {
            FxUiTestSupport.call(()-> {pane.setSqlText(sql);Button execute=(Button)field(pane,"executeBtn");assertFalse(execute.isDisabled());execute.fire();return null;});
        }
        Stage awaitErrorWindow() throws Exception {
            var found=new CompletableFuture<Stage>();
            ListChangeListener<Window> observer=change-> {List<Stage> windows=errorWindows();if(!windows.isEmpty())found.complete(windows.getFirst());};
            FxUiTestSupport.call(()-> {Window.getWindows().addListener(observer);List<Stage> windows=errorWindows();if(!windows.isEmpty())found.complete(windows.getFirst());return null;});
            try {Stage dialog=found.get(5,TimeUnit.SECONDS);FxUiTestSupport.call(()-> {assertSame(stage,dialog.getOwner());assertTrue(dialog.isShowing());DialogPane content=(DialogPane)dialog.getScene().getRoot();assertTrue(content.getContentText().contains("synthetic SQL failure"));return null;});return dialog;}
            finally {FxUiTestSupport.call(()-> {Window.getWindows().removeListener(observer);return null;});}
        }
        void answer(Stage dialog,String choice) throws Exception {
            FxUiTestSupport.call(()-> {if(choice.equals("X"))dialog.close();else {String text=switch(choice){case "CONTINUE"->"继续";case "CONTINUE_ALL"->"全部继续";default->"取消";};DialogPane content=(DialogPane)dialog.getScene().getRoot();ButtonType selected=content.getButtonTypes().stream().filter(b->b.getText().equals(text)).findFirst().orElseThrow();((Button)content.lookupButton(selected)).fire();}return null;});
        }
        void awaitIdle() throws Exception {queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);Thread worker=jdbc.executionThread.get();if(worker!=null)assertTrue(worker.join(Duration.ofSeconds(3)));DataCubeFxShutdownContractTest.awaitLayoutPulses();}
        AppShellSqlShutdownTest.Visible visible() throws Exception {
            @SuppressWarnings("unchecked") var table=(TableView<Object>)field(pane,"resultTable");var report=((SqlBatchResults)field(pane,"batchResults")).report();
            return new AppShellSqlShutdownTest.Visible(((Label)field(pane,"statusLabel")).getText(),field(pane,"resultStatusRevision"),report==null ? null : report.summary(),List.copyOf(table.getItems()),table.getColumns().stream().map(TableColumn::getText).toList());
        }
        void executeNextAndAssert(int count) throws Exception {jdbc.updateCount=count;execute(ShellSqlScriptDialogJdbcProbe.SECOND);awaitIdle();FxUiTestSupport.call(()-> {assertTrue(errorWindows().isEmpty());var report=((SqlBatchResults)field(pane,"batchResults")).report();assertNotNull(report);System.out.println("SCRIPT_DIALOG_NEXT actualReport="+report+" status="+visible().status()+" expectedUpdateCount="+count+" trace="+jdbc.trace);assertFalse(report.hasFailures());assertEquals(1,report.normal());assertEquals(0,report.cancelled());assertEquals(count,report.entries().getFirst().updateCount());assertEquals(report.summary(),visible().status());return null;});}
        CompletableFuture<TabCloseOutcome> closeTab(boolean approve,boolean queued) throws Exception {
            var answered=new CompletableFuture<Void>();
            var attempt=new AtomicReference<CloseAttempt>();
            Set<Window> scheduled=Collections.newSetFromMap(new IdentityHashMap<>());
            ListChangeListener<Window> observer=change-> {
                for(Window window:List.copyOf(Window.getWindows())) {
                    if(priorWindows.contains(window) || !(window instanceof Stage dialog))continue;
                    String title=dialog.getTitle();
                    if(!"关闭 SQL 文件".equals(title) && !"关闭 SQL 编辑器".equals(title))continue;
                    if(!scheduled.add(window))continue;
                    Platform.runLater(()-> {
                        try {
                            assertTrue(dialog.isShowing());DialogPane content=(DialogPane)dialog.getScene().getRoot();
                            System.out.println("SCRIPT_TAB_CLOSE_ACTUAL title="+title+" content="+content.getContentText()+" buttons="+content.getButtonTypes().stream().map(ButtonType::getText).toList()+" owner="+dialog.getOwner());
                            if ("关闭 SQL 文件".equals(title)) assertSame(stage,dialog.getOwner());
                            else assertNull(dialog.getOwner(), "existing SQL close confirmation has no owner" );
                            Object current=field(coordinator,"current");
                            assertNotNull(current);attempt.compareAndSet(null,(CloseAttempt)field(current,"exposed"));
                            String text="关闭 SQL 文件".equals(title) ? "不保存" : approve ? "取消执行、回滚并关闭" : "取消关闭";
                            jdbc.trace.add("real-original-close-dialog:"+title+":choice="+text);
                            ButtonType button=content.getButtonTypes().stream().filter(b->b.getText().equals(text)).findFirst().orElseThrow();
                            ((Button)content.lookupButton(button)).fire();
                            if("关闭 SQL 编辑器".equals(title))answered.complete(null);
                        }catch(Throwable failure){dialog.hide();answered.completeExceptionally(failure);}
                    });
                }
            };
            try {
                FxUiTestSupport.call(()-> {Window.getWindows().addListener(observer);if(queued){jdbc.release.countDown();awaitQuestionWorker(jdbc.executionThread.get());assertTrue(errorWindows().isEmpty());}tab.getOnCloseRequest().handle(new Event(Tab.TAB_CLOSE_REQUEST_EVENT));return null;});
                answered.get(5,TimeUnit.SECONDS);assertNotNull(attempt.get());return attempt.get().settlement().toCompletableFuture();
            }finally {FxUiTestSupport.call(()-> {Window.getWindows().removeListener(observer);for(Window window:scheduled)if(window.isShowing())window.hide();return null;});}
        }
        SqlDraftUi readyDrafts() throws Exception {
            @SuppressWarnings("unchecked") var drafts=FxUiTestSupport.call(()->((LazyValue<SqlDraftUi>)field(shell,"sqlDrafts")).get());var ready=new CompletableFuture<Void>();AutoCloseable observer=FxUiTestSupport.call(()-> {Runnable check=()-> {if(drafts.runtime().mode()!=SqlDraftCoordinator.Mode.INITIALIZING)ready.complete(null);};var token=drafts.observe(check);check.run();return token;});
            try {ready.get(5,TimeUnit.SECONDS);assertTrue(FxUiTestSupport.call(()->drafts.runtime().refresh()).get(5,TimeUnit.SECONDS).succeeded());}finally{FxUiTestSupport.call(()-> {observer.close();return null;});}return drafts;
        }
        @Override public void close() throws Exception {
            jdbc.release.countDown();jdbc.closeRelease.countDown();System.out.println("SCRIPT_DIALOG_FIXTURE_CLEANUP_BEGIN notProductRecovery=true trace="+jdbc.trace);
            try {
                FxUiTestSupport.call(()-> {
                    for(Stage dialog:errorWindows()) {
                        DialogPane content=(DialogPane)dialog.getScene().getRoot();ButtonType abort=content.getButtonTypes().stream().filter(t->t.getText().equals("取消")).findFirst().orElseThrow();
                        ((Button)content.lookupButton(abort)).fire();jdbc.trace.add("fixture-only-original-Alert-abort");
                    }
                    return null;
                });
                queue.idle().toCompletableFuture().get(5,TimeUnit.SECONDS);
                Thread worker=jdbc.executionThread.get();if(worker!=null)assertTrue(worker.join(Duration.ofSeconds(3)));
                assertEquals(ShutdownOutcome.COMPLETED,FxUiTestSupport.call(shell::shutdownAsync).toCompletableFuture().get(10,TimeUnit.SECONDS));
                DataCubeFxShutdownContractTest.awaitLayoutPulses();assertEquals(jdbc.opens.get(),jdbc.closes.get());assertEquals(1,jdbc.globalCloses.get());
                System.out.println("SCRIPT_DIALOG_FIXTURE_CLEANUP_END notProductRecovery=true sessionClose="+jdbc.closes.get()+" globalClose="+jdbc.globalCloses.get()+" trace="+jdbc.trace);
            }finally {try{FxUiTestSupport.call(()-> {stage.setOnCloseRequest(null);stage.close();return null;});}finally{System.setProperty("user.home",previousHome);}}
        }
    }
    static Object field(Object owner,String name) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    static void set(Object owner,String name,Object value) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
}
