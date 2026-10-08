package com.datacube.provider.oracle;

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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.List;

/**
 * Oracle SQL 执行器：与 {@code PgSqlRunner} 对等，schema 切换委托 {@link SqlDialect}。
 *
 * <p>Oracle 不接受语句尾分号（ORA-00911），执行前统一剥离。
 * 执行计划为两步：估算走 {@code EXPLAIN PLAN FOR} + {@code DBMS_XPLAN.DISPLAY}；
 * 实际走 {@code STATISTICS_LEVEL=ALL} + 真实执行 + {@code DBMS_XPLAN.DISPLAY_CURSOR}。
 */
public final class OracleSqlRunner implements SqlRunner {

    private final SqlDialect dialect;

    public OracleSqlRunner(SqlDialect dialect) {
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
                    boolean hasResult = stmt.execute(strip(sql));
                    userExecute = false;
                    executionMillis = (System.nanoTime() - executionStarted) / 1_000_000;
                    long elapsed = System.currentTimeMillis() - t0;
                    if (hasResult) {
                        fetchStarted = System.nanoTime();
                        try (ResultSet rs = stmt.getResultSet()) {
                            ResultSetMetaData md = rs.getMetaData();
                            QueryResult r = QueryResult.fromResultSet(rs, elapsed, options.maxRows(), options.resultBudget(), options.control());
                            fetchMillis = (System.nanoTime() - fetchStarted) / 1_000_000;
                            fetchStarted = -1;
                            r = r.withExecutionDetails(0, executionMillis, fetchMillis);
                            options.control().release(activation);
                            activation = null;
                            // best-effort 解析列注释；失败或无表列时返回 null，不影响结果展示
                            int omissions = options.resultBudget().omittedFields();
                            List<String> comments = OracleColumnComments.resolve(
                                    conn, md, sql, schema, options);
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
            // Oracle can report explicit Statement.cancel as SQLTimeoutException too.
            QueryResult result = options.control().cancellationRequested() ? QueryResult.cancelled(e.getMessage(), elapsed)
                    : e instanceof SQLTimeoutException ? QueryResult.timeout(e.getMessage(), elapsed)
                    : QueryResult.error(e.getMessage(), elapsed);
            return result.withExecutionDetails(0, executionMillis, fetchMillis);
        }
    }
    @Override
    public QueryResult executePrepared(
            Connection conn, String sql, List<SqlParameter> parameters,
            String schema, SqlExecutionOptions options) {
        long startedAt = System.currentTimeMillis();
        try {
            applySchema(conn, schema, options);
            return JdbcPreparedQueryExecutor.execute(conn, strip(sql), parameters, options);
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
                this, conn, script, schema, options, policy, true);
    }

    @Override
    public QueryResult explain(Connection conn, String sql, String schema, boolean analyze,
                               SqlExecutionOptions options) {
        long t0 = System.currentTimeMillis();
        String stmt = strip(sql);
        try {
            applySchema(conn, schema, options);
            if (analyze) {
                try (Statement s = conn.createStatement()) {
                    var activation = options.control().activate(s, options.queryTimeoutSeconds());
                    try {
                        options.control().ensureNotCancelled(activation);
                        s.execute("ALTER SESSION SET STATISTICS_LEVEL = ALL");
                    } finally {
                        options.control().release(activation);
                    }
                }
                // 实际执行以采集运行时统计（消费结果集）
                try (Statement s = conn.createStatement()) {
                    var activation = options.control().activate(s, options.queryTimeoutSeconds());
                    try {
                        options.control().ensureNotCancelled(activation);
                        boolean has = s.execute(stmt);
                        if (has) {
                            try (ResultSet rs = s.getResultSet()) {
                                while (rs.next()) { /* drain */ }
                            }
                        }
                    } finally {
                        options.control().release(activation);
                    }
                }
                return execute(conn,
                        "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))",
                        null, options);
            } else {
                try (Statement s = conn.createStatement()) {
                    var activation = options.control().activate(s, options.queryTimeoutSeconds());
                    try {
                        options.control().ensureNotCancelled(activation);
                        s.execute("EXPLAIN PLAN FOR " + stmt);
                    } finally {
                        options.control().release(activation);
                    }
                }
                return execute(conn,
                        "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())",
                        null, options);
            }
        } catch (SQLException e) {
            return failure(e, t0, options);
        }
    }

    private void applySchema(Connection conn, String schema, SqlExecutionOptions options) throws SQLException {
        String schemaSql = dialect.currentSchemaSql(schema);
        if (schemaSql != null) {
            try (Statement s = conn.createStatement()) {
                var activation = options.control().activate(s, options.queryTimeoutSeconds());
                try {
                    options.control().ensureNotCancelled(activation);
                    s.execute(schemaSql);
                } finally {
                    options.control().release(activation);
                }
            }
        }
    }

    private static QueryResult failure(SQLException error, long startedAt, SqlExecutionOptions options) {
        long elapsed = System.currentTimeMillis() - startedAt;
        return options.control().cancellationRequested()
                ? QueryResult.cancelled(error.getMessage(), elapsed)
                : error instanceof SQLTimeoutException ? QueryResult.timeout(error.getMessage(), elapsed)
                : QueryResult.error(error.getMessage(), elapsed);
    }

    /** 剥离语句首尾空白与尾部分号（Oracle 单语句执行不接受尾分号）。 */
    private static String strip(String sql) {
        String s = sql.strip();
        // PL/SQL 块（CREATE ... PROCEDURE/PACKAGE/... 或 DECLARE/BEGIN）末尾 ; 是语法的一部分，保留
        if (isPlSqlBlock(s)) return s;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    private static boolean isPlSqlBlock(String sql) {
        var fragments = com.datacube.sqleditor.SqlScriptSplitter.fragments(sql, true);
        return fragments.size() == 1 && fragments.getFirst().procedural();
    }
}
