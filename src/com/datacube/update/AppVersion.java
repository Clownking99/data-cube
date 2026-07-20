package com.datacube.update;

import java.io.InputStream;
import java.util.Properties;

/**
 * 应用版本号：读取构建时打进包的类路径资源 {@code com/datacube/version.properties}，
 * 并提供语义化版本（major.minor.patch）解析与比较。
 *
 * <p>版本资源由 {@code build.gradle} 的 {@code generateVersionProperties} 任务写入
 * 真实发布版本；资源缺失或读取失败时回退 {@code 0.0.0-dev}（例如未经 Gradle 生成资源的
 * 开发运行）。
 */
public final class AppVersion {

    private static final String DEV = "0.0.0-dev";
    private static final String RESOURCE = "/com/datacube/version.properties";

    private static volatile String cached;

    private AppVersion() {
    }

    /** 当前应用版本字符串（如 {@code 3.0.1} 或 {@code 0.0.0-dev}）。 */
    public static String current() {
        String v = cached;
        if (v == null) {
            v = load();
            cached = v;
        }
        return v;
    }

    /** 是否为开发构建（版本无法读取或为 dev 占位）——用于跳过启动自检。 */
    public static boolean isDev() {
        String v = current();
        return v == null || v.startsWith("0.0.0");
    }

    private static String load() {
        try (InputStream in = AppVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return DEV;
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            return (v == null || v.isBlank()) ? DEV : v.trim();
        } catch (Exception e) {
            return DEV;
        }
    }

    /**
     * 解析语义化版本为三元组 {@code [major, minor, patch]}；容忍前导 {@code v} 与
     * 预发布/构建后缀（如 {@code 3.0.1-dev}、{@code v3.0.1+build}）；无法解析的段视为 0。
     */
    public static int[] parse(String version) {
        int[] out = {0, 0, 0};
        if (version == null) return out;
        String s = version.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        // 去除预发布/构建元数据（- 或 + 之后）
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut);
        String[] parts = s.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            out[i] = toInt(parts[i]);
        }
        return out;
    }

    /** {@code remote} 是否严格新于 {@code local}（按 major→minor→patch 数值比较）。 */
    public static boolean isNewer(String remote, String local) {
        int[] r = parse(remote);
        int[] l = parse(local);
        for (int i = 0; i < 3; i++) {
            if (r[i] != l[i]) {
                return r[i] > l[i];
            }
        }
        return false;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
