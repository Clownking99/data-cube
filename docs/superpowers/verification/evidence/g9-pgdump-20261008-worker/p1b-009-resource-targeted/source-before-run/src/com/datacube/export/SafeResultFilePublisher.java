package com.datacube.export;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class SafeResultFilePublisher {
    public enum Stage { PREPARE, TARGET_CHANGED, TARGET_BUSY, WRITE, XML_CHARACTER, PUBLISH, CLEANUP }

    public static final class Failure extends IOException {
        private final Stage stage;
        private final Path temporaryPath;
        private final java.util.List<Path> residualPaths;
        public Failure(Stage stage, Path temporaryPath) {
            this(stage, temporaryPath == null ? java.util.List.of() : java.util.List.of(temporaryPath), true);
        }
        private Failure(Stage stage, java.util.List<Path> residualPaths, boolean cleanup) {
            super("Result export failed at " + stage);
            this.stage = stage;
            this.residualPaths = java.util.List.copyOf(residualPaths);
            this.temporaryPath = residualPaths.isEmpty() ? null : residualPaths.getFirst();
        }
        public Stage stage() { return stage; }
        public Path temporaryPath() { return temporaryPath; }
        public java.util.List<Path> residualPaths() { return residualPaths; }
    }

    private record Stamp(boolean exists, Object key, long size, FileTime modified, FileTime created) {}
    private record Identity(Object key, FileTime created) {}

    public static final class Target {
        private final Path path;
        private final Stamp stamp;
        private final Path chosen;
        private final java.util.Map<Path, Identity> parents;
        private Target(Path path, Path chosen, Stamp stamp, java.util.Map<Path, Identity> parents) {
            this.path = path; this.chosen = chosen; this.stamp = stamp; this.parents = parents;
        }
        public Path path() { return path; }
        public boolean existed() { return stamp.exists(); }
    }

    @FunctionalInterface public interface TempWriter {
        void write(Path path, ResultExportOperation operation) throws Exception;
    }
    @FunctionalInterface interface AtomicMover { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface TempCleaner { void delete(Path path) throws IOException; }
    @FunctionalInterface interface AttributeReader { BasicFileAttributes read(Path path) throws IOException; }
    @FunctionalInterface interface LinkCreator { void create(Path link, Path existing) throws IOException; }

    private static final java.util.List<Target> BUSY = new java.util.ArrayList<>();
    private static final Object BUSY_LOCK = new Object();
    private static final Logger LOG = Logger.getLogger(SafeResultFilePublisher.class.getName());
    private final AtomicMover mover;
    private final TempCleaner cleaner;
    private final Consumer<Path> cleanupDiagnostic;
    private final AttributeReader attributes;
    private final LinkCreator links;

    public SafeResultFilePublisher() {
        this((source, target) -> Files.move(source, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                path -> Files.deleteIfExists(path),
                path -> LOG.warning("Result export CLEANUP: " + path));
    }

    SafeResultFilePublisher(AtomicMover mover, TempCleaner cleaner, Consumer<Path> diagnostic) {
        this(mover, cleaner, diagnostic,
                path -> Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
    }

    SafeResultFilePublisher(AtomicMover mover, TempCleaner cleaner, Consumer<Path> diagnostic, AttributeReader attributes) {
        this(mover, cleaner, diagnostic, attributes, Files::createLink);
    }
    SafeResultFilePublisher(AtomicMover mover, TempCleaner cleaner, Consumer<Path> diagnostic,
                            AttributeReader attributes, LinkCreator links) {
        this.mover = Objects.requireNonNull(mover);
        this.cleaner = Objects.requireNonNull(cleaner);
        this.cleanupDiagnostic = Objects.requireNonNull(diagnostic);
        this.attributes = Objects.requireNonNull(attributes);
        this.links = Objects.requireNonNull(links);
    }

    public static Target capture(Path chosen) throws Failure {
        try {
            Path original = chosen.toAbsolutePath();
            // Inspect the spelling before lexical normalization can erase link/.. components.
            noLinks(original);
            Path absolute = original.normalize();
            noLinks(absolute);
            Path parent = absolute.getParent();
            if (parent == null || absolute.getFileName() == null) throw new IOException("Unsupported export path");
            Path path = parent.toRealPath().resolve(absolute.getFileName());
            Stamp stamp = stamp(path);
            if (stamp.exists()) path = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            var parents = new java.util.LinkedHashMap<Path, Identity>();
            for (Path current = path.getParent(); current != null; current = current.getParent()) {
                parents.put(current, identity(current, true));
            }
            return new Target(path, original, stamp, java.util.Map.copyOf(parents));
        } catch (IOException | RuntimeException failure) {
            throw new Failure(Stage.PREPARE, null);
        }
    }

    private static void noLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            try {
                var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Unsupported export path");
            } catch (NoSuchFileException absent) { /* New filename: still inspect ancestors. */ }
        }
    }

    private static Identity identity(Path path, boolean directory) throws IOException {
        return identity(path, directory, candidate -> Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
    }
    private static Identity identity(Path path, boolean directory, AttributeReader reader) throws IOException {
        var attrs = reader.read(path);
        if (directory ? !attrs.isDirectory() : !attrs.isRegularFile()) throw new IOException("Export identity changed");
        if (attrs.isSymbolicLink() || attrs.isOther())
            throw new IOException("Export identity unavailable");
        return new Identity(attrs.fileKey(), attrs.creationTime());
    }

    private static Stamp stamp(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther())
                throw new IOException("Unsupported export target");
            return new Stamp(true, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime());
        } catch (NoSuchFileException absent) {
            return new Stamp(false, null, 0, null, null);
        }
    }

    private static void verify(Target target) throws Failure {
        try {
            noLinks(target.chosen);
            verifyParents(target);
            if (!stamp(target.path).equals(target.stamp))
                throw new IOException("Export target changed");
        } catch (IOException | RuntimeException changed) {
            throw new Failure(Stage.TARGET_CHANGED, null);
        }
    }

    public Path publish(Target target, ResultExportOperation operation, TempWriter writer) throws Exception {
        Objects.requireNonNull(target); Objects.requireNonNull(operation); Objects.requireNonNull(writer);
        synchronized (BUSY_LOCK) {
            for (Target busy : BUSY) {
                boolean same = busy.path.equals(target.path);
                try {
                    if (!same && busy.stamp.exists() && target.stamp.exists())
                        same = Files.isSameFile(busy.path, target.path);
                } catch (IOException unsafeIdentity) { throw new Failure(Stage.TARGET_CHANGED, null); }
                if (same) throw new Failure(Stage.TARGET_BUSY, null);
            }
            BUSY.add(target);
        }
        Path temporary = null;
        Identity temporaryIdentity = null;
        Path witness = null;
        Identity witnessIdentity = null;
        boolean witnessCreated = false;
        Error primaryFatal = null;
        Failure primarySafe = null;
        Stage stage = Stage.PREPARE;
        try {
            operation.check(); verify(target);
            temporary = Files.createTempFile(target.path.getParent(), ".datacube-export-", ".tmp");
            temporaryIdentity = identity(temporary, false, attributes);
            if (temporaryIdentity.key() == null) {
                witness = target.path.getParent().resolve(".datacube-export-" + java.util.UUID.randomUUID() + ".identity");
                links.create(witness, temporary);
                witnessCreated = true;
                witnessIdentity = identity(witness, false, attributes);
            }
            stage = Stage.WRITE;
            operation.check(); writer.write(temporary, operation);
            stage = Stage.PUBLISH;
            Path ready = temporary;
            Identity owned = temporaryIdentity;
            Path proof = witness;
            Identity proofIdentity = witnessIdentity;
            operation.publish(() -> {
                verify(target);
                verifyTemporary(ready, owned, proof, proofIdentity);
                operation.verifyPublicationEligibility();
                mover.move(ready, target.path);
            });
            return target.path;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (ResultExporter.InvalidXmlCharacterException invalidXml) {
            throw new Failure(Stage.XML_CHARACTER, null);
        } catch (Failure safe) {
            primarySafe = safe;
            throw safe;
        } catch (PgDumpRunner.Failure safeDump) {
            throw safeDump;
        } catch (Exception failure) {
            throw new Failure(stage, null);
        } catch (Error fatal) {
            primaryFatal = fatal;
            throw fatal;
        } finally {
            var residues = new java.util.ArrayList<Path>();
            boolean witnessVerified = false;
            try {
                // Keep the identity proof until both names have been verified. Never delete an unproved witness.
                if (witnessCreated) {
                    try {
                        verifyParents(target);
                        Path reference = operation.published() ? target.path : temporary;
                        verifyTemporary(reference, temporaryIdentity, witness, witnessIdentity);
                        witnessVerified = true;
                    } catch (IOException | RuntimeException unsafeWitness) { /* Preserve it and report below. */ }
                }
                if (temporary != null && !operation.published()) {
                    try {
                        verifyParents(target);
                        Identity current = null;
                        try { current = identity(temporary, false, attributes); }
                        catch (NoSuchFileException absent) { /* Only confirmed absence needs no cleanup. */ }
                        if (current != null) {
                            verifyTemporary(temporary, current, temporaryIdentity, witness, witnessIdentity);
                            cleaner.delete(temporary);
                        }
                    }
                    catch (IOException | RuntimeException cleanupFailure) {
                        residues.add(temporary);
                    }
                }
            } finally {
                try {
                    Identity current = null;
                    if (witnessCreated) {
                        try { current = identity(witness, false, attributes); }
                        catch (NoSuchFileException absent) { /* No owned witness remains at this path. */ }
                    }
                    if (current != null) {
                        verifyParents(target);
                        if (!witnessVerified || !current.equals(witnessIdentity)) throw new IOException("Export witness changed");
                        Files.deleteIfExists(witness);
                    }
                } catch (IOException | RuntimeException witnessCleanupFailure) {
                    residues.add(witness);
                } finally {
                    synchronized (BUSY_LOCK) { BUSY.remove(target); }
                }
            }
            boolean localCleanupFailed = !residues.isEmpty();
            if (primarySafe != null) for (Path earlier : primarySafe.residualPaths())
                if (!residues.contains(earlier)) residues.add(earlier);
            for (Path earlier : operation.cleanupResidues()) if (!residues.contains(earlier)) residues.add(earlier);
            for (Path residue : residues) {
                try { cleanupDiagnostic.accept(residue); } catch (RuntimeException ignored) { }
            }
            if (!residues.isEmpty()) {
                operation.recordCleanupResidues(residues);
                // A committed file remains successful, with an explicit warning consumed by both UIs.
                if (!operation.published() && localCleanupFailed) {
                    var cleanup = new Failure(Stage.CLEANUP, residues, true);
                    if (primaryFatal != null) primaryFatal.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }

    private void verifyTemporary(Path path, Identity owned, Path witness, Identity witnessIdentity) throws IOException {
        verifyTemporary(path, identity(path, false, attributes), owned, witness, witnessIdentity);
    }
    private void verifyTemporary(Path path, Identity current, Identity owned, Path witness, Identity witnessIdentity) throws IOException {
        if (!current.equals(owned)) throw new IOException("Export temporary changed");
        if (owned.key() == null && (witness == null || !identity(witness, false, attributes).equals(witnessIdentity)
                || !Files.isSameFile(path, witness))) throw new IOException("Export temporary ownership unavailable");
    }

    private static void verifyParents(Target target) throws IOException {
        noLinks(target.chosen.getParent());
        Path parent = target.path.getParent();
        if (!parent.toRealPath().equals(parent)) throw new IOException("Export parent changed");
        for (var entry : target.parents.entrySet()) {
            if (!identity(entry.getKey(), true).equals(entry.getValue())) throw new IOException("Export parent changed");
        }
    }
}
