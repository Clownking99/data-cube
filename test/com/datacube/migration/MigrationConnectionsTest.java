package com.datacube.migration;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class MigrationConnectionsTest {
    @Test void discoversBundledDriversWithoutConnectingOrCredentials() throws Exception {
        assertEquals("oracle.jdbc.OracleDriver",MigrationConnections.driverFor("jdbc:oracle:thin:@example.invalid:1/synthetic").getClass().getName());
        assertEquals("org.postgresql.Driver",MigrationConnections.driverFor("jdbc:postgresql://example.invalid:1/synthetic").getClass().getName());
    }
    @Test void unsupportedDriversAreRejectedBeforeAnyConnection() {
        var error=assertThrows(SQLException.class,()->MigrationConnections.connect("jdbc:unsupported:synthetic-secret","fake","fake"));
        assertEquals("DC004",error.getSQLState());assertFalse(error.getMessage().contains("synthetic-secret"));
    }
}
