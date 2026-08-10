package com.datacube.fx;

import com.datacube.service.JdbcEditorSession;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            if (attempts.incrementAndGet() == 1) throw retryableCloseFailure("first close failed");
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
                throw retryableCloseFailure("still owned");
            }
        }, ignored -> {}, Duration.ofMillis(1));

        var first = channel.start();
        assertTrue(failedTwice.await(2, TimeUnit.SECONDS));
        assertFalse(first.toCompletableFuture().isDone());
        assertSame(first, channel.start());

        allowSuccess.set(true);
        first.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void terminalTransactionCleanupFailureSettlesExceptionallyWithoutNoOpRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        StrictCleanupRetryChannel channel = new StrictCleanupRetryChannel(() -> {
            attempts.incrementAndGet();
            throw new JdbcEditorSession.StrictCleanupFailure(
                    JdbcEditorSession.StrictCleanupFailureKind.TERMINAL_PARTIAL,
                    new SQLException("rollback failed"));
        }, ignored -> {}, Duration.ofMillis(1));

        var settlement = channel.start();
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> settlement.toCompletableFuture().get(2, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof JdbcEditorSession.StrictCleanupFailure);
        assertEquals(1, attempts.get());
        assertSame(settlement, channel.start());
        assertTrue(settlement.toCompletableFuture().isCompletedExceptionally());
    }

    private static JdbcEditorSession.StrictCleanupFailure retryableCloseFailure(String message) {
        return new JdbcEditorSession.StrictCleanupFailure(
                JdbcEditorSession.StrictCleanupFailureKind.RETRYABLE_CONNECTION_CLOSE,
                new SQLException(message));
    }
}
