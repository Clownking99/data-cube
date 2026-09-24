package com.datacube.migration;

import com.datacube.core.MigrationLogger;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.datacube.migration.MigrationPreflight.*;
import static com.datacube.migration.MigrationRun.*;

/** Reviewed, data-only import. Each table commits once after its complete comparison. */
public class PgImporter {
    private static final long BATCH_PAYLOAD_BYTES=4L*1024*1024;
    @FunctionalInterface public interface Checkpoint { void save(MigrationRun run) throws IOException; }
    private final MigrationLogger logger;
    private final MigrationCancellation cancellation;
    private final MigrationConnections connections;
    private final Checkpoint checkpoint;
    private volatile MigrationRun lastRun;

    public PgImporter(MigrationLogger logger) { this(logger,new MigrationCancellation()); }
    public PgImporter(MigrationLogger logger,MigrationCancellation cancellation) {
        this(logger,cancellation,MigrationConnections::connect,run -> MigrationReport.of(run).checkpoint(MigrationReport.defaultDirectory()));
    }
    public PgImporter(MigrationLogger logger,MigrationCancellation cancellation,MigrationConnections connections,Checkpoint checkpoint) {
        this.logger=Objects.requireNonNull(logger); this.cancellation=Objects.requireNonNull(cancellation);
        this.connections=Objects.requireNonNull(connections); this.checkpoint=Objects.requireNonNull(checkpoint);
    }
    /** Export concurrency remains configurable; imports serialize whole-table transactions. */
    public void setMaxConcurrency(int concurrency) { }
    public void cancel() { cancellation.cancel(); }
    public void resetCancel() { cancellation.reset(); }
    public boolean isCancelled() { return cancellation.isCancelled(); }
    public MigrationRun lastRun() { return lastRun; }

    /** Legacy callers must obtain the same reviewed plan and one-shot approval as the GUI. */
    public void importToPg(String url,String user,String pass,String owner,String schema,boolean incremental) {
        throw new IllegalStateException("Import requires an explicit preflight plan and approval");
    }

    public MigrationRun importPrepared(MigrationPlan plan,MigrationPlan.Approval approval,MigrationRequest current) throws Exception {
        if(approval==null)throw new IllegalStateException("Migration approval is required");
        approval.consume(plan,current);
        cancellation.checkCancelled();
        MigrationRun run=new MigrationRun(plan); lastRun=run;
        checkpoint.save(run); // Failure here cannot acquire a write resource.
        Connection source=null;
        try {
            source=cancellation.register(connections.open(current.source().url(),current.source().user(),current.source().password()));
            source.setReadOnly(true);
            if(!plan.sourceIdentity().equals(sourceIdentity(source,cancellation)))throw guard(Reason.SOURCE_CHANGED);
        } catch(Exception failure) {
            Reason reason=cancellation.isCancelled() || failure instanceof java.util.concurrent.CancellationException?Reason.CANCELLED:
                    failure instanceof GuardFailure guard?guard.reason:Reason.SOURCE_UNAVAILABLE;
            run.update(0,State.FAILED_BEFORE_WRITE,Phase.PRECHECK,reason,0);
            save(run); return run;
        } finally { cancellation.release(source); }
        for(int index=0;index<plan.tables().size();index++) {
            if(cancellation.isCancelled())break;
            var table=plan.tables().get(index);
            run.update(index,State.RUNNING,Phase.PRECHECK,Reason.NONE,0);
            if(!save(run))break;
            importTable(run,index,table);
            if(!save(run))break;
            logger.logProgress("迁移表任务",index+1,plan.tables().size());
            State state=run.outcomes().get(index).state();
            if(state!=State.COMMITTED_VERIFIED && state!=State.SKIPPED_NONEMPTY)break;
        }
        logger.logSummary("迁移结果（有限范围）",Map.of(
                "已提交并完成文件对账",run.outcomes().stream().filter(o -> o.state()==State.COMMITTED_VERIFIED).count(),
                "跳过",run.outcomes().stream().filter(o -> o.state()==State.SKIPPED_NONEMPTY).count(),
                "结果范围","逐表提交；文件与目标的行数/空值/摘要对账，不是当前源库全量一致证明"));
        return run;
    }

