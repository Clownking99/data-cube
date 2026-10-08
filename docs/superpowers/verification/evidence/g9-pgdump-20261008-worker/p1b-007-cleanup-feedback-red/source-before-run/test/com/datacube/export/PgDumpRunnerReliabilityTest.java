package com.datacube.export;

import com.datacube.service.TableExportMocks;
import com.datacube.spi.model.TableRef;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PgDumpRunnerReliabilityTest {
    @TempDir Path root;
    private final class Job implements AutoCloseable {
        final Path target = Files.writeString(root.resolve("target"), "old bytes");
        final Path neighbor = Files.writeString(root.resolve("neighbor"), "neighbor bytes");
        final ResultExportOperation operation = new ResultExportOperation();
        final PgDumpTestJobs.Control control;
        FutureTask<Path> result;
        Thread thread;
        Job(String mode) throws IOException { control = new PgDumpTestJobs.Control(root.resolve("control"), mode); }
        void start() {
            result = new FutureTask<>(() -> PgDumpTestJobs.export(new TableExportMocks().manager,
                    new TableExporter.Request("synthetic", new TableRef("synthetic", "items"), ExportContent.DATA,
                            ExportFormat.PG_DUMP, SafeResultFilePublisher.capture(target)), operation, control));
            thread = Thread.ofVirtual().start(result);
        }
        Throwable failed() throws Exception {
            Throwable failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause();
            assertFalse(String.valueOf(failure.getMessage()).contains("SYNTHETIC_PGDUMP_SECRET"));
            assertTrue(thread.join(Duration.ofSeconds(5))); return failure;
        }
        void protectedFiles() throws Exception {
            assertEquals("old bytes", Files.readString(target)); assertEquals("neighbor bytes", Files.readString(neighbor));
            assertFalse(operation.published());
            try (var entries = Files.list(root)) { assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".datacube-"))); }
        }
        void physical() throws Exception {
            assertTrue(control.settled.await(5, TimeUnit.SECONDS));
            var receipt = control.receipt.get();
            assertTrue(receipt.physicallySettled()); assertFalse(receipt.rootAlive());
            assertEquals(0, receipt.livingDescendants()); assertEquals(0, receipt.livingWorkers());
            for (Thread owned : control.threads) assertFalse(owned.isAlive());
            Process child = control.process.get(); if (child != null) assertFalse(child.isAlive());
            if (child instanceof PgDumpTestJobs.TrackingProcess tracked) {
                assertTrue(tracked.stdinClosed); assertTrue(tracked.closes.get() >= 2);
                assertEquals(8192, tracked.maxRead.get());
                for (Thread reader : tracked.readers) assertFalse(reader.isAlive());
            }
            assertFalse(receipt.toString().contains("SYNTHETIC_PGDUMP_SECRET"));
            System.out.println("PGDUMP_PHYSICAL mode=" + control.mode + " receipt=" + receipt);
        }
        public void close() throws Exception {
            control.clock.set(Duration.ofDays(1).toNanos()); operation.cancel();
            if (control.fake != null) control.fake.release(1);
            Process child = control.process.get();
            if (child != null && child.isAlive()) child.destroyForcibly();
            if (result != null) try { result.get(5, TimeUnit.SECONDS); } catch (ExecutionException ignored) {}
            if (thread != null) assertTrue(thread.join(Duration.ofSeconds(5)));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"hang", "stream"})
    void controlledDeadlineOccursAfterHelperActuallyEnteredOutputPhase(String mode) throws Exception {
        try (var job = new Job(mode)) {
            job.start(); assertTrue(job.control.started.await(5, TimeUnit.SECONDS));
            PgDumpTestJobs.awaitFile(job.control.directory.resolve("ready-" + mode));
            if (mode.equals("stream")) assertTrue(job.control.received.await(5, TimeUnit.SECONDS));
            assertTrue(job.control.process.get().isAlive()); assertFalse(job.result.isDone());
            job.control.expire(); job.failed(); job.physical(); job.protectedFiles();
            assertEquals(PgDumpRunner.Reason.TIMEOUT, job.control.receipt.get().reason());
            if (mode.equals("stream")) {
                assertTrue(job.control.receipt.get().stdoutBytes() >= 65536);
                assertTrue(job.control.receipt.get().stderrBytes() >= 65536);
            } else { assertEquals(0, job.control.receipt.get().stdoutBytes()); assertEquals(0, job.control.receipt.get().stderrBytes()); }
        }
    }
    @Test void twoUnbrokenLargeStreamsAreDrainedWithoutTextRetentionAndRealOutputPublishes() throws Exception {
        try (var job = new Job("flood")) {
            job.start(); assertNotNull(job.result.get(5, TimeUnit.SECONDS)); assertTrue(job.thread.join(Duration.ofSeconds(5)));
            job.physical(); assertTrue(job.operation.published());
            assertFalse(job.operation.cancel());
            assertEquals(8192L * 1024, job.control.receipt.get().stdoutBytes());
            assertEquals(8192L * 1024, job.control.receipt.get().stderrBytes());
            assertEquals("synthetic dump bytes", Files.readString(job.target)); assertEquals("neighbor bytes", Files.readString(job.neighbor));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"start", "nonzero", "drain"})
    void everyFailurePhysicallySettlesBeforePublisherCleansItsTemporary(String failure) throws Exception {
        try (var job = new Job(failure.equals("nonzero") ? "fail" : "hang")) {
            job.control.startFailure = failure.equals("start"); job.control.drainFailure = failure.equals("drain");
            job.start(); job.failed(); job.physical(); job.protectedFiles();
            assertEquals(switch (failure) { case "start" -> PgDumpRunner.Reason.START; case "nonzero" -> PgDumpRunner.Reason.NONZERO;
                default -> PgDumpRunner.Reason.OUTPUT; }, job.control.receipt.get().reason());
        }
    }
    @Test void cancellationBeforeStartAcquiresNoProcessOrScratch() throws Exception {
        try (var job = new Job("hang")) {
            job.operation.cancel(); job.start(); job.failed(); job.protectedFiles(); assertNull(job.control.process.get());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exitZeroAtOrAfterDeadlineCannotObtainPublication(boolean atFinalSettlement) throws Exception {
        try (var job = new Job("fake")) {
            var fake = job.control.fake = new PgDumpTestJobs.HeldProcess();
            if (atFinalSettlement) job.control.afterSettlement = job.control::expire;
            job.start(); assertTrue(fake.reading.await(5, TimeUnit.SECONDS));
            if (!atFinalSettlement) job.control.expire();
            fake.release(0); job.failed(); job.physical(); job.protectedFiles();
            assertEquals(PgDumpRunner.Reason.TIMEOUT, job.control.receipt.get().reason());
            assertFalse(job.operation.cancel(), "Failed or cancelled publication token must not become reusable");
        }
    }
    @Test void startStillBlockedAfterDeadlineKeepsBusyAndLateRootIsActuallyReaped() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var job = new Job("hang")) {
            job.control.beforeStart = () -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); } };
            try {
                job.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); job.control.expire();
                assertTrue(job.control.stopping.await(5, TimeUnit.SECONDS));
                // First observe the stop at the deadline; then move the same clock through its cleanup observation budget.
                var stopping = new CountDownLatch(1);
                job.operation.onCleanupPending(stopping::countDown);
                job.control.advanceCleanup(); assertTrue(stopping.await(5, TimeUnit.SECONDS));
                assertFalse(job.result.isDone()); assertNull(job.control.process.get());
                var busy = assertThrows(SafeResultFilePublisher.Failure.class, () -> new SafeResultFilePublisher().publish(
                        SafeResultFilePublisher.capture(job.target), new ResultExportOperation(), (path, operation) -> fail("Still owned")));
                assertEquals(SafeResultFilePublisher.Stage.TARGET_BUSY, busy.stage());
                assertEquals("old bytes", Files.readString(job.target));
                release.countDown(); job.failed(); job.physical(); job.protectedFiles();
                assertTrue(job.control.receipt.get().rootPid() > 0);
            } finally { release.countDown(); }
        }
    }
    @Test void forceSurvivorAndHeldCloseCallsRemainOwnedPastObservationBudget() throws Exception {
        try (var job = new Job("fake")) {
            var fake = job.control.fake = new PgDumpTestJobs.HeldProcess(); job.start();
            assertTrue(fake.reading.await(5, TimeUnit.SECONDS)); assertTrue(job.operation.cancel());
            assertTrue(fake.forced.await(5, TimeUnit.SECONDS)); job.control.advanceCleanup();
            assertTrue(job.control.pending.await(5, TimeUnit.SECONDS));
            assertTrue(fake.isAlive()); assertFalse(job.result.isDone()); assertFalse(job.control.physicallySettled());
            assertEquals(0, fake.closed.get()); assertEquals("old bytes", Files.readString(job.target));
            assertTrue(job.control.receipt.get().livingWorkers() > 0);
            fake.release(0); job.failed(); job.physical(); job.protectedFiles();
            assertEquals(1, fake.destroys.get()); assertEquals(1, fake.forces.get());
            assertTrue(fake.stdinClosed); assertTrue(fake.closed.get() >= 2);
            for (Thread worker : fake.workers) assertFalse(worker.isAlive());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"parent", "tree"})
    void capturedFamilyHoldingPipeIsStoppedAfterParentExitAndIndependentNeighborSurvives(String mode) throws Exception {
        Path neighborControl = Files.createDirectory(root.resolve("independent-control"));
        Process neighbor = PgDumpRunnerBaselineRedTest.helper(new ProcessBuilder(), "hang", root.resolve("independent.tmp"), neighborControl);
        try (var job = new Job(mode)) {
            try {
                PgDumpTestJobs.awaitFile(neighborControl.resolve("ready-hang"));
                job.start(); assertTrue(job.control.captured.await(5, TimeUnit.SECONDS));
                PgDumpTestJobs.awaitFile(job.control.directory.resolve("child-pid"));
                long child = Long.parseLong(Files.readString(job.control.directory.resolve("child-pid")));
                assertTrue(job.control.awaitCaptured(child), job.control.diagnostic());
                if (mode.equals("tree")) {
                    PgDumpTestJobs.awaitFile(job.control.directory.resolve("grandchild-pid"));
                    long grandchild = Long.parseLong(Files.readString(job.control.directory.resolve("grandchild-pid")));
                    assertTrue(job.control.awaitCaptured(grandchild), job.control.diagnostic());
                }
                job.control.releaseHelper(); job.failed(); job.physical(); job.protectedFiles();
                assertEquals(PgDumpRunner.Reason.DESCENDANTS, job.control.receipt.get().reason());
                assertTrue(neighbor.isAlive());
            } finally {
                Files.writeString(job.control.directory.resolve("release-parent"), "release");
                Files.writeString(job.control.directory.resolve("release-tree"), "release");
                Files.writeString(job.control.directory.resolve("release-branch"), "release");
                Files.writeString(job.control.directory.resolve("release-child"), "release");
            }
        } finally { neighbor.destroyForcibly(); assertTrue(neighbor.waitFor(5, TimeUnit.SECONDS)); }
    }
    @Test void throwingNormalHandleControlStillJoinsItsWorkerAndForcesOnlyOwnedFamily() throws Exception {
        var thrown = new CountDownLatch(1);
        try (var job = new Job("parent")) {
            job.control.handleControl = (handle, force) -> {
                if (!force) { thrown.countDown(); throw new IllegalStateException("SYNTHETIC_PGDUMP_SECRET control"); }
                try { assertTrue(thrown.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                handle.destroyForcibly();
            };
            try {
                job.start(); PgDumpTestJobs.awaitFile(job.control.directory.resolve("child-pid"));
                assertTrue(job.control.awaitCaptured(Long.parseLong(Files.readString(job.control.directory.resolve("child-pid")))));
                job.operation.cancel(); assertTrue(thrown.await(5, TimeUnit.SECONDS));
                job.failed(); job.physical(); job.protectedFiles();
            } finally {
                Files.writeString(job.control.directory.resolve("release-parent"), "release");
                Files.writeString(job.control.directory.resolve("release-child"), "release");
            }
        }
    }
    @Test void blockedHandleControlCannotBlockObservationAndItsLateReturnIsStillOwned() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var job = new Job("parent")) {
            job.control.handleControl = (handle, force) -> {
                assertFalse(Thread.currentThread().getName().contains("supervisor"));
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                if (force) handle.destroyForcibly(); else handle.destroy();
            };
            try {
                job.start(); assertTrue(job.control.captured.await(5, TimeUnit.SECONDS)); job.operation.cancel();
                assertTrue(entered.await(5, TimeUnit.SECONDS)); job.control.advanceCleanup();
                assertTrue(job.control.pending.await(5, TimeUnit.SECONDS)); assertFalse(job.result.isDone());
                assertTrue(job.control.receipt.get().livingWorkers() > 0); assertTrue(job.control.receipt.get().livingWorkers() <= 9);
                release.countDown(); job.failed(); job.physical(); job.protectedFiles();
            } finally {
                release.countDown(); Files.writeString(job.control.directory.resolve("release-parent"), "release");
                Files.writeString(job.control.directory.resolve("release-child"), "release");
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void supervisorLaunchFatalIsPreservedAndAnyStartedSupervisorIsJoined(boolean startFirst) throws Exception {
        try (var job = new Job("empty")) {
            Error fatal = new AssertionError("synthetic early supervisor error");
            job.control.launch = thread -> { if (startFirst) thread.start(); throw fatal; };
            job.start(); assertSame(fatal, job.failed()); job.physical(); job.protectedFiles(); assertNull(job.control.process.get());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void scratchCleanupResidueSurvivesNormalAndFatalStartFailureThroughActualPublisher(boolean fatal) throws Exception {
        try (var job = new Job("empty")) {
            Error original = new AssertionError("synthetic fatal start");
            job.control.starterFatal = fatal ? original : null; job.control.startFailure = !fatal;
            var scratch = new AtomicReference<Path>();
            job.control.configureStart = builder -> {
                scratch.set(builder.directory().toPath());
                try { Files.writeString(scratch.get().resolve("foreign-object"), "foreign bytes"); }
                catch (IOException failure) { throw new AssertionError(failure); }
            };
            job.start(); Throwable failure = job.failed(); job.physical();
            if (fatal) assertSame(original, failure);
            else assertEquals(SafeResultFilePublisher.Stage.CLEANUP, ((SafeResultFilePublisher.Failure) failure).stage());
            assertEquals("foreign bytes", Files.readString(scratch.get().resolve("foreign-object")));
            assertEquals("old bytes", Files.readString(job.target)); assertEquals("neighbor bytes", Files.readString(job.neighbor));
            assertFalse(job.operation.published()); assertTrue(job.operation.cleanupResidues().contains(scratch.get()),
                    "Actual UI reads the operation residue list, including fatal cleanup failures");
            try (var entries = Files.list(root)) {
                assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".datacube-export-")));
            }
        }
    }
}
