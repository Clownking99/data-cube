package com.datacube.export;

import com.datacube.service.*;
import com.datacube.spi.model.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class TableExportJdbcCancellationTest {
 @TempDir Path root;
 static void await(CountDownLatch l){TableExportJdbcOwnershipTest.await(l);}
 void busy(Path target)throws Exception{var failure=assertThrows(SafeResultFilePublisher.Failure.class,()->new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target),new ResultExportOperation(),(p,o)->fail("No busy source")));assertEquals(SafeResultFilePublisher.Stage.TARGET_BUSY,failure.stage());}
 @ParameterizedTest @CsvSource({"open,false","open,true","setup,false","execute,false","next,false","getter,false","rollback,false","conn-close,false","rs-close,false","stmt-close,false","writer-close,false"})
 void blockedOwnedCallRetainsBusyAndPhysicalPromiseUntilLateReturn(String stage,boolean invalidation)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;var entered=new CountDownLatch(1);var release=new CountDownLatch(1);Runnable held=()->{entered.countDown();await(release);};
  switch(stage){case "open"->mock.beforeOpen=held;case "setup"->mock.beforeSetup=held;case "execute"->mock.beforeExecute=held;case "next"->mock.beforeNext=held;case "getter"->mock.beforeGet=held;case "rollback"->mock.beforeRollback=held;case "conn-close"->mock.beforeClose=held;case "rs-close"->mock.beforeResultClose=held;case "stmt-close"->mock.beforeStatementClose=held;}
  Path target=Files.writeString(root.resolve("target"),"old bytes");Path neighbor=Files.writeString(root.resolve("neighbor"),"neighbor bytes");var op=new ResultExportOperation();var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();
  var policy=new TableExportJdbcJob.Policy(Duration.ofSeconds(10),Duration.ofMillis(15),Duration.ofMillis(30),System::nanoTime,receipt::set);
  var req=new TableExporter.Request("synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,SafeResultFilePublisher.capture(target));
  var worker=new FutureTask<Throwable>(()->{try{TableExporter.export(mock.manager,req,op,new SafeResultFilePublisher(),(a,b,c,d,e)->fail("No dump"),p->new FilterOutputStream(Files.newOutputStream(p)){public void close()throws IOException{if(stage.equals("writer-close"))held.run();super.close();}},policy);return null;}catch(Throwable failure){return failure;}});Thread.ofVirtual().start(worker);
  try{
   await(entered);if(invalidation)mock.manager.register(mock.config("changed"));else assertTrue(op.cancel());TableExportJdbcOwnershipTest.eventually(op::cleanupPending);
   assertFalse(worker.isDone());assertEquals("old bytes",Files.readString(target));busy(target);release.countDown();Throwable failure=worker.get(8,TimeUnit.SECONDS);
   if(invalidation){assertInstanceOf(TableExportFailure.class,failure);assertEquals(TableExportFailure.Kind.TARGET_CHANGED,((TableExportFailure)failure).kind());assertEquals("synthetic",mock.owners.getFirst().opened.database());}else assertInstanceOf(CancellationException.class,failure);
   assertTrue(receipt.get().physicallySettled());assertEquals(0,receipt.get().unresolvedResources());assertEquals(0,receipt.get().livingWorkers());assertFalse(receipt.get().activeStatement());
   for(var owner:mock.owners)assertTrue(owner.closed);assertEquals("old bytes",Files.readString(target));assertEquals("neighbor bytes",Files.readString(neighbor));System.out.println("JDBC_PHYSICAL stage="+stage+" invalidation="+invalidation+" receipt="+receipt.get());
  }finally{release.countDown();worker.get(8,TimeUnit.SECONDS);}
 }
 @ParameterizedTest @ValueSource(strings={"open","execute","next","getter","rollback","conn-close"})
 void totalDeadlineCoversBlockedJdbcAndCleanupCalls(String stage)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;var entered=new CountDownLatch(1);var release=new CountDownLatch(1);Runnable held=()->{entered.countDown();await(release);};
  switch(stage){case "open"->mock.beforeOpen=held;case "execute"->mock.beforeExecute=held;case "next"->mock.beforeNext=held;case "getter"->mock.beforeGet=held;case "rollback"->mock.beforeRollback=held;case "conn-close"->mock.beforeClose=held;}
  var clock=new AtomicLong();var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();var op=new ResultExportOperation();var policy=new TableExportJdbcJob.Policy(Duration.ofSeconds(1),Duration.ZERO,Duration.ZERO,clock::get,receipt::set);Path target=Files.writeString(root.resolve("deadline"),"old bytes");
  var req=new TableExporter.Request("synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,SafeResultFilePublisher.capture(target));
  var worker=new FutureTask<Throwable>(()->{try{TableExporter.export(mock.manager,req,op,new SafeResultFilePublisher(),(a,b,c,d,e)->fail("No dump"),Files::newOutputStream,policy);return null;}catch(Throwable failure){return failure;}});Thread.ofVirtual().start(worker);
  try{await(entered);clock.set(TimeUnit.SECONDS.toNanos(2));TableExportJdbcOwnershipTest.eventually(op::cleanupPending);assertFalse(worker.isDone());busy(target);release.countDown();var failure=assertInstanceOf(TableExportFailure.class,worker.get(8,TimeUnit.SECONDS));assertEquals(TableExportFailure.Kind.TIMEOUT,failure.kind());assertTrue(receipt.get().physicallySettled());assertEquals("old bytes",Files.readString(target));System.out.println("JDBC_DEADLINE stage="+stage+" receipt="+receipt.get());}
  finally{release.countDown();worker.get(8,TimeUnit.SECONDS);}
 }
 @Test void blockedCancelWorkerRemainsOwnedAfterCursorAndConnectionClose()throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;var reading=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);var cancelling=new CountDownLatch(1);var releaseCancel=new CountDownLatch(1);
  mock.beforeNext=()->{reading.countDown();await(releaseRead);};mock.beforeCancel=()->{cancelling.countDown();await(releaseCancel);};Path target=Files.writeString(root.resolve("cancel-worker"),"old bytes");var op=new ResultExportOperation();var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();
  var test=new TableExportJdbcOwnershipTest();var worker=new FutureTask<Throwable>(()->{try{test.run(mock,target,op,receipt);return null;}catch(Throwable failure){return failure;}});Thread.ofVirtual().start(worker);
  try{await(reading);assertTrue(op.cancel());await(cancelling);releaseRead.countDown();TableExportJdbcOwnershipTest.eventually(()->mock.owners.getFirst().closed);TableExportJdbcOwnershipTest.eventually(op::cleanupPending);assertFalse(worker.isDone());assertTrue(receipt.get().livingWorkers()>0);busy(target);releaseCancel.countDown();assertInstanceOf(CancellationException.class,worker.get(8,TimeUnit.SECONDS));assertTrue(receipt.get().physicallySettled());assertEquals(1,mock.owners.getFirst().statements.getFirst().cancels.get());System.out.println("JDBC_CANCEL_WORKER receipt="+receipt.get());}
  finally{releaseRead.countDown();releaseCancel.countDown();worker.get(8,TimeUnit.SECONDS);}
 }
 @Test void failedConnectionCloseGetsOnlyOneFinalConfirmationAndStillFailsPublication()throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;var calls=new AtomicInteger();mock.beforeClose=()->mock.failure=calls.incrementAndGet()==1?"conn-close":"";Path target=Files.writeString(root.resolve("close"),"old bytes");var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();
  var failure=assertThrows(TableExportFailure.class,()->new TableExportJdbcOwnershipTest().run(mock,target,new ResultExportOperation(),receipt));assertEquals(TableExportFailure.Kind.CLEANUP,failure.kind());assertEquals(2,calls.get());assertTrue(mock.owners.getFirst().closed);assertTrue(receipt.get().physicallySettled());assertEquals("old bytes",Files.readString(target));
 }
}