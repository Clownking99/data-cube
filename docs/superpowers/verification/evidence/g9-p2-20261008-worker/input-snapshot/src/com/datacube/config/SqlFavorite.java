package com.datacube.config;

import java.util.Objects;
import java.util.UUID;

/** Explicitly saved SQL text; deliberately contains no connection, credential or result fields. */
public record SqlFavorite(UUID id, String name, String group, String sql, long modifiedAt) {
    public SqlFavorite {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(group); Objects.requireNonNull(sql);
        if (name.isBlank() || name.length()>160 || group.length()>80 || sql.isBlank() || modifiedAt<0)
            throw new IllegalArgumentException("Invalid SQL favorite");
    }
    @Override public String toString() { return "SQL favorite " + id; }
}
