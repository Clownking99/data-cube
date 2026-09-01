package com.datacube.sqleditor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Safe, versioned UTF-8 storage for one SQL script file. */
public final class SqlScriptFileStore {
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    private static final Set<Path> BUSY_TARGETS = ConcurrentHashMap.newKeySet();

    public enum FailureCode {
        INVALID_TARGET, READ, TOO_LARGE, INVALID_UTF8, CHANGED, BUSY, WRITE, PUBLISH, CLEANUP
    }

    public static final class Failure extends IOException {
        private final FailureCode code;
        private final Path temporaryPath;

        private Failure(FailureCode code) {
            this(code, null);
        }

        private Failure(FailureCode code, Path temporaryPath) {
            super("SQL script file store failed: " + code);
            this.code = code;
            this.temporaryPath = temporaryPath;
        }

        public FailureCode code() {
            return code;
        }

        public Path temporaryPath() {
            return temporaryPath;
        }
    }

    public static final class Target {
        private final Path path;
        private final Version version;

        private Target(Path path, Version version) {
            this.path = path;
            this.version = version;
        }

        public Path path() {
            return path;
        }

        public boolean exists() {
            return version != null;
        }
    }

    public record Loaded(Path path, String text, Target target) { }

    @FunctionalInterface
    interface ByteReader {
        byte[] read(Path path) throws IOException;
    }

