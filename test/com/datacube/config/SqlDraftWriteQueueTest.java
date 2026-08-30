package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SqlDraftWriteQueueTest {
    @TempDir Path temp;
    private static SqlDraft draft(int id, String sql) {
        return new SqlDraft(new UUID(0, id), 100, null, null, null, null, sql);
    }
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        int submissions;
        @Override public void execute(Runnable task) { submissions++; tasks.add(task); }
        void drain() {
            assertEquals(1, tasks.size(), "only one drain runnable may be scheduled");
            tasks.remove().run();
            assertTrue(tasks.isEmpty());
        }
    }
    private static void reason(SqlDraftWriteQueue.Reason expected, CompletableFuture<?> future) {
        assertTrue(future.isDone(), "no unresolved queue outcome");
        Throwable cause = assertThrows(CompletionException.class, future::join).getCause();
        SqlDraftWriteQueue.Failure failure = assertInstanceOf(SqlDraftWriteQueue.Failure.class, cause);
        assertEquals(expected, failure.reason());
        assertNull(failure.getCause());
    }

    @Test void oneThousandPendingVersionsRetainOnlyLatestAndSettleSupersededFutures() {
        ManualExecutor executor = new ManualExecutor();
        List<SqlDraft> writes = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, writes::add);
        CompletableFuture<Void> previous = queue.save(draft(1, "version 0"));
        for (int i = 1; i <= 1000; i++) {
            CompletableFuture<Void> latest = queue.save(draft(1, "version " + i));
            reason(SqlDraftWriteQueue.Reason.SUPERSEDED, previous);
            previous = latest;
        }
        assertEquals(1, executor.submissions);
        assertFalse(previous.isDone());
        executor.drain();
        previous.join();
        assertEquals(List.of(draft(1, "version 1000")), writes);
    }

    @Test void independentIdsSurviveAndNewestReplacementOccupiesCurrentTail() {
        ManualExecutor executor = new ManualExecutor();
        List<SqlDraft> writes = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, writes::add);
        CompletableFuture<Void> old = queue.save(draft(1, "old"));
        CompletableFuture<Void> other = queue.save(draft(2, "other"));
        CompletableFuture<Void> latest = queue.save(draft(1, "latest"));
        reason(SqlDraftWriteQueue.Reason.SUPERSEDED, old);
        executor.drain();
        other.join(); latest.join();
        assertEquals(List.of(draft(2, "other"), draft(1, "latest")), writes);
    }

    @Test void clearCancelsPendingAndPostBarrierSaveCannotMoveBeforeAction() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> events.add(value.sql()));
        CompletableFuture<Void> old = queue.save(draft(1, "old"));
        CompletableFuture<Integer> cleared = queue.barrierAll(() -> { events.add("clear"); return 7; });
        reason(SqlDraftWriteQueue.Reason.CANCELLED, old);
        CompletableFuture<Void> after = queue.save(draft(1, "after"));
        assertFalse(cleared.isDone());
        executor.drain();
        assertEquals(7, cleared.join()); after.join();
        assertEquals(List.of("clear", "after"), events);
    }

    @Test void targetedDeleteKeepsOtherIdsAndSerializesNewTargetSnapshotAfterIt() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> events.add(value.sql()));
        CompletableFuture<Void> target = queue.save(draft(1, "old target"));
        CompletableFuture<Void> other = queue.save(draft(2, "other"));
        CompletableFuture<Void> deleted = queue.barrier(Set.of(new UUID(0, 1)), () -> { events.add("delete"); return null; });
        reason(SqlDraftWriteQueue.Reason.CANCELLED, target);
        CompletableFuture<Void> after = queue.save(draft(1, "new target"));
        executor.drain();
        other.join(); deleted.join(); after.join();
        assertEquals(List.of("other", "delete", "new target"), events);
    }

    @Test void clearDuringRunningWriteWaitsForItAndCancelsOnlyPendingVersion() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        AtomicReference<SqlDraftWriteQueue> reference = new AtomicReference<>();
        List<CompletableFuture<?>> completions = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> {
            events.add(value.sql());
            if (value.sql().equals("running")) {
                SqlDraftWriteQueue active = reference.get();
                CompletableFuture<Void> pending = active.save(draft(1, "pending"));
                completions.add(active.barrierAll(() -> { events.add("clear"); return null; }));
                reason(SqlDraftWriteQueue.Reason.CANCELLED, pending);
                completions.add(active.save(draft(1, "after")));
                events.add("running finished");
            }
        });
        reference.set(queue);
        CompletableFuture<Void> running = queue.save(draft(1, "running"));
        executor.drain();
        running.join(); completions.forEach(CompletableFuture::join);
        assertEquals(List.of("running", "running finished", "clear", "after"), events);
        assertEquals(1, executor.submissions);
    }

    @Test void failedWriteSettlesItsFutureWithoutRetryingOrDroppingOtherOperations() {
        ManualExecutor executor = new ManualExecutor();
        List<String> events = new ArrayList<>();
        IOException fault = new IOException("synthetic failure");
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, value -> {
            events.add(value.sql());
            if (value.sql().equals("failed")) throw fault;
        });
        CompletableFuture<Void> failed = queue.save(draft(1, "failed"));
        CompletableFuture<Void> good = queue.save(draft(2, "good"));
        CompletableFuture<Integer> read = queue.barrier(Set.of(), () -> 2);
        executor.drain();
        assertSame(fault, assertThrows(CompletionException.class, failed::join).getCause());
        good.join(); assertEquals(2, read.join());
        assertEquals(List.of("failed", "good"), events);
        assertEquals(1, executor.submissions);
    }

    @Test void failedBarrierDoesNotPretendSuccessAndLaterActionStillRuns() {
        ManualExecutor executor = new ManualExecutor();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, ignored -> fail("unexpected write"));
        IOException fault = new IOException("synthetic barrier failure");
        CompletableFuture<Void> failed = queue.barrierAll(() -> { throw fault; });
        CompletableFuture<Integer> later = queue.barrier(Set.of(), () -> 5);
        executor.drain();
        assertSame(fault, assertThrows(CompletionException.class, failed::join).getCause());
        assertEquals(5, later.join());
    }

    @Test void unexpectedWriterErrorStillSettlesSaveAndDrainFutures() {
        ManualExecutor executor = new ManualExecutor();
        AssertionError fault = new AssertionError("synthetic writer error");
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, ignored -> { throw fault; });
        CompletableFuture<Void> saved = queue.save(draft(1, "last"));
        CompletableFuture<Void> drained = queue.drainAndClose();
        executor.drain();
        assertSame(fault, assertThrows(CompletionException.class, saved::join).getCause());
        assertTrue(drained.isDone());
        drained.join();
    }

    @Test void closeDrainsAcceptedJobsAndRejectsNewJobsWithoutClosingExternalExecutor() {
        ManualExecutor executor = new ManualExecutor();
        List<SqlDraft> writes = new ArrayList<>();
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, writes::add);
        CompletableFuture<Void> saved = queue.save(draft(1, "last"));
        CompletableFuture<Void> drained = queue.drainAndClose();
        assertSame(drained, queue.drainAndClose());
        assertFalse(drained.isDone());
        reason(SqlDraftWriteQueue.Reason.CLOSED, queue.save(draft(2, "rejected")));
        reason(SqlDraftWriteQueue.Reason.CLOSED, queue.barrierAll(() -> fail("closed action ran")));
        executor.drain();
        saved.join(); drained.join();
        assertEquals(List.of(draft(1, "last")), writes);
        executor.execute(() -> writes.add(draft(3, "external owner")));
        executor.drain();
        assertEquals(2, writes.size());
    }

    @Test void rejectedExecutorSettlesQueueAndDoesNotLeakItsErrorMessage() {
        SqlDraftWriteQueue queue = new SqlDraftWriteQueue(
                ignored -> { throw new RejectedExecutionException("synthetic private SQL"); },
                ignored -> fail("rejected executor wrote"));
        CompletableFuture<Void> first = queue.save(draft(1, "private SQL"));
        reason(SqlDraftWriteQueue.Reason.REJECTED, first);
        Throwable failure = assertThrows(CompletionException.class, first::join).getCause();
        assertFalse(failure.toString().contains("private SQL"));
        reason(SqlDraftWriteQueue.Reason.CLOSED, queue.save(draft(2, "second")));
        reason(SqlDraftWriteQueue.Reason.REJECTED, queue.drainAndClose());
    }

    @Test void isolatedRealStoreClearCannotResurrectOldQueuedText() throws Exception {
        try (SqlDraftStore store = SqlDraftStore.open(temp.resolve("drafts"))) {
            store.save(draft(1, "old published"));
            ManualExecutor executor = new ManualExecutor();
            SqlDraftWriteQueue queue = new SqlDraftWriteQueue(executor, store::save);
            CompletableFuture<Void> obsolete = queue.save(draft(1, "old queued"));
            CompletableFuture<Integer> clear = queue.barrierAll(store::clearRecoverable);
            CompletableFuture<Void> latest = queue.save(draft(2, "new edit"));
            CompletableFuture<Void> drain = queue.drainAndClose();
            reason(SqlDraftWriteQueue.Reason.CANCELLED, obsolete);
            executor.drain();
            assertEquals(1, clear.join()); latest.join(); drain.join();
            assertEquals(List.of(draft(2, "new edit")), store.snapshot().drafts());
        }
    }
}
