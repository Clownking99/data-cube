package com.datacube.spi.schemadiff;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TableDefinition(
        ObjectKey key, List<ColumnDefinition> columns,
        List<ConstraintDefinition> constraints, List<IndexDefinition> indexes,
        Set<ObjectKey> dependencies) implements SchemaObject {
    public TableDefinition {
        key = Objects.requireNonNull(key, "key");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
        indexes = List.copyOf(Objects.requireNonNull(indexes, "indexes"));
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
    }
}
