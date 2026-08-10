package com.datacube.spi.schemadiff;

import java.util.Objects;
import java.util.Set;

public record SequenceDefinition(
        ObjectKey key, String startValue, String incrementBy, String minimumValue,
        String maximumValue, boolean cycle, Integer cacheSize,
        Set<ObjectKey> dependencies) implements SchemaObject {
    public SequenceDefinition {
        key = Objects.requireNonNull(key, "key");
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
    }
}
