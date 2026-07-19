package com.datacube.provider.postgres;

import com.datacube.sqleditor.SqlScriptSplitter;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
    public QueryResult execute(Connection conn, String sql, String schema) {
        long t0 = System.currentTimeMillis();
        try {
            String schemaSql = dialect.currentSchemaSql(schema);
            if (schemaSql != null) {
                try (Statement s = conn.createStatement()) {
                    s.execute(schemaSql);
                }
            }
            try (Statement stmt = conn.createStatement()) {
                boolean hasResult = stmt.execute(sql);
                long elapsed = System.currentTimeMillis() - t0;
                if (hasResult) {
                    try (var rs = stmt.getResultSet()) {
                        java.sql.ResultSetMetaData md = rs.getMetaData();
                        QueryResult r = QueryResult.fromResultSet(rs, elapsed);
                        // best-effort 解析列注释；失败或无表列时返回 null，不影响结果展示
                        List<String> comments = PgColumnComments.resolve(conn, md);
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
    public List<ScriptOutcome> executeScript(Connection conn, String script, String schema) {
        List<String> stmts = SqlScriptSplitter.split(script);
        List<ScriptOutcome> outcomes = new ArrayList<>(stmts.size());
        for (int i = 0; i < stmts.size(); i++) {
            String sql = stmts.get(i);
            QueryResult r = execute(conn, sql, schema);
            outcomes.add(new ScriptOutcome(i + 1, sql, r));
            if (r.kind == QueryResult.Kind.ERROR) {
                // 遇错停止后续，避免连锁报错
                break;
            }
        }
        return outcomes;
    }
}
