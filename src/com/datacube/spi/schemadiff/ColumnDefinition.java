package com.datacube.spi.schemadiff;

import java.util.Objects;

public record ColumnDefinition(
        QualifiedName name, CanonicalDataType dataType, boolean nullable,
        String normalizedDefault, int ordinal, String comment) {
    public ColumnDefinition {
        name = Objects.requireNonNull(name, "name");
        dataType = Objects.requireNonNull(dataType, "dataType");
    }

    /** Null and blank catalog expressions both mean that no default is present. */
    public boolean hasDefault() {
        return normalizedDefault != null && !normalizedDefault.isBlank();
    }

    @Override
    public String toString() {
        return "ColumnDefinition[nullable=" + nullable
                + ", ordinal=" + ordinal
                + ", defaultPresent=" + hasDefault()
                + ", commentPresent=" + (comment != null) + "]";
    }
}
