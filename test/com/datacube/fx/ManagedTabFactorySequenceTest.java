package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedTabFactorySequenceTest {

    @Test
    void bindsAbortThenInitializesHistoryBeforeReturningSpec() {
        List<String> events = new ArrayList<>();

        String spec = ManagedTabFactorySequence.create(
                () -> {
                    events.add("construct");
                    return "pane";
                },
                pane -> events.add("bind-abort:" + pane),
                pane -> events.add("set-sql:" + pane),
                pane -> {
                    events.add("spec:" + pane);
                    return "managed-spec";
                });

        assertEquals("managed-spec", spec);
        assertEquals(List.of("construct", "bind-abort:pane", "set-sql:pane", "spec:pane"), events);
    }

    @Test
    void initializationFailureOccursOnlyAfterAbortOwnershipWasBound() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> ManagedTabFactorySequence.create(
                () -> "pane",
                pane -> events.add("bound"),
                pane -> { throw new IllegalStateException("set sql"); },
                pane -> "spec"));

        assertEquals(List.of("bound"), events);
    }
}
