package com.datacube.provider.postgres;

import com.datacube.sqleditor.SqlScriptSplitter;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.ScriptErrorPolicy;
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
    public QueryResult execute(Connection conn, String sql, String schema, int maxRows) {
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
                        QueryResult r = QueryResult.fromResultSet(rs, elapsed, maxRows);
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
    public List<ScriptOutcome> executeScript(Connection conn, String script, String schema, int maxRows,
                                             ScriptErrorPolicy policy) {
        // PG 显式使用非 PL/SQL 模式：函数体靠 dollar-quote + ; 切分，行为与历史一致
        List<String> stmts = SqlScriptSplitter.split(script, false);
        List<ScriptOutcome> outcomes = new ArrayList<>(stmts.size());
        boolean continueAll = false;
        for (int i = 0; i < stmts.size(); i++) {
            String sql = stmts.get(i);
            QueryResult r = execute(conn, sql, schema, maxRows);
            outcomes.add(new ScriptOutcome(i + 1, sql, r));
            if (r.kind == QueryResult.Kind.ERROR && !continueAll) {
                ScriptErrorPolicy.Decision d = policy == null
                        ? ScriptErrorPolicy.Decision.ABORT
                        : policy.onError(i + 1, sql, r.errorMessage);
                if (d == ScriptErrorPolicy.Decision.ABORT) break;
                if (d == ScriptErrorPolicy.Decision.CONTINUE_ALL) continueAll = true;
            }
        }
        return outcomes;
    }

    @Override
    public QueryResult explain(Connection conn, String sql, String schema, boolean analyze) {
        // PG：单条 EXPLAIN [ANALYZE] <sql>，直接复用 execute（计划行数少，不限行）。
        return execute(conn, dialect.explainSql(sql, analyze), schema, 0);
    }
}
