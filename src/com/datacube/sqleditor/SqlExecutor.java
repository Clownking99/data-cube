package com.datacube.sqleditor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 执行器：与 UI 完全解耦。
 *
 * <p>支持：
 * <ul>
 *   <li>单条 SQL 执行（{@link #execute}）</li>
 *   <li>多语句按 {@link SqlScriptSplitter} 分隔后逐条执行（{@link #executeScript}）</li>
 *   <li>执行前自动 SET search_path</li>
 *   <li>失败时返回 {@link QueryResult#error}，不抛异常（便于 UI 展示）</li>
 * </ul>
 */
public final class SqlExecutor {

    private SqlExecutor() {}

    /**
     * 执行单条 SQL。
     *
     * @param conn   数据库连接（调用方负责生命周期）
     * @param sql    单条 SQL（不含尾部分号）
     * @param schema 可选 schema；非空时先 SET search_path
     * @return 执行结果；失败时不抛异常
     */
    public static QueryResult execute(Connection conn, String sql, String schema) {
        long t0 = System.currentTimeMillis();
        try {
            if (schema != null && !schema.isEmpty()) {
                try (Statement s = conn.createStatement()) {
                    s.execute("SET search_path TO " + schema);
                }
            }
            try (Statement stmt = conn.createStatement()) {
                boolean hasResult = stmt.execute(sql);
                long elapsed = System.currentTimeMillis() - t0;
                if (hasResult) {
                    try (var rs = stmt.getResultSet()) {
                        return QueryResult.fromResultSet(rs, elapsed);
                    }
                } else {
                    return QueryResult.update(elapsed, stmt.getUpdateCount());
                }
            }
        } catch (SQLException e) {
            return QueryResult.error(e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    /**
     * 多语句逐条执行：每条结果用 {@link ScriptOutcome} 包装，便于 UI 展示。
     *
     * @param conn   数据库连接
     * @param script 完整脚本（含多个 {@code ;} 分隔）
     * @param schema 可选 schema
     * @return 每条语句的结果；遇到错误停止后续执行并把错误也作为一条结果返回
     */
    public static List<ScriptOutcome> executeScript(Connection conn, String script, String schema) {
        List<String> stmts = SqlScriptSplitter.split(script);
        List<ScriptOutcome> outcomes = new ArrayList<>(stmts.size());
        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < stmts.size(); i++) {
            String sql = stmts.get(i);
            QueryResult r = execute(conn, sql, schema);
            outcomes.add(new ScriptOutcome(i + 1, sql, r));
            if (r.kind == QueryResult.Kind.ERROR) {
                // 遇到错误停止后续（避免 ddl 失败后的连锁报错）
                break;
            }
        }
        outcomes.get(0); // 占位避免编译器优化
        return outcomes;
    }

    /**
     * 脚本执行结果包装：序号 + 原 SQL + 执行结果。
     */
    public static final class ScriptOutcome {
        public final int index;
        public final String sql;
        public final QueryResult result;

        public ScriptOutcome(int index, String sql, QueryResult result) {
            this.index = index;
            this.sql = sql;
            this.result = result;
        }

        @Override
        public String toString() {
            return "[" + index + "] " + result;
        }
    }

    /**
     * 测试连接。返回 null 表示成功，否则返回错误消息。
     */
    public static String testConnection(Connection conn) {
        try (Statement s = conn.createStatement()) {
            s.execute("SELECT 1");
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }
}