package com.datacube.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialCipherTest {

    private final CredentialProtector aes = AesGcmCredentialProtector.legacy();
    private final CredentialCipher cipher = new CredentialCipher(aes, aes, aes);

    @Test
    void prefixesNewAesGcmCiphertextAndRoundTripsUnicode() {
        String plain = "Redis 密码 \\ \" 不应明文保存";

        String encoded = cipher.encrypt(plain);

        assertTrue(encoded.startsWith("v2:aesgcm:"));
        assertFalse(encoded.contains(plain));
        assertNotEquals(encoded, cipher.encrypt(plain));
        assertEquals(plain, cipher.decrypt(encoded));
    }

    @Test
    void decryptsLegacyUnprefixedAesGcmCiphertext() {
        String legacy = aes.protect("legacy-secret");

        assertFalse(legacy.contains(":"));
        assertEquals("legacy-secret", cipher.decrypt(legacy));
        assertTrue(cipher.needsUpgrade(legacy));
    }

    @Test
    void keepsEmptyCredentialsEmpty() {
        assertEquals("", cipher.encrypt(null));
        assertEquals("", cipher.encrypt(""));
        assertEquals("", cipher.decrypt(null));
        assertEquals("", cipher.decrypt(""));
        assertFalse(cipher.needsUpgrade(""));
    }

    @Test
    void rejectsUnknownOrDamagedFormatsWithoutEchoingSecrets() {
        String unknown = "v9:unknown:complete-ciphertext-secret";
        String damaged = "v2:aesgcm:not-base64-password";

        IllegalArgumentException unknownError = assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(unknown));
        IllegalStateException damagedError = assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(damaged));

        assertFalse(unknownError.getMessage().contains(unknown));
        assertFalse(unknownError.getMessage().contains("complete-ciphertext-secret"));
        assertFalse(damagedError.getMessage().contains(damaged));
        assertFalse(damagedError.getMessage().contains("not-base64-password"));
    }

    @Test
    void upgradesReadableLegacyCiphertextAndPreservesUnreadableValue() {
        String legacy = aes.protect("migrate-me");
        String unreadable = "damaged-legacy-secret";

        String upgraded = cipher.upgrade(legacy);

        assertTrue(upgraded.startsWith("v2:aesgcm:"));
        assertEquals("migrate-me", cipher.decrypt(upgraded));
        assertEquals(unreadable, cipher.upgrade(unreadable));
    }
}
