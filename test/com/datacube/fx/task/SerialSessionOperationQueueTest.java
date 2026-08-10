package com.datacube.fx.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialSessionOperationQueueTest {

    @Test
    void manualModeThenExecuteRunFifoOnOneVirtualThreadAtATime() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner();
             SerialSessionOperationQueue queue =
                     new SerialSessionOperationQueue(runner, Runnable::run)) {
            CountDownLatch manualStarted = new CountDownLatch(1);
            CountDownLatch releaseManual = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();
            AtomicBoolean virtual = new AtomicBoolean(true);

            var manual = queue.submit(SerialSessionOperationQueue.OperationKind.SET_MODE, () -> {
                virtual.compareAndSet(true, Thread.currentThread().isVirtual());
                order.add("manual");
                manualStarted.countDown();
                releaseManual.await();
                return "manual";
            }, ignored -> {}, fail());
            assertTrue(manualStarted.await(2, TimeUnit.SECONDS));
            var execute = queue.submit(SerialSessionOperationQueue.OperationKind.EXECUTE, () -> {
                virtual.compareAndSet(true, Thread.currentThread().isVirtual());
                order.add("execute");
                return "execute";
            }, ignored -> {}, fail());

            assertTrue(queue.snapshot().running());
            assertEquals(SerialSessionOperationQueue.OperationKind.SET_MODE,
                    queue.snapshot().currentKind());
            assertFalse(queue.snapshot().currentCancellable());
            assertEquals(1, queue.snapshot().queued());
            releaseManual.countDown();

            assertEquals("manual", manual.get(2, TimeUnit.SECONDS));
            assertEquals("execute", execute.get(2, TimeUnit.SECONDS));
            queue.idle().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(List.of("manual", "execute"), order);
            assertTrue(virtual.get());
            assertFalse(queue.snapshot().pending());
        }
    }

    @Test
    void closingCancelsQueuedCommitThenCloseRollbackRunsAfterCurrentBecomesIdle() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner();
             SerialSessionOperationQueue queue =
                     new SerialSessionOperationQueue(runner, Runnable::run)) {
            CountDownLatch executeStarted = new CountDownLatch(1);
            CountDownLatch releaseExecute = new CountDownLatch(1);
            AtomicBoolean commitRan = new AtomicBoolean();
            List<String> order = new CopyOnWriteArrayList<>();

            var execute = queue.submit(SerialSessionOperationQueue.OperationKind.EXECUTE, () -> {
                executeStarted.countDown();
                releaseExecute.await();
                order.add("execute");
                return "execute";
            }, ignored -> {}, fail());
            assertTrue(executeStarted.await(2, TimeUnit.SECONDS));
            var commit = queue.submit(SerialSessionOperationQueue.OperationKind.COMMIT, () -> {
                commitRan.set(true);
                return "commit";
            }, ignored -> {}, fail());

            var idle = queue.stopAcceptingAndCancelQueued();

            assertTrue(commit.isCancelled());
            assertFalse(commitRan.get());
            assertFalse(idle.toCompletableFuture().isDone());
            assertThrows(RejectedExecutionException.class,
                    () -> queue.submit(SerialSessionOperationQueue.OperationKind.ROLLBACK,
                            () -> "late", ignored -> {}, fail()));
            releaseExecute.countDown();
            assertEquals("execute", execute.get(2, TimeUnit.SECONDS));
            idle.toCompletableFuture().get(2, TimeUnit.SECONDS);
            order.add("close-rollback");
            assertEquals(List.of("execute", "close-rollback"), order);
            assertFalse(queue.snapshot().pending());
        }
    }

    @Test
    void rejectedCloseCanReopenAnIdleQueue() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner();
             SerialSessionOperationQueue queue =
                     new SerialSessionOperationQueue(runner, Runnable::run)) {
            queue.stopAcceptingAndCancelQueued().toCompletableFuture().join();
            queue.reopen();

            assertEquals("retry", queue.submit(SerialSessionOperationQueue.OperationKind.EXECUTE,
                            () -> "retry", ignored -> {}, fail())
                    .get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void currentCommitIsExplicitlyNonCancellable() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner();
             SerialSessionOperationQueue queue =
                     new SerialSessionOperationQueue(runner, Runnable::run)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var commit = queue.submit(SerialSessionOperationQueue.OperationKind.COMMIT, () -> {
                started.countDown();
                release.await();
                return null;
            }, ignored -> {}, fail());
            assertTrue(started.await(2, TimeUnit.SECONDS));

            SerialSessionOperationQueue.Snapshot snapshot = queue.snapshot();
            assertEquals(SerialSessionOperationQueue.OperationKind.COMMIT, snapshot.currentKind());
            assertFalse(snapshot.currentCancellable());

            release.countDown();
            commit.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopAcceptingPreservesCurrentTerminalCallbackForRejectedClose() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner();
             SerialSessionOperationQueue queue =
                     new SerialSessionOperationQueue(runner, Runnable::run)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch callback = new CountDownLatch(1);
            AtomicReference<String> visibleResult = new AtomicReference<>();
            var execute = queue.submit(SerialSessionOperationQueue.OperationKind.EXECUTE, () -> {
                started.countDown();
                release.await();
                return "visible-result";
            }, result -> {
                visibleResult.set(result);
                callback.countDown();
            }, fail());
            assertTrue(started.await(2, TimeUnit.SECONDS));

            queue.stopAcceptingAndCancelQueued();
            release.countDown();

            assertEquals("visible-result", execute.get(2, TimeUnit.SECONDS));
            assertTrue(callback.await(2, TimeUnit.SECONDS));
            queue.reopen();
            assertEquals("visible-result", visibleResult.get());
        }
    }

    private static java.util.function.Consumer<Throwable> fail() {
        return failure -> { throw new AssertionError(failure); };
    }
}
