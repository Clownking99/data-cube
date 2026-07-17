package com.datacube.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 凭据加解密：AES-GCM。
 *
 * <p>密钥由本机信息（用户名 + 用户主目录）+ 固定 salt 经 SHA-256 派生为 AES-128。
 * 密文格式为 Base64({@code IV(12) || cipherText+tag})。
 *
 * <p>定位：个人本机工具，威胁模型为"防止明文直接可读"，非对抗性密钥托管。
 */
public final class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] SALT = "datacube-cred-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher() {
        this.key = deriveKey();
    }

    private static SecretKeySpec deriveKey() {
        try {
            String seed = System.getProperty("user.name", "user")
                    + "|" + System.getProperty("user.home", "home");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(SALT);
            byte[] digest = sha.digest(seed.getBytes(StandardCharsets.UTF_8));
            // 取前 16 字节作为 AES-128 密钥
            return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("派生加密密钥失败: " + e.getMessage(), e);
        }
    }

    /** 加密明文，返回 Base64 密文。空/null 原样返回空串。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密 Base64 密文，返回明文。空/null 原样返回空串。 */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] body = Arrays.copyOfRange(all, IV_LENGTH, all.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败: " + e.getMessage(), e);
        }
    }
}
