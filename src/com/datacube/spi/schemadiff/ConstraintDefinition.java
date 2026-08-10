package com.datacube.spi.schemadiff;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ConstraintDefinition(
        ObjectKey key, ConstraintKind kind, List<QualifiedName> columns,
        ObjectKey referencedTable, List<QualifiedName> referencedColumns,
        String normalizedExpression, String updateAction, String deleteAction,
        boolean providerGeneratedName, Set<ObjectKey> dependencies) {
    public ConstraintDefinition {
        key = Objects.requireNonNull(key, "key");
        kind = Objects.requireNonNull(kind, "kind");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        referencedColumns = List.copyOf(Objects.requireNonNull(referencedColumns, "referencedColumns"));
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
    }
}
