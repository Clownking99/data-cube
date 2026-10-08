package com.datacube.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static com.datacube.migration.MigrationRun.*;
import static org.junit.jupiter.api.Assertions.*;

class PgImporterTest {
    @TempDir Path root;

    @Test void approvalAndLegacyGuardsRejectBeforeOpeningAnyConnection() throws Exception {
        Fixture f=new Fixture(1); var importer=f.importer();
        assertThrows(IllegalStateException.class,() -> importer.importToPg("url","user","secret","owner","dest",false));
        assertThrows(IllegalStateException.class,() -> importer.importPrepared(f.plan,null,f.request));
        var changed=new MigrationRequest(f.request.source(),f.request.target(),"OTHER",f.request.schema(),root,f.request.mode(),false);
        assertThrows(IllegalStateException.class,() -> importer.importPrepared(f.plan,f.plan.approve(f.request),changed));
        assertEquals(0,f.sourceOpens); assertEquals(0,f.targetOpens);
    }
    @Test void commitsOnceOnlyAfterEveryBatchAndWholeTableComparison() throws Exception {
        Fixture f=new Fixture(501); MigrationRun run=f.run();
        assertEquals(State.COMMITTED_VERIFIED,run.outcomes().getFirst().state()); assertTrue(run.completeWithinScope());
        assertEquals(501,run.outcomes().getFirst().rows()); assertEquals(501,f.committed.size());
        assertEquals(2,f.batches); assertEquals(1,f.commits); assertEquals(0,f.rollbacks);
        assertTrue(f.events.indexOf("compare")<f.events.indexOf("commit"));
        assertTrue(f.events.indexOf("batch2")<f.events.indexOf("compare"));
        assertEquals(1,f.sourceOpens); assertEquals(1,f.targetOpens); assertEquals(2,f.closed);
        assertTrue(f.sqls.stream().noneMatch(sql -> sql.contains("synthetic-row-secret")));
        assertFalse(f.logger.messages.toString().contains("synthetic-row-secret"));
        assertFalse(MigrationReport.of(run).display().contains("target-secret"));
        assertTrue(f.checkpoints.stream().anyMatch(r -> r.outcomes().getFirst().phase()==Phase.COMMIT && r.outcomes().getFirst().state()==State.RUNNING));
    }
    @Test void largeScalarRowsFlushByPayloadBudgetBeforeTheRowCountLimit() throws Exception {
        Fixture f=new Fixture(5);String label="界".repeat(500_000);StringBuilder file=new StringBuilder();
        for(int i=0;i<5;i++)file.append(MigrationDataFile.format("items",f.columns,List.of(new BigDecimal(i),label))).append('\n');
        Files.writeString(root.resolve("data/items.sql"),file);f.plan=f.freshPlan();
        var run=f.run();assertTrue(run.completeWithinScope());assertEquals(5,f.committed.size());
        assertTrue(f.batches>1,"Large rows must flush before accumulating 500 rows");assertEquals(1,f.commits);
    }
    @Test void laterBatchFailureRollsBackEntireTableWithoutAutomaticReplay() throws Exception {
        Fixture f=new Fixture(501); f.failBatch=2;
        MigrationRun run=f.run();
        assertEquals(State.FAILED_ROLLED_BACK,run.outcomes().getFirst().state()); assertEquals(Phase.INSERT,run.outcomes().getFirst().phase());
        assertEquals(2,f.batches); assertEquals(0,f.commits); assertEquals(1,f.rollbacks); assertTrue(f.committed.isEmpty());
        assertEquals(1,f.targetOpens); assertFalse(run.completeWithinScope());
        assertFalse(f.logger.messages.toString().contains("synthetic-driver-secret"));
        MigrationPlan retry=run.retryPlan(f.freshPlan()); assertEquals(1,retry.tables().size());
        assertEquals(run.id(),retry.previousRun());assertEquals(Map.of(1,1),retry.previousOrdinals());
        f.failBatch=0; f.batches=0;
        MigrationRun retried=f.importer().importPrepared(retry,retry.approve(f.request),f.request);
        assertTrue(retried.completeWithinScope()); assertEquals(501,f.committed.size()); assertEquals(1,f.commits);
    }
    @Test void commitResponseFailureIsUnknownEvenIfServerCommittedAndCannotBeRetried() throws Exception {
        Fixture f=new Fixture(1); f.failCommit=true;
        MigrationRun run=f.run(); assertEquals(State.COMMIT_UNKNOWN,run.outcomes().getFirst().state());
        assertEquals(1,f.committed.size()); assertEquals(1,f.commits); assertEquals(0,f.rollbacks);
        assertThrows(IllegalStateException.class,() -> run.retryPlan(f.freshPlan()));
        assertEquals(1,f.targetOpens);
    }
    @Test void comparisonMismatchPreventsCommitAndReportsTheComparisonPhase() throws Exception {
        Fixture f=new Fixture(2); f.mismatch=true; MigrationRun run=f.run();
        assertEquals(State.FAILED_ROLLED_BACK,run.outcomes().getFirst().state());
        assertEquals(Reason.DATA_MISMATCH,run.outcomes().getFirst().reason()); assertEquals(Phase.COMPARE,run.outcomes().getFirst().phase());
        assertEquals(0,f.commits); assertEquals(1,f.rollbacks); assertTrue(f.committed.isEmpty());
    }
    @Test void targetReplacedWhileLockingIsRejectedBeforeAnyRowWrite() throws Exception {
        Fixture f=new Fixture(1); f.driftAfterLock=true; MigrationRun run=f.run();
        assertEquals(Reason.TARGET_CHANGED,run.outcomes().getFirst().reason()); assertEquals(0,f.batches); assertEquals(0,f.commits);
        assertEquals(1,f.rollbacks);
    }
    @Test void modifiedReviewedInputCannotAcquireTargetConnectionOrUseOldApprovalAgain() throws Exception {
        Fixture f=new Fixture(1); var approval=f.plan.approve(f.request);
        Files.writeString(root.resolve("data/items.sql"),"INSERT INTO items (id,label) VALUES (2,E'changed');\n");
        MigrationRun run=f.importer().importPrepared(f.plan,approval,f.request);
        assertEquals(State.FAILED_BEFORE_WRITE,run.outcomes().getFirst().state()); assertEquals(Reason.INPUT_CHANGED,run.outcomes().getFirst().reason());
        assertEquals(0,f.targetOpens);
        assertThrows(IllegalStateException.class,() -> f.importer().importPrepared(f.plan,approval,f.request));
    }
    @Test void cancellationBeforeCommitNeverCommitsAndUnconfirmedRollbackBlocksRetry() throws Exception {
        Fixture f=new Fixture(1); f.cancelBeforeCommit=true; MigrationRun run=f.run();
        assertEquals(0,f.commits); assertEquals(State.COMMIT_UNKNOWN,run.outcomes().getFirst().state());
        assertTrue(f.committed.isEmpty()); assertThrows(IllegalStateException.class,() -> run.retryPlan(f.freshPlan()));
    }
    @Test void reportFailureBeforeExecutionDoesNotOpenResourcesAndAfterCommitPreservesCommitState() throws Exception {
        Fixture before=new Fixture(1); before.failCheckpoint=1;
        assertThrows(IOException.class,before::run); assertEquals(0,before.sourceOpens); assertEquals(0,before.targetOpens);
        Fixture after=new Fixture(1); after.failAfterCommit=true; MigrationRun run=after.run();
        assertTrue(run.reportFailed()); assertFalse(run.completeWithinScope()); assertEquals(State.COMMITTED_VERIFIED,run.outcomes().getFirst().state());
        assertEquals(1,after.commits); assertEquals(1,after.committed.size());
    }
    @Test void nonemptySkipDoesNotInsertAndIsNotReportedAsVerified() throws Exception {
        Fixture f=new Fixture(1); f.existingRows=true;
        f.request=new MigrationRequest(f.request.source(),f.request.target(),"OWNER","dest",root,MigrationRequest.Mode.SKIP_NONEMPTY,false);
        f.plan=f.freshPlan(); f.committed.add(List.of(new BigDecimal("999"),"existing"));
        MigrationRun run=f.run(); assertEquals(State.SKIPPED_NONEMPTY,run.outcomes().getFirst().state()); assertFalse(run.completeWithinScope());
        assertEquals(0,f.batches); assertEquals(0,f.commits); assertEquals(1,f.committed.size());
    }
    @Test void retryRequiresSameFileTargetAndRequestAndCannotReimportSuccessfulTable() throws Exception {
        Fixture f=new Fixture(1); f.failBatch=1; var failed=f.run();
        var changed=new MigrationRequest(f.request.source(),f.request.target(),"OWNER","elsewhere",root,f.request.mode(),false);
        var drift=new MigrationPlan(changed,f.plan.sourceIdentity(),f.plan.targetIdentity(),10L,f.plan.tables(),List.of());
        assertThrows(IllegalStateException.class,() -> failed.retryPlan(drift));
        Files.writeString(root.resolve("data/items.sql"),"INSERT INTO items (id,label) VALUES (4,E'x');");
        assertThrows(IllegalStateException.class,() -> failed.retryPlan(f.freshPlan()));
        Fixture successful=new Fixture(1); MigrationRun complete=successful.run();
        assertThrows(IllegalStateException.class,() -> complete.retryPlan(successful.freshPlan()));
    }
    @Test void targetIdentityColumnsAddedAfterReviewPreventWrites() throws Exception {
        Fixture f=new Fixture(1);f.generated=true;MigrationRun run=f.run();
        assertEquals(Reason.UNSUPPORTED_TARGET,run.outcomes().getFirst().reason());assertEquals(0,f.batches);assertEquals(0,f.commits);assertEquals(1,f.rollbacks);
    }
    @Test void createsMissingSchemaAndTableWithinTheSameTransaction() throws Exception {
        Fixture f=new Fixture(1);f.schemaExists=false;f.tableExists=false;f.plan=f.freshPlan();
        var run=f.run();assertTrue(run.completeWithinScope());assertEquals(1,f.commits);
        assertTrue(f.sqls.stream().anyMatch(s->s.equals("CREATE SCHEMA \"dest\"")));
        assertTrue(f.sqls.stream().anyMatch(s->s.startsWith("CREATE TABLE \"dest\".\"items\"") && s.contains("PRIMARY KEY (\"id\")")));
        assertEquals(501L,run.schemaOid());
    }

