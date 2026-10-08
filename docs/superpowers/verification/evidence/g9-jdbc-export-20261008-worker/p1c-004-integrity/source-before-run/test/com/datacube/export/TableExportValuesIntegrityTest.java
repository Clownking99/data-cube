package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.*;
import com.datacube.provider.postgres.PgSqlDialect;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class TableExportValuesIntegrityTest {
 @TempDir Path root;
 static Stream<Arguments> rejected(){return Stream.of(new Object(),Double.NaN,Double.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,new BigInteger("1234567890123456"),new BigDecimal("0.1234567890123456"),new byte[]{1},new java.util.Date()).map(Arguments::of);}
 @ParameterizedTest @MethodSource("rejected") void xlsxRejectsUnknownNonfiniteImpreciseBinaryWithoutPublishing(Object value)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.value=value;Path target=Files.writeString(root.resolve("old"),"old bytes");
  assertThrows(TableExportFailure.class,()->TableExporter.export(mock.manager,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.XLSX,target.toFile()));assertEquals("old bytes",Files.readString(target));assertTrue(mock.owners.getFirst().closed);
 }
 @ParameterizedTest @ValueSource(ints={Types.ARRAY,Types.STRUCT,Types.REF,Types.ROWID,Types.JAVA_OBJECT})
 void structuredTypesRejectedBeforeGetter(int kind)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.sqlType=kind;mock.beforeGet=()->fail("Unsupported getter must not run");
  assertThrows(TableExportFailure.class,()->TableExporter.export(mock.manager,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,root.resolve("result").toFile()));assertEquals(0,mock.getters.get());
 }
 @Test void textBoundsUseUtf8BytesAndXlsxUtf16UnitsAndRejectUnpairedSurrogate(){
  assertEquals(4,TableExportValues.textBytes("😀",4,2));assertThrows(TableExportFailure.class,()->TableExportValues.textBytes("😀",3,2));assertThrows(TableExportFailure.class,()->TableExportValues.textBytes("😀",4,1));
  assertThrows(TableExportFailure.class,()->TableExportValues.textBytes("\uD800",100,100));assertThrows(TableExportFailure.class,()->TableExportValues.textBytes("\uDC00",100,100));
  assertEquals(32767,TableExportValues.textBytes("x".repeat(32767),TableExportValues.VALUE_BYTES,TableExportValues.XLSX_TEXT));assertThrows(TableExportFailure.class,()->TableExportValues.textBytes("x".repeat(32768),TableExportValues.VALUE_BYTES,TableExportValues.XLSX_TEXT));
  TableExportValues.rowNumber(1048575,ExportFormat.XLSX);assertThrows(TableExportFailure.class,()->TableExportValues.rowNumber(1048576,ExportFormat.XLSX));
 }
 @Test void finiteWhitelistHasNoImplicitNumericOrTextFallback(){
  var d=new PgSqlDialect();for(Object value:Arrays.asList(null,"x",true,1,123456789012345L,new BigDecimal("0.1"),java.sql.Date.valueOf("2026-10-09")))assertDoesNotThrow(()->TableExportValues.scalar(value,ExportFormat.XLSX,DbType.POSTGRESQL,d));
  assertThrows(TableExportFailure.class,()->TableExportValues.scalar(1e-309,ExportFormat.XLSX,DbType.POSTGRESQL,d));assertThrows(TableExportFailure.class,()->TableExportValues.scalar(new BigDecimal("1E-1000001"),ExportFormat.SQL,DbType.POSTGRESQL,d));
  assertThrows(TableExportFailure.class,()->TableExportValues.scalar(new BigInteger("1") {public String toString(){throw new AssertionError("Unknown subclass conversion");}},ExportFormat.SQL,DbType.POSTGRESQL,d));
  assertThrows(TableExportFailure.class,()->TableExportValues.scalar(new byte[2001],ExportFormat.SQL,DbType.ORACLE,d));
 }
 @ParameterizedTest @ValueSource(booleans={false,true})
 void incrementalLobLimitStopsAtBoundAndReleasesBeforePublishing(boolean binary)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.sqlType=binary?Types.VARBINARY:Types.LONGVARCHAR;var closes=new java.util.concurrent.atomic.AtomicInteger();var read=new java.util.concurrent.atomic.AtomicInteger();
  if(binary)mock.value=new InputStream(){public int read(){read.incrementAndGet();return 1;}public int read(byte[] b,int o,int n){read.addAndGet(n);Arrays.fill(b,o,o+n,(byte)1);return n;}public void close(){closes.incrementAndGet();}};
  else mock.value=new Reader(){public int read(char[] b,int o,int n){read.addAndGet(n);Arrays.fill(b,o,o+n,'x');return n;}public void close(){closes.incrementAndGet();}};
  Path target=Files.writeString(root.resolve("limit"),"old bytes");var error=assertThrows(TableExportFailure.class,()->TableExporter.export(mock.manager,"synthetic",new TableRef("s","t"),ExportContent.DATA,ExportFormat.SQL,target.toFile()));
  assertEquals(TableExportFailure.Kind.LIMIT,error.kind());assertTrue(read.get()<=TableExportValues.VALUE_BYTES+8192);assertEquals(1,closes.get());assertEquals("old bytes",Files.readString(target));assertTrue(mock.owners.getFirst().closed);
 }
}