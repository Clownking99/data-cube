package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncShutdownCoordinatorTest {

    @Test
    void cancelledCloseAllSkipsTeardownAndAllowsRetry() {
        AtomicReference<CompletionStage<TabCloseOutcome>> tabs =
                new AtomicReference<>(CompletableFuture.completedFuture(TabCloseOutcome.CANCELLED));
        Queue<Runnable> workers = new ArrayDeque<>();
        AtomicInteger teardowns = new AtomicInteger();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                tabs::get, workers::add, teardowns::incrementAndGet, ignored -> {});

        assertEquals(ShutdownOutcome.CANCELLED, shutdown.shutdown().toCompletableFuture().join());
        assertEquals(0, teardowns.get());
        tabs.set(CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED));
        CompletionStage<ShutdownOutcome> retry = shutdown.shutdown();
        workers.remove().run();

        assertEquals(ShutdownOutcome.COMPLETED, retry.toCompletableFuture().join());
        assertEquals(1, teardowns.get());
    }

    @Test
    void exceptionalCloseAllAndThreadStartFailureBothResetForRetry() {
        CompletableFuture<TabCloseOutcome> failedTabs = new CompletableFuture<>();
        failedTabs.completeExceptionally(new IllegalStateException("tabs"));
        AtomicReference<CompletionStage<TabCloseOutcome>> tabs = new AtomicReference<>(failedTabs);
        AtomicBoolean failStart = new AtomicBoolean(true);
        Queue<Runnable> workers = new ArrayDeque<>();
        List<Throwable> failures = new ArrayList<>();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                tabs::get,
                task -> {
                    if (failStart.getAndSet(false)) throw new IllegalStateException("start");
                    workers.add(task);
                },
                () -> {},
                failures::add);

        assertThrows(CompletionException.class, () -> shutdown.shutdown().toCompletableFuture().join());
        tabs.set(CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED));
        assertThrows(CompletionException.class, () -> shutdown.shutdown().toCompletableFuture().join());
        CompletionStage<ShutdownOutcome> retry = shutdown.shutdown();
        workers.remove().run();

        assertEquals(ShutdownOutcome.COMPLETED, retry.toCompletableFuture().join());
        assertEquals(2, failures.size());
    }

    @Test
    void teardownFailureIsFatalPartialAndNeverMasqueradesAsSuccessOrRetryable() {
        Queue<Runnable> workers = new ArrayDeque<>();
        IllegalStateException failure = new IllegalStateException("teardown");
        List<Throwable> failures = new ArrayList<>();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                () -> CompletableFuture.completedFuture(TabCloseOutcome.COMPLETED),
                workers::add,
                () -> { throw failure; },
                failures::add);

        CompletionStage<ShutdownOutcome> first = shutdown.shutdown();
        workers.remove().run();

        assertEquals(ShutdownOutcome.FAILED_PARTIAL, first.toCompletableFuture().join());
        assertSame(first, shutdown.shutdown());
        assertEquals(List.of(failure), failures);
    }

    @Test
    void fatalTabCloseDoesNotStartApplicationTeardown() {
        AtomicInteger teardowns = new AtomicInteger();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                () -> CompletableFuture.completedFuture(TabCloseOutcome.FAILED_PARTIAL),
                Runnable::run,
                teardowns::incrementAndGet,
                ignored -> {});

        assertEquals(ShutdownOutcome.FAILED_PARTIAL, shutdown.shutdown().toCompletableFuture().join());
        assertEquals(0, teardowns.get());
    }

    @Test
    void duplicateShutdownRequestsShareOneAttempt() {
        CompletableFuture<TabCloseOutcome> tabs = new CompletableFuture<>();
        Queue<Runnable> workers = new ArrayDeque<>();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                () -> tabs, workers::add, () -> {}, ignored -> {});

        CompletionStage<ShutdownOutcome> first = shutdown.shutdown();
        assertSame(first, shutdown.shutdown());
        tabs.complete(TabCloseOutcome.COMPLETED);
        workers.remove().run();
        assertEquals(ShutdownOutcome.COMPLETED, first.toCompletableFuture().join());
    }
}