    private final class Fixture {
        final MigrationCancellation cancellation=new MigrationCancellation(); final MigrationTestLogger logger=new MigrationTestLogger();
        final List<MigrationPlan.Column> columns=List.of(new MigrationPlan.Column("ID","id","NUMBER","NUMERIC",false),new MigrationPlan.Column("LABEL","label","VARCHAR2","TEXT",true));
        final List<List<Object>> committed=new ArrayList<>(),working=new ArrayList<>();
        final List<String> events=new ArrayList<>(),sqls=new ArrayList<>(); final List<MigrationReport> checkpoints=new ArrayList<>();
        MigrationRequest request; MigrationPlan plan;
        int sourceOpens,targetOpens,closed,batches,commits,rollbacks,failBatch,failCheckpoint;
        boolean failCommit,mismatch,driftAfterLock,locked,cancelBeforeCommit,failAfterCommit,existingRows,targetClosed,generated;
        boolean schemaExists=true,tableExists=true,createdSchema,createdTable;
        Fixture(int rows) throws Exception {
            Files.createDirectories(root.resolve("data")); StringBuilder file=new StringBuilder();
            for(int i=0;i<rows;i++)file.append(MigrationDataFile.format("items",columns,List.of(new BigDecimal(i),"synthetic-row-secret"))).append('\n');
            Files.writeString(root.resolve("data/items.sql"),file);
            request=new MigrationRequest(new MigrationRequest.Endpoint("source","source-user","source-secret"),new MigrationRequest.Endpoint("target","target-user","target-secret"),"OWNER","dest",root,MigrationRequest.Mode.EMPTY_TABLES_ONLY,false);
            plan=freshPlan();
        }
        MigrationPlan freshPlan() throws IOException {
            var file=MigrationFiles.inspect(root,"items",new MigrationCancellation());
            var table=new MigrationPlan.Table("ITEMS","items",columns,List.of("id"),tableExists?20L:null,existingRows,file,List.of());
            return new MigrationPlan(request,MigrationPreflight.fingerprint("source-id"),MigrationPreflight.fingerprint("target-id"),schemaExists?10L:null,List.of(table),List.of());
        }
        PgImporter importer() { return new PgImporter(logger,cancellation,(url,user,password) -> {
            if(url.equals("source"))sourceOpens++;else{targetOpens++; targetClosed=false; locked=false;working.clear();working.addAll(committed);}
            return connection(url.equals("source"));
        },run -> {
            checkpoints.add(MigrationReport.of(run));
            if(checkpoints.size()==failCheckpoint || failAfterCommit && commits>0)throw new IOException("synthetic-report-secret");
            if(cancelBeforeCommit && run.outcomes().getFirst().phase()==Phase.COMMIT)cancellation.cancel();
        }); }
        MigrationRun run() throws Exception { return importer().importPrepared(plan,plan.approve(request),request); }
        Connection connection(boolean source) { return proxy(Connection.class,(method,args) -> switch(method) {
            case "setReadOnly" -> { assertTrue(source); yield null; }
            case "setTransactionIsolation" -> { assertEquals(Connection.TRANSACTION_READ_COMMITTED,args[0]); yield null; }
            case "setAutoCommit" -> { assertEquals(false,args[0]); yield null; }
            case "close" -> {closed++; if(!source)targetClosed=true; yield null;}
            case "rollback" -> {if(targetClosed)throw new SQLException("closed");rollbacks++;working.clear();working.addAll(committed);yield null;}
            case "commit" -> {commits++;events.add("commit");committed.clear();committed.addAll(working);if(failCommit)throw new SQLException("synthetic-driver-secret");yield null;}
            case "createStatement" -> proxy(Statement.class,(m,a) -> switch(m) {
                case "setQueryTimeout","close" -> null;
                case "execute" -> {String sql=(String)a[0];sqls.add(sql);assertTrue(sql.startsWith("SET LOCAL") || sql.startsWith("LOCK TABLE ONLY") || sql.startsWith("CREATE "));if(sql.startsWith("LOCK"))locked=true;if(sql.startsWith("CREATE SCHEMA"))createdSchema=true;if(sql.startsWith("CREATE TABLE")){createdTable=true;locked=true;} yield false;}
                default -> throw new AssertionError(m);
            });
            case "prepareStatement" -> prepared((String)args[0],source);
            default -> throw new AssertionError(method);
        }); }
        PreparedStatement prepared(String sql,boolean source) {
            sqls.add(sql); Map<Integer,Object> params=new HashMap<>(); List<List<Object>> pending=new ArrayList<>();
            return proxy(PreparedStatement.class,(method,args) -> switch(method) {
                case "setQueryTimeout","setMaxRows","setFetchSize","close" -> null;
                case "setString","setBigDecimal" -> {params.put((int)args[0],args[1]);yield null;}
                case "setNull" -> {params.put((int)args[0],null);yield null;}
                case "addBatch" -> {assertTrue(sql.startsWith("INSERT INTO \"dest\".\"items\"")); assertTrue(locked);pending.add(Arrays.asList(params.get(1),params.get(2)));yield null;}
                case "executeBatch" -> {batches++;events.add("batch"+batches);working.addAll(pending);if(batches==failBatch)throw new SQLException("synthetic-driver-secret");int[] counts=new int[pending.size()];Arrays.fill(counts,1);yield counts;}
                case "clearBatch" -> {pending.clear();yield null;}
                case "executeQuery" -> result(query(sql,source));
                default -> throw new AssertionError(method);
            });
        }
        List<List<Object>> query(String sql,boolean source) {
            if(source){assertTrue(sql.contains("SYS_CONTEXT"));return cell("source-id");}
            if(sql.contains("pg_postmaster_start_time"))return cell("target-id");
            if(sql.startsWith("SELECT oid::text FROM pg_namespace"))return schemaExists?cell("10"):createdSchema?cell("501"):List.of();
            if(sql.startsWith("SELECT c.oid::text FROM pg_class"))return tableExists?cell(driftAfterLock&&locked?"21":"20"):createdTable?cell("502"):List.of();
            if(sql.startsWith("SELECT (relkind"))return cell("true");
            if(sql.contains("FROM pg_attribute"))return List.of(List.of("id","numeric","",generated?"a":""),List.of("label","text","",""));
            if(sql.startsWith("SELECT 1 FROM"))return existingRows?cell("1"):List.of();
            if(sql.startsWith("SELECT COUNT(*) FROM (SELECT"))return cell(Integer.toString(working.size()));
            if(sql.startsWith("SELECT \"id\",\"label\" FROM")){events.add("compare");var copy=new ArrayList<>(working);if(mismatch)copy.set(0,List.of(new BigDecimal("9999"),"changed"));return copy;}
            throw new AssertionError(sql);
        }
    }
    private static List<List<Object>> cell(Object value){return List.of(List.of(value));}
    private static ResultSet result(List<List<Object>> values) {int[] row={-1};return proxy(ResultSet.class,(method,args) -> switch(method) {
        case "getMetaData" -> proxy(ResultSetMetaData.class,(m,a) -> {if(m.equals("getColumnCount"))return values.isEmpty()?1:values.getFirst().size();throw new AssertionError(m);});
        case "next" -> ++row[0]<values.size();
        case "getString" -> {Object value=values.get(row[0]).get((int)args[0]-1);yield value==null?null:value.toString();}
        case "getBigDecimal" -> (BigDecimal)values.get(row[0]).get((int)args[0]-1);
        case "close" -> null;
        default -> throw new AssertionError(method);
    });}
    @FunctionalInterface private interface Call {Object invoke(String method,Object[] args)throws Throwable;}
    private static <T>T proxy(Class<T> type,Call call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];return call.invoke(m.getName(),a);
    }));}
}
