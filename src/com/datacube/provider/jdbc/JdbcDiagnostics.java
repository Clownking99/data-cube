package com.datacube.provider.jdbc;

import java.sql.SQLException;

/** Builds value-free JDBC diagnostics with only validated structural codes. */
public final class JdbcDiagnostics {
    private JdbcDiagnostics() {
    }

    public static String sqlFailure(SQLException failure) {
        return message("数据库查询失败", failure);
    }

    public static String timeout(SQLException failure) {
        return message("数据库查询超时", failure);
    }

    public static String cancelled(SQLException failure) {
        return message("数据库查询已取消", failure);
    }

    private static String message(String summary, SQLException failure) {
        String sqlState = failure.getSQLState();
        boolean safeSqlState = sqlState != null && sqlState.matches("[A-Za-z0-9]{5}");
        int vendorCode = failure.getErrorCode();
        if (!safeSqlState && vendorCode == 0) return summary;

        StringBuilder diagnostic = new StringBuilder(summary).append(" (");
        if (safeSqlState) diagnostic.append("SQLState=").append(sqlState);
        if (vendorCode != 0) {
            if (safeSqlState) diagnostic.append(", ");
            diagnostic.append("vendorCode=").append(vendorCode);
        }
        return diagnostic.append(')').toString();
    }
}
