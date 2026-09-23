package com.datacube.update;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.*;

/** Exact-byte Ed25519 protocol; the Release never supplies its own trusted key. */
final class UpdateManifest {
    static final long MAX_ASSET_BYTES = 1024L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private final Map<String, PublicKey> trustedKeys;
    private final Clock clock;
    UpdateManifest(Map<String, PublicKey> keys, Clock clock) {
        this.trustedKeys = Map.copyOf(keys); this.clock = clock;
    }
    static UpdateManifest bundled() {
        Map<String, PublicKey> keys = new HashMap<>();
        try (InputStream in = UpdateManifest.class.getResourceAsStream("/com/datacube/update/trusted-keys.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                byte[] bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException();
                properties.load(new java.io.ByteArrayInputStream(bytes));
                for (String id : properties.stringPropertyNames()) {
                    if (!id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException();
                    keys.put(id, KeyFactory.getInstance("Ed25519").generatePublic(
                            new X509EncodedKeySpec(Base64.getDecoder().decode(properties.getProperty(id)))));
                }
            }
        } catch (Exception invalid) { keys.clear(); }
        return new UpdateManifest(keys, Clock.systemUTC());
    }
    boolean configured() { return !trustedKeys.isEmpty(); }
    static boolean validVersion(String version) {
        return version != null && version.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    }
    static String assetName(String version, InstallMode mode) {
        if (!validVersion(version) || mode == InstallMode.UNKNOWN) throw rejected();
        return "DataCube-v" + version + "-win64-" + (mode == InstallMode.INSTALLED ? "setup.exe" : "portable.zip");
    }
    static String baseUrl(String version) {
        if (!validVersion(version)) throw rejected();
        return "https://github.com/Clownking99/data-cube/releases/download/v" + version + "/";
    }
    Verified verify(ReleaseInfo release, String current, byte[] bytes, byte[] signature) throws Exception {
        if (!configured() || bytes.length > MAX_MANIFEST_BYTES || signature.length != 64) throw rejected();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        String[] lines = text.split("\n", -1);
        if (lines.length != 9 || !lines[0].equals("datacube-update-v1") || !lines[8].isEmpty()
                || text.contains("\r") || !lines[1].matches("key=[a-zA-Z0-9_-]{1,64}")) throw rejected();
        String keyId = lines[1].substring(4);
        PublicKey key = trustedKeys.get(keyId);
        if (key == null) throw rejected();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key); verifier.update(bytes);
        if (!verifier.verify(signature)) throw rejected();
        String version = field(lines[2], "version=");
        if (!validVersion(version) || !validVersion(current)
                || !version.equals(release.version()) || !("v" + version).equals(release.tag())
                || !AppVersion.isNewer(version, current) || !lines[3].equals("platform=windows-x64")) throw rejected();
        long issued = number(field(lines[4], "issued=")), expires = number(field(lines[5], "expires="));
        long now = clock.instant().getEpochSecond();
        if (issued > now + 300 || expires <= now || expires <= issued || expires - issued > 90L * 86400) throw rejected();
        Asset setup = parseAsset(lines[6], "setup", version, InstallMode.INSTALLED);
        Asset portable = parseAsset(lines[7], "portable", version, InstallMode.PORTABLE);
        return new Verified(version, setup, portable);
    }
    private static Asset parseAsset(String line, String kind, String version, InstallMode mode) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 4 || !parts[0].equals(kind) || !parts[1].equals(assetName(version, mode))
                || !parts[3].matches("[0-9a-f]{64}")) throw rejected();
        long size = number(parts[2]);
        if (size < 1 || size > MAX_ASSET_BYTES) throw rejected();
        return new Asset(parts[1], size, parts[3]);
    }
    private static String field(String line, String prefix) {
        if (!line.startsWith(prefix)) throw rejected();
        return line.substring(prefix.length());
    }
    private static long number(String text) {
        if (!text.matches("[1-9][0-9]{0,11}")) throw rejected();
        return Long.parseLong(text);
    }
    static IllegalStateException rejected() {
        return new IllegalStateException("更新验证失败：版本、平台、签名或清单不可信，请从官方发布页手动升级");
    }
    record Asset(String name, long size, String sha256) { }
    static final class Verified {
        final String version;
        private final Asset setup, portable;
        private Verified(String version, Asset setup, Asset portable) {
            this.version = version; this.setup = setup; this.portable = portable;
        }
        Asset asset(InstallMode mode) {
            return switch (mode) { case INSTALLED -> setup; case PORTABLE -> portable; default -> throw rejected(); };
        }
    }
}
