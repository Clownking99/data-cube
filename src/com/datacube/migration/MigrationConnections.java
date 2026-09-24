package com.datacube.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** Explicit resource boundary; production uses DriverManager, tests use synthetic JDBC. */
@FunctionalInterface
public interface MigrationConnections {
    Connection open(String url, String user, String password) throws SQLException;
}
