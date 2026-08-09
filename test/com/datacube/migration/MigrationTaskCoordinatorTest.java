package com.datacube.migration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationTaskCoordinatorTest {

    @Test
    void normalCompletionShutsDownExecutor() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicBoolean ran = new AtomicBoolean();
        Future<?> future = executor.submit(() -> ran.set(true));

        boolean completed = MigrationTaskCoordinator.awaitAll(
                List.of(future), executor, new MigrationCancellation(),
                Duration.ofSeconds(2));

        assertTrue(completed);
        assertTrue(ran.get());
        assertTrue(executor.isTerminated());
    }

    @Test
    void interruptionCancelsChildrenAndPreservesInterrupt() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        MigrationCancellation cancellation = new MigrationCancellation();
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch childInterrupted = new CountDownLatch(1);
        AtomicBoolean waitCompleted = new AtomicBoolean(true);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Future<?> child = executor.submit(() -> {
            childStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                childInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        Thread waiter = Thread.ofVirtual().start(() -> {
            waitCompleted.set(MigrationTaskCoordinator.awaitAll(
                    List.of(child), executor, cancellation, Duration.ofSeconds(30)));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });

        assertTrue(childStarted.await(2, TimeUnit.SECONDS));
        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(waiter.isAlive());
        assertFalse(waitCompleted.get());
        assertTrue(cancellation.isCancelled());
        assertTrue(childInterrupted.await(2, TimeUnit.SECONDS));
        assertTrue(interruptPreserved.get());
        assertTrue(executor.isTerminated());
    }
}
