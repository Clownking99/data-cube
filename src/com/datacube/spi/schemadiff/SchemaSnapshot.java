package com.datacube.spi.schemadiff;

import com.datacube.spi.model.DbType;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public record SchemaSnapshot(
        DbType databaseType, String connectionId, QualifiedName schema,
        Instant capturedAt, SnapshotCompleteness completeness,
        SortedMap<ObjectKey, SchemaObject> objects, String fingerprint) {
    public SchemaSnapshot {
        databaseType = Objects.requireNonNull(databaseType, "databaseType");
        schema = Objects.requireNonNull(schema, "schema");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        completeness = Objects.requireNonNull(completeness, "completeness");
        objects = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(objects, "objects")));
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    @Override
    public String toString() {
        return "SchemaSnapshot[complete=" + completeness.complete()
                + ", objectCount=" + objects.size()
                + ", fingerprint=" + fingerprint + "]";
    }
}
