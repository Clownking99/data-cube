package com.datacube.sqleditor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对 SQL 脚本做保守的顶层词法风险分析。
 *
 * <p>这不是完整 SQL 解析器；它只识别安全策略所需的顶层关键字，并跳过字符串、
 * 注释、PostgreSQL dollar quote、Oracle q quote 以及括号中的内容。
 */
public final class SqlSafetyAnalyzer {
    private static final Set<String> READ_KEYWORDS = Set.of(
            "SELECT", "SHOW", "DESCRIBE", "DESC", "VALUES", "TABLE");
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE",
            "CALL", "DO", "EXEC", "EXECUTE", "DECLARE");
    private static final Set<String> DDL_KEYWORDS = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "GRANT", "REVOKE", "RENAME");
    private static final Set<String> TRANSACTION_KEYWORDS = Set.of(
            "BEGIN", "START", "COMMIT", "ROLLBACK", "SET", "SAVEPOINT", "RELEASE");
    private static final Set<String> EXPLAINABLE_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "VALUES", "TABLE");
    private static final Set<String> CTE_COMMAND_KEYWORDS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE");

    private SqlSafetyAnalyzer() {}

    public enum StatementKind { READ, WRITE, DDL, TRANSACTION_CONTROL, UNKNOWN }

    public enum Risk { MISSING_WHERE, DESTRUCTIVE_DDL, UNKNOWN_STATEMENT, SESSION_STATE_CONFLICT }

    public record StatementAnalysis(
            int index, String sql, String firstKeyword, StatementKind kind, Set<Risk> risks) {
        public StatementAnalysis {
            risks = Set.copyOf(risks);
        }
    }

    public record ScriptAnalysis(List<StatementAnalysis> statements) {
        public ScriptAnalysis {
            statements = List.copyOf(statements);
        }
    }

    public static ScriptAnalysis analyze(String script, boolean oracleMode) {
        List<String> statements = SqlScriptSplitter.split(script, oracleMode);
        List<StatementAnalysis> analyses = new ArrayList<>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            analyses.add(analyzeStatement(i + 1, statements.get(i)));
        }
        return new ScriptAnalysis(analyses);
    }

    private static StatementAnalysis analyzeStatement(int index, String sql) {
        List<Token> tokens = topLevelTokens(sql);
        String first = tokens.isEmpty() ? "" : tokens.getFirst().word();
        String effective = effectiveKeyword(tokens);
        StatementKind kind = classify(effective, tokens);
        EnumSet<Risk> risks = EnumSet.noneOf(Risk.class);
        if (kind == StatementKind.UNKNOWN) risks.add(Risk.UNKNOWN_STATEMENT);
        if (("UPDATE".equals(effective) || "DELETE".equals(effective))
                && tokens.stream().noneMatch(token -> "WHERE".equals(token.word()))) {
            risks.add(Risk.MISSING_WHERE);
        }
        if ("DROP".equals(effective) || "TRUNCATE".equals(effective)) {
            risks.add(Risk.DESTRUCTIVE_DDL);
        }
        if (Set.of("BEGIN", "START", "SET", "SAVEPOINT", "RELEASE").contains(effective)
                && kind == StatementKind.TRANSACTION_CONTROL) {
            risks.add(Risk.SESSION_STATE_CONFLICT);
        }
        return new StatementAnalysis(index, sql, first, kind, risks);
    }

    private static String effectiveKeyword(List<Token> tokens) {
        if (tokens.isEmpty()) return "";
        String first = tokens.getFirst().word();
        if ("WITH".equals(first)) return commandAfterWith(tokens, 1);
        if (!"EXPLAIN".equals(first)) return first;

        for (int i = 1; i < tokens.size(); i++) {
            String word = tokens.get(i).word();
            if (!EXPLAINABLE_KEYWORDS.contains(word)) continue;
            return "WITH".equals(word) ? commandAfterWith(tokens, i + 1) : word;
        }
        return "EXPLAIN";
    }

    private static String commandAfterWith(List<Token> tokens, int start) {
        for (int i = start; i < tokens.size(); i++) {
            String word = tokens.get(i).word();
            if (CTE_COMMAND_KEYWORDS.contains(word)) return word;
        }
        return "WITH";
    }

    private static StatementKind classify(String effective, List<Token> tokens) {
        if (READ_KEYWORDS.contains(effective) || "EXPLAIN".equals(effective)) {
            return StatementKind.READ;
        }
        if (WRITE_KEYWORDS.contains(effective)) return StatementKind.WRITE;
        if (DDL_KEYWORDS.contains(effective)) return StatementKind.DDL;
        if (TRANSACTION_KEYWORDS.contains(effective)) {
            if ("BEGIN".equals(effective) && !isStandaloneBegin(tokens)) return StatementKind.WRITE;
            return StatementKind.TRANSACTION_CONTROL;
        }
        return StatementKind.UNKNOWN;
    }

    private static boolean isStandaloneBegin(List<Token> tokens) {
        if (tokens.size() == 1) return true;
        return tokens.size() == 2
                && ("WORK".equals(tokens.get(1).word()) || "TRANSACTION".equals(tokens.get(1).word()));
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, LINE_COMMENT, BLOCK_COMMENT,
        DOLLAR_QUOTE, ORACLE_Q_QUOTE
    }

    private static List<Token> topLevelTokens(String sql) {
        List<Token> tokens = new ArrayList<>();
        State state = State.NORMAL;
        int depth = 0;
        int blockCommentDepth = 0;
        String dollarDelimiter = null;
        char oracleClose = 0;

        for (int i = 0; i < sql.length();) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            switch (state) {
                case NORMAL -> {
                    if (current == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        i += 2;
                    } else if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        blockCommentDepth = 1;
                        i += 2;
                    } else if (current == '\'') {
                        state = State.SINGLE_QUOTE;
                        i++;
                    } else if (current == '"') {
                        state = State.DOUBLE_QUOTE;
                        i++;
                    } else if ((current == 'q' || current == 'Q') && next == '\''
                            && i + 2 < sql.length()) {
                        oracleClose = oracleClosingDelimiter(sql.charAt(i + 2));
                        state = State.ORACLE_Q_QUOTE;
                        i += 3;
                    } else {
                        String delimiter = dollarDelimiterAt(sql, i);
                        if (delimiter != null) {
                            dollarDelimiter = delimiter;
                            state = State.DOLLAR_QUOTE;
                            i += delimiter.length();
                        } else if (current == '(') {
                            depth++;
                            i++;
                        } else if (current == ')') {
                            if (depth > 0) depth--;
                            i++;
                        } else if (depth == 0 && isWordStart(current)) {
                            int start = i++;
                            while (i < sql.length() && isWordPart(sql.charAt(i))) i++;
                            tokens.add(new Token(sql.substring(start, i).toUpperCase(Locale.ROOT)));
                        } else {
                            i++;
                        }
                    }
                }
                case SINGLE_QUOTE -> {
                    if (current == '\'' && next == '\'') {
                        i += 2;
                    } else if (current == '\'') {
                        state = State.NORMAL;
                        i++;
                    } else {
                        i++;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (current == '"' && next == '"') {
                        i += 2;
                    } else if (current == '"') {
                        state = State.NORMAL;
                        i++;
                    } else {
                        i++;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') state = State.NORMAL;
                    i++;
                }
                case BLOCK_COMMENT -> {
                    if (current == '/' && next == '*') {
                        blockCommentDepth++;
                        i += 2;
                    } else if (current == '*' && next == '/') {
                        blockCommentDepth--;
                        i += 2;
                        if (blockCommentDepth == 0) state = State.NORMAL;
                    } else {
                        i++;
                    }
                }
                case DOLLAR_QUOTE -> {
                    if (sql.startsWith(dollarDelimiter, i)) {
                        i += dollarDelimiter.length();
                        state = State.NORMAL;
                    } else {
                        i++;
                    }
                }
                case ORACLE_Q_QUOTE -> {
                    if (current == oracleClose && next == '\'') {
                        i += 2;
                        state = State.NORMAL;
                    } else {
                        i++;
                    }
                }
            }
        }
        return tokens;
    }

    private static String dollarDelimiterAt(String sql, int offset) {
        if (sql.charAt(offset) != '$') return null;
        int i = offset + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        if (i >= sql.length() || sql.charAt(i) != '$') return null;
        if (i > offset + 1 && Character.isDigit(sql.charAt(offset + 1))) return null;
        return sql.substring(offset, i + 1);
    }

    private static char oracleClosingDelimiter(char opening) {
        return switch (opening) {
            case '[' -> ']';
            case '(' -> ')';
            case '{' -> '}';
            case '<' -> '>';
            default -> opening;
        };
    }

    private static boolean isWordStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isWordPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private record Token(String word) {}
}
