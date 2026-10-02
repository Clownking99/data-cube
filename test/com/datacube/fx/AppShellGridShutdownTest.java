package com.datacube.fx;

import com.datacube.fx.task.FxTaskRunner;
import com.datacube.provider.jdbc.JdbcDataEditor;
import com.datacube.service.ConnectionManager;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Window;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Real AppShell tree action, grid/service/editor and mandatory guard; only JDBC is mock. */
class AppShellGridShutdownTest {
    @ParameterizedTest(name="{0} blockedRow={1} physicalTimeout={2}")
    @CsvSource({"POSTGRESQL,1,false","ORACLE,1,false","POSTGRESQL,2,false","ORACLE,2,false","POSTGRESQL,1,true","ORACLE,1,true"})
    void actualShellExitPreservesTransactionsAndPhysicalOwnership(DbType type,int blockedRow,boolean timeout) throws Exception {
        try(Harness h=new Harness(type,blockedRow); WarningCapture warning=new WarningCapture()) {
            h.save();
            assertTrue(h.entered.await(5,TimeUnit.SECONDS));
            Object save=field(h.pane,"saveAttempt");
            CompletableFuture<?> settled=(CompletableFuture<?>)field(save,"settled");
            String status=FxUiTestSupport.call(()->((Label)field(h.pane,"statusLabel")).getText());
            long started=System.nanoTime();
            var closing=FxUiTestSupport.call(h.shell::shutdownAsync).toCompletableFuture();
            assertTrue(h.interrupted.await(3,TimeUnit.SECONDS),"production grid close must interrupt JDBC");
            assertTrue(warning.warned.await(8,TimeUnit.SECONDS),"unmodified default PT5S warning");
            Object closeAttempt=FxUiTestSupport.call(()->field(h.coordinator,"current"));
            FxUiTestSupport.call(()->{
                assertEquals(CloseAttemptStatus.STILL_CLOSING,((CloseAttempt)field(closeAttempt,"exposed")).status());
                assertTrue(h.tabs.getTabs().contains(h.tab)); assertTrue(h.tab.isDisable()); return null;
            });
            assertFalse(closing.isDone()); assertFalse(settled.isDone());
            h.assertOwned();
            assertTrue(((com.datacube.fx.task.FxTaskScope)field(h.pane,"tasks")).isClosed());
            assertEquals(blockedRow-1,h.jdbc.commits.get()); assertEquals(0,h.jdbc.rollbacks.get());
            if(timeout) {
                assertEquals(ShutdownOutcome.FAILED_PARTIAL,closing.get(20,TimeUnit.SECONDS));
                long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                assertTrue(millis>=14500,"actual awaitClose must wait fifteen seconds: "+millis);
                h.assertOwned(); assertFalse(settled.isDone());
                assertFalse((boolean)field(closeAttempt,"finalizerInvoked"),"fatal cleanup must not finalize active UI owner");
                assertTrue(h.managed());
                assertEquals(ShutdownOutcome.FAILED_PARTIAL,FxUiTestSupport.call(h.shell::shutdownAsync).toCompletableFuture().get(2,TimeUnit.SECONDS));
                h.release.countDown(); settled.get(5,TimeUnit.SECONDS);
                assertEquals(ShutdownOutcome.FAILED_PARTIAL,FxUiTestSupport.call(h.shell::shutdownAsync).toCompletableFuture().get(2,TimeUnit.SECONDS));
                FxUiTestSupport.call(()->{assertFalse((boolean)field(closeAttempt,"finalizerInvoked"));assertTrue(h.tabs.getTabs().contains(h.tab));assertTrue(h.tab.isDisable());assertTrue(h.managed());return null;});
                assertEquals(0,h.jdbc.connectionCloses.getFirst().get(),"late settlement still cannot start global teardown");
                assertTrue(millis<23000,"bounded actual guard settlement");
                System.out.println("PHYSICAL_WAIT type="+type+" millis="+millis+" result=FAILED_PARTIAL fixtureCleanupNotYetStarted=true");
            } else {
                h.release.countDown();
                assertEquals(ShutdownOutcome.COMPLETED,closing.get(8,TimeUnit.SECONDS)); settled.get(2,TimeUnit.SECONDS);
                assertTrue((boolean)field(closeAttempt,"finalizerInvoked"),"actual production finalizer invoked");
                assertThrows(RejectedExecutionException.class,()->((FxTaskRunner)field(h.shell,"tasks")).submit(()->{}));
                h.assertClosed();
                FxUiTestSupport.call(()->{assertFalse(h.tabs.getTabs().contains(h.tab));assertFalse(h.managed());return null;});
            }
            assertEquals(java.util.stream.IntStream.rangeClosed(1,blockedRow).boxed().toList(),h.jdbc.executedRows);
            assertEquals(java.util.stream.IntStream.range(1,blockedRow).boxed().toList(),h.jdbc.committedRows);
            assertEquals(List.of(blockedRow),h.jdbc.rolledBackRows);
            assertEquals(1,h.jdbc.connectionCloses.getLast().get(),"current dedicated write lease released once");
            FxUiTestSupport.call(()->{
                assertEquals(status,((Label)field(h.pane,"statusLabel")).getText());
                assertEquals(3,((GridChangeSet)field(h.pane,"changes")).pendingCount(),"late result must not apply even committed row to closing UI");
                assertEquals(3,grid(h.pane).getItems().size());
                assertTrue(((Button)field(h.pane,"saveBtn")).isDisabled()); return null;
            });
            assertEquals(1,h.reads.get(),"no late page/refresh");
            System.out.println("SHELL_GRID type="+type+" blockedRow="+blockedRow+" timeout="+timeout+" executed="+h.jdbc.executedRows+" committed="+h.jdbc.committedRows+" rolledBack="+h.jdbc.rolledBackRows+" trace="+h.jdbc.trace);
        }
    }
    static final class Harness implements AutoCloseable {
        final ShellGridJdbcProbe jdbc=new ShellGridJdbcProbe();
        final CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        final AppShell shell; final DataGridPane pane; final Object coordinator; final TabPane tabs; final Tab tab;
        Harness(DbType type,int blockedRow) throws Exception {
            jdbc.onExecute=()->{
                if(jdbc.executes.get()!=blockedRow)return;
                entered.countDown(); boolean restore=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
                try { while(true) {
                    long remaining=deadline-System.nanoTime();
                    if(remaining<=0)throw new AssertionError("bounded synthetic JDBC barrier");
                    try { if(release.await(remaining,TimeUnit.NANOSECONDS))break; throw new AssertionError("bounded synthetic JDBC barrier"); }
                    catch(InterruptedException ignored){ restore=true; interrupted.countDown(); jdbc.trace.add("interrupted"); }
                }} finally { if(restore)Thread.currentThread().interrupt(); }
            };
            ConnectionFactory factory=new ConnectionFactory(){
                public void ensureDriverLoaded(){}
                public String test(ConnConfig ignored){throw new AssertionError("no real DB");}
                public Connection open(ConnConfig ignored){return jdbc.open();}
            };
            DatabaseProvider provider=ShellGridJdbcProbe.proxy(DatabaseProvider.class,(p,m,a)->switch(m.getName()){
                case "type"->type; case "connectionFactory"->factory; case "dialect"->jdbc.dialect;
                case "dataEditor"->new JdbcDataEditor((Connection)a[0],jdbc.dialect);
                case "dataAccessor"->ShellGridJdbcProbe.proxy(DataAccessor.class,(dp,dm,da)->{
                    if(!dm.getName().equals("page"))throw new AssertionError(dm.getName());
                    reads.incrementAndGet();jdbc.trace.add("page");
                    return new PagedResult(List.of("id","name"),List.of(List.of(1,"before-1"),List.of(2,"before-2"),List.of(3,"before-3")),false);
                });
                default->throw new AssertionError(m.getName());
            });
            var loaded=new CountDownLatch(1);
            AppShell[] initialized=new AppShell[1];
            try {
            shell=FxUiTestSupport.call(()->{
                AppShell actual=new AppShell();initialized[0]=actual;new Scene(actual.getRoot(),1000,700);
                ConnectionManager manager=(ConnectionManager)field(actual,"connMgr");
                set(manager,"providerResolver",(Function<DbType,DatabaseProvider>)ignored->provider);
                manager.register(new ConnConfig("synthetic-grid","synthetic-grid",type,"synthetic.invalid",1,"synthetic","synthetic","",Map.of("readOnly","false","environment","TEST")));
                ((ConnectionTreePane.Actions)field(actual,"treeActions")).openDataGrid("synthetic-grid",new TableRef("synthetic","items"),false);
                DataGridPane actualPane=actualPane(actual);
                grid(actualPane).itemsProperty().addListener((o,before,after)->loaded.countDown());
                return actual;
            });
            pane=FxUiTestSupport.call(()->actualPane(shell));
            coordinator=coordinator(shell);
            tabs=(TabPane)((ContentTabPane)field(shell,"contentTabs")).getNode();
            tab=FxUiTestSupport.call(()->tabs.getTabs().stream().filter(t->t.getContent()==pane.getNode()).findFirst().orElseThrow());
            assertTrue(loaded.await(5,TimeUnit.SECONDS),"actual tree-created grid load");
            } catch(Exception | Error failure) {
                release.countDown();
                if(initialized[0]!=null) {
                    var cleaned=new CompletableFuture<Void>();
                    Thread.startVirtualThread(()->{
                        try {
                            BestEffortCloseSequence.run(
                                () -> { try { actualPane(initialized[0]).closeResources(); } catch(Exception failureInPane) { throw new RuntimeException(failureInPane); } },
                                () -> { try { var method=AppShell.class.getDeclaredMethod("shutdownRemaining");method.setAccessible(true);method.invoke(initialized[0]); } catch(Exception failureInShell) { throw new RuntimeException(failureInShell); } });
                            cleaned.complete(null);
                        } catch(Throwable cleanupFailure){cleaned.completeExceptionally(cleanupFailure);}
                    });
                    try { cleaned.get(8,TimeUnit.SECONDS); } catch(Exception cleanupFailure){failure.addSuppressed(cleanupFailure);}
                }
                throw failure;
            }
        }
        void save() throws Exception {
            FxUiTestSupport.call(()->{
                assertFalse((boolean)field(pane,"busy"));assertEquals(3,grid(pane).getItems().size());
                EditableGridModel model=(EditableGridModel)field(pane,"model");
                for(int i=0;i<3;i++){var row=grid(pane).getItems().get(i);row.cell(1).setText("synthetic-edit-"+(i+1));model.reconcile(row);}
                var method=DataGridPane.class.getDeclaredMethod("updateChanges");method.setAccessible(true);method.invoke(pane);
                var confirm=new CompletableFuture<Void>();
                Platform.runLater(()->{
                    try {
                        DialogPane dialog=Window.getWindows().stream().filter(Window::isShowing).map(w->w.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
                        ((Button)dialog.lookupButton(dialog.getButtonTypes().getFirst())).fire();confirm.complete(null);
                    }catch(Throwable failure){confirm.completeExceptionally(failure);}
                });
                ((Button)field(pane,"saveBtn")).fire();confirm.get(2,TimeUnit.SECONDS);return null;
            });
        }
        void assertOwned() throws Exception {
            assertEquals(0,jdbc.connectionCloses.getFirst().get(),"cached browsing connection owned");
            assertEquals(0,jdbc.connectionCloses.getLast().get(),"dedicated write lease owned");
            ((FxTaskRunner)field(shell,"tasks")).submit(()->{}).get(2,TimeUnit.SECONDS);
        }
        @SuppressWarnings("unchecked") boolean managed() throws Exception {return ((AsyncManagedTabRegistry<Tab>)field(field(shell,"contentTabs"),"guardedTabs")).isManaged(tab);}
        void assertClosed(){for(var closes:jdbc.connectionCloses)assertEquals(1,closes.get(),"each cached/dedicated connection closes once");}
        @Override public void close() throws Exception {
            release.countDown();
            Object save=field(pane,"saveAttempt");if(save!=null)((CompletableFuture<?>)field(save,"settled")).get(5,TimeUnit.SECONDS);
            var cleaned=new CompletableFuture<Void>();
            Thread.startVirtualThread(()->{
                try{pane.closeResources();var method=AppShell.class.getDeclaredMethod("shutdownRemaining");method.setAccessible(true);method.invoke(shell);cleaned.complete(null);}
                catch(Throwable failure){cleaned.completeExceptionally(failure);}
            });
            cleaned.get(8,TimeUnit.SECONDS);FxUiTestSupport.call(()->{pane.finalizeCloseOnFx();return null;});assertClosed();
            System.out.println("FIXTURE_CLEANUP physicalSettled=true allConnectionsClosedOnce=true");
        }
    }
    static Object coordinator(AppShell shell)throws Exception{
        Object registry=field(field(shell,"contentTabs"),"guardedTabs");
        return ((Map<?,?>)field(registry,"entries")).values().stream().findFirst().orElseThrow();
    }
    static DataGridPane actualPane(AppShell shell)throws Exception{
        Object guard=field(coordinator(shell),"mandatoryGuard");
        for(Field capture:guard.getClass().getDeclaredFields()){capture.setAccessible(true);if(capture.get(guard) instanceof DataGridPane pane)return pane;}
        throw new AssertionError("production mandatory method reference must capture real pane");
    }
    @SuppressWarnings("unchecked") static TableView<EditableGridModel.Row> grid(DataGridPane pane)throws Exception{return (TableView<EditableGridModel.Row>)field(pane,"grid");}
    static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    static final class WarningCapture implements AutoCloseable{
        final CountDownLatch warned=new CountDownLatch(1);final java.io.PrintStream previous=System.err;
        WarningCapture(){System.setErr(new java.io.PrintStream(new java.io.OutputStream(){
            final StringBuilder line=new StringBuilder();
            public synchronized void write(int b){previous.write(b);line.append((char)b);if(line.toString().contains("tab close still running after PT5S"))warned.countDown();if(b=='\n')line.setLength(0);}
        }));}
        public void close(){System.setErr(previous);}
    }
}
