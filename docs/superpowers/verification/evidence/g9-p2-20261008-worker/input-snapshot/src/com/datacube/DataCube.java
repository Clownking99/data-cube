package com.datacube;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataCube {

    public static void main(String[] args) {
        // --gui 模式启动 JavaFX 界面（纯反射，编译时不依赖 JavaFX）
        if (args.length > 0 && "--gui".equals(args[0])) {
            // 自动启用 native access：JDK 17+ 启 JavaFX 必须加 --enable-native-access=ALL-UNNAMED
            // 用户直接 java -jar DataCube.jar --gui 时自动加上后重启
            if (needsNativeAccess() && !hasNativeAccess()) {
                if (restartWithNativeAccess(args)) {
                    return; // 子进程接管，正常退出
                }
                // 重启失败，原地继续（会失败但会提示）
            }
            // 如果 java.library.path 不含 prism_d3d.dll等 JavaFX native，则解压到临时目录后重启
            if (needsNativesInLibraryPath()) {
                if (restartWithNativesInLibraryPath(args)) {
                    return;
                }
            }
            try {
                Class<?> appClass = Class.forName("javafx.application.Application");
                Class<?> fxClass = Class.forName("com.datacube.DataCubeFx");
                java.lang.reflect.Method launch = appClass.getMethod("launch", Class.class, String[].class);
                launch.invoke(null, fxClass, args);
            } catch (ClassNotFoundException e) {
                System.err.println("  [ERR] JavaFX 未找到。DataCube.jar 应已含 JavaFX classes，如未含请重新构建。");
            } catch (NoClassDefFoundError e) {
                System.err.println("  [ERR] JavaFX 未找到。DataCube.jar 应已含 JavaFX classes，如未含请重新构建。");
            } catch (Exception e) {
                System.err.println("  [ERR] GUI 启动失败: " + e.getMessage());
                if (e.getCause() != null) {
                    System.err.println("  cause: " + e.getCause().getMessage());
                }
                diagnosePipelineFailure(e);
            }
            return;
        }

        printBanner();
        com.datacube.cli.MigrationConsole.run();
    }
    private static void printBanner() {
        System.out.println();
        System.out.println("       ______            datacube  \u00b7  \u6570\u636e\u9b54\u65b9");
        System.out.println("      /\\     \\           DataCube \u6570\u636e\u5e93\u7ba1\u7406\u5de5\u5177 v3.0");
        System.out.println("     /  \\     \\");
        System.out.println("    /____\\_____\\         \u6bcf\u4e00\u9762\uff0c\u7686\u662f\u6570\u636e\u65b0\u7ef4\u5ea6");
        System.out.println("    \\    /     /         Every Face, A New Dimension of Data");
        System.out.println("     \\  /     /");
        System.out.println("      \\/_____/");
        System.out.println();
    }

    /** Java 主版本号（JDK 9+ 适用；JDK 8 返回 8） */
    private static int javaMajorVersion() {
        String v = System.getProperty("java.version", "");
        try {
            String[] parts = v.split("\\.");
            int first = Integer.parseInt(parts[0]);
            if (first >= 9) {
                // "17.0.5" -> 17; "21-ea" -> 21; "17-internal" -> 17
                String head = parts[0];
                int dash = head.indexOf('-');
                if (dash > 0) head = head.substring(0, dash);
                return Integer.parseInt(head);
            }
            // JDK 8: 1.8.0_xxx
            if (first == 1 && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return first;
        } catch (Exception e) {
            return 8;
        }
    }

    /** 是否需要 --enable-native-access （JDK 17+ 默认拒 System::load） */
    private static boolean needsNativeAccess() {
        return javaMajorVersion() >= 17;
    }

    /** 检测当前 JVM 参数是否已含 --enable-native-access */
    private static boolean hasNativeAccess() {
        try {
            for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (a.startsWith("--enable-native-access")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 重启当前 JVM，自动加 --enable-native-access=ALL-UNNAMED
     * @return true=子进程已启动，本进程应退出
     */
    private static boolean restartWithNativeAccess(String[] args) {
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
            String jarPath = new File(DataCube.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("--enable-native-access=ALL-UNNAMED");
            // 让 prism 打印详细初始化日志，以诊断 d3d/sw 失败原因
            cmd.add("-Dprism.verbose=true");
            cmd.add("-Djavafx.verbose=true");
            cmd.add("-Djava.awt.headless=false");
            cmd.add("-jar");
            cmd.add(jarPath);
            cmd.addAll(Arrays.asList(args));

            System.out.println("  [info] JDK 17+ 需要 --enable-native-access=ALL-UNNAMED，自动重启进程...");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            System.exit(code);
            return true;
        } catch (URISyntaxException | InterruptedException | IOException e) {
            System.err.println("  [warn] 自动启用 native-access 失败: " + e.getMessage());
            System.err.println("         请手动使用以下命令启动 GUI:");
            System.err.println("         java --enable-native-access=ALL-UNNAMED -jar DataCube.jar --gui");
            return false;
        }
    }

    /**
     * 诊断 JavaFX 渲染管线初始化失败
     * d3d/sw 两个 pipeline 都失败的常见原因：远程桌面/虚拟机无 GPU、缺 VC++ Redistributable、
     * 显卡驱动不兼容、Server Core 无 D2D/GDI 组件
     */
    private static void diagnosePipelineFailure(Throwable t) {
        String msg = (t.getMessage() == null ? "" : t.getMessage());
        Throwable cause = t.getCause();
        while (cause != null && cause.getMessage() != null) {
            msg += " | " + cause.getMessage();
            cause = cause.getCause();
        }

        if (msg.contains("no suitable pipeline") || msg.contains("QuantumRenderer")) {
            System.err.println();
            System.err.println("  ===== JavaFX 渲染管线初始化失败诊断 =====");
            System.err.println("  d3d 和 sw 两个 pipeline 都无法初始化。请按以下顺序排查：");
            System.err.println();
            System.err.println("  1. 显卡驱动：确保显卡驱动为最新。集成显卡/老旧显卡可能需要更新驱动。");
            System.err.println("     远程桌面/虚拟机环境下 GPU 不可用，需切到主机本地会话。");
            System.err.println();
            System.err.println("  2. VC++ 运行库：安装 Microsoft Visual C++ 2015-2022 Redistributable (x64)");
            System.err.println("     下载: https://aka.ms/vs/17/release/vc_redist.x64.exe");
            System.err.println();
            System.err.println("  3. 显卡硬件加速：尝试 -Dprism.order=sw 强制软件渲染:");
            System.err.println("     java --enable-native-access=ALL-UNNAMED -Dprism.order=sw -jar DataCube.jar --gui");
            System.err.println();
            System.err.println("  4. 禁用硬件加速（备选）:");
            System.err.println("     java --enable-native-access=ALL-UNNAMED -Dprism.disableD3D=true -jar DataCube.jar --gui");
            System.err.println();
            System.err.println("  5. 查看 prism verbose 详细日志:");
            System.err.println("     java --enable-native-access=ALL-UNNAMED -Dprism.verbose=true -jar DataCube.jar --gui");
            System.err.println("  ========================================");
        }
    }

    /** JavaFX 必需的关键 native dll 名字（不带 .dll 后缀） */
    private static final String[] JAVAFX_NATIVES = {
        // 渲染管线
        "prism_d3d", "prism_sw", "prism_common",
        // 窗口/合成
        "glass", "javafx_font", "javafx_iio",
        // 装饰 + 媒体
        "decora_sse", "fxplugins",
        // 关键 VC++ 运行库（防止 prism_d3d.dll 等找不到依赖）
        "msvcp140", "msvcp140_1", "msvcp140_2",
        "vcruntime140", "vcruntime140_1",
        "ucrtbase"
    };

    /**
     * 检查 java.library.path 是否含 prism_d3d.dll。
     * 如果不含，说明 DataCube.jar 是 fat-jar 形式嵌入 dll，需要解压到临时目录后重启。
     */
    private static boolean needsNativesInLibraryPath() {
        // 1) 如果 prism_d3d.dll 已经加载，跳过
        try {
            java.lang.reflect.Field f = ClassLoader.class.getDeclaredField("loadedLibraryNames");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Set<String> loaded = (java.util.Set<String>) f.get(null);
            if (loaded != null) {
                for (String n : loaded) {
                    if (n != null && n.contains("prism_d3d")) return false;
                }
            }
        } catch (Throwable ignored) {}
        // 2) 检查 java.library.path 是否任一目录含 prism_d3d.dll
        String libPath = System.getProperty("java.library.path", "");
        for (String p : libPath.split(File.pathSeparator)) {
            if (p.isEmpty()) continue;
            File f = new File(p, "prism_d3d.dll");
            if (f.isFile()) return false;
        }
        return true;
    }

    /**
     * 解压 jar 内的 JavaFX native dll 到 java.io.tmpdir/datacube-javafx-natives/
     * 返回该目录路径。
     */
    private static Path extractJavafxNatives() throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "datacube-javafx-natives");
        Files.createDirectories(tempDir);
        ClassLoader cl = DataCube.class.getClassLoader();
        int copied = 0;
        for (String dll : JAVAFX_NATIVES) {
            URL u = cl.getResource("lib/javafx/" + dll + ".dll");
            if (u == null) continue;
            Path dst = tempDir.resolve(dll + ".dll");
            try (InputStream is = u.openStream()) {
                Files.copy(is, dst, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        System.out.println("  [info] 已解压 " + copied + " 个 JavaFX native dll -> " + tempDir);
        return tempDir;
    }

    /**
     * 重启 JVM，加上 --enable-native-access 和 -Djava.library.path 指向已解压 dll 的目录
     */
    private static boolean restartWithNativesInLibraryPath(String[] args) {
        try {
            Path nativeDir = extractJavafxNatives();
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
            String jarPath = new File(DataCube.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            if (needsNativeAccess()) cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("-Djava.library.path=" + nativeDir.toAbsolutePath());
            cmd.add("-Dprism.verbose=true");
            cmd.add("-Djavafx.verbose=true");
            cmd.add("-jar");
            cmd.add(jarPath);
            cmd.addAll(Arrays.asList(args));

            System.out.println("  [info] 未检测到 java.library.path 含 JavaFX native dll，自动解压后重启进程...");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            System.exit(code);
            return true;
        } catch (Throwable e) {
            System.err.println("  [warn] 自动配置 native dll 失败 (" + e.getClass().getName() + "): " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
