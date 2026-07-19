package com.datacube.export;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.TableRef;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调用外部 {@code pg_dump} 生成单表备份文件。
 *
 * <p>密码经 {@code PGPASSWORD} 环境变量传入，避免出现在命令行参数中。
 * pg_dump 需已安装并在 PATH 中；找不到或非零退出时抛出可读异常。
 */
public final class PgDumpRunner {

    private PgDumpRunner() {
    }

    /**
     * 备份单表。
     *
     * @param cfg           连接配置（host/port/database/username）
     * @param plainPassword 明文密码（由调用方解密）
     * @param t             目标表
     * @param content       导出内容（结构/数据/两者→映射 --schema-only/--data-only/无）
     * @param out           输出文件
     */
    public static void run(ConnConfig cfg, String plainPassword, TableRef t,
                           ExportContent content, File out) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("pg_dump");
        cmd.add("-h");
        cmd.add(cfg.host());
        cmd.add("-p");
        cmd.add(String.valueOf(cfg.port()));
        cmd.add("-U");
        cmd.add(cfg.username());
        cmd.add("-d");
        cmd.add(cfg.database());
        cmd.add("-t");
        cmd.add(t.qualified());
        if (content == ExportContent.STRUCTURE) {
            cmd.add("--schema-only");
        } else if (content == ExportContent.DATA) {
            cmd.add("--data-only");
        }
        cmd.add("-f");
        cmd.add(out.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PGPASSWORD", plainPassword == null ? "" : plainPassword);
        pb.redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("无法启动 pg_dump，请确认已安装 PostgreSQL 客户端工具并在 PATH 中。原始错误: "
                    + e.getMessage(), e);
        }

        StringBuilder stderr = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stderr.append(line).append('\n');
            }
        }

        boolean done = proc.waitFor(10, TimeUnit.MINUTES);
        if (!done) {
            proc.destroyForcibly();
            throw new IOException("pg_dump 执行超时（超过 10 分钟）。");
        }
        int code = proc.exitValue();
        if (code != 0) {
            String tail = stderr.length() > 2000
                    ? stderr.substring(stderr.length() - 2000) : stderr.toString();
            throw new IOException("pg_dump 退出码 " + code + "：\n" + tail.trim());
        }
    }
}
