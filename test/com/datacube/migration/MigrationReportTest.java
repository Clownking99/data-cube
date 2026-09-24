package com.datacube.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationReportTest {
    @TempDir Path root;
    @Test void checkpointsRoundTripAndRecoveredRunningIsUnknownWithoutRetryAuthority() throws Exception {
        Instant now=Instant.now(); UUID id=UUID.randomUUID();
        var report=new MigrationReport(id,now,List.of(
                new MigrationRun.Outcome(1,MigrationRun.State.COMMITTED_VERIFIED,MigrationRun.Phase.FINISHED,MigrationRun.Reason.NONE,4,now,now),
                new MigrationRun.Outcome(2,MigrationRun.State.RUNNING,MigrationRun.Phase.COMMIT,MigrationRun.Reason.NONE,3,now,null)),false);
        report.checkpoint(root); Path file=root.resolve(id+".report"); MigrationReport restored=MigrationReport.read(file);
        assertEquals(report.outcomes().getFirst(),restored.outcomes().getFirst());
        assertEquals(MigrationRun.State.COMMIT_UNKNOWN,restored.outcomes().get(1).state());
        assertEquals(MigrationRun.Reason.COMMIT_UNCERTAIN,restored.outcomes().get(1).reason());
        assertTrue(restored.display().contains("仅供查看")); assertTrue(restored.display().contains("T0002"));
        assertTrue(Arrays.stream(MigrationReport.class.getMethods()).noneMatch(m -> m.getName().toLowerCase(Locale.ROOT).contains("retry") || m.getName().equals("approve")));
        new MigrationReport(id,now,List.of(report.outcomes().getFirst()),false).checkpoint(root);
        assertEquals(1,MigrationReport.read(file).outcomes().size());
        try(var files=Files.list(root)){assertEquals(1,files.count());}
    }
    @Test void corruptedUnknownAndOversizedReportsAreRejectedWithoutChangingOriginal() throws Exception {
        var report=new MigrationReport(UUID.randomUUID(),Instant.now(),List.of(),false);report.checkpoint(root);
        Path file=root.resolve(report.id()+".report");byte[] bytes=Files.readAllBytes(file);bytes[5]^=1;Files.write(file,bytes);
        assertThrows(IOException.class,() -> MigrationReport.read(file));assertArrayEquals(bytes,Files.readAllBytes(file));
        report.checkpoint(root);bytes=Files.readAllBytes(file);bytes[3]^=1;
        byte[] digest=MigrationFiles.digest().digest(Arrays.copyOf(bytes,bytes.length-32));System.arraycopy(digest,0,bytes,bytes.length-32,32);Files.write(file,bytes);
        assertThrows(IOException.class,() -> MigrationReport.read(file));assertArrayEquals(bytes,Files.readAllBytes(file));
        Files.write(file,new byte[1024*1024+33]);assertThrows(IOException.class,() -> MigrationReport.read(file));
    }
    @Test void retainsSourceWindowInputFingerprintAndPriorRunOrdinalWithoutNamesOrCredentials() throws Exception {
        Instant start=Instant.parse("2026-09-24T12:00:00Z"),end=start.plusSeconds(3),compared=end.plusSeconds(30);UUID prior=UUID.randomUUID();
        var outcome=new MigrationRun.Outcome(1,MigrationRun.State.COMMITTED_VERIFIED,MigrationRun.Phase.FINISHED,MigrationRun.Reason.NONE,2,compared,compared.plusSeconds(2),start,end,"a".repeat(64),compared);
        var report=new MigrationReport(UUID.randomUUID(),compared,List.of(outcome),false,prior,Map.of(1,7));report.checkpoint(root);
        var restored=MigrationReport.read(root.resolve(report.id()+".report"));assertEquals(report,restored);
        assertTrue(restored.display().contains("部分重试"));assertTrue(restored.display().contains("T0007"));assertTrue(restored.display().contains(start.toString()));
        assertTrue(restored.display().contains(compared.toString()));
    }
}
