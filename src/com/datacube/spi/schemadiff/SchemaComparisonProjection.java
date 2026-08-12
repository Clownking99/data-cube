package com.datacube.spi.schemadiff;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Immutable, reversible comparison view; projected values must never be rendered or displayed. */
public record SchemaComparisonProjection(
        SchemaSnapshot original,
        SortedMap<ObjectKey, SchemaObject> comparisonObjects,
        SortedMap<ObjectKey, ObjectKey> originalKeys) {
    private static final String INVALID = "Schema comparison projection is invalid";

    public SchemaComparisonProjection {
        original = Objects.requireNonNull(original, "original");
        comparisonObjects = Collections.unmodifiableSortedMap(
                new TreeMap<>(Objects.requireNonNull(comparisonObjects, "comparisonObjects")));
        originalKeys = Collections.unmodifiableSortedMap(
                new TreeMap<>(Objects.requireNonNull(originalKeys, "originalKeys")));
        if (!comparisonObjects.keySet().equals(originalKeys.keySet())
                || originalKeys.size() != original.objects().size()
                || !new HashSet<>(originalKeys.values()).equals(original.objects().keySet())) {
            throw new IllegalArgumentException(INVALID);
        }
        for (var entry : comparisonObjects.entrySet()) {
            if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().key())
                    || !original.objects().containsKey(originalKeys.get(entry.getKey()))) {
                throw new IllegalArgumentException(INVALID);
            }
        }
    }

    public SchemaObject originalObject(ObjectKey comparisonKey) {
        ObjectKey originalKey = originalKeys.get(comparisonKey);
        if (originalKey == null) throw new IllegalArgumentException(INVALID);
        SchemaObject object = original.objects().get(originalKey);
        if (object == null) throw new IllegalArgumentException(INVALID);
        return object;
    }
}
