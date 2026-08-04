package com.datacube.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledOnOs(OS.WINDOWS)
class DpapiCredentialProtectorTest {

    @Test
    void defaultFacadeSelectsDpapiOnWindows() {
        CredentialCipher cipher = new CredentialCipher();

        String encoded = cipher.encrypt("default-windows-secret");

        assertFalse(encoded.contains("default-windows-secret"));
        assertEquals("default-windows-secret", cipher.decrypt(encoded));
        org.junit.jupiter.api.Assertions.assertTrue(encoded.startsWith("v2:dpapi:"));
    }

    @Test
    void protectsForCurrentWindowsUserAndRoundTripsUnicode() {
        DpapiCredentialProtector protector = new DpapiCredentialProtector();
        String plain = "当前 Windows 用户的 Redis 密码";

        String payload = protector.protect(plain);

        assertNotEquals(Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)), payload);
        assertFalse(payload.contains(plain));
        assertEquals(plain, protector.unprotect(payload));
    }

    @Test
    void reportsNativeFailureWithoutEchoingCiphertext() {
        DpapiCredentialProtector protector = new DpapiCredentialProtector();
        String damaged = Base64.getEncoder().encodeToString("complete-ciphertext-secret"
                .getBytes(StandardCharsets.UTF_8));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> protector.unprotect(damaged));

        assertFalse(error.getMessage().contains(damaged));
        assertFalse(error.getMessage().contains("complete-ciphertext-secret"));
    }
}
