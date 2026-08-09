package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncCloseGateTest {

    @Test
    void suppressesDuplicateRequestsAndClosesOnlyAfterApproval() {
        AsyncCloseGate gate = new AsyncCloseGate();
        AtomicInteger closes = new AtomicInteger();

        assertTrue(gate.beginRequest());
        assertFalse(gate.beginRequest());
        gate.complete(false, closes::incrementAndGet);
        assertEquals(0, closes.get());

        assertTrue(gate.beginRequest());
        gate.complete(true, closes::incrementAndGet);
        gate.complete(true, closes::incrementAndGet);
        assertEquals(1, closes.get());
    }
}
