package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BestEffortCloseSequenceTest {

    @Test
    void attemptsEveryStepAndAggregatesFailures() {
        List<String> calls = new ArrayList<>();
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");

        PartialCloseException thrown = assertThrows(PartialCloseException.class, () ->
                BestEffortCloseSequence.run(
                        () -> { calls.add("one"); throw first; },
                        () -> calls.add("two"),
                        () -> { calls.add("three"); throw second; }));

        assertEquals(List.of("one", "two", "three"), calls);
        assertSame(first, thrown.getCause());
        assertEquals(List.of(second), List.of(thrown.getSuppressed()));
    }
}
