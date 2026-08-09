package com.datacube.provider.postgres;

import com.datacube.sqleditor.SqlScriptSplitter;
import com.datacube.spi.SqlDialect;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ScriptOutcome;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
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
    public QueryResult execute(Connection conn, String sql, String schema, SqlExecutionOptions options) {
        long t0 = System.currentTimeMillis();
        try {
            applySchema(conn, schema, options);
            try (Statement stmt = conn.createStatement()) {
                var activation = options.control().activate(stmt, options.queryTimeoutSeconds());
                try {
                    options.control().ensureNotCancelled(activation);
                    boolean hasResult = stmt.execute(sql);
                    long elapsed = System.currentTimeMillis() - t0;
                    if (hasResult) {
                        try (var rs = stmt.getResultSet()) {
                            java.sql.ResultSetMetaData md = rs.getMetaData();
                            QueryResult r = QueryResult.fromResultSet(rs, elapsed, options.maxRows());
                            // best-effort 解析列注释；失败或无表列时返回 null，不影响结果展示
                            List<String> comments = PgColumnComments.resolve(conn, md);
                            return comments == null ? r : r.withColumnComments(comments);
                        }
                    } else {
                        return QueryResult.update(elapsed, stmt.getUpdateCount());
                    }
                } finally {
                    options.control().release(activation);
                }
            }
        } catch (SQLTimeoutException e) {
            return QueryResult.timeout(e.getMessage(), System.currentTimeMillis() - t0);
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - t0;
            return options.control().cancellationRequested()
                    ? QueryResult.cancelled(e.getMessage(), elapsed)
                    : QueryResult.error(e.getMessage(), elapsed);
        }
    }

    @Override
    public List<ScriptOutcome> executeScript(Connection conn, String script, String schema,
                                             SqlExecutionOptions options,
                                             ScriptErrorPolicy policy) {
        // PG 显式使用非 PL/SQL 模式：函数体靠 dollar-quote + ; 切分，行为与历史一致
        List<String> stmts = SqlScriptSplitter.split(script, false);
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
