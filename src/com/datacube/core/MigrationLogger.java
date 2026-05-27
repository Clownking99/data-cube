package com.datacube.core;

import java.util.Map;

public interface MigrationLogger {
    void logInfo(String msg);
    void logOk(String msg);
    void logWarn(String msg);
    void logErr(String msg);
    void logToFile(String msg);
    void logProgress(String label, int done, int total);
    void logSummary(String title, Map<String, Object> stats);
    void logSection(String msg);
    void logLine();
}
