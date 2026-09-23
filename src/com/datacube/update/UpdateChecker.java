package com.datacube.update;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public final class UpdateChecker {
    private static final String REPO = "Clownking99/data-cube";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private final UpdateDownloads downloads;
    public UpdateChecker() { this(UpdateDownloads.http()); }
    UpdateChecker(UpdateDownloads downloads) { this.downloads = downloads; }
    public static String releasesPage() { return "https://github.com/" + REPO + "/releases/latest"; }
    public ReleaseInfo fetchLatest() throws Exception {
        try (var control = new UpdateCancellation(Duration.ofSeconds(30))) {
            control.bindWorker();
            return map(MiniJson.parse(new String(downloads.bytes(API, 1024 * 1024, control), StandardCharsets.UTF_8)));
        }
    }
    static ReleaseInfo map(Object root) {
        if (!(root instanceof Map<?, ?> obj) || Boolean.TRUE.equals(obj.get("draft"))
                || Boolean.TRUE.equals(obj.get("prerelease"))) throw UpdateManifest.rejected();
        String tag = str(obj.get("tag_name"));
        String version = tag != null && tag.startsWith("v") ? tag.substring(1) : null;
        if (!UpdateManifest.validVersion(version)) throw UpdateManifest.rejected();
        Map<String, String> assets = new HashMap<>();
        if (obj.get("assets") instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> asset)) throw UpdateManifest.rejected();
                String name = str(asset.get("name")), url = str(asset.get("browser_download_url"));
                if (name == null || url == null) throw UpdateManifest.rejected();
                if (assets.putIfAbsent(name, url) != null) throw UpdateManifest.rejected();
            }
        }
        String base = UpdateManifest.baseUrl(version);
        String setup = exact(assets, base, UpdateManifest.assetName(version, InstallMode.INSTALLED));
        String portable = exact(assets, base, UpdateManifest.assetName(version, InstallMode.PORTABLE));
        boolean manifest = exact(assets, base, "datacube-update.manifest") != null
                && exact(assets, base, "datacube-update.manifest.sig") != null;
        return new ReleaseInfo(tag, version, str(obj.get("body")),
                "https://github.com/" + REPO + "/releases/tag/" + tag, setup, portable, manifest);
    }
    private static String exact(Map<String, String> assets, String base, String name) {
        String value = assets.get(name);
        if (value != null && !value.equals(base + name)) throw UpdateManifest.rejected();
        return value;
    }
    private static String str(Object value) { return value instanceof String text ? text : null; }
    public Optional<ReleaseInfo> checkForUpdate() throws Exception {
        ReleaseInfo latest = fetchLatest();
        return AppVersion.isNewer(latest.version(), AppVersion.current()) ? Optional.of(latest) : Optional.empty();
    }
}
