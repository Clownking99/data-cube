package com.datacube.migration;

import java.nio.file.*;
import java.util.List;

/** Synthetic plan factory shared by GUI/CLI boundary tests; never connects to a database. */
public final class MigrationPlanFixture {
    private MigrationPlanFixture() { }
    public static MigrationPlan create(MigrationRequest request) throws Exception {
        Path directory=request.directory();Files.createDirectories(directory.resolve("data"));
        Path file=directory.resolve("data/items.sql");if(!Files.exists(file))Files.writeString(file,"INSERT INTO items (id) VALUES (1);\n");
        var input=MigrationFiles.inspect(directory,"items",new MigrationCancellation());
        var table=new MigrationPlan.Table("ITEMS","items",List.of(new MigrationPlan.Column("ID","id","NUMBER","NUMERIC",false)),List.of("id"),20L,false,input,List.of());
        return new MigrationPlan(request,MigrationPreflight.fingerprint("synthetic-source"),MigrationPreflight.fingerprint("synthetic-target"),10L,List.of(table),List.of(MigrationPreflight.manual("LIMITED","其他对象待人工审阅；源与目标没有共同快照")));
    }
    public static MigrationRun result(MigrationPlan plan,MigrationRun.State state) {
        var run=new MigrationRun(plan);run.update(0,state,MigrationRun.Phase.FINISHED,state==MigrationRun.State.COMMIT_UNKNOWN?MigrationRun.Reason.COMMIT_UNCERTAIN:MigrationRun.Reason.NONE,1);return run;
    }
}
