package com.datacube.update;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Locale;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Function;

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
 * {@code DisplayName}（=应用名）；只有 InstallLocation 精确匹配当前目录才判为安装版。
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
        try {
            return registeredMode(dir.get().toString(), InstallMode::regQuery);
        } catch (Exception e) {
            return UNKNOWN; // 注册表查询异常，不猜
        }
    }

    /** Exact location only; failed/incomplete registry queries never imply portable ownership. */
    static InstallMode registeredMode(String appDir, Function<String, String> query) {
        String target = normalize(appDir);
        boolean incomplete = false;
        for (String key : UNINSTALL_KEYS) {
            String out = query.apply(key);
            if (out == null) { incomplete = true; continue; }
            for (String line : out.split("\\r?\\n")) {
                String s = line.trim();
                String loc = valueOf(s, "InstallLocation");
                if (loc != null && locationMatches(normalize(loc), target)) {
                    return INSTALLED;
                }
            }
        }
        return incomplete ? UNKNOWN : PORTABLE;
    }

    /** 执行 {@code reg query <key> /s}，以控制台代码页解码输出；键不存在或失败返回 null。 */
    private static String regQuery(String key) {
        Process process = null;
        ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process = new ProcessBuilder(Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                    "System32", "reg.exe").toString(), "query", key, "/s")
                    .redirectErrorStream(true)
                    .start();
            final Process running = process;
            Future<byte[]> result = reader.submit(() -> {
                try (var input = running.getInputStream()) {
                    byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                    if (bytes.length > 2 * 1024 * 1024) throw new IOException("REGISTRY_OUTPUT_LIMIT");
                    return bytes;
                }
            });
            byte[] bytes = result.get(5, TimeUnit.SECONDS);
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            // reg.exe 按控制台代码页输出；用 native.encoding（Windows 上即本地代码页）解码，
            // 避免 JDK 18+ 默认 UTF-8 把非 ASCII 路径/名称解成乱码而错过匹配。
            return new String(bytes, consoleCharset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            reader.shutdownNow();
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

    /** Only exact normalized locations identify this installation. */
    private static boolean locationMatches(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b);
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
        return s.toLowerCase(Locale.ROOT);
    }
}
