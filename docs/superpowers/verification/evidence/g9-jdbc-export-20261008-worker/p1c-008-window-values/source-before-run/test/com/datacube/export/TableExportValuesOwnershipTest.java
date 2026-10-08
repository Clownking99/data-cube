package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class TableExportValuesOwnershipTest {
 @TempDir Path root;
 @ParameterizedTest @ValueSource(ints={Types.CLOB,Types.NCLOB,Types.SQLXML,Types.BLOB})
 void originalLobStreamGetterErrorSurvivesFreeFailureAndConcurrentCancellation(int kind)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.sqlType=kind;var entered=new CountDownLatch(1);var released=new CountDownLatch(1);var frees=new AtomicInteger();Error original=new AssertionError("SYNTHETIC_LOB_STREAM_ERROR");var op=new ResultExportOperation();
  Class<?> api=kind==Types.CLOB?Clob.class:kind==Types.NCLOB?NClob.class:kind==Types.SQLXML?SQLXML.class:Blob.class;
  mock.value=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,a)->switch(m.getName()){
   case "getCharacterStream","getBinaryStream"->{entered.countDown();TableExportJdbcOwnershipTest.await(released);throw original;}
   case "free"->{if(frees.incrementAndGet()==1)throw new SQLException("SYNTHETIC_FREE");yield null;}
   default->throw new AssertionError("Unexpected LOB method");});
  Path target=Files.writeString(root.resolve("lob"),"old bytes");var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();var worker=new FutureTask<Throwable>(()->{try{new TableExportJdbcOwnershipTest().run(mock,target,op,receipt);return null;}catch(Throwable failure){return failure;}});Thread.ofVirtual().start(worker);
  try{TableExportJdbcOwnershipTest.await(entered);assertTrue(op.cancel());released.countDown();assertSame(original,worker.get(8,TimeUnit.SECONDS));assertEquals(2,frees.get());assertTrue(Arrays.stream(original.getSuppressed()).anyMatch(t->t instanceof TableExportFailure f&&f.kind()==TableExportFailure.Kind.CLEANUP));assertTrue(receipt.get().physicallySettled());assertEquals("old bytes",Files.readString(target));System.out.println("JDBC_LOB_ERROR kind="+kind+" original=same freeAttempts=2 receipt="+receipt.get());}
  finally{released.countDown();worker.get(8,TimeUnit.SECONDS);}
 }
 @ParameterizedTest @CsvSource({"5,1048576","16385,1"})
 void aggregateRowAndColumnLimitsFailClosedBeforeRowReachesWriter(int columns,int textLength)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.columnCount=columns;mock.value="x".repeat(textLength);Path target=Files.writeString(root.resolve("row-limit"),"old bytes");
  var failure=assertThrows(TableExportFailure.class,()->TableExporter.export(mock.manager,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,target.toFile()));assertEquals(TableExportFailure.Kind.LIMIT,failure.kind());assertEquals(columns==5?5:0,mock.getters.get());assertEquals("old bytes",Files.readString(target));assertTrue(mock.owners.getFirst().closed);
 }
 @ParameterizedTest @EnumSource(value=DbType.class,names={"POSTGRESQL","ORACLE"})
 void supportedBinaryIsFullySerializedThroughRealDialect(DbType type)throws Exception{
  var mock=new TableExportJdbcMocks(type);mock.rows=1;mock.sqlType=Types.VARBINARY;var closes=new AtomicInteger();mock.value=new ByteArrayInputStream(new byte[]{0,1,(byte)255}){public void close(){closes.incrementAndGet();}};Path target=root.resolve("binary");TableExporter.export(mock.manager,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,target.toFile());assertTrue(Files.readString(target).contains(type==DbType.ORACLE?"HEXTORAW('0001ff')":"decode('0001ff', 'hex')"));assertEquals(1,closes.get());
 }
}