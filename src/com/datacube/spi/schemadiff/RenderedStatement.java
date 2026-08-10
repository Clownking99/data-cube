package com.datacube.spi.schemadiff;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record RenderedStatement(
        String changeId, String sql, boolean destructive,
        Set<String> dependencyIds, String warning) {
    public RenderedStatement {
        changeId = Objects.requireNonNull(changeId, "changeId");
        sql = Objects.requireNonNull(sql, "sql");
        dependencyIds = Collections.unmodifiableSortedSet(
                new TreeSet<>(Objects.requireNonNull(dependencyIds, "dependencyIds")));
    }

    @Override
    public String toString() {
        return "RenderedStatement[destructive=" + destructive
                + ", dependencyCount=" + dependencyIds.size()
                + ", warningPresent=" + (warning != null && !warning.isBlank()) + "]";
    }
}
