package com.datacube.export;

import com.datacube.service.ConnectionManager;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Synthetic helper bridge to the actual TableExporter -> PgDumpRunner -> publisher path. */
public final class PgDumpTestJobs {
    private PgDumpTestJobs() {}
    public static final class Control {
        public final Path directory;
        public final String mode;
        public final AtomicLong clock = new AtomicLong();
        public final AtomicReference<Process> process = new AtomicReference<>();
        final AtomicReference<PgDumpRunner.Receipt> receipt = new AtomicReference<>();
        public final CountDownLatch started = new CountDownLatch(1), received = new CountDownLatch(1),
                pending = new CountDownLatch(1), settled = new CountDownLatch(1), captured = new CountDownLatch(1), stopping = new CountDownLatch(1);
        public final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        public HeldProcess fake;
        public boolean drainFailure, startFailure;
        public Error starterFatal;
        public Consumer<ProcessBuilder> configureStart = ignored -> {};
        public Runnable beforeStart = () -> {};
        public Runnable afterSettlement = () -> {};
        public volatile Path temporary;
        private long expectedChild = -1;
        private CountDownLatch exactChildCaptured = new CountDownLatch(1);
        PgDumpRunner.HandleControl handleControl = (handle, force) -> { if (force) handle.destroyForcibly(); else handle.destroy(); };
        Consumer<Thread> launch = Thread::start;
        public Control(Path directory, String mode) throws IOException { this.directory = Files.createDirectories(directory); this.mode = mode; }
        PgDumpRunner.Policy policy(Path temporary) {
            this.temporary = temporary;
            return new PgDumpRunner.Policy(Duration.ofSeconds(1), Duration.ZERO, Duration.ofSeconds(1), clock::get,
                    builder -> {
                        beforeStart.run();
                        configureStart.accept(builder);
                        if (starterFatal != null) throw starterFatal;
                        if (startFailure) throw new IOException("SYNTHETIC_PGDUMP_SECRET start failure");
                        Process child = fake != null ? fake : new TrackingProcess(
                                PgDumpRunnerBaselineRedTest.helper(builder, mode, temporary, directory), drainFailure);
                        process.set(child); started.countDown(); return child;
                    }, snapshot -> {
                        receipt.set(snapshot);
                        synchronized (this) {
                            if (snapshot.descendantPids().contains(expectedChild)) exactChildCaptured.countDown();
                        }
                        if (snapshot.stdoutBytes() >= 65536 && snapshot.stderrBytes() >= 65536) received.countDown();
                        if (!snapshot.descendantPids().isEmpty()) captured.countDown();
                        if (snapshot.cleanupPending()) pending.countDown();
                        if (snapshot.reason() != PgDumpRunner.Reason.NONE) stopping.countDown();
                        if (snapshot.physicallySettled()) { settled.countDown(); afterSettlement.run(); }
                    }, handleControl, thread -> { threads.add(thread); launch.accept(thread); });
        }
        public boolean physicallySettled() { return receipt.get() != null && receipt.get().physicallySettled(); }
        public String diagnostic() { return String.valueOf(receipt.get()); }
        public List<Long> descendantPids() { return receipt.get() == null ? List.of() : receipt.get().descendantPids(); }
        public boolean awaitCaptured(long pid) throws InterruptedException {
            CountDownLatch signal;
            synchronized (this) {
                expectedChild = pid;
                exactChildCaptured = signal = new CountDownLatch(1);
                if (descendantPids().contains(pid)) exactChildCaptured.countDown();
            }
            return signal.await(5, TimeUnit.SECONDS);
        }
        public void expire() { clock.set(Duration.ofSeconds(1).toNanos()); }
        public void advanceCleanup() { clock.addAndGet(Duration.ofSeconds(2).toNanos()); }
        public void releaseHelper() throws IOException { Files.writeString(directory.resolve("release-" + mode), "release"); }
    }
    public static Path export(ConnectionManager manager, TableExporter.Request request, ResultExportOperation operation,
                              Control control) throws Exception {
        return TableExporter.export(manager, request, operation, new SafeResultFilePublisher(),
                (cfg, password, table, content, temporary) -> PgDumpRunner.run(cfg, password, table, content, temporary,
                        operation, control.policy(temporary.toPath())), Files::newOutputStream);
    }
    public static void awaitFile(Path file) throws Exception {
        try (var watcher = FileSystems.getDefault().newWatchService()) {
            file.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(file)) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) throw new IOException("Synthetic helper did not reach protocol event");
                var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key == null) throw new IOException("Synthetic helper did not reach protocol event");
                key.reset();
            }
        }
    }
    public static final class TrackingProcess extends Process {
        public final Process raw;
        public final AtomicInteger maxRead = new AtomicInteger(), closes = new AtomicInteger();
        public final Set<Thread> readers = ConcurrentHashMap.newKeySet();
        public volatile boolean stdinClosed;
        private final InputStream stdout, stderr;
        private final OutputStream stdin;
        TrackingProcess(Process raw, boolean failRead) {
            this.raw = raw;
            stdout = tracked(raw.getInputStream(), false); stderr = tracked(raw.getErrorStream(), failRead);
            stdin = new FilterOutputStream(raw.getOutputStream()) {
                @Override public void close() throws IOException { super.close(); stdinClosed = true; }
            };
        }
        private InputStream tracked(InputStream source, boolean fail) {
            return new FilterInputStream(source) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    readers.add(Thread.currentThread()); maxRead.accumulateAndGet(length, Math::max);
                    if (fail) throw new IOException("SYNTHETIC_PGDUMP_SECRET drain failure");
                    return super.read(bytes, offset, length);
                }
                @Override public void close() throws IOException { super.close(); closes.incrementAndGet(); }
            };
        }
        public OutputStream getOutputStream() { return stdin; }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return stderr; }
        public int waitFor() throws InterruptedException { return raw.waitFor(); }
        public boolean waitFor(long value, TimeUnit unit) throws InterruptedException { return raw.waitFor(value, unit); }
        public int exitValue() { return raw.exitValue(); }
        public void destroy() { raw.destroy(); }
        public Process destroyForcibly() { raw.destroyForcibly(); return this; }
        public boolean isAlive() { return raw.isAlive(); }
        public long pid() { return raw.pid(); }
        public ProcessHandle toHandle() { return raw.toHandle(); }
    }
    /** An explicitly fake root which survives destroy/force and holds both stream-close calls until release. */
    public static final class HeldProcess extends Process {
        public final CountDownLatch forced = new CountDownLatch(1), reading = new CountDownLatch(2);
        private final CountDownLatch released = new CountDownLatch(1);
        public final AtomicInteger destroys = new AtomicInteger(), forces = new AtomicInteger(), closed = new AtomicInteger();
        public final Set<Thread> workers = ConcurrentHashMap.newKeySet();
        public volatile boolean alive = true, stdinClosed;
        public boolean failStdinClose;
        public boolean stdinClosedBeforeFailure;
        public boolean failOutputClose;
        public final CountDownLatch outputCloseFailed = new CountDownLatch(2), outputRetryEntered = new CountDownLatch(2), allowOutputClose = new CountDownLatch(1);
        public final CountDownLatch stdinCloseFailed = new CountDownLatch(1), stdinRetryEntered = new CountDownLatch(1), allowStdinClose = new CountDownLatch(1);
        private final AtomicInteger stdinAttempts = new AtomicInteger();
        private int exit;
        private final InputStream stdout = stream(), stderr = stream();
        private final ProcessHandle handle = new ProcessHandle() {
            public long pid() { return 1_000_000_001L; }
            public Optional<ProcessHandle> parent() { return Optional.empty(); }
            public Stream<ProcessHandle> children() { return Stream.empty(); }
            public Stream<ProcessHandle> descendants() { return Stream.empty(); }
            public Info info() { throw new UnsupportedOperationException("Synthetic handle has no OS info"); }
            public CompletableFuture<ProcessHandle> onExit() { return CompletableFuture.supplyAsync(() -> { awaitRelease(); return this; }); }
            public boolean supportsNormalTermination() { return true; }
            public boolean destroy() { HeldProcess.this.destroy(); return true; }
            public boolean destroyForcibly() { HeldProcess.this.destroyForcibly(); return true; }
            public boolean isAlive() { return alive; }
            public int compareTo(ProcessHandle other) { return Long.compare(pid(), other.pid()); }
        };
        private void awaitRelease() {
            boolean interrupted = false;
            while (true) try { released.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
        private InputStream stream() {
            var attempts = new AtomicInteger();
            return new InputStream() {
                public int read() { workers.add(Thread.currentThread()); reading.countDown(); awaitRelease(); return -1; }
                public int read(byte[] bytes, int offset, int length) { return read(); }
                public void close() throws IOException {
                    workers.add(Thread.currentThread()); awaitRelease();
                    if (failOutputClose) {
                        if (attempts.incrementAndGet() == 1) { outputCloseFailed.countDown(); throw new IOException("Synthetic output close failed while open"); }
                        outputRetryEntered.countDown();
                        try { allowOutputClose.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Synthetic interrupted close"); }
                    }
                    closed.incrementAndGet();
                }
            };
        }
        public void release(int code) { exit = code; alive = false; released.countDown(); }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return stderr; }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream() {
            public void close() throws IOException {
                if (stdinClosed) return;
                if (failStdinClose) {
                    if (stdinAttempts.incrementAndGet() == 1) {
                        stdinClosed = stdinClosedBeforeFailure; stdinCloseFailed.countDown();
                        throw new IOException("Synthetic close failure");
                    }
                    stdinRetryEntered.countDown();
                    try { assertPermit(allowStdinClose); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException("Synthetic interrupted close"); }
                }
                stdinClosed = true;
            }
        }; }
        private static void assertPermit(CountDownLatch permission) throws InterruptedException { permission.await(); }
        public int waitFor() throws InterruptedException { released.await(); return exit; }
        public boolean waitFor(long value, TimeUnit unit) throws InterruptedException { return released.await(value, unit); }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return exit; }
        public void destroy() { destroys.incrementAndGet(); }
        public Process destroyForcibly() { forces.incrementAndGet(); forced.countDown(); return this; }
        public boolean isAlive() { return alive; }
        public long pid() { return handle.pid(); }
        public ProcessHandle toHandle() { return handle; }
    }
}
