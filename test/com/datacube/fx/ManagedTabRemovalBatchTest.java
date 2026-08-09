package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManagedTabRemovalBatchTest {

    @Test
    void restoresRemovedTabsAtOriginalRelativePositionsAndSelectionOnce() {
        List<String> tabs = new ArrayList<>(List.of("A", "D"));
        List<String> disabled = new ArrayList<>();
        AtomicReference<String> selection = new AtomicReference<>();
        AtomicInteger selections = new AtomicInteger();
        ManagedTabRemovalBatch<String> batch = ManagedTabRemovalBatch.capture(
                1, List.of("B", "C"), "C");

        batch.restoreInto(tabs, disabled::add, selected -> {
            selection.set(selected);
            selections.incrementAndGet();
        });

        assertEquals(List.of("A", "B", "C", "D"), tabs);
        assertEquals(List.of("B", "C"), disabled);
        assertEquals("C", selection.get());
        assertEquals(1, selections.get());
    }

    @Test
    void preservesExistingSelectionWhenOriginalSelectionWasNotRemoved() {
        List<String> tabs = new ArrayList<>(List.of("A", "D"));
        AtomicReference<String> selection = new AtomicReference<>();
        ManagedTabRemovalBatch<String> batch = ManagedTabRemovalBatch.capture(
                1, List.of("B", "C"), "A");

        batch.restoreInto(tabs, ignored -> {}, selection::set);

        assertEquals(List.of("A", "B", "C", "D"), tabs);
        assertNull(selection.get());
    }

    @Test
    void insertionIndexIsClampedWhenOtherMutationsAlreadyShortenedList() {
        List<String> tabs = new ArrayList<>(List.of("A"));
        ManagedTabRemovalBatch<String> batch = ManagedTabRemovalBatch.capture(
                9, List.of("B", "C"), null);

        batch.restoreInto(tabs, ignored -> {}, ignored -> {});

        assertEquals(List.of("A", "B", "C"), tabs);
    }
}
