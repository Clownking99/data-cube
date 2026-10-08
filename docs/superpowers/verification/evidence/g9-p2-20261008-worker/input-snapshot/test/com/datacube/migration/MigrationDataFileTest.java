package com.datacube.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationDataFileTest {
    @TempDir Path root;
    private final List<MigrationPlan.Column> columns=List.of(
            new MigrationPlan.Column("ID","id","NUMBER","NUMERIC",false),
            new MigrationPlan.Column("LABEL","label","VARCHAR2","TEXT",true));
    private MigrationPlan.Table table() { return new MigrationPlan.Table("ITEMS","items",columns,List.of("id"),null,false,null,List.of()); }

    @Test void roundTripsLiteralsWithoutExecutingEmbeddedSqlAndPreservesUnicodeWhitespace() throws Exception {
        List<Object> values=List.of(new BigDecimal("-12345678901234567890.0000123"),"O'Reilly; DROP TABLE x; --\n\t\\中文🙂");
        assertEquals(values,MigrationDataFile.parse(MigrationDataFile.format("items",columns,values),table()));
        assertEquals(Arrays.asList(new BigDecimal("0"),null),MigrationDataFile.parse("INSERT INTO items (id, label) VALUES (0, NULL);",table()));
    }
    @Test void rejectsExpressionsCrossTargetsWrongColumnsTrailingSqlAndAmbiguousLegacyStrings() {
        for(String text:List.of(
                "INSERT INTO items (id,label) VALUES (nextval('seq'), 'x');",
                "INSERT INTO other (id,label) VALUES (1, 'x');",
                "INSERT INTO public.items (id,label) VALUES (1, 'x');",
                "INSERT INTO items (label,id) VALUES ('x', 1);",
                "INSERT INTO items (id,label) VALUES (1, 'x'); DELETE FROM items;",
                "INSERT INTO items (id,label) VALUES (NULL, 'x');",
                "INSERT INTO items (id,label) VALUES (1, 'x\\n');",
                "INSERT INTO items (id,label) VALUES (1, E'\\x01');",
                "INSERT INTO items (id,label) VALUES (1e9999999, 'x');",
                "INSERT INTO items (id,label) VALUES ('1', 'x');",
                "INSERT INTO items (id,label) VALUES (1, 2);",
                "\"INSERT\" INTO items (id,label) VALUES (1, 'x');",
                "INSERT INTO \"ITEMS\" (id,label) VALUES (1, 'x');",
                "INSERT INTO items (id,label) VALUES (1, 'unclosed);")) {
            assertThrows(IOException.class,() -> MigrationDataFile.parse(text,table()),text);
        }
    }
    @Test void multisetDigestIgnoresOrderAndNumericScaleButDetectsNullsDuplicatesAndTextChanges() throws Exception {
        var a=new MigrationDataFile.Accumulator(2); a.add(List.of(new BigDecimal("1.00"),"x ")); a.add(Arrays.asList(new BigDecimal("2"),null));
        var b=new MigrationDataFile.Accumulator(2); b.add(Arrays.asList(new BigDecimal("2.0"),null)); b.add(List.of(new BigDecimal("1"),"x "));
        assertEquals(a.finish(),b.finish()); assertEquals(2,a.finish().rows()); assertEquals(List.of(0L,1L),a.finish().nulls());
        var changed=new MigrationDataFile.Accumulator(2); changed.add(List.of(new BigDecimal("1"),"x")); changed.add(Arrays.asList(new BigDecimal("2"),null));
        assertNotEquals(a.finish().multisetDigest(),changed.finish().multisetDigest());
        b.add(List.of(new BigDecimal("1"),"x ")); assertNotEquals(a.finish(),b.finish());
    }
    @Test void consumesOnlyReviewedFileAndDetectsMutationDuringStreamBeforeReturningSuccess() throws Exception {
        Path data=Files.createDirectories(root.resolve("data")).resolve("items.sql");
        Files.writeString(data,"-- synthetic\nINSERT INTO items (id,label) VALUES (1,E'x');\nCOMMIT;\n");
        MigrationCancellation cancellation=new MigrationCancellation(); var snapshot=MigrationFiles.inspect(root,"items",cancellation);
        List<List<Object>> rows=new ArrayList<>(); var statistics=MigrationDataFile.read(snapshot,table(),cancellation,rows::add);
        assertEquals(List.of(List.of(new BigDecimal("1"),"x")),rows); assertEquals(1,statistics.rows());
        assertThrows(IOException.class,() -> MigrationDataFile.read(snapshot,table(),cancellation,row -> Files.writeString(data,"INSERT INTO items (id,label) VALUES (2,E'x');")));
        assertThrows(IOException.class,() -> MigrationDataFile.read(snapshot,table(),cancellation,rows::add));
        assertEquals(1,rows.size());
    }
    @Test void rejectsMalformedUtf8OversizedRowsAndPathTraversal() throws Exception {
        Path data=Files.createDirectories(root.resolve("data")).resolve("items.sql");
        Files.write(data,new byte[]{(byte)0xc3,0x28});
        var cancellation=new MigrationCancellation(); var malformed=MigrationFiles.inspect(root,"items",cancellation);
        assertThrows(IOException.class,() -> MigrationDataFile.read(malformed,table(),cancellation,row -> fail("No row should be emitted")));
        Files.writeString(data,"x".repeat(MigrationDataFile.MAX_LINE_CHARS+1),StandardCharsets.UTF_8);
        var oversized=MigrationFiles.inspect(root,"items",cancellation);
        assertThrows(IOException.class,() -> MigrationDataFile.read(oversized,table(),cancellation,row -> fail("No row should be emitted")));
        assertThrows(IOException.class,() -> MigrationFiles.inspect(root,"../items",cancellation));
    }
    @Test void cancellationStopsReadingAndEmptyFilesProduceKnownZeroRows() throws Exception {
        Files.createDirectories(root.resolve("data")); Files.writeString(root.resolve("data/items.sql"),"COMMIT;\n");
        var cancellation=new MigrationCancellation(); var input=MigrationFiles.inspect(root,"items",cancellation);
        assertEquals(0,MigrationDataFile.read(input,table(),cancellation,row -> fail()).rows());
        cancellation.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,() -> MigrationDataFile.read(input,table(),cancellation,row -> fail()));
    }
}
