package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCloseGateTest {

    @Test
    void staleApprovalCannotConsumeNewerPendingRequest() {
        AsyncCloseGate gate = new AsyncCloseGate();
        AtomicInteger closes = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        AsyncCloseGate.Request first = gate.beginRequest();
        assertNotNull(first);
        assertNull(gate.beginRequest());
        assertTrue(gate.complete(first, false, closes::incrementAndGet, failures::add));

        AsyncCloseGate.Request second = gate.beginRequest();
        assertNotNull(second);
        assertFalse(gate.complete(first, true, closes::incrementAndGet, failures::add));
        assertEquals(0, closes.get());

        assertTrue(gate.complete(second, true, closes::incrementAndGet, failures::add));
        assertNull(gate.beginRequest());
        assertEquals(1, closes.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void concurrentCompletionsInvokeCloseActionOnce() throws Exception {
        AsyncCloseGate gate = new AsyncCloseGate();
        AsyncCloseGate.Request request = gate.beginRequest();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 64; i++) {
            threads.add(Thread.startVirtualThread(() -> {
                await(start);
                if (gate.complete(request, true, closes::incrementAndGet, ignored -> {})) {
                    accepted.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) thread.join(2_000);

        assertEquals(1, accepted.get());
        assertEquals(1, closes.get());
    }

    @Test
    void reportsCloseActionFailureAndRemainsClosed() {
        AsyncCloseGate gate = new AsyncCloseGate();
        AsyncCloseGate.Request request = gate.beginRequest();
        List<Throwable> failures = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("finalizer failed");

        assertTrue(gate.complete(request, true, () -> { throw failure; }, failures::add));

        assertEquals(List.of(failure), failures);
        assertNull(gate.beginRequest());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
