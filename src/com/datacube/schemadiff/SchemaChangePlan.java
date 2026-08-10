package com.datacube.schemadiff;

import com.datacube.spi.schemadiff.SchemaChange;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record SchemaChangePlan(
        SchemaDiffResult diff, List<SchemaChange> changes,
        Set<String> selectedChangeIds, Set<String> blockedChangeIds,
        String digest) {
    public SchemaChangePlan {
        diff = Objects.requireNonNull(diff, "diff");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        selectedChangeIds = immutableSortedSet(selectedChangeIds, "selectedChangeIds");
        blockedChangeIds = immutableSortedSet(blockedChangeIds, "blockedChangeIds");
        digest = Objects.requireNonNull(digest, "digest");
    }

    private static Set<String> immutableSortedSet(Set<String> values, String name) {
        return Collections.unmodifiableSortedSet(
                new TreeSet<>(Objects.requireNonNull(values, name)));
    }

    @Override
    public String toString() {
        return "SchemaChangePlan[changeCount=" + changes.size()
                + ", selectedCount=" + selectedChangeIds.size()
                + ", blockedCount=" + blockedChangeIds.size() + "]";
    }
}
