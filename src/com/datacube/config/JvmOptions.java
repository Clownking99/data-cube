package com.datacube.config;

import com.datacube.update.InstallMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 运行时改写 jpackage 启动器配置中的最大堆（{@code -Xmx}）。
 *
 * <p>{@code -Xmx} 无法在已启动的 JVM 内动态生效，故写入 app-image 的
 * {@code <appDir>/app/DataCube.cfg} 的 {@code [JavaOptions]} 段，下次启动生效。
 * best-effort：非 jpackage 环境（IDE 运行）或文件缺失/只读时静默失败并返回 false。
 */
public final class JvmOptions {

    private static final Logger LOG = Logger.getLogger(JvmOptions.class.getName());

    /** 启动器配置文件名（与 build.gradle 中 launcher name 一致）。 */
    private static final String CFG_NAME = "DataCube.cfg";
    private static final String SECTION = "[JavaOptions]";

    private JvmOptions() {}

    /** 定位启动器配置文件：{@code <appDir>/app/DataCube.cfg}；不可用时返回空。 */
    public static Optional<Path> configFile() {
        return InstallMode.appDir().map(dir -> dir.resolve("app").resolve(CFG_NAME));
    }

    /**
     * 将最大堆写入启动器配置（覆盖已有 {@code -Xmx} 行），下次启动生效。
     *
     * @param maxHeapMb 目标最大堆（MB），下限 128
     * @return 是否成功写入
     */
    public static boolean applyMaxHeap(int maxHeapMb) {
        int mb = Math.max(128, maxHeapMb);
        Optional<Path> cfg = configFile();
        if (cfg.isEmpty()) return false;
        Path file = cfg.get();
        if (!Files.exists(file)) return false;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size() + 2);
            String newOption = "java-options=-Xmx" + mb + "m";

            // 1) 删除已有的 -Xmx 行（忽略大小写与前后空白）
            for (String line : lines) {
                String t = line.trim().toLowerCase();
                if (t.startsWith("java-options=-xmx")) continue;
                out.add(line);
            }
            // 2) 在 [JavaOptions] 段后插入新行；无该段则追加一个
            int idx = -1;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).trim().equalsIgnoreCase(SECTION)) { idx = i; break; }
            }
            if (idx >= 0) {
                out.add(idx + 1, newOption);
            } else {
                out.add(SECTION);
                out.add(newOption);
            }
            Files.write(file, out, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOG.warning("写入启动器最大堆配置失败: " + e.getMessage());
            return false;
        }
    }
}
