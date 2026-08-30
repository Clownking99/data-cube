package com.datacube.export;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class SafeResultFilePublisher {
    public enum Stage { PREPARE, TARGET_CHANGED, TARGET_BUSY, WRITE, PUBLISH, CLEANUP }

    public static final class Failure extends IOException {
        private final Stage stage;
        private final Path temporaryPath;
        public Failure(Stage stage, Path temporaryPath) {
            super("Result export failed at " + stage);
            this.stage = stage;
            this.temporaryPath = temporaryPath;
        }
        public Stage stage() { return stage; }
        public Path temporaryPath() { return temporaryPath; }
    }

    private record Stamp(boolean exists, Object key, long size, FileTime modified, FileTime created) {}

    public static final class Target {
        private final Path path;
        private final Stamp stamp;
        private Target(Path path, Stamp stamp) { this.path = path; this.stamp = stamp; }
        public Path path() { return path; }
        public boolean existed() { return stamp.exists(); }
    }

    @FunctionalInterface public interface TempWriter {
        void write(Path path, ResultExportOperation operation) throws Exception;
    }
    @FunctionalInterface interface AtomicMover { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface TempCleaner { void delete(Path path) throws IOException; }

    private static final Set<Path> BUSY = ConcurrentHashMap.newKeySet();
    private static final Logger LOG = Logger.getLogger(SafeResultFilePublisher.class.getName());
    private final AtomicMover mover;
    private final TempCleaner cleaner;
    private final Consumer<Path> cleanupDiagnostic;

    public SafeResultFilePublisher() {
        this((source, target) -> Files.move(source, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                path -> Files.deleteIfExists(path),
                path -> LOG.warning("Result export CLEANUP: " + path));
    }

    SafeResultFilePublisher(AtomicMover mover, TempCleaner cleaner, Consumer<Path> diagnostic) {
        this.mover = Objects.requireNonNull(mover);
        this.cleaner = Objects.requireNonNull(cleaner);
        this.cleanupDiagnostic = Objects.requireNonNull(diagnostic);
    }

    public static Target capture(Path chosen) throws Failure {
        try {
            Path absolute = chosen.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null || absolute.getFileName() == null) throw new IOException("Unsupported export path");
            Path path = parent.toRealPath().resolve(absolute.getFileName());
            return new Target(path, stamp(path));
        } catch (IOException | RuntimeException failure) {
            throw new Failure(Stage.PREPARE, null);
        }
    }

    private static Stamp stamp(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Unsupported export target");
            return new Stamp(true, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
        } catch (NoSuchFileException absent) {
            return new Stamp(false, null, 0, null, null);
        }
    }

    private static void verify(Target target) throws Failure {
        try {
            Path parent = target.path.getParent();
            if (!parent.toRealPath().equals(parent) || !stamp(target.path).equals(target.stamp))
                throw new IOException("Export target changed");
        } catch (IOException | RuntimeException changed) {
            throw new Failure(Stage.TARGET_CHANGED, null);
        }
    }

    public Path publish(Target target, ResultExportOperation operation, TempWriter writer) throws Exception {
        Objects.requireNonNull(target); Objects.requireNonNull(operation); Objects.requireNonNull(writer);
        if (!BUSY.add(target.path)) throw new Failure(Stage.TARGET_BUSY, null);
        Path temporary = null;
        Stage stage = Stage.PREPARE;
        try {
            operation.check(); verify(target);
            temporary = Files.createTempFile(target.path.getParent(), ".datacube-export-", ".tmp");
            stage = Stage.WRITE;
            operation.check(); writer.write(temporary, operation);
            stage = Stage.PUBLISH;
            Path ready = temporary;
            operation.publish(() -> { verify(target); mover.move(ready, target.path); });
            return target.path;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Failure safe) {
            throw safe;
        } catch (Exception failure) {
            throw new Failure(stage, null);
        } finally {
            try {
                if (temporary != null && !operation.published()) {
                    try { cleaner.delete(temporary); }
                    catch (IOException | RuntimeException cleanupFailure) {
                        try { cleanupDiagnostic.accept(temporary); } catch (RuntimeException ignored) { }
                        throw new Failure(Stage.CLEANUP, temporary);
                    }
                }
            } finally { BUSY.remove(target.path); }
        }
    }
}
