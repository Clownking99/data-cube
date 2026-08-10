package com.datacube.schemadiff;

import java.util.Objects;

public record PropertyDifference(
        String path, Object sourceValue, Object targetValue, String explanation) {
    public PropertyDifference {
        path = Objects.requireNonNull(path, "path");
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
