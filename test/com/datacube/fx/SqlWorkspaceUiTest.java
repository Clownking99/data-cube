package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.DraftConnectionProbe;
import com.datacube.service.ObjectTreeService;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqlWorkspaceUiTest {
    @TempDir Path directory;

    @Test void untouchedSessionAndExitPreservePreviousLayout() throws Exception {
        try(Fixture f=new Fixture()) {
            UUID id=UUID.randomUUID();var prior=new SqlWorkspace(1,List.of(new SqlWorkspace.Entry(id,3,1)),id);
            f.call(() -> f.owner.runtime().saveWorkspace(prior)).get(5,TimeUnit.SECONDS);f.writes.set(0);
            f.fx(() -> f.tabs.openTab("non SQL",new Label("synthetic")));
            f.tick(10000);assertEquals(TabCloseOutcome.COMPLETED,f.closeAll());
            assertEquals(prior,f.snapshot());assertEquals(0,f.writes.get());
        }
    }

    @Test void firstCheckpointBecomesEligibleWithoutAnotherUserAction() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlEditorPane pane = f.open("select synthetic", false);
            f.fx(() -> f.editor(pane).selectRange(11, 3));
            assertEquals(List.of(), f.call(f.workspace::capture).entries());
            f.flush(pane); f.tick(1000); f.tick(2000);
            var stored = f.snapshot();
            assertEquals(List.of(new SqlWorkspace.Entry(f.id(pane), 11, 3)), stored.entries());
            assertEquals(f.id(pane), stored.selectedDraftId());
        }
    }

    @Test void emptyNeverSavedExcludedButClearedCheckpointIncluded() throws Exception {
        try (Fixture f = new Fixture()) {
            SqlEditorPane saved = f.open("checkpoint", false); f.flush(saved);
            f.fx(() -> saved.setSqlText("")); f.flush(saved);
            f.open("", false); f.tick(1000); f.tick(2000);
            var layout = f.snapshot();
            assertEquals(List.of(new SqlWorkspace.Entry(f.id(saved), 0, 0)), layout.entries());
            assertNull(layout.selectedDraftId());
        }
    }

    @Test void sqlOrderSelectionAndReversePositionsFollowActualTabs() throws Exception {
        try (Fixture f = new Fixture()) {
            var a=f.open("select alpha", false); f.flush(a);
            f.fx(() -> f.tabs.openTab("non SQL", new Label("synthetic")));
            var b=f.open("select beta", false); f.flush(b);
            f.fx(() -> {
                f.editor(a).selectRange(9, 2); f.editor(b).selectRange(8, 3);
                List<Tab> desired=List.of(f.tabPane().getTabs().get(2),f.tabPane().getTabs().get(1),f.tabPane().getTabs().get(0));
                javafx.collections.FXCollections.sort(f.tabPane().getTabs(),Comparator.comparingInt(desired::indexOf));
                f.tabPane().getSelectionModel().select(1);
                assertEquals(3,f.tabPane().getTabs().size());
            });
            // JavaFX sort permutation preserves managed ownership; do not remove/re-add managed tabs.
            f.tick(1000); f.tick(2000);
            assertEquals(List.of(new SqlWorkspace.Entry(f.id(b),8,3),
                    new SqlWorkspace.Entry(f.id(a),9,2)), f.snapshot().entries());
            assertNull(f.snapshot().selectedDraftId());
        }
    }

    @Test void cancelledSingleCloseKeepsLayoutButActualRemovalUpdatesIt() throws Exception {
        try (Fixture f=new Fixture()) {
            var pane=f.open("checkpoint", true); f.flush(pane); f.tick(1000); f.tick(2000);
            var previous=f.snapshot();
            f.fx(() -> f.requestSingle(pane)); f.fx(() -> {});
            assertEquals(1, f.call(() -> f.tabPane().getTabs().size()));
            f.tick(3000); assertEquals(previous,f.snapshot());
            f.approve.set(true); f.fx(() -> f.requestSingle(pane));
            f.awaitTabs(0); f.tick(4000); f.tick(5000);
            assertEquals(List.of(),f.snapshot().entries());
            assertNull(f.snapshot().selectedDraftId());
        }
    }

    @Test void exitFreezesBeforeRemovalAndIncludesFinalDraftCheckpoint() throws Exception {
        try(Fixture f=new Fixture()) {
            var a=f.open("select alpha",false);
            var b=f.open("select beta",false);
            f.fx(() -> { f.editor(a).selectRange(9,2); f.editor(b).selectRange(8,3);
                f.tabPane().getSelectionModel().selectFirst(); });
            UUID aid=f.id(a), bid=f.id(b);
            assertEquals(TabCloseOutcome.COMPLETED,f.closeAll());
            var layout=f.snapshot();
            assertEquals(List.of(new SqlWorkspace.Entry(aid,9,2),new SqlWorkspace.Entry(bid,8,3)),layout.entries());
            assertEquals(aid,layout.selectedDraftId());
            assertEquals(0,f.call(() -> f.tabPane().getTabs().size()));
            assertEquals(1,f.writes.get());
        }
    }

    @Test void cancelledExitRetainsFrozenUntilExplicitActivity() throws Exception {
        try(Fixture f=new Fixture()) {
            var a=f.open("saved alpha",false); f.flush(a);
            var b=f.open("saved beta",true); f.flush(b); f.tick(1000); f.tick(2000);
            var prior=f.snapshot();
            assertEquals(TabCloseOutcome.CANCELLED,f.closeAll());
            assertEquals(1,f.call(() -> f.tabPane().getTabs().size()));
            f.tick(3000); f.tick(4000); assertEquals(prior,f.snapshot());
            f.fx(() -> f.editor(b).selectRange(6,1)); f.tick(5000); f.tick(6000);
            assertEquals(List.of(new SqlWorkspace.Entry(f.id(b),6,1)),f.snapshot().entries());
        }
    }

    @Test void layoutFailureCancelAllowsNewManagedTab() throws Exception {
        try(Fixture f=new Fixture()) {
            var a=f.open("save",false); f.flush(a); f.tick(1000); f.tick(2000);
            var old=f.snapshot(); f.failWrites.set(true);
            f.decision=SqlWorkspaceUi.Decision.CANCEL;
            assertEquals(TabCloseOutcome.CANCELLED,f.closeAll());
            assertEquals(old,f.snapshot());
            var next=f.open("new checkpoint",false); assertNotNull(next);
            f.failWrites.set(false);
            assertEquals(TabCloseOutcome.COMPLETED,f.closeAll());
            assertEquals(1,f.snapshot().entries().size());
        }
    }

    @Test void layoutFailureRetryAndIgnoreHaveDifferentPersistence() throws Exception {
        for(var decision:List.of(SqlWorkspaceUi.Decision.RETRY,SqlWorkspaceUi.Decision.IGNORE)) {
            try(Fixture f=new Fixture()) {
                var a=f.open("checkpoint",false); f.flush(a); f.tick(1000); f.tick(2000);
                var prior=f.snapshot(); f.fx(() -> f.editor(a).selectRange(8,2));
                UUID id=f.id(a); f.failWrites.set(true); f.decision=decision;
                assertEquals(TabCloseOutcome.COMPLETED,f.closeAll());
                assertEquals(1,f.decisions.get());
                assertEquals(decision==SqlWorkspaceUi.Decision.IGNORE ? prior.entries()
                        : List.of(new SqlWorkspace.Entry(id,8,2)),f.snapshot().entries());
            }
        }
    }

    @Test void reservationFinishingDuringExitIsCapturedBeforeGuardClose() throws Exception {
        try(Fixture f=new Fixture()) {
            AtomicReference<CompletionStage<TabCloseOutcome>> close=new AtomicReference<>();
            f.duringFactory=() -> close.set(f.tabs.closeAllManagedTabsMandatory());
            var pane=f.open("reserved checkpoint",false);
            UUID id=f.idBeforeRemoval.get();
            assertEquals(TabCloseOutcome.COMPLETED,close.get().toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertEquals(List.of(id),f.snapshot().entries().stream().map(SqlWorkspace.Entry::draftId).toList());
            assertEquals(0,f.call(() -> f.tabPane().getTabs().size()));
        }
    }

    @Test void callerCancellationDoesNotCancelInternalCloseOrPublication() throws Exception {
        try(Fixture f=new Fixture()) {
            var a=f.open("checkpoint",false); f.flush(a);
            CompletableFuture<SqlWorkspaceUi.Decision> pending=new CompletableFuture<>();
            f.pendingDecision=pending; f.failWrites.set(true);
            var first=f.tabs.closeAllManagedTabsMandatory().toCompletableFuture();
            assertTrue(f.decisionEntered.await(5,TimeUnit.SECONDS));
            first.cancel(false);
            var second=f.tabs.closeAllManagedTabsMandatory().toCompletableFuture();
            assertFalse(second.isDone());
            f.failWrites.set(false); pending.complete(SqlWorkspaceUi.Decision.RETRY);
            assertEquals(TabCloseOutcome.COMPLETED,second.get(5,TimeUnit.SECONDS));
            assertEquals(1,f.snapshot().entries().size());
        }
    }

    @Test void partialAbortFailureNeverReopensRegistryOrRunsTeardown() throws Exception {
        try(Fixture f=new Fixture()) {
            CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
            AtomicInteger teardown=new AtomicInteger();
            AtomicReference<CompletionStage<ShutdownOutcome>> result=new AtomicReference<>();
            var shutdown=new AsyncShutdownCoordinator(f.tabs::closeAllManagedTabsMandatory,
                    Runnable::run,teardown::incrementAndGet,ignored -> {});
            f.fx(() -> assertNull(f.tabs.openManagedTab("failed construction",abort -> {
                abort.bind(() -> {
                    started.countDown();
                    try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch(InterruptedException e){throw new AssertionError(e);}
                    throw new IllegalStateException("synthetic abort failure");
                });
                result.set(shutdown.shutdown());
                throw new IllegalStateException("synthetic construction failure");
            })));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertFalse(result.get().toCompletableFuture().isDone());
            release.countDown();
            assertEquals(ShutdownOutcome.FAILED_PARTIAL,result.get().toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertEquals(0,teardown.get());
            AtomicInteger factories=new AtomicInteger();
            f.fx(() -> assertNull(f.tabs.openManagedTab("rejected",abort -> {
                factories.incrementAndGet(); throw new AssertionError("must not construct");
            })));
            assertEquals(0,factories.get());
            assertEquals(0,f.decisions.get());
        }
    }

    @Test void repeatedCancelledExitKeepsOriginalFrozenLayoutUntilExplicitAction() throws Exception {
        try(Fixture f=new Fixture()) {
            var a=f.open("saved alpha",false);f.flush(a);
            var b=f.open("saved beta",true);f.flush(b);f.tick(1000);f.tick(2000);
            var previous=f.snapshot();
            assertEquals(TabCloseOutcome.CANCELLED,f.closeAll());
            f.approve.set(true);
            assertEquals(TabCloseOutcome.COMPLETED,f.closeAll());
            assertEquals(previous.entries(),f.snapshot().entries());
            assertEquals(previous.selectedDraftId(),f.snapshot().selectedDraftId());
        }
    }

    @Test void finalCallbackCannotDowngradeMandatoryAbortFailure() throws Exception {
        try(Fixture f=new Fixture()) {
            f.fx(() -> {
                f.tabs.workspaceLifecycle(() -> CompletableFuture.completedFuture(null),
                        ignored -> CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED));
                assertNull(f.tabs.openManagedTab("abort fails",abort -> {
                    abort.bind(() -> { throw new IllegalStateException("synthetic abort failure"); });
                    throw new IllegalStateException("synthetic factory failure");
                }));
            });
            assertEquals(TabCloseOutcome.FAILED_PARTIAL,f.closeAll());
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path root=directory.resolve(UUID.randomUUID().toString());
        final DraftConnectionProbe probe=new DraftConnectionProbe();
        final FxTaskRunner runner=new FxTaskRunner();
        final SessionContext context=new SessionContext();
        final AppSettings settings=new AppSettings(root.resolve("settings"));
        final SqlHistoryStore history=new SqlHistoryStore(root.resolve("history"));
        final ShortcutSettings shortcuts=new ShortcutSettings(root.resolve("shortcuts"));
        final List<SqlEditorPane> panes=new ArrayList<>();
        final AtomicBoolean approve=new AtomicBoolean(),failWrites=new AtomicBoolean();
        final AtomicInteger writes=new AtomicInteger(),decisions=new AtomicInteger();
        final CountDownLatch decisionEntered=new CountDownLatch(1);
        final AtomicReference<UUID> idBeforeRemoval=new AtomicReference<>();
        volatile SqlWorkspaceUi.Decision decision=SqlWorkspaceUi.Decision.CANCEL;
        volatile CompletableFuture<SqlWorkspaceUi.Decision> pendingDecision;
        Runnable duringFactory=() -> {};
        long now;
        final ContentTabPane tabs;
        final SqlDraftUi owner;
        final SqlWorkspaceUi workspace;
        Fixture() throws Exception {
            tabs=call(ContentTabPane::new);
            owner=call(() -> new SqlDraftUi(root.resolve("drafts")));
            ready();
            // Fault delegation still executes the real store, lock and writer for every successful operation.
            fx(() -> {
                Field backend=field(SqlDraftCoordinator.class,"backend");
                try {
                    Object actual=backend.get(owner.runtime());
                    Object wrapper=Proxy.newProxyInstance(actual.getClass().getClassLoader(),
                            new Class<?>[]{backend.getType()},(proxy,method,args) -> {
                        if(method.getName().equals("saveWorkspace")) {
                            writes.incrementAndGet();
                            if(failWrites.get()) throw new java.io.IOException("synthetic private failure");
                        }
                        method.setAccessible(true);
                        try { return method.invoke(actual,args); }
                        catch(InvocationTargetException e) { throw e.getCause(); }
                    });
                    backend.set(owner.runtime(),wrapper);
                } catch(IllegalAccessException e) { throw new AssertionError(e); }
            });
            workspace=call(() -> {
                new Scene(tabPane(),1000,700);
                return owner.attachWorkspace(tabs,() -> now,() -> {
                    decisions.incrementAndGet(); decisionEntered.countDown();
                    if(pendingDecision!=null) return pendingDecision;
                    if(decision==SqlWorkspaceUi.Decision.RETRY) failWrites.set(false);
                    return CompletableFuture.completedFuture(decision);
                });
            });
        }
        SqlEditorPane open(String sql,boolean rejecting) throws Exception {
            return call(() -> {
                AtomicReference<SqlEditorPane> result=new AtomicReference<>();
                Tab tab=tabs.openManagedTab("synthetic SQL",abort -> {
                    var pane=new SqlEditorPane(context,probe.manager,new ObjectTreeService(probe.manager),
                            settings,null,null,null,history,shortcuts,runner);
                    result.set(pane); panes.add(pane); abort.bind(pane::closeResources);
                    pane.setSqlText(sql); owner.bind(pane);
                    idBeforeRemoval.set(binding(pane).id()); duringFactory.run();
                    AsyncTabCloseGuard guard=() -> rejecting && !approve.get()
                            ? CompletableFuture.completedFuture(CloseGuardOutcome.REJECTED) : pane.requestMandatoryClose();
                    return new ContentTabPane.ManagedTabSpec(pane.getNode(),guard,guard,
                            pane::finalizeCloseOnFx,pane::closeResources);
                });
                assertNotNull(tab); owner.installed(tab.getContent()); return result.get();
            });
        }
        TabPane tabPane(){return (TabPane)tabs.getNode();}
        CodeArea editor(SqlEditorPane pane){return (CodeArea)get(binding(pane),"editor");}
        SqlDraftEditorBinding binding(SqlEditorPane pane){return (SqlDraftEditorBinding)get(pane,"draftBinding");}
        UUID id(SqlEditorPane pane) throws Exception {return call(() -> binding(pane).id());}
        void flush(SqlEditorPane pane) throws Exception {
            call(() -> ((SqlDraftCoordinator.Handle)get(binding(pane),"handle")).flush()).get(5,TimeUnit.SECONDS);
            fx(() -> {});
        }
        void requestSingle(SqlEditorPane pane) {
            Tab tab=tabPane().getTabs().stream().filter(t -> t.getContent()==pane.getNode()).findFirst().orElseThrow();
            tab.getOnCloseRequest().handle(new javafx.event.Event(Tab.TAB_CLOSE_REQUEST_EVENT));
        }
        void ready() throws Exception {
            for(int n=0;n<100;n++) {
                if(!call(() -> owner.runtime().managementPending())) return;
                Thread.sleep(10);
            }
            fail("runtime initialization");
        }
        void tick(long time) throws Exception {fx(() -> {now=time;workspace.pulse();}); barrier();}
        void barrier() throws Exception {call(() -> owner.runtime().workspaceSnapshot()).get(5,TimeUnit.SECONDS);fx(() -> {});}
        SqlWorkspace snapshot() throws Exception {return call(() -> owner.runtime().workspaceSnapshot()).get(5,TimeUnit.SECONDS).workspace();}
        TabCloseOutcome closeAll() throws Exception {return tabs.closeAllManagedTabsMandatory().toCompletableFuture().get(5,TimeUnit.SECONDS);}
        void awaitTabs(int count) throws Exception {
            for(int n=0;n<100;n++){if(call(() -> tabPane().getTabs().size())==count)return;Thread.sleep(10);}
            fail("tab settlement");
        }
        <T>T call(Callable<T> action)throws Exception{return FxUiTestSupport.call(action);}
        void fx(Runnable action)throws Exception{call(() -> {action.run();return null;});}
        public void close() throws Exception {
            try {for(var pane:panes){pane.closeResources();fx(pane::finalizeCloseOnFx);}}
            finally{try{owner.closeFromBackground();}finally{runner.close();probe.manager.closeAll();}}
            assertEquals(0,probe.providers.get());assertEquals(0,probe.sessions.get());
            assertEquals(0,probe.metadata.get());assertEquals(0,probe.network.get());
        }
    }
    private static Field field(Class<?> type,String name){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new AssertionError(e);}}
    private static Object get(Object target,String name){try{return field(target.getClass(),name).get(target);}catch(Exception e){throw new AssertionError(e);}}
}
