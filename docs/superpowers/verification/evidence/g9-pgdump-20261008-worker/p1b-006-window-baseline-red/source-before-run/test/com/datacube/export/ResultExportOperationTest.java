package com.datacube.export;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultExportOperationTest {
    @Test void cancelReturnsBeforeBlockedPublicationIsReleased() throws Exception {
        var operation = new ResultExportOperation();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publication = new FutureTask<Void>(() -> {
            operation.publish(() -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); });
            return null;
        });
        Thread.ofVirtual().start(publication);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var cancellation = new FutureTask<Boolean>(operation::cancel);
            Thread.ofVirtual().start(cancellation);
            assertFalse(cancellation.get(1, TimeUnit.SECONDS), "Cancellation must not wait for disk I/O");
            assertFalse(operation.published());
            release.countDown();
            publication.get(5, TimeUnit.SECONDS);
            assertTrue(operation.published());
        } finally { release.countDown(); publication.get(5, TimeUnit.SECONDS); }
    }

    @Test void failedPublicationCannotReuseItsToken() throws Exception {
        var operation = new ResultExportOperation();
        assertThrows(IOException.class, () -> operation.publish(() -> { throw new IOException("fixed"); }));
        assertFalse(operation.published());
        assertFalse(operation.cancel());
        var moves = new AtomicInteger();
        assertThrows(CancellationException.class, () -> operation.publish(moves::incrementAndGet));
        assertEquals(0, moves.get());
    }
}
