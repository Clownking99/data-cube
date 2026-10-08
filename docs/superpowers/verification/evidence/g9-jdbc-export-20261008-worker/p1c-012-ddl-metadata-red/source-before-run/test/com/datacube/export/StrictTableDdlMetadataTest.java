package com.datacube.export;

import com.datacube.service.TableExportJdbcMocks;
import com.datacube.spi.model.TableRef;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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