package com.datacube.provider.jdbc;

import java.sql.SQLException;
import java.sql.Statement;

/** Applies JDBC-side query bounds while retaining one overflow row for truncation proof. */
public final class JdbcStatementLimits {
    private JdbcStatementLimits() {
    }

    public static void apply(Statement statement, int maxRows) throws SQLException {
        if (maxRows <= 0) return;
        statement.setMaxRows(maxRows == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxRows + 1);
    }
}
