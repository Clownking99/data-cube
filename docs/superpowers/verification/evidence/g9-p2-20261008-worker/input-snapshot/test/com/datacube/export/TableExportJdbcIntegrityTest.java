package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.*;
import com.datacube.spi.model.*;
import java.io.*;
import java.lang.reflect.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class TableExportJdbcIntegrityTest {
 @TempDir Path root;
 final TableRef table=new TableRef("S.ch\"ema","T.ab\"le");
 TableExporter.Request request(TableExportJdbcMocks mock,Path target,ExportFormat format,ExportContent content)throws Exception{return new TableExporter.Request("synthetic",table,content,format,SafeResultFilePublisher.capture(target));}
 void export(TableExportJdbcMocks mock,Path target,ExportFormat format,ExportContent content)throws Exception{TableExporter.export(mock.manager,request(mock,target,format,content),new ResultExportOperation());}
 @ParameterizedTest @CsvSource({"SQL,0","XLSX,0","SQL,501","XLSX,501"})
 void oneMetadataCursorIncludesEmptyHeadersAndAllRowsWithoutPagingOrSharedMutation(ExportFormat format,int count)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=count;mock.label="Actual.Header";
  var shared=mock.manager.acquire("synthetic");shared.setAutoCommit(false);
  mock.beforeStatement=sql->{mock.rows=0;mock.value="changed later";};
  Path target=root.resolve(format+"-"+count);export(mock,target,format,ExportContent.DATA);
  var owned=mock.owners.getLast();assertEquals(2,mock.owners.size());assertFalse(mock.owners.getFirst().closed);assertEquals(0,mock.owners.getFirst().rollbacks.get());
  assertTrue(owned.readOnly);assertFalse(owned.autoCommit);assertEquals(Connection.TRANSACTION_READ_COMMITTED,owned.isolation);assertTrue(owned.closed);assertEquals(1,owned.rollbacks.get());
  assertEquals(1,mock.queries.size());assertEquals("SELECT * FROM \"S.ch\"\"ema\".\"T.ab\"\"le\"",mock.queries.getFirst());assertEquals(0,mock.pages.get());
  var stmt=owned.statements.getFirst();assertEquals(ResultSet.TYPE_FORWARD_ONLY,stmt.mode);assertEquals(ResultSet.CONCUR_READ_ONLY,stmt.concurrency);assertEquals(16,stmt.fetchSize);assertTrue(stmt.timeout>0);
  assertEquals(count,mock.getters.get());assertTrue(stmt.closed);assertTrue(stmt.cursor.closed);
  if(format==ExportFormat.XLSX){try(var zip=new ZipFile(target.toFile())){String xml=new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),StandardCharsets.UTF_8);assertTrue(xml.contains("Actual.Header"));assertEquals(count+1,xml.split("<row r=",-1).length-1);if(count>0)assertTrue(xml.contains("complete value"));assertFalse(xml.contains("changed later"));}}
  else{String sql=Files.readString(target);if(count>0){assertTrue(sql.contains("Actual.Header"));assertEquals(count,sql.split("'complete value'",-1).length-1);}else assertFalse(sql.contains("INSERT"));assertFalse(sql.contains("changed later"));}
 }
 @ParameterizedTest @EnumSource(ExportFormat.class)
 void selectedConfigurationCannotRebindAfterAbaAcrossAllThreeFormats(ExportFormat format)throws Exception{
  var mock=new TableExportJdbcMocks();Path target=Files.writeString(root.resolve("aba"),"old bytes");
  try(var selection=TableExporter.capture(mock.manager,"synthetic")){
   var req=new TableExporter.Request("synthetic",table,ExportContent.DATA,format,SafeResultFilePublisher.capture(target),selection.snapshot());
   mock.manager.register(mock.config("changed"));mock.manager.register(mock.config("synthetic"));
   var failure=assertThrows(TableExportFailure.class,()->TableExporter.export(mock.manager,req,selection.operation(),new SafeResultFilePublisher(),(a,b,c,d,e)->fail("No dump"),Files::newOutputStream));
   assertEquals(TableExportFailure.Kind.TARGET_CHANGED,failure.kind());assertEquals(0,mock.owners.size());assertEquals("old bytes",Files.readString(target));
  }
 }
 @Test void pgDumpUsesSelectionSnapshotAndNeverOpensJdbc()throws Exception{
  var mock=new TableExportJdbcMocks();Path target=root.resolve("dump");try(var selected=TableExporter.capture(mock.manager,"synthetic")){
   var req=new TableExporter.Request("synthetic",table,ExportContent.DATA,ExportFormat.PG_DUMP,SafeResultFilePublisher.capture(target),selected.snapshot());
   TableExporter.export(mock.manager,req,selected.operation(),new SafeResultFilePublisher(),(cfg,password,t,content,out)->{assertSame(selected.snapshot().config(),cfg);assertEquals(table,t);Files.writeString(out.toPath(),"synthetic dump");},Files::newOutputStream);
   assertTrue(selected.operation().published());assertEquals(0,mock.owners.size());assertEquals("synthetic dump",Files.readString(target));
  }
 }
 @Test void publicationClaimWinsLaterSourceInvalidationWithoutHoldingManagerDuringMove()throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;Path target=Files.writeString(root.resolve("winner"),"old bytes");var op=new ResultExportOperation();
  var publisher=new SafeResultFilePublisher((from,to)->{assertFalse(Thread.holdsLock(mock.manager));mock.manager.register(mock.config("changed"));assertFalse(op.cancel());Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);},Files::deleteIfExists,p->{});
  TableExporter.export(mock.manager,request(mock,target,ExportFormat.SQL,ExportContent.DATA),op,publisher,(a,b,c,d,e)->fail("No dump"),Files::newOutputStream);
  assertTrue(op.published());assertTrue(Files.readString(target).contains("complete value"));
 }
 @ParameterizedTest @ValueSource(strings={"open","setup","execute","next","getter","metadata","rollback","rs-close","stmt-close"})
 void eachJdbcFailurePreservesTargetAndOtherSessions(String stage)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.manager.acquire("synthetic");mock.failure=stage;Path target=Files.writeString(root.resolve(stage),"old bytes");
  var failure=assertThrows(Exception.class,()->export(mock,target,ExportFormat.SQL,ExportContent.DATA));assertFalse(failure.getMessage().contains("SYNTHETIC_PRIVATE"));
  assertEquals("old bytes",Files.readString(target));assertFalse(mock.owners.getFirst().closed);assertEquals(0,mock.owners.getFirst().rollbacks.get());if(mock.owners.size()>1)assertTrue(mock.owners.getLast().closed);
 }
 @Test void eachRowReachesRealSqlSinkBeforeNextCursorAdvance()throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=3;var rendered=new AtomicInteger();var base=mock.provider;
  SqlDialect delegate=base.dialect();SqlDialect dialect=(SqlDialect)Proxy.newProxyInstance(SqlDialect.class.getClassLoader(),new Class<?>[]{SqlDialect.class},(p,m,a)->{
   if(m.getName().equals("sqlLiteral")){assertEquals(mock.getters.get(),rendered.get()+1);rendered.incrementAndGet();}
   try{return m.invoke(delegate,a);}catch(InvocationTargetException e){throw e.getCause();}
  });
  mock.provider=(DatabaseProvider)Proxy.newProxyInstance(DatabaseProvider.class.getClassLoader(),new Class<?>[]{DatabaseProvider.class},(p,m,a)->{if(m.getName().equals("dialect"))return dialect;try{return m.invoke(base,a);}catch(InvocationTargetException e){throw e.getCause();}});
  mock.beforeNext=()->assertEquals(mock.getters.get(),rendered.get(),"No prefetch of rows before sink returns");export(mock,root.resolve("sink"),ExportFormat.SQL,ExportContent.DATA);assertEquals(3,rendered.get());
 }
}