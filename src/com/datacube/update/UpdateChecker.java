package com.datacube.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 向 GitHub Releases API 查询最新 release，并映射为 {@link ReleaseInfo}。
 *
 * <p>匿名访问公开仓库；网络/限流/超时/非 200 一律抛异常，由调用方决定是否提示用户。
 */
public final class UpdateChecker {

    /** 仓库 owner/repo（与 CI 发布目标一致）。 */
    private static final String REPO = "Clownking99/data-cube";
    private static final String API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/" + REPO + "/releases/latest";

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 仓库 releases 页地址（UNKNOWN 形态兜底）。 */
    public static String releasesPage() {
        return RELEASES_PAGE;
    }

    /**
     * 拉取最新 release。成功返回解析后的 {@link ReleaseInfo}。
     *
     * @throws Exception 网络失败、超时、HTTP 非 200 或 JSON 解析失败
     */
    public ReleaseInfo fetchLatest() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DataCube-Updater")
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("GitHub API 返回状态 " + resp.statusCode());
        }
        return map(MiniJson.parse(resp.body()));
    }

    @SuppressWarnings("unchecked")
    private ReleaseInfo map(Object root) {
        if (!(root instanceof Map<?, ?> obj)) {
            throw new IllegalStateException("响应不是 JSON 对象");
        }
        String tag = str(obj.get("tag_name"));
        String body = str(obj.get("body"));
        String htmlUrl = str(obj.get("html_url"));

        String version = tag == null ? null
                : (tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag);

        String setupUrl = null;
        String portableUrl = null;
        Object assets = obj.get("assets");
        if (assets instanceof List<?> list) {
            for (Object a : list) {
                if (!(a instanceof Map<?, ?> asset)) continue;
                String name = str(asset.get("name"));
                String url = str(asset.get("browser_download_url"));
                if (name == null || url == null) continue;
                String lower = name.toLowerCase();
                if (lower.endsWith("setup.exe")) {
                    setupUrl = url;
                } else if (lower.endsWith("portable.zip")) {
                    portableUrl = url;
                }
            }
        }
        return new ReleaseInfo(tag, version, body, htmlUrl, setupUrl, portableUrl);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** 便捷方法：拉取最新版并与当前版本比较，仅当有更新时返回。 */
    public Optional<ReleaseInfo> checkForUpdate() throws Exception {
        ReleaseInfo latest = fetchLatest();
        if (latest.version() != null && AppVersion.isNewer(latest.version(), AppVersion.current())) {
            return Optional.of(latest);
        }
        return Optional.empty();
    }
}
