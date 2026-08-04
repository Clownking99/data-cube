package com.datacube.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** AES-GCM payload implementation used for legacy reads and cross-platform fallback. */
final class AesGcmCredentialProtector implements CredentialProtector {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final byte[] LEGACY_SALT = "datacube-cred-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom random;

    private AesGcmCredentialProtector(SecretKeySpec key, SecureRandom random) {
        this.key = key;
        this.random = random;
    }

    static AesGcmCredentialProtector legacy() {
        return new AesGcmCredentialProtector(deriveLegacyKey(), new SecureRandom());
    }

    private static SecretKeySpec deriveLegacyKey() {
        try {
            String seed = System.getProperty("user.name", "user")
                    + "|" + System.getProperty("user.home", "home");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(LEGACY_SALT);
            byte[] digest = sha.digest(seed.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 AES-GCM 凭据保护", e);
        }
    }

    @Override
    public String scheme() {
        return "aesgcm";
    }

    @Override
    public String protect(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] output = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 凭据保护失败", e);
        }
    }

    @Override
    public String unprotect(String payload) {
        try {
            byte[] all = Base64.getDecoder().decode(payload);
            if (all.length < IV_LENGTH + TAG_BYTES) {
                throw new IllegalArgumentException("payload too short");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] body = Arrays.copyOfRange(all, IV_LENGTH, all.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 凭据解密失败", e);
        }
    }
}
