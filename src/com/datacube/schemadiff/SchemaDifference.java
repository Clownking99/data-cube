package com.datacube.schemadiff;

import com.datacube.spi.schemadiff.AutomationLevel;
import com.datacube.spi.schemadiff.ObjectKey;
import com.datacube.spi.schemadiff.RiskLevel;
import com.datacube.spi.schemadiff.SchemaObject;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record SchemaDifference(
        DifferenceKind kind, ObjectKey object, SchemaObject source,
        SchemaObject target, List<PropertyDifference> properties,
        RiskLevel risk, AutomationLevel automation,
        Set<ObjectKey> dependencies, String explanation) {
    public SchemaDifference {
        kind = Objects.requireNonNull(kind, "kind");
        object = Objects.requireNonNull(object, "object");
        properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
        risk = Objects.requireNonNull(risk, "risk");
        automation = Objects.requireNonNull(automation, "automation");
        dependencies = Collections.unmodifiableSortedSet(
                new TreeSet<>(Objects.requireNonNull(dependencies, "dependencies")));
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
