package com.datacube.fx;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable model for restoring one externally removed batch without selection/order drift. */
final class ManagedTabRemovalBatch<T> {
    private final int fromIndex;
    private final List<T> removed;
    private final T originalSelection;

    private ManagedTabRemovalBatch(int fromIndex, List<T> removed, T originalSelection) {
        this.fromIndex = fromIndex;
        this.removed = List.copyOf(removed);
        this.originalSelection = originalSelection;
    }

    static <T> ManagedTabRemovalBatch<T> capture(
            int fromIndex, List<? extends T> removed, T originalSelection) {
        return new ManagedTabRemovalBatch<>(fromIndex, List.copyOf(removed), originalSelection);
    }

    void restoreInto(
            List<T> destination,
            Consumer<? super T> disable,
            Consumer<? super T> select) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(disable, "disable");
        Objects.requireNonNull(select, "select");
        int insertion = Math.max(0, Math.min(fromIndex, destination.size()));
        destination.addAll(insertion, removed);
        removed.forEach(disable);
        if (originalSelection != null && removed.contains(originalSelection)) {
            select.accept(originalSelection);
        }
    }
}
