package com.datacube.update;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 运行形态判定：区分 exe 安装版 / 免安装绿色版 / 未知（开发或无法判定）。
 *
 * <ul>
 *   <li>{@link #INSTALLED} —— 走下载 setup.exe 原地升级；</li>
 *   <li>{@link #PORTABLE} —— 走辅助脚本自替换重启；</li>
 *   <li>{@link #UNKNOWN} —— 不猜，打开 Releases 页让用户手动下载。</li>
 * </ul>
 *
 * <p>依据 jpackage 启动器自带的系统属性 {@code jpackage.app-path} 定位 app-image
 * 根目录，再查 Windows 卸载项判断当前实例是否为“已安装”产物。jpackage 生成的
 * WiX 安装包会写入 {@code InstallLocation}（=安装目录，来自 ARPINSTALLLOCATION）与
 * {@code DisplayName}（=应用名）；绿色版解压后从不写入任何卸载项，故二者任一命中即判为安装版。
 */
public enum InstallMode {

    INSTALLED,
    PORTABLE,
    UNKNOWN;

    // per-user 安装写入 HKCU；per-machine 写入 HKLM。两者都扫，取更鲁棒的判定。
    private static final String[] UNINSTALL_KEYS = {
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
    };

    /**
     * app-image 根目录（jpackage 启动器所在目录）。
     * 非 jpackage 环境（如 IDE 直接运行）返回空。
     */
    public static Optional<Path> appDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            return Optional.empty();
        }
        try {
            Path exe = Path.of(appPath);
            Path dir = exe.getParent();
            return dir == null ? Optional.empty() : Optional.of(dir.toAbsolutePath().normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 判定当前运行形态。 */
    public static InstallMode detect() {
        Optional<Path> dir = appDir();
        if (dir.isEmpty()) {
            return UNKNOWN; // 非 jpackage 启动（开发环境）
        }
        String appDir = dir.get().toString();
        String appName = appName().orElse(null);
        try {
            return isRegisteredInstall(appDir, appName) ? INSTALLED : PORTABLE;
        } catch (Exception e) {
            return UNKNOWN; // 注册表查询异常，不猜
        }
    }

    /** 启动器可执行文件名去扩展名（jpackage 中即应用名，与安装包 DisplayName 一致）。 */
    private static Optional<String> appName() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isBlank()) {
            return Optional.empty();
        }
        try {
            String name = Path.of(appPath).getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            return name.isBlank() ? Optional.empty() : Optional.of(name);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 扫描卸载项判断是否为已安装产物。命中任一即视为安装版：
     * <ul>
     *   <li>{@code InstallLocation} 与 appDir 互为父子或相等（容忍 dir-chooser 目录差异）；</li>
     *   <li>{@code DisplayName} 等于应用名（绿色版从不写卸载项，故此信号足以判定）。</li>
     * </ul>
     */
    private static boolean isRegisteredInstall(String appDir, String appName) throws Exception {
        String target = normalize(appDir);
        String wantName = appName == null ? null : appName.trim();
        for (String key : UNINSTALL_KEYS) {
            String out = regQuery(key);
            if (out == null) continue;
            for (String line : out.split("\\r?\\n")) {
                String s = line.trim();
                String loc = valueOf(s, "InstallLocation");
                if (loc != null && locationMatches(normalize(loc), target)) {
                    return true;
                }
                if (wantName != null) {
                    String name = valueOf(s, "DisplayName");
                    if (name != null && name.equalsIgnoreCase(wantName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 执行 {@code reg query <key> /s}，以控制台代码页解码输出；键不存在或失败返回 null。 */
    private static String regQuery(String key) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/s")
                    .redirectErrorStream(true)
                    .start();
            byte[] bytes;
            try (var in = p.getInputStream()) {
                bytes = in.readAllBytes();
            }
            p.waitFor();
            // reg.exe 按控制台代码页输出；用 native.encoding（Windows 上即本地代码页）解码，
            // 避免 JDK 18+ 默认 UTF-8 把非 ASCII 路径/名称解成乱码而错过匹配。
            return new String(bytes, consoleCharset());
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 {@code Name    REG_SZ    value} 形式的行中取指定值名的值；不匹配返回 null。 */
    private static String valueOf(String line, String name) {
        if (!line.startsWith(name)) return null;
        String rest = line.substring(name.length());
        // 名称后须紧跟空白（避免 InstallLocation 误配 InstallLocationX 之类）
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) return null;
        int idx = rest.indexOf("REG_");
        if (idx < 0) return null;
        int sp = rest.indexOf("    ", idx); // REG_SZ 与值之间的分隔
        if (sp < 0) return null;
        String value = rest.substring(sp).trim();
        return value.isEmpty() ? null : value;
    }

    /** 目录相等，或一方为另一方的父目录（应对 dir-chooser 追加/去除应用名子目录）。 */
    private static boolean locationMatches(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b)
                || a.startsWith(b + "\\")
                || b.startsWith(a + "\\");
    }

    private static Charset consoleCharset() {
        String enc = System.getProperty("native.encoding");
        if (enc != null && !enc.isBlank()) {
            try {
                return Charset.forName(enc);
            } catch (Exception ignored) {
                // 无法识别的编码名，回退默认
            }
        }
        return Charset.defaultCharset();
    }

    private static String normalize(String path) {
        String s = path.trim();
        while (s.endsWith("\\") || s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase();
    }
}
