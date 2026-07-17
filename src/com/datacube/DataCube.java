package com.datacube;

import com.datacube.cli.ConsoleLogger;
import com.datacube.cli.ConsolePrompter;
import com.datacube.core.ConnectionHelper;
import com.datacube.migration.OracleExporter;
import com.datacube.migration.PgImporter;
import com.datacube.migration.PgVerifier;

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
import java.sql.*;
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

        try {
            printBanner();

            ConsoleLogger logger = new ConsoleLogger();
            ConsolePrompter prompter = new ConsolePrompter();
            logger.openLog();

            // Oracle
            logger.logSection("第一步：Oracle 数据库连接信息");
            String oraUrl  = prompter.prompt("Oracle JDBC URL",  "jdbc:oracle:thin:@127.0.0.1:1521/orcl", "格式: jdbc:oracle:thin:@IP:端口/服务名");
            String oraUser = prompter.prompt("Oracle 用户名",     "scott", "将导出该用户下的所有对象").toUpperCase();
            String oraPass = prompter.prompt("Oracle 密码",       "", "");

            // PostgreSQL
            logger.logSection("第二步：PostgreSQL 数据库连接信息");
            String pgUrl    = prompter.prompt("PostgreSQL JDBC URL", "jdbc:postgresql://127.0.0.1:5432/postgres", "格式: jdbc:postgresql://IP:端口/数据库名");
            String pgUser   = prompter.prompt("PostgreSQL 用户名",   "postgres", "");
            String pgPass   = prompter.prompt("PostgreSQL 密码",     "", "");
            String pgSchema = prompter.prompt("PostgreSQL Schema",   oraUser.toLowerCase(), "Oracle 用户 " + oraUser + " 的对象将导入到此 schema");

            // 导出配置
            logger.logSection("第三步：导出配置");
            int maxConcurrency = 20;
            String concurrencyStr = prompter.prompt("并发上限", "20", "虚拟线程数，建议 10-50，网络不稳定可设低");
            try { maxConcurrency = Integer.parseInt(concurrencyStr); } catch (Exception e) { maxConcurrency = 20; }
            if (maxConcurrency < 1) maxConcurrency = 1;

            String boolStr = prompter.prompt("是否自动转换布尔值(0/1→TRUE/FALSE)", "n", "仅当字段注释包含\"是否/true/false\"等关键词时转换 (y/n)");
            boolean convertBool = "y".equalsIgnoreCase(boolStr) || "yes".equalsIgnoreCase(boolStr);
            if (convertBool) logger.logInfo("布尔转换: 开启（仅注释含\"是否/true/false\"的 NUMBER(1,0) 字段）");
            else logger.logInfo("布尔转换: 关闭（0/1 保持原值）");

            logger.logInfo("Oracle 用户: " + oraUser + " → PG Schema: " + pgSchema);
            logger.logInfo("并发上限: " + maxConcurrency);

            // 加载驱动
            ConnectionHelper.loadDrivers(logger);

            // 测试连接
            logger.logSection("测试连接");
            Connection oraConn;
            try {
                oraConn = ConnectionHelper.openAndTest(oraUrl, oraUser, oraPass, "Oracle", logger);
            } catch (SQLException e) {
                logger.closeLog();
                return;
            }

            try {
                Connection pgConn = ConnectionHelper.openAndTest(pgUrl, pgUser, pgPass, "PostgreSQL", logger);
                ConnectionHelper.ensureSchema(pgConn, pgSchema, logger);
                pgConn.createStatement().execute("SET search_path TO " + pgSchema);
                pgConn.close();
            } catch (SQLException e) {
                try { oraConn.close(); } catch (Exception ignored) {}
                logger.closeLog();
                return;
            }

            // 初始化模块（注入 logger）
            OracleExporter exporter = new OracleExporter(logger);
            exporter.setMaxConcurrency(maxConcurrency);
            exporter.setConvertBool(convertBool);

            PgImporter importer = new PgImporter(logger);
            importer.setMaxConcurrency(maxConcurrency);

            PgVerifier verifier = new PgVerifier(logger);

            // 主菜单
            while (true) {
                System.out.println();
                logger.logLine();
                System.out.println("  功能菜单");
                logger.logLine();
                System.out.println("  1. 导出 DDL（表/序列/索引/约束/函数）");
                System.out.println("  2. 导出数据（全量，虚拟线程并发 " + maxConcurrency + "）");
                System.out.println("  3. 导入到 PostgreSQL（完整模式 - 先清空再导入）");
                System.out.println("  4. 导入到 PostgreSQL（增量模式 - 仅补充缺失）");
                System.out.println("  5. 一键全部（导出DDL + 导出数据 + 增量导入）");
                System.out.println("  6. 验证导入结果");
                System.out.println("  0. 退出");
                logger.logLine();

                String choice = prompter.prompt("请选择", "5", "");

                switch (choice) {
                    case "1": exporter.exportDDL(oraConn, oraUser, pgSchema); break;
                    case "2": exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema); break;
                    case "3": importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, false); break;
                    case "4": importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true); break;
                    case "5":
                        exporter.exportDDL(oraConn, oraUser, pgSchema);
                        exporter.exportData(oraConn, oraUrl, oraUser, oraPass, pgSchema);
                        importer.importToPg(pgUrl, pgUser, pgPass, oraUser, pgSchema, true);
                        verifier.verify(pgUrl, pgUser, pgPass, pgSchema);
                        break;
                    case "6": verifier.verify(pgUrl, pgUser, pgPass, pgSchema); break;
                    case "0":
                        oraConn.close();
                        logger.logOk("再见!");
                        logger.closeLog();
                        return;
                    default:
                        logger.logWarn("无效选择，请重试");
                }
            }
        } catch (Exception e) {
            System.err.println("  [ERR] 致命错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════╗");
        System.out.println("  ║            DataCube 迁移工具 v3.0             ║");
        System.out.println("  ╠═══════════════════════════════════════════════╣");
        System.out.println("  ║  导出: DDL + 全量数据（并行导出）             ║");
        System.out.println("  ║  导入: 完整模式 / 增量模式                    ║");
        System.out.println("  ║  兼容: NVARCHAR2/NCLOB/BLOB/NUMBER/SYSDATE    ║");
        System.out.println("  ╚═══════════════════════════════════════════════╝");
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
            System.err.println("  5. 重新执行 ./run-gui.bat / ./run-gui.sh 以查看 prism verbose 详细日志。");
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
