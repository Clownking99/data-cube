package com.datacube.export;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SafeResultFilePublisherTest {
    @TempDir Path directory;

    private SafeResultFilePublisher publisher() {
        return new SafeResultFilePublisher((source, target) ->
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING),
                path -> Files.deleteIfExists(path), path -> fail("Unexpected cleanup failure"));
    }

    @Test void failingWriterTouchesOnlyItsOwnTempAndKeepsOldBytes() throws Exception {
        Path target = directory.resolve("result.csv");
        Files.writeString(target, "old");
        Path unrelated = Files.writeString(directory.resolve("unrelated.tmp"), "keep");
        var temp = new AtomicReference<Path>();
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () ->
                publisher().publish(SafeResultFilePublisher.capture(target),
                        new ResultExportOperation(), (path, operation) -> {
                            temp.set(path);
                            assertNotEquals(target, path);
                            Files.writeString(path, "partial");
                            throw new IOException("secret must not surface");
                        }));
        assertEquals(SafeResultFilePublisher.Stage.WRITE, failure.stage());
        assertNull(failure.getCause());
        assertEquals("old", Files.readString(target));
        assertEquals("keep", Files.readString(unrelated));
        assertFalse(Files.exists(temp.get()));
    }

    @Test void changedTargetAndUnsupportedAtomicMoveNeverReplaceOldBytes() throws Exception {
        Path target = Files.writeString(directory.resolve("result.csv"), "old");
        var confirmed = SafeResultFilePublisher.capture(target);
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () ->
                publisher().publish(confirmed, new ResultExportOperation(), (path, operation) -> {
                    Files.writeString(path, "new");
                    Files.writeString(target, "external change");
                }));
        assertEquals(SafeResultFilePublisher.Stage.TARGET_CHANGED, failure.stage());
        assertEquals("external change", Files.readString(target));
        var moverCalls = new AtomicInteger();
        var unsupported = new SafeResultFilePublisher((source, destination) -> {
            moverCalls.incrementAndGet();
            throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test");
        }, path -> Files.deleteIfExists(path), path -> fail("cleanup"));
        var atomicFailure = assertThrows(SafeResultFilePublisher.Failure.class, () -> unsupported.publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(),
                (path, operation) -> Files.writeString(path, "new")));
        assertEquals(SafeResultFilePublisher.Stage.PUBLISH, atomicFailure.stage());
        assertEquals(1, moverCalls.get());
        assertEquals("external change", Files.readString(target));
    }

    @Test void missingTargetThatAppearsDuringWriteIsNotOverwritten() throws Exception {
        Path target = directory.resolve("new.csv");
        assertThrows(SafeResultFilePublisher.Failure.class, () -> publisher().publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(),
                (path, operation) -> {
                    Files.writeString(path, "ours");
                    Files.writeString(target, "other");
                }));
        assertEquals("other", Files.readString(target));
    }

    @Test void successfulPublishAndCancellationHaveDifferentTerminalEffects() throws Exception {
        // A chosen path can contain aliases; publication returns the real parent path.
        Path target = directory.resolve(".").resolve("result.csv");
        var operation = new ResultExportOperation();
        Path published = publisher().publish(SafeResultFilePublisher.capture(target), operation,
                (path, token) -> Files.writeString(path, "new"));
        assertEquals(directory.toRealPath().resolve("result.csv"), published);
        assertTrue(Files.isSameFile(target, published));
        assertTrue(operation.published());
        assertFalse(operation.cancel());
        assertEquals("new", Files.readString(target));
        var cancelled = new ResultExportOperation();
        assertThrows(java.util.concurrent.CancellationException.class, () -> publisher().publish(
                SafeResultFilePublisher.capture(target), cancelled, (path, token) -> {
                    Files.writeString(path, "partial");
                    cancelled.cancel();
                }));
        assertEquals("new", Files.readString(target));
    }

    @Test void streamCloseFailurePreservesTarget() throws Exception {
        Path target = Files.writeString(directory.resolve("result.csv"), "old");
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> publisher().publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(), (path, operation) -> {
                    try (var writer = new java.io.FilterWriter(Files.newBufferedWriter(path)) {
                        @Override public void close() throws IOException {
                            super.close();
                            throw new IOException("close sentinel");
                        }
                    }) { writer.write("new"); }
                }));
        assertEquals(SafeResultFilePublisher.Stage.WRITE, failure.stage());
        assertEquals("old", Files.readString(target));
    }

    @Test void sameCanonicalTargetIsExclusiveAndLockIsReleased() throws Exception {
        Path target = directory.resolve("result.csv");
        var selected = SafeResultFilePublisher.capture(target);
        var alias = SafeResultFilePublisher.capture(directory.resolve(".").resolve("result.csv"));
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var worker = new java.util.concurrent.FutureTask<Path>(() -> publisher().publish(selected,
                new ResultExportOperation(), (path, operation) -> {
                    Files.writeString(path, "first");
                    started.countDown();
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                }));
        Thread.ofVirtual().start(worker);
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> publisher().publish(alias,
                    new ResultExportOperation(), (path, operation) -> fail("Second writer must not start")));
            assertEquals(SafeResultFilePublisher.Stage.TARGET_BUSY, failure.stage());
            release.countDown();
            worker.get(5, java.util.concurrent.TimeUnit.SECONDS);
            publisher().publish(SafeResultFilePublisher.capture(target), new ResultExportOperation(),
                    (path, operation) -> Files.writeString(path, "second"));
            assertEquals("second", Files.readString(target));
        } finally {
            release.countDown();
            worker.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test void cleanupFailureReportsOnlyOwnedPathAndFixedStage() throws Exception {
        Path target = Files.writeString(directory.resolve("result.csv"), "old");
        var owned = new AtomicReference<Path>();
        var diagnosed = new AtomicReference<Path>();
        var brokenCleaner = new SafeResultFilePublisher((source, destination) ->
                fail("Failed writer cannot publish"),
                path -> { throw new IOException("private cleanup text"); }, diagnosed::set);
        var failure = assertThrows(SafeResultFilePublisher.Failure.class, () -> brokenCleaner.publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(), (path, operation) -> {
                    owned.set(path);
                    Files.writeString(path, "partial");
                    throw new IOException("private row value");
                }));
        assertEquals(SafeResultFilePublisher.Stage.CLEANUP, failure.stage());
        assertEquals(owned.get(), diagnosed.get());
        assertEquals(owned.get(), failure.temporaryPath());
        assertTrue(Files.exists(owned.get()));
        assertEquals("old", Files.readString(target));
        assertFalse(failure.getMessage().contains("private"));
        assertNull(failure.getCause());
    }

    @Test void directoriesAreRejected() {
        assertThrows(SafeResultFilePublisher.Failure.class,
                () -> SafeResultFilePublisher.capture(directory));
    }

    @Test void symbolicLinkTargetIsRejectedWithoutTouchingDestination() throws Exception {
        Path actual = Files.writeString(directory.resolve("actual.csv"), "old");
        Path link = directory.resolve("link.csv");
        try {
            Files.createSymbolicLink(link, actual);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Symbolic link creation unavailable for this test account");
        }
        assertThrows(SafeResultFilePublisher.Failure.class, () -> SafeResultFilePublisher.capture(link));
        assertEquals("old", Files.readString(actual));
    }
}
