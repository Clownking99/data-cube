package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisPaneCloseSequenceTest {

    @Test
    void queueFailureStillAttemptsSessionClose() {
        List<String> calls = new ArrayList<>();

        assertThrows(PartialCloseException.class, () -> RedisPaneCloseSequence.close(
                () -> { calls.add("queue"); throw new IllegalStateException("queue"); },
                () -> calls.add("session")));

        assertEquals(List.of("queue", "session"), calls);
    }
}
