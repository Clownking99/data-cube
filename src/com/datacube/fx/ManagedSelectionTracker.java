package com.datacube.fx;

import java.util.List;

/** Synchronously remembers the selection transition immediately preceding a tab-list mutation. */
final class ManagedSelectionTracker<T> {
    private T previous;

    void changed(T before, T after) {
        previous = before;
    }

    T originalSelection(List<? extends T> removed, T current) {
        if (current != null && removed.contains(current)) return current;
        return previous != null && removed.contains(previous) ? previous : null;
    }
}
