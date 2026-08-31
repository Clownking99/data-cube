package com.datacube.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Local draft filesystem boundary. Caller supplies a trusted existing parent. */
final class SqlDraftDirectory implements AutoCloseable {
    enum Stage { OPEN, BUSY, CLOSED, UNSAFE, SCAN_LIMIT, READ, WRITE, PUBLISH, CLEANUP, DELETE, CLOSE }
    static final class Failure extends IOException {
        private final Stage stage;
        Failure(Stage stage) { super("SQL draft I/O failed: " + stage); this.stage = stage; }
        Stage stage() { return stage; }
    }
    @FunctionalInterface interface Writer { void write(Path path, byte[] bytes) throws IOException; }
    @FunctionalInterface interface Mover { void move(Path source, Path target) throws IOException; }
    @FunctionalInterface interface Cleaner { void delete(Path path) throws IOException; }
    private record Identity(Object key, FileTime created) { }
    private record Stamp(Identity identity, long size, FileTime modified) { }
    private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();
    private final Path root;
    private final Identity rootIdentity;
    private final Identity lockIdentity;
    private final FileChannel channel;
    private final FileLock lock;
    private final Writer writer;
    private final Mover mover;
    private final Cleaner cleaner;
    private boolean closed;

    private SqlDraftDirectory(Path root, Identity rootIdentity, Identity lockIdentity,
            FileChannel channel, FileLock lock, Writer writer, Mover mover, Cleaner cleaner) {
        this.root = root; this.rootIdentity = rootIdentity; this.lockIdentity = lockIdentity;
        this.channel = channel; this.lock = lock; this.writer = writer; this.mover = mover; this.cleaner = cleaner;
    }

    static SqlDraftDirectory open(Path requested) throws IOException {
        return open(requested, SqlDraftDirectory::writeForced, SqlDraftDirectory::moveAtomic, Files::deleteIfExists);
    }

