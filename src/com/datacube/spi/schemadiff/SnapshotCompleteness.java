package com.datacube.spi.schemadiff;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public record SnapshotCompleteness(
        boolean complete, SortedMap<ObjectType, String> unavailableScopes) {
    public SnapshotCompleteness {
        unavailableScopes = Collections.unmodifiableSortedMap(
                new TreeMap<>(Objects.requireNonNull(unavailableScopes, "unavailableScopes")));
    }
}
