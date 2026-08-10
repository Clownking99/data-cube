package com.datacube.spi.schemadiff;

import com.datacube.schemadiff.PropertyDifference;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record SchemaChange(
        String id, ChangeKind kind, ObjectKey object,
        SchemaObject source, SchemaObject target,
        PropertyDifference property,
        RiskLevel risk, AutomationLevel automation,
        boolean selectedByDefault,
        Set<String> dependencyChangeIds,
        String explanation) {
    public SchemaChange {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        object = Objects.requireNonNull(object, "object");
        risk = Objects.requireNonNull(risk, "risk");
        automation = Objects.requireNonNull(automation, "automation");
        dependencyChangeIds = Collections.unmodifiableSortedSet(
                new TreeSet<>(Objects.requireNonNull(dependencyChangeIds, "dependencyChangeIds")));
        explanation = Objects.requireNonNull(explanation, "explanation");
    }

    @Override
    public String toString() {
        return "SchemaChange[kind=" + kind
                + ", objectType=" + object.type()
                + ", sourcePresent=" + (source != null)
                + ", targetPresent=" + (target != null)
                + ", propertyPresent=" + (property != null)
                + ", risk=" + risk
                + ", automation=" + automation
                + ", selectedByDefault=" + selectedByDefault
                + ", dependencyCount=" + dependencyChangeIds.size() + "]";
    }
}
