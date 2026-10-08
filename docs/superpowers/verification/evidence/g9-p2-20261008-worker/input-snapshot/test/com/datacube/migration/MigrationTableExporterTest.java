package com.datacube.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTableExporterTest {
    @TempDir Path root;
    @Test void preservesNumbersTextAndRecordsSingleTableWindowBoundToPublishedBytes() throws Exception {
        Db db=new Db();db.values.add(List.of(new BigDecimal("12345678901234567890.1234"),"'\\\n中文🙂"));
        var cancellation=new MigrationCancellation();var exported=new MigrationTableExporter(cancellation).export(db.connection(),"OWNER","ITEMS",root);
        assertEquals(1,exported.rows());assertEquals(1,db.rollbacks);assertEquals(1,db.dataReads);assertEquals("readonly",db.events.getFirst());
        assertTrue(db.events.indexOf("transaction")<db.events.indexOf("source-query"));
        var file=MigrationFiles.inspect(root,"items",cancellation);var evidence=MigrationExportEvidence.read(file.path());
        assertEquals(file.bytes(),exported.bytes());assertEquals(file.sha256(),evidence.dataSha256());assertEquals(1,evidence.rows());
        assertFalse(evidence.finished().isBefore(evidence.began()));
        var table=new MigrationPlan.Table("ITEMS","items",db.columns(),List.of("id"),null,false,file,List.of());
        List<List<Object>> received=new ArrayList<>();MigrationDataFile.read(file,table,cancellation,received::add);assertEquals(db.values,received);
        assertEquals(MigrationExportEvidence.scope(MigrationPreflight.fingerprint("source-id"),"OWNER"),evidence.sourceScope());
    }
    @Test void emptyTableReplacesStaleDataAndPublishesKnownZeroEvidence() throws Exception {
        Files.createDirectories(root.resolve("data"));Files.writeString(root.resolve("data/items.sql"),"stale data");
        Db db=new Db();var exported=new MigrationTableExporter(new MigrationCancellation()).export(db.connection(),"OWNER","ITEMS",root);
        assertEquals(0,exported.rows());assertEquals("",Files.readString(root.resolve("data/items.sql")));
        assertEquals(0,MigrationExportEvidence.read(root.resolve("data/items.sql")).rows());assertEquals(1,db.rollbacks);
    }
    @Test void readFailureKeepsPreviousDataAndCleansTemporaryFile() throws Exception {
        Files.createDirectories(root.resolve("data"));Path file=root.resolve("data/items.sql");Files.writeString(file,"old export");
        Db db=new Db();db.values.add(List.of(BigDecimal.ONE,"first"));db.failDuringRead=true;
        assertThrows(SQLException.class,() -> new MigrationTableExporter(new MigrationCancellation()).export(db.connection(),"OWNER","ITEMS",root));
        assertEquals("old export",Files.readString(file));assertEquals(1,db.rollbacks);
        try(var files=Files.list(root.resolve("data"))){assertEquals(List.of(file),files.toList());}
    }
    @Test void unsupportedTypeOrPathIsRejectedBeforePublishingAnything() throws Exception {
        Db db=new Db();db.type="BLOB";
        assertThrows(IOException.class,() -> new MigrationTableExporter(new MigrationCancellation()).export(db.connection(),"OWNER","ITEMS",root));
        assertFalse(Files.exists(root.resolve("data")));assertEquals(0,db.dataReads);assertEquals(1,db.rollbacks);
        Db path=new Db();assertThrows(IOException.class,() -> new MigrationTableExporter(new MigrationCancellation()).export(path.connection(),"OWNER","../ITEMS",root));
        assertTrue(path.events.isEmpty());
    }
    @Test void corruptSourceEvidenceCannotBeAcceptedOrSilentlyRewrittenByRead() throws Exception {
        Db db=new Db();new MigrationTableExporter(new MigrationCancellation()).export(db.connection(),"OWNER","ITEMS",root);
        Path data=root.resolve("data/items.sql"),proof=MigrationExportEvidence.path(data);byte[] bytes=Files.readAllBytes(proof);bytes[12]^=1;Files.write(proof,bytes);
        assertThrows(IOException.class,() -> MigrationExportEvidence.read(data));assertArrayEquals(bytes,Files.readAllBytes(proof));
    }
    @Test void failedProofPublicationCannotBeMistakenForLegacyDataOrAutomaticallyOverwritten() throws Exception {
        Path data=Files.createDirectories(root.resolve("data")).resolve("items.sql");Files.createDirectory(MigrationExportEvidence.path(data));
        Db db=new Db();db.values.add(List.of(BigDecimal.ONE,"synthetic"));
        assertThrows(IOException.class,()->new MigrationTableExporter(new MigrationCancellation()).export(db.connection(),"OWNER","ITEMS",root));
        assertTrue(Files.exists(data));assertTrue(Files.exists(MigrationExportEvidence.pending(data)));
        assertThrows(IOException.class,()->MigrationExportEvidence.read(data));
        Db retry=new Db();assertThrows(IOException.class,()->new MigrationTableExporter(new MigrationCancellation()).export(retry.connection(),"OWNER","ITEMS",root));
        assertEquals(0,retry.dataReads);assertTrue(Files.readString(data).contains("synthetic"));
    }
    static final class Db {
        List<List<Object>> values=new ArrayList<>();List<String> events=new ArrayList<>();String type="NUMBER";int rollbacks,dataReads;boolean failDuringRead;
        List<MigrationPlan.Column> columns(){return List.of(new MigrationPlan.Column("ID","id",type,"NUMERIC",false),new MigrationPlan.Column("LABEL","label","VARCHAR2","TEXT",true));}
        Connection connection(){return proxy(Connection.class,(m,a)->switch(m){
            case "setReadOnly"->{assertEquals(true,a[0]);events.add("readonly");yield null;}
            case "setAutoCommit"->{assertEquals(false,a[0]);yield null;}
            case "rollback"->{rollbacks++;yield null;}
            case "close"->null;
            case "createStatement"->proxy(Statement.class,(method,args)->switch(method){
                case "setQueryTimeout","setFetchSize","close"->null;
                case "execute"->{assertEquals("SET TRANSACTION READ ONLY",args[0]);events.add("transaction");yield false;}
                case "executeQuery"->{assertEquals("SELECT \"ID\",\"LABEL\" FROM \"OWNER\".\"ITEMS\"",args[0]);dataReads++;events.add("source-query");yield result(values,failDuringRead);}
                default->throw new AssertionError(method);
            });
            case "prepareStatement"->{String sql=(String)a[0];yield proxy(PreparedStatement.class,(method,args)->switch(method){
                case "setQueryTimeout","setMaxRows","setString","close"->null;
                case "executeQuery"->sql.contains("ALL_TABLES")?result(List.of(List.of("ITEMS")),false):sql.contains("SYS_CONTEXT")?result(List.of(List.of("source-id")),false):result(List.of(List.of("ID",type,"N"),List.of("LABEL","VARCHAR2","Y")),false);
                default->throw new AssertionError(method);
            });}
            default->throw new AssertionError(m);
        });}
        static ResultSet result(List<List<Object>> values,boolean fail){int[] row={-1};return proxy(ResultSet.class,(m,a)->switch(m){
            case "getMetaData"->proxy(ResultSetMetaData.class,(method,args)->{if(method.equals("getColumnCount"))return values.isEmpty()?2:values.getFirst().size();throw new AssertionError(method);});
            case "next"->{if(fail && row[0]>=0)throw new SQLException("synthetic-source-row-secret");yield ++row[0]<values.size();}
            case "getString"->{Object value=values.get(row[0]).get(a[0] instanceof Integer index?index-1:0);yield value==null?null:value.toString();}
            case "getBigDecimal"->(BigDecimal)values.get(row[0]).get((int)a[0]-1);
            case "close"->null;
            default->throw new AssertionError(m);
        });}
    }
    @FunctionalInterface private interface Call{Object invoke(String method,Object[] args)throws Throwable;}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];return call.invoke(m.getName(),a);
    }));}
}
