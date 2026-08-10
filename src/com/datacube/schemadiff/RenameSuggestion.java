package com.datacube.schemadiff;

import com.datacube.spi.schemadiff.ObjectKey;

import java.util.Objects;

public record RenameSuggestion(
        ObjectKey sourceObject, ObjectKey targetObject,
        double similarity, String explanation) {
    public RenameSuggestion {
        sourceObject = Objects.requireNonNull(sourceObject, "sourceObject");
        targetObject = Objects.requireNonNull(targetObject, "targetObject");
        if (!Double.isFinite(similarity) || similarity < 0.0 || similarity > 1.0) {
            throw new IllegalArgumentException("similarity must be between zero and one");
        }
        explanation = Objects.requireNonNull(explanation, "explanation");
    }

    @Override
    public String toString() {
        return "RenameSuggestion[sourceType=" + sourceObject.type()
                + ", targetType=" + targetObject.type()
                + ", similarity=" + similarity + "]";
    }
}
