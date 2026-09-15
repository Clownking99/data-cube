package com.datacube.service;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.TableInfo;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** One schema, one immutable target, one caller-owned connection; never uses the shared tree connection. */
public final class SchemaObjectCatalog {
    public static final int MAX_OBJECTS = 10_000;
    private final ConnectionManager connections;

    public SchemaObjectCatalog(ConnectionManager connections) { this.connections = Objects.requireNonNull(connections); }

    public List<TableInfo> load(ConnConfig target, String schema) throws SQLException {
        Objects.requireNonNull(target);
        if ((target.type() != DbType.POSTGRESQL && target.type() != DbType.ORACLE)
                || schema == null || schema.isEmpty() || schema.length() > 1024)
            throw new IllegalArgumentException("A relational schema is required");
        checkCancelled();
        var provider = connections.provider(target);
        checkCancelled();
        try (Connection connection = connections.openDedicated(target, provider)) {
            checkCancelled();
            var names = provider.metadataReader(connection).tableAndViewNames(schema, MAX_OBJECTS + 1);
            checkCancelled();
            return validate(schema, names);
        }
    }

    public static List<TableInfo> validate(String schema, List<TableInfo> names) throws SQLException {
        if (names == null) throw new SQLException("Missing object names");
        if (names.size() > MAX_OBJECTS) throw new TooManyObjectsException();
        for (TableInfo name : names) {
            checkCancelled();
            if (name == null || !Objects.equals(schema, name.schema()) || name.name() == null
                    || name.name().isEmpty() || name.name().length() > 1024 || name.kind() == null)
                throw new SQLException("Invalid object name snapshot");
        }
        // Retain only identity and type even if a third-party provider attaches extra metadata.
        return names.stream().map(n -> new TableInfo(n.schema(), n.name(), n.kind(), null)).distinct().toList();
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    public static final class TooManyObjectsException extends SQLException {
        public TooManyObjectsException() { super("Object count exceeds the search limit"); }
    }
}
