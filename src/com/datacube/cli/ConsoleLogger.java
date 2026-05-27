package com.datacube.cli;

import com.datacube.core.MigrationLogger;

import java.io.*;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class ConsoleLogger implements MigrationLogger {

    private PrintWriter logWriter;

    public void openLog() {
        try {
            String logFileName = "migration_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".log";
            logWriter = new PrintWriter(new FileWriter(logFileName), true);
            logInfo("日志文件: " + new File(logFileName).getAbsolutePath());
        } catch (IOException e) {
            System.err.println("  无法创建日志文件: " + e.getMessage());
        }
    }

    public void closeLog() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
    }

    private String ts() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    @Override
    public void logSection(String msg) {
        System.out.println();
        logLine();
        System.out.println("  " + msg);
        logLine();
        logToFile("");
        logToFile("=== " + msg + " ===");
    }

    @Override
    public void logLine() {
        System.out.println("  ─────────────────────────────────────────────");
    }

    @Override
    public void logInfo(String msg) {
        System.out.println("  [" + ts() + "]  " + msg);
        logToFile("[INFO]  " + msg);
    }

    @Override
    public void logOk(String msg) {
        System.out.println("  [" + ts() + "]  [OK] " + msg);
        logToFile("[OK]    " + msg);
    }

    @Override
    public void logWarn(String msg) {
        System.out.println("  [" + ts() + "]  [!!] " + msg);
        logToFile("[WARN]  " + msg);
    }

    @Override
    public void logErr(String msg) {
        System.out.println("  [" + ts() + "]  [ERR] " + msg);
        logToFile("[ERR]   " + msg);
    }

    @Override
    public void logToFile(String msg) {
        if (logWriter != null) logWriter.println("[" + ts() + "] " + msg);
    }

    @Override
    public void logProgress(String current, int done, int total) {
        int pct = total > 0 ? done * 100 / total : 0;
        String bar = repeat("█", pct / 5) + repeat("░", 20 - pct / 5);
        System.out.print("\r  [" + ts() + "]  " + current + " [" + bar + "] " + pct + "% (" + done + "/" + total + ")");
        if (done == total) {
            System.out.println();
            logToFile("[INFO]  " + current + " " + pct + "% (" + done + "/" + total + ")");
        }
    }

    @Override
    public void logSummary(String title, Map<String, Object> stats) {
        System.out.println();
        logLine();
        System.out.println("  " + title);
        logLine();
        logToFile("--- " + title + " ---");
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            String label = e.getKey();
            Object val = e.getValue();
            String icon;
            if (val instanceof Number && ((Number) val).longValue() > 0) icon = "  ✓";
            else if (val instanceof String) icon = "  ·";
            else icon = "  -";
            System.out.println(icon + " " + label + ": " + val);
            logToFile("  " + label + ": " + val);
        }
        logLine();
    }

    // ==================== 工具方法 ====================

    public static String stackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
