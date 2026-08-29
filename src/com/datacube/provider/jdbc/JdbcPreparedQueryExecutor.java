package com.datacube.provider.jdbc;

import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlParameter;
import com.datacube.spi.model.QueryResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;

/** Shared JDBC execution path for parameterized result-filter queries. */
public final class JdbcPreparedQueryExecutor {
    private JdbcPreparedQueryExecutor() {}

    public static QueryResult execute(
            Connection connection, String sql, List<SqlParameter> parameters,
            SqlExecutionOptions options) {
        long started = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            SqlExecutionControl.Activation activation =
                    options.control().activate(statement, options.queryTimeoutSeconds());
            try {
                JdbcStatementLimits.apply(statement, options.maxRows());
                for (int i = 0; i < parameters.size(); i++) {
                    parameters.get(i).bind(statement, i + 1);
                }
                options.control().ensureNotCancelled(activation);
                try (ResultSet rows = statement.executeQuery()) {
                    return QueryResult.fromResultSet(
                            rows, System.currentTimeMillis() - started, options.maxRows());
                }
            } finally {
                options.control().release(activation);
            }
        } catch (SQLTimeoutException timeout) {
            return QueryResult.timeout(
                    JdbcDiagnostics.timeout(timeout), System.currentTimeMillis() - started);
        } catch (SQLException failure) {
            long elapsed = System.currentTimeMillis() - started;
            return options.control().cancellationRequested()
                    ? QueryResult.cancelled(JdbcDiagnostics.cancelled(failure), elapsed)
                    : QueryResult.error(JdbcDiagnostics.sqlFailure(failure), elapsed);
        }
    }
}
