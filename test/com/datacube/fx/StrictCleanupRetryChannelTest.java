package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictCleanupRetryChannelTest {

    @Test
    void firstStrictCloseFailureRetriesOnVirtualThreadUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean virtual = new AtomicBoolean(true);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        StrictCleanupRetryChannel channel = new StrictCleanupRetryChannel(() -> {
            virtual.compareAndSet(true, Thread.currentThread().isVirtual());
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("first close failed");
        }, failures::add, Duration.ofMillis(1));

        channel.start().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(virtual.get());
        assertTrue(attempts.get() >= 2);
        assertTrue(failures.stream().anyMatch(failure ->
                "first close failed".equals(failure.getMessage())));
    }

    @Test
    void continuingFailureKeepsOneObservableUnsettledResponsibility() throws Exception {
        AtomicBoolean allowSuccess = new AtomicBoolean();
        CountDownLatch failedTwice = new CountDownLatch(2);
        StrictCleanupRetryChannel channel = new StrictCleanupRetryChannel(() -> {
            if (!allowSuccess.get()) {
                failedTwice.countDown();
                throw new IllegalStateException("still owned");
            }
        }, ignored -> {}, Duration.ofMillis(1));

        var first = channel.start();
        assertTrue(failedTwice.await(2, TimeUnit.SECONDS));
        assertFalse(first.toCompletableFuture().isDone());
        assertSame(first, channel.start());

        allowSuccess.set(true);
        first.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
}
