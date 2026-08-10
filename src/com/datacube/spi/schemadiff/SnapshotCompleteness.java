package com.datacube.spi.schemadiff;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Captures only fixed, non-sensitive diagnostic codes for unavailable metadata scopes.
 * Exception text, URLs, credentials, and provider messages are never valid diagnostic values.
 */
public record SnapshotCompleteness(
        boolean complete, SortedMap<ObjectType, String> unavailableScopes) {
    public static final String NOT_SUPPORTED = "NOT_SUPPORTED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String METADATA_UNAVAILABLE = "METADATA_UNAVAILABLE";
    public static final String DEFINITION_UNAVAILABLE = "DEFINITION_UNAVAILABLE";
    public static final String DEPENDENCY_UNRESOLVED = "DEPENDENCY_UNRESOLVED";

    private static final Set<String> SAFE_DIAGNOSTIC_CODES = Set.of(
            NOT_SUPPORTED,
            PERMISSION_DENIED,
            METADATA_UNAVAILABLE,
            DEFINITION_UNAVAILABLE,
            DEPENDENCY_UNRESOLVED);

    public SnapshotCompleteness {
        SortedMap<ObjectType, String> copiedScopes = new TreeMap<>(
                Objects.requireNonNull(unavailableScopes, "unavailableScopes"));
        if (copiedScopes.values().stream().anyMatch(code -> code == null || !SAFE_DIAGNOSTIC_CODES.contains(code))) {
            throw new IllegalArgumentException("Unavailable scope diagnostic code is not allowed");
        }
        unavailableScopes = Collections.unmodifiableSortedMap(copiedScopes);
    }
}
