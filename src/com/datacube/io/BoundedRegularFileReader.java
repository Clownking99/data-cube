package com.datacube.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Set;

/** Bounded, no-follow reads for small application-owned or user-selected files. */
public final class BoundedRegularFileReader {
    private static final int BUFFER_BYTES = 8192;
    private static final Set<OpenOption> READ_OPTIONS = Set.of(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private BoundedRegularFileReader() { }

    public static byte[] read(Path path, int maximumBytes) throws IOException {
        return read(path, maximumBytes,
                (candidate, options) -> Files.readAttributes(candidate,
                        BasicFileAttributes.class, options),
                Files::newByteChannel);
    }

    static byte[] read(Path path, int maximumBytes, AttributeReader attributes,
            ChannelOpener opener) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(opener, "opener");
        if (maximumBytes < 0 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid byte bound");
        }

        Stamp before = stamp(attributes.read(path, LinkOption.NOFOLLOW_LINKS));
        if (!before.regular()) throw new IOException("not a regular file");

        int bound = maximumBytes + 1;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(bound, BUFFER_BYTES));
        try (SeekableByteChannel channel = opener.open(path, READ_OPTIONS)) {
            while (bytes.size() < bound) {
                int remaining = bound - bytes.size();
                ByteBuffer buffer = ByteBuffer.allocate(Math.min(BUFFER_BYTES, remaining));
                int count = channel.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                bytes.write(buffer.array(), 0, count);
            }
        }

        Stamp after = stamp(attributes.read(path, LinkOption.NOFOLLOW_LINKS));
        if (!before.equals(after)) throw new ChangedDuringReadException();
        return bytes.toByteArray();
    }

    private static Stamp stamp(BasicFileAttributes attributes) throws IOException {
        if (attributes == null || !attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("not a regular file");
        }
        return new Stamp(true, attributes.fileKey(), attributes.size(),
                attributes.lastModifiedTime(), attributes.creationTime());
    }

    @FunctionalInterface
    interface AttributeReader {
        BasicFileAttributes read(Path path, LinkOption... options) throws IOException;
    }

    @FunctionalInterface
    interface ChannelOpener {
        SeekableByteChannel open(Path path, Set<? extends OpenOption> options) throws IOException;
    }

    public static final class ChangedDuringReadException extends IOException {
        public ChangedDuringReadException() {
            super("file changed while reading");
        }
    }

    private record Stamp(boolean regular, Object key, long size, FileTime modified, FileTime created) { }
}