    private void importTable(MigrationRun run,int index,MigrationPlan.Table table) {
        MigrationRequest request=run.plan().request(); Connection conn=null;
        boolean transaction=false,commitStarted=false,committed=false;
        long importedRows=0; Phase phase=Phase.PRECHECK;
        try {
            cancellation.checkCancelled();
            try { MigrationFiles.verify(table.data(),cancellation);
                if(!Objects.equals(table.evidence(),MigrationExportEvidence.read(table.data().path())))throw new IOException("Source evidence changed"); }
            catch(IOException invalid) { throw guard(Reason.INPUT_CHANGED); }
            conn=cancellation.register(connections.open(request.target().url(),request.target().user(),request.target().password()));
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false); transaction=true;
            if(!run.plan().targetIdentity().equals(targetIdentity(conn,cancellation)))throw guard(Reason.TARGET_CHANGED);
            execute(conn,"SET LOCAL lock_timeout='5s'");
            execute(conn,"SET LOCAL statement_timeout='600s'");
            Long schemaOid=namespaceOid(conn,request.schema());
            if(!Objects.equals(schemaOid,run.schemaOid()))throw guard(Reason.TARGET_CHANGED);
            phase=Phase.CREATE;
            if(schemaOid==null) {
                execute(conn,"CREATE SCHEMA "+quote(request.schema()));
                schemaOid=namespaceOid(conn,request.schema());
                if(schemaOid==null)throw guard(Reason.TARGET_CHANGED);
            }
            String qualified=quote(request.schema())+"."+quote(table.targetName());
            Long oid=relationOid(conn,request.schema(),table.targetName());
            if(!Objects.equals(oid,table.targetOid()))throw guard(Reason.TARGET_CHANGED);
            if(oid==null) {
                execute(conn,createTable(qualified,table));
                oid=relationOid(conn,request.schema(),table.targetName());
                if(oid==null)throw guard(Reason.TARGET_CHANGED);
            } else execute(conn,"LOCK TABLE ONLY "+qualified+" IN SHARE ROW EXCLUSIVE MODE");
            // Repeat identity after acquiring the lock; a same-name replacement is not the reviewed table.
            if(!Objects.equals(oid,relationOid(conn,request.schema(),table.targetName()))
                    || !Objects.equals(schemaOid,namespaceOid(conn,request.schema())))throw guard(Reason.TARGET_CHANGED);
            checkTarget(conn,oid,table);
            boolean hasRows=!rows(conn,"SELECT 1 FROM "+qualified+" LIMIT 1",cancellation).isEmpty();
            if(hasRows!=table.targetHasRows())throw guard(Reason.TARGET_CHANGED);
            if(hasRows) {
                if(request.mode()!=MigrationRequest.Mode.SKIP_NONEMPTY)throw guard(Reason.TARGET_CHANGED);
                conn.rollback(); transaction=false;
                run.update(index,State.SKIPPED_NONEMPTY,Phase.FINISHED,Reason.NOT_SELECTED,0);
                return;
            }
            phase=Phase.READ_FILE; run.update(index,State.RUNNING,phase,Reason.NONE,0);
            if(!save(run))throw guard(Reason.REPORT_IO);
            String columns=table.columns().stream().map(c -> quote(c.targetName())).collect(java.util.stream.Collectors.joining(","));
            String insert="INSERT INTO "+qualified+" ("+columns+") VALUES ("+String.join(",",Collections.nCopies(table.columns().size(),"?"))+")";
            MigrationDataFile.Statistics expected;
            phase=Phase.INSERT;
            try(PreparedStatement writer=conn.prepareStatement(insert)) {
                writer.setQueryTimeout(600); int[] pending={0};long[] payload={0};
                expected=MigrationDataFile.read(table.data(),table,cancellation,row -> {
                    cancellation.checkCancelled();
                    long rowBytes=32;
                    for(Object value:row)rowBytes+=32+(value instanceof String text?2L*text.length():value instanceof java.math.BigDecimal number?2L*number.toPlainString().length():0);
                    if(pending[0]>0 && payload[0]+rowBytes>BATCH_PAYLOAD_BYTES){flush(writer);pending[0]=0;payload[0]=0;}
                    for(int c=0;c<row.size();c++) {
                        Object value=row.get(c);
                        if(value==null)writer.setNull(c+1,table.columns().get(c).numeric()?Types.NUMERIC:Types.VARCHAR);
                        else if(value instanceof java.math.BigDecimal number)writer.setBigDecimal(c+1,number);
                        else writer.setString(c+1,(String)value);
                    }
                    writer.addBatch();payload[0]+=rowBytes;
                    if(++pending[0]>=500) { flush(writer); pending[0]=0;payload[0]=0; }
                });
                if(pending[0]>0)flush(writer);
            }
            if(!Objects.equals(table.evidence(),MigrationExportEvidence.read(table.data().path()))
                    || table.evidence()!=null && table.evidence().rows()!=expected.rows())throw guard(Reason.INPUT_CHANGED);
            importedRows=expected.rows(); phase=Phase.COMPARE;
            run.update(index,State.RUNNING,phase,Reason.NONE,importedRows);
            if(!save(run))throw guard(Reason.REPORT_IO);
            MigrationDataFile.Accumulator observed=new MigrationDataFile.Accumulator(table.columns().size());
            try(PreparedStatement read=conn.prepareStatement("SELECT "+columns+" FROM "+qualified)) {
                read.setQueryTimeout(600); read.setFetchSize(1000);
                try(ResultSet result=read.executeQuery()) {
                    while(result.next()) { cancellation.checkCancelled(); observed.add(MigrationDataFile.jdbcRow(result,table.columns())); }
                }
            }
            if(!expected.equals(observed.finish()))throw guard(Reason.DATA_MISMATCH);
            if(!table.primaryKey().isEmpty()) {
                String keyColumns=table.primaryKey().stream().map(MigrationPreflight::quote).collect(java.util.stream.Collectors.joining(","));
                long distinct=Long.parseLong(one(conn,"SELECT COUNT(*) FROM (SELECT "+keyColumns+" FROM "+qualified+" GROUP BY "+keyColumns+") dc_keys",cancellation));
                if(distinct!=expected.rows())throw guard(Reason.DATA_MISMATCH);
                for(String key:table.primaryKey()) {
                    int position=-1; for(int i=0;i<table.columns().size();i++)if(table.columns().get(i).targetName().equals(key))position=i;
                    if(position<0 || expected.nulls().get(position)!=0)throw guard(Reason.DATA_MISMATCH);
                }
            }
            cancellation.checkCancelled();
            phase=Phase.COMMIT; run.update(index,State.RUNNING,phase,Reason.NONE,importedRows);
            if(!save(run))throw guard(Reason.REPORT_IO);
            cancellation.checkCancelled(); commitStarted=true;
            conn.commit(); committed=true; transaction=false;
            run.schemaOid(schemaOid);
            run.update(index,State.COMMITTED_VERIFIED,Phase.FINISHED,Reason.NONE,importedRows);
        } catch(Exception failure) {
            Reason reason=failure instanceof GuardFailure guard?guard.reason:
                    cancellation.isCancelled() || failure instanceof java.util.concurrent.CancellationException?Reason.CANCELLED:Reason.SQL_FAILED;
            State state=State.FAILED_BEFORE_WRITE;
            if(commitStarted && !committed) { state=State.COMMIT_UNKNOWN; reason=Reason.COMMIT_UNCERTAIN; }
            else if(transaction) {
                try { conn.rollback(); state=reason==Reason.CANCELLED?State.CANCELLED_ROLLED_BACK:State.FAILED_ROLLED_BACK; }
                catch(SQLException rollbackFailure) { state=State.COMMIT_UNKNOWN; reason=Reason.COMMIT_UNCERTAIN; }
            }
            run.update(index,state,phase,reason,importedRows);
            logger.logErr("表任务 T"+(index+1)+"： "+state+" / "+reason+"；未自动重试");
        } finally { cancellation.release(conn); }
    }

    private void checkTarget(Connection conn,long oid,MigrationPlan.Table table) throws SQLException {
        String safe=one(conn,"SELECT (relkind='r' AND NOT relrowsecurity AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid=c.oid AND NOT tgisinternal) AND NOT EXISTS (SELECT 1 FROM pg_rewrite WHERE ev_class=c.oid AND rulename<>'_RETURN') AND NOT EXISTS (SELECT 1 FROM pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid))::text FROM pg_class c WHERE c.oid=?::oid",cancellation,Long.toString(oid));
        if(!truth(safe))throw guard(Reason.UNSUPPORTED_TARGET);
        if(!targetColumnsMatch(conn,oid,table.columns(),cancellation))throw guard(Reason.UNSUPPORTED_TARGET);
    }
    private Long namespaceOid(Connection connection,String schema) throws SQLException {
        var values=rows(connection,"SELECT oid::text FROM pg_namespace WHERE nspname=?",cancellation,schema);
        if(values.size()>1)throw guard(Reason.TARGET_CHANGED);
        return values.isEmpty()?null:Long.valueOf(values.getFirst().getFirst());
    }
    private Long relationOid(Connection connection,String schema,String table) throws SQLException {
        var values=rows(connection,"SELECT c.oid::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?",cancellation,schema,table);
        if(values.size()>1)throw guard(Reason.TARGET_CHANGED);
        return values.isEmpty()?null:Long.valueOf(values.getFirst().getFirst());
    }
    private String createTable(String qualified,MigrationPlan.Table table) {
        List<String> parts=new ArrayList<>();
        for(var column:table.columns()) {
            if(!Set.of("TEXT","NUMERIC").contains(column.pgType()))throw new IllegalStateException("Unreviewed type");
            parts.add(quote(column.targetName())+" "+column.pgType()+(column.nullable()?"":" NOT NULL"));
        }
        if(!table.primaryKey().isEmpty())parts.add("PRIMARY KEY ("+table.primaryKey().stream().map(MigrationPreflight::quote).collect(java.util.stream.Collectors.joining(","))+")");
        return "CREATE TABLE "+qualified+" ("+String.join(",",parts)+")";
    }
    private void execute(Connection connection,String sql) throws SQLException {
        cancellation.checkCancelled();
        try(Statement statement=connection.createStatement()) { statement.setQueryTimeout(600); statement.execute(sql); }
    }
    private void flush(PreparedStatement statement) throws SQLException {
        cancellation.checkCancelled();
        try { for(int count:statement.executeBatch())if(count==Statement.EXECUTE_FAILED)throw new SQLException("Batch failed","DC003"); }
        finally { statement.clearBatch(); }
    }
    private boolean save(MigrationRun run) {
        try { checkpoint.save(run); return true; }
        catch(IOException failed) { run.reportFailed(true); logger.logErr("报告写入失败，已停止后续写入"); return false; }
    }
    private static GuardFailure guard(Reason reason) { return new GuardFailure(reason); }
    private static final class GuardFailure extends SQLException {
        final Reason reason;
        GuardFailure(Reason reason) { super(reason.name(),"DC003"); this.reason=reason; }
    }
}
