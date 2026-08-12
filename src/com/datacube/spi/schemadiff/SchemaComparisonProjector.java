package com.datacube.spi.schemadiff;

import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Provider-owned projection from exact snapshot identity to comparison-only identity. */
@FunctionalInterface
public interface SchemaComparisonProjector {
    SchemaComparisonProjection project(SchemaSnapshot snapshot);

    /** Backward-compatible behavior for providers that do not opt into schema-relative identity. */
    static SchemaComparisonProjector identity() {
        return snapshot -> {
            Objects.requireNonNull(snapshot, "snapshot");
            SortedMap<ObjectKey, ObjectKey> originals = new TreeMap<>();
            snapshot.objects().keySet().forEach(key -> originals.put(key, key));
            return new SchemaComparisonProjection(snapshot, snapshot.objects(), originals);
        };
    }
}
