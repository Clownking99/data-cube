package com.datacube.migration;

import com.datacube.core.MigrationLogger;
import java.util.*;

final class MigrationTestLogger implements MigrationLogger {
    final List<String> messages = new ArrayList<>();
    Map<String, Object> summary = Map.of();
    public void logInfo(String value) { messages.add(value); }
    public void logOk(String value) { messages.add(value); }
    public void logWarn(String value) { messages.add(value); }
    public void logErr(String value) { messages.add(value); }
    public void logToFile(String value) { messages.add(value); }
    public void logProgress(String value, int done, int total) { messages.add(value); }
    public void logSummary(String title, Map<String, Object> values) { messages.add(title); summary = Map.copyOf(values); }
    public void logSection(String value) { messages.add(value); }
    public void logLine() { }
}
