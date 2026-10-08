package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.TableRef;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TableExporter/strict catalog/writer/publisher contract; no JDBC driver/network. */
class StrictTableDdlMetadataTest {
 @TempDir Path root;
 static Map<String,Object> column(String type){var column=new HashMap<String,Object>();column.put("column_name","value");column.put("data_type",type);column.put("is_nullable","YES");column.put("is_identity","NO");column.put("is_generated","NEVER");return column;}
 static Stream<Arguments> unsupported(){
  var identity=column("bigint");identity.put("is_identity","YES");identity.put("identity_generation","ALWAYS");
  var generated=column("integer");generated.put("is_generated","ALWAYS");generated.put("generation_expression","1 + 1");
  var domain=column("text");domain.put("domain_catalog","synthetic");domain.put("domain_schema","s");domain.put("domain_name","restricted_text");
  var bit=column("bit");bit.put("character_maximum_length",8L);
  var timestamp=column("timestamp without time zone");timestamp.put("datetime_precision",3L);
  var collation=column("text");collation.put("collation_catalog","synthetic");collation.put("collation_schema","pg_catalog");collation.put("collation_name","C");
  return Stream.of(Arguments.of("identity",identity),Arguments.of("generated",generated),Arguments.of("domain",domain),Arguments.of("bit8",bit),Arguments.of("timestamp3",timestamp),Arguments.of("collation",collation)).flatMap(a->Stream.of(ExportContent.STRUCTURE,ExportContent.BOTH).map(content->Arguments.of(a.get()[0],a.get()[1],content)));
 }
 static Stream<Arguments> malformed(){
  return Stream.of("data_type","is_identity","is_generated","is_nullable").flatMap(field->Stream.of(false,true).map(missing->{var map=column("text");if(missing)map.remove(field);else map.put(field,"SYNTHETIC_UNKNOWN_FLAG");return Arguments.of(field+(missing?"-missing":"-unknown"),map,ExportContent.STRUCTURE);}));
 }
 @ParameterizedTest @MethodSource("malformed")
 void missingAndUnknownRequiredFlagsFailClosed(String scenario,Map<String,Object> column,ExportContent content)throws Exception{
  unsupportedColumnDefinitionFailsBeforeOutputOpenAndMove(scenario,column,content);
 }
 static Stream<Arguments> modifiers(){
  var character=TableExportJdbcMocks.basicColumn("character");character.put("character_maximum_length",8L);
  var varying=TableExportJdbcMocks.basicColumn("character varying");varying.put("character_maximum_length",32L);
  var numeric=TableExportJdbcMocks.basicColumn("numeric");numeric.put("numeric_precision",10L);numeric.put("numeric_scale",3L);
  var negative=TableExportJdbcMocks.basicColumn("numeric");negative.put("numeric_precision",2L);negative.put("numeric_scale",-3L);
  var overPrecision=TableExportJdbcMocks.basicColumn("numeric");overPrecision.put("numeric_precision",3L);overPrecision.put("numeric_scale",5L);
  return Stream.of(Arguments.of(character,"character(8)"),Arguments.of(varying,"character varying(32)"),Arguments.of(TableExportJdbcMocks.basicColumn("character varying"),"character varying"),Arguments.of(numeric,"numeric(10,3)"),Arguments.of(negative,"numeric(2,-3)"),Arguments.of(overPrecision,"numeric(3,5)"),Arguments.of(TableExportJdbcMocks.basicColumn("numeric"),"numeric"));
 }
 @ParameterizedTest @MethodSource("modifiers")
 void admittedModifiersAreRenderedCompletelyBeforeBasicPrimaryKeyAndData(Map<String,Object> column,String definition)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.ddlColumns=List.of(column);mock.ddlKeys=List.of(Map.of("constraint_name","pk","column_name","value"));Path target=root.resolve("success");TableExporter.export(mock.manager,"synthetic",new TableRef("s","t"),ExportContent.BOTH,ExportFormat.SQL,target.toFile());String sql=Files.readString(target);
  assertTrue(sql.contains("\"value\" "+definition+",\n"));assertTrue(sql.contains("PRIMARY KEY (\"value\")"));assertTrue(sql.contains("'complete value'"));assertEquals(3,mock.executions.get());assertTrue(mock.owners.getFirst().closed);assertEquals(0,mock.exportSubscriptions());
 }
 @ParameterizedTest @ValueSource(strings={"boolean","smallint","integer","bigint","real","double precision","text","bytea","date","uuid","json","jsonb"})
 void otherAdmittedSimpleTypesKeepBasicColumnDefinitions(String type)throws Exception{
  var mock=new TableExportJdbcMocks();mock.ddlColumns=List.of(TableExportJdbcMocks.basicColumn(type));Path target=root.resolve("simple");TableExporter.export(mock.manager,"synthetic",new TableRef("s","t"),ExportContent.STRUCTURE,ExportFormat.SQL,target.toFile());assertTrue(Files.readString(target).contains("\"value\" "+type+"\n"));assertTrue(mock.owners.getFirst().closed);
 }
 @ParameterizedTest @ValueSource(strings={"bit varying","time without time zone","time with time zone","timestamp with time zone","interval","USER-DEFINED","ARRAY","SYNTHETIC_FUTURE_TYPE"})
 void unsupportedTimeIntervalBitAndFutureTypesNeverPassThroughArbitraryTypeText(String type)throws Exception{
  unsupportedColumnDefinitionFailsBeforeOutputOpenAndMove(type,column(type),ExportContent.BOTH);
 }
 @ParameterizedTest @ValueSource(strings={"identity_generation","generation_expression","domain_catalog","domain_schema","domain_name","collation_catalog","collation_schema","collation_name","interval_type"})
 void isolatedOrContradictoryMetadataNeverSilentlyDropsColumnSemantics(String field)throws Exception{
  var col=column("text");col.put(field,"synthetic");unsupportedColumnDefinitionFailsBeforeOutputOpenAndMove(field,col,ExportContent.BOTH);
 }
 static Stream<Arguments> invalidModifiers(){
  var badChar=TableExportJdbcMocks.basicColumn("character");badChar.remove("character_maximum_length");
  var zeroChar=TableExportJdbcMocks.basicColumn("character varying");zeroChar.put("character_maximum_length",0L);
  var unknownNumeric=TableExportJdbcMocks.basicColumn("numeric");unknownNumeric.remove("numeric_precision_radix");
  var missingScale=TableExportJdbcMocks.basicColumn("numeric");missingScale.put("numeric_precision",10L);
  var implicitScale=TableExportJdbcMocks.basicColumn("numeric");implicitScale.put("numeric_scale",1L);
  var hugeNumeric=TableExportJdbcMocks.basicColumn("numeric");hugeNumeric.put("numeric_precision",1001L);hugeNumeric.put("numeric_scale",0L);
  var wrongDate=TableExportJdbcMocks.basicColumn("date");wrongDate.put("datetime_precision",3L);
  var interval=column("text");interval.put("interval_precision",1L);
  var datetime=column("text");datetime.put("datetime_precision",3L);
  return Stream.of(badChar,zeroChar,unknownNumeric,missingScale,implicitScale,hugeNumeric,wrongDate,interval,datetime).map(map->Arguments.of(map));
 }
 @ParameterizedTest @MethodSource("invalidModifiers")
 void invalidOrIncompleteModifiersFailBeforeOutput(Map<String,Object> column)throws Exception{
  unsupportedColumnDefinitionFailsBeforeOutputOpenAndMove("invalid-modifier",column,ExportContent.STRUCTURE);
 }
 @ParameterizedTest @ValueSource(strings={"is_identity","is_generated","domain_name","collation_name","datetime_precision","interval_type","numeric_precision_radix"})
 void requiredMetadataReadFailureIsFixedStructureAndStillSettlesAllOwnedJdbc(String field)throws Exception{
  var mock=new TableExportJdbcMocks();mock.catalogFailureLabel=field;Path target=Files.writeString(root.resolve("failure"),"old bytes");Path neighbor=Files.writeString(root.resolve("neighbor"),"neighbor bytes");var opens=new AtomicInteger();var moves=new AtomicInteger();var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();
  var req=new TableExporter.Request("synthetic",new TableRef("s","t"),ExportContent.BOTH,ExportFormat.SQL,SafeResultFilePublisher.capture(target));var publisher=new SafeResultFilePublisher((a,b)->moves.incrementAndGet(),Files::deleteIfExists,p->{});
  var failure=assertThrows(TableExportFailure.class,()->TableExporter.export(mock.manager,req,new ResultExportOperation(),publisher,(a,b,c,d,e)->fail("No dump"),p->{opens.incrementAndGet();return Files.newOutputStream(p);},new TableExportJdbcJob.Policy(Duration.ofSeconds(10),Duration.ZERO,Duration.ZERO,System::nanoTime,receipt::set)));
  assertEquals(TableExportFailure.Kind.STRUCTURE,failure.kind());assertFalse(failure.getMessage().contains("SYNTHETIC_PRIVATE"));assertEquals(0,opens.get());assertEquals(0,moves.get());assertEquals("old bytes",Files.readString(target));assertEquals("neighbor bytes",Files.readString(neighbor));assertTrue(receipt.get().physicallySettled());assertFalse(receipt.get().activeStatement());assertEquals(0,mock.exportSubscriptions());
 }
 @Test void cancellationInsideLateRequiredMetadataGetterWinsAfterOwnedResourcesSettle()throws Exception{
  var mock=new TableExportJdbcMocks();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);mock.beforeCatalogGet=field->{if(field.equals("is_identity")){entered.countDown();TableExportJdbcOwnershipTest.await(release);}};
  Path target=Files.writeString(root.resolve("cancel"),"old bytes");var op=new ResultExportOperation();var receipt=new AtomicReference<TableExportJdbcJob.Receipt>();var opens=new AtomicInteger();var req=new TableExporter.Request("synthetic",new TableRef("s","t"),ExportContent.BOTH,ExportFormat.SQL,SafeResultFilePublisher.capture(target));
  var worker=new FutureTask<Throwable>(()->{try{TableExporter.export(mock.manager,req,op,new SafeResultFilePublisher(),(a,b,c,d,e)->fail("No dump"),p->{opens.incrementAndGet();return Files.newOutputStream(p);},new TableExportJdbcJob.Policy(Duration.ofSeconds(10),Duration.ZERO,Duration.ZERO,System::nanoTime,receipt::set));return null;}catch(Throwable t){return t;}});Thread.ofVirtual().start(worker);
  try{TableExportJdbcOwnershipTest.await(entered);assertTrue(op.cancel());TableExportJdbcOwnershipTest.eventually(op::cleanupPending);assertFalse(worker.isDone());var busy=assertThrows(SafeResultFilePublisher.Failure.class,()->new SafeResultFilePublisher().publish(SafeResultFilePublisher.capture(target),new ResultExportOperation(),(p,o)->fail("No busy producer")));assertEquals(SafeResultFilePublisher.Stage.TARGET_BUSY,busy.stage());release.countDown();assertInstanceOf(CancellationException.class,worker.get(8,TimeUnit.SECONDS));assertEquals(0,opens.get());assertEquals("old bytes",Files.readString(target));assertTrue(receipt.get().physicallySettled());assertFalse(receipt.get().activeStatement());assertEquals(0,mock.exportSubscriptions());System.out.println("JDBC_DDL_METADATA cancel=true outputOpen=0 receipt="+receipt.get());}
  finally{release.countDown();worker.get(8,TimeUnit.SECONDS);}
 }
 @ParameterizedTest @EnumSource(value=ExportFormat.class,names={"SQL","XLSX","PG_DUMP"})
 void unsupportedDdlColumnsDoNotExpandDataOnlyExcelOrDumpContracts(ExportFormat format)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.ddlColumns=List.of(column("SYNTHETIC_FUTURE_TYPE"));mock.catalogFailureLabel="is_identity";Path target=root.resolve("data");var op=new ResultExportOperation();var req=new TableExporter.Request("synthetic",new TableRef("s","t"),ExportContent.DATA,format,SafeResultFilePublisher.capture(target));
  TableExporter.export(mock.manager,req,op,new SafeResultFilePublisher(),(a,b,c,d,e)->Files.writeString(e.toPath(),"synthetic dump"),Files::newOutputStream);assertTrue(op.published());assertEquals(format==ExportFormat.PG_DUMP?0:1,mock.executions.get());assertTrue(mock.queries.stream().noneMatch(sql->sql.contains("information_schema")));assertEquals(0,mock.exportSubscriptions());
 }
 @ParameterizedTest @MethodSource("unsupported")
 void unsupportedColumnDefinitionFailsBeforeOutputOpenAndMove(String scenario,Map<String,Object> column,ExportContent content)throws Exception{
  var mock=new TableExportJdbcMocks();mock.rows=1;mock.ddlColumns=List.of(column);Path target=Files.writeString(root.resolve("old"),"old bytes");Path neighbor=Files.writeString(root.resolve("neighbor"),"neighbor bytes");var opens=new AtomicInteger();var moves=new AtomicInteger();var op=new ResultExportOperation();
  var req=new TableExporter.Request("synthetic",new TableRef("s","t"),content,ExportFormat.SQL,SafeResultFilePublisher.capture(target));
  var publisher=new SafeResultFilePublisher((from,to)->{moves.incrementAndGet();Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);},Files::deleteIfExists,p->{});
  var failure=assertThrows(TableExportFailure.class,()->TableExporter.export(mock.manager,req,op,publisher,(a,b,c,d,e)->fail("No dump"),p->{opens.incrementAndGet();return Files.newOutputStream(p);}));
  assertEquals(TableExportFailure.Kind.STRUCTURE,failure.kind());assertEquals(0,opens.get());assertEquals(0,moves.get());assertFalse(op.published());assertEquals("old bytes",Files.readString(target));assertEquals("neighbor bytes",Files.readString(neighbor));assertEquals(1,mock.owners.size());var owner=mock.owners.getFirst();assertTrue(owner.closed);assertEquals(1,owner.rollbacks.get());assertTrue(owner.statements.stream().allMatch(s->s.closed));assertEquals(0,mock.exportSubscriptions());
  try(var files=Files.list(root)){assertEquals(2,files.count());}System.out.println("JDBC_DDL_METADATA scenario="+scenario+" content="+content+" outputOpen=0 move=0 jdbcClosed=true listeners=0");
 }
}
