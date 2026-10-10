package com.datacube.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/** Only the synthetic update-helper tests: bootstrap acknowledgement, execution and physical exit. */
final class UpdateHelperProcess {
    static final int OUTPUT_LIMIT=64*1024;
    enum Phase { STARTUP, EXECUTION, EXIT, INTERRUPTED }
    record Budgets(Duration startup, Duration execution, Duration cleanup) {
        static Budgets normal() { return new Budgets(Duration.ofSeconds(90), Duration.ofSeconds(20), Duration.ofSeconds(10)); }
    }
    @FunctionalInterface interface Starter { Process start(ProcessBuilder command, Path ready) throws IOException; }
    record Receipt(Phase phase, Process actualProcess, long pid, Integer exit, boolean terminationRequested, boolean exited,
                   boolean interrupted, long startupNanos, long executionNanos, long cleanupNanos, Budgets budgets, List<String> command,
                   Path stdout, Path stderr, String stdoutHash, String stderrHash, String stdoutBase64, String stderrBase64,
                   long stdoutLength, long stderrLength, boolean outputComplete) {
        String diagnostic() { return "phase="+phase+" pid="+pid+" exit="+exit+" terminationRequested="+terminationRequested
                +" exited="+exited+" interrupted="+interrupted+" startupNanos="+startupNanos+" executionNanos="+executionNanos
                +" cleanupNanos="+cleanupNanos+" budgets="+budgets+" command="+command+" stdout="+stdout+" sha256="+stdoutHash+" base64="+stdoutBase64
                +" stdoutLength="+stdoutLength+" stderrLength="+stderrLength+" outputComplete="+outputComplete+" outputLimit="+OUTPUT_LIMIT
                +" stderr="+stderr+" sha256="+stderrHash+" base64="+stderrBase64; }
    }
    static final class Failure extends AssertionError {
        final Receipt receipt;
        Failure(String reason, Receipt receipt) { super(reason+"; "+receipt.diagnostic()); this.receipt=receipt; }
    }
    private final Budgets budgets;
    private final Starter starter;
    private final LongSupplier clock;
    private final BiConsumer<Phase,Process> observation;
    UpdateHelperProcess() { this(Budgets.normal(), (command, ready)->command.start(), System::nanoTime, (phase, process)->{}); }
    UpdateHelperProcess(Budgets budgets, Starter starter, LongSupplier clock, BiConsumer<Phase,Process> observation) {
        this.budgets=budgets; this.starter=starter; this.clock=clock; this.observation=observation;
    }
    Receipt run(Path directory, String script, int expectedExit) throws Exception {
        Path own=Files.createDirectory(directory.resolve("update-process-"+UUID.randomUUID()));
        Path body=own.resolve("harness.ps1"), bootstrap=own.resolve("bootstrap.ps1"), ready=own.resolve("bootstrap.ready");
        Path stdout=own.resolve("stdout.bin"),stderr=own.resolve("stderr.bin");
        // BOM is required by Windows PowerShell 5.1 for literal Unicode fixture paths.
        Files.writeString(body,"\uFEFF"+script,StandardCharsets.UTF_8);
        Files.writeString(bootstrap,"\uFEFF$ErrorActionPreference='Stop'\ntry {\n[IO.File]::WriteAllText("
                +PortableUpdateHelperTest.ps(ready.toString())+",'ready')\n& "+PortableUpdateHelperTest.ps(body.toString())
                +"\nif($null -ne $LASTEXITCODE){exit $LASTEXITCODE}\nexit 0\n} catch { Write-Error $_ -ErrorAction Continue; exit 1 }\n",StandardCharsets.UTF_8);
        String executable=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),
                "System32","WindowsPowerShell","v1.0","powershell.exe").toString();
        List<String> command=List.of(executable,"-NoProfile","-NonInteractive","-File",bootstrap.toString());
        ProcessBuilder builder=new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        Process process=null;Phase phase=Phase.STARTUP;String reason=null;boolean interrupted=false,terminated=false,acknowledged=false;
        long begin=clock.getAsLong(),executionStart=-1,end=begin,cleanupNanos=0;Integer exit=null;boolean exited=false;
        try {
        try {
            process=starter.start(builder,ready); process.getOutputStream().close(); observation.accept(Phase.STARTUP,process);
            while(true) {
                long now=clock.getAsLong();
                if(phase==Phase.STARTUP && now-begin>=budgets.startup().toNanos()) { reason="STARTUP_TIMEOUT";break; }
                if(phase==Phase.STARTUP && Files.exists(ready) && Files.readString(ready).equals("ready")) {
                    acknowledged=true;executionStart=now;phase=Phase.EXECUTION;observation.accept(phase,process);
                }
                if(!process.isAlive()) { phase=Phase.EXIT;break; }
                long elapsed=now-(phase==Phase.STARTUP?begin:executionStart);
                long limit=(phase==Phase.STARTUP?budgets.startup():budgets.execution()).toNanos();
                if(elapsed>=limit) { reason=phase==Phase.STARTUP?"STARTUP_TIMEOUT":"EXECUTION_TIMEOUT";break; }
                process.waitFor(Math.max(1,Math.min(25,TimeUnit.NANOSECONDS.toMillis(limit-elapsed))),TimeUnit.MILLISECONDS);
            }
        } catch(InterruptedException failure) { interrupted=true;phase=Phase.INTERRUPTED;reason="INTERRUPTED"; }
        catch(IOException failure) { reason=phase+"_IO: "+failure; }
        finally {
            long cleanupStart=System.nanoTime();
            if(process!=null) {
                if(process.isAlive()) { terminated=true;process.destroyForcibly(); }
                long deadline=System.nanoTime()+budgets.cleanup().toNanos();
                while(process.isAlive() && System.nanoTime()<deadline) {
                    try { process.waitFor(Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())),TimeUnit.MILLISECONDS); }
                    catch(InterruptedException failure) { interrupted=true; }
                }
                exited=!process.isAlive();if(exited)exit=process.exitValue();
            }
            end=clock.getAsLong();cleanupNanos=System.nanoTime()-cleanupStart;
        }
        try {
            Captured out=capture(stdout),err=capture(stderr);
            Receipt receipt=new Receipt(phase,process,process==null?-1:process.pid(),exit,terminated,exited,interrupted,
                    (executionStart<0?end:executionStart)-begin,executionStart<0?0:end-executionStart,cleanupNanos,budgets,command,stdout,stderr,
                    out.hash(),err.hash(),Base64.getEncoder().encodeToString(out.bytes()),Base64.getEncoder().encodeToString(err.bytes()),
                    out.length(),err.length(),out.complete() && err.complete());
            System.out.println("UPDATE_HELPER_RECEIPT "+receipt.diagnostic());
            if(process==null)throw new Failure(reason,receipt);
            if(!exited)throw new Failure("PHYSICAL_EXIT_NOT_OBSERVED; firstFailure="+reason,receipt);
            if(reason!=null)throw new Failure(reason,receipt);
            if(!receipt.outputComplete())throw new Failure("OUTPUT_LIMIT",receipt);
            if(exit!=expectedExit)throw new Failure("EXPECTED_EXIT_"+expectedExit,receipt);
            if(!acknowledged)throw new Failure("BOOTSTRAP_NOT_ACKNOWLEDGED",receipt);
            return receipt;
        } catch(IOException failure) {
            throw new AssertionError("OUTPUT_READ_FAILURE; firstFailure="+reason+" phase="+phase+" pid="+(process==null?-1:process.pid())+" exited="+exited,failure);
        }
        } finally { if(interrupted)Thread.currentThread().interrupt(); }
    }
    private record Captured(byte[] bytes,long length,String hash,boolean complete) { }
    private static Captured capture(Path path) throws Exception {
        if(!Files.exists(path))return new Captured(new byte[0],0,hash(new byte[0]),true);
        long length=Files.size(path);byte[] bytes;
        try(var input=Files.newInputStream(path)){bytes=input.readNBytes(OUTPUT_LIMIT+1);}
        boolean complete=length<=OUTPUT_LIMIT && bytes.length<=OUTPUT_LIMIT;
        return new Captured(bytes.length>OUTPUT_LIMIT?Arrays.copyOf(bytes,OUTPUT_LIMIT):bytes,length,
                complete?hash(bytes):"NOT_COMPLETE",complete);
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
