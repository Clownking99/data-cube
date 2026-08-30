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
}
