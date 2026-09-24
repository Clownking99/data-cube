package com.datacube.spi;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

/** One serial execution's retained-data allowance, also bounding all incremental snapshots. */
public final class SqlResultBudget {
    public record Limits(int results, int rows, int cells, int columns, int textUnits, int cellUnits) {
        public Limits {
            if (results < 1 || rows < 1 || cells < 1 || columns < 1 || textUnits < 1 || cellUnits < 1)
                throw new IllegalArgumentException("Result limits must be positive");
        }
    }
    // Reserve one overview slot for the explicit 'not executed' control result.
    public static final Limits DEFAULT = new Limits(999, 50_000, 250_000, 256, 4_194_304, 4096);
    private final Limits limits;
    private int rows, cells, text, omittedFields;

    public SqlResultBudget() { this(DEFAULT); }
    public SqlResultBudget(Limits limits) { this.limits = java.util.Objects.requireNonNull(limits); }
    public Limits limits() { return limits; }
    public int remainingText() { return limits.textUnits() - text; }
    public int retainedRows() { return rows; }
    public int retainedCells() { return cells; }
    public int retainedTextUnits() { return text; }
    public int omittedFields() { return omittedFields; }
    public void noteOmission() { omittedFields++; }
    public boolean exhausted() { return rows >= limits.rows() || cells >= limits.cells() || remainingText() == 0; }
    public boolean canReadRow(int columns) {
        return columns > 0 && rows < limits.rows() && columns <= limits.cells() - cells && remainingText() > 0;
    }
    public void takeRow(int columns) {
        if (!canReadRow(columns)) throw new IllegalStateException("Result row budget exhausted");
        rows++; cells += columns;
    }
    public String text(String value, int fieldLimit) {
        if (value == null) return null;
        int end = Math.min(value.length(), Math.min(fieldLimit, remainingText()));
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) end--;
        text += end;
        if (end < value.length()) omittedFields++;
        return value.substring(0, end);
    }

    /** Bounded optional annotation read; never asks a driver for the entire wide value. */
    public String readText(java.io.Reader source, int fieldLimit) throws java.io.IOException {
        if (source == null) return null;
        try (source) {
            int cap = Math.min(fieldLimit, remainingText());
            char[] buffer = new char[cap + 1]; int used = 0;
            while (used < buffer.length) {
                int n = source.read(buffer, used, buffer.length - used);
                if (n < 0) break;
                if (n == 0) { int c = source.read(); if (c < 0) break; buffer[used++] = (char) c; }
                else used += n;
            }
            return text(new String(buffer, 0, used), cap);
        }
    }
    public ScriptOutcome retain(int index, String sql, QueryResult result) {
        String kept = text(sql, 16_384);
        if (result.kind == QueryResult.Kind.ERROR) {
            String message = text(result.errorMessage, 16_384);
            if (!java.util.Objects.equals(message, result.errorMessage)) message += " [错误详情已省略]";
            QueryResult original = result;
            result = switch (result.failureKind) {
                case SQL_ERROR -> QueryResult.error(message, result.elapsedMillis);
                case CANCELLED -> QueryResult.cancelled(message, result.elapsedMillis);
                case TIMEOUT -> QueryResult.timeout(message, result.elapsedMillis);
            };
            result = result.withExecutionDetails(original.errorPosition, original.executionMillis, original.fetchMillis);
        }
        boolean shortened = kept.length() < sql.length();
        if (shortened) result = result.withRetentionNotice("SQL 仅保留前缀；" + result.retentionNotice);
        return new ScriptOutcome(index, kept, result);
    }
}
