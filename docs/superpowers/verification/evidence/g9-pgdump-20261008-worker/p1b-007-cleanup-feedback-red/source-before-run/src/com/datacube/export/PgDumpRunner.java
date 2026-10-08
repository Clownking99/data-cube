package com.datacube.export;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableRef;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;

/** A single owned pg_dump job. Output contents and connection secrets never enter diagnostics. */
public final class PgDumpRunner {
    private PgDumpRunner() {}
    enum Reason { NONE, PREPARE, START, OUTPUT, NONZERO, TIMEOUT, CANCELLED, DESCENDANTS, CONTROL }
    public static final class Failure extends IOException {
        private final Reason reason;
        private Failure(Reason reason) { super("pg_dump failed at " + reason); this.reason = reason; }
        Reason reason() { return reason; }
    }
    @FunctionalInterface interface Starter { Process start(ProcessBuilder builder) throws IOException; }
    @FunctionalInterface interface HandleControl { void signal(ProcessHandle handle, boolean force); }
    @FunctionalInterface interface ScratchAttributes { java.nio.file.attribute.BasicFileAttributes read(Path path) throws IOException; }
    record Policy(Duration timeout, Duration grace, Duration observation, LongSupplier clock,
                  Starter starter, Consumer<Receipt> observer, HandleControl control, Consumer<Thread> launch) {
        Policy(Duration timeout, Duration grace, Duration observation, LongSupplier clock,
               Starter starter, Consumer<Receipt> observer) {
            this(timeout, grace, observation, clock, starter, observer,
                    (handle, force) -> { if (force) handle.destroyForcibly(); else handle.destroy(); }, Thread::start);
        }
        Policy(Duration timeout, Duration grace, Duration observation, LongSupplier clock,
               Starter starter, Consumer<Receipt> observer, HandleControl control) {
            this(timeout, grace, observation, clock, starter, observer, control, Thread::start);
        }
        Policy {
            Objects.requireNonNull(timeout); Objects.requireNonNull(grace); Objects.requireNonNull(observation);
            Objects.requireNonNull(clock); Objects.requireNonNull(starter); Objects.requireNonNull(observer);
            Objects.requireNonNull(control);
            Objects.requireNonNull(launch);
            if (timeout.isNegative() || timeout.isZero() || grace.isNegative() || observation.isNegative())
                throw new IllegalArgumentException("Invalid pg_dump budget");
            timeout.toNanos(); grace.toNanos(); observation.toNanos();
        }
        @Override public String toString() { return "PgDumpPolicy"; }
    }
    record Receipt(long rootPid, List<Long> descendantPids, boolean startPending, boolean rootAlive,
                   int livingDescendants, int livingWorkers, long stdoutBytes, long stderrBytes,
                   Reason reason, boolean cleanupPending, boolean physicallySettled) {}
    private static Policy defaults() {
        return new Policy(Duration.ofMinutes(10), Duration.ofSeconds(1), Duration.ofSeconds(5),
                System::nanoTime, ProcessBuilder::start, ignored -> {});
    }
    public static void run(ConnConfig cfg, String password, TableRef table, ExportContent content, File out) throws Exception {
        run(cfg, password, table, content, out, new ResultExportOperation(), defaults());
    }
    public static void run(ConnConfig cfg, String password, TableRef table, ExportContent content,
                           File out, ResultExportOperation operation) throws Exception {
        run(cfg, password, table, content, out, operation, defaults());
    }
    static void run(ConnConfig cfg, String password, TableRef table, ExportContent content,
                    File out, Starter starter, Duration timeout) throws Exception {
        run(cfg, password, table, content, out, new ResultExportOperation(),
                new Policy(timeout, Duration.ofMillis(100), Duration.ofSeconds(5), System::nanoTime, starter, ignored -> {}));
    }
    static void run(ConnConfig cfg, String password, TableRef table, ExportContent content,
                    File out, ResultExportOperation operation, Policy policy) throws Exception {
        long beginning = policy.clock().getAsLong();
        operation.check();
        Prepared prepared = prepare(cfg, password, table, content, out.toPath());
        Session session = null;
        Error fatal = null;
        boolean interrupted = false;
        try {
            session = new Session(operation, policy, beginning);
            Session owned = session;
            operation.requireBeforePublication(() -> {
                if (policy.clock().getAsLong() - beginning >= policy.timeout().toNanos()) {
                    owned.stop(Reason.TIMEOUT); owned.observe(true); throw new Failure(Reason.TIMEOUT);
                }
            });
            try {
                policy.launch().accept(session.supervisor);
                if (session.supervisor.getState() == Thread.State.NEW) throw new Failure(Reason.START);
                operation.check();
                session.register(policy.starter().start(prepared.builder));
            } catch (CancellationException cancelled) { session.stop(Reason.CANCELLED); }
            catch (Error error) { fatal = error; session.stop(Reason.START); }
            catch (Exception failure) { session.stop(Reason.START); }
            finally {
                prepared.builder.environment().remove("PGPASSWORD");
                session.setupComplete = true;
                LockSupport.unpark(session.supervisor);
            }
            // Interruption requests stopping; it never proves a child or drainer ended.
            while (session.supervisor.isAlive()) {
                try { session.supervisor.join(); }
                catch (InterruptedException stop) { interrupted = true; session.stop(Reason.CANCELLED); }
            }
            if (session.fatal.get() != null && fatal == null) fatal = session.fatal.get();
            if (fatal != null) throw fatal;
            if (session.reason.get() == Reason.CANCELLED)
                throw new CancellationException("pg_dump cancelled after resource settlement");
            if (session.reason.get() != Reason.NONE) throw new Failure(session.reason.get());
            operation.check();
        } catch (Error error) {
            fatal = error;
            throw error;
        } finally {
            prepared.builder.environment().remove("PGPASSWORD");
            try { prepared.close(); }
            catch (IOException | RuntimeException | Error failure) {
                var cleanup = new SafeResultFilePublisher.Failure(SafeResultFilePublisher.Stage.CLEANUP, prepared.root);
                if (fatal != null) fatal.addSuppressed(cleanup);
                else if (failure instanceof Error error) throw error;
                else throw cleanup;
            } finally {
                try { if (session != null) session.observe(true); }
                catch (Error observation) {
                    if (fatal == null) throw observation;
                    if (fatal != observation) fatal.addSuppressed(new Failure(Reason.CONTROL));
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    private static String literal(String value) throws Failure {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) throw new Failure(Reason.PREPARE);
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
    private static String pattern(String value) throws Failure {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) throw new Failure(Reason.PREPARE);
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    static Prepared prepare(ConnConfig cfg, String password, TableRef table, ExportContent content, Path output) throws IOException {
        return prepare(cfg, password, table, content, output,
                path -> Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
    }
    static Prepared prepare(ConnConfig cfg, String password, TableRef table, ExportContent content, Path output,
                            ScratchAttributes attributes) throws IOException {
        if (cfg == null || table == null || content == null || output == null
                || cfg.type() != DbType.POSTGRESQL || cfg.port() < 1 || cfg.port() > 65535)
            throw new Failure(Reason.PREPARE);
        String host = literal(cfg.host()), database = literal(cfg.database()), user = literal(cfg.username());
        if (cfg.host().contains(",") || cfg.host().startsWith("/") || cfg.host().startsWith("@")
                || cfg.host().matches("^[A-Za-z]:.*")) throw new Failure(Reason.PREPARE);
        String tablePattern = pattern(table.schema()) + "." + pattern(table.name());
        if (password != null && password.indexOf('\0') >= 0) throw new Failure(Reason.PREPARE);
        Path root;
        try { root = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".datacube-pgdump-"); }
        catch (IOException | RuntimeException failure) { throw new Failure(Reason.PREPARE); }
        Path passfile = root.resolve("empty.pgpass"), service = root.resolve("empty.pg_service.conf");
        Prepared prepared = new Prepared(root, passfile, service, attributes);
        try {
            Files.write(passfile, new byte[0], StandardOpenOption.CREATE_NEW);
            Files.write(service, new byte[0], StandardOpenOption.CREATE_NEW);
            prepared.captureFiles();
            try {
                Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                Files.setPosixFilePermissions(passfile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException platformAcl) { /* Empty files contain no credentials. */ }
            String conninfo = "host=" + host + " port=" + literal(Integer.toString(cfg.port()))
                    + " dbname=" + database + " user=" + user + " passfile=" + literal(passfile.toString())
                    + " sslcert=" + literal(root.resolve("absent-client.crt").toString())
                    + " sslkey=" + literal(root.resolve("absent-client.key").toString())
                    + " sslrootcert=" + literal(root.resolve("absent-root.crt").toString())
                    + " sslcrl=" + literal(root.resolve("absent-root.crl").toString())
                    + " gssencmode='disable' require_auth='!gss,!sspi'";
            var command = new ArrayList<>(List.of("pg_dump", "--format=plain", "--no-password", "--dbname", conninfo,
                    "--table", tablePattern));
            if (content == ExportContent.STRUCTURE) command.add("--schema-only");
            else if (content == ExportContent.DATA) command.add("--data-only");
            command.addAll(List.of("-f", output.toAbsolutePath().toString()));
            prepared.builder = new ProcessBuilder(command).directory(root.toFile());
            Map<String,String> env = prepared.builder.environment(); env.clear();
            for (String key : List.of("SystemRoot", "WINDIR", "PATH", "COMSPEC", "PATHEXT")) {
                String value = System.getenv(key); if (value != null) env.put(key, value);
            }
            for (String key : List.of("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "TEMP", "TMP"))
                env.put(key, root.toString());
            env.put("PGPASSFILE", passfile.toString()); env.put("PGSERVICEFILE", service.toString());
            env.put("PGSYSCONFDIR", root.toString()); env.put("PGPASSWORD", password == null ? "" : password);
            return prepared;
        } catch (IOException | RuntimeException | Error failure) {
            try { prepared.close(); }
            catch (IOException | RuntimeException | Error cleanup) {
                var safe = new SafeResultFilePublisher.Failure(SafeResultFilePublisher.Stage.CLEANUP, root);
                if (failure instanceof Error fatal) { fatal.addSuppressed(safe); throw fatal; }
                if (cleanup instanceof Error fatal) { fatal.addSuppressed(safe); throw fatal; }
                throw safe;
            }
            if (failure instanceof Error fatal) throw fatal;
            throw new Failure(Reason.PREPARE);
        }
    }
    static final class Prepared implements AutoCloseable {
        final Path root, passfile, service;
        ProcessBuilder builder;
        private record Stamp(Object key, java.nio.file.attribute.FileTime created, long size,
                             java.nio.file.attribute.FileTime modified) {}
        private final Map<Path,Stamp> owned = new HashMap<>();
        ScratchAttributes attributes;
        private Object rootKey;
        private java.nio.file.attribute.FileTime rootCreated;
        Prepared(Path root, Path passfile, Path service, ScratchAttributes attributes) {
            this.root = root; this.passfile = passfile; this.service = service; this.attributes = attributes;
        }
        @Override public String toString() { return "PgDumpPrepared[isolated]"; }
        void captureFiles() throws IOException {
            var directory = attributes.read(root);
            rootKey = directory.fileKey(); rootCreated = directory.creationTime();
            for (Path path : List.of(passfile, service)) owned.put(path, stamp(path));
        }
        private Stamp stamp(Path path) throws IOException {
            var attrs = attributes.read(path);
            if (!attrs.isRegularFile() || attrs.isOther() || attrs.isSymbolicLink()) throw new IOException("Isolation identity changed");
            return new Stamp(attrs.fileKey(), attrs.creationTime(), attrs.size(), attrs.lastModifiedTime());
        }
        @Override public void close() throws IOException {
            if (builder != null) builder.environment().remove("PGPASSWORD");
            IOException first = null;
            var directory = attributes.read(root);
            if (!directory.isDirectory() || directory.isOther() || directory.isSymbolicLink()
                    || rootCreated != null && (!Objects.equals(rootKey, directory.fileKey()) || !rootCreated.equals(directory.creationTime())))
                throw new IOException("Isolation parent changed");
            for (Path path : List.of(passfile, service, root)) {
                try {
                    if (!path.equals(root)) {
                        if (!owned.containsKey(path)) continue;
                        if (!stamp(path).equals(owned.get(path))) throw new IOException("Isolation file changed");
                    }
                    Files.deleteIfExists(path);
                } catch (NoSuchFileException absent) { /* Only confirmed absence requires no deletion. */ }
                catch (IOException failure) { if (first == null) first = failure; }
            }
            if (first != null) throw first;
        }
    }

    private static final class Session {
        private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
        final ResultExportOperation operation;
        final Policy policy;
        final long beginning;
        final AtomicReference<Reason> reason = new AtomicReference<>(Reason.NONE);
        final AtomicReference<Error> fatal = new AtomicReference<>();
        final AtomicLong stdout = new AtomicLong(), stderr = new AtomicLong();
        final Map<Long,ProcessHandle> descendants = new ConcurrentHashMap<>();
        final List<Thread> workers = new CopyOnWriteArrayList<>();
        final Thread supervisor;
        final Controls normalControls = new Controls(false), forceControls = new Controls(true);
        volatile Process process;
        volatile InputStream output, error;
        volatile OutputStream input;
        volatile boolean setupComplete, cleanupPending;
        volatile long stopAt;
        boolean normalSent, forceSent, streamsClosing;
        long normalAt;
        Session(ResultExportOperation operation, Policy policy, long beginning) {
            this.operation = operation; this.policy = policy; this.beginning = beginning;
            supervisor = Thread.ofVirtual().name("DataCube-pgdump-supervisor").unstarted(this::supervise);
        }
        void stop(Reason cause) {
            if (reason.compareAndSet(Reason.NONE, cause)) stopAt = policy.clock().getAsLong();
            operation.cancel(); LockSupport.unpark(supervisor);
        }
        void startWorker(String name, Runnable action) {
            Thread thread = Thread.ofVirtual().name("DataCube-pgdump-" + name).unstarted(() -> {
                try { action.run(); }
                catch (Error error) { fatal.compareAndSet(null, error); stop(Reason.CONTROL); }
                catch (RuntimeException failure) { stop(Reason.CONTROL); }
                finally { LockSupport.unpark(supervisor); }
            });
            workers.add(thread); thread.start();
        }
        void register(Process child) {
            process = Objects.requireNonNull(child);
            output = child.getInputStream(); error = child.getErrorStream(); input = child.getOutputStream();
            startWorker("stdin-close", () -> close(input));
            startWorker("stdout", () -> drain(output, stdout));
            startWorker("stderr", () -> drain(error, stderr));
        }
        void close(Closeable stream) {
            if (stream == null) return;
            try { stream.close(); }
            catch (IOException failure) { if (reason.get() == Reason.NONE) stop(Reason.OUTPUT); }
        }
        void drain(InputStream stream, AtomicLong count) {
            byte[] bytes = new byte[8192];
            try {
                int n;
                while ((n = stream.read(bytes)) >= 0) {
                    if (n == 0) { Thread.yield(); continue; }
                    final int consumed = n;
                    count.updateAndGet(prior -> prior > Long.MAX_VALUE - consumed ? Long.MAX_VALUE : prior + consumed);
                }
            } catch (IOException failure) { if (reason.get() == Reason.NONE) stop(Reason.OUTPUT); }
            finally { close(stream); }
        }
        void capture() {
            Process child = process;
            var ancestors = new ArrayList<>(descendants.values());
            if (child != null && child.isAlive()) ancestors.add(child.toHandle());
            for (var ancestor : ancestors) if (ancestor.isAlive()) {
                try (var handles = ancestor.descendants()) {
                    handles.forEach(handle -> descendants.compute(handle.pid(), (pid, prior) ->
                            prior == null || !prior.isAlive() ? handle : prior));
                } catch (RuntimeException failure) { stop(Reason.CONTROL); }
            }
        }
        /** One queue/worker per stop phase. A blocked native call stays owned without blocking observation. */
        final class Controls {
            final boolean force;
            final Queue<ProcessHandle> pending = new ConcurrentLinkedQueue<>();
            final Set<ProcessHandle> submitted = Collections.newSetFromMap(new IdentityHashMap<>());
            volatile boolean closing;
            Thread thread;
            Controls(boolean force) { this.force = force; }
            void enqueue() {
                for (ProcessHandle handle : descendants.values()) if (handle.isAlive() && submitted.add(handle)) pending.add(handle);
                if (thread == null && !pending.isEmpty()) {
                    thread = Thread.ofVirtual().name("DataCube-pgdump-handles-" + force).unstarted(this::run);
                    workers.add(thread); thread.start();
                }
                if (thread != null) LockSupport.unpark(thread);
            }
            void finish() { closing = true; if (thread != null) LockSupport.unpark(thread); }
            void run() {
                while (true) {
                    ProcessHandle handle = pending.poll();
                    if (handle != null) {
                        try { if (handle.isAlive()) policy.control().signal(handle, force); }
                        catch (Error failure) { fatal.compareAndSet(null, failure); stop(Reason.CONTROL); }
                        catch (RuntimeException failure) { stop(Reason.CONTROL); }
                    } else if (closing) return;
                    else LockSupport.parkNanos(POLL_NANOS);
                    if (Thread.interrupted()) stop(Reason.CANCELLED);
                    LockSupport.unpark(supervisor);
                }
            }
        }
        int livingDescendants() { return (int) descendants.values().stream().filter(ProcessHandle::isAlive).count(); }
        int livingWorkers() { return (int) workers.stream().filter(Thread::isAlive).count(); }
        void supervise() {
            while (true) {
                try {
                    long now = policy.clock().getAsLong();
                    if (operation.cancelled()) stop(Reason.CANCELLED);
                    if (now - beginning >= policy.timeout().toNanos()) stop(Reason.TIMEOUT);
                    if (reason.get() != Reason.NONE && !cleanupPending && now - stopAt >= policy.observation().toNanos()) {
                        cleanupPending = true; operation.markCleanupPending();
                    }
                    capture();
                    Process child = process;
                    boolean alive = child != null && child.isAlive();
                    if (child != null && !alive && setupComplete && reason.get() == Reason.NONE) {
                        if (child.exitValue() != 0) stop(Reason.NONZERO);
                        else if (livingDescendants() != 0) stop(Reason.DESCENDANTS);
                    }
                    if (reason.get() != Reason.NONE) {
                        if (child != null && !normalSent) {
                            normalSent = true; normalAt = now;
                            startWorker("terminate", child::destroy);
                        }
                        if (normalSent && !forceSent && now - normalAt >= policy.grace().toNanos()) {
                            forceSent = true;
                            startWorker("force", child::destroyForcibly);
                        }
                        normalControls.enqueue();
                        if (forceSent) forceControls.enqueue();
                        if (setupComplete && !streamsClosing) {
                            streamsClosing = true;
                            startWorker("stdout-close", () -> close(output));
                            startWorker("stderr-close", () -> close(error));
                        }
                    }
                    if (setupComplete && !alive && livingDescendants() == 0) {
                        normalControls.finish(); forceControls.finish();
                    }
                    if (setupComplete && !alive && livingDescendants() == 0 && livingWorkers() == 0) return;
                    observe(false);
                } catch (Error error) { fatal.compareAndSet(null, error); stop(Reason.CONTROL); }
                catch (RuntimeException failure) { stop(Reason.CONTROL); }
                LockSupport.parkNanos(POLL_NANOS);
                if (Thread.interrupted()) stop(Reason.CANCELLED);
            }
        }
        void observe(boolean settled) {
            Process child = process;
            long pid = -1;
            if (child != null) try { pid = child.pid(); } catch (RuntimeException ignored) {}
            var receipt = new Receipt(pid, descendants.keySet().stream().sorted().toList(), !setupComplete,
                    child != null && child.isAlive(), livingDescendants(), livingWorkers(), stdout.get(), stderr.get(),
                    reason.get(), cleanupPending, settled);
            try { policy.observer().accept(receipt); } catch (RuntimeException ignored) {}
        }
    }
}
