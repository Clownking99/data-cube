package com.datacube.provider.oracle;

import com.datacube.provider.jdbc.JdbcPreparedQueryExecutor;
import com.datacube.provider.jdbc.JdbcDiagnostics;
import com.datacube.provider.jdbc.JdbcStatementLimits;
import com.datacube.sqleditor.SqlScriptSplitter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
        try {
            applySchema(conn, schema, options);
            try (Statement stmt = conn.createStatement()) {
                var activation = options.control().activate(stmt, options.queryTimeoutSeconds());
                try {
                    options.control().ensureNotCancelled(activation);
                    JdbcStatementLimits.apply(stmt, options.maxRows());
                    boolean hasResult = stmt.execute(strip(sql));
                    long elapsed = System.currentTimeMillis() - t0;
                    if (hasResult) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            ResultSetMetaData md = rs.getMetaData();
                            QueryResult r = QueryResult.fromResultSet(rs, elapsed, options.maxRows());
                            options.control().release(activation);
                            activation = null;
                            // best-effort 解析列注释；失败或无表列时返回 null，不影响结果展示
                            List<String> comments = OracleColumnComments.resolve(
                                    conn, md, sql, schema, options);
                            return comments == null ? r : r.withColumnComments(comments);
                        }
                    } else {
                        return QueryResult.update(elapsed, stmt.getUpdateCount());
                    }
                } finally {
                    if (activation != null) options.control().release(activation);
                }
            }
        } catch (SQLTimeoutException e) {
            return QueryResult.timeout(e.getMessage(), System.currentTimeMillis() - t0);
        } catch (SQLException e) {
            return failure(e, t0, options);
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
        } catch (SQLTimeoutException timeout) {
            return QueryResult.timeout(
                    JdbcDiagnostics.timeout(timeout), System.currentTimeMillis() - startedAt);
        } catch (SQLException failure) {
            long elapsed = System.currentTimeMillis() - startedAt;
            return options.control().cancellationRequested()
                    ? QueryResult.cancelled(JdbcDiagnostics.cancelled(failure), elapsed)
                    : QueryResult.error(JdbcDiagnostics.sqlFailure(failure), elapsed);
        }
    }

    @Override
    public List<ScriptOutcome> executeScript(Connection conn, String script, String schema,
                                             SqlExecutionOptions options,
                                             ScriptErrorPolicy policy) {
        List<String> stmts = SqlScriptSplitter.split(script, true);
        List<ScriptOutcome> outcomes = new ArrayList<>(stmts.size());
        boolean continueAll = false;
        for (int i = 0; i < stmts.size(); i++) {
            if (options.control().cancellationRequested()) break;
            String sql = stmts.get(i);
            QueryResult r = execute(conn, sql, schema, options);
            outcomes.add(new ScriptOutcome(i + 1, sql, r));
            if (r.failureKind == QueryResult.FailureKind.CANCELLED) break;
            if (r.kind == QueryResult.Kind.ERROR && !continueAll) {
                ScriptErrorPolicy.Decision d = policy == null
                        ? ScriptErrorPolicy.Decision.ABORT
                        : policy.onError(i + 1, sql, r.errorMessage);
                if (d == ScriptErrorPolicy.Decision.ABORT) break;
                if (d == ScriptErrorPolicy.Decision.CONTINUE_ALL) continueAll = true;
            }
            if (options.control().cancellationRequested()) break;
        }
        return outcomes;
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
        } catch (SQLTimeoutException e) {
            return QueryResult.timeout(e.getMessage(), System.currentTimeMillis() - t0);
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

    private static final Pattern PLSQL_BLOCK = Pattern.compile(
            "(?is)^(?:DECLARE|BEGIN|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:EDITIONABLE\\s+|NONEDITIONABLE\\s+)?"
                    + "(?:PROCEDURE|FUNCTION|PACKAGE\\s+BODY|PACKAGE|TRIGGER|TYPE\\s+BODY|TYPE))\\b.*");

    private static boolean isPlSqlBlock(String sql) {
        return PLSQL_BLOCK.matcher(sql).matches();
    }
}
