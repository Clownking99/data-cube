package com.datacube.sqleditor;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;
import java.util.ArrayList;
import java.util.List;

/** Detached, bounded display evidence. Never retains query data, sessions or connections. */
public record SqlScriptExecutionReport(List<Entry> entries, int returned, int normal,
        int failed, int timedOut, int cancelled, long elapsedMillis) {
    public static final int MAX_ENTRIES = 1000;
    public static final int MAX_FIELD_UNITS = 16_384;
    public static final int MAX_TEXT_UNITS = 1_048_576;
    public static final int MAX_SQL_PREVIEW_UNITS = 120;

    public SqlScriptExecutionReport { entries = List.copyOf(entries); }

    public record Text(String value, boolean truncated) { }

    public record Entry(int index, QueryResult.Kind kind, QueryResult.FailureKind failureKind,
            long elapsedMillis, int rowCount, boolean rowsTruncated, int updateCount, Text sql, Text error) {
        public String status() {
            if (kind != QueryResult.Kind.ERROR) return kind.name();
            return switch (failureKind) {
                case SQL_ERROR -> "失败";
                case TIMEOUT -> "超时";
                case CANCELLED -> "取消";
            };
        }

        public String resultDescription() {
            return switch (kind) {
                case QUERY -> "已加载 " + rowCount + " 行" + (rowsTruncated ? "（结果已截断）" : "");
                case UPDATE -> updateCount < 0 ? "影响行数未提供" : "影响 " + updateCount + " 行";
                case ERROR -> error.value().isEmpty()
                        ? (error.truncated() ? "错误详情因显示上限省略" : "未提供错误信息") : error.value();
            };
        }

        public String summary() {
            String description = resultDescription();
            int end = safeEnd(description, 80);
            return description.substring(0, end).replace('\n', ' ').replace('\r', ' ')
                    + (end < description.length() || error.truncated() ? "…" : "");
        }

        /** Display only: flatten lines without parsing comments, literals or executable SQL. */
        public String sqlPreview() {
            String flattened = sql.value().replaceAll("\\R|\\t", " ").strip();
            if (flattened.isEmpty()) return sql.truncated() ? "SQL 因显示上限省略" : "未提供 SQL";
            int end = safeEnd(flattened, MAX_SQL_PREVIEW_UNITS);
            return flattened.substring(0, end) + (end < flattened.length() || sql.truncated() ? "…" : "");
        }
    }

    public static SqlScriptExecutionReport capture(List<ScriptOutcome> outcomes, long elapsedMillis) {
        List<Entry> entries = new ArrayList<>(Math.min(outcomes.size(), MAX_ENTRIES));
        int normal = 0, failed = 0, timedOut = 0, cancelled = 0;
        Budget budget = new Budget();
        for (ScriptOutcome outcome : outcomes) {
            QueryResult result = outcome.result();
            if (result.kind != QueryResult.Kind.ERROR) normal++;
            else switch (result.failureKind) {
                case SQL_ERROR -> failed++;
                case TIMEOUT -> timedOut++;
                case CANCELLED -> cancelled++;
            }
            if (entries.size() < MAX_ENTRIES) {
                Text sql = budget.capture(outcome.sql());
                Text error = budget.capture(result.errorMessage);
                entries.add(new Entry(outcome.index(), result.kind, result.failureKind, result.elapsedMillis,
                        result.rows.size(), result.truncated, result.updateCount, sql, error));
            }
        }
        return new SqlScriptExecutionReport(entries, outcomes.size(), normal, failed, timedOut, cancelled, elapsedMillis);
    }

    public boolean hasFailures() { return failed + timedOut + cancelled > 0; }

    public String summary() {
        return "已返回 " + returned + " 条结果：正常 " + normal + " · 失败 " + failed
                + " · 超时 " + timedOut + " · 取消 " + cancelled + " - " + elapsedMillis + "ms";
    }

    public String displayNotice() {
        return "SQL 摘要仅供浏览；选择一条后查看结果，或 Enter 打开详情（只读）"
                + (returned > entries.size() ? "；仅显示前 " + entries.size() + " 条" : "");
    }

    private static int safeEnd(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return end;
    }

    private static final class Budget {
        private int remaining = MAX_TEXT_UNITS;
        Text capture(String source) {
            if (source == null) return new Text("", false);
            int end = safeEnd(source, Math.min(MAX_FIELD_UNITS, remaining));
            remaining -= end;
            return new Text(source.substring(0, end), end < source.length());
        }
    }
}
