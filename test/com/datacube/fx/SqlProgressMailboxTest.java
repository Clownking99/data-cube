package com.datacube.fx;

import com.datacube.spi.SqlScriptProgress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlProgressMailboxTest {
    @Test void slowUiHasOnePendingCallbackAndReceivesLatestCompletedPrefix() {
        var queued = new ArrayList<Runnable>(); var rendered = new ArrayList<SqlScriptProgress>();
        var mailbox = new SqlProgressMailbox(queued::add, rendered::add);
        for (int i = 1; i <= 10_000; i++) mailbox.offer(new SqlScriptProgress(List.of(), i, 10_000, i));
        assertEquals(1, queued.size()); assertTrue(rendered.isEmpty());
        queued.removeFirst().run(); assertEquals(10_000, rendered.getFirst().completed());
        mailbox.offer(new SqlScriptProgress(List.of(), 10_001, 10_001, 1));
        assertEquals(1, queued.size()); mailbox.close(); queued.removeFirst().run();
        mailbox.offer(new SqlScriptProgress(List.of(), 2, 2, 1));
        assertTrue(queued.isEmpty()); assertEquals(1, rendered.size());
    }

    @Test void oldBatchCallbacksCannotRenderIntoANewBatch() {
        var queued = new ArrayList<Runnable>(); var rendered = new ArrayList<Integer>();
        var old = new SqlProgressMailbox(queued::add, p -> rendered.add(1));
        old.offer(new SqlScriptProgress(List.of(), 1, 1, 1)); old.close();
        var current = new SqlProgressMailbox(queued::add, p -> rendered.add(2));
        current.offer(new SqlScriptProgress(List.of(), 1, 1, 1));
        queued.forEach(Runnable::run); assertEquals(List.of(2), rendered);
    }
}
