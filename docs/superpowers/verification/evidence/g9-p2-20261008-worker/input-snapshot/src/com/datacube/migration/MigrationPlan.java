package com.datacube.migration;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reviewable immutable plan; approval is local, single-use and bound to this exact request. */
public final class MigrationPlan {
    public enum Level { INFO, MANUAL, BLOCK }
    public record Finding(Level level, String code, String message) { }
    public record Column(String sourceName, String targetName, String sourceType, String pgType, boolean nullable) {
        public boolean numeric() { return pgType.equals("NUMERIC"); }
    }
    public record Table(String sourceName, String targetName, List<Column> columns,
                        List<String> primaryKey, Long targetOid, boolean targetHasRows,
                        MigrationFiles.Snapshot data, MigrationExportEvidence evidence, List<Finding> findings) {
        public Table { columns=List.copyOf(columns); primaryKey=List.copyOf(primaryKey); findings=List.copyOf(findings); }
        public Table(String sourceName,String targetName,List<Column> columns,List<String> primaryKey,Long targetOid,boolean targetHasRows,MigrationFiles.Snapshot data,List<Finding> findings) {
            this(sourceName,targetName,columns,primaryKey,targetOid,targetHasRows,data,null,findings);
        }
        @Override public String toString() { return "MigrationTable[redacted]"; }
    }
    public static final class Approval {
        private final MigrationPlan plan;
        private final AtomicBoolean used = new AtomicBoolean();
        private Approval(MigrationPlan plan) { this.plan = plan; }
        void consume(MigrationPlan actual, MigrationRequest current) {
            if (plan != actual || !plan.request.equals(current) || !plan.canRun()
                    || Instant.now().isAfter(plan.created.plusSeconds(600)) || !used.compareAndSet(false, true))
                throw new IllegalStateException("Migration approval is missing, stale or already used");
        }
    }
    private final UUID id = UUID.randomUUID();
    private final Instant created = Instant.now();
    private final MigrationRequest request;
    private final String sourceIdentity, targetIdentity;
    private final Long targetSchemaOid;
    private final UUID previousRun;
    private final Map<String,Integer> previousOrdinals;
    private final List<Table> tables;
    private final List<Finding> findings;

    MigrationPlan(MigrationRequest request, String sourceIdentity, String targetIdentity, Long targetSchemaOid,
                  List<Table> tables, List<Finding> findings) {
        this(request,sourceIdentity,targetIdentity,targetSchemaOid,tables,findings,null,Map.of());
    }
    MigrationPlan(MigrationRequest request,String sourceIdentity,String targetIdentity,Long targetSchemaOid,
                  List<Table> tables,List<Finding> findings,UUID previousRun,Map<String,Integer> previousOrdinals) {
        this.request=Objects.requireNonNull(request); this.sourceIdentity=sourceIdentity; this.targetIdentity=targetIdentity;
        this.targetSchemaOid=targetSchemaOid; this.tables=List.copyOf(tables); this.findings=List.copyOf(findings);
        this.previousRun=previousRun;this.previousOrdinals=Map.copyOf(previousOrdinals);
    }
    public UUID id() { return id; }
    public MigrationRequest request() { return request; }
    public String sourceIdentity() { return sourceIdentity; }
    public String targetIdentity() { return targetIdentity; }
    public Long targetSchemaOid() { return targetSchemaOid; }
    public UUID previousRun() { return previousRun; }
    public Map<Integer,Integer> previousOrdinals() {
        Map<Integer,Integer> result=new TreeMap<>();
        for(int i=0;i<tables.size();i++){Integer previous=previousOrdinals.get(tables.get(i).targetName());if(previous!=null)result.put(i+1,previous);}
        return Map.copyOf(result);
    }
    public List<Table> tables() { return tables; }
    public List<Finding> findings() { return findings; }
    public boolean canRun() { return !tables.isEmpty() && findings.stream().noneMatch(f -> f.level()==Level.BLOCK)
            && tables.stream().flatMap(t -> t.findings().stream()).noneMatch(f -> f.level()==Level.BLOCK); }
    /** Call only after the human has reviewed the specific target, mode, scope and limitations. */
    public Approval approve(MigrationRequest current) {
        if (!request.equals(current) || !canRun()) throw new IllegalStateException("Migration plan cannot be approved");
        return new Approval(this);
    }
    @Override public String toString() { return "MigrationPlan["+id+", tables="+tables.size()+"]"; }
}
