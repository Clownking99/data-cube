package com.datacube.sqleditor;

import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将词法分析结果映射为连接环境的执行决策。 */
public final class SqlSafetyPolicy {
    private SqlSafetyPolicy() {}

    public record Decision(
            boolean blocked,
            boolean confirmationRequired,
            List<SqlSafetyAnalyzer.StatementAnalysis> relevantStatements,
            String message) {
        public Decision {
            relevantStatements = List.copyOf(relevantStatements);
        }
    }

    public static Decision decide(SqlSafetyAnalyzer.ScriptAnalysis analysis,
                                  ConnectionSafetyOptions options) {
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(options, "options");

        List<SqlSafetyAnalyzer.StatementAnalysis> blocked = new ArrayList<>();
        boolean hasSessionConflict = false;
        for (SqlSafetyAnalyzer.StatementAnalysis statement : analysis.statements()) {
            boolean sessionConflict = statement.risks().contains(
                    SqlSafetyAnalyzer.Risk.SESSION_STATE_CONFLICT);
            boolean readOnlyViolation = options.readOnly()
                    && statement.kind() != SqlSafetyAnalyzer.StatementKind.READ
                    && !isCommitOrRollback(statement);
            if (sessionConflict) hasSessionConflict = true;
            if (sessionConflict || readOnlyViolation) blocked.add(statement);
        }
        if (!blocked.isEmpty()) {
            return new Decision(true, false, blocked, hasSessionConflict
                    ? "SQL 会话状态与安全执行会话冲突，已阻止执行"
                    : "只读连接不允许执行非只读语句");
        }

        List<SqlSafetyAnalyzer.StatementAnalysis> confirmations = matching(analysis, statement ->
                statement.risks().contains(SqlSafetyAnalyzer.Risk.MISSING_WHERE)
                        || statement.risks().contains(SqlSafetyAnalyzer.Risk.DESTRUCTIVE_DDL)
                        || options.environment() == ConnectionEnvironment.PRODUCTION
                        && requiresProductionConfirmation(statement));
        if (!confirmations.isEmpty()) {
            return new Decision(false, true, confirmations,
                    "检测到需要确认的 SQL 风险");
        }

        return new Decision(false, false, List.of(), "");
    }

    private static boolean isCommitOrRollback(SqlSafetyAnalyzer.StatementAnalysis statement) {
        return statement.kind() == SqlSafetyAnalyzer.StatementKind.TRANSACTION_CONTROL
                && !statement.risks().contains(SqlSafetyAnalyzer.Risk.SESSION_STATE_CONFLICT)
                && ("COMMIT".equals(statement.firstKeyword())
                || "ROLLBACK".equals(statement.firstKeyword()));
    }

    private static boolean requiresProductionConfirmation(
            SqlSafetyAnalyzer.StatementAnalysis statement) {
        return statement.kind() == SqlSafetyAnalyzer.StatementKind.WRITE
                || statement.kind() == SqlSafetyAnalyzer.StatementKind.DDL
                || statement.kind() == SqlSafetyAnalyzer.StatementKind.UNKNOWN;
    }

    private static List<SqlSafetyAnalyzer.StatementAnalysis> matching(
            SqlSafetyAnalyzer.ScriptAnalysis analysis,
            java.util.function.Predicate<SqlSafetyAnalyzer.StatementAnalysis> predicate) {
        List<SqlSafetyAnalyzer.StatementAnalysis> matches = new ArrayList<>();
        for (SqlSafetyAnalyzer.StatementAnalysis statement : analysis.statements()) {
            if (predicate.test(statement)) matches.add(statement);
        }
        return matches;
    }
}
