package com.datacube.export;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultExportSessionTest {
    @Test void cancellationWinsBeforePublicationAndCloseSealsAdmission() throws Exception {
        var session = new ResultExportSession();
        var operation = session.begin();
        assertNotNull(operation);
        assertNull(session.begin());
        session.close();
        var moves = new AtomicInteger();
        assertThrows(CancellationException.class, () -> operation.publish(moves::incrementAndGet));
        assertEquals(0, moves.get());
        assertNull(session.begin());
        assertTrue(session.isClosed());
    }

    @Test void publicationWinsAndLateFinishCannotClearNewOwner() throws Exception {
        var session = new ResultExportSession();
        var first = session.begin();
        first.publish(() -> {});
        assertTrue(first.published());
        assertFalse(first.cancel());
        session.finish(first);
        var second = session.begin();
        session.finish(first);
        assertTrue(session.isBusy());
        assertNotSame(first, second);
        session.finish(second);
        assertFalse(session.isBusy());
    }

    @Test void cancellationCannotUndoAnAtomicPublicationAlreadyInsideTheGate() throws Exception {
        var operation = new ResultExportOperation();
        var publishing = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cancelCalled = new java.util.concurrent.CountDownLatch(1);
        var committed = new AtomicInteger();
        var publication = new java.util.concurrent.FutureTask<Void>(() -> {
            operation.publish(() -> {
                publishing.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                committed.incrementAndGet();
            });
            return null;
        });
        Thread.ofVirtual().start(publication);
        try {
            assertTrue(publishing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var cancellation = new java.util.concurrent.FutureTask<Boolean>(() -> {
                cancelCalled.countDown();
                return operation.cancel();
            });
            Thread.ofVirtual().start(cancellation);
            assertTrue(cancelCalled.await(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            publication.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(cancellation.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, committed.get());
            assertTrue(operation.published());
        } finally {
            release.countDown();
            publication.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
