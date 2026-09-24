package com.datacube.fx;

import com.datacube.core.MigrationLogger;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.migration.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class MigrationWorkflowTest {
    @TempDir Path root;

    @Test void preflightNeverImportsAndConfirmationCancelKeepsWriteCountZero() throws Exception {
        try(Fixture f=new Fixture(plan->false)) {
            f.action("prepare");assertEquals(1,f.backend.get().prepares.get());assertEquals(0,f.backend.get().executes.get());
            FxUiTestSupport.call(()->{assertFalse(f.button("execute").isDisabled());assertTrue(f.review().getText().contains("没有共同快照"));return null;});
            f.action("execute");assertEquals(0,f.backend.get().executes.get());
            FxUiTestSupport.call(()->{assertTrue(f.status().getText().contains("取消确认"));return null;});
        }
    }
    @Test void confirmedImportUsesPreparedSnapshotAndShowsUnknownWithoutSuccessLabel() throws Exception {
        try(Fixture f=new Fixture(plan->true)) {
            f.backend.get().state=MigrationRun.State.COMMIT_UNKNOWN;
            f.action("prepare");f.action("execute");assertEquals(1,f.backend.get().executes.get());
            FxUiTestSupport.call(()->{assertTrue(f.status().getText().contains("未知"));assertTrue(f.report().getText().contains("禁止直接重试"));assertTrue(f.button("execute").isDisabled());return null;});
        }
    }
    @Test void inputChangesDuringModalConfirmationInvalidateApprovalBeforeBackendCall() throws Exception {
        AtomicReference<Fixture> owner=new AtomicReference<>();
        try(Fixture f=new Fixture(plan->{owner.get().text("schema").setText("changed");return true;})) {
            owner.set(f);f.action("prepare");f.action("execute");assertEquals(0,f.backend.get().executes.get());
            FxUiTestSupport.call(()->{assertTrue(f.button("execute").isDisabled());assertTrue(f.status().getText().contains("配置已变化"));return null;});
        }
    }
    @Test void latePreflightCannotAuthorizeChangedInputsAndShutdownClosesItsResource() throws Exception {
        try(Fixture f=new Fixture(plan->true)) {
            f.backend.get().gate=new CountDownLatch(1);CountDownLatch completed=f.start("prepare");
            assertTrue(f.backend.get().entered.await(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(()->{f.text("schema").setText("changed");return null;});f.backend.get().gate.countDown();
            assertTrue(completed.await(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(()->{assertTrue(f.button("execute").isDisabled());assertTrue(f.status().getText().contains("配置已变化"));return null;});
            f.backend.get().gate=new CountDownLatch(1);f.backend.get().entered=new CountDownLatch(1);f.start("prepare");
            assertTrue(f.backend.get().entered.await(5,TimeUnit.SECONDS));
            FxUiTestSupport.call(()->{f.controller.shutdown();return null;});f.backend.get().gate.countDown();
            assertTrue(f.backend.get().closed.await(5,TimeUnit.SECONDS));
        }
    }
    @Test void exportAndPrepareStopsBeforeTargetWriteAndFailureNeverShowsCompleted() throws Exception {
        try(Fixture f=new Fixture(plan->true)) {
            f.action("all");assertEquals(2,f.backend.get().exports.get());assertEquals(1,f.backend.get().prepares.get());assertEquals(0,f.backend.get().executes.get());
            f.backend.get().fail=true;f.action("prepare");
            FxUiTestSupport.call(()->{assertTrue(f.status().getText().contains("失败"));assertTrue(f.button("execute").isDisabled());assertFalse(f.log().getText().contains("synthetic-secret"));return null;});
        }
    }
    @Test void targetStatisticsDoesNotRequireSourceAndHasNoImportOrSchemaCreation() throws Exception {
        try(Fixture f=new Fixture(plan->true)) {
            FxUiTestSupport.call(()->{f.text("source.user").setText("");f.text("source.url").setText("");return null;});
            f.action("statistics");assertEquals(1,f.backend.get().statistics.get());assertEquals(0,f.backend.get().executes.get());assertEquals(0,f.backend.get().prepares.get());
            FxUiTestSupport.call(()->{assertTrue(f.status().getText().contains("非迁移一致性"));return null;});
        }
    }
    private final class Fixture implements AutoCloseable {
        final FxTaskRunner runner=new FxTaskRunner(); final AtomicReference<FakeOperations> backend=new AtomicReference<>();
        final AtomicReference<CountDownLatch> completed=new AtomicReference<>();MainController controller;VBox pane;
        Fixture(Predicate<MigrationPlan> confirmation) throws Exception {
            FxUiTestSupport.call(()->{
                controller=new MainController(runner.scope(),command->runner.submit(command),logger->{var operations=new FakeOperations(logger);backend.set(operations);return operations;},confirmation);
                pane=controller.createMigrationContent();new Scene(pane,1100,900);pane.applyCss();pane.layout();
                text("directory").setText(root.toString());text("source.password").setText("synthetic-secret");
                status().textProperty().addListener((o,a,b)->{var latch=completed.get();if(!controller.isRunning() && latch!=null)latch.countDown();});return null;
            });
        }
        Button button(String id){return (Button)find(pane,"migration."+id);}
        TextField text(String id){return (TextField)find(pane,"migration."+id);}
        TextArea review(){return (TextArea)find(pane,"migration.review");} TextArea report(){return (TextArea)find(pane,"migration.report");} TextArea log(){return (TextArea)find(pane,"migration.log");}
        Label status(){return (Label)find(pane,"migration.status");}
        CountDownLatch start(String id)throws Exception{return FxUiTestSupport.call(()->{CountDownLatch latch=new CountDownLatch(1);completed.set(latch);((Button)find(pane,"migration."+id)).fire();return latch;});}
        void action(String id)throws Exception{assertTrue(start(id).await(5,TimeUnit.SECONDS),id);}
        public void close()throws Exception{FxUiTestSupport.call(()->{controller.shutdown();return null;});runner.close();}
    }
    // CSS treats dots in IDs as class selectors, so locate exact IDs through the scene tree.
    private static javafx.scene.Node find(javafx.scene.Parent root,String id){
        if(id.equals(root.getId()))return root;
        if(root instanceof TabPane tabs)for(var tab:tabs.getTabs())if(tab.getContent() instanceof javafx.scene.Parent content){var found=find(content,id);if(found!=null)return found;}
        for(javafx.scene.Node node:root.getChildrenUnmodifiable()){
            if(id.equals(node.getId()))return node;
            if(node instanceof javafx.scene.Parent child){var found=find(child,id);if(found!=null)return found;}
        }
        return null;
    }
    private static final class FakeOperations extends MigrationOperations {
        final AtomicInteger prepares=new AtomicInteger(),executes=new AtomicInteger(),exports=new AtomicInteger(),statistics=new AtomicInteger();
        volatile CountDownLatch gate,entered=new CountDownLatch(1),closed=new CountDownLatch(1);
        volatile boolean fail;volatile MigrationRun.State state=MigrationRun.State.COMMITTED_VERIFIED;
        FakeOperations(MigrationLogger logger){super(logger,(u,n,p)->{throw new AssertionError("No network allowed");});}
        @Override public MigrationPlan prepare(MigrationRequest request,MigrationCancellation cancellation)throws Exception{
            prepares.incrementAndGet();var resourceClosed=new CountDownLatch(1);closed=resourceClosed;cancellation.register(resourceClosed::countDown);entered.countDown();if(gate!=null)gate.await();if(fail)throw new IllegalStateException("synthetic-secret");
            return MigrationPlanFixture.create(request);
        }
        @Override public MigrationRun execute(MigrationPlan plan,MigrationPlan.Approval approval,MigrationRequest request,MigrationCancellation cancellation){executes.incrementAndGet();assertEquals(plan.request(),request);assertNotNull(approval);return MigrationPlanFixture.result(plan,state);}
        @Override public void export(MigrationRequest request,MigrationCancellation cancellation,int concurrency,boolean data){exports.incrementAndGet();}
        @Override public PgVerifier.Statistics statistics(MigrationRequest request,MigrationCancellation cancellation){statistics.incrementAndGet();return new PgVerifier.Statistics(1,2L,0,0);}
    }
}
