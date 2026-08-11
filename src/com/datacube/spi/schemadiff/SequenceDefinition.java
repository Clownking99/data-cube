package com.datacube.spi.schemadiff;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record SequenceDefinition(
        ObjectKey key, String startValue, String incrementBy, String minimumValue,
        String maximumValue, boolean cycle, Integer cacheSize,
        Set<ObjectKey> dependencies,
        Map<String, String> providerExtensions) implements SchemaObject {
    public SequenceDefinition {
        key = Objects.requireNonNull(key, "key");
        dependencies = Set.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        providerExtensions = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(providerExtensions, "providerExtensions")));
    }

    public SequenceDefinition(
            ObjectKey key, String startValue, String incrementBy, String minimumValue,
            String maximumValue, boolean cycle, Integer cacheSize,
            Set<ObjectKey> dependencies) {
        this(key, startValue, incrementBy, minimumValue, maximumValue, cycle, cacheSize,
                dependencies, Map.of());
    }
}
