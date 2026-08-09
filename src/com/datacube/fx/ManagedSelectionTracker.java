package com.datacube.fx;

import java.util.List;

/** Synchronously remembers the selection transition immediately preceding a tab-list mutation. */
final class ManagedSelectionTracker<T> {
    private T current;
    private T displaced;

    void changed(T before, T after, boolean beforePresent) {
        current = after;
        displaced = beforePresent ? null : before;
    }

    T originalSelection(List<? extends T> removed, T current) {
        T removalDriven = displaced;
        displaced = null;
        if (current != null && removed.contains(current)) return current;
        if (this.current != null && removed.contains(this.current)) return this.current;
        return removalDriven != null && removed.contains(removalDriven) ? removalDriven : null;
    }
}
