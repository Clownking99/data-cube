package com.datacube.sqleditor;

import com.datacube.io.BoundedRegularFileReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Safe, versioned UTF-8 storage for one SQL script file. */
public final class SqlScriptFileStore {
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    private static final Set<Path> BUSY_TARGETS = ConcurrentHashMap.newKeySet();

    public enum FailureCode {
        INVALID_TARGET, READ, TOO_LARGE, INVALID_UTF8, CHANGED, BUSY, WRITE, PUBLISH, CLEANUP,
        RECOVERY
    }

    public static final class Failure extends IOException {
        private final FailureCode code;
        private final Path temporaryPath;
        private final Path recoveryPath;
        private final List<Path> temporaryPaths;
        private final List<Path> recoveryPaths;
        private final List<Path> retainedPaths;

        private Failure(FailureCode code) {
            this(code, List.of(), List.of());
        }

        private Failure(FailureCode code, Path temporaryPath) {
            this(code, temporaryPath, null);
        }

        private Failure(FailureCode code, Path temporaryPath, Path recoveryPath) {
            this(code, temporaryPath == null ? List.of() : List.of(temporaryPath),
                    recoveryPath == null ? List.of() : List.of(recoveryPath));
        }

        private Failure(FailureCode code, List<Path> temporaryPaths,
                List<Path> recoveryPaths) {
            super("SQL script file store failed: " + code);
            this.code = code;
            this.temporaryPaths = immutableDistinct(temporaryPaths);
            this.recoveryPaths = immutableDistinct(recoveryPaths);
            this.temporaryPath = this.temporaryPaths.isEmpty()
                    ? null : this.temporaryPaths.getFirst();
            this.recoveryPath = this.recoveryPaths.isEmpty()
                    ? null : this.recoveryPaths.getFirst();
            LinkedHashSet<Path> retained = new LinkedHashSet<>(this.temporaryPaths);
            retained.addAll(this.recoveryPaths);
            this.retainedPaths = List.copyOf(retained);
        }

        private static List<Path> immutableDistinct(List<Path> paths) {
            LinkedHashSet<Path> distinct = new LinkedHashSet<>();
            for (Path path : paths) if (path != null) distinct.add(path);
            return List.copyOf(distinct);
        }

        public FailureCode code() {
            return code;
        }

        public Path temporaryPath() {
            return temporaryPath;
        }

        /** A retained file which may be needed for manual recovery, if any. */
        public Path recoveryPath() {
            return recoveryPath;
        }

        /** Every identity-proven directory entry retained by the failed transaction. */
        public List<Path> retainedPaths() {
            return retainedPaths;
        }
    }

    public static final class Target {
        private final Path path;
        private final Version version;
        private final byte[] fingerprint;

