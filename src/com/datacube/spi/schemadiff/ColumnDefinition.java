package com.datacube.spi.schemadiff;

import java.util.Objects;

public record ColumnDefinition(
        QualifiedName name, CanonicalDataType dataType, boolean nullable,
        String normalizedDefault, int ordinal, String comment) {
    public ColumnDefinition {
        name = Objects.requireNonNull(name, "name");
        dataType = Objects.requireNonNull(dataType, "dataType");
    }
}
