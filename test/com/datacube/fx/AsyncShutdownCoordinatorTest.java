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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncShutdownCoordinatorTest {

    @Test
    void falseCloseAllSkipsTeardownAndAllowsRetry() {
        AtomicReference<CompletionStage<Boolean>> tabs =
                new AtomicReference<>(CompletableFuture.completedFuture(false));
        Queue<Runnable> workers = new ArrayDeque<>();
        AtomicInteger teardowns = new AtomicInteger();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                tabs::get, workers::add, teardowns::incrementAndGet, ignored -> {});

        assertFalse(shutdown.shutdown().toCompletableFuture().join());
        assertEquals(0, teardowns.get());
        tabs.set(CompletableFuture.completedFuture(true));
        CompletionStage<Boolean> retry = shutdown.shutdown();
        workers.remove().run();

        assertTrue(retry.toCompletableFuture().join());
        assertEquals(1, teardowns.get());
    }

    @Test
    void exceptionalCloseAllAndThreadStartFailureBothResetForRetry() {
        CompletableFuture<Boolean> failedTabs = new CompletableFuture<>();
        failedTabs.completeExceptionally(new IllegalStateException("tabs"));
        AtomicReference<CompletionStage<Boolean>> tabs = new AtomicReference<>(failedTabs);
        AtomicBoolean failStart = new AtomicBoolean(true);
        Queue<Runnable> workers = new ArrayDeque<>();
        List<Throwable> failures = new ArrayList<>();
        AtomicInteger teardowns = new AtomicInteger();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                tabs::get,
                task -> {
                    if (failStart.getAndSet(false)) throw new IllegalStateException("start");
                    workers.add(task);
                },
                teardowns::incrementAndGet,
                failures::add);

        CompletionException tabsFailure = assertThrows(
                CompletionException.class,
                () -> shutdown.shutdown().toCompletableFuture().join());
        assertEquals("tabs", tabsFailure.getCause().getMessage());
        tabs.set(CompletableFuture.completedFuture(true));
        CompletionException startFailure = assertThrows(
                CompletionException.class,
                () -> shutdown.shutdown().toCompletableFuture().join());
        assertEquals("start", startFailure.getCause().getMessage());
        CompletionStage<Boolean> retry = shutdown.shutdown();
        workers.remove().run();

        assertTrue(retry.toCompletableFuture().join());
        assertEquals(1, teardowns.get());
        assertEquals(2, failures.size());
    }

    @Test
    void duplicateShutdownRequestsShareOneAttempt() {
        CompletableFuture<Boolean> tabs = new CompletableFuture<>();
        Queue<Runnable> workers = new ArrayDeque<>();
        AsyncShutdownCoordinator shutdown = new AsyncShutdownCoordinator(
                () -> tabs, workers::add, () -> {}, ignored -> {});

        CompletionStage<Boolean> first = shutdown.shutdown();
        assertSame(first, shutdown.shutdown());
        tabs.complete(true);
        workers.remove().run();
        assertTrue(first.toCompletableFuture().join());
    }
}
