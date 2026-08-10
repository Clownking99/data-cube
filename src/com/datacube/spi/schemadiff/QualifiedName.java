package com.datacube.spi.schemadiff;

import java.util.Objects;

public record QualifiedName(String original, String comparisonKey, boolean quoted)
        implements Comparable<QualifiedName> {
    public QualifiedName {
        original = Objects.requireNonNull(original, "original");
        comparisonKey = Objects.requireNonNull(comparisonKey, "comparisonKey");
    }

    @Override
    public int compareTo(QualifiedName other) {
        int comparison = comparisonKey.compareTo(other.comparisonKey);
        if (comparison != 0) return comparison;
        comparison = original.compareTo(other.original);
        if (comparison != 0) return comparison;
        return Boolean.compare(quoted, other.quoted);
    }
}
