package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncManagedTabRegistryTest {

    @Test
    void closeAllAggregatesBooleanAndReopensAfterRejection() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        registry.register(new Object(), immediateCoordinator(true));
        registry.register(new Object(), immediateCoordinator(false));

        assertFalse(registry.closeAll().toCompletableFuture().join());

        registry.register(new Object(), immediateCoordinator(true));
    }

    @Test
    void registerRacingCloseAllIsEitherIncludedOrRejected() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
            CompletableFuture<Boolean> firstCleanup = new CompletableFuture<>();
            registry.register(new Object(), coordinator(() -> firstCleanup));
            AtomicInteger secondGuardCalls = new AtomicInteger();
            Object second = new Object();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean registered = new AtomicBoolean();
            AtomicReference<CompletionStage<Boolean>> closeAll = new AtomicReference<>();

            Thread closer = Thread.startVirtualThread(() -> {
                await(start);
                closeAll.set(registry.closeAll());
            });
            Thread registrar = Thread.startVirtualThread(() -> {
                await(start);
                try {
                    registry.register(second, coordinator(() -> {
                        secondGuardCalls.incrementAndGet();
                        return CompletableFuture.completedFuture(true);
                    }));
                    registered.set(true);
                } catch (IllegalStateException closing) {
                    // Explicit rejection is the other valid linearization.
                }
            });

            start.countDown();
            closer.join(2_000);
            registrar.join(2_000);
            firstCleanup.complete(true);

            assertTrue(closeAll.get().toCompletableFuture().join());
            assertEquals(registered.get() ? 1 : 0, secondGuardCalls.get());
        }
    }

    @Test
    void timeoutMakesCloseAllFalseAndLateCleanupMustFinishBeforeRetryStarts() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        Object tab = new Object();
        CompletableFuture<Boolean> firstCleanup = new CompletableFuture<>();
        CompletableFuture<Boolean> secondCleanup = new CompletableFuture<>();
        AtomicReference<CompletionStage<Boolean>> current = new AtomicReference<>(firstCleanup);
        ManualTimeoutScheduler timeouts = new ManualTimeoutScheduler();
        AtomicInteger guardCalls = new AtomicInteger();
        AtomicInteger finalizers = new AtomicInteger();
        AsyncTabCloseCoordinator coordinator = coordinator(() -> {
            guardCalls.incrementAndGet();
            return current.get();
        }, timeouts, () -> {
            registry.unregister(tab);
            finalizers.incrementAndGet();
        });
        registry.register(tab, coordinator);

        CompletionStage<Boolean> firstCloseAll = registry.closeAll();
        timeouts.fireNext();
        assertFalse(firstCloseAll.toCompletableFuture().join());

        current.set(secondCleanup);
        assertFalse(registry.closeAll().toCompletableFuture().join());
        assertEquals(1, guardCalls.get());

        firstCleanup.complete(true);
        CompletionStage<Boolean> retryCloseAll = registry.closeAll();
        secondCleanup.complete(true);

        assertTrue(retryCloseAll.toCompletableFuture().join());
        assertEquals(2, guardCalls.get());
        assertEquals(1, finalizers.get());
    }

    @Test
    void closingRegistryRejectsNewRegistrationUntilFalseResultReopensIt() {
        AsyncManagedTabRegistry<Object> registry = new AsyncManagedTabRegistry<>();
        CompletableFuture<Boolean> cleanup = new CompletableFuture<>();
        registry.register(new Object(), coordinator(() -> cleanup));

        CompletionStage<Boolean> closing = registry.closeAll();
        assertThrows(IllegalStateException.class,
                () -> registry.register(new Object(), immediateCoordinator(true)));
        cleanup.complete(false);
        assertFalse(closing.toCompletableFuture().join());

        registry.register(new Object(), immediateCoordinator(true));
    }

    private static AsyncTabCloseCoordinator immediateCoordinator(boolean approved) {
        return coordinator(() -> CompletableFuture.completedFuture(approved));
    }

    private static AsyncTabCloseCoordinator coordinator(AsyncTabCloseGuard guard) {
        return coordinator(guard, new ManualTimeoutScheduler(), () -> {});
    }

    private static AsyncTabCloseCoordinator coordinator(
            AsyncTabCloseGuard guard,
            ManualTimeoutScheduler timeouts,
            Runnable finalizer) {
        return new AsyncTabCloseCoordinator(
                guard,
                Duration.ofSeconds(5),
                timeouts,
                Runnable::run,
                () -> {},
                finalizer,
                ignored -> {});
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class ManualTimeoutScheduler
            implements AsyncTabCloseCoordinator.TimeoutScheduler {
        private final List<ScheduledTimeout> scheduled = new ArrayList<>();

        @Override
        public AsyncTabCloseCoordinator.TimeoutHandle schedule(Duration delay, Runnable task) {
            ScheduledTimeout timeout = new ScheduledTimeout(task);
            scheduled.add(timeout);
            return timeout;
        }

        void fireNext() {
            scheduled.removeFirst().fire();
        }
    }

    private static final class ScheduledTimeout implements AsyncTabCloseCoordinator.TimeoutHandle {
        private final Runnable task;
        private boolean cancelled;

        private ScheduledTimeout(Runnable task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void fire() {
            if (!cancelled) task.run();
        }
    }
}
