package com.datacube.spi.schemadiff;

import java.util.Objects;

public record ObjectKey(ObjectType type, QualifiedName name, String signature)
        implements Comparable<ObjectKey> {
    public ObjectKey {
        type = Objects.requireNonNull(type, "type");
        name = Objects.requireNonNull(name, "name");
        signature = signature == null ? "" : signature;
    }

    @Override
    public int compareTo(ObjectKey other) {
        int comparison = type.compareTo(other.type);
        if (comparison != 0) return comparison;
        comparison = name.comparisonKey().compareTo(other.name.comparisonKey());
        if (comparison != 0) return comparison;
        comparison = signature.compareTo(other.signature);
        if (comparison != 0) return comparison;
        return name.compareTo(other.name);
    }
}
