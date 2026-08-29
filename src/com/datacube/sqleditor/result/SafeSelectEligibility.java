package com.datacube.sqleditor.result;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.SqlScriptSplitter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Proves the conservative subset of SELECT statements that may be wrapped for filtering. */
public final class SafeSelectEligibility {
    private static final Set<String> UNSAFE_TOP_LEVEL = Set.of(
            "WITH", "UNION", "INTERSECT", "EXCEPT", "MINUS", "INTO");

    private SafeSelectEligibility() {
    }

    public static Result check(String sql, boolean oracleMode, QueryResult result) {
        if (sql == null || result == null) return Result.rejected("SQL 与结果不能为空");
        List<String> statements = SqlScriptSplitter.split(sql, oracleMode);
        if (statements.size() != 1) return Result.rejected("仅支持单条 SELECT");

        String normalized = stripSingleTerminalSemicolon(statements.getFirst());
        List<String> tokens;
        try {
            tokens = TopLevelSqlTokens.scan(normalized, oracleMode);
            if (TopLevelSqlTokens.containsKnownSideEffectInvocation(normalized, oracleMode)) {
                return Result.rejected("该 SELECT 包含不能安全执行的调用");
            }
        } catch (IllegalArgumentException failure) {
            return Result.rejected("SQL 结构不能安全识别");
        }
        if (tokens.isEmpty() || !tokens.getFirst().equals("SELECT")) {
            return Result.rejected("仅支持只读 SELECT");
        }
        if (tokens.stream().anyMatch(UNSAFE_TOP_LEVEL::contains) || containsLockClause(tokens)) {
            return Result.rejected("该 SELECT 结构不能安全包装");
        }
        if (result.kind != QueryResult.Kind.QUERY || hasDuplicateOrBlankLabels(result.resultColumns)) {
            return Result.rejected("结果列名必须唯一，请在原 SQL 中添加别名");
        }
        return Result.allowed(normalized);
    }

    private static String stripSingleTerminalSemicolon(String sql) {
        String normalized = sql.strip();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).stripTrailing();
        }
        return normalized;
    }

    private static boolean containsLockClause(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).equals("FOR")) continue;
            int end = Math.min(tokens.size(), index + 5);
            for (int next = index + 1; next < end; next++) {
                String token = tokens.get(next);
                if (token.equals("UPDATE") || token.equals("SHARE")) return true;
            }
        }
        return false;
    }

    private static boolean hasDuplicateOrBlankLabels(List<ResultColumn> columns) {
        if (columns.isEmpty()) return true;
        Set<String> labels = new HashSet<>();
        for (ResultColumn column : columns) {
            if (column == null || column.label().isBlank() || !labels.add(column.label())) return true;
        }
        return false;
    }

    public record Result(boolean eligible, String normalizedSql, String reason) {
        private static Result allowed(String normalizedSql) {
            return new Result(true, normalizedSql, "");
        }

        private static Result rejected(String reason) {
            return new Result(false, "", reason);
        }
    }
}
