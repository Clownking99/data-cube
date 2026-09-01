package com.datacube.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Versioned, bounded local metadata for recently used SQL script paths. */
public final class RecentSqlFiles {
    static final int MAX_ENTRIES = 10;
    static final int MAX_PATH_CHARS = 4096;
    static final int MAX_INDEX_BYTES = 128 * 1024;
    private static final int MAX_ENCODED_LINE_CHARS = MAX_PATH_CHARS * 4;
    private static final String HEADER = "DATACUBE_SQL_RECENT_V1";
    private static final String SAVE_DIAGNOSTIC = "Unable to save recent SQL files.";
    private static final String CLEAR_DIAGNOSTIC = "Unable to clear recent SQL files.";

    @FunctionalInterface
    interface ContentWriter {
        void write(Path path, byte[] bytes) throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface IndexDeleter {
        void delete(Path path) throws IOException;
    }

    @FunctionalInterface
    interface TemporaryIdentityReader {
        TemporaryIdentity read(Path path) throws IOException;
    }

    private final Path storage;
    private final ContentWriter writer;
    private final AtomicMover mover;
    private final IndexDeleter deleter;
    private final Consumer<String> diagnostic;
    private final TemporaryIdentityReader temporaryIdentityReader;
    private List<Path> paths;

    public RecentSqlFiles(Path storage) {
        this(storage, (path, bytes) -> Files.write(path, bytes), (source, destination) ->
                        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING),
                path -> Files.deleteIfExists(path), ignored -> { });
    }

    RecentSqlFiles(Path storage, ContentWriter writer, AtomicMover mover, IndexDeleter deleter,
            Consumer<String> diagnostic) {
        this(storage, writer, mover, deleter, diagnostic, RecentSqlFiles::temporaryIdentity);
    }

    RecentSqlFiles(Path storage, ContentWriter writer, AtomicMover mover, IndexDeleter deleter,
            Consumer<String> diagnostic, TemporaryIdentityReader temporaryIdentityReader) {
        this.storage = Objects.requireNonNull(storage, "storage").toAbsolutePath().normalize();
        this.writer = Objects.requireNonNull(writer, "writer");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.deleter = Objects.requireNonNull(deleter, "deleter");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.temporaryIdentityReader = Objects.requireNonNull(temporaryIdentityReader,
                "temporaryIdentityReader");
        this.paths = load();
    }

    public synchronized List<Path> recent() {
        return List.copyOf(paths);
    }

    public synchronized void record(Path path) {
        Path required = Objects.requireNonNull(path, "path");
        try {
            Path normalized = required.toAbsolutePath().normalize();
            if (normalized.toString().length() > MAX_PATH_CHARS) throw new IOException();
            List<Path> candidate = new ArrayList<>();
            candidate.add(normalized);
            for (Path existing : paths) {
                if (!existing.equals(normalized) && candidate.size() < MAX_ENTRIES) candidate.add(existing);
            }
            byte[] serialized = encode(candidate);
            if (serialized.length > MAX_INDEX_BYTES) throw new IOException();
            if (!publish(serialized)) throw new IOException();
            paths = List.copyOf(candidate);
        } catch (IOException | RuntimeException failure) {
            report(SAVE_DIAGNOSTIC);
        }
    }

    public synchronized void clear() {
        try {
            deleter.delete(storage);
            paths = List.of();
        } catch (IOException | RuntimeException failure) {
            report(CLEAR_DIAGNOSTIC);
        }
    }

    private List<Path> load() {
        final byte[] bytes;
        try {
            if (!Files.exists(storage) || Files.size(storage) > MAX_INDEX_BYTES) return List.of();
            bytes = Files.readAllBytes(storage);
        } catch (IOException | RuntimeException failure) {
            return List.of();
        }
        if (bytes.length > MAX_INDEX_BYTES) return List.of();

        final String content;
        try {
            content = decodeUtf8(bytes);
        } catch (CharacterCodingException failure) {
            return List.of();
        }
        String[] lines = content.split("\\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) return List.of();

        List<Path> loaded = new ArrayList<>();
        for (int i = 1; i < lines.length && loaded.size() < MAX_ENTRIES; i++) {
            Path candidate = decodePath(lines[i]);
            if (candidate != null && !loaded.contains(candidate)) loaded.add(candidate);
        }
        return List.copyOf(loaded);
    }

    private Path decodePath(String line) {
        if (line.length() > MAX_ENCODED_LINE_CHARS) return null;
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(line);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        final String decoded;
        try {
            decoded = decodeUtf8(bytes);
        } catch (CharacterCodingException invalid) {
            return null;
        }
        if (decoded.length() > MAX_PATH_CHARS) return null;
        try {
            Path path = Path.of(decoded).normalize();
            return path.isAbsolute() ? path : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private boolean publish(byte[] contents) {
        Path temporary = null;
        TemporaryIdentity temporaryIdentity = null;
        boolean published = false;
        boolean successful = false;
        try {
            Path parent = storage.getParent();
            if (parent == null) throw new IOException();
            temporary = Files.createTempFile(parent, ".datacube-recent-", ".tmp");
            temporaryIdentity = captureTemporaryIdentity(temporary);
            if (temporaryIdentity == null) return false;
            writer.write(temporary, contents);
            if (!hasTemporaryIdentity(temporary, temporaryIdentity)) return false;
            mover.move(temporary, storage);
            published = true;
            successful = true;
        } catch (IOException | RuntimeException failure) {
            return false;
        } finally {
            if (!published && temporary != null) cleanOwnedTemporary(temporary, temporaryIdentity);
            if (!cleanOwnedWitness(temporaryIdentity, published ? storage : temporary)) successful = false;
        }
        return successful;
    }

    private static byte[] encode(List<Path> paths) throws CharacterCodingException {
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        for (Path path : paths) {
            content.append(Base64.getEncoder().encodeToString(strictUtf8(path.toString()))).append('\n');
        }
        return strictUtf8(content.toString());
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
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

    private TemporaryIdentity captureTemporaryIdentity(Path path) throws IOException {
        TemporaryIdentity identity = temporaryIdentityReader.read(path);
        if (identity == null || identity.fileKey() != null) return identity;
        Path witness = path.resolveSibling(".datacube-recent-owner-" + UUID.randomUUID());
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
                return matchesIdentity(expected, candidate) && matchesIdentity(expected, witness)
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

    private void cleanOwnedTemporary(Path temporary, TemporaryIdentity identity) {
        if (!hasTemporaryIdentity(temporary, identity)) return;
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException ignored) {
            // The temporary was created by this instance and cleanup is best effort.
        }
    }

    private boolean cleanOwnedWitness(TemporaryIdentity identity, Path relatedPath) {
        if (identity == null || identity.witness() == null) return true;
        try {
            TemporaryIdentity witness = temporaryIdentityReader.read(identity.witness());
            if (!matchesIdentity(identity, witness)) return false;
            if (relatedPath != null && Files.exists(relatedPath, LinkOption.NOFOLLOW_LINKS)) {
                TemporaryIdentity related = temporaryIdentityReader.read(relatedPath);
                if (matchesIdentity(identity, related)) {
                    if (!Files.isSameFile(identity.witness(), relatedPath)) return false;
                } else if (Files.isSameFile(identity.witness(), relatedPath)) {
                    return false;
                }
            }
            Files.deleteIfExists(identity.witness());
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    record TemporaryIdentity(Object fileKey, FileTime created, Path witness) { }

    private void report(String message) {
        try {
            diagnostic.accept(message);
        } catch (RuntimeException ignored) {
            // Diagnostics must not change the persistence outcome.
        }
    }
}
