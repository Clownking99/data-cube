package com.datacube.spi;

import java.util.Objects;

/** 单次或脚本 SQL 执行的行数、超时与取消选项。 */
public record SqlExecutionOptions(
        int maxRows,
        int queryTimeoutSeconds,
        SqlExecutionControl control) {

    public SqlExecutionOptions {
        if (maxRows < 0) maxRows = 0;
        if (queryTimeoutSeconds < 0) queryTimeoutSeconds = 0;
        control = Objects.requireNonNull(control, "control");
    }

    public static SqlExecutionOptions defaults(int maxRows) {
        return new SqlExecutionOptions(maxRows, 0, new SqlExecutionControl());
    }
}
