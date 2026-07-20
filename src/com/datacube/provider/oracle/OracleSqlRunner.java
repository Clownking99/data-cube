package com.datacube.provider.oracle;

import com.datacube.sqleditor.SqlScriptSplitter;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
    public QueryResult execute(Connection conn, String sql, String schema, int maxRows) {
        long t0 = System.currentTimeMillis();
        try {
            applySchema(conn, schema);
            try (Statement stmt = conn.createStatement()) {
                boolean hasResult = stmt.execute(strip(sql));
                long elapsed = System.currentTimeMillis() - t0;
                if (hasResult) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        QueryResult r = QueryResult.fromResultSet(rs, elapsed, maxRows);
                        // best-effort 解析列注释；失败或无表列时返回 null，不影响结果展示
                        List<String> comments = OracleColumnComments.resolve(conn, md);
                        return comments == null ? r : r.withColumnComments(comments);
                    }
                } else {
                    return QueryResult.update(elapsed, stmt.getUpdateCount());
                }
            }
        } catch (SQLException e) {
            return QueryResult.error(e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    @Override
    public List<ScriptOutcome> executeScript(Connection conn, String script, String schema, int maxRows) {
        List<String> stmts = SqlScriptSplitter.split(script);
        List<ScriptOutcome> outcomes = new ArrayList<>(stmts.size());
        for (int i = 0; i < stmts.size(); i++) {
            String sql = stmts.get(i);
            QueryResult r = execute(conn, sql, schema, maxRows);
            outcomes.add(new ScriptOutcome(i + 1, sql, r));
            if (r.kind == QueryResult.Kind.ERROR) {
                // 遇错停止后续，避免连锁报错
                break;
            }
        }
        return outcomes;
    }

    @Override
    public QueryResult explain(Connection conn, String sql, String schema, boolean analyze) {
        long t0 = System.currentTimeMillis();
        String stmt = strip(sql);
        try {
            applySchema(conn, schema);
            if (analyze) {
                try (Statement s = conn.createStatement()) {
                    s.execute("ALTER SESSION SET STATISTICS_LEVEL = ALL");
                }
                // 实际执行以采集运行时统计（消费结果集）
                try (Statement s = conn.createStatement()) {
                    boolean has = s.execute(stmt);
                    if (has) {
                        try (ResultSet rs = s.getResultSet()) {
                            while (rs.next()) { /* drain */ }
                        }
                    }
                }
                return execute(conn,
                        "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))",
                        null, 0);
            } else {
                try (Statement s = conn.createStatement()) {
                    s.execute("EXPLAIN PLAN FOR " + stmt);
                }
                return execute(conn,
                        "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())",
                        null, 0);
            }
        } catch (SQLException e) {
            return QueryResult.error(e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    private void applySchema(Connection conn, String schema) throws SQLException {
        String schemaSql = dialect.currentSchemaSql(schema);
        if (schemaSql != null) {
            try (Statement s = conn.createStatement()) {
                s.execute(schemaSql);
            }
        }
    }

    /** 剥离语句首尾空白与尾部分号（Oracle 单语句执行不接受尾分号）。 */
    private static String strip(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }
}
