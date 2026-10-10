package com.datacube.fx;

import com.datacube.config.*;
import com.datacube.fx.task.FxTaskRunner;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic FX actions on the real production Alert and shell mandatory-close assembly. */
class AppShellWorkspaceShutdownTest {
    @BeforeAll static void preloadFxNatives() throws Exception {
        FxUiTestSupport.call(() -> {
            Label label = new Label("synthetic workspace probe");
            label.setEffect(new javafx.scene.effect.DropShadow());
            new Scene(new javafx.scene.layout.StackPane(label), 100, 40);
            label.applyCss(); label.snapshot(null, null); return null;
        });
    }

    @Test void fixtureSeedWaitsForActualInFlightWorkspacePublication() throws Exception {
        try (Fixture f = new Fixture()) {
            Tab tab = f.open("seed-barrier.sql", "select 1;\n");
            f.checkpoint(tab);
            SqlWorkspace prior = f.capture(); f.seed(prior);
            SqlWorkspace seeded = new SqlWorkspace(prior.capturedAt() + 1, prior.entries(), prior.selectedDraftId());
            SqlWorkspaceActivity owner = FxUiTestSupport.call(() -> f.drafts.workspace().owner());
            var token = FxUiTestSupport.call(() -> owner.freezeForExit(prior));
            Object directory = get(get(get(f.drafts.runtime(), "backend"), "store"), "directory");
            Object delegate = get(directory, "mover");
            Class<?> type = delegate.getClass().getInterfaces()[0];
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger publications = new AtomicInteger();
            Object gate = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                Path target = (Path) args[1];
                if (target.equals(f.workspacePath) && publications.incrementAndGet() == 1) {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS), "controlled workspace publication not released");
                }
                method.setAccessible(true);
                try { return method.invoke(delegate, args); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            set(directory, "mover", gate);
            try {
                var first = FxUiTestSupport.call(() -> owner.saveFrozen(token, prior));
                assertTrue(entered.await(5, TimeUnit.SECONDS), "actual workspace atomic publication not entered");
                var seed = f.seedAsync(seeded);
                assertFalse(first.isDone()); assertFalse(seed.isDone(), "seed must wait for the actual in-flight save");
                FxUiTestSupport.call(() -> { owner.pulse(); return null; });
                assertEquals(1, publications.get(), "seed must not submit a competing publication");
                release.countDown(); first.get(5, TimeUnit.SECONDS); seed.get(5, TimeUnit.SECONDS);
                assertEquals(2, publications.get(), "seed must publish after the first save settles");
                var stored = FxUiTestSupport.call(() -> f.drafts.runtime().workspaceSnapshot()).get(5, TimeUnit.SECONDS);
                assertEquals(seeded, stored.workspace());
                FxUiTestSupport.call(() -> { assertEquals(SqlWorkspaceActivity.Status.SAVED, owner.status()); return null; });
                assertTrue(f.attempts.isEmpty(), "seed clears observations only after its publication completes");
                var entry = seeded.entries().getFirst();
                SqlWorkspace changed = new SqlWorkspace(seeded.capturedAt() + 1,
                        List.of(new SqlWorkspace.Entry(entry.draftId(), entry.anchor() + 1, entry.caret() + 1)), seeded.selectedDraftId());
                FxUiTestSupport.call(() -> {
                    owner.activity(changed);
                    assertEquals(SqlWorkspaceActivity.Status.PENDING, owner.status(), "seed must restore later activity admission");
                    return null;
                });
            } finally { release.countDown(); }
        }
    }

    @Test void cancelRepeatedExitThenExplicitFileActivityPublishesNewLayout() throws Exception {
        try (Fixture f = new Fixture()) {
            Tab a = f.open("initial-a.sql", "select 101;\n");
            Tab b = f.open("initial-b.sql", "select 202;\n");
            f.checkpoint(a); f.checkpoint(b);
            SqlWorkspace original = f.capture(); f.seed(original);
            byte[] prior = Files.readAllBytes(f.workspacePath);
            f.fail.set(true);
            var first = f.beginDecision(); first.choose("取消退出");
            assertEquals(ShutdownOutcome.CANCELLED, first.outcome.get(10, TimeUnit.SECONDS));
            f.assertCancelled(prior);
            SqlWorkspace frozen = f.frozen();
            var second = f.beginDecision(); second.choose("取消退出");
            assertEquals(ShutdownOutcome.CANCELLED, second.outcome.get(10, TimeUnit.SECONDS));
            f.assertCancelled(prior);
            assertEquals(frozen, f.frozen(), "no activity must reuse the original frozen recovery layout");
            assertEquals(2, f.attempts.size());
            assertArrayEquals(f.attempts.get(0), f.attempts.get(1));

            Tab fresh = f.open("after-cancel.sql", "select 303;\n");
            CodeArea area = f.editor(fresh);
            FxUiTestSupport.call(() -> { area.replaceText("select 404;\n"); area.selectRange(2, 8); return null; });
            assertSame(fresh, f.reopen(f.root.resolve("after-cancel.sql")));
            assertEquals("select 404;\n", FxUiTestSupport.call(area::getText));
            SqlScriptFileController controller = (SqlScriptFileController) get(f.pane(fresh), "fileController");
            assertTrue(FxUiTestSupport.call(controller::save).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals("select 404;\n", Files.readString(f.root.resolve("after-cancel.sql")));
            f.checkpoint(fresh);
            SqlWorkspace current = f.capture();
            assertNotEquals(original.entries(), current.entries());
            f.fail.set(false);
            assertEquals(ShutdownOutcome.COMPLETED, f.shutdown().get(10, TimeUnit.SECONDS));
            f.assertCompleted(); f.assertStored(current);
            assertFalse(Arrays.equals(prior, Files.readAllBytes(f.workspacePath)));
            System.out.println("WORKSPACE_CANCEL repeated=2 oldBytes=unchanged priorTabs=closed newFile=admitted duplicate=reused save=real final=COMPLETED");
        }
    }

    @Test void retryProductionButtonRepublishesSameCheckpointedFrozenLayout() throws Exception {
        try (Fixture f = new Fixture()) {
            Tab a = f.open("retry-a.sql", "select 11;\n");
            Tab b = f.open("retry-b.sql", "select 22;\n");
            FxUiTestSupport.call(() -> { f.editor(a).selectRange(1, 7); f.tabs.getSelectionModel().select(a); return null; });
            f.checkpoint(a); f.checkpoint(b);
            SqlWorkspace layout = f.capture(); f.seed(new SqlWorkspace(1, List.of(), null));
            byte[] prior = Files.readAllBytes(f.workspacePath);
            f.fail.set(true);
            var decision = f.beginDecision();
            assertArrayEquals(prior, Files.readAllBytes(f.workspacePath));
            assertEquals(1, f.attempts.size());
            f.fail.set(false); decision.choose("重试");
            assertEquals(ShutdownOutcome.COMPLETED, decision.outcome.get(10, TimeUnit.SECONDS));
            assertEquals(2, f.attempts.size());
            assertArrayEquals(f.attempts.get(0), f.attempts.get(1), "retry must publish the same validated frozen bytes");
            f.assertStored(layout); f.assertCompleted();
            System.out.println("WORKSPACE_RETRY productionButton=true failedAtomicPublish=1 sameFrozenBytes=true final=COMPLETED");
        }
    }

    @Test void ignoreProductionButtonPreservesOldBytesAndCompletesShutdown() throws Exception {
        try (Fixture f = new Fixture()) {
            Tab tab = f.open("ignore.sql", "select 33;\n"); f.checkpoint(tab);
            f.seed(new SqlWorkspace(1, List.of(), null)); byte[] prior = Files.readAllBytes(f.workspacePath);
            f.fail.set(true); var decision = f.beginDecision();
            decision.choose("忽略本次工作区更新并退出");
            assertEquals(ShutdownOutcome.COMPLETED, decision.outcome.get(10, TimeUnit.SECONDS));
            assertArrayEquals(prior, Files.readAllBytes(f.workspacePath));
            assertTrue(f.fail.get()); assertEquals(1, f.attempts.size()); f.assertCompleted();
            System.out.println("WORKSPACE_IGNORE oldBytes=unchanged fault=stillEnabled final=COMPLETED");
        }
    }

    @Test void syntheticDialogDismissUsesDefaultCancelSemantics() throws Exception {
        try (Fixture f = new Fixture()) {
            Tab tab = f.open("dismiss.sql", "select 44;\n"); f.checkpoint(tab);
            f.seed(new SqlWorkspace(1, List.of(), null)); byte[] prior = Files.readAllBytes(f.workspacePath);
            f.fail.set(true); var decision = f.beginDecision();
            FxUiTestSupport.call(() -> { decision.window.close(); return null; });
            assertEquals(ShutdownOutcome.CANCELLED, decision.outcome.get(10, TimeUnit.SECONDS));
            f.assertCancelled(prior);
            System.out.println("WORKSPACE_DISMISS syntheticStageClose=true defaultCancel=true final=CANCELLED");
        }
    }

    static final class Fixture implements AutoCloseable {
        final String previousHome = System.getProperty("user.home");
        final Path root = Files.createTempDirectory("datacube-workspace-shell-" + UUID.randomUUID()).toRealPath();
        final Path workspacePath = root.resolve(".datacube/sql-drafts/workspace.bin");
        final AppShell shell;
        final Stage stage;
        final AtomicReference<CompletableFuture<ShutdownOutcome>> requested=new AtomicReference<>();
        final AtomicInteger shutdownRequests=new AtomicInteger();
        javafx.scene.Node lastWaiting;
        final TabPane tabs;
        final SqlFileTabRegistry registry;
        final SqlDraftUi drafts;
        final TrackingDispatcher dispatcher;
        final AtomicBoolean fail = new AtomicBoolean();
        final List<byte[]> attempts = new CopyOnWriteArrayList<>();
        final AtomicInteger draftMoves = new AtomicInteger();
        final AtomicInteger providerRequests = new AtomicInteger();
        boolean completed;
        Fixture() throws Exception {
            System.setProperty("user.home", root.toString());
            AppShell created = null;
            Stage createdStage = null;
            try {
                shell = created = FxUiTestSupport.call(AppShell::new);
                set(get(shell, "connMgr"), "providerResolver", (java.util.function.Function<com.datacube.spi.model.DbType, com.datacube.spi.DatabaseProvider>) type -> {
                    providerRequests.incrementAndGet(); throw new AssertionError("synthetic workspace test forbids all database providers");
                });
                tabs = (TabPane) ((ContentTabPane) get(shell, "contentTabs")).getNode();
                registry = (SqlFileTabRegistry) get(shell, "sqlFileTabs");
                stage = createdStage = FxUiTestSupport.call(() -> {
                    Stage s = new Stage();s.setTitle("Synthetic workspace " + UUID.randomUUID());
                    var controller=new WindowShutdownController(s,shell.getRoot(),shell::isRunning,()->{
                        shutdownRequests.incrementAndGet();var outcome=shell.shutdownAsync();requested.set(outcome.toCompletableFuture());return outcome;
                    });
                    var scene=new Scene(controller.getRoot(),1000,700);scene.getStylesheets().setAll(
                            ThemeManager.class.getResource("theme-base.css").toExternalForm(),ThemeManager.class.getResource("theme-dark.css").toExternalForm());
                    s.setScene(scene);s.show();return s;
                });
                Object entry = get(shell, "sqlFileEntry");
                dispatcher = new TrackingDispatcher((AppShell.SqlFileTaskDispatcher) get(entry, "tasks"));
                FxUiTestSupport.call(() -> { set(entry, "tasks", dispatcher); return null; });
                drafts = FxUiTestSupport.call(() -> ((LazyValue<SqlDraftUi>) get(shell, "sqlDrafts")).get());
                ready();
                Object store = get(get(drafts.runtime(), "backend"), "store");
                Object directory = get(store, "directory");
                Object mover = get(directory, "mover");
                Class<?> type = mover.getClass().getInterfaces()[0];
                Object fault = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    Path target = (Path) args[1];
                    assertEquals(workspacePath.getParent(), target.getParent(), "owned draft directory only");
                    if (target.getFileName().toString().equals("workspace.bin")) {
                        attempts.add(Files.readAllBytes((Path) args[0]));
                        if (fail.get()) throw new java.io.IOException("synthetic owned workspace atomic publication failure");
                    }
                    method.setAccessible(true);
                    try {
                        Object result = method.invoke(mover, args);
                        if (target.getFileName().toString().endsWith(".draft")) draftMoves.incrementAndGet();
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
                set(directory, "mover", fault);
            } catch (Throwable failure) {
                if (created != null) try { dispose(created); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                try {
                    if (createdStage != null) {
                        Stage ownStage = createdStage;
                        FxUiTestSupport.call(() -> { ownStage.setOnCloseRequest(null);ownStage.close(); return null; });
                    }
                } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                finally { System.setProperty("user.home", previousHome); }
                throw failure;
            }
        }
        void ready() throws Exception {
            CompletableFuture<Void> initialized = new CompletableFuture<>();
            AutoCloseable observation = FxUiTestSupport.call(() -> {
                Runnable check = () -> { if (drafts.runtime().mode() != SqlDraftCoordinator.Mode.INITIALIZING) initialized.complete(null); };
                AutoCloseable o = drafts.observe(check); check.run(); return o;
            });
            try {
                initialized.get(5, TimeUnit.SECONDS);
                var result = FxUiTestSupport.call(() -> drafts.runtime().refresh()).get(5, TimeUnit.SECONDS);
                assertTrue(result.succeeded()); assertNotNull(result.snapshot()); assertTrue(result.snapshot().writable());
                FxUiTestSupport.call(() -> { assertEquals(SqlDraftCoordinator.Mode.ENABLED, drafts.runtime().mode()); assertFalse(drafts.runtime().managementPending()); return null; });
            } finally { FxUiTestSupport.call(() -> { observation.close(); return null; }); }
        }
        Tab open(String name, String sql) throws Exception { Path path = Files.writeString(root.resolve(name), sql); return reopen(path); }
        Tab reopen(Path path) throws Exception {
            CompletableFuture<Void> loaded = new CompletableFuture<>();
            FxUiTestSupport.call(() -> { dispatcher.nextRead = loaded; shell.openSqlFile(path); return null; });
            loaded.get(5, TimeUnit.SECONDS);
            return FxUiTestSupport.call(() -> { assertTrue(registry.select(path)); return tabs.getSelectionModel().getSelectedItem(); });
        }
        SqlEditorPane pane(Tab tab) throws Exception { return FxUiTestSupport.call(() -> tab.getContent().getProperties().values().stream().filter(SqlEditorPane.class::isInstance).map(SqlEditorPane.class::cast).findFirst().orElseThrow()); }
        CodeArea editor(Tab tab) throws Exception { return (CodeArea) get(pane(tab), "editorArea"); }
        void checkpoint(Tab tab) throws Exception {
            SqlDraftEditorBinding binding = (SqlDraftEditorBinding) get(pane(tab), "draftBinding");
            FxUiTestSupport.call(() -> ((SqlDraftCoordinator.Handle) get(binding, "handle")).flush()).get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> { assertTrue(binding.checkpointed()); return null; });
        }
        SqlWorkspace capture() throws Exception { return FxUiTestSupport.call(() -> drafts.workspace().capture()); }
        SqlWorkspace frozen() throws Exception { return ((SqlWorkspaceActivity.Frozen) FxUiTestSupport.call(() -> get(drafts.workspace(), "frozen"))).workspace(); }
        CompletableFuture<Void> seedAsync(SqlWorkspace workspace) throws Exception {
            return FxUiTestSupport.call(() -> {
                SqlWorkspaceActivity owner = drafts.workspace().owner();
                owner.activity(workspace);
                var frozen = owner.freezeForExit(workspace);
                return owner.saveFrozen(frozen, workspace).thenRun(() -> {
                    owner.activity(workspace);
                    attempts.clear();
                });
            });
        }
        void seed(SqlWorkspace workspace) throws Exception { seedAsync(workspace).get(5, TimeUnit.SECONDS); }
        CompletableFuture<ShutdownOutcome> shutdown() throws Exception {
            if(completed)return FxUiTestSupport.call(shell::shutdownAsync).toCompletableFuture();
            return FxUiTestSupport.call(()->{DataCubeFxShutdownContractTest.closeRequest(stage);return requested.get();});
        }
        Decision beginDecision() throws Exception {
            CompletableFuture<Stage> shown = new CompletableFuture<>();
            ListChangeListener<Window> observer = change -> {
                while (change.next()) for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage s && s.getOwner() == stage) Platform.runLater(() -> {
                        if (SqlWorkspaceUi.TITLE.equals(s.getTitle()) && s.isShowing()) shown.complete(s);
                    });
                }
            };
            FxUiTestSupport.call(() -> { Window.getWindows().addListener(observer); return null; });
            try {
                CompletableFuture<ShutdownOutcome> outcome = shutdown();
                Stage dialog = shown.get(10, TimeUnit.SECONDS);
                DataCubeFxShutdownContractTest.awaitLayoutPulses();
                return FxUiTestSupport.call(() -> {
                    DialogPane pane = (DialogPane) dialog.getScene().getRoot();
                    assertEquals(stage, dialog.getOwner()); assertEquals(SqlWorkspaceUi.MESSAGE, pane.getContentText());
                    assertEquals(List.of("重试", "取消退出", "忽略本次工作区更新并退出"), pane.getButtonTypes().stream().map(ButtonType::getText).toList());
                    for (ButtonType type : pane.getButtonTypes()) assertEquals(type.getText().equals("取消退出"), ((Button) pane.lookupButton(type)).isDefaultButton());
                    DataCubeFxShutdownContractTest.assertPendingFeedback(stage,shell.getRoot());
                    var waiting=stage.getScene().getRoot().lookup("#shutdown-pending-notice");assertNotSame(lastWaiting,waiting);lastWaiting=waiting;
                    int before=shutdownRequests.get();DataCubeFxShutdownContractTest.closeRequest(stage);assertEquals(before,shutdownRequests.get(),"modal waiting never repeats application shutdown");
                    for(ButtonType type:pane.getButtonTypes())assertFalse(((Button)pane.lookupButton(type)).isDisabled(),"production modal remains actionable");
                    assertFalse(outcome.isDone()); assertTrue(tabs.getTabs().isEmpty(), "actual guards already finalized the original tabs");
                    System.out.println("PRODUCTION_ALERT owner=syntheticShell title=" + dialog.getTitle() + " default=cancel originalTabs=removed");
                    return new Decision(dialog, pane, outcome);
                });
            } finally { FxUiTestSupport.call(() -> { Window.getWindows().removeListener(observer); return null; }); }
        }
        void assertCancelled(byte[] prior) throws Exception {
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()->{assertTrue(stage.isShowing());assertFalse(shell.getRoot().isDisabled());DataCubeFxShutdownContractTest.assertNoFeedback(stage.getScene().getRoot());assertNull(lastWaiting.getParent());return null;});
            assertArrayEquals(prior, Files.readAllBytes(workspacePath));
            assertEquals(0, dispatcher.closes.get());
            ((FxTaskRunner) get(shell, "tasks")).submit(() -> {}).get(3, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> { assertEquals(SqlDraftCoordinator.Mode.ENABLED, drafts.runtime().mode()); assertTrue(tabs.getTabs().isEmpty()); return null; });
            assertTrue(draftMoves.get() > 0, "real draft atomic publications happened");
            assertTrue(((Map<?, ?>) get(get(shell, "connMgr"), "live")).isEmpty());
            assertEquals(0, providerRequests.get(), "no database reads, SQL executions, or database writes admitted");
        }
        void assertCompleted() throws Exception {
            DataCubeFxShutdownContractTest.awaitLayoutPulses();
            FxUiTestSupport.call(()->{assertFalse(stage.isShowing());assertTrue(shell.getRoot().isDisabled());DataCubeFxShutdownContractTest.assertNoFeedback(stage.getScene().getRoot());if(lastWaiting!=null)assertNull(lastWaiting.getParent());return null;});
            completed = true; assertEquals(1, dispatcher.closes.get());
            assertTrue(draftMoves.get() > 0, "real successful draft atomic publication required for every scenario");
            assertThrows(RejectedExecutionException.class, () -> ((FxTaskRunner) get(shell, "tasks")).submit(() -> {}));
            FxUiTestSupport.call(() -> { assertEquals(SqlDraftCoordinator.Mode.CLOSED, drafts.runtime().mode()); assertTrue(tabs.getTabs().isEmpty()); assertThrows(IllegalStateException.class, () -> registry.createOwner(() -> {})); return null; });
            assertEquals(ShutdownOutcome.COMPLETED, shutdown().get(3, TimeUnit.SECONDS)); assertEquals(1, dispatcher.closes.get());
            assertEquals(0, providerRequests.get());
            System.out.println("OWNERSHIP fileDispatcherClose=1 runtime=CLOSED registry=CLOSED providers=0 draftAtomicMoves=" + draftMoves.get());
        }
        void assertStored(SqlWorkspace expected) throws Exception {
            try (SqlDraftStore store = SqlDraftStore.open(workspacePath.getParent())) {
                SqlWorkspace stored = store.workspaceSnapshot().workspace();
                assertEquals(expected.entries(), stored.entries()); assertEquals(expected.selectedDraftId(), stored.selectedDraftId());
                Set<UUID> ids = new HashSet<>(); store.snapshot().drafts().forEach(d -> ids.add(d.id()));
                assertTrue(ids.containsAll(stored.entries().stream().map(SqlWorkspace.Entry::draftId).toList()));
            }
        }
        @Override public void close() throws Exception {
            Throwable primary = null;
            try {
                fail.set(false);
                FxUiTestSupport.call(() -> { for (Window window : List.copyOf(Window.getWindows())) if (window instanceof Stage s && s.getOwner() == stage) s.close(); return null; });
                if (!completed) {
                    assertEquals(ShutdownOutcome.COMPLETED, shutdown().get(10, TimeUnit.SECONDS));
                    assertCompleted();
                }
            } catch (Throwable failure) {
                primary = failure;
                // Fixture safety fallback only; never evidence of successful product shutdown.
                try { dispose(shell); System.out.println("FIXTURE_FALLBACK shutdownRemaining=cleanupOnly"); }
                catch (Throwable cleanup) { primary.addSuppressed(cleanup); }
            } finally {
                // Synthetic stage disposal only; it does not exercise or recover product shutdown.
                try { FxUiTestSupport.call(() -> { stage.setOnCloseRequest(null);stage.close(); return null; }); }
                catch (Throwable cleanup) { if (primary == null) primary = cleanup; else primary.addSuppressed(cleanup); }
                finally { System.setProperty("user.home", previousHome); }
            }
            if (primary instanceof Exception exception) throw exception;
            if (primary instanceof Error error) throw error;
        }
    }
    record Decision(Stage window, DialogPane pane, CompletableFuture<ShutdownOutcome> outcome) {
        void choose(String label) throws Exception { FxUiTestSupport.call(() -> { ButtonType type = pane.getButtonTypes().stream().filter(t -> t.getText().equals(label)).findFirst().orElseThrow(); ((Button) pane.lookupButton(type)).fire(); return null; }); }
    }
    static final class TrackingDispatcher implements AppShell.SqlFileTaskDispatcher {
        final AppShell.SqlFileTaskDispatcher delegate;
        final AtomicInteger closes = new AtomicInteger();
        CompletableFuture<Void> nextRead;
        TrackingDispatcher(AppShell.SqlFileTaskDispatcher delegate) { this.delegate = delegate; }
        public <T> void submit(Callable<T> operation, java.util.function.Consumer<? super T> success, java.util.function.Consumer<? super Throwable> failure) {
            CompletableFuture<Void> read = nextRead; nextRead = null;
            delegate.submit(operation, value -> { try { success.accept(value); if (read != null) read.complete(null); } catch (Throwable error) { if (read != null) read.completeExceptionally(error); throw error; } }, error -> { if (read != null) read.completeExceptionally(error); failure.accept(error); });
        }
        public void close() { closes.incrementAndGet(); delegate.close(); }
    }
    static Object get(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    static void set(Object object, String name, Object value) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    static void dispose(AppShell shell) throws Exception {
        Method m = AppShell.class.getDeclaredMethod("shutdownRemaining"); m.setAccessible(true);
        CompletableFuture<Void> released = new CompletableFuture<>();
        Thread.startVirtualThread(() -> { try { m.invoke(shell); released.complete(null); } catch (Throwable failure) { released.completeExceptionally(failure); } });
        released.get(10, TimeUnit.SECONDS);
    }
}
