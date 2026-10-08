package com.datacube.fx;

import com.datacube.config.RecentSqlFiles;
import com.datacube.sqleditor.SqlScriptFileStore;
import com.datacube.fx.task.FxTaskRunner;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real AppShell assembly, with synthetic file I/O and controlled asynchronous collaborators. */
class AppShellShutdownRecoveryTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.BeforeEach
    void canonicalFixtureDirectory() throws Exception {
        // The registry contract uses the canonical identity returned by the SQL file store.
        directory = directory.toRealPath();
    }

    @Test void cancelledShutdownPreservesExistingFileIdentityAndNewAdmission() throws Exception {
        try (var fixture = new Fixture()) {
            Path existing = Files.writeString(directory.resolve("existing.sql"), "select 1;\n");
            Tab original = fixture.open(existing);
            SqlEditorPane pane = fixture.pane(original);
            CodeArea editor = (CodeArea) field(pane, "editorArea");
            FxUiTestSupport.call(() -> { editor.replaceText("select 123;\n"); return null; });
            CompletableFuture<CloseGuardOutcome> guard = new CompletableFuture<>();
            FxUiTestSupport.call(() -> { set(pane, "mandatoryCloseGuard", (AsyncTabCloseGuard) () -> guard); return null; });
            CompletionStage<ShutdownOutcome> attempt = FxUiTestSupport.call(fixture.shell::shutdownAsync);
            guard.complete(CloseGuardOutcome.REJECTED);
            assertEquals(ShutdownOutcome.CANCELLED, attempt.toCompletableFuture().get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                assertTrue(fixture.registry.select(existing), "cancelled shutdown must preserve the original file identity");
                assertSame(original, fixture.tabs.getSelectionModel().getSelectedItem());
                assertFalse(fixture.dispatcher.closed, "cancelled shutdown must retain the file task dispatcher");
                return null;
            });
            assertSame(original, fixture.open(existing), "duplicate reopen must reuse the surviving real file tab");
            assertEquals("select 123;\n", FxUiTestSupport.call(editor::getText));
            SqlScriptFileController controller = (SqlScriptFileController) field(pane, "fileController");
            assertTrue(FxUiTestSupport.call(controller::save).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals("select 123;\n", Files.readString(existing));
            Path savedAs = directory.resolve("renamed.sql");
            FxUiTestSupport.call(() -> { set(controller, "savePathChooser", (java.util.function.Function<javafx.stage.Window, Path>) ignored -> savedAs); return null; });
            assertTrue(FxUiTestSupport.call(controller::saveAs).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals("select 123;\n", Files.readString(savedAs));
            assertSame(original, fixture.open(savedAs));
            Path next = Files.writeString(directory.resolve("new.sql"), "select 2;\n");
            assertNotSame(original, fixture.open(next));
            System.out.println("REAL_SHELL cancelled=CANCELLED existingIdentity=retained duplicate=reused newFile=opened");
        }
    }

    @Test void oldGenerationCallbacksAndPublicCancellationCannotReopenAdmissions() throws Exception {
        try (var fixture = new Fixture()) {
            Path live = Files.writeString(directory.resolve("live.sql"), "select 1;");
            Tab tab = fixture.open(live);
            Path delayed = Files.writeString(directory.resolve("delayed.sql"), "select 2;");
            java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
            Object entry = field(fixture.shell, "sqlFileEntry");
            FxUiTestSupport.call(() -> { set(entry, "feedback", (Consumer<String>) ignored -> errors.incrementAndGet()); fixture.shell.openSqlFile(delayed); fixture.shell.openSqlFile(directory.resolve("missing.sql")); return null; });
            CompletableFuture<CloseGuardOutcome> guard = new CompletableFuture<>();
            FxUiTestSupport.call(() -> { set(fixture.pane(tab), "mandatoryCloseGuard", (AsyncTabCloseGuard) () -> guard); return null; });
            var exposed = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
            assertTrue(exposed.cancel(false));
            var actual = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
            assertFalse(actual.isDone());
            FxUiTestSupport.call(() -> { int before = fixture.dispatcher.work.size(); fixture.shell.openSqlFile(delayed); assertEquals(before, fixture.dispatcher.work.size()); fixture.dispatcher.runNext(); return null; });
            guard.complete(CloseGuardOutcome.REJECTED);
            assertEquals(ShutdownOutcome.CANCELLED, actual.get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { fixture.dispatcher.runNext(); assertEquals(0, errors.get()); assertFalse(fixture.registry.select(delayed)); return null; });
            assertNotSame(tab, fixture.open(delayed));
            CompletableFuture<CloseGuardOutcome> second = new CompletableFuture<>();
            FxUiTestSupport.call(() -> { for (Tab fileTab : java.util.List.copyOf(fixture.tabs.getTabs())) { if (fileTab.getContent().getProperties().values().stream().anyMatch(SqlEditorPane.class::isInstance)) set(fixture.pane(fileTab), "mandatoryCloseGuard", (AsyncTabCloseGuard) () -> second); } return null; });
            var retry = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
            second.complete(CloseGuardOutcome.REJECTED);
            assertEquals(ShutdownOutcome.CANCELLED, retry.get(5, TimeUnit.SECONDS));
            assertSame(tab, fixture.open(live));
            System.out.println("REAL_SHELL staleRead=ignored staleError=ignored publicCancel=isolated repeatedCancel=retained");
        }
    }

    final class Fixture implements AutoCloseable {
        final AppShell shell;
        final TabPane tabs;
        final SqlFileTabRegistry registry;
        final ControlledDispatcher dispatcher = new ControlledDispatcher();
        Fixture() throws Exception {
            shell = FxUiTestSupport.call(() -> {
                AppShell result = new AppShell();
                new Scene(result.getRoot(), 1000, 700);
                set(field(result, "sqlFileEntry"), "tasks", dispatcher);
                return result;
            });
            tabs = (TabPane) ((ContentTabPane) field(shell, "contentTabs")).getNode();
            registry = (SqlFileTabRegistry) field(shell, "sqlFileTabs");
        }
        Tab open(Path path) throws Exception {
            return FxUiTestSupport.call(() -> {
                int before = dispatcher.work.size();
                shell.openSqlFile(path);
                assertTrue(dispatcher.work.size() > before, "actual shell file entry must admit a new-generation read");
                dispatcher.runNext();
                while (!dispatcher.work.isEmpty()) dispatcher.runNext();
                return tabs.getSelectionModel().getSelectedItem();
            });
        }
        SqlEditorPane pane(Tab tab) throws Exception {
            return FxUiTestSupport.call(() -> tab.getContent().getProperties().values().stream()
                    .filter(SqlEditorPane.class::isInstance).map(SqlEditorPane.class::cast).findFirst().orElseThrow());
        }
        @Override public void close() throws Exception {
            FxUiTestSupport.call(() -> {
                LazyValue<?> drafts = (LazyValue<?>) field(shell, "sqlDrafts");
                drafts.peek().ifPresent(value -> {
                    try { set(((SqlDraftUi) value).workspace(), "decision", (java.util.function.Supplier<CompletionStage<SqlWorkspaceUi.Decision>>) () -> CompletableFuture.completedFuture(SqlWorkspaceUi.Decision.IGNORE)); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                });
                for (Tab tab : java.util.List.copyOf(tabs.getTabs())) {
                    SqlEditorPane pane = pane(tab);
                    set(pane, "mandatoryCloseGuard", AsyncTabCloseGuards.retryable(() -> {
                        CompletableFuture<CloseGuardOutcome> result = new CompletableFuture<>();
                        Thread.startVirtualThread(() -> {
                            try { pane.closeResources(); result.complete(CloseGuardOutcome.APPROVED); }
                            catch (Throwable failure) { result.completeExceptionally(failure); }
                        });
                        return result;
                    }));
                }
                return null;
            });
            CompletionStage<ShutdownOutcome> outcome = FxUiTestSupport.call(shell::shutdownAsync);
            assertEquals(ShutdownOutcome.COMPLETED, outcome.toCompletableFuture().get(10, TimeUnit.SECONDS));
        }
    }

    @Test void queuedRecentWriteIsDiscardedAcrossCancelGeneration() throws Exception {
        try (var fixture = new Fixture()) {
            Path file = Files.writeString(directory.resolve("queued.sql"), "select 3;");
            Tab tab = FxUiTestSupport.call(() -> { fixture.shell.openSqlFile(file); fixture.dispatcher.runNext(); return fixture.tabs.getSelectionModel().getSelectedItem(); });
            assertEquals(1, fixture.dispatcher.work.size(), "real loaded callback queued recent persistence");
            var guard = new CompletableFuture<CloseGuardOutcome>();
            FxUiTestSupport.call(() -> { set(fixture.pane(tab), "mandatoryCloseGuard", (AsyncTabCloseGuard) () -> guard); return null; });
            var closing = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
            guard.complete(CloseGuardOutcome.REJECTED);
            assertEquals(ShutdownOutcome.CANCELLED, closing.get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { fixture.dispatcher.runNext(); return null; });
            RecentSqlFiles recent = (RecentSqlFiles) field(fixture.shell, "recentSqlFiles");
            assertFalse(recent.recent().contains(file), "old queued write must not publish after resume");
        }
    }

    @Test void blockedAdmittedRecentWriteDoesNotBlockFxShutdown() throws Exception {
        try (var fixture = new Fixture()) {
            Object entry = field(fixture.shell, "sqlFileEntry");
            RecentSqlFiles recent = new RecentSqlFiles(directory.resolve("blocked-recent.txt"));
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            Class<?> writerType = Class.forName("com.datacube.config.RecentSqlFiles$ContentWriter");
            set(recent, "writer", java.lang.reflect.Proxy.newProxyInstance(writerType.getClassLoader(), new Class<?>[]{writerType}, (proxy, method, args) -> {
                entered.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) throw new java.io.IOException("bounded synthetic write wait");
                Files.write((Path) args[0], (byte[]) args[1]); return null;
            }));
            FxUiTestSupport.call(() -> { set(entry, "recentFiles", recent); fixture.shell.openSqlFile(Files.writeString(directory.resolve("blocked.sql"), "select 4;")); fixture.dispatcher.runNext(); return null; });
            Runnable write = fixture.dispatcher.work.remove();
            var finished = new CompletableFuture<Void>();
            Thread.startVirtualThread(() -> { try { write.run(); finished.complete(null); } catch (Throwable failure) { finished.completeExceptionally(failure); } });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                Tab tab = FxUiTestSupport.call(() -> fixture.tabs.getSelectionModel().getSelectedItem());
                var guard = new CompletableFuture<CloseGuardOutcome>();
                FxUiTestSupport.call(() -> { set(fixture.pane(tab), "mandatoryCloseGuard", (AsyncTabCloseGuard) () -> guard); return null; });
                var fxReturned = new CompletableFuture<CompletionStage<ShutdownOutcome>>();
                javafx.application.Platform.runLater(() -> fxReturned.complete(fixture.shell.shutdownAsync()));
                var closing = fxReturned.get(2, TimeUnit.SECONDS);
                var pulse = new CompletableFuture<Void>();
                javafx.application.Platform.runLater(() -> pulse.complete(null));
                pulse.get(2, TimeUnit.SECONDS);
                guard.complete(CloseGuardOutcome.REJECTED);
                assertEquals(ShutdownOutcome.CANCELLED, closing.toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertFalse(finished.isDone());
                System.out.println("REAL_SHELL blockedAdmittedRecent=running FXshutdown=returned FXpulse=delivered cancel=CANCELLED");
            } finally { release.countDown(); finished.get(3, TimeUnit.SECONDS); }
        }
    }

    @Test void workerStartFailureAfterTabsCommitRemainsPartial() throws Exception {
        Fixture fixture = new Fixture();
        try {
            Object coordinator = field(fixture.shell, "shutdown");
            Object originalStarter = field(coordinator, "blockingStarter");
            FxUiTestSupport.call(() -> { set(coordinator, "blockingStarter", (Consumer<Runnable>) ignored -> { throw new IllegalStateException("synthetic worker start failure"); }); return null; });
            var failed = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
            assertEquals(ShutdownOutcome.FAILED_PARTIAL, failed.get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { set(coordinator, "blockingStarter", originalStarter); assertFalse(fixture.dispatcher.closed); return null; });
            FxUiTestSupport.call(() -> { fixture.shell.openSqlFile(directory.resolve("after-start-failure.sql")); assertTrue(fixture.dispatcher.work.isEmpty()); return null; });
            assertEquals(ShutdownOutcome.FAILED_PARTIAL, FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally { disposeFatalFixture(fixture); }
    }

    @Test void preTabFailureRestoresExistingIdentityAndNewFileAdmission() throws Exception {
        try (var fixture = new Fixture()) {
            Path existing = Files.writeString(directory.resolve("pre-failure-existing.sql"), "select 6;");
            Tab tab = fixture.open(existing);
            Object coordinator = field(fixture.shell, "shutdown");
            Object original = field(coordinator, "closeTabs");
            FxUiTestSupport.call(() -> { set(coordinator, "closeTabs", (java.util.function.Supplier<CompletionStage<TabCloseOutcome>>) () -> { throw new IllegalStateException("synthetic before-tab failure"); }); return null; });
            var attempt = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> attempt.get(3, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { set(coordinator, "closeTabs", original); assertTrue(fixture.registry.select(existing)); assertFalse(fixture.dispatcher.closed); return null; });
            assertSame(tab, fixture.open(existing));
            assertNotSame(tab, fixture.open(Files.writeString(directory.resolve("pre-failure-new.sql"), "select 7;")));
            System.out.println("REAL_SHELL preTabException=recoverable existingIdentity=retained newFile=opened");
        }
    }

    @Test void partialFailureRemainsQuarantined() throws Exception {
        Fixture fixture = new Fixture();
        try {
            ContentTabPane owner = (ContentTabPane) field(fixture.shell, "contentTabs");
            FxUiTestSupport.call(() -> owner.openManagedTab("synthetic fatal", () -> new ContentTabPane.ManagedTabSpec(new javafx.scene.control.Label("fatal"),
                    () -> CompletableFuture.completedFuture(CloseGuardOutcome.FAILED_PARTIAL), () -> {}, () -> {})));
            assertEquals(ShutdownOutcome.FAILED_PARTIAL, FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture().get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> { fixture.shell.openSqlFile(directory.resolve("never-admitted.sql")); assertTrue(fixture.dispatcher.work.isEmpty()); return null; });
            assertEquals(ShutdownOutcome.FAILED_PARTIAL, FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            // Terminal fatal isolation forbids retry; the fixture alone explicitly disposes its synthetic resources.
            disposeFatalFixture(fixture);
        }
    }

    @Test void fileDisposalFailureStillClosesRegistryGlobalTasksAndCachedConnection() throws Exception {
        Fixture fixture = new Fixture();
        var connectionCloses = new java.util.concurrent.atomic.AtomicInteger();
        Object manager = field(fixture.shell, "connMgr");
        @SuppressWarnings("unchecked") var live = (java.util.Map<String, java.sql.Connection>) field(manager, "live");
        java.sql.Connection connection = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) { connectionCloses.incrementAndGet(); return null; }
            throw new AssertionError("synthetic cached connection permits close only: " + method.getName());
        });
        synchronized (manager) { live.put("synthetic-owned-close-only", connection); }
        fixture.dispatcher.failClose = true;
        var result = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
        assertEquals(ShutdownOutcome.FAILED_PARTIAL, result.get(8, TimeUnit.SECONDS));
        FxTaskRunner runner = (FxTaskRunner) field(fixture.shell, "tasks");
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> runner.submit(() -> {}));
        assertEquals(1, connectionCloses.get());
        FxUiTestSupport.call(() -> { assertThrows(IllegalStateException.class, () -> fixture.registry.createOwner(() -> {})); fixture.shell.openSqlFile(directory.resolve("unused.sql")); assertTrue(fixture.dispatcher.work.isEmpty()); return null; });
        assertEquals(ShutdownOutcome.FAILED_PARTIAL, FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals(1, connectionCloses.get());
        System.out.println("REAL_SHELL fileDisposalFailure=FAILED_PARTIAL globalTasksClosed=true cachedConnectionClose=1 retry=quarantined");
    }

    static void disposeFatalFixture(Fixture fixture) throws Exception {
            var disposed = new CompletableFuture<Void>();
            Thread.startVirtualThread(() -> { try { var method = AppShell.class.getDeclaredMethod("shutdownRemaining"); method.setAccessible(true); method.invoke(fixture.shell); disposed.complete(null); } catch (Throwable failure) { disposed.completeExceptionally(failure); } });
            disposed.get(8, TimeUnit.SECONDS);
    }

    @Test void defaultFiveSecondWarningRetainsActualShellManagedOwnership() throws Exception {
        try (var fixture = new Fixture()) {
            var started = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var warned = new java.util.concurrent.CountDownLatch(1);
            var closes = new java.util.concurrent.atomic.AtomicInteger();
            var finalizers = new java.util.concurrent.atomic.AtomicInteger();
            java.io.PrintStream previous = System.err;
            System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
                final StringBuilder text = new StringBuilder();
                @Override public synchronized void write(int b) { previous.write(b); text.append((char)b); if (text.toString().contains("PT5S")) warned.countDown(); }
            }));
            try {
                ContentTabPane owner = (ContentTabPane) field(fixture.shell, "contentTabs");
                Tab tab = FxUiTestSupport.call(() -> owner.openManagedTab("synthetic in-flight resource", () -> {
                    AsyncTabCloseGuard guard = AsyncTabCloseGuards.retryable(() -> {
                        var result = new CompletableFuture<CloseGuardOutcome>();
                        Thread.startVirtualThread(() -> {
                            started.countDown();
                            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("bounded mock release missing"); closes.incrementAndGet(); result.complete(CloseGuardOutcome.APPROVED); }
                            catch (Throwable failure) { result.completeExceptionally(failure); }
                        });
                        return result;
                    });
                    return new ContentTabPane.ManagedTabSpec(new javafx.scene.control.Label("synthetic"), guard,
                            () -> { assertTrue(javafx.application.Platform.isFxApplicationThread()); finalizers.incrementAndGet(); }, () -> {});
                }));
                var attempt = FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture();
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(warned.await(8, TimeUnit.SECONDS), "actual default timer must warn");
                assertFalse(attempt.isDone());
                FxUiTestSupport.call(() -> { assertTrue(fixture.tabs.getTabs().contains(tab)); assertTrue(tab.isDisable()); return null; });
                assertEquals(0, closes.get()); assertEquals(0, finalizers.get());
                release.countDown();
                assertEquals(ShutdownOutcome.COMPLETED, attempt.get(8, TimeUnit.SECONDS));
                assertEquals(ShutdownOutcome.COMPLETED, FxUiTestSupport.call(fixture.shell::shutdownAsync).toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertEquals(1, closes.get()); assertEquals(1, finalizers.get());
                System.out.println("REAL_SHELL timer=defaultPT5S pending=true ownership=retained mockResourceClose=1 FXfinalizer=1 final=COMPLETED");
            } finally { release.countDown(); System.setErr(previous); }
        }
    }
    static final class ControlledDispatcher implements AppShell.SqlFileTaskDispatcher {
        final Queue<Runnable> work = new ArrayDeque<>();
        boolean closed;
        boolean failClose;
        @Override public <T> void submit(Callable<T> operation, Consumer<? super T> success, Consumer<? super Throwable> failure) {
            if (closed) throw new java.util.concurrent.RejectedExecutionException("synthetic dispatcher closed");
            work.add(() -> { try { success.accept(operation.call()); } catch (Throwable error) { failure.accept(error); } });
        }
        void runNext() { work.remove().run(); }
        @Override public void close() { closed = true; if (failClose) throw new IllegalStateException("synthetic dispatcher close failure"); }
    }
    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
}
