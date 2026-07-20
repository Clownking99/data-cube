package com.datacube.update;

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
 * 根目录，再查 {@code HKCU} 卸载项中 {@code InstallLocation} 是否指向该目录。
 */
public enum InstallMode {

    INSTALLED,
    PORTABLE,
    UNKNOWN;

    private static final String UNINSTALL_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

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
        try {
            return isRegisteredInstall(appDir) ? INSTALLED : PORTABLE;
        } catch (Exception e) {
            return UNKNOWN; // 注册表查询异常，不猜
        }
    }

    /** 扫描 HKCU 卸载项，判断是否存在 InstallLocation 指向 appDir 的安装记录。 */
    private static boolean isRegisteredInstall(String appDir) throws Exception {
        Process p = new ProcessBuilder("reg", "query", UNINSTALL_KEY, "/s")
                .redirectErrorStream(true)
                .start();
        String out;
        try (var in = p.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        p.waitFor();

        String target = normalize(appDir);
        for (String line : out.split("\\r?\\n")) {
            String s = line.trim();
            if (!s.startsWith("InstallLocation")) continue;
            // 形如: InstallLocation    REG_SZ    C:\Users\x\AppData\Local\DataCube
            int idx = s.indexOf("REG_SZ");
            if (idx < 0) continue;
            String value = s.substring(idx + "REG_SZ".length()).trim();
            if (!value.isEmpty() && normalize(value).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        String s = path.trim();
        while (s.endsWith("\\") || s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase();
    }
}
