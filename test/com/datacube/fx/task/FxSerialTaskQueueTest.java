package com.datacube.fx.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxSerialTaskQueueTest {

    @Test
    void executesTasksInSubmissionOrderWithOneNamedVirtualThreadAtATime() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner();
             FxSerialTaskQueue queue = new FxSerialTaskQueue(runner, Runnable::run)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            List<Integer> order = new CopyOnWriteArrayList<>();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicBoolean allVirtual = new AtomicBoolean(true);
            AtomicBoolean allNamed = new AtomicBoolean(true);

            Future<Integer> first = queue.submit(() -> task(1, order, active, maximum,
                    allVirtual, allNamed, firstStarted, releaseFirst), ignored -> { }, fail());
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            Future<Integer> second = queue.submit(() -> task(2, order, active, maximum,
                    allVirtual, allNamed, null, null), ignored -> { }, fail());
            Future<Integer> third = queue.submit(() -> task(3, order, active, maximum,
                    allVirtual, allNamed, null, null), ignored -> { }, fail());

            releaseFirst.countDown();
            assertEquals(1, first.get(2, TimeUnit.SECONDS));
            assertEquals(2, second.get(2, TimeUnit.SECONDS));
            assertEquals(3, third.get(2, TimeUnit.SECONDS));

            assertEquals(List.of(1, 2, 3), order);
            assertEquals(1, maximum.get());
            assertTrue(allVirtual.get());
            assertTrue(allNamed.get());
        }
    }

    @Test
    void closeInterruptsActiveTaskCancelsQueuedTaskAndSuppressesCallbacks() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner()) {
            FxSerialTaskQueue queue = new FxSerialTaskQueue(runner, Runnable::run);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicBoolean queuedRan = new AtomicBoolean();
            AtomicBoolean callback = new AtomicBoolean();

            Future<String> active = queue.submit(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    throw expected;
                }
                return "active";
            }, ignored -> callback.set(true), error -> callback.set(true));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Future<String> queued = queue.submit(() -> {
                queuedRan.set(true);
                return "queued";
            }, ignored -> callback.set(true), error -> callback.set(true));

            queue.close();

            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(active.isCancelled());
            assertTrue(queued.isCancelled());
            assertFalse(queuedRan.get());
            assertFalse(callback.get());
        }
    }

    @Test
    void cancellingQueuedTaskDoesNotBlockFollowingTask() throws Exception {
        try (FxTaskRunner runner = new FxTaskRunner();
             FxSerialTaskQueue queue = new FxSerialTaskQueue(runner, Runnable::run)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            Future<String> first = queue.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                return "first";
            }, ignored -> { }, fail());
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            Future<String> cancelled = queue.submit(() -> "cancelled", ignored -> { }, fail());
            assertTrue(cancelled.cancel(true));
            Future<String> following = queue.submit(() -> "following", ignored -> { }, fail());

            releaseFirst.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("following", following.get(2, TimeUnit.SECONDS));
            assertTrue(cancelled.isCancelled());
        }
    }

    private static int task(int value, List<Integer> order, AtomicInteger active,
                            AtomicInteger maximum, AtomicBoolean allVirtual,
                            AtomicBoolean allNamed, CountDownLatch started,
                            CountDownLatch release) throws Exception {
        int concurrent = active.incrementAndGet();
        maximum.accumulateAndGet(concurrent, Math::max);
        try {
            allVirtual.compareAndSet(true, Thread.currentThread().isVirtual());
            allNamed.compareAndSet(true, Thread.currentThread().getName().startsWith("DataCube-io-"));
            order.add(value);
            if (started != null) started.countDown();
            if (release != null) release.await();
            return value;
        } finally {
            active.decrementAndGet();
        }
    }

    private static java.util.function.Consumer<Throwable> fail() {
        return error -> { throw new AssertionError(error); };
    }
}
