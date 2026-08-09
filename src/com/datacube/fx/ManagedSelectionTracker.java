package com.datacube.fx;

import java.util.List;

/** Synchronously remembers the selection transition immediately preceding a tab-list mutation. */
final class ManagedSelectionTracker<T> {
    private T current;

    void changed(T before, T after) {
        current = after;
    }

    T originalSelection(List<? extends T> removed, T current) {
        if (current != null && removed.contains(current)) return current;
        return this.current != null && removed.contains(this.current) ? this.current : null;
    }
}
