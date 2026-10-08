package com.datacube.spi;

import java.util.Objects;

/** 单次或脚本 SQL 执行的行数、超时与取消选项。 */
public record SqlExecutionOptions(
        int maxRows,
        int queryTimeoutSeconds,
        SqlExecutionControl control,
        SqlResultBudget resultBudget,
        java.util.function.Consumer<SqlScriptProgress> progress) {

    public SqlExecutionOptions(int maxRows, int queryTimeoutSeconds, SqlExecutionControl control) {
        this(maxRows, queryTimeoutSeconds, control, new SqlResultBudget(), ignored -> {});
    }

    public SqlExecutionOptions {
        if (maxRows < 0) maxRows = 0;
        if (queryTimeoutSeconds < 0) queryTimeoutSeconds = 0;
        control = Objects.requireNonNull(control, "control");
        resultBudget = Objects.requireNonNull(resultBudget, "resultBudget");
        progress = Objects.requireNonNull(progress, "progress");
    }

    public void publish(SqlScriptProgress event) {
        // An unavailable UI observer must not alter the script's transaction/error semantics.
        try { progress.accept(event); } catch (RuntimeException ignored) { }
    }

    public static SqlExecutionOptions defaults(int maxRows) {
        return new SqlExecutionOptions(maxRows, 0, new SqlExecutionControl());
    }
}
