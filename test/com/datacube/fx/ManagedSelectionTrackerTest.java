package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManagedSelectionTrackerTest {

    @Test
    void doesNotRestoreASelectionThatIsNoLongerCurrent() {
        ManagedSelectionTracker<String> tracker = new ManagedSelectionTracker<>();
        tracker.changed("A", "B", true);
        tracker.changed("B", "C", true);

        assertNull(tracker.originalSelection(List.of("B"), "C"));
    }

    @Test
    void unrelatedRemovalDoesNotForceASelectionRestore() {
        ManagedSelectionTracker<String> tracker = new ManagedSelectionTracker<>();
        tracker.changed("A", "B", true);

        assertNull(tracker.originalSelection(List.of("C"), "B"));
    }

    @Test
    void sameTurnSelectionChangeThenRemovingPreviousTabDoesNotRestoreIt() {
        ManagedSelectionTracker<String> tracker = new ManagedSelectionTracker<>();
        tracker.changed("A", "B", true);

        assertNull(tracker.originalSelection(List.of("A"), "B"));
    }

    @Test
    void removingCurrentTabCapturesItForRestoration() {
        ManagedSelectionTracker<String> tracker = new ManagedSelectionTracker<>();
        tracker.changed("A", "B", true);

        assertEquals("B", tracker.originalSelection(List.of("B"), null));
    }

    @Test
    void removalDrivenSelectionTransitionRestoresDisplacedCurrentTab() {
        ManagedSelectionTracker<String> tracker = new ManagedSelectionTracker<>();
        tracker.changed("A", "B", true);
        tracker.changed("B", "C", false);

        assertEquals("B", tracker.originalSelection(List.of("B"), "C"));
        assertNull(tracker.originalSelection(List.of("B"), "C"));
    }
}
