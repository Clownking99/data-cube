package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class TableExportJdbcOwnershipTest {
 @TempDir Path root;
 static final TableRef TABLE=new TableRef("S.ch\"ema","T.ab\"le");
 static void await(CountDownLatch latch){try{assertTrue(latch.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
 TableExporter.Request request(Path path,ExportFormat format)throws Exception{return new TableExporter.Request("synthetic",TABLE,ExportContent.DATA,format,SafeResultFilePublisher.capture(path));}
 TableExportJdbcJob.Policy policy(AtomicReference<TableExportJdbcJob.Receipt> receipt){return new TableExportJdbcJob.Policy(Duration.ofSeconds(10),Duration.ofMillis(30),Duration.ofMillis(60),System::nanoTime,receipt::set);}
 void run(TableExportJdbcMocks mock,Path target,ResultExportOperation op,AtomicReference<TableExportJdbcJob.Receipt> receipt)throws Exception{
  TableExporter.export(mock.manager,request(target,ExportFormat.SQL),op,new SafeResultFilePublisher(),(a,b,c,d,e)->fail("No dump"),Files::newOutputStream,policy(receipt));
 }
 static void eventually(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());}
 @ParameterizedTest @CsvSource({"2005,false","2011,false","2009,false","2004,false","-1,false","-3,false","2005,true","2011,true","2009,true","2004,true","-1,true","-3,true"})
 void lateGetterOwnsAndReleasesResourceBeforeCancellationCanSettle(int kind,boolean invalidation)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.sqlType=kind;
  var get=new CountDownLatch(1);var returnValue=new CountDownLatch(1);var closing=new CountDownLatch(1);var releaseClose=new CountDownLatch(1);
  var closes=new AtomicInteger();var free=new AtomicInteger();
  Reader reader=new StringReader("complete"){public void close(){closes.incrementAndGet();closing.countDown();await(releaseClose);super.close();}};
  InputStream bytes=new ByteArrayInputStream(new byte[]{1,2}){public void close(){closes.incrementAndGet();closing.countDown();await(releaseClose);}};
  Class<?> api=kind==Types.CLOB?Clob.class:kind==Types.NCLOB?NClob.class:kind==Types.SQLXML?SQLXML.class:Blob.class;
  Object lob=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,a)->switch(m.getName()){
   case "getCharacterStream"->reader;case "getBinaryStream"->bytes;
   case "free"->{free.incrementAndGet();closing.countDown();await(releaseClose);yield null;}
   default->throw new AssertionError("Unknown LOB method");});
  mock.value=kind==Types.LONGVARCHAR?reader:kind==Types.VARBINARY?bytes:lob;
  mock.beforeGet=()->{get.countDown();await(returnValue);};
  Path target=Files.writeString(root.resolve("held"),"old bytes");var op=new ResultExportOperation();var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();
  var worker=new FutureTask<Throwable>(()->{try{run(mock,target,op,receipt);return null;}catch(Throwable t){return t;}});Thread.ofVirtual().start(worker);
  try{
   await(get);if(invalidation)mock.manager.register(mock.config("changed"));else assertTrue(op.cancel());returnValue.countDown();await(closing);eventually(op::cleanupPending);
   assertFalse(worker.isDone());assertEquals("old bytes",Files.readString(target));
   var busy=assertThrows(SafeResultFilePublisher.Failure.class,()->new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target),new ResultExportOperation(),(p,o)->fail("Busy producer")));
   assertEquals(SafeResultFilePublisher.Stage.TARGET_BUSY,busy.stage());
   releaseClose.countDown();Throwable outcome=worker.get(8,TimeUnit.SECONDS);
   if(invalidation)assertEquals(TableExportFailure.Kind.TARGET_CHANGED,assertInstanceOf(TableExportFailure.class,outcome).kind());else assertInstanceOf(CancellationException.class,outcome);
   assertEquals(kind==Types.LONGVARCHAR||kind==Types.VARBINARY?1:0,closes.get());assertEquals(kind==Types.LONGVARCHAR||kind==Types.VARBINARY?0:1,free.get());
   assertTrue(receipt.get().physicallySettled());assertEquals(0,receipt.get().unresolvedResources());assertEquals(0,receipt.get().livingWorkers());assertEquals("old bytes",Files.readString(target));System.out.println("JDBC_LATE_VALUE kind="+kind+" invalidation="+invalidation+" frees="+free.get()+" closes="+closes.get()+" receipt="+receipt.get());
  }finally{returnValue.countDown();releaseClose.countDown();worker.get(8,TimeUnit.SECONDS);}
 }
 @ParameterizedTest @ValueSource(ints={Types.LONGVARCHAR,Types.VARBINARY,Types.CLOB,Types.SQLXML,Types.BLOB})
 void originalReadErrorSurvivesCloseAndFreeFailure(int kind)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.sqlType=kind;Error original=new AssertionError("SYNTHETIC_READ_ERROR");var closes=new AtomicInteger();var frees=new AtomicInteger();
  Reader reader=new Reader(){public int read(char[] c,int o,int n){throw original;}public void close()throws IOException{if(closes.incrementAndGet()==1)throw new IOException("SYNTHETIC_CLOSE");}};
  InputStream stream=new InputStream(){public int read(){throw original;}public void close()throws IOException{if(closes.incrementAndGet()==1)throw new IOException("SYNTHETIC_CLOSE");}};
  Class<?> api=kind==Types.SQLXML?SQLXML.class:kind==Types.BLOB?Blob.class:Clob.class;
  Object lob=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,a)->switch(m.getName()){
   case "getCharacterStream"->reader;case "getBinaryStream"->stream;
   case "free"->{if(frees.incrementAndGet()==1)throw new SQLException("SYNTHETIC_FREE");yield null;}
   default->throw new AssertionError("Unknown LOB method");});
  mock.value=kind==Types.LONGVARCHAR?reader:kind==Types.VARBINARY?stream:lob;
  Path target=Files.writeString(root.resolve("error"),"old bytes");var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();
  assertSame(original,assertThrows(Error.class,()->run(mock,target,new ResultExportOperation(),receipt)));
  assertTrue(Arrays.stream(original.getSuppressed()).anyMatch(t->t instanceof TableExportFailure f&&f.kind()==TableExportFailure.Kind.CLEANUP));
  assertEquals(2,closes.get());if(kind!=Types.LONGVARCHAR&&kind!=Types.VARBINARY)assertEquals(2,frees.get());
  assertTrue(receipt.get().physicallySettled());assertEquals("old bytes",Files.readString(target));
 }
 @Test void setupExecuteOriginalErrorWinsOverStatementCloseError()throws Exception{
  var mock=new TableExportJdbcMocks(DbType.ORACLE);Error original=new AssertionError("SYNTHETIC_SETUP_ERROR"),close=new AssertionError("SYNTHETIC_CLOSE_ERROR");
  mock.beforeExecute=()->{throw original;};var calls=new AtomicInteger();mock.beforeStatementClose=()->{if(calls.incrementAndGet()==1)throw close;};
  Path target=Files.writeString(root.resolve("setup"),"old bytes");var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();
  assertSame(original,assertThrows(Error.class,()->run(mock,target,new ResultExportOperation(),receipt)));
  assertTrue(receipt.get().physicallySettled());assertEquals("old bytes",Files.readString(target));
 }
}
