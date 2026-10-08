package com.datacube.fx;

import com.datacube.export.*;
import com.datacube.service.TableExportMocks;
import com.datacube.spi.model.TableRef;
import com.datacube.fx.task.FxTaskRunner;
import javafx.application.Platform;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ExportDialogReliabilityTest {
    @TempDir Path root;
    private static final TableRef TABLE = new TableRef("synthetic", "items");
    private final class Ui implements ExportDialog.ExportUi {
        final AtomicInteger confirms = new AtomicInteger(), starts = new AtomicInteger(), finishes = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        boolean overwrite = true;
        Boolean cancelAccepted;
        ExportDialog.Outcome outcome;
        Runnable duringConfirmation = () -> {};
        Runnable duringRunning = () -> {};
        public boolean confirmOverwrite(Path target) {
            assertTrue(Platform.isFxApplicationThread()); confirms.incrementAndGet(); duringConfirmation.run(); return overwrite;
        }
        public void running(ExportDialog.ExportTask task) {
            assertTrue(Platform.isFxApplicationThread()); starts.incrementAndGet(); duringRunning.run();
        }
        public void cancelling(boolean accepted) { assertTrue(Platform.isFxApplicationThread()); cancelAccepted = accepted; }
        public void finished(ExportDialog.Outcome value) {
            assertTrue(Platform.isFxApplicationThread()); outcome = value; finishes.incrementAndGet(); done.countDown();
        }
    }
    private ExportDialog.ExportTask start(TableExportMocks source, Path target, ExportFormat format,
                                          FxTaskRunner runner, Ui ui) throws Exception {
        return FxUiTestSupport.call(() -> ExportDialog.startExport(source.manager, "synthetic", TABLE,
                ExportContent.DATA, format, target, runner, ui));
    }
    private void done(Ui ui, ExportDialog.ExportTask task) throws Exception {
        task.completion.get(5, TimeUnit.SECONDS);
        assertTrue(ui.done.await(5, TimeUnit.SECONDS));
        FxUiTestSupport.call(() -> { assertEquals(1, ui.finishes.get()); return null; });
    }
    private void noTemp() throws Exception {
        try (var entries = Files.list(root)) { assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".datacube-export-"))); }
    }
    @Test void actualTablePublicationShowsAuxiliaryCleanupWarningAndResidualPath() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui();
            var task = FxUiTestSupport.call(() -> ExportDialog.startExport("synthetic", TABLE, ExportContent.DATA,
                    ExportFormat.SQL, target, runner, ui, (request, operation) ->
                            TableExportTestJobs.tableWithCleanupWarning(source.manager, request, operation)));
            done(ui, task);
            assertEquals(ExportDialog.Status.SUCCEEDED, ui.outcome.status());
            assertTrue(Files.readString(target).contains("complete value"));
            Path residue;
            try (var entries = Files.list(root)) {
                residue = entries.filter(path -> path.getFileName().toString().endsWith(".identity")).findFirst().orElseThrow();
            }
            assertTrue(Files.isSameFile(target, residue));
            assertTrue(ui.outcome.message().contains("已发布，但辅助临时文件清理失败"));
            assertTrue(ui.outcome.message().contains(residue.toString()));
            assertFalse(ui.outcome.message().contains("synthetic witness"));
        }
    }
    @ParameterizedTest @EnumSource(ExportFormat.class)
    void refusedOverwriteDoesNotCreateScopeJobConnectionProcessOrTemporary(ExportFormat format) throws Exception {
        var source = new TableExportMocks();
        Path target = Files.writeString(root.resolve("target"), "old");
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui(); ui.overwrite = false;
            assertNull(start(source, target, format, runner, ui));
            assertEquals(1, ui.confirms.get()); assertEquals(0, ui.starts.get()); assertEquals(0, ui.finishes.get());
            assertEquals(0, source.opens.get()); assertEquals("old", Files.readString(target)); noTemp();
        }
    }
    @ParameterizedTest @EnumSource(value = ExportFormat.class, names = {"SQL", "XLSX"})
    void actualTableExporterConnectionFailureDoesNotDeleteOldTarget(ExportFormat format) throws Exception {
        var source = new TableExportMocks(); source.failure = TableExportMocks.Failure.CONNECT;
        Path target = Files.writeString(root.resolve("target"), "old");
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui();
            ui.duringConfirmation = () -> assertEquals(0, source.opens.get());
            ui.duringRunning = () -> { assertEquals(0, source.opens.get()); };
            var task = start(source, target, format, runner, ui); done(ui, task);
            assertEquals(ExportDialog.Status.FAILED, ui.outcome.status());
            assertFalse(ui.outcome.message().contains("sensitive"));
            assertEquals("old", Files.readString(target)); noTemp();
        }
    }
    @Test void injectedLocalPgFailureStillUsesActualTableExporterAndKeepsTarget() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui();
            var task = FxUiTestSupport.call(() -> ExportDialog.startExport("synthetic", TABLE, ExportContent.DATA,
                    ExportFormat.PG_DUMP, target, runner, ui,
                    (request, operation) -> TableExportTestJobs.failingDump(source.manager, request, operation)));
            done(ui, task);
            assertEquals(ExportDialog.Status.FAILED, ui.outcome.status()); assertFalse(ui.outcome.message().contains("sensitive"));
            assertEquals("old", Files.readString(target)); noTemp();
        }
    }
    @Test void confirmationReplacementIsRejectedBeforeActualJobAcquiresSource() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui();
            ui.duringConfirmation = () -> {
                try { Files.delete(target); Files.writeString(target, "changed during confirm"); }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            var task = start(source, target, ExportFormat.SQL, runner, ui); done(ui, task);
            assertEquals(ExportDialog.Status.FAILED, ui.outcome.status()); assertEquals(0, source.opens.get());
            assertEquals("changed during confirm", Files.readString(target)); noTemp();
        }
    }
    @Test void cancelledQueuedHandleSettlesWithoutStartingCallable() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui();
            var task = FxUiTestSupport.call(() -> {
                var cancellingUi = new ExportDialog.ExportUi() {
                    public boolean confirmOverwrite(Path path) { return ui.confirmOverwrite(path); }
                    public void running(ExportDialog.ExportTask value) { ui.running(value); assertTrue(value.cancel()); }
                    public void cancelling(boolean accepted) { ui.cancelling(accepted); }
                    public void finished(ExportDialog.Outcome outcome) { ui.finished(outcome); }
                };
                return ExportDialog.startExport(source.manager, "synthetic", TABLE, ExportContent.DATA,
                        ExportFormat.SQL, target, runner, cancellingUi);
            });
            done(ui, task); assertEquals(ExportDialog.Status.CANCELLED, ui.outcome.status());
            assertEquals(0, source.opens.get()); assertEquals("old", Files.readString(target)); noTemp();
        }
    }
    @Test void runnerRejectionSettlesOnceWithoutDeletingTarget() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        var runner = new FxTaskRunner(); runner.close();
        Ui ui = new Ui(); var task = start(source, target, ExportFormat.SQL, runner, ui); done(ui, task);
        assertEquals(ExportDialog.Status.FAILED, ui.outcome.status()); assertEquals(0, source.opens.get());
        assertEquals("old", Files.readString(target)); noTemp();
    }
    @Test void cancellationWaitsForActualRowSourceReturnThenCanRetryWithNewConfirmation() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        source.beforePage = () -> {
            if (source.pages.get() == 1) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            }
        };
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui(); var task = start(source, target, ExportFormat.SQL, runner, ui);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> {
                    assertTrue(task.cancel()); assertTrue(ui.cancelAccepted);
                    assertTrue(task.cancel()); assertTrue(ui.cancelAccepted, "Repeated cancellation must still say cancelling");
                    return null;
                });
                assertFalse(task.completion.isDone()); assertEquals(0, ui.finishes.get()); assertEquals("old", Files.readString(target));
                release.countDown(); done(ui, task); assertEquals(ExportDialog.Status.CANCELLED, ui.outcome.status());
                source.beforePage = () -> {};
                Ui retryUi = new Ui(); var retry = start(source, target, ExportFormat.SQL, runner, retryUi); done(retryUi, retry);
                assertEquals(1, retryUi.confirms.get()); assertEquals(ExportDialog.Status.SUCCEEDED, retryUi.outcome.status());
                assertTrue(Files.readString(target).contains("complete value")); noTemp();
            } finally { release.countDown(); task.completion.get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void fxCancelReturnsWhileActualAtomicMoveIsBlockedAndDoesNotMisreportCancellation() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui();
            var task = FxUiTestSupport.call(() -> ExportDialog.startExport("synthetic", TABLE, ExportContent.DATA,
                    ExportFormat.SQL, target, runner, ui, (request, operation) ->
                            TableExportTestJobs.blockedPublication(source.manager, request, operation, () -> {
                                entered.countDown();
                                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                                catch (InterruptedException failure) { throw new AssertionError(failure); }
                            })));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { assertFalse(task.cancel()); assertFalse(ui.cancelAccepted); return null; });
                assertFalse(task.completion.isDone()); assertEquals("old", Files.readString(target));
                release.countDown(); done(ui, task); assertEquals(ExportDialog.Status.SUCCEEDED, ui.outcome.status());
                assertTrue(Files.readString(target).contains("complete value")); noTemp();
            } finally { release.countDown(); task.completion.get(5, TimeUnit.SECONDS); }
        }
    }
    @Test void ownerClosureSuppressesLateUiWithoutClaimingWorkerStopped() throws Exception {
        var source = new TableExportMocks(); Path target = Files.writeString(root.resolve("target"), "old");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        source.beforePage = () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        };
        try (var runner = new FxTaskRunner()) {
            Ui ui = new Ui(); var task = start(source, target, ExportFormat.SQL, runner, ui);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxUiTestSupport.call(() -> { task.ownerClosed(); return null; });
                assertFalse(task.completion.isDone()); release.countDown(); task.completion.get(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> { assertEquals(0, ui.finishes.get()); return null; });
                assertEquals("old", Files.readString(target)); noTemp();
            } finally { release.countDown(); task.completion.get(5, TimeUnit.SECONDS); }
        }
    }
}
