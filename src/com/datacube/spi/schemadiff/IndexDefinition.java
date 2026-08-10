package com.datacube.spi.schemadiff;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record IndexDefinition(
        ObjectKey key, boolean unique, List<String> normalizedExpressions,
        String normalizedPredicate, boolean providerGeneratedName,
        Set<ObjectKey> dependencies) {
    public IndexDefinition {
        key = Objects.requireNonNull(key, "key");
        normalizedExpressions = List.copyOf(Objects.requireNonNull(normalizedExpressions, "normalizedExpressions"));
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
    }
}