    static SqlDraftDirectory open(Path requested, Writer writer, Mover mover, Cleaner cleaner) throws IOException {
        FileChannel channel = null;
        FileLock lock = null;
        Path claimedRoot = null;
        try {
            Objects.requireNonNull(writer); Objects.requireNonNull(mover); Objects.requireNonNull(cleaner);
            Path absolute = requested.toAbsolutePath().normalize();
            if (absolute.getParent() == null || absolute.getFileName() == null) throw new Failure(Stage.UNSAFE);
            Path root = absolute.getParent().toRealPath().resolve(absolute.getFileName());
            try { Files.createDirectory(root); } catch (FileAlreadyExistsException exists) { }
            BasicFileAttributes directory = attributes(root);
            if (!directory.isDirectory() || directory.isSymbolicLink()) throw new Failure(Stage.UNSAFE);
            // Avoid opening/closing another descriptor for a same-JVM live lock.
            if (!LIVE.add(root)) throw new Failure(Stage.BUSY);
            claimedRoot = root;
            Path lockPath = root.resolve(".writer.lock");
            stamp(lockPath);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new Failure(Stage.BUSY); }
            if (lock == null) throw new Failure(Stage.BUSY);
            Stamp lockStamp = stamp(lockPath);
            if (lockStamp == null) throw new Failure(Stage.UNSAFE);
            SqlDraftDirectory result = new SqlDraftDirectory(root, identity(directory), lockStamp.identity(),
                    channel, lock, writer, mover, cleaner);
            result.check();
            return result;
        } catch (IOException | RuntimeException error) {
            if (lock != null) try { lock.release(); } catch (IOException ignored) { }
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            if (claimedRoot != null) LIVE.remove(claimedRoot);
            if (error instanceof Failure failure) throw failure;
            throw new Failure(Stage.OPEN);
        }
    }

    synchronized List<String> entries() throws IOException {
        check();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            List<String> names = new ArrayList<>();
            for (Path path : stream) {
                if (names.size() == 512) throw new Failure(Stage.SCAN_LIMIT);
                names.add(path.getFileName().toString());
            }
            return List.copyOf(names);
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.READ); }
    }

    synchronized byte[] read(String name, int limit) throws IOException {
        check();
        Path path = target(name);
        if (limit < 0 || limit > SqlDraftCodec.MAX_FILE_BYTES) throw new Failure(Stage.READ);
        try {
            Stamp before = stamp(path);
            if (before == null) return null;
            if (before.size() > limit) throw new Failure(Stage.READ);
            try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ByteBuffer buffer = ByteBuffer.allocate(Math.min(8192, limit + 1));
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > limit) throw new Failure(Stage.READ);
                    bytes.write(buffer.array(), 0, count);
                    buffer.clear();
                }
                check();
                if (!Objects.equals(before, stamp(path))) throw new Failure(Stage.UNSAFE);
                return bytes.toByteArray();
            }
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.READ); }
    }

    synchronized void publish(String name, byte[] bytes) throws IOException {
        check();
        Path path = target(name);
        if (bytes == null || bytes.length > SqlDraftCodec.MAX_FILE_BYTES) throw new Failure(Stage.WRITE);
        Path temporary = null;
        boolean published = false;
        Stage stage = Stage.WRITE;
        try {
            Stamp before = stamp(path);
            temporary = Files.createTempFile(root, ".draft-", ".tmp");
            writer.write(temporary, bytes);
            stage = Stage.PUBLISH;
            check();
            if (!Objects.equals(before, stamp(path))) throw new Failure(Stage.UNSAFE);
            Stamp ready = stamp(temporary);
            if (ready == null || ready.size() != bytes.length) throw new Failure(Stage.UNSAFE);
            mover.move(temporary, path);
            published = true;
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(stage); }
        finally {
            if (temporary != null && !published) {
                try { cleaner.delete(temporary); }
                catch (IOException | RuntimeException error) { throw new Failure(Stage.CLEANUP); }
            }
        }
    }

    synchronized void delete(String name) throws IOException {
        check();
        Path path = target(name);
        try {
            if (stamp(path) != null) Files.delete(path);
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.DELETE); }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        boolean failed = false;
        try { lock.release(); } catch (IOException error) { failed = true; }
        try { channel.close(); } catch (IOException error) { failed = true; }
        LIVE.remove(root);
        if (failed) throw new Failure(Stage.CLOSE);
    }

    static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    static void moveAtomic(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void check() throws IOException {
        if (closed || !lock.isValid()) throw new Failure(Stage.CLOSED);
        try {
            BasicFileAttributes directory = attributes(root);
            Stamp lockStamp = stamp(root.resolve(".writer.lock"));
            if (!directory.isDirectory() || !rootIdentity.equals(identity(directory))
                    || !root.toRealPath().equals(root) || lockStamp == null
                    || !lockIdentity.equals(lockStamp.identity())) throw new Failure(Stage.UNSAFE);
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException error) { throw new Failure(Stage.UNSAFE); }
    }

    private Path target(String name) throws IOException {
        boolean allowed = "preferences.bin".equals(name) || "workspace.bin".equals(name)
                || "workspace-preferences.bin".equals(name);
        if (name != null && name.endsWith(".draft")) {
            String id = name.substring(0, name.length() - 6);
            try { allowed = UUID.fromString(id).toString().equals(id); }
            catch (IllegalArgumentException invalid) { }
        }
        if (!allowed) throw new Failure(Stage.UNSAFE);
        for (String existing : entries()) {
            if (existing.equalsIgnoreCase(name) && !existing.equals(name)) throw new Failure(Stage.UNSAFE);
        }
        return root.resolve(name);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static Identity identity(BasicFileAttributes value) {
        return new Identity(value.fileKey(), value.creationTime());
    }

    private static Stamp stamp(Path path) throws IOException {
        try {
            BasicFileAttributes value = attributes(path);
            if (!value.isRegularFile() || value.isSymbolicLink()) throw new Failure(Stage.UNSAFE);
            return new Stamp(identity(value), value.size(), value.lastModifiedTime());
        } catch (NoSuchFileException absent) { return null; }
    }
}
