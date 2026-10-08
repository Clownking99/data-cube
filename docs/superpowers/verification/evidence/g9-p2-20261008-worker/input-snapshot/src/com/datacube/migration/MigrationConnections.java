package com.datacube.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** Explicit resource boundary; production uses DriverManager, tests use synthetic JDBC. */
@FunctionalInterface
public interface MigrationConnections {
    Connection open(String url, String user, String password) throws SQLException;

    /** The packaged merged module does not expose JDBC ServiceLoader providers. No connection is opened here. */
    static java.sql.Driver driverFor(String url) throws SQLException {
        String name;
        if(url!=null && url.startsWith("jdbc:oracle:"))name="oracle.jdbc.driver.OracleDriver";
        else if(url!=null && url.startsWith("jdbc:postgresql:"))name="org.postgresql.Driver";
        else throw new SQLException("Unsupported migration JDBC driver","DC004");
        try { Class.forName(name); }
        catch(ClassNotFoundException | LinkageError unavailable) { throw new SQLException("Migration JDBC driver unavailable","DC004"); }
        return java.sql.DriverManager.getDriver(url);
    }
    static Connection connect(String url,String user,String password) throws SQLException {
        driverFor(url);
        return java.sql.DriverManager.getConnection(url,user,password);
    }
}
