package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManagedSelectionTrackerTest {

    @Test
    void sameTurnSelectThenRemoveCapturesSelectionBeforeRemovalTransition() {
        ManagedSelectionTracker<String> tracker = new ManagedSelectionTracker<>();
        tracker.changed("A", "B");
        tracker.changed("B", "C");

        assertEquals("B", tracker.originalSelection(List.of("B"), "C"));
    }

    @Test
    void unrelatedRemovalDoesNotForceASelectionRestore() {
        ManagedSelectionTracker<String> tracker = new ManagedSelectionTracker<>();
        tracker.changed("A", "B");

        assertNull(tracker.originalSelection(List.of("C"), "B"));
    }
}
