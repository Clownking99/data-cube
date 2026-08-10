package com.datacube.spi.schemadiff;

import java.util.Objects;
import java.util.Set;

public record DefinitionObject(
        ObjectKey key, String normalizedDefinition, String originalDefinition,
        Set<ObjectKey> dependencies,
        DefinitionConfidence confidence) implements SchemaObject {
    public DefinitionObject {
        key = Objects.requireNonNull(key, "key");
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        confidence = Objects.requireNonNull(confidence, "confidence");
    }
}
