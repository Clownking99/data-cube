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
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "INTO");

    private SafeSelectEligibility() {
    }

    public static Result check(String sql, boolean oracleMode, QueryResult result) {
        if (sql == null || result == null) return Result.rejected("SQL 与结果不能为空");
        List<String> statements = SqlScriptSplitter.split(sql, oracleMode);
        if (statements.size() != 1) return Result.rejected("仅支持单条 SELECT");

        String normalized = stripSingleTerminalSemicolon(statements.getFirst());
        TopLevelSqlTokens.Analysis analysis;
        try {
            analysis = TopLevelSqlTokens.analyze(normalized, oracleMode);
        } catch (IllegalArgumentException failure) {
            return Result.rejected("SQL 结构不能安全识别");
        }
        List<String> tokens = analysis.topLevelTokens();
        if (tokens.isEmpty() || !tokens.getFirst().equals("SELECT")) {
            return Result.rejected("仅支持只读 SELECT");
        }
        if (analysis.unsafeStructure() || tokens.stream().anyMatch(UNSAFE_TOP_LEVEL::contains)) {
            return Result.rejected("该 SELECT 结构不能安全包装");
        }
        if (analysis.unprovenCallable()) {
            return Result.rejected("该 SELECT 包含无法证明安全的调用");
        }
        if (!oracleMode && !analysis.postgresNativeLiteralSelect()) {
            return Result.rejected(
                    "该 PostgreSQL SELECT 超出可证明安全的无 FROM 基础字面量子集；本地筛选仍可使用");
        }
        if (oracleMode && !analysis.oracleTrustedSysDualSelect()) {
            return Result.rejected(
                    "该 Oracle SELECT 超出可证明安全的 SYS.DUAL 通配符子集；本地筛选仍可使用");
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
