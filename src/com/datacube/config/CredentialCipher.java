package com.datacube.config;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Versioned credential facade. New values use {@code v2:<scheme>:<payload>};
 * unprefixed values remain compatible with the original AES-GCM format.
 */
public final class CredentialCipher {

    private static final Logger LOG = Logger.getLogger(CredentialCipher.class.getName());
    private static final String V2_PREFIX = "v2:";

    private final CredentialProtector primary;
    private final CredentialProtector fallback;
    private final CredentialProtector legacy;

    public CredentialCipher() {
        this(AesGcmCredentialProtector.legacy());
    }

    private CredentialCipher(CredentialProtector aes) {
        this(aes, aes, aes);
    }

    CredentialCipher(CredentialProtector primary, CredentialProtector fallback,
                     CredentialProtector legacy) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.legacy = Objects.requireNonNull(legacy, "legacy");
    }

    /** Protects a plaintext credential using the current platform format. */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            return frame(primary, primary.protect(plain));
        } catch (RuntimeException primaryFailure) {
            if (primary == fallback) {
                throw new IllegalStateException("凭据保护失败", primaryFailure);
            }
            LOG.warning("首选凭据保护不可用，使用 AES-GCM 回退");
            try {
                return frame(fallback, fallback.protect(plain));
            } catch (RuntimeException fallbackFailure) {
                throw new IllegalStateException("凭据保护失败", fallbackFailure);
            }
        }
    }

    /** Decrypts current versioned formats and the original unprefixed AES-GCM payload. */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        if (encoded.startsWith(V2_PREFIX)) {
            int separator = encoded.indexOf(':', V2_PREFIX.length());
            if (separator < 0 || separator == encoded.length() - 1) {
                throw new IllegalArgumentException("凭据格式不完整");
            }
            String scheme = encoded.substring(V2_PREFIX.length(), separator);
            CredentialProtector protector = protectorFor(scheme);
            return unprotect(protector, encoded.substring(separator + 1));
        }
        if (looksVersioned(encoded)) {
            throw new IllegalArgumentException("不支持的凭据格式版本");
        }
        return unprotect(legacy, encoded);
    }

    /** Returns whether a non-empty value still uses the unprefixed legacy format. */
    public boolean needsUpgrade(String encoded) {
        return encoded != null && !encoded.isEmpty() && !looksVersioned(encoded);
    }

    /** Re-protects readable legacy data, preserving the original value on migration failure. */
    public String upgrade(String encoded) {
        if (!needsUpgrade(encoded)) return encoded == null ? "" : encoded;
        try {
            return encrypt(decrypt(encoded));
        } catch (RuntimeException migrationFailure) {
            LOG.warning("旧版凭据迁移失败，保留原密文");
            return encoded;
        }
    }

    private CredentialProtector protectorFor(String scheme) {
        if (primary.scheme().equals(scheme)) return primary;
        if (fallback.scheme().equals(scheme)) return fallback;
        throw new IllegalArgumentException("不支持的凭据保护方案");
    }

    private static String unprotect(CredentialProtector protector, String payload) {
        try {
            return protector.unprotect(payload);
        } catch (RuntimeException e) {
            throw new IllegalStateException("凭据解密失败，数据可能损坏或不属于当前用户", e);
        }
    }

    private static String frame(CredentialProtector protector, String payload) {
        return V2_PREFIX + protector.scheme() + ':' + payload;
    }

    private static boolean looksVersioned(String encoded) {
        int colon = encoded.indexOf(':');
        if (colon < 2 || encoded.charAt(0) != 'v') return false;
        for (int i = 1; i < colon; i++) {
            if (!Character.isDigit(encoded.charAt(i))) return false;
        }
        return true;
    }
}
