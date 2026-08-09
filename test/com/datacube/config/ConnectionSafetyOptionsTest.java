package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConnectionSafetyOptionsTest {
    @Test
    void missingPropertiesUseSafeDefaults() {
        ConnectionSafetyOptions options = ConnectionSafetyOptions.from(config(Map.of()));
        assertEquals(ConnectionEnvironment.DEVELOPMENT, options.environment());
        assertFalse(options.readOnly());
        assertEquals(60, options.queryTimeoutSeconds());
    }

    @Test
    void invalidPropertiesFallBackWithoutPreservingTransientSecrets() {
        ConnConfig config = config(Map.of(
                "environment", "unknown",
                "readOnly", "not-a-boolean",
                "queryTimeoutSeconds", "9000",
                "__plainPassword", "must-not-persist"));

        ConnectionSafetyOptions options = ConnectionSafetyOptions.from(config);

        assertEquals(ConnectionEnvironment.DEVELOPMENT, options.environment());
        assertFalse(options.readOnly());
        assertEquals(60, options.queryTimeoutSeconds());
        assertEquals(Map.of(
                "environment", "DEVELOPMENT",
                "readOnly", "false",
                "queryTimeoutSeconds", "60"), options.toPersistentProps());
    }

    @Test
    void applyMergesOnlyValidatedSafetyValues() {
        ConnConfig original = config(Map.of("driverFlag", "keep"));
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 0);

        ConnConfig updated = options.applyTo(original);

        assertEquals("keep", updated.props().get("driverFlag"));
        assertEquals("PRODUCTION", updated.props().get("environment"));
        assertEquals("true", updated.props().get("readOnly"));
        assertEquals("0", updated.props().get("queryTimeoutSeconds"));
    }

    private static ConnConfig config(Map<String, String> props) {
        return new ConnConfig("id", "name", DbType.POSTGRESQL, "localhost", 5432,
                "db", "user", "encrypted", props);
    }
}
