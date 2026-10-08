package com.datacube.provider.postgres;

import com.datacube.provider.jdbc.JdbcPreparedQueryExecutor;
import com.datacube.provider.jdbc.JdbcDiagnostics;
import com.datacube.provider.jdbc.JdbcStatementLimits;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlParameter;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;

/**
 * PostgreSQL SQL 执行器：迁移自原 {@code sqleditor.SqlExecutor}，
 * 将 schema 切换（{@code SET search_path}）委托给 {@link SqlDialect}。
 */
public final class PgSqlRunner implements SqlRunner {

    private final SqlDialect dialect;

    public PgSqlRunner(SqlDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public QueryResult execute(Connection conn, String sql, String schema, SqlExecutionOptions options) {
        long t0 = System.currentTimeMillis();
        long executionStarted = -1, executionMillis = -1, fetchStarted = -1, fetchMillis = -1;
        boolean userExecute = false;
        try {
            applySchema(conn, schema, options);
            try (Statement stmt = conn.createStatement()) {
                var activation = options.control().activate(stmt, options.queryTimeoutSeconds());
                try {
                    options.control().ensureNotCancelled(activation);
                    JdbcStatementLimits.apply(stmt, options.maxRows());
                    executionStarted = System.nanoTime();
                    userExecute = true;
                    boolean hasResult = stmt.execute(sql);
                    userExecute = false;
                    executionMillis = (System.nanoTime() - executionStarted) / 1_000_000;
                    long elapsed = System.currentTimeMillis() - t0;
                    if (hasResult) {
                        fetchStarted = System.nanoTime();
                        try (var rs = stmt.getResultSet()) {
                            java.sql.ResultSetMetaData md = rs.getMetaData();
                            QueryResult r = QueryResult.fromResultSet(rs, elapsed, options.maxRows(), options.resultBudget(), options.control());
                            fetchMillis = (System.nanoTime() - fetchStarted) / 1_000_000;
                            fetchStarted = -1;
                            r = r.withExecutionDetails(0, executionMillis, fetchMillis);
                            options.control().release(activation);
                            activation = null;
                            // best-effort 解析列注释；失败或无表列时返回 null，不影响结果展示
                            int omissions = options.resultBudget().omittedFields();
                            List<String> comments = PgColumnComments.resolve(conn, md, options);
                            if (options.resultBudget().omittedFields() > omissions)
                                r = r.withRetentionNotice(r.retentionNotice + " 列注释读取受预算限制");
                            return comments == null ? r : r.withColumnComments(comments);
                        }
                    } else {
                        return QueryResult.update(elapsed, stmt.getUpdateCount()).withExecutionDetails(0, executionMillis, 0);
                    }
                } finally {
                    if (activation != null) options.control().release(activation);
                }
            }
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - t0;
            if (userExecute) executionMillis = (System.nanoTime() - executionStarted) / 1_000_000;
            if (fetchStarted >= 0) fetchMillis = (System.nanoTime() - fetchStarted) / 1_000_000;
            QueryResult result = options.control().cancellationRequested() ? QueryResult.cancelled(e.getMessage(), elapsed)
                    : e instanceof SQLTimeoutException ? QueryResult.timeout(e.getMessage(), elapsed)
                    : QueryResult.error(e.getMessage(), elapsed);
            int position = result.failureKind == QueryResult.FailureKind.SQL_ERROR ? (userExecute ? PgErrorPosition.originalPosition(e, conn, sql) : 0) : 0;
            return result.withExecutionDetails(position, executionMillis, fetchMillis);
        }
    }
    @Override
    public QueryResult executePrepared(
            Connection conn, String sql, List<SqlParameter> parameters,
            String schema, SqlExecutionOptions options) {
        long startedAt = System.currentTimeMillis();
        try {
            applySchema(conn, schema, options);
            return JdbcPreparedQueryExecutor.execute(conn, sql, parameters, options);
        } catch (SQLException failure) {
            long elapsed = System.currentTimeMillis() - startedAt;
            return options.control().cancellationRequested()
                    ? QueryResult.cancelled(JdbcDiagnostics.cancelled(failure), elapsed)
                    : failure instanceof SQLTimeoutException ? QueryResult.timeout(JdbcDiagnostics.timeout(failure), elapsed)
                    : QueryResult.error(JdbcDiagnostics.sqlFailure(failure), elapsed);
        }
    }

    @Override
    public List<ScriptOutcome> executeScript(Connection conn, String script, String schema,
                                             SqlExecutionOptions options,
                                             ScriptErrorPolicy policy) {
        return com.datacube.provider.jdbc.JdbcScriptExecutor.execute(
                this, conn, script, schema, options, policy, false);
    }

    @Override
    public QueryResult explain(Connection conn, String sql, String schema, boolean analyze,
                               SqlExecutionOptions options) {
        // PG：单条 EXPLAIN [ANALYZE] <sql>，直接复用 execute 与同一控制选项。
        return execute(conn, dialect.explainSql(sql, analyze), schema, options);
    }

    private void applySchema(Connection conn, String schema, SqlExecutionOptions options) throws SQLException {
        String schemaSql = dialect.currentSchemaSql(schema);
        if (schemaSql == null) return;
        try (Statement statement = conn.createStatement()) {
            var activation = options.control().activate(statement, options.queryTimeoutSeconds());
            try {
                options.control().ensureNotCancelled(activation);
                statement.execute(schemaSql);
            } finally {
                options.control().release(activation);
            }
        }
    }
}
