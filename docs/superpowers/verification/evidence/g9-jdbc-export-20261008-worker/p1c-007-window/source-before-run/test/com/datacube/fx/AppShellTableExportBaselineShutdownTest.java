package com.datacube.fx;

import com.datacube.export.PgDumpTestJobs;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression run also against the original AppShell teardown, with only a local export entry seam. */
class AppShellTableExportBaselineShutdownTest {
    @BeforeAll static void preload() throws Exception { AppShellWorkspaceShutdownTest.preloadFxNatives(); }
    @Test void runnerShutdownReturnDoesNotProveOwnedExportHasPhysicallyStopped() throws Exception {
        try (var f = new AppShellWorkspaceShutdownTest.Fixture()) {
            var actions = new AppShellTableExportShutdownTest(); actions.seed(f);
            Path target = Files.writeString(f.root.resolve("target"), "old bytes");
            var control = new PgDumpTestJobs.Control(f.root.resolve("helper"), "fake");
            var fake = control.fake = new PgDumpTestJobs.HeldProcess();
            var task = actions.start(f, target, new AppShellTableExportShutdownTest.Ui(), control);
            try {
                assertTrue(fake.reading.await(5, TimeUnit.SECONDS));
                var shutdown = f.shutdown(); assertTrue(fake.forced.await(5, TimeUnit.SECONDS));
                assertTrue(fake.isAlive()); assertFalse(task.physicalCompletion.isDone());
                System.out.println("TABLE_BASELINE beforeAssertion root=fake-alive streams=held physical=pending shutdownDone=" + shutdown.isDone());
                assertThrows(TimeoutException.class, () -> shutdown.get(500, TimeUnit.MILLISECONDS),
                        "Window completion must wait for the actual owned root and stream workers");
                assertTrue(FxUiTestSupport.call(f.stage::isShowing));
                fake.release(0); actions.complete(task);
                assertEquals(ShutdownOutcome.COMPLETED, shutdown.get(10, TimeUnit.SECONDS)); f.assertCompleted();
                assertEquals("old bytes", Files.readString(target)); assertTrue(control.physicallySettled());
                System.out.println("TABLE_BASELINE physical=settled final=COMPLETED " + control.diagnostic());
            } finally {
                fake.release(1); task.physicalCompletion.get(5, TimeUnit.SECONDS);
                System.out.println("TABLE_BASELINE fixtureRelease=true rootAlive=" + fake.isAlive());
            }
        }
    }
}
