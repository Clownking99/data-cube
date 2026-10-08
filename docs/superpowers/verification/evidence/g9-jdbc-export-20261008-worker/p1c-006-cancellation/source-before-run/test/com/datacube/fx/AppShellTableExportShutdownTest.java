package com.datacube.fx;

import com.datacube.export.*;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.service.TableExportMocks;
import com.datacube.spi.model.TableRef;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual AppShell and window-close chain, with owned synthetic files and helper processes only. */
class AppShellTableExportShutdownTest {
    static class Ui implements ExportDialog.ExportUi {
        final AtomicInteger finishes = new AtomicInteger(), pending = new AtomicInteger(), cancellations = new AtomicInteger();
        final CountDownLatch stopped = new CountDownLatch(1), cleanup = new CountDownLatch(1);
        volatile ExportDialog.Outcome outcome;
        volatile boolean accepted;
        public boolean confirmOverwrite(Path target) { return true; }
        public void running(ExportDialog.ExportTask task) {}
        public void cancelling(boolean value) { accepted = value; cancellations.incrementAndGet(); stopped.countDown(); }
        public void cleanupPending() { pending.incrementAndGet(); cleanup.countDown(); }
        public void finished(ExportDialog.Outcome value) { outcome = value; finishes.incrementAndGet(); }
    }
    TableExportTasks owner(AppShell shell) throws Exception {
        return (TableExportTasks) AppShellWorkspaceShutdownTest.get(shell, "tableExports");
    }
    ExportDialog.ExportTask start(AppShellWorkspaceShutdownTest.Fixture f, Path target, Ui ui, PgDumpTestJobs.Control control) throws Exception {
        var source = new TableExportMocks();
        return FxUiTestSupport.call(() -> f.shell.startTableExport("synthetic", new TableRef("synthetic", "items"),
                ExportContent.DATA, ExportFormat.PG_DUMP, target, ui,
                (request, operation) -> PgDumpTestJobs.export(source.manager, request, operation, control)));
    }
    void complete(ExportDialog.ExportTask task) throws Exception {
        task.physicalCompletion.get(5, TimeUnit.SECONDS); task.completion.get(5, TimeUnit.SECONDS);
        FxUiTestSupport.call(() -> null);
    }
    void seed(AppShellWorkspaceShutdownTest.Fixture f) throws Exception {
        var tab = f.open("owned.sql", "select 1;\n"); f.checkpoint(tab); f.seed(f.capture());
    }
    @Test void cancelledMandatoryDecisionKeepsExportAliveAndRestoresActualAdmission() throws Exception {
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            seed(f); Path target = Files.writeString(f.root.resolve("target"), "old bytes");
            byte[] workspace = Files.readAllBytes(f.workspacePath);
            var control = new PgDumpTestJobs.Control(f.root.resolve("helper"), "hang"); Ui ui = new Ui();
            var task = start(f, target, ui, control);
            try {
                PgDumpTestJobs.awaitFile(control.directory.resolve("ready-hang"));
                f.fail.set(true); var decision = f.beginDecision();
                assertFalse(owner(f.shell).admitting()); assertTrue(control.process.get().isAlive());
                assertFalse(task.physicalCompletion.isDone()); assertEquals(0, ui.cancellations.get());
                assertNull(start(f, f.root.resolve("not-started"), new Ui(), new PgDumpTestJobs.Control(f.root.resolve("refused"), "empty")));
                assertFalse(Files.exists(f.root.resolve("not-started")));
                decision.choose("取消退出"); assertEquals(ShutdownOutcome.CANCELLED, decision.outcome().get(10, TimeUnit.SECONDS));
                f.assertCancelled(workspace); assertTrue(owner(f.shell).admitting());
                assertTrue(control.process.get().isAlive()); assertEquals(0, ui.cancellations.get());
                var second = new PgDumpTestJobs.Control(f.root.resolve("second"), "empty"); Ui secondUi = new Ui();
                var secondTask = start(f, f.root.resolve("second-target"), secondUi, second); complete(secondTask);
                assertEquals(ExportDialog.Status.SUCCEEDED, secondUi.outcome.status());
                assertEquals(1, owner(f.shell).pending(), "Settling a newer export must retain the original owner");
                assertTrue(FxUiTestSupport.call(task::cancel)); complete(task);
                assertEquals(ExportDialog.Status.CANCELLED, ui.outcome.status()); assertEquals(1, ui.finishes.get());
                assertEquals("old bytes", Files.readString(target)); assertTrue(control.physicallySettled());
                assertEquals(0, owner(f.shell).pending()); f.fail.set(false);
                assertEquals(ShutdownOutcome.COMPLETED, f.shutdown().get(10, TimeUnit.SECONDS)); f.assertCompleted();
                System.out.println("TABLE_WINDOW cancelledGuard=exportAlive admission=restored oldOwner=retained physical=settled finishes=1");
            } finally {
                control.releaseHelper(); FxUiTestSupport.call(() -> { task.cancel(); return null; });
                task.physicalCompletion.get(5, TimeUnit.SECONDS);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realWindowCannotFinishOrEnterBestEffortWhileForceSurvivorOrHiddenOwnerStillOwnsResources(boolean hidden) throws Exception {
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            seed(f); Path target = Files.writeString(f.root.resolve("target"), "old bytes");
            Path neighbor = Files.writeString(f.root.resolve("neighbor"), "neighbor bytes");
            var control = new PgDumpTestJobs.Control(f.root.resolve("helper"), "fake");
            var fake = control.fake = new PgDumpTestJobs.HeldProcess(); Ui ui = new Ui(); var task = start(f, target, ui, control);
            try {
                assertTrue(fake.reading.await(5, TimeUnit.SECONDS));
                if (hidden) FxUiTestSupport.call(() -> { task.ownerClosed(); return null; });
                var shutdown = f.shutdown(); assertTrue(fake.forced.await(5, TimeUnit.SECONDS));
                control.advanceCleanup(); assertTrue(control.pending.await(5, TimeUnit.SECONDS));
                if (!hidden) assertTrue(ui.cleanup.await(5, TimeUnit.SECONDS));
                DataCubeFxShutdownContractTest.awaitLayoutPulses();
                FxUiTestSupport.call(() -> { DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage, f.shell.getRoot()); return null; });
                assertFalse(shutdown.isDone()); assertFalse(task.physicalCompletion.isDone()); assertTrue(fake.isAlive());
                assertEquals(1, owner(f.shell).pending()); assertFalse(owner(f.shell).admitting());
                assertEquals(0, f.dispatcher.closes.get(), "Best-effort SQL teardown must not run yet");
                ((FxTaskRunner) AppShellWorkspaceShutdownTest.get(f.shell, "tasks")).submit(() -> {}).get(3, TimeUnit.SECONDS);
                assertNull(start(f, f.root.resolve("refused-target"), new Ui(), new PgDumpTestJobs.Control(f.root.resolve("refused"), "empty")));
                assertEquals("old bytes", Files.readString(target)); assertEquals("neighbor bytes", Files.readString(neighbor));
                fake.release(0); complete(task); assertTrue(control.physicallySettled()); assertEquals(0, owner(f.shell).pending());
                assertEquals(ShutdownOutcome.COMPLETED, shutdown.get(10, TimeUnit.SECONDS)); f.assertCompleted();
                assertEquals(hidden ? 0 : 1, ui.finishes.get());
                assertEquals("old bytes", Files.readString(target)); assertEquals("neighbor bytes", Files.readString(neighbor));
                for (Thread owned : control.threads) assertFalse(owned.isAlive());
                for (Thread owned : fake.workers) assertFalse(owned.isAlive());
                System.out.println("TABLE_WINDOW hidden=" + hidden + " forceAlive=pending bestEffort=deferred ownership=0 final=COMPLETED " + control.diagnostic());
            } finally { fake.release(1); task.physicalCompletion.get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void publicationWinnerSettlesOnceBeforeActualMainWindowCanClose() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            seed(f); var source = new TableExportMocks(); Ui ui = new Ui();
            Path target = Files.writeString(f.root.resolve("target.sql"), "old bytes");
            Path neighbor = Files.writeString(f.root.resolve("neighbor"), "neighbor bytes");
            var task = FxUiTestSupport.call(() -> f.shell.startTableExport("synthetic", new TableRef("synthetic", "items"),
                    ExportContent.DATA, ExportFormat.SQL, target, ui, (request, operation) ->
                    TableExportTestJobs.blockedPublication(source.manager, request, operation, () -> {
                        entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { throw new AssertionError(error); }
                    })));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); var shutdown = f.shutdown();
                assertTrue(ui.stopped.await(5, TimeUnit.SECONDS)); assertFalse(ui.accepted);
                assertFalse(task.physicalCompletion.isDone()); assertFalse(shutdown.isDone());
                assertEquals(1, owner(f.shell).pending()); assertEquals("old bytes", Files.readString(target));
                release.countDown(); complete(task); assertEquals(ExportDialog.Status.SUCCEEDED, ui.outcome.status());
                assertEquals(1, ui.finishes.get()); assertEquals(0, owner(f.shell).pending());
                assertTrue(Files.readString(target).contains("INSERT")); assertEquals("neighbor bytes", Files.readString(neighbor));
                assertEquals(ShutdownOutcome.COMPLETED, shutdown.get(10, TimeUnit.SECONDS)); f.assertCompleted();
                System.out.println("TABLE_WINDOW publication=winner cancellation=rejected finishes=1 ownership=0 final=COMPLETED");
            } finally { release.countDown(); task.physicalCompletion.get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void queuedCancellationUnregistersBeforeStartingAnyProducer() throws Exception {
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            seed(f); var starts = new AtomicInteger(); Ui ui = new Ui() {
                public void running(ExportDialog.ExportTask task) { assertTrue(task.cancel()); }
            };
            Path target = Files.writeString(f.root.resolve("queued"), "old bytes");
            var task = FxUiTestSupport.call(() -> f.shell.startTableExport("synthetic", new TableRef("synthetic", "items"),
                    ExportContent.DATA, ExportFormat.PG_DUMP, target, ui, (request, operation) -> {
                        starts.incrementAndGet(); throw new AssertionError("Cancelled queued producer must not start");
                    }));
            complete(task); assertEquals(0, starts.get()); assertEquals(0, owner(f.shell).pending());
            assertEquals(ExportDialog.Status.CANCELLED, ui.outcome.status()); assertEquals(1, ui.finishes.get());
            assertEquals("old bytes", Files.readString(target));
            assertEquals(ShutdownOutcome.COMPLETED, f.shutdown().get(10, TimeUnit.SECONDS)); f.assertCompleted();
            System.out.println("TABLE_WINDOW queuedCancel=settled producerStarts=0 ownership=0 final=COMPLETED");
        }
    }
    @Test void realMainWindowRetainsOwnershipAfterRootExitWhileFailedStreamCloseIsUnresolved() throws Exception {
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            seed(f); Path target = Files.writeString(f.root.resolve("close-failure"), "old bytes");
            var control = new PgDumpTestJobs.Control(f.root.resolve("helper"), "fake");
            var fake = control.fake = new PgDumpTestJobs.HeldProcess(); fake.failStdinClose = true;
            Ui ui = new Ui(); var task = start(f, target, ui, control);
            try {
                assertTrue(fake.stdinCloseFailed.await(5, TimeUnit.SECONDS)); assertTrue(fake.forced.await(5, TimeUnit.SECONDS));
                var shutdown = f.shutdown(); fake.release(0);
                assertTrue(fake.stdinRetryEntered.await(5, TimeUnit.SECONDS)); control.advanceCleanup();
                assertTrue(control.pending.await(5, TimeUnit.SECONDS)); assertTrue(ui.cleanup.await(5, TimeUnit.SECONDS));
                DataCubeFxShutdownContractTest.awaitLayoutPulses();
                FxUiTestSupport.call(() -> { DataCubeFxShutdownContractTest.assertPendingFeedback(f.stage, f.shell.getRoot()); return null; });
                assertFalse(fake.isAlive()); assertFalse(fake.stdinClosed); assertFalse(shutdown.isDone());
                assertFalse(task.physicalCompletion.isDone()); assertEquals(1, owner(f.shell).pending());
                assertEquals(0, f.dispatcher.closes.get()); assertEquals("old bytes", Files.readString(target));
                fake.allowStdinClose.countDown(); complete(task); assertTrue(fake.stdinClosed); assertTrue(control.physicallySettled());
                assertEquals(0, owner(f.shell).pending()); assertEquals(1, ui.finishes.get());
                assertEquals(ShutdownOutcome.COMPLETED, shutdown.get(10, TimeUnit.SECONDS)); f.assertCompleted();
                System.out.println("TABLE_WINDOW root=exited closeFailure=unresolved-pending lateClose=success final=COMPLETED " + control.diagnostic());
            } finally { fake.allowStdinClose.countDown(); fake.release(1); task.physicalCompletion.get(5, TimeUnit.SECONDS); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "unsupported"})
    void actualUiReportsClientRequirementsWithoutRawFailureOrOutput(String scenario) throws Exception {
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            seed(f); Path target = Files.writeString(f.root.resolve("client-failure"), "old bytes");
            var control = new PgDumpTestJobs.Control(f.root.resolve("helper"), scenario.equals("missing") ? "empty" : "fail");
            control.startFailure = scenario.equals("missing"); Ui ui = new Ui(); var task = start(f, target, ui, control);
            complete(task); assertEquals(ExportDialog.Status.FAILED, ui.outcome.status());
            assertTrue(ui.outcome.message().contains("pg_dump/libpq 16+"));
            assertFalse(ui.outcome.message().contains("SYNTHETIC_PGDUMP_SECRET"));
            assertFalse(ui.outcome.message().contains("synthetic.invalid"));
            assertEquals(1, ui.finishes.get()); assertEquals("old bytes", Files.readString(target));
            assertTrue(control.physicallySettled()); assertEquals(0, owner(f.shell).pending());
            assertEquals(ShutdownOutcome.COMPLETED, f.shutdown().get(10, TimeUnit.SECONDS)); f.assertCompleted();
            System.out.println("TABLE_CLIENT scenario=" + scenario + " fixedFeedback=true physical=settled finishes=1");
        }
    }
    @Test void earlyUiFatalRetainsOriginalErrorAndUnregistersUnstartedOwnerOnce() throws Exception {
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            seed(f); Error fatal = new AssertionError("synthetic early UI error");
            var registered = new AtomicReference<ExportDialog.ExportTask>(); var starts = new AtomicInteger();
            Ui ui = new Ui() { public void running(ExportDialog.ExportTask task) { registered.set(task); throw fatal; } };
            Path target = Files.writeString(f.root.resolve("ui-fatal"), "old bytes");
            var failure = assertThrows(ExecutionException.class, () -> FxUiTestSupport.call(() -> f.shell.startTableExport(
                    "synthetic", new TableRef("synthetic", "items"), ExportContent.DATA, ExportFormat.PG_DUMP, target, ui,
                    (request, operation) -> { starts.incrementAndGet(); throw new AssertionError("Unstarted producer"); })));
            assertSame(fatal, failure.getCause()); complete(registered.get());
            assertEquals(0, owner(f.shell).pending()); assertEquals(0, starts.get()); assertEquals(1, ui.finishes.get());
            assertEquals(ExportDialog.Status.FAILED, ui.outcome.status()); assertEquals("old bytes", Files.readString(target));
            assertEquals(ShutdownOutcome.COMPLETED, f.shutdown().get(10, TimeUnit.SECONDS)); f.assertCompleted();
            System.out.println("TABLE_WINDOW earlyUiFatal=original ownership=0 producerStarts=0 finishes=1 final=COMPLETED");
        }
    }
}
