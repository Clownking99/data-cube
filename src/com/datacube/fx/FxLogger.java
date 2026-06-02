package com.datacube.fx;

import com.datacube.core.MigrationLogger;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

import java.io.*;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class FxLogger implements MigrationLogger {

    private final TextArea logArea;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private PrintWriter logWriter;

    public FxLogger(TextArea logArea, ProgressBar progressBar, Label statusLabel) {
        this.logArea = logArea;
        this.progressBar = progressBar;
        this.statusLabel = statusLabel;
        openLog();
    }

    private void openLog() {
        try {
            String logFileName = "migration_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".log";
            logWriter = new PrintWriter(new FileWriter(logFileName), true);
        } catch (IOException e) {
            // 文件日志创建失败不影响 GUI
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
    public void logInfo(String msg) {
        appendLog("  [" + ts() + "]  " + msg);
        logToFile("[INFO]  " + msg);
    }

    @Override
    public void logOk(String msg) {
        appendLog("  [" + ts() + "]  [OK] " + msg);
        logToFile("[OK]    " + msg);
    }

    @Override
    public void logWarn(String msg) {
        appendLog("  [" + ts() + "]  [!!] " + msg);
        logToFile("[WARN]  " + msg);
    }

    @Override
    public void logErr(String msg) {
        appendLog("  [" + ts() + "]  [ERR] " + msg);
        logToFile("[ERR]   " + msg);
    }

    @Override
    public void logToFile(String msg) {
        if (logWriter != null) logWriter.println("[" + ts() + "] " + msg);
    }

    @Override
    public void logProgress(String label, int done, int total) {
        double progress = total > 0 ? (double) done / total : 0;
        Platform.runLater(() -> {
            progressBar.setProgress(progress);
            statusLabel.setText(label + " (" + done + "/" + total + ")");
        });
        if (done == total) {
            logToFile("[INFO]  " + label + " " + (total > 0 ? done * 100 / total : 0) + "% (" + done + "/" + total + ")");
        }
    }

    @Override
    public void logSummary(String title, Map<String, Object> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  ─────────────────────────────────────────────\n");
        sb.append("  ").append(title).append("\n");
        sb.append("  ─────────────────────────────────────────────\n");
        logToFile("--- " + title + " ---");
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            String icon;
            if (val instanceof Number && ((Number) val).longValue() > 0) icon = "  ✓";
            else if (val instanceof String) icon = "  ·";
            else icon = "  -";
            sb.append(icon).append(" ").append(key).append(": ").append(val).append("\n");
            logToFile("  " + key + ": " + val);
        }
        sb.append("  ─────────────────────────────────────────────\n");
        appendLog(sb.toString());
    }

    @Override
    public void logSection(String msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  ─────────────────────────────────────────────\n");
        sb.append("  ").append(msg).append("\n");
        sb.append("  ─────────────────────────────────────────────\n");
        appendLog(sb.toString());
        logToFile("");
        logToFile("=== " + msg + " ===");
    }

    @Override
    public void logLine() {
        appendLog("  ─────────────────────────────────────────────");
    }

    private void appendLog(String text) {
        Platform.runLater(() -> {
            logArea.appendText(text + "\n");
        });
    }
}
