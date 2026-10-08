package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class StrictTableDdlIntegrityTest {
 @TempDir Path root;
 static final TableRef TABLE=new TableRef("S.ch\"ema","T.ab\"le");
 void export(TableExportJdbcMocks mock,Path target,ExportContent content)throws Exception{TableExporter.export(mock.manager,"synthetic",TABLE,content,ExportFormat.SQL,target.toFile());}
 @ParameterizedTest @ValueSource(booleans={false,true})
 void postgresCatalogStatementsHaveFullTableConstraintJoinBindingsAndIndependentDataCursor(boolean keys)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=2;if(keys)mock.ddlKeys=List.of(Map.of("constraint_name","Pk.Name","column_name","value"));
  Path target=root.resolve("ddl");export(mock,target,ExportContent.BOTH);String ddl=Files.readString(target);
  assertTrue(ddl.contains("CREATE TABLE \"S.ch\"\"ema\".\"T.ab\"\"le\""));assertEquals(keys,ddl.contains("PRIMARY KEY"));assertEquals(2,ddl.split("'complete value'",-1).length-1);
  assertEquals(List.of(StrictTableDdl.COLUMNS,StrictTableDdl.PRIMARY_KEY,"SELECT * FROM \"S.ch\"\"ema\".\"T.ab\"\"le\""),mock.queries);
  for(String join:List.of("constraint_catalog","constraint_schema","constraint_name","table_catalog","table_schema","table_name"))assertTrue(mock.queries.get(1).contains("tc."+join+" = kcu."+join));
  var owner=mock.owners.getFirst();assertEquals(3,owner.statements.size());for(int i=0;i<2;i++){var stmt=owner.statements.get(i);assertEquals(Map.of(1,TABLE.schema(),2,TABLE.name()),stmt.bindings);assertEquals(16,stmt.fetchSize);assertTrue(stmt.timeout>0);assertTrue(stmt.closed);}
  assertEquals(1,owner.rollbacks.get());assertTrue(owner.closed);
 }
 @ParameterizedTest @ValueSource(strings={"ddl","execute","getter","next"})
 void catalogFailureFailsClosedBeforeOutputPublication(String stage)throws Exception{
  var mock=new TableExportJdbcMocks();mock.failure=stage;Path target=Files.writeString(root.resolve("old"),"old bytes");
  assertThrows(TableExportFailure.class,()->export(mock,target,ExportContent.STRUCTURE));assertEquals("old bytes",Files.readString(target));assertTrue(mock.owners.getFirst().closed);
 }
 @Test void absentColumnsOrUnsupportedCatalogTypeCannotProduceSuccessComment()throws Exception{
  for(List<Map<String,Object>> catalog:List.of(List.<Map<String,Object>>of(),List.of(Map.<String,Object>of("column_name","value","data_type","USER-DEFINED")))){
   var mock=new TableExportJdbcMocks();mock.ddlColumns=catalog;Path target=Files.writeString(root.resolve(UUID.randomUUID().toString()),"old bytes");assertThrows(TableExportFailure.class,()->export(mock,target,ExportContent.STRUCTURE));assertEquals("old bytes",Files.readString(target));
  }
 }
 @ParameterizedTest @ValueSource(strings={"CREATE TABLE synthetic (value VARCHAR2(10))","","-- SYNTHETIC_PRIVATE_FALLBACK"})
 void oracleGetDdlOnlyTableHasOwnedClobAndNoFallback(String ddl)throws Exception{
  var mock=new TableExportJdbcMocks(DbType.ORACLE);mock.rows=1;var frees=new AtomicInteger();var closes=new AtomicInteger();
  Reader reader=new StringReader(ddl){public void close(){closes.incrementAndGet();super.close();}};
  mock.value=Proxy.newProxyInstance(Clob.class.getClassLoader(),new Class<?>[]{Clob.class},(p,m,a)->switch(m.getName()){case "getCharacterStream"->reader;case "free"->{frees.incrementAndGet();yield null;}default->throw new AssertionError("No fallback/getSubString");});
  Path target=Files.writeString(root.resolve("oracle"),"old bytes");if(ddl.startsWith("CREATE"))export(mock,target,ExportContent.STRUCTURE);else assertThrows(TableExportFailure.class,()->export(mock,target,ExportContent.STRUCTURE));
  assertEquals(List.of("SET TRANSACTION READ ONLY","SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL"),mock.queries);
  var owner=mock.owners.getFirst();var stmt=owner.statements.getLast();assertEquals(Map.of(1,"TABLE",2,TABLE.name(),3,TABLE.schema()),stmt.bindings);assertTrue(stmt.timeout>0);assertEquals(1,frees.get());assertEquals(1,closes.get());assertTrue(owner.closed);assertEquals(1,owner.rollbacks.get());
  if(!ddl.startsWith("CREATE"))assertEquals("old bytes",Files.readString(target));
 }
}