        private Target(Path path, Version version, byte[] fingerprint) {
            this.path = path;
            this.version = version;
            this.fingerprint = fingerprint;
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

    /** Test seam and default no-replace move boundary. */
    @FunctionalInterface
    interface NoReplaceMover {
        void move(Path source, Path destination, FinalTargetVerifier finalCheck) throws IOException;
    }

    @FunctionalInterface
    interface FinalTargetVerifier {
        void verify() throws IOException;
    }

    @FunctionalInterface
    interface MovePrimitive {
        void move(Path source, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface LinkPrimitive {
        void create(Path link, Path existing) throws IOException;
    }

    @FunctionalInterface
    interface TempCleaner {
        void clean(Path path) throws IOException;
    }

    @FunctionalInterface
    interface TemporaryIdentityReader {
        TemporaryIdentity read(Path path) throws IOException;
    }

    @FunctionalInterface
    interface UniquePathFactory {
        Path create(Path parent, String prefix);
    }

    private final ByteReader reader;
    private final ContentWriter writer;
    private final NoReplaceMover mover;
    private final TempCleaner cleaner;
    private final Consumer<Path> cleanupDiagnostic;
    private final TemporaryIdentityReader temporaryIdentityReader;
    private final Runnable beforePublish;
    private final UniquePathFactory uniquePathFactory;
    private final Predicate<Path> ownedLinkDeleter;

    public SqlScriptFileStore() {
        this(SqlScriptFileStore::readBoundedRegularFile, Files::write,
                SqlScriptFileStore::moveNoReplace, Files::deleteIfExists, ignored -> { });
    }

    SqlScriptFileStore(ByteReader reader, ContentWriter writer, NoReplaceMover mover, TempCleaner cleaner,
            Consumer<Path> cleanupDiagnostic) {
        this(reader, writer, mover, cleaner, cleanupDiagnostic, SqlScriptFileStore::temporaryIdentity);
    }

    SqlScriptFileStore(ByteReader reader, ContentWriter writer, NoReplaceMover mover, TempCleaner cleaner,
            Consumer<Path> cleanupDiagnostic, TemporaryIdentityReader temporaryIdentityReader) {
        this(reader, writer, mover, cleaner, cleanupDiagnostic, temporaryIdentityReader, () -> { });
    }

    SqlScriptFileStore(ByteReader reader, ContentWriter writer, NoReplaceMover mover, TempCleaner cleaner,
            Consumer<Path> cleanupDiagnostic, TemporaryIdentityReader temporaryIdentityReader,
            Runnable beforePublish) {
        this(reader, writer, mover, cleaner, cleanupDiagnostic, temporaryIdentityReader,
                beforePublish, (parent, prefix) -> parent.resolve(prefix + UUID.randomUUID() + ".tmp"));
    }

    SqlScriptFileStore(ByteReader reader, ContentWriter writer, NoReplaceMover mover, TempCleaner cleaner,
            Consumer<Path> cleanupDiagnostic, TemporaryIdentityReader temporaryIdentityReader,
            Runnable beforePublish, UniquePathFactory uniquePathFactory) {
        this(reader, writer, mover, cleaner, cleanupDiagnostic, temporaryIdentityReader,
                beforePublish, uniquePathFactory, SqlScriptFileStore::deleteOwnedLinkOnClose);
    }

    SqlScriptFileStore(ByteReader reader, ContentWriter writer, NoReplaceMover mover, TempCleaner cleaner,
            Consumer<Path> cleanupDiagnostic, TemporaryIdentityReader temporaryIdentityReader,
            Runnable beforePublish, UniquePathFactory uniquePathFactory,
            Predicate<Path> ownedLinkDeleter) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.cleaner = Objects.requireNonNull(cleaner, "cleaner");
        this.cleanupDiagnostic = Objects.requireNonNull(cleanupDiagnostic, "cleanupDiagnostic");
        this.temporaryIdentityReader = Objects.requireNonNull(temporaryIdentityReader,
                "temporaryIdentityReader");
        this.beforePublish = Objects.requireNonNull(beforePublish, "beforePublish");
        this.uniquePathFactory = Objects.requireNonNull(uniquePathFactory, "uniquePathFactory");
        this.ownedLinkDeleter = Objects.requireNonNull(ownedLinkDeleter, "ownedLinkDeleter");
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
            byte[] fingerprint = current.version() == null || current.version().size() > MAX_BYTES
                    ? null : fingerprint(path);
            return new Target(path, current.version(), fingerprint);
        } catch (Failure failure) {
            throw failure;
        } catch (BoundedRegularFileReader.ChangedDuringReadException failure) {
            throw new Failure(FailureCode.CHANGED);
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
        } catch (BoundedRegularFileReader.ChangedDuringReadException failure) {
            throw new Failure(FailureCode.CHANGED);
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
            bytes = strictUtf8(Objects.requireNonNull(text, "text"));
        } catch (CharacterCodingException | RuntimeException failure) {
            throw new Failure(FailureCode.WRITE);
        }
        if (bytes.length > MAX_BYTES) throw new Failure(FailureCode.TOO_LARGE);
        if (!BUSY_TARGETS.add(expected.path)) throw new Failure(FailureCode.BUSY);

        try {
            return new SaveTransaction(expected, text, bytes).execute();
        } finally {
            BUSY_TARGETS.remove(expected.path);
        }
    }

    private final class SaveTransaction {
        private final Target expected;
        private final String text;
        private final byte[] bytes;
        private Path temporary;
        private TemporaryIdentity temporaryIdentity;
        private TemporaryIdentity temporaryWitnessIdentity;
        private Path temporaryWitnessGuard;
        private boolean temporaryWitnessGuardCreated;
        private Path backup;
        private Path backupWitness;
        private TemporaryIdentity backupWitnessIdentity;
        private boolean backupWitnessCreated;
        private Path restoredTargetGuard;
        private Path restoredTargetGuardWitness;
        private TemporaryIdentity restoredTargetGuardIdentity;
        private TemporaryIdentity restoredTargetGuardWitnessIdentity;
        private StableFileIdentity restoredTargetGuardStableIdentity;
        private StableFileIdentity restoredTargetGuardWitnessStableIdentity;
        private boolean restoredTargetGuardCreated;
        private boolean restoredTargetGuardWitnessCreated;
        private Path rollback;
        private TemporaryIdentity rollbackIdentity;
        private boolean published;
        private boolean backupMoved;
        private Loaded result;

        private SaveTransaction(Target expected, String text, byte[] bytes) {
            this.expected = expected;
            this.text = text;
            this.bytes = bytes;
        }

        private Loaded execute() throws Failure {
            Failure failure = prepareTemporary();
            if (failure == null && expected.exists()) failure = displaceExpected();
            if (failure == null) failure = publishTemporary();
            if (failure == null) failure = completeSuccess();
            failure = finishBackupArtifacts(failure);
            failure = finishTemporary(failure);
            if (failure != null) throw withVerifiedArtifacts(failure);
            return result;
        }

        private Failure withVerifiedArtifacts(Failure failure) {
            LinkedHashSet<Path> temporaryArtifacts = new LinkedHashSet<>();
            for (Path path : failure.temporaryPaths) {
                addVerifiedTemporaryArtifact(temporaryArtifacts, path);
            }
            addVerifiedTemporaryArtifact(temporaryArtifacts, temporary);
            addVerifiedTemporaryArtifact(temporaryArtifacts, rollback);
            if (temporaryIdentity != null) {
                addVerifiedTemporaryArtifact(temporaryArtifacts,
                        temporaryIdentity.witness());
            }
            if (temporaryWitnessIdentity != null) {
                addVerifiedTemporaryArtifact(temporaryArtifacts,
                        temporaryWitnessIdentity.witness());
            }
            addVerifiedTemporaryArtifact(temporaryArtifacts, temporaryWitnessGuard);

            LinkedHashSet<Path> recoveryArtifacts = new LinkedHashSet<>();
            for (Path path : failure.recoveryPaths) {
                addVerifiedRecoveryArtifact(recoveryArtifacts, path);
            }
            addVerifiedRecoveryArtifact(recoveryArtifacts, backup);
            addVerifiedRecoveryArtifact(recoveryArtifacts, backupWitness);
            addVerifiedRecoveryArtifact(recoveryArtifacts, restoredTargetGuard);
            addVerifiedRecoveryArtifact(recoveryArtifacts, restoredTargetGuardWitness);
            return new Failure(failure.code(), List.copyOf(temporaryArtifacts),
                    List.copyOf(recoveryArtifacts));
        }

        private void addVerifiedTemporaryArtifact(Set<Path> artifacts, Path path) {
            if (path == null) return;
            boolean verified;
            if (path.equals(temporary)) {
                verified = hasCurrentTemporaryIdentity(path);
            } else if (path.equals(rollback)) {
                verified = hasCurrentRelocatedTemporaryIdentity(path) || hasRollbackIdentity();
            } else if (temporaryIdentity != null
                    && path.equals(temporaryIdentity.witness())) {
                verified = hasTemporaryWitnessIdentity()
                        && (hasUncapturedTemporaryWitnessGuard()
                        || samePublishedBytes(path, bytes));
            } else {
                verified = path.equals(temporaryWitnessGuard)
                        && hasTemporaryWitnessGuardIdentity()
                        && (hasUncapturedTemporaryWitnessGuard()
                        || samePublishedBytes(path, bytes));
            }
            if (verified) artifacts.add(path);
        }

        private void addVerifiedRecoveryArtifact(Set<Path> artifacts, Path path) {
            if (path == null) return;
            boolean verified;
            if (path.equals(backup)) {
                verified = hasBackupIdentityAt(path);
            } else if (path.equals(backupWitness)) {
                verified = hasBackupWitnessPathIdentity();
            } else if (path.equals(restoredTargetGuard)) {
                verified = hasRestoredTargetGuardPathIdentity();
            } else if (path.equals(restoredTargetGuardWitness)) {
                verified = hasRestoredTargetGuardWitnessPathIdentity();
            } else {
                verified = path.equals(expected.path) && hasBackupIdentityAt(path);
            }
            if (verified) artifacts.add(path);
        }

        private Failure prepareTemporary() {
            try {
                temporary = Files.createTempFile(expected.path.getParent(), ".datacube-sql-", ".tmp");
                temporaryIdentity = captureTemporaryIdentity(temporary);
                if (temporaryIdentity != null && temporaryIdentity.witness() != null) {
                    temporaryWitnessIdentity = captureTemporaryWitnessIdentity(
                            temporaryIdentity.witness());
                }
                if (temporaryIdentity == null) return new Failure(FailureCode.CLEANUP, temporary);
                writer.write(temporary, bytes);
                if (!hasCurrentTemporaryIdentity(temporary)) {
                    return new Failure(FailureCode.CLEANUP, temporary);
                }
                if (!samePublishedBytes(temporary, bytes)) return new Failure(FailureCode.WRITE);
                return null;
            } catch (IOException | RuntimeException failure) {
                return new Failure(FailureCode.WRITE);
            }
        }

        private TemporaryIdentity captureTemporaryWitnessIdentity(Path witness)
                throws IOException {
            temporaryWitnessGuard = vacantPath(witness.getParent(),
                    ".datacube-sql-owner-guard-");
            Files.createLink(temporaryWitnessGuard, witness);
            temporaryWitnessGuardCreated = true;
            TemporaryIdentity identity = temporaryIdentityReader.read(temporaryWitnessGuard);
            if (identity == null) throw new IOException("temporary witness guard is not regular");
            return new TemporaryIdentity(identity.fileKey(), identity.created(),
                    temporaryWitnessGuard);
        }

        private Failure displaceExpected() {
            try {
                if (!matches(expected)) return new Failure(FailureCode.CHANGED);
                backup = vacantPath(expected.path.getParent(), ".datacube-sql-backup-");
                backupWitness = vacantPath(expected.path.getParent(),
                        ".datacube-sql-backup-owner-");
                Files.createLink(backupWitness, expected.path);
                backupWitnessCreated = true;
                backupWitnessIdentity = temporaryIdentityReader.read(backupWitness);
                if (!backupWitnessMatches()) return new Failure(FailureCode.CHANGED);
            } catch (FileAlreadyExistsException collision) {
                return new Failure(FailureCode.PUBLISH);
            } catch (IOException | RuntimeException failure) {
                return new Failure(FailureCode.PUBLISH);
            }

            Failure moveFailure = null;
            try {
                mover.move(expected.path, backup, () -> {
                    if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                        throw new FileAlreadyExistsException(backup.toString());
                    }
                    if (!matches(expected) || !backupWitnessMatches()) {
                        throw new Failure(FailureCode.CHANGED);
                    }
                });
            } catch (Failure failure) {
                moveFailure = failure;
            } catch (IOException | RuntimeException failure) {
                moveFailure = new Failure(FailureCode.PUBLISH);
            }

            boolean targetAbsent = !Files.exists(expected.path, LinkOption.NOFOLLOW_LINKS);
            boolean ownedBackup = ownsBackup();
            backupMoved = targetAbsent && ownedBackup;
            if (moveFailure != null) {
                if (backupMoved) {
                    Failure restored = restoreBackup(moveFailure.code());
                    return restored == null ? moveFailure : restored;
                }
                if (!sameFile(expected.path, backupWitness)) {
                    return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
                }
                return moveFailure;
            }
            if (backupMatches() && targetAbsent) {
                backupMoved = true;
                return null;
            }
            if (ownedBackup && targetAbsent) {
                Failure restored = restoreBackup(FailureCode.CHANGED);
                return restored == null ? new Failure(FailureCode.CHANGED) : restored;
            }
            if (sameFile(expected.path, backupWitness)) return new Failure(FailureCode.CHANGED);
            return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
        }

        private Failure publishTemporary() {
            try {
                beforePublish.run();
            } catch (RuntimeException failure) {
                return recoverBeforePublication(FailureCode.PUBLISH);
            }

            Failure moveFailure = null;
            try {
                mover.move(temporary, expected.path, () -> {
                    if (Files.exists(expected.path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new Failure(FailureCode.CHANGED);
                    }
                    if (expected.exists() && !backupMatches()) {
                        throw new Failure(FailureCode.CHANGED);
                    }
                    if (!expected.exists() && !matches(expected)) {
                        throw new Failure(FailureCode.CHANGED);
                    }
                    if (!hasCurrentTemporaryIdentity(temporary)
                            || !samePublishedBytes(temporary, bytes)) {
                        throw new Failure(FailureCode.WRITE);
                    }
                });
            } catch (Failure failure) {
                moveFailure = failure;
            } catch (IOException | RuntimeException failure) {
                moveFailure = new Failure(FailureCode.PUBLISH);
            }

            refreshTemporaryWitnessAfterMove();
            published = ownsPublishedTarget();
            if (moveFailure != null) {
                if (published) return rollbackPublished(moveFailure.code());
                return recoverBeforePublication(moveFailure.code());
            }
            if (!published) {
                return expected.exists()
                        ? new Failure(FailureCode.RECOVERY, null, recoveryCandidate())
                        : new Failure(FailureCode.CHANGED);
            }
            if (expected.exists() && !backupMatches()) {
                return rollbackPublished(FailureCode.CHANGED);
            }

            try {
                Target saved = capture(expected.path);
                if (!ownsPublishedTarget()) return expected.exists()
                        ? new Failure(FailureCode.RECOVERY, null, recoveryCandidate())
                        : new Failure(FailureCode.CHANGED);
                result = new Loaded(saved.path(), text, saved);
                return null;
            } catch (Failure failure) {
                return expected.exists() ? rollbackPublished(FailureCode.CHANGED)
                        : new Failure(FailureCode.CHANGED);
            }
        }

        private Failure completeSuccess() {
            if (expected.exists()) {
                if (!backupMatches() || !ownsPublishedTarget()) {
                    return rollbackPublished(FailureCode.CHANGED);
                }
                boolean backupDeleteReported = deleteOwnedLink(backup);
                if (ownsBackup()) {
                    return new Failure(FailureCode.RECOVERY, null, backup);
                }
                if (!backupDeleteReported
                        || Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    return new Failure(FailureCode.RECOVERY, null,
                            hasBackupWitnessIdentity() ? backupWitness : null);
                }
                backupMoved = false;
                if (!deleteBackupWitnessAfterBackupRemoval()) {
                    return new Failure(FailureCode.RECOVERY, null,
                            hasBackupWitnessIdentity() ? backupWitness : null);
                }
            }
            return null;
        }

        private Failure recoverBeforePublication(FailureCode original) {
            if (!expected.exists()) {
                return Files.exists(expected.path, LinkOption.NOFOLLOW_LINKS)
                        ? new Failure(FailureCode.CHANGED) : new Failure(original);
            }
            if (!backupMoved && !Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                return new Failure(original);
            }
            Failure restored = restoreBackup(original);
            return restored == null ? new Failure(original) : restored;
        }

        private Failure restoreBackup(FailureCode restoredCode) {
            if (!ownsBackup()) {
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            if (Files.exists(expected.path, LinkOption.NOFOLLOW_LINKS)) {
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            try {
                mover.move(backup, expected.path, () -> {
                    if (Files.exists(expected.path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new FileAlreadyExistsException(expected.path.toString());
                    }
                    if (!ownsBackup()) throw new IOException();
                });
            } catch (IOException | RuntimeException failure) {
                if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                        && sameFile(expected.path, backupWitness)) {
                    return finishRestoredBackup(restoredCode);
                }
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    || !sameFile(expected.path, backupWitness)) {
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            return finishRestoredBackup(restoredCode);
        }

        private Failure finishRestoredBackup(FailureCode restoredCode) {
            if (!establishRestoredTargetGuard()) {
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            backupMoved = false;
            boolean witnessDeleted = deleteBackupWitness(expected.path);
            if (!hasRestoredTargetIdentity()) {
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            if (!witnessDeleted || restoredCode == FailureCode.RECOVERY) {
                return new Failure(FailureCode.RECOVERY, null, expected.path);
            }
            if (!deleteRestoredTargetGuard()) {
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            return null;
        }

        private boolean establishRestoredTargetGuard() {
            if (!hasBackupWitnessIdentity() || !sameFile(expected.path, backupWitness)) return false;
            try {
                restoredTargetGuard = vacantPath(expected.path.getParent(),
                        ".datacube-sql-restored-owner-");
                Files.createLink(restoredTargetGuard, expected.path);
                restoredTargetGuardCreated = true;
                TemporaryIdentity guardIdentity = temporaryIdentityReader.read(restoredTargetGuard);
                if (guardIdentity == null || !sameFile(restoredTargetGuard, expected.path)
                        || !sameFile(restoredTargetGuard, backupWitness)) throw new IOException();
                restoredTargetGuardIdentity = guardIdentity;
                restoredTargetGuardStableIdentity = stableFileIdentity(restoredTargetGuard);
                restoredTargetGuardWitness = vacantPath(expected.path.getParent(),
                        ".datacube-sql-restored-owner-guard-");
                Files.createLink(restoredTargetGuardWitness, restoredTargetGuard);
                restoredTargetGuardWitnessCreated = true;
                TemporaryIdentity guardWitnessIdentity = temporaryIdentityReader.read(
                        restoredTargetGuardWitness);
                if (guardWitnessIdentity == null
                        || !sameFile(restoredTargetGuardWitness, restoredTargetGuard)) {
                    throw new IOException();
                }
                restoredTargetGuardWitnessIdentity = guardWitnessIdentity;
                restoredTargetGuardWitnessStableIdentity = stableFileIdentity(
                        restoredTargetGuardWitness);
                return hasRestoredTargetGuardPathIdentity()
                        && hasRestoredTargetGuardWitnessPathIdentity()
                        && hasRestoredTargetIdentity();
            } catch (IOException | RuntimeException failure) {
                reconcileRestoredTargetGuardAliases();
                return false;
            }
        }

        private Failure rollbackPublished(FailureCode restoredCode) {
            if (!ownsPublishedTarget()) {
                return expected.exists()
                        ? new Failure(FailureCode.RECOVERY, null, recoveryCandidate())
                        : new Failure(FailureCode.CHANGED);
            }
            try {
                rollback = vacantPath(expected.path.getParent(), ".datacube-sql-rollback-");
                mover.move(expected.path, rollback, () -> {
                    if (Files.exists(rollback, LinkOption.NOFOLLOW_LINKS)) {
                        throw new FileAlreadyExistsException(rollback.toString());
                    }
                    if (!ownsPublishedTarget()) throw new Failure(FailureCode.CHANGED);
                });
            } catch (IOException | RuntimeException failure) {
                // Reconcile below: providers may report failure after completing the move.
            }
            refreshTemporaryWitnessAfterMove();
            boolean rollbackOwned = hasCurrentRelocatedTemporaryIdentity(rollback);
            if (rollbackOwned) rememberRollbackIdentity();
            boolean rollbackMoved = rollbackOwned && samePublishedBytes(rollback, bytes)
                    && !Files.exists(expected.path, LinkOption.NOFOLLOW_LINKS);
            if (!rollbackMoved) {
                if (rollbackOwned && ownsPublishedTarget()) {
                    deleteOwnedLink(rollback);
                }
                Path retainedRollback = retainedRollbackPath();
                if (retainedRollback == null) rollback = null;
                return new Failure(FailureCode.RECOVERY, retainedRollback, recoveryCandidate());
            }
            published = false;

            Failure restoreFailure = restoreBackup(restoredCode);
            if (restoreFailure != null) return withRetainedRollback(restoreFailure);
            if (!hasCurrentRelocatedTemporaryIdentity(rollback)) {
                return new Failure(FailureCode.RECOVERY, null, recoveryCandidate());
            }
            if (!samePublishedBytes(rollback, bytes)) {
                return new Failure(FailureCode.RECOVERY, retainedRollbackPath(),
                        recoveryCandidate());
            }
            boolean rollbackCleanupCompleted = false;
            try {
                cleaner.clean(rollback);
                rollbackCleanupCompleted = !Files.exists(rollback,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException failure) {
                // Reconcile the rollback path and witness below.
            }
            Path retainedRollback = retainedRollbackPath();
            if (retainedRollback == null) rollback = null;
            boolean retainedRollbackNeedsWitness = retainedRollback != null
                    && temporaryIdentity != null && temporaryIdentity.witness() != null;
            boolean witnessFinished = retainedRollbackNeedsWitness
                    || (retainedRollback != null
                    ? deleteTemporaryWitness()
                    : hasOwnedOriginalTemporary()
                    || rollbackCleanupCompleted && deleteOrphanedTemporaryWitness());
            if (!witnessFinished) {
                Path retainedTemporary = retainedRollback != null
                        ? retainedRollback : retainedTemporaryPath();
                return new Failure(FailureCode.RECOVERY, retainedTemporary,
                        recoveryCandidate());
            }
            if (retainedRollback != null) {
                return new Failure(FailureCode.RECOVERY, retainedRollback,
                        recoveryCandidate());
            }
            return new Failure(restoredCode);
        }

        private Failure withRetainedRollback(Failure failure) {
            return new Failure(failure.code(), retainedRollbackPath(), failure.recoveryPath());
        }

        private Path retainedRollbackPath() {
            return hasCurrentRelocatedTemporaryIdentity(rollback)
                    || hasRollbackIdentity() ? rollback : null;
        }

        private void rememberRollbackIdentity() {
            try {
                rollbackIdentity = temporaryIdentityReader.read(rollback);
            } catch (IOException | RuntimeException failure) {
                rollbackIdentity = null;
            }
        }

        private boolean hasRollbackIdentity() {
            if (rollback == null || rollbackIdentity == null
                    || !Files.isRegularFile(rollback, LinkOption.NOFOLLOW_LINKS)) return false;
            if (rollbackIdentity.fileKey() == null) {
                return hasCurrentRelocatedTemporaryIdentity(rollback);
            }
            try {
                return matchesIdentity(rollbackIdentity,
                        temporaryIdentityReader.read(rollback));
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }

        private boolean hasOwnedOriginalTemporary() {
            return temporary != null && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                    && hasCurrentTemporaryIdentity(temporary);
        }

        private Failure finishTemporary(Failure primary) {
            Failure cleanupFailure = null;
            boolean uncapturedGuard = hasUncapturedTemporaryWitnessGuard();
            boolean partialLinksFinished = !uncapturedGuard
                    || reconcileUncapturedTemporaryWitnessLinks();
            boolean retainedUncapturedGuard = uncapturedGuard
                    && hasTemporaryWitnessGuardIdentity();
            if (!partialLinksFinished) {
                cleanupFailure = cleanupFailure(retainedTemporaryPath());
            }
            boolean originalTemporaryCleaned = false;
            if (!retainedUncapturedGuard && temporary != null
                    && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                if (!hasCurrentTemporaryIdentity(temporary)) {
                    cleanupFailure = cleanupFailure(temporary);
                } else {
                    try {
                        cleaner.clean(temporary);
                        originalTemporaryCleaned = !Files.exists(temporary,
                                LinkOption.NOFOLLOW_LINKS);
                    } catch (IOException | RuntimeException failure) {
                        // Reconcile below: cleanup may have removed the owned path before failing.
                    }
                    if (hasCurrentTemporaryIdentity(temporary)) {
                        cleanupFailure = cleanupFailure(temporary);
                    }
                }
            }
            boolean retainedRollbackNeedsWitness = retainedRollbackPath() != null
                    && temporaryIdentity != null && temporaryIdentity.witness() != null;
            if (!retainedUncapturedGuard && !retainedRollbackNeedsWitness
                    && !deleteTemporaryWitness()
                    && !(originalTemporaryCleaned && deleteOrphanedTemporaryWitness())) {
                if (cleanupFailure == null) {
                    cleanupFailure = cleanupFailure(retainedTemporaryPath());
                }
            }
            if (cleanupFailure != null && primary != null
                    && (primary.code() == FailureCode.CHANGED
                    || primary.code() == FailureCode.RECOVERY)) {
                Path retainedPrimary = primary.temporaryPath() != null
                        ? primary.temporaryPath() : cleanupFailure.temporaryPath();
                return new Failure(primary.code(), retainedPrimary,
                        primary.recoveryPath());
            }
            return cleanupFailure == null ? primary : cleanupFailure;
        }

        private boolean reconcileUncapturedTemporaryWitnessLinks() {
            if (!hasUncapturedTemporaryWitnessGuard()) return true;
            boolean guardDeletionReported = false;
            if (hasTemporaryWitnessGuardIdentity()) {
                guardDeletionReported = deleteOwnedLink(temporaryWitnessGuard);
            }
            if (hasTemporaryWitnessGuardIdentity()) return false;

            boolean guardAbsent = !Files.exists(temporaryWitnessGuard,
                    LinkOption.NOFOLLOW_LINKS);
            if (guardAbsent) {
                temporaryWitnessGuard = null;
                temporaryWitnessGuardCreated = false;
            }
            boolean witnessFinished = deleteUncapturedTemporaryWitness();
            return guardDeletionReported && guardAbsent && witnessFinished;
        }

        private boolean deleteUncapturedTemporaryWitness() {
            if (temporaryIdentity == null || temporaryIdentity.witness() == null) return true;
            if (!hasLiveTemporaryWitnessAnchor()) return false;
            Path witness = temporaryIdentity.witness();
            boolean deletionReported = deleteOwnedLink(witness);
            if (hasLiveTemporaryWitnessAnchor()) return false;
            boolean witnessAbsent = !Files.exists(witness, LinkOption.NOFOLLOW_LINKS);
            temporaryIdentity = new TemporaryIdentity(temporaryIdentity.fileKey(),
                    temporaryIdentity.created(), null);
            return deletionReported && witnessAbsent;
        }

        private boolean deleteOrphanedTemporaryWitness() {
            if (temporaryIdentity == null || temporaryIdentity.witness() == null) return true;
            return deleteTemporaryWitnessAndGuard();
        }

        private Failure finishBackupArtifacts(Failure primary) {
            if (primary != null && primary.code() == FailureCode.RECOVERY) {
                Path verifiedRecovery = primary.recoveryPath() != null
                        && isVerifiedRecoveryPath(primary.recoveryPath())
                        ? primary.recoveryPath() : recoveryCandidate();
                primary = new Failure(primary.code(), primary.temporaryPath(), verifiedRecovery);
                if (verifiedRecovery != null) return primary;
            }
            if (ownsBackup()) {
                Path recovery = recoveryCandidate();
                return primary == null || primary.code() != FailureCode.RECOVERY
                        || !Objects.equals(primary.recoveryPath(), recovery)
                        ? new Failure(FailureCode.RECOVERY, null, recovery) : primary;
            }
            if (retainedRollbackPath() != null) {
                return primary == null || primary.code() != FailureCode.RECOVERY
                        ? new Failure(FailureCode.RECOVERY, null, rollback) : primary;
            }
            if (hasBackupWitnessPathIdentity() && !deleteBackupWitness(expected.path)) {
                return new Failure(FailureCode.RECOVERY, null,
                        hasBackupWitnessPathIdentity() ? backupWitness : null);
            }
            return primary;
        }

        private boolean isVerifiedRecoveryPath(Path path) {
            if (path.equals(backup)) return ownsBackup();
            if (path.equals(backupWitness)) return hasBackupWitnessPathIdentity();
            if (path.equals(restoredTargetGuard)) return hasRestoredTargetGuardPathIdentity();
            if (path.equals(restoredTargetGuardWitness)) {
                return hasRestoredTargetGuardWitnessPathIdentity();
            }
            if (path.equals(rollback)) return retainedRollbackPath() != null;
            return path.equals(expected.path) && hasBackupIdentityAt(expected.path);
        }

        private boolean ownsPublishedTarget() {
            return hasCurrentRelocatedTemporaryIdentity(expected.path)
                    && samePublishedBytes(expected.path, bytes);
        }

        private boolean backupWitnessMatches() {
            return hasBackupWitnessIdentity() && matchesAtPath(backupWitness, expected)
                    && sameFile(backupWitness, expected.path);
        }

        private boolean backupMatches() {
            return backup != null && matchesAtPath(backup, expected)
                    && hasBackupWitnessIdentity() && matchesAtPath(backupWitness, expected)
                    && sameFile(backup, backupWitness);
        }

        private boolean ownsBackup() {
            return backup != null && hasBackupWitnessIdentity()
                    && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    && sameFile(backup, backupWitness);
        }

        private boolean hasBackupWitnessIdentity() {
            if (backupWitness == null || backupWitnessIdentity == null
                    || !Files.isRegularFile(backupWitness, LinkOption.NOFOLLOW_LINKS)) return false;
            try {
                return matchesIdentity(backupWitnessIdentity,
                        temporaryIdentityReader.read(backupWitness));
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }

        private boolean hasBackupWitnessPathIdentity() {
            if (backupWitness == null || !backupWitnessCreated
                    || !Files.isRegularFile(backupWitness, LinkOption.NOFOLLOW_LINKS)) return false;
            return hasBackupWitnessIdentity() || matchesAtPath(backupWitness, expected);
        }

        private boolean hasBackupIdentityAt(Path path) {
            if (path == null || backupWitnessIdentity == null
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
            if (backupWitnessIdentity.fileKey() == null) {
                if (path.equals(expected.path) && hasRestoredTargetIdentity()) return true;
                return hasBackupWitnessIdentity() && sameFile(path, backupWitness);
            }
            try {
                boolean matchesStored = matchesIdentity(backupWitnessIdentity,
                        temporaryIdentityReader.read(path));
                return matchesStored;
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }

        private boolean hasRestoredTargetGuardIdentity() {
            return hasRestoredTargetGuardPathIdentity()
                    || hasRestoredTargetGuardWitnessPathIdentity();
        }

        private boolean hasRestoredTargetGuardPathIdentity() {
            return hasDirectRestoredAliasIdentity(restoredTargetGuard,
                    restoredTargetGuardIdentity)
                    || hasRestoredGuardPairIdentity();
        }

        private boolean hasRestoredTargetGuardWitnessPathIdentity() {
            return hasDirectRestoredAliasIdentity(restoredTargetGuardWitness,
                    restoredTargetGuardWitnessIdentity)
                    || hasRestoredGuardPairIdentity();
        }

        private boolean hasDirectRestoredAliasIdentity(Path path, TemporaryIdentity identity) {
            if (!wasRestoredAliasCreated(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
            try {
                if (identity != null && identity.fileKey() != null) {
                    TemporaryIdentity current = temporaryIdentityReader.read(path);
                    if (matchesIdentity(identity, current)) return true;
                }
                StableFileIdentity stableIdentity = path.equals(restoredTargetGuard)
                        ? restoredTargetGuardStableIdentity
                        : restoredTargetGuardWitnessStableIdentity;
                if (matchesStableFileIdentity(path, stableIdentity)) return true;
                return hasTrustedLiveBackupAnchor(path);
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }

        private boolean hasRestoredGuardPairIdentity() {
            return restoredTargetGuardCreated
                    && restoredTargetGuardWitnessCreated
                    && Files.isRegularFile(restoredTargetGuard, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(restoredTargetGuardWitness,
                    LinkOption.NOFOLLOW_LINKS)
                    && sameFile(restoredTargetGuard, restoredTargetGuardWitness)
                    && (hasDirectRestoredAliasIdentity(restoredTargetGuard,
                    restoredTargetGuardIdentity)
                    || hasDirectRestoredAliasIdentity(restoredTargetGuardWitness,
                    restoredTargetGuardWitnessIdentity));
        }

        private boolean wasRestoredAliasCreated(Path path) {
            return path != null && (path.equals(restoredTargetGuard)
                    ? restoredTargetGuardCreated
                    : path.equals(restoredTargetGuardWitness)
                    && restoredTargetGuardWitnessCreated);
        }

        private boolean hasTrustedLiveBackupAnchor(Path path) {
            if (path == null) return false;
            if (hasBackupWitnessIdentity() && sameFile(path, backupWitness)) return true;
            if (ownsBackup() && sameFile(path, backup)) return true;
            return matches(expected) && sameFile(path, expected.path);
        }

        private boolean hasRestoredTargetIdentity() {
            return (hasRestoredTargetGuardPathIdentity()
                    && sameFile(expected.path, restoredTargetGuard)
                    || hasRestoredTargetGuardWitnessPathIdentity()
                    && sameFile(expected.path, restoredTargetGuardWitness));
        }

        private boolean deleteRestoredTargetGuard() {
            if (!hasRestoredTargetIdentity()) return false;
            return reconcileRestoredTargetGuardAliases();
        }

        private boolean reconcileRestoredTargetGuardAliases() {
            boolean witnessFinished = reconcileRestoredTargetGuardWitness();
            boolean guardFinished = reconcileRestoredTargetGuard();
            return witnessFinished && guardFinished;
        }

        private boolean reconcileRestoredTargetGuardWitness() {
            if (restoredTargetGuardWitness == null) return true;
            if (!hasRestoredTargetGuardWitnessPathIdentity()) {
                return !Files.exists(restoredTargetGuardWitness, LinkOption.NOFOLLOW_LINKS);
            }
            boolean deletionReported = deleteOwnedLink(restoredTargetGuardWitness);
            if (hasRestoredTargetGuardWitnessPathIdentity() || !deletionReported) return false;
            restoredTargetGuardWitness = null;
            restoredTargetGuardWitnessIdentity = null;
            restoredTargetGuardWitnessStableIdentity = null;
            restoredTargetGuardWitnessCreated = false;
            return true;
        }

        private boolean reconcileRestoredTargetGuard() {
            if (restoredTargetGuard == null) return true;
            if (!hasRestoredTargetGuardPathIdentity()) {
                return !Files.exists(restoredTargetGuard, LinkOption.NOFOLLOW_LINKS);
            }
            boolean deletionReported = deleteOwnedLink(restoredTargetGuard);
            if (hasRestoredTargetGuardPathIdentity() || !deletionReported) return false;
            restoredTargetGuard = null;
            restoredTargetGuardIdentity = null;
            restoredTargetGuardStableIdentity = null;
            restoredTargetGuardCreated = false;
            return true;
        }

        private boolean deleteBackupWitness(Path relatedPath) {
            if (backupWitness == null) return true;
            try {
                boolean sameRelated = relatedPath != null
                        && hasBackupWitnessPathIdentity()
                        && Files.exists(relatedPath, LinkOption.NOFOLLOW_LINKS)
                        && sameFile(backupWitness, relatedPath);
                if (backupWitnessIdentity == null) {
                    sameRelated = sameRelated && relatedPath.equals(expected.path)
                            && matches(expected);
                }
                if (!sameRelated) return false;
                boolean deletionReported = deleteOwnedLink(backupWitness);
                if (hasBackupWitnessPathIdentity()) return false;
                if (!deletionReported) return false;
                backupWitness = null;
                backupWitnessCreated = false;
                return true;
            } catch (RuntimeException failure) {
                return false;
            }
        }

        private boolean deleteBackupWitnessAfterBackupRemoval() {
            if (backupWitness == null) return true;
            if (backup != null && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!hasBackupWitnessIdentity()) return false;
            boolean deletionReported = deleteOwnedLink(backupWitness);
            if (hasBackupWitnessIdentity()) return false;
            if (!deletionReported) return false;
            backupWitness = null;
            backupWitnessCreated = false;
            return true;
        }

        private boolean deleteTemporaryWitness() {
            if (temporaryIdentity == null || temporaryIdentity.witness() == null) return true;
            try {
                Path related = published ? expected.path
                        : rollback != null && Files.exists(rollback, LinkOption.NOFOLLOW_LINKS)
                        ? rollback : temporary;
                if (!hasCurrentTemporaryIdentity(related)) return false;
                return deleteTemporaryWitnessAndGuard();
            } catch (RuntimeException failure) {
                return false;
            }
        }

        private boolean deleteTemporaryWitnessAndGuard() {
            if (!hasTemporaryWitnessIdentity()) return false;
            Path witness = temporaryIdentity.witness();
            boolean witnessDeletionReported = deleteOwnedLink(witness);
            if (hasTemporaryWitnessIdentity() || !witnessDeletionReported) return false;

            Path guard = temporaryWitnessGuard();
            if (guard != null) {
                if (!hasTemporaryWitnessGuardIdentity()) return false;
                boolean guardDeletionReported = deleteOwnedLink(guard);
                if (hasTemporaryWitnessGuardIdentity() || !guardDeletionReported) return false;
                temporaryWitnessGuard = null;
                temporaryWitnessGuardCreated = false;
            }
            temporaryIdentity = new TemporaryIdentity(temporaryIdentity.fileKey(),
                    temporaryIdentity.created(), null);
            temporaryWitnessIdentity = null;
            return true;
        }

        private Path retainedTemporaryPath() {
            Path witness = temporaryIdentity == null ? null : temporaryIdentity.witness();
            if (witness != null && hasTemporaryWitnessIdentity()
                    && (hasUncapturedTemporaryWitnessGuard()
                    || samePublishedBytes(witness, bytes))) return witness;
            Path guard = temporaryWitnessGuard();
            if (guard != null && hasTemporaryWitnessGuardIdentity()
                    && (hasUncapturedTemporaryWitnessGuard()
                    || samePublishedBytes(guard, bytes))) return guard;
            if (temporary != null && hasCurrentTemporaryIdentity(temporary)) {
                return temporary;
            }
            return null;
        }

        private boolean hasTemporaryWitnessIdentity() {
            if (temporaryIdentity == null || temporaryIdentity.witness() == null
                    || !Files.isRegularFile(temporaryIdentity.witness(),
                    LinkOption.NOFOLLOW_LINKS)) return false;
            try {
                Path guard = temporaryWitnessGuard();
                if (guard != null) {
                    return hasTemporaryWitnessGuardIdentity()
                            && sameFile(temporaryIdentity.witness(), guard);
                }
                return temporaryWitnessIdentity != null
                        && matchesIdentity(temporaryWitnessIdentity,
                        temporaryIdentityReader.read(temporaryIdentity.witness()));
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }

        private Path temporaryWitnessGuard() {
            return temporaryWitnessGuard;
        }

        private boolean hasUncapturedTemporaryWitnessGuard() {
            return temporaryWitnessGuardCreated && temporaryWitnessGuard != null
                    && temporaryWitnessIdentity == null;
        }

        private boolean hasTemporaryWitnessGuardIdentity() {
            Path guard = temporaryWitnessGuard();
            if (guard == null || !temporaryWitnessGuardCreated
                    || !Files.isRegularFile(guard, LinkOption.NOFOLLOW_LINKS)) return false;
            if (temporaryWitnessIdentity == null) {
                return hasLiveTemporaryWitnessAnchor()
                        && sameFile(temporaryIdentity.witness(), guard);
            }
            try {
                TemporaryIdentity current = temporaryIdentityReader.read(guard);
                return matchesIdentity(temporaryWitnessIdentity, current);
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }

        private boolean hasLiveTemporaryWitnessAnchor() {
            if (temporary == null || temporaryIdentity == null
                    || temporaryIdentity.witness() == null
                    || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(temporaryIdentity.witness(),
                    LinkOption.NOFOLLOW_LINKS)) return false;
            try {
                return matchesIdentity(temporaryIdentity,
                        temporaryIdentityReader.read(temporary))
                        && matchesIdentity(temporaryIdentity,
                        temporaryIdentityReader.read(temporaryIdentity.witness()))
                        && sameFile(temporary, temporaryIdentity.witness());
            } catch (IOException | RuntimeException failure) {
                return false;
            }
        }

        private void refreshTemporaryWitnessAfterMove() {
            Path witness = temporaryIdentity == null ? null : temporaryIdentity.witness();
            Path guard = temporaryWitnessGuard();
            // Windows can change creation metadata shared by every hard link when one name moves.
            // Refresh only while the witness is still tied to the independently named guard.
            if (witness == null || guard == null
                    || !Files.isRegularFile(witness, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(guard, LinkOption.NOFOLLOW_LINKS)
                    || !sameFile(witness, guard)
                    || !samePublishedBytes(guard, bytes)) return;
            try {
                TemporaryIdentity current = temporaryIdentityReader.read(guard);
                if (current != null) {
                    temporaryWitnessIdentity = new TemporaryIdentity(current.fileKey(),
                            current.created(), guard);
                }
            } catch (IOException | RuntimeException ignored) {
                // An ambiguous relocation remains unowned and is handled conservatively.
            }
        }

        private boolean hasCurrentTemporaryIdentity(Path path) {
            if (temporaryIdentity == null || path == null
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
            if (temporaryIdentity.witness() == null) {
                return hasTemporaryIdentity(path, temporaryIdentity);
            }
            if (path.equals(temporary)) {
                try {
                    return hasTemporaryWitnessIdentity()
                            && matchesIdentity(temporaryIdentity,
                            temporaryIdentityReader.read(path))
                            && sameFile(path, temporaryIdentity.witness());
                } catch (IOException | RuntimeException failure) {
                    return false;
                }
            }
            return path.equals(temporaryIdentity.witness())
                    ? hasTemporaryWitnessIdentity()
                    : hasTemporaryWitnessIdentity()
                    && sameFile(path, temporaryIdentity.witness());
        }

        private boolean hasCurrentRelocatedTemporaryIdentity(Path path) {
            return hasCurrentTemporaryIdentity(path);
        }

        private boolean deleteOwnedLink(Path path) {
            try {
                return ownedLinkDeleter.test(path);
            } catch (RuntimeException failure) {
                return false;
            }
        }

        private Path recoveryCandidate() {
            if (ownsBackup()) return backup;
            if (hasBackupWitnessPathIdentity()) {
                return backupWitness;
            }
            if (hasRestoredTargetGuardPathIdentity()) return restoredTargetGuard;
            if (hasRestoredTargetGuardWitnessPathIdentity()) {
                return restoredTargetGuardWitness;
            }
            if (hasRestoredTargetIdentity()) return expected.path;
            if (hasBackupIdentityAt(backup)) return backup;
            if (hasBackupIdentityAt(expected.path)) return expected.path;
            return null;
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

    private Path vacantPath(Path parent, String prefix) throws IOException {
        Path candidate;
        try {
            candidate = uniquePathFactory.create(parent, prefix);
        } catch (RuntimeException failure) {
            throw new IOException(failure);
        }
        if (candidate == null) throw new IOException();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!Objects.equals(parent, normalized.getParent())
                || normalized.getFileName() == null
                || Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(String.valueOf(normalized));
        }
        return normalized;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                .toString();
    }

    private static byte[] strictUtf8(String value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static byte[] readBoundedRegularFile(Path path) throws IOException {
        return BoundedRegularFileReader.read(path, Math.toIntExact(MAX_BYTES));
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF;
    }

    private static boolean matches(Target expected) {
        return matchesAtPath(expected.path, expected);
    }

    private static boolean matchesAtPath(Path path, Target expected) {
        try {
            Current current = inspect(path);
            return current.valid() && Objects.equals(expected.version, current.version())
                    && (expected.fingerprint == null
                    || Arrays.equals(expected.fingerprint, fingerprint(path)));
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

    private static byte[] fingerprint(Path path) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(readBoundedRegularFile(path));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static boolean samePublishedBytes(Path path, byte[] expected) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink()
                    && attributes.size() == expected.length
                    && Arrays.equals(readBoundedRegularFile(path), expected);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static boolean sameFile(Path first, Path second) {
        try {
            return first != null && second != null && Files.isSameFile(first, second);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Requests deletion through an opened provider handle rather than a separate path deletion.
     * The hard-link witnesses used by this class establish ownership before this call. Java NIO
     * cannot defend against a malicious same-user process swapping the name between validation and
     * open; cooperative writers are the supported boundary.
     */
    private static boolean deleteOwnedLinkOnClose(Path path) {
        try {
            Set<OpenOption> options = Set.of(StandardOpenOption.READ,
                    StandardOpenOption.DELETE_ON_CLOSE, LinkOption.NOFOLLOW_LINKS);
            try (var ignored = Files.newByteChannel(path, options)) {
                // DELETE_ON_CLOSE binds cleanup to the object opened by the installed provider.
            }
            return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Windows' default provider uses its verified no-options MoveFileEx(0) behavior. Other default
     * providers atomically create the destination as a hard link before unlinking the source, so a
     * destination appearing after the final check is never replaced. Unsupported hard links,
     * cross-filesystem moves, and non-default providers fail closed. Java NIO cannot make the
     * identity-check-to-unlink interval safe from a malicious same-user process; cooperative
     * writers are the supported boundary and every ambiguity retains artifacts for recovery.
     */
    static void moveNoReplace(Path source, Path destination,
            FinalTargetVerifier finalCheck) throws IOException {
        if (source.getFileSystem() != destination.getFileSystem()
                || source.getFileSystem() != FileSystems.getDefault()) {
            throw new IOException("unsupported no-replace provider");
        }
        boolean windowsDefault = "sun.nio.fs.WindowsFileSystemProvider".equals(
                source.getFileSystem().provider().getClass().getName());
        moveNoReplace(source, destination, finalCheck, windowsDefault,
                Files::move, Files::createLink);
    }

    static void moveNoReplace(Path source, Path destination, FinalTargetVerifier finalCheck,
            boolean windowsDefault, MovePrimitive movePrimitive, LinkPrimitive linkPrimitive)
            throws IOException {
        moveNoReplace(source, destination, finalCheck, windowsDefault, movePrimitive,
                linkPrimitive, SqlScriptFileStore::deleteOwnedLinkOnClose);
    }

    static void moveNoReplace(Path source, Path destination, FinalTargetVerifier finalCheck,
            boolean windowsDefault, MovePrimitive movePrimitive, LinkPrimitive linkPrimitive,
            Predicate<Path> sourceUnlink) throws IOException {
        Objects.requireNonNull(movePrimitive, "movePrimitive");
        Objects.requireNonNull(linkPrimitive, "linkPrimitive");
        Objects.requireNonNull(sourceUnlink, "sourceUnlink");
        Path sourceParent = source.toAbsolutePath().normalize().getParent();
        Path destinationParent = destination.toAbsolutePath().normalize().getParent();
        if (sourceParent == null || !Objects.equals(sourceParent, destinationParent)) {
            throw new IOException("no-replace move must stay in one directory");
        }
        finalCheck.verify();
        if (windowsDefault) {
            movePrimitive.move(source, destination);
            return;
        }

        TemporaryIdentity sourceIdentity = temporaryIdentity(source);
        if (sourceIdentity == null) throw new IOException("no-replace source is not regular");
        linkPrimitive.create(destination, source);
        TemporaryIdentity linkedIdentity = temporaryIdentity(destination);
        if (!sameFile(source, destination)
                || !matchesIdentity(sourceIdentity, linkedIdentity)) {
            throw new IOException("no-replace link identity is ambiguous");
        }
        if (!sourceUnlink.test(source)) {
            throw new IOException("no-replace source unlink failed");
        }
        TemporaryIdentity retainedIdentity = temporaryIdentity(destination);
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                || !matchesIdentity(linkedIdentity, retainedIdentity)) {
            throw new IOException("no-replace post-unlink identity is ambiguous");
        }
    }

    private TemporaryIdentity captureTemporaryIdentity(Path path) throws IOException {
        TemporaryIdentity identity = temporaryIdentityReader.read(path);
        if (identity == null || identity.fileKey() != null) return identity;
        Path witness = vacantPath(path.getParent(), ".datacube-sql-owner-");
        Files.createLink(witness, path);
        return new TemporaryIdentity(null, identity.created(), witness);
    }

    private static TemporaryIdentity temporaryIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return null;
        return new TemporaryIdentity(attributes.fileKey(), attributes.creationTime(), null);
    }

    private static StableFileIdentity stableFileIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) return null;
        byte[] fingerprint = attributes.size() <= MAX_BYTES ? fingerprint(path) : null;
        return new StableFileIdentity(attributes.fileKey(), attributes.creationTime(),
                attributes.lastModifiedTime(), attributes.size(), fingerprint);
    }

    private static boolean matchesStableFileIdentity(Path path, StableFileIdentity expected) {
        if (path == null || expected == null) return false;
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.size() != expected.size()
                    || !Objects.equals(attributes.lastModifiedTime(), expected.modified())) {
                return false;
            }
            boolean objectIdentity = expected.fileKey() != null
                    ? Objects.equals(expected.fileKey(), attributes.fileKey())
                    : Objects.equals(expected.created(), attributes.creationTime());
            return objectIdentity
                    && (expected.fingerprint() == null
                    || Arrays.equals(expected.fingerprint(), fingerprint(path)));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private boolean hasTemporaryIdentity(Path path, TemporaryIdentity expected) {
        if (expected == null || path == null
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (expected.witness() != null) {
                TemporaryIdentity witness = temporaryIdentityReader.read(expected.witness());
                return matchesIdentity(expected, witness)
                        && Files.isSameFile(path, expected.witness());
            }
            return matchesIdentity(expected, temporaryIdentityReader.read(path));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private boolean hasRelocatedTemporaryIdentity(Path path, TemporaryIdentity expected) {
        if (expected == null || path == null
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (expected.witness() != null) {
                return Files.isRegularFile(expected.witness(), LinkOption.NOFOLLOW_LINKS)
                        && Files.isSameFile(path, expected.witness());
            }
            return matchesIdentity(expected, temporaryIdentityReader.read(path));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static boolean matchesIdentity(TemporaryIdentity expected, TemporaryIdentity candidate) {
        return expected != null && candidate != null
                && Objects.equals(expected.fileKey(), candidate.fileKey())
                && Objects.equals(expected.created(), candidate.created());
    }

    private record Version(Object fileKey, long size, FileTime modified, FileTime created) { }

    record TemporaryIdentity(Object fileKey, FileTime created, Path witness) { }

    private record StableFileIdentity(Object fileKey, FileTime created, FileTime modified,
            long size, byte[] fingerprint) { }

    private record Current(boolean valid, Version version) {
        private static final Current MISSING = new Current(true, null);
        private static final Current INVALID = new Current(false, null);
    }
}
