package com.datacube.cli;

import com.datacube.migration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationConsoleTest {
    @TempDir Path root;
    @Test void executionRequiresPreparedPlanAndExactTargetConfirmation() {
        FakeBackend absent=run("7","0");assertEquals(0,absent.executes);
        FakeBackend cancelled=run("3","7","wrong-schema","0");assertEquals(1,cancelled.prepares);assertEquals(0,cancelled.executes);
        FakeBackend accepted=run("3","7","dest","0");assertEquals(1,accepted.executes);assertEquals(MigrationRequest.Mode.EMPTY_TABLES_ONLY,accepted.executed.request().mode());
    }
    @Test void exportAndPreflightDoNotAutomaticallyImportAndSkipModeIsExplicit() {
        FakeBackend planned=run("5","0");assertEquals(2,planned.exports);assertEquals(1,planned.prepares);assertEquals(0,planned.executes);
        FakeBackend skip=run("4","7","dest","0");assertEquals(1,skip.executes);assertEquals(MigrationRequest.Mode.SKIP_NONEMPTY,skip.executed.request().mode());
    }
    @Test void failedPreflightAndUnknownCommitCannotAuthorizeAnotherWrite() {
        FakeBackend failed=new FakeBackend();failed.fail=true;session(failed,"3","7","0");assertEquals(0,failed.executes);assertFalse(failed.logger.messages.toString().contains("synthetic-secret"));
        FakeBackend unknown=new FakeBackend();unknown.state=MigrationRun.State.COMMIT_UNKNOWN;session(unknown,"3","7","dest","8","7","0");assertEquals(1,unknown.executes);assertEquals(2,unknown.prepares);
    }
    private FakeBackend run(String... answers){FakeBackend backend=new FakeBackend();session(backend,answers);return backend;}
    private void session(FakeBackend backend,String... answers) {
        Queue<String> queue=new ArrayDeque<>(List.of(answers));
        var request=new MigrationRequest(new MigrationRequest.Endpoint("jdbc:synthetic:source","source-user","source-secret"),new MigrationRequest.Endpoint("jdbc:synthetic:target","target-user","target-secret"),"OWNER","dest",root.resolve("dest"),MigrationRequest.Mode.EMPTY_TABLES_ONLY,false);
        MigrationConsole.session((label,defaultValue,hint)->{assertFalse(label.contains("secret"));assertFalse(queue.isEmpty(),label);return queue.remove();},backend.logger,backend,request,2);
        assertTrue(queue.isEmpty());
    }
    private static final class TestLogger extends ConsoleLogger {
        final List<String> messages=new ArrayList<>();
        @Override public void logInfo(String value){messages.add(value);}
        @Override public void logWarn(String value){messages.add(value);}
        @Override public void logErr(String value){messages.add(value);}
        @Override public void logToFile(String value){messages.add(value);}
    }
    private static final class FakeBackend extends MigrationOperations {
        final TestLogger logger;int prepares,exports,executes;boolean fail;MigrationPlan executed;MigrationRun.State state=MigrationRun.State.COMMITTED_VERIFIED;
        FakeBackend(){this(new TestLogger());}FakeBackend(TestLogger logger){super(logger,(u,n,p)->{throw new AssertionError("No network allowed");});this.logger=logger;}
        @Override public MigrationPlan prepare(MigrationRequest request,MigrationCancellation cancellation)throws Exception{prepares++;if(fail)throw new IllegalStateException("synthetic-secret");return MigrationPlanFixture.create(request);}
        @Override public void export(MigrationRequest request,MigrationCancellation cancellation,int concurrency,boolean data){exports++;}
        @Override public MigrationRun execute(MigrationPlan plan,MigrationPlan.Approval approval,MigrationRequest request,MigrationCancellation cancellation){assertNotNull(approval);assertEquals(plan.request(),request);executes++;executed=plan;return MigrationPlanFixture.result(plan,state);}
    }
}
