package com.datacube.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedRegularFileReaderTest {

    @Test
    void opensReadNoFollowReadsOnlyLimitPlusOneAndChecksThePostReadStamp() throws Exception {
        byte[] growingContents = new byte[64];
        Arrays.fill(growingContents, (byte) 'x');
        TrackingChannel channel = new TrackingChannel(growingContents);
        AtomicReference<Set<? extends OpenOption>> options = new AtomicReference<>();
        AtomicInteger attributeReads = new AtomicInteger();
        BasicFileAttributes stamp = attributes(8, FileTime.fromMillis(10));

        byte[] result = BoundedRegularFileReader.read(Path.of("bounded.sql"), 16,
                (path, linkOptions) -> {
                    assertArrayEquals(new LinkOption[]{LinkOption.NOFOLLOW_LINKS}, linkOptions);
                    attributeReads.incrementAndGet();
                    return stamp;
                }, (path, requested) -> {
                    options.set(Set.copyOf(requested));
                    return channel;
                });

        assertEquals(17, result.length);
        assertEquals(17, channel.bytesRead());
        assertEquals(2, attributeReads.get());
        assertEquals(Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), options.get());
        assertTrue(channel.closed());
    }

    @Test
    void rejectsAFileWhosePathStampChangesWhileTheChannelIsRead() {
        AtomicInteger reads = new AtomicInteger();
        BasicFileAttributes before = attributes(3, FileTime.fromMillis(10));
        BasicFileAttributes after = attributes(4, FileTime.fromMillis(20));

        assertThrows(BoundedRegularFileReader.ChangedDuringReadException.class,
                () -> BoundedRegularFileReader.read(Path.of("changed.sql"), 16,
                        (path, options) -> reads.getAndIncrement() == 0 ? before : after,
                        (path, options) -> new TrackingChannel(new byte[]{1, 2, 3})));
        assertEquals(2, reads.get());
    }

    @Test
    void rejectsNonRegularInputsWithoutOpeningAChannel() {
        AtomicInteger opens = new AtomicInteger();
        assertThrows(IOException.class, () -> BoundedRegularFileReader.read(Path.of("link.sql"), 16,
                (path, options) -> nonRegularAttributes(), (path, options) -> {
                    opens.incrementAndGet();
                    return new TrackingChannel(new byte[0]);
                }));
        assertEquals(0, opens.get());
    }

    private static BasicFileAttributes attributes(long size, FileTime modified) {
        return new TestAttributes(true, false, size, modified, FileTime.fromMillis(1), "key");
    }

    private static BasicFileAttributes nonRegularAttributes() {
        return new TestAttributes(false, true, 0, FileTime.fromMillis(1), FileTime.fromMillis(1), "link");
    }

    private record TestAttributes(boolean regular, boolean symbolic, long size, FileTime modified,
            FileTime created, Object key) implements BasicFileAttributes {
        @Override public FileTime lastModifiedTime() { return modified; }
        @Override public FileTime lastAccessTime() { return modified; }
        @Override public FileTime creationTime() { return created; }
        @Override public boolean isRegularFile() { return regular; }
        @Override public boolean isDirectory() { return false; }
        @Override public boolean isSymbolicLink() { return symbolic; }
        @Override public boolean isOther() { return !regular && !symbolic; }
        @Override public long size() { return size; }
        @Override public Object fileKey() { return key; }
    }

    private static final class TrackingChannel implements SeekableByteChannel {
        private final byte[] contents;
        private int position;
        private boolean open = true;

        private TrackingChannel(byte[] contents) {
            this.contents = contents;
        }

        @Override
        public int read(ByteBuffer destination) {
            if (position == contents.length) return -1;
            int count = Math.min(destination.remaining(), contents.length - position);
            destination.put(contents, position, count);
            position += count;
            return count;
        }

        @Override public int write(ByteBuffer source) { throw new UnsupportedOperationException(); }
        @Override public long position() { return position; }
        @Override public SeekableByteChannel position(long newPosition) { throw new UnsupportedOperationException(); }
        @Override public long size() { return contents.length; }
        @Override public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException(); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        private int bytesRead() { return position; }
        private boolean closed() { return !open; }
    }
}
