package com.datacube.spi.schemadiff;

import com.datacube.spi.SqlExecutionOptions;

import java.sql.SQLException;

@FunctionalInterface
public interface SchemaSnapshotReader {
    SchemaSnapshot read(String connectionId, QualifiedName schema,
                        SqlExecutionOptions options) throws SQLException;
}
