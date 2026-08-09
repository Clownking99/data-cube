package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTabRemovalRecoveryTest {

    @Test
    void rejectionOrTimeoutRestoresRemovedTabOnFxDispatcher() {
        CompletableFuture<Boolean> close = new CompletableFuture<>();
        Queue<Runnable> fxTasks = new ArrayDeque<>();
        AtomicInteger restores = new AtomicInteger();

        AsyncTabRemovalRecovery.restoreOnRejection(
                close, fxTasks::add, restores::incrementAndGet, ignored -> {});
        close.complete(false);

        assertEquals(0, restores.get());
        fxTasks.remove().run();
        assertEquals(1, restores.get());
    }

    @Test
    void approvalDoesNotRestoreAndExceptionalResultIsReportedAndRestored() {
        Queue<Runnable> fxTasks = new ArrayDeque<>();
        AtomicInteger restores = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        AsyncTabRemovalRecovery.restoreOnRejection(
                CompletableFuture.completedFuture(true),
                fxTasks::add, restores::incrementAndGet, failures::add);
        assertTrue(fxTasks.isEmpty());

        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        AsyncTabRemovalRecovery.restoreOnRejection(
                failed, fxTasks::add, restores::incrementAndGet, failures::add);
        failed.completeExceptionally(new IllegalStateException("close"));
        fxTasks.remove().run();

        assertEquals(1, restores.get());
        assertEquals(1, failures.size());
    }
}