    @FunctionalInterface
    interface ContentWriter {
        void write(Path path, byte[] bytes) throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path destination, FinalTargetVerifier finalCheck) throws IOException;
    }

    @FunctionalInterface
    interface FinalTargetVerifier {
        void verify() throws IOException;
    }

    @FunctionalInterface
    interface TempCleaner {
        void clean(Path path) throws IOException;
    }

    @FunctionalInterface
    interface TemporaryIdentityReader {
        TemporaryIdentity read(Path path) throws IOException;
    }

    private final ByteReader reader;
    private final ContentWriter writer;
    private final AtomicMover mover;
    private final TempCleaner cleaner;
    private final Consumer<Path> cleanupDiagnostic;
    private final TemporaryIdentityReader temporaryIdentityReader;

    public SqlScriptFileStore() {
        this(Files::readAllBytes, Files::write, (source, destination, finalCheck) -> {
            finalCheck.verify();
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        },
                Files::deleteIfExists, ignored -> { });
    }

    SqlScriptFileStore(ByteReader reader, ContentWriter writer, AtomicMover mover, TempCleaner cleaner,
            Consumer<Path> cleanupDiagnostic) {
        this(reader, writer, mover, cleaner, cleanupDiagnostic, SqlScriptFileStore::temporaryIdentity);
    }

    SqlScriptFileStore(ByteReader reader, ContentWriter writer, AtomicMover mover, TempCleaner cleaner,
            Consumer<Path> cleanupDiagnostic, TemporaryIdentityReader temporaryIdentityReader) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner");
        this.cleanupDiagnostic = Objects.requireNonNull(cleanupDiagnostic, "cleanupDiagnostic");
        this.temporaryIdentityReader = Objects.requireNonNull(temporaryIdentityReader,
                "temporaryIdentityReader");
    }

    public Target capture(Path chosen) throws Failure {
        final Path path;
        try {
            if (chosen == null) throw new IOException();
            Path absolute = chosen.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            Path name = absolute.getFileName();
            if (parent == null || name == null) throw new IOException();
            Path realParent = parent.toRealPath();
            if (!Files.isDirectory(realParent, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            path = realParent.resolve(name).normalize();
            if (!path.getParent().equals(realParent)) throw new IOException();
        } catch (IOException | RuntimeException failure) {
            throw new Failure(FailureCode.INVALID_TARGET);
        }

        try {
            Current current = inspect(path);
            if (!current.valid()) throw new Failure(FailureCode.INVALID_TARGET);
            return new Target(path, current.version());
        } catch (Failure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new Failure(FailureCode.INVALID_TARGET);
        }
    }

    public Loaded load(Path chosen) throws Failure {
        Target target = capture(chosen);
        if (!target.exists()) throw new Failure(FailureCode.READ);

        byte[] bytes;
        try {
            if (target.version.size() > MAX_BYTES) throw new Failure(FailureCode.TOO_LARGE);
            bytes = reader.read(target.path());
        } catch (Failure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new Failure(FailureCode.READ);
        }
        if (bytes == null) throw new Failure(FailureCode.READ);
        if (bytes.length > MAX_BYTES) throw new Failure(FailureCode.TOO_LARGE);
        if (!matches(target)) throw new Failure(FailureCode.CHANGED);

        try {
            return new Loaded(target.path(), decode(bytes), target);
        } catch (CharacterCodingException failure) {
            throw new Failure(FailureCode.INVALID_UTF8);
        }
    }

    public Loaded save(Target expected, String text) throws Failure {
        if (expected == null) throw new Failure(FailureCode.INVALID_TARGET);
        final byte[] bytes;
        try {
            bytes = Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException failure) {
            throw new Failure(FailureCode.WRITE);
        }
        if (bytes.length > MAX_BYTES) throw new Failure(FailureCode.TOO_LARGE);
        if (!BUSY_TARGETS.add(expected.path)) throw new Failure(FailureCode.BUSY);

        Path temporary = null;
        TemporaryIdentity initialTemporaryIdentity = null;
        TemporaryIdentity writtenTemporaryIdentity = null;
        boolean published = false;
        Failure resultFailure = null;
        Loaded result = null;
        try {
            try {
                temporary = Files.createTempFile(expected.path.getParent(), ".datacube-sql-", ".tmp");
                initialTemporaryIdentity = captureTemporaryIdentity(temporary);
                if (initialTemporaryIdentity == null) resultFailure = new Failure(FailureCode.CLEANUP, temporary);
                else writer.write(temporary, bytes);
            } catch (IOException | RuntimeException failure) {
                resultFailure = new Failure(FailureCode.WRITE);
            }
            if (resultFailure == null && !hasTemporaryIdentity(temporary, initialTemporaryIdentity)) {
                resultFailure = new Failure(FailureCode.CLEANUP, temporary);
            }
            if (resultFailure == null) {
                try {
                    writtenTemporaryIdentity = initialTemporaryIdentity;
                    mover.move(temporary, expected.path, () -> {
                        if (!matches(expected)) throw new Failure(FailureCode.CHANGED);
                    });
                    published = true;
                    if (resultFailure == null && !hasTemporaryIdentity(expected.path, writtenTemporaryIdentity)) {
                        resultFailure = new Failure(FailureCode.CHANGED);
                    }
                    if (resultFailure == null) {
                        Target saved = capture(expected.path);
                        if (!hasTemporaryIdentity(saved.path, writtenTemporaryIdentity)) {
                            resultFailure = new Failure(FailureCode.CHANGED);
                        } else {
                            result = new Loaded(saved.path(), text, saved);
                        }
                    }
                } catch (Failure failure) {
                    resultFailure = failure.code() == FailureCode.CHANGED ? failure
                            : new Failure(FailureCode.PUBLISH);
                } catch (IOException | RuntimeException failure) {
                    resultFailure = new Failure(FailureCode.PUBLISH);
                }
            }
        } finally {
            Failure cleanupFailure = cleanup(temporary, initialTemporaryIdentity, published, expected.path);
            BUSY_TARGETS.remove(expected.path);
            if (cleanupFailure != null) throw cleanupFailure;
        }
        if (resultFailure != null) throw resultFailure;
        return result;
    }

    private Failure cleanup(Path temporary, TemporaryIdentity initialIdentity, boolean published,
            Path destination) {
        Path ownedPath = published ? destination : temporary;
        boolean witnessed = initialIdentity != null && initialIdentity.witness() != null;
        if (witnessed && !deleteOwnedWitness(ownedPath, initialIdentity)) {
            return cleanupFailure(temporary);
        }
        if (temporary == null || published) return null;
        if (!witnessed && !hasTemporaryIdentity(temporary, initialIdentity)) {
            return cleanupFailure(temporary);
        }
        try {
            cleaner.clean(temporary);
            return null;
        } catch (IOException | RuntimeException ignored) {
            return cleanupFailure(temporary);
        }
    }

    private Failure cleanupFailure(Path temporary) {
        try {
            cleanupDiagnostic.accept(temporary);
        } catch (RuntimeException ignored) {
            // Diagnostics cannot alter the classified cleanup failure.
        }
        return new Failure(FailureCode.CLEANUP, temporary);
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF;
    }

    private static boolean matches(Target expected) {
        try {
            Current current = inspect(expected.path);
            return current.valid() && Objects.equals(expected.version, current.version());
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static Current inspect(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return Current.INVALID;
            return new Current(true, new Version(attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime(), attributes.creationTime()));
        } catch (java.nio.file.NoSuchFileException absent) {
            return Current.MISSING;
        }
    }

    private TemporaryIdentity captureTemporaryIdentity(Path path) throws IOException {
        TemporaryIdentity identity = temporaryIdentityReader.read(path);
        if (identity == null || identity.fileKey() != null) return identity;
        Path witness = path.resolveSibling(".datacube-sql-owner-" + UUID.randomUUID());
        Files.createLink(witness, path);
        return new TemporaryIdentity(null, identity.created(), witness);
    }

    private static TemporaryIdentity temporaryIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return null;
        return new TemporaryIdentity(attributes.fileKey(), attributes.creationTime(), null);
    }

    private boolean hasTemporaryIdentity(Path path, TemporaryIdentity expected) {
        if (expected == null) return false;
        try {
            if (expected.witness() != null) {
                TemporaryIdentity candidate = temporaryIdentityReader.read(path);
                TemporaryIdentity witness = temporaryIdentityReader.read(expected.witness());
                return candidate != null && witness != null
                        && candidate.fileKey() == null && witness.fileKey() == null
                        && Objects.equals(expected.created(), candidate.created())
                        && Objects.equals(expected.created(), witness.created())
                        && Files.isSameFile(path, expected.witness());
            }
            return expected.equals(temporaryIdentityReader.read(path));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private boolean deleteOwnedWitness(Path ownedPath, TemporaryIdentity identity) {
        if (identity == null || identity.witness() == null) return true;
        if (!hasTemporaryIdentity(ownedPath, identity)) return false;
        try {
            return Files.deleteIfExists(identity.witness());
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private record Version(Object fileKey, long size, FileTime modified, FileTime created) { }

    record TemporaryIdentity(Object fileKey, FileTime created, Path witness) { }

    private record Current(boolean valid, Version version) {
        private static final Current MISSING = new Current(true, null);
        private static final Current INVALID = new Current(false, null);
    }
}
