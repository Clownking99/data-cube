package com.datacube.migration;

import java.time.Instant;
import java.util.*;

/** In-memory execution authority. A deserialized report can never become a retry permit. */
public final class MigrationRun {
    public enum State { NOT_STARTED, RUNNING, COMMITTED_VERIFIED, SKIPPED_NONEMPTY,
        FAILED_BEFORE_WRITE, FAILED_ROLLED_BACK, CANCELLED_ROLLED_BACK, COMMIT_UNKNOWN }
    public enum Phase { PRECHECK, CREATE, READ_FILE, INSERT, COMPARE, COMMIT, FINISHED }
    public enum Reason { NONE, INPUT_CHANGED, SOURCE_CHANGED, SOURCE_UNAVAILABLE, TARGET_CHANGED, UNSUPPORTED_TARGET, SQL_FAILED,
        DATA_MISMATCH, CANCELLED, COMMIT_UNCERTAIN, REPORT_IO, NOT_SELECTED }
    public record Outcome(int ordinal,State state,Phase phase,Reason reason,long rows,
                          Instant began,Instant finished,Instant sourceBegan,Instant sourceFinished,
                          String inputSha256,Instant comparedAt) {
        public Outcome(int ordinal,State state,Phase phase,Reason reason,long rows,Instant began,Instant finished) {
            this(ordinal,state,phase,reason,rows,began,finished,null,null,"",null);
        }
    }
    private final MigrationPlan plan;
    private final UUID id=UUID.randomUUID();
    private final Instant began=Instant.now();
    private final List<Outcome> outcomes=new ArrayList<>();
    private boolean reportFailed;
    private Long schemaOid;
    MigrationRun(MigrationPlan plan) {
        this.plan=plan; this.schemaOid=plan.targetSchemaOid();
        for(int i=0;i<plan.tables().size();i++)outcomes.add(new Outcome(i+1,State.NOT_STARTED,Phase.PRECHECK,Reason.NONE,0,null,null));
    }
    public UUID id() { return id; }
    public MigrationPlan plan() { return plan; }
    public Instant began() { return began; }
    public synchronized List<Outcome> outcomes() { return List.copyOf(outcomes); }
    public synchronized boolean reportFailed() { return reportFailed; }
    synchronized void reportFailed(boolean value) { reportFailed=value; }
    synchronized void schemaOid(Long value) { schemaOid=value; }
    synchronized Long schemaOid() { return schemaOid; }
    synchronized void update(int index,State state,Phase phase,Reason reason,long rows) {
        Outcome prior=outcomes.get(index); Instant start=prior.began()==null?Instant.now():prior.began();
        var table=plan.tables().get(index);var evidence=table.evidence();
        outcomes.set(index,new Outcome(index+1,state,phase,reason,rows,start,state==State.RUNNING?null:Instant.now(),
                evidence==null?null:evidence.began(),evidence==null?null:evidence.finished(),table.data()==null?"":table.data().sha256(),
                phase==Phase.COMPARE?Instant.now():prior.comparedAt()));
    }
    public boolean completeWithinScope() { return !reportFailed() && outcomes().stream().allMatch(o -> o.state()==State.COMMITTED_VERIFIED); }
    public MigrationPlan retryPlan(MigrationPlan fresh) {
        if(!plan.request().equals(fresh.request()) || !plan.sourceIdentity().equals(fresh.sourceIdentity())
                || !plan.targetIdentity().equals(fresh.targetIdentity()) || !Objects.equals(schemaOid(),fresh.targetSchemaOid()))
            throw new IllegalStateException("Retry target or request changed");
        Map<String,MigrationPlan.Table> now=new HashMap<>(); fresh.tables().forEach(t -> now.put(t.targetName(),t));
        List<MigrationPlan.Table> selected=new ArrayList<>();Map<String,Integer> previousOrdinals=new HashMap<>();
        for(Outcome outcome:outcomes()) {
            if(!Set.of(State.NOT_STARTED,State.FAILED_BEFORE_WRITE,State.FAILED_ROLLED_BACK,State.CANCELLED_ROLLED_BACK).contains(outcome.state()))continue;
            MigrationPlan.Table previous=plan.tables().get(outcome.ordinal()-1),current=now.get(previous.targetName());
            if(current==null || !Objects.equals(previous.data(),current.data()) || !Objects.equals(previous.evidence(),current.evidence()) || !previous.columns().equals(current.columns())
                    || !previous.primaryKey().equals(current.primaryKey()) || !Objects.equals(previous.targetOid(),current.targetOid()) || current.targetHasRows())
                throw new IllegalStateException("Retry input, structure or target state changed");
            selected.add(current);
            previousOrdinals.put(current.targetName(),outcome.ordinal());
        }
        if(selected.isEmpty())throw new IllegalStateException("No table has a proven safe retry state");
        List<MigrationPlan.Finding> findings=new ArrayList<>(fresh.findings());
        findings.add(MigrationPreflight.manual("RETRY_SUBSET","本次仅处理原运行中明确可安全重试的项；原报告的已提交、跳过或未知项仍需单独查看"));
        return new MigrationPlan(fresh.request(),fresh.sourceIdentity(),fresh.targetIdentity(),fresh.targetSchemaOid(),selected,findings,id,previousOrdinals);
    }
    @Override public String toString() { return "MigrationRun["+id+"]"; }
}
