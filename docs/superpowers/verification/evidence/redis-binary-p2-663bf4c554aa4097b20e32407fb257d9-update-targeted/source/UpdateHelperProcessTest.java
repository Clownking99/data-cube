package com.datacube.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class UpdateHelperProcessTest {
    @TempDir Path directory;
    @Test void controlledSlowBootstrapHasItsOwnBudgetRatherThanConsumingExecutionBudget() throws Exception {
        System.out.println("MODEL_ONLY: deterministic Process/clock, not an OS termination proof");
        AtomicLong clock=new AtomicLong();AtomicReference<ModelProcess> own=new AtomicReference<>();
        var runner=new UpdateHelperProcess(budgets(200,50), (command,ready)->{
            var process=new ModelProcess(clock,ready,100,125);own.set(process);return process;
        },clock::get,(phase,process)->{});
        var receipt=runner.run(directory,"exit 0",0);
        assertTrue(receipt.startupNanos()>Duration.ofMillis(50).toNanos());
        assertEquals(Duration.ofMillis(25).toNanos(),receipt.executionNanos());
        assertTrue(receipt.exited());assertFalse(receipt.terminationRequested());assertFalse(own.get().destroyed);
    }
    @Test void missingBootstrapAcknowledgementTimesOutAndObservesControlledExit() {
        System.out.println("MODEL_ONLY: deterministic missing acknowledgement, not an OS termination proof");
        AtomicLong clock=new AtomicLong();AtomicReference<ModelProcess> own=new AtomicReference<>();
        var runner=new UpdateHelperProcess(budgets(100,50),(command,ready)->{
            var process=new ModelProcess(clock,ready,Long.MAX_VALUE,Long.MAX_VALUE);own.set(process);return process;
        },clock::get,(phase,process)->{});
        var failure=assertThrows(UpdateHelperProcess.Failure.class,()->runner.run(directory,"exit 0",0));
        assertTrue(failure.getMessage().startsWith("STARTUP_TIMEOUT"));assertEquals(UpdateHelperProcess.Phase.STARTUP,failure.receipt.phase());
        assertTrue(own.get().destroyed);assertTrue(failure.receipt.exited());assertEquals(137,failure.receipt.exit());
    }
    @Test void nonzeroActualPowerShellRetainsBothRawStreamsAndExitCode() throws Exception {
        var failure=assertThrows(UpdateHelperProcess.Failure.class,()->new UpdateHelperProcess().run(directory,
                "[Console]::Out.WriteLine('synthetic stdout'); [Console]::Error.WriteLine('synthetic stderr'); exit 7",0));
        assertEquals(7,failure.receipt.exit());assertEquals(UpdateHelperProcess.Phase.EXIT,failure.receipt.phase());
        assertTrue(Files.readString(failure.receipt.stdout()).contains("synthetic stdout"));
        assertTrue(Files.readString(failure.receipt.stderr()).contains("synthetic stderr"));
        assertTrue(failure.receipt.exited());assertFalse(failure.receipt.terminationRequested());assertDead(failure.receipt);
    }
    @Test void zeroExitWithoutBootstrapAcknowledgementCannotBeReportedAsSuccess() {
        AtomicLong clock=new AtomicLong();
        var runner=new UpdateHelperProcess(budgets(100,50),(command,ready)->new ModelProcess(clock,ready,Long.MAX_VALUE,25),clock::get,(phase,process)->{});
        var failure=assertThrows(UpdateHelperProcess.Failure.class,()->runner.run(directory,"exit 0",0));
        assertTrue(failure.getMessage().startsWith("BOOTSTRAP_NOT_ACKNOWLEDGED"));assertEquals(0,failure.receipt.exit());assertTrue(failure.receipt.exited());
    }
    @Test void oversizedActualOutputIsBoundedAndNeverGivenACompleteHash() {
        var failure=assertThrows(UpdateHelperProcess.Failure.class,()->new UpdateHelperProcess().run(directory,
                "[Console]::Out.Write('x'*65537)",0));
        assertTrue(failure.getMessage().startsWith("OUTPUT_LIMIT"));assertEquals(65537,failure.receipt.stdoutLength());
        assertFalse(failure.receipt.outputComplete());assertEquals("NOT_COMPLETE",failure.receipt.stdoutHash());
        assertEquals(UpdateHelperProcess.OUTPUT_LIMIT,java.util.Base64.getDecoder().decode(failure.receipt.stdoutBase64()).length);
        assertDead(failure.receipt);
    }
    @Test void stuckExecutionTerminatesActualOwnedPowerShellOnlyAfterBodyEntry() {
        Path entered=directory.resolve("blocked-body-entered");AtomicBoolean execution=new AtomicBoolean();
        var runner=new UpdateHelperProcess(UpdateHelperProcess.Budgets.normal(),(command,ready)->command.start(),
                ()->System.nanoTime()+(execution.get() && Files.exists(entered)?Duration.ofSeconds(21).toNanos():0),
                (phase,process)->{if(phase==UpdateHelperProcess.Phase.EXECUTION)execution.set(true);});
        var failure=assertThrows(UpdateHelperProcess.Failure.class,()->runner.run(directory,
                "[IO.File]::WriteAllText("+PortableUpdateHelperTest.ps(entered.toString())+",'entered'); $gate=[Threading.ManualResetEvent]::new($false); $null=$gate.WaitOne()",0));
        assertTrue(Files.exists(entered),"actual body must enter before the controlled execution clock expires");
        assertTrue(failure.getMessage().startsWith("EXECUTION_TIMEOUT"));assertEquals(UpdateHelperProcess.Phase.EXECUTION,failure.receipt.phase());
        assertTrue(failure.receipt.terminationRequested());assertTrue(failure.receipt.exited());assertDead(failure.receipt);
    }
    @Test void interruptedExecutionRestoresInterruptOnlyAfterActualOwnedProcessSettles() throws Exception {
        Path entered=directory.resolve("interrupted-body-entered");CountDownLatch inBody=new CountDownLatch(1);AtomicBoolean execution=new AtomicBoolean();
        AtomicReference<UpdateHelperProcess.Failure> result=new AtomicReference<>();
        AtomicReference<Throwable> unexpected=new AtomicReference<>();AtomicBoolean restored=new AtomicBoolean();
        var runner=new UpdateHelperProcess(UpdateHelperProcess.Budgets.normal(),(command,ready)->command.start(),
                ()->{if(execution.get() && Files.exists(entered))inBody.countDown();return System.nanoTime();},
                (phase,process)->{if(phase==UpdateHelperProcess.Phase.EXECUTION)execution.set(true);});
        Thread worker=Thread.ofVirtual().start(()->{
            try { runner.run(directory,"[IO.File]::WriteAllText("+PortableUpdateHelperTest.ps(entered.toString())+",'entered'); $gate=[Threading.ManualResetEvent]::new($false); $null=$gate.WaitOne()",0);unexpected.set(new AssertionError("interruption not reported")); }
            catch(UpdateHelperProcess.Failure failure){result.set(failure);restored.set(Thread.currentThread().isInterrupted());}
            catch(Throwable failure){unexpected.set(failure);}
        });
        try { assertTrue(inBody.await(90,TimeUnit.SECONDS),"actual body not entered");worker.interrupt();worker.join(12000); }
        finally { if(worker.isAlive()){worker.interrupt();worker.join(12000);} }
        assertFalse(worker.isAlive(),"test worker did not settle");assertNull(unexpected.get());assertNotNull(result.get());
        assertEquals(UpdateHelperProcess.Phase.INTERRUPTED,result.get().receipt.phase());assertTrue(restored.get());
        assertTrue(result.get().receipt.terminationRequested());assertTrue(result.get().receipt.exited());assertDead(result.get().receipt);
    }
    private static UpdateHelperProcess.Budgets budgets(long startup,long execution){return new UpdateHelperProcess.Budgets(Duration.ofMillis(startup),Duration.ofMillis(execution),Duration.ofSeconds(1));}
    private static void assertDead(UpdateHelperProcess.Receipt receipt){assertNotNull(receipt.actualProcess());assertFalse(receipt.actualProcess().isAlive(),"actual owned Process remains alive");assertEquals(receipt.exit(),receipt.actualProcess().exitValue());}
    private static final class ModelProcess extends Process {
        final AtomicLong clock;final Path ready;final long readyAt,exitAt;boolean alive=true,destroyed;int exit;
        ModelProcess(AtomicLong clock,Path ready,long readyAt,long exitAt){this.clock=clock;this.ready=ready;this.readyAt=TimeUnit.MILLISECONDS.toNanos(readyAt);this.exitAt=TimeUnit.MILLISECONDS.toNanos(exitAt);}
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}
        public InputStream getInputStream(){return InputStream.nullInputStream();}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor(){return exitValue();}
        public boolean waitFor(long time,TimeUnit unit){
            long tick=clock.addAndGet(unit.toNanos(time));
            try {if(tick>=readyAt && !Files.exists(ready))Files.writeString(ready,"ready");}catch(IOException failure){throw new UncheckedIOException(failure);}
            if(tick>=exitAt)alive=false;return !alive;
        }
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return exit;}
        public boolean isAlive(){return alive;}
        public void destroy(){alive=false;destroyed=true;exit=137;}
        public Process destroyForcibly(){destroy();return this;}
        public long pid(){return 99999999;}
    }
}
