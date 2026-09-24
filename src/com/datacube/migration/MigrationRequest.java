package com.datacube.migration;

import java.nio.file.Path;
import java.util.Objects;

/** An operation snapshot. Credentials remain in memory and must never be rendered or persisted. */
public record MigrationRequest(Endpoint source, Endpoint target, String owner, String schema,
                               Path directory, Mode mode, boolean convertBoolean) {
    public enum Mode { EMPTY_TABLES_ONLY, SKIP_NONEMPTY }
    public record Endpoint(String url, String user, String password) {
        public Endpoint { Objects.requireNonNull(url); Objects.requireNonNull(user); Objects.requireNonNull(password); }
        @Override public String toString() { return "MigrationEndpoint[redacted]"; }
    }
    public MigrationRequest {
        Objects.requireNonNull(source); Objects.requireNonNull(target); Objects.requireNonNull(mode);
        Objects.requireNonNull(owner); Objects.requireNonNull(schema);
        directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        if (owner.isBlank() || schema.isBlank()) throw new IllegalArgumentException("Migration scope required");
    }
    @Override public String toString() { return "MigrationRequest[redacted, mode=" + mode + "]"; }
}
