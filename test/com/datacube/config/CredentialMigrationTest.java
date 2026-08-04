package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialMigrationTest {

    private final CredentialProtector aes = AesGcmCredentialProtector.legacy();
    private final CredentialCipher cipher = new CredentialCipher(aes, aes, aes);

    @Test
    void upgradesValidLegacyEntriesWithoutChangingCurrentEmptyOrDamagedSiblings() {
        ConnConfig legacy = config("legacy", aes.protect("old-password"));
        ConnConfig current = config("current", cipher.encrypt("current-password"));
        ConnConfig empty = config("empty", "");
        ConnConfig damaged = config("damaged", "damaged-legacy-secret");

        List<ConnConfig> migrated = CredentialMigration.upgradeAll(
                List.of(legacy, current, empty, damaged), cipher);

        assertTrue(migrated.get(0).encryptedPassword().startsWith("v2:aesgcm:"));
        assertEquals("old-password", cipher.decrypt(migrated.get(0).encryptedPassword()));
        assertSame(current, migrated.get(1));
        assertSame(empty, migrated.get(2));
        assertSame(damaged, migrated.get(3));
    }

    private static ConnConfig config(String id, String encryptedPassword) {
        return new ConnConfig(id, id, DbType.REDIS, "localhost", 6379,
                "0", "", encryptedPassword, Map.of());
    }
}
