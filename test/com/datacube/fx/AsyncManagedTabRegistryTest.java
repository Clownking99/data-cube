package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncManagedTabRegistryTest {

    @Test
    void programmaticRemovalStartsGuardAndFinalizesAfterApproval() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        Object tab = new Object();
        CompletableFuture<Boolean> cleanup = new CompletableFuture<>();
        Queue<Runnable> fxTasks = new ArrayDeque<>();
        AtomicInteger guardCalls = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        AsyncTabCloseCoordinator coordinator = coordinator(
                () -> {
                    guardCalls.incrementAndGet();
                    return cleanup;
                }, fxTasks, new ArrayDeque<>(), () -> {
                    registry.unregister(tab);
                    finalizers.incrementAndGet();
                });
        registry.register(tab, coordinator);

        CompletionStage<Boolean> close = registry.requestClose(tab);
        assertEquals(1, guardCalls.get());
        cleanup.complete(true);
        assertEquals(0, finalizers.get());
        fxTasks.remove().run();

        assertTrue(close.toCompletableFuture().join());
        assertEquals(1, finalizers.get());
        assertTrue(registry.closeAll().toCompletableFuture().isDone());
    }

    @Test
    void closeAllCompletesOnTimeoutAndLateCompletionCannotConsumeRetry() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        Object tab = new Object();
        CompletableFuture<Boolean> firstCleanup = new CompletableFuture<>();
        CompletableFuture<Boolean> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<Boolean>> current = new AtomicReference<>(firstCleanup);
        Queue<Runnable> fxTasks = new ArrayDeque<>();
        Queue<Runnable> timeouts = new ArrayDeque<>();
        AtomicInteger finalizers = new AtomicInteger();
        AsyncTabCloseCoordinator coordinator = coordinator(current::get, fxTasks, timeouts, () -> {
            registry.unregister(tab);
            finalizers.incrementAndGet();
        });
        registry.register(tab, coordinator);

        CompletionStage<Void> firstCloseAll = registry.closeAll();
        timeouts.remove().run();
        assertTrue(firstCloseAll.toCompletableFuture().isDone());

        current.set(secondCleanup);
        CompletionStage<Void> retryCloseAll = registry.closeAll();
        firstCleanup.complete(true);
        assertTrue(fxTasks.isEmpty());
        secondCleanup.complete(true);
        fxTasks.remove().run();

        assertTrue(retryCloseAll.toCompletableFuture().isDone());
        assertEquals(1, finalizers.get());
    }

    private static AsyncTabCloseCoordinator coordinator(
            AsyncTabCloseGuard guard,
            Queue<Runnable> fxTasks,
            Queue<Runnable> timeouts,
            Runnable finalizer) {
        return new AsyncTabCloseCoordinator(
                guard,
                Duration.ofSeconds(5),
                (delay, task) -> timeouts.add(task),
                fxTasks::add,
                () -> {},
                finalizer,
                ignored -> {});
    }
}
