package com.datacube;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

public class ConsoleLogger {

    private static final Scanner SCAN = new Scanner(System.in);
    private static PrintWriter logWriter;
    private static long startTime;

    // ==================== 日志文件 ====================

    public static void openLog() {
        try {
            String logFileName = "migration_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".log";
            logWriter = new PrintWriter(new FileWriter(logFileName), true);
            logInfo("日志文件: " + new File(logFileName).getAbsolutePath());
        } catch (IOException e) {
            System.err.println("  无法创建日志文件: " + e.getMessage());
        }
    }

    public static void closeLog() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
    }

    // ==================== 用户输入 ====================

    public static String prompt(String label, String defaultVal, String hint) {
        if (!hint.isEmpty()) System.out.println("    (" + hint + ")");
        System.out.print("  " + label + (defaultVal.isEmpty() ? ": " : " [" + defaultVal + "]: "));
        String input = SCAN.nextLine().trim();
        return input.isEmpty() ? defaultVal : input;
    }

    // ==================== 日志输出 ====================

    private static String ts() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public static void logSection(String msg) {
        System.out.println();
        logLine();
        System.out.println("  " + msg);
        logLine();
        logToFile("");
        logToFile("=== " + msg + " ===");
    }

    public static void logLine() {
        System.out.println("  ─────────────────────────────────────────────");
    }

    public static void logInfo(String msg) {
        System.out.println("  [" + ts() + "]  " + msg);
        logToFile("[INFO]  " + msg);
    }

    public static void logOk(String msg) {
        System.out.println("  [" + ts() + "]  [OK] " + msg);
        logToFile("[OK]    " + msg);
    }

    public static void logWarn(String msg) {
        System.out.println("  [" + ts() + "]  [!!] " + msg);
        logToFile("[WARN]  " + msg);
    }

    public static void logErr(String msg) {
        System.out.println("  [" + ts() + "]  [ERR] " + msg);
        logToFile("[ERR]   " + msg);
    }

    public static void logToFile(String msg) {
        if (logWriter != null) logWriter.println("[" + ts() + "] " + msg);
    }

    // ==================== 进度与摘要 ====================

    public static void logProgress(String current, int done, int total) {
        int pct = total > 0 ? done * 100 / total : 0;
        String bar = repeat("█", pct / 5) + repeat("░", 20 - pct / 5);
        System.out.print("\r  [" + ts() + "]  " + current + " [" + bar + "] " + pct + "% (" + done + "/" + total + ")");
        if (done == total) {
            System.out.println();
            logToFile("[INFO]  " + current + " " + pct + "% (" + done + "/" + total + ")");
        }
    }

    public static void logSummary(String title, Map<String, Object> stats) {
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

    // ==================== 计时 ====================

    public static void startTimer() {
        startTime = System.currentTimeMillis();
    }

    public static String elapsed() {
        long ms = System.currentTimeMillis() - startTime;
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return (ms / 1000) + "s";
        return (ms / 60000) + "m " + ((ms % 60000) / 1000) + "s";
    }

    // ==================== 工具 ====================

    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

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
}
