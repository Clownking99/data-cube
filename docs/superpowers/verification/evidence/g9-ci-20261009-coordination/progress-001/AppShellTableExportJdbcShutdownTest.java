package com.datacube.fx;

import com.datacube.export.*;
import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class AppShellTableExportJdbcShutdownTest {
 static void await(CountDownLatch latch){try{assertTrue(latch.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
 static void eventually(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());}
 TableExportTasks owner(AppShell shell)throws Exception{return (TableExportTasks)AppShellWorkspaceShutdownTest.get(shell,"tableExports");}
 void complete(ExportDialog.ExportTask task)throws Exception{task.physicalCompletion.get(8,TimeUnit.SECONDS);task.completion.get(8,TimeUnit.SECONDS);FxUiTestSupport.call(()->null);}
 @ParameterizedTest @CsvSource({"open,false","getter,false","rollback,false","conn-close,false","getter,true","lob-free,false","reader-close,false"})
 void actualMainWindowRetainsJdbcOwnerUntilPhysicalSettlementAndSuppressesHiddenCallbacks(String stage,boolean hidden)throws Exception{
  try(var f=new AppShellWorkspaceShutdownTest.Fixture()){
   var tab=f.open("owned.sql","select 1;\n");f.checkpoint(tab);f.seed(f.capture());
   var mock=new TableExportJdbcMocks();mock.rows=1;mock.manager.acquire("synthetic");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);Runnable block=()->{entered.countDown();await(release);};
   switch(stage){case "open"->mock.beforeOpen=block;case "getter"->mock.beforeGet=block;case "rollback"->mock.beforeRollback=block;case "conn-close"->mock.beforeClose=block;}
   if(stage.equals("reader-close")){mock.sqlType=java.sql.Types.LONGVARCHAR;mock.value=new java.io.StringReader("complete"){public void close(){block.run();super.close();}};}
   if(stage.equals("lob-free")){mock.sqlType=java.sql.Types.CLOB;mock.value=java.lang.reflect.Proxy.newProxyInstance(java.sql.Clob.class.getClassLoader(),new Class<?>[]{java.sql.Clob.class},(p,m,a)->switch(m.getName()){case "getCharacterStream"->new java.io.StringReader("complete");case "free"->{block.run();yield null;}default->throw new AssertionError("Unknown LOB method");});}
   Path target=Files.writeString(f.root.resolve("jdbc"),"old bytes");var control=new TableExportJdbcTestJobs.Control();var ui=new AppShellTableExportShutdownTest.Ui();var selection=TableExporter.capture(mock.manager,"synthetic");
   var task=FxUiTestSupport.call(()->f.shell.startTableExport(selection,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,target,ui,(request,op)->TableExportJdbcTestJobs.export(mock.manager,request,op,control)));
   try{
    await(entered);if(hidden)FxUiTestSupport.call(()->{task.ownerClosed();return null;});var shutdown=f.shutdown();await(ui.stopped);eventually(()->{control.advanceCleanup();return selection.operation().cleanupPending();});
    DataCubeFxShutdownContractTest.awaitLayoutPulses();FxUiTestSupport.call(()->{DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage,f.shell.getRoot());return null;});
    assertFalse(task.physicalCompletion.isDone());assertFalse(shutdown.isDone());assertEquals(1,owner(f.shell).pending());assertFalse(owner(f.shell).admitting());assertEquals(0,f.dispatcher.closes.get());
    assertEquals("old bytes",Files.readString(target));assertFalse(mock.owners.getFirst().closed);assertEquals(0,mock.owners.getFirst().rollbacks.get());
    release.countDown();complete(task);assertTrue(control.settled());assertEquals(0,mock.exportSubscriptions());assertEquals(0,owner(f.shell).pending());assertEquals(hidden?0:1,ui.finishes.get());assertEquals(ExportDialog.Status.CANCELLED,task.completion.get().status());
    assertEquals(ShutdownOutcome.COMPLETED,shutdown.get(10,TimeUnit.SECONDS));f.assertCompleted();assertFalse(mock.owners.getFirst().closed);assertEquals("old bytes",Files.readString(target));System.out.println("JDBC_WINDOW stage="+stage+" hidden="+hidden+" owner=0 listeners=0 physical="+control.diagnostic()+" final=COMPLETED");
   }finally{release.countDown();task.physicalCompletion.get(8,TimeUnit.SECONDS);}
  }
 }
 @Test void cancelledMandatoryDecisionKeepsDedicatedExportAliveAndResumesAdmission()throws Exception{
  try(var f=new AppShellWorkspaceShutdownTest.Fixture()){
   var tab=f.open("owned.sql","select 1;\n");f.checkpoint(tab);f.seed(f.capture());var mock=new TableExportJdbcMocks();mock.rows=1;var entered=new CountDownLatch(1);var release=new CountDownLatch(1);mock.beforeNext=()->{entered.countDown();await(release);};
   Path target=Files.writeString(f.root.resolve("jdbc"),"old bytes");var selection=TableExporter.capture(mock.manager,"synthetic");var ui=new AppShellTableExportShutdownTest.Ui();var control=new TableExportJdbcTestJobs.Control();
   var task=FxUiTestSupport.call(()->f.shell.startTableExport(selection,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.XLSX,target,ui,(request,op)->TableExportJdbcTestJobs.export(mock.manager,request,op,control)));
   try{await(entered);f.fail.set(true);var decision=f.beginDecision();assertEquals(0,ui.cancellations.get());assertFalse(selection.operation().cancelled());decision.choose("取消退出");assertEquals(ShutdownOutcome.CANCELLED,decision.outcome().get(10,TimeUnit.SECONDS));assertTrue(owner(f.shell).admitting());assertFalse(task.physicalCompletion.isDone());assertFalse(mock.owners.getFirst().closed);
    release.countDown();complete(task);assertEquals(ExportDialog.Status.SUCCEEDED,ui.outcome.status());assertTrue(control.settled());assertEquals(0,mock.exportSubscriptions());f.fail.set(false);assertEquals(ShutdownOutcome.COMPLETED,f.shutdown().get(10,TimeUnit.SECONDS));f.assertCompleted();System.out.println("JDBC_WINDOW guard=CANCELLED export=SUCCEEDED admission=restored listeners=0 physical="+control.diagnostic());
   }finally{release.countDown();task.physicalCompletion.get(8,TimeUnit.SECONDS);}
  }
 }
 @ParameterizedTest @ValueSource(strings={"chosen-null","overwrite-refused","stale-before-confirm","stale-during-confirm","queued-cancel","runner-rejected","ui-error","success"})
 void selectionSubscriptionsReleaseAcrossEveryActualUiAdmissionAndCompletionPath(String mode)throws Exception{
  FxUiTestSupport.call(()->null);
  var mock=new TableExportJdbcMocks();mock.rows=1;Path target=Files.createTempFile("datacube-g9c-ui-",".owned");Files.writeString(target,"old bytes");var selection=TableExporter.capture(mock.manager,"synthetic");var starts=new AtomicInteger();var runner=new com.datacube.fx.task.FxTaskRunner();
  var ui=new AppShellTableExportShutdownTest.Ui(){public boolean confirmOverwrite(Path path){if(mode.equals("stale-during-confirm"))mock.manager.register(mock.config("changed"));return !mode.equals("overwrite-refused");}public void running(ExportDialog.ExportTask task){if(mode.equals("queued-cancel"))task.cancel();if(mode.equals("ui-error"))throw new AssertionError("SYNTHETIC_UI_ERROR");}};
  try{
   if(mode.equals("stale-before-confirm"))mock.manager.register(mock.config("changed"));if(mode.equals("runner-rejected"))runner.close();
   java.util.concurrent.Callable<ExportDialog.ExportTask> call=()->ExportDialog.startExport(selection,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,mode.equals("chosen-null")?null:target,runner,ui,(request,op)->{starts.incrementAndGet();return TableExporter.export(mock.manager,request,op);},null);
   if(mode.equals("ui-error"))assertInstanceOf(AssertionError.class,assertThrows(ExecutionException.class,()->FxUiTestSupport.call(call)).getCause());else{var task=FxUiTestSupport.call(call);if(task!=null)complete(task);else FxUiTestSupport.call(()->null);}
   assertEquals(0,mock.exportSubscriptions());assertEquals(mode.equals("success")?1:0,starts.get());if(!mode.equals("success"))assertEquals("old bytes",Files.readString(target));
  }finally{selection.close();runner.close();Files.deleteIfExists(target);}
 }
}
