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
                    if(worker.getState()==Thread.State.WAITING && Arrays.stream(stack).anyMatch(e->e.getMethodName().equals("askScriptError"))
                            && Arrays.stream(stack).anyMatch(e->e.getClassName().equals(CountDownLatch.class.getName())&&e.getMethodName().equals("await")))break;
                    Thread.onSpinWait();
                }while(System.nanoTime()<deadline);
                assertEquals(Thread.State.WAITING,worker.getState());
                assertTrue(Arrays.stream(stack).anyMatch(e->e.getMethodName().equals("askScriptError")),"actual production policy must await the queued question before cancel");
                assertTrue(Arrays.stream(stack).anyMatch(e->e.getClassName().equals(CountDownLatch.class.getName())&&e.getMethodName().equals("await")),"actual latch wait, not a replacement policy");
                f.jdbc.trace.add("fx-held-policy-latch-await-observed");System.out.println("SCRIPT_DIALOG_ACTUAL_WORKER_STACK type="+type+" "+Arrays.toString(stack));
                assertTrue(f.errorWindows().isEmpty(),"the error dialog cannot have displayed in this retained FX turn");
                Button cancel=(Button)field(f.pane,"cancelBtn");assertFalse(cancel.isDisabled());cancel.fire();f.jdbc.trace.add("earlier-real-cancel-button-fired");
                // Baseline closes the connection when no Statement is active; repaired code may instead finish.
                // Neither outcome is imposed as the cancellation contract.
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
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
        }
    }
    static final class Fixture implements AutoCloseable {
        final String previousHome=System.getProperty("user.home");
        final ShellSqlScriptDialogJdbcProbe jdbc;final AppShell shell;final Stage stage;
        final SqlEditorPane pane;final JdbcEditorSession session;final SerialSessionOperationQueue queue;
        final Set<Window> priorWindows;
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
                    SqlEditorPane editor=AppShellSqlTransactionShutdownTest.capturedPane(field(entries.values().iterator().next(),"mandatoryGuard"));
                    var window=new Stage();initializedStage.set(window);var controller=new WindowShutdownController(window,actual.getRoot(),actual::isRunning,actual::shutdownAsync);
                    var scene=new Scene(controller.getRoot(),900,600);scene.getStylesheets().setAll(ThemeManager.class.getResource("theme-base.css").toExternalForm(),ThemeManager.class.getResource("theme-dark.css").toExternalForm());
                    window.setScene(scene);window.show();return new Object[]{actual,window,editor};
                });
                shell=(AppShell)data[0];stage=(Stage)data[1];pane=(SqlEditorPane)data[2];priorWindows=priorWindowsHolder.get();
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
        void execute() throws Exception {
            FxUiTestSupport.call(()-> {pane.setSqlText(ShellSqlScriptDialogJdbcProbe.SCRIPT);Button execute=(Button)field(pane,"executeBtn");assertFalse(execute.isDisabled());execute.fire();return null;});
        }
        @Override public void close() throws Exception {
            jdbc.release.countDown();System.out.println("SCRIPT_DIALOG_FIXTURE_CLEANUP_BEGIN notProductRecovery=true trace="+jdbc.trace);
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
                DataCubeFxShutdownContractTest.awaitLayoutPulses();assertEquals(1,jdbc.closes.get());assertEquals(1,jdbc.globalCloses.get());
                System.out.println("SCRIPT_DIALOG_FIXTURE_CLEANUP_END notProductRecovery=true sessionClose="+jdbc.closes.get()+" globalClose="+jdbc.globalCloses.get()+" trace="+jdbc.trace);
            }finally {try{FxUiTestSupport.call(()-> {stage.setOnCloseRequest(null);stage.close();return null;});}finally{System.setProperty("user.home",previousHome);}}
        }
    }
    static Object field(Object owner,String name) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    static void set(Object owner,String name,Object value) throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
}
