package com.datacube.export;

import java.io.IOException;
import java.nio.file.*;
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
        var unsupported = new SafeResultFilePublisher((source, destination) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test");
        }, path -> Files.deleteIfExists(path), path -> fail("cleanup"));
        assertThrows(SafeResultFilePublisher.Failure.class, () -> unsupported.publish(
                SafeResultFilePublisher.capture(target), new ResultExportOperation(),
                (path, operation) -> Files.writeString(path, "new")));
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
        Path target = directory.resolve("result.csv");
        var operation = new ResultExportOperation();
        Path published = publisher().publish(SafeResultFilePublisher.capture(target), operation,
                (path, token) -> Files.writeString(path, "new"));
        assertEquals(target, published);
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
}